# WorldExplorer bot — Plan A: fix wall-following (boundary-hugging) redesign

> **Status: historical, snapshot-era planning document — not a description of current or final behavior.**
> This document predates the final implementation and does not describe it. The snapshot-era `WorldExplorer`
> that actually shipped evolved into a separate `CoastFollower`/`TileField` distance-field design, not the
> boundary-hugging fix specified below. **Do not treat this document as a literal implementation
> specification.** For current, code-confirmed behavior, see
> [`docs/world-explorer-system.md`](world-explorer-system.md).

Standalone implementation plan. Self-contained — no other document needed to act on this.

See also: [world-explorer-plan-b-frontier-exploration.md](world-explorer-plan-b-frontier-exploration.md) — an alternative, higher-effort redesign considered alongside this one. Not chosen yet; either can be implemented independently.

## Context

`WorldExplorer` (`src/nurgling/actions/bots/WorldExplorer.java`) drives a boat along a water-tile-type boundary (e.g. shallow-ocean/deep-ocean) so the player auto-explores coastline by hugging it clockwise or counterclockwise. Three concrete problems were reported:

1. **Jerky, slow travel** — one tile per click, full stop between every click, no benefit from boat momentum.
2. **Gets stuck on shore obstacles** (e.g. clam reefs) with no detection or recovery — keeps retrying the same blocked click forever.
3. **Infinite 180° flip at river mouths** — bot oscillates between one ocean tile and one freshwater tile forever.

Root causes were confirmed by direct code reading (not guessed), cross-checked against `src/nurgling/pf/NPFMap.java`'s existing water-safety logic, which already solves a version of the same tile-classification problem for its own pathfinder.

## Root causes (confirmed)

**Bug 1 — jerky motion**: `GoTo.java` (`src/nurgling/actions/GoTo.java`) issues one click then blocks on `MovingCompleted`/`MovingCompletedBySpeed` (`src/nurgling/tasks/MovingCompleted.java`, `MovingCompletedBySpeed.java`), which wait for the player's pose to **fully stop** (cap 1000 ticks) before returning. `WorldExplorer` calls `GoTo` once per single adjacent tile. Result: click → wait-full-stop → click → wait-full-stop, never chaining while still moving.

**Bug 2 — river-mouth infinite loop**: `WorldExplorer.java` lines 44-45:
```java
String targetTile = "odeep";
String nearestTile = (prop.deeper)?"odeeper":"owater";
```
`targetTile` is **always** `"odeep"` regardless of the "Deep and Shallow" vs "Deep and Deeper" checkbox — matching is `Resource.name.endsWith(targetTile)`. Real tile resource names (confirmed via `NPFMap.java` lines 308-316, resource prefix is `gfx/tiles/`, not `gfx/tile/`) are `gfx/tiles/water`, `gfx/tiles/deep` (freshwater shallow/deep) and `gfx/tiles/owater`, `gfx/tiles/odeep`, `gfx/tiles/odeeper` (ocean shallow/deep/deeper). `"gfx/tiles/deep".endsWith("odeep")` is `false` (char before "deep" is `/` not `o`) — so the bot **never** matches freshwater tiles, despite "Deep and Shallow" implying it should apply to both. At a river mouth the boundary alternates between ocean and freshwater safe tiles; when neither satisfies the literal ocean-only match, the main loop's fallback (`WorldExplorer.java` lines 128-131) just clicks back to the previous tile — **every tick, forever** — exactly the reported flip-flop. `NPFMap.java` lines 308-316 already treats `gfx/tiles/water`/`owater`/`deep`/`odeep` as one interchangeable safe set for its own boat pathfinder (used in `waterMode`) — that's the established precedent to follow. Note: that same NPFMap logic does **not** include `odeeper` as passable — relevant for Plan B, not this plan.

**Bug 3 — no stuck detection**: only a 100-slot ring buffer of recently-visited tile *coordinates* (prevents instant re-visit) — nothing checks actual world-position progress or physical blockage by a Gob.

## Design

### 1. New file `src/nurgling/pf/WaterTiles.java` — unified tile classification
```java
public class WaterTiles {
    public static boolean isShallow(String name); // startsWith("gfx/tiles/water") || startsWith("gfx/tiles/owater")
    public static boolean isDeep(String name);     // equals("gfx/tiles/deep") || equals("gfx/tiles/odeep")
    public static boolean isDeeper(String name);   // equals("gfx/tiles/odeeper")  -- ocean-only, no freshwater tier
    public static boolean isSafe(String name, boolean deeperMode); // deeperMode: shallow||deep||deeper ; else: shallow||deep
}
```
`startsWith` for shallow mirrors `NPFMap`'s handling of numbered water variants; `equals` for deep/deeper since no variants exist. This is the fix for bug 2: "Deep and Shallow" mode now hugs the deep/shallow boundary using **either** fresh or salt water tiles, so a river mouth becomes a continuous safe boundary, not a dead zone. `isSafe` is also the hard gate every candidate tile must pass before any click — enforces "never enter odeeper (Deep&Shallow mode) or land."

### 2. New file `src/nurgling/actions/bots/WorldExplorerMove.java` — momentum-preserving movement
- `Coord scanAhead(Coord pltc, Coord dir, Coord[] neardirs, boolean deeperMode, int maxLookahead)` — walks up to `maxLookahead` tiles along the current heading, checking `WaterTiles.isSafe` + the boundary condition at each step, returns the farthest still-valid tile (falls back to 1 tile if even that fails). This is what lets the boat move many tiles per click instead of one.
- `Results clickAndChase(Coord2d target, NGameUI gui)` — same click/cursor/vehicle-dispatch logic as `GoTo.java` (lines 25-65: handles the `Following` attribute for horse/dugout/coracle/skis/rowboat/snekkja), but swaps every full-stop wait for a motion-confirmed-or-close-enough wait: `IsMoving(target, 20)` (`src/nurgling/tasks/IsMoving.java`) for pose-based vehicles, `IsMovingBySpeed(target, gob, 20)` (`src/nurgling/tasks/IsMovingBySpeed.java`) for `snekkja`. **Both classes already exist and already support exactly this non-blocking semantics** — confirmed by reading them: both return `true` once within `pfmdelta` (`PathFinder.pfmdelta = 1.5`) of target, OR once the relevant pose/speed indicates motion has started, OR unconditionally after the `th` tick cap passed to the constructor (so it can never block forever; `th=20` mirrors what `DynamicPf.java` already uses for exactly this purpose). **No new task classes are needed.**
- **Do not modify `GoTo.java`** — it's used broadly elsewhere in the codebase; changing its blocking contract is out of scope and risky. `WorldExplorerMove` is a parallel, bot-specific mover, modeled on the existing chained-click pattern already proven in `src/nurgling/actions/DynamicPf.java` (lines 78-115).

### 3. New file `src/nurgling/actions/bots/WorldExplorerStuck.java` — stuck detection + recovery
- **Detection**: track `lastTile` + `lastProgressTime` (via `Utils.rtime()`). Each loop tick, if current tile differs from `lastTile`, reset timer; if unchanged for **>`stuckTimeoutS` seconds** (default 2.0 — same threshold precedent as `src/nurgling/WaypointMovementService.java` lines 92-107, which already does a >2.0s no-progress check for the minimap click-to-move stuck case), declare stuck. This one signal covers both the river-mouth-style flip-flop and a physical obstacle block — both are "no net progress."
- **Recovery** (`recover(Coord pltc, Coord recentHeading, Coord[] dirs, ExplorerTunables cfg)`), implementing the user's requested "back up, then swing wide" maneuver:
  1. Back up `cfg.backupTiles` (default 2) roughly opposite `recentHeading` — try straight-back first, then a slight-angle variant if that tile isn't `isSafe` — via `WorldExplorerMove.clickAndChase`. Ensures the retry isn't blocked by the same obstacle.
  2. From the backed-up tile, check both perpendicular directions for `isSafe`; pick the side that's open, preferring the side matching the configured hug direction (clockwise/counterclockwise) when both are open, so the bot re-enters its normal orientation afterward.
  3. Swing `cfg.swingTiles` (default 4-5) laterally on that side via `WorldExplorerMove.scanAhead`, each tile gated by `isSafe`, then resume normal boundary scanning.
  - **Escalation cap** (per user's decision — abort rather than retry forever): if recovery fails to produce progress twice in a row, widen the swing once (e.g. `cfg.swingTiles + 3`); a third consecutive failure aborts the bot with `Results.ERROR("Stuck: unable to route around obstacle")`.

### 4. Rewrite `src/nurgling/actions/bots/WorldExplorer.java`
- Keep the existing `clockwise`/`counterclockwise`/`nearest`/`counternearest` direction geometry (it's sound), but:
  - Replace lines 44-45 hardcoded strings with `WaterTiles` calls.
  - Reorder direction scanning each tick to a heading-relative priority (hug-side first, then straight, then away, then reverse) instead of a fixed absolute-compass order — reduces zigzag and lets `scanAhead` build longer straight runs.
  - Replace the acquisition loop and main loop with: `WorldExplorerMove.scanAhead` → `clickAndChase` → check `WorldExplorerStuck` each tick → `WorldExplorerStuck.recover` on trigger.
  - Drop the 100-slot anti-revisit ring buffer (superseded by proper stuck detection) unless manual testing shows it's still needed for legitimate tight-curve back-and-forth.

### 5. UI — expose tunables (per user's explicit choice)
Add to `src/nurgling/conf/NWorldExplorerProp.java`: new fields `lookaheadTiles` (default 6), `stuckTimeoutS` (default 2.0), `backupTiles` (default 2), `swingTiles` (default 5) — persisted/loaded in `toJson()`/the `HashMap` constructor exactly like the existing `clockwise`/`deeper` fields (lines 29-32, 73-74).

Add to `src/nurgling/widgets/bots/WorldExplorerWnd.java`: four `TextEntry` fields (pattern already used elsewhere in this codebase for numeric bot settings, e.g. `src/nurgling/widgets/bots/Carrier.java:16`, `AutoFlowerAction.java:34` — `new TextEntry(width, defaultValueString)`), one per tunable, added below the existing checkboxes and above the Start button (current Start button logic at lines 78-90), parsed with `Integer.parseInt`/`Double.parseDouble` (with a sane fallback to the default on parse failure) when the Start button is clicked, written into the `prop` object alongside the existing `prop.deeper`/`prop.clockwise` assignment (line 84-85).

## Verification

No test harness in this repo (per project convention) — verification is `ant jar` for compilation, then manual in-client testing:
- Ocean-only coastline, both modes (Deep&Shallow, Deep&Deeper) — confirm smoother/faster travel, never enters odeeper/land.
- A known river mouth — confirm no infinite flip.
- Deliberately encounter a shore obstacle (e.g. clam reef) — confirm back-up+swing-wide recovery fires and bot resumes.
- Extended run (several in-game minutes) — confirm no stuck-loop spam and no land/deep-water intrusion.
- Confirm the new UI fields round-trip correctly (set non-default values, restart the window, values persist).

## Files touched
- `src/nurgling/pf/WaterTiles.java` (new)
- `src/nurgling/actions/bots/WorldExplorerMove.java` (new)
- `src/nurgling/actions/bots/WorldExplorerStuck.java` (new)
- `src/nurgling/actions/bots/WorldExplorer.java` (rewrite)
- `src/nurgling/conf/NWorldExplorerProp.java` (add 4 tunable fields)
- `src/nurgling/widgets/bots/WorldExplorerWnd.java` (add 4 TextEntry fields)
- `src/nurgling/actions/GoTo.java` — reference only, not modified