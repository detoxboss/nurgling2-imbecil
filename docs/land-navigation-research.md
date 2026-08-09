# Land navigation research (future land-based World Explorer)

**Status: recovery/proposal — not current implementation.** This document preserves research and
evidence recovered from rescue snapshot `802a885500a76b68697fb6450bd35bcc82355cab` about a *possible
future* land-based long-distance exploration bot.

**Precise reconstruction status** (do not simplify to "nothing is implemented anywhere" — that
contradicts §7/§8 below):

- The cliff-specific group — `CliffScan.java`, `CliffAwareMove.java`, `CliffCalibrate.java`, and the
  `DynamicPf.cliffAware` opt-in diff — exists **only** in rescue snapshot `802a8855`. None of it has
  been reconstructed on `master` or on `feat/world-explorer-reconstruction`, and it is not implemented
  anywhere in this repository today.
- `FrontierPicker` and `StuckDetector` **were restored, unchanged, by the World Explorer reconstruction**
  (branch `feat/world-explorer-reconstruction`, commit `a8070ebb1`) — as part of the water bot's own
  core-file set (shared/dormant infrastructure the water bot carries), not as a land-bot implementation.
  See §7/§8 for exactly what each restored class does and does not do.
- **No land explorer, and no land-specific consumer of either restored class, exists anywhere.**
  Restoration as part of the water bot does not constitute land-navigation behavior — nothing in this
  document is implemented as land-navigation behavior anywhere.

**This is a separate, future, proposed bot with its own future menu entry.** It is not a mode,
extension, variant, or dependency of the restored water-based shore-tracing World Explorer bot. See
[`world-explorer-system.md`](world-explorer-system.md)'s scope-boundary note. Do not apply anything in
this document to the water bot without separate, explicit approval — see
[`.claude/rules/land-navigation.md`](../.claude/rules/land-navigation.md) for the enforcement of that
boundary.

## Evidence tiers used throughout this document

Every claim below is tagged with one of these four tiers. Do not upgrade a claim's tier when citing it
elsewhere — the tier is load-bearing information, not decoration.

- **[User-reported]** — described by the user from in-game observation. Not independently measured or
  re-verified against server behavior in this pass.
- **[Snapshot-era tested]** — the snapshot-era code comments claim this was measured or verified during
  real testing at the time the snapshot code was written. That testing predates this document and has
  not been repeated. Treat as historical evidence the design worked once, not current verification.
- **[Current source-confirmed]** — independently re-verified against current `master` source in this
  pass (file/line references given).
- **[Proposal / open decision]** — not implemented anywhere, not measured, a design option or a
  decision explicitly left to the user.

## 1. Goal

**[Proposal]** The future land bot's goal is autonomous long-distance land exploration through terrain
that may not yet be loaded, discovered, or recorded — i.e. genuinely unexplored overland travel, not
routing across terrain the client (or a persisted database) already fully knows.

## 2. Intended direction: rolling-horizon navigation, not pre-recorded-data-dependent

**[Proposal]** Pre-recorded `ChunkNav` data must **not** be a prerequisite for this bot to function.
`ChunkNav` (`src/nurgling/navigation/**`, current `master`) is an opt-in, persisted, background-recorded
navigation database (`NConfig.Key.chunkNavOverlay`, default off — see
`docs/world-explorer-plan-b-frontier-exploration.md`'s "What ChunkNav and ExploredArea actually provide"
section for the code-read investigation of what it does and doesn't offer). Requiring a user to have
pre-recorded an area before the bot can explore it there defeats the bot's own purpose.

The intended direction instead is a **rolling horizon**:

1. Inspect currently loaded terrain through `MCache` (the client's own live, session-scoped terrain
   cache — see §3).
2. Use existing local pathfinding (see §8) over the currently known/loaded terrain only.
3. Choose a bounded intermediate target — not a single leap to the final destination — within the
   range that's actually loaded and resolvable right now.
4. Move toward that intermediate target, which causes additional terrain to stream in as the client
   receives it from the server.
5. Re-scan and re-plan from the new position, repeating from step 1.
6. Treat unloaded or trimmed terrain as **unknown** — neither "safe to enter" nor "permanently
   unexplored, never revisit." It is simply not yet observed *right now*, and may become observed again
   later (see §3).

## 3. `MCache` is live terrain input, not permanent exploration memory

**[Current source-confirmed]** `MCache.grids` (`src/haven/MCache.java:70`) does **not** only grow.
`MCache.trim(Coord, Coord)` (`src/haven/MCache.java:1483`) and `MCache.trimall()`
(`src/haven/MCache.java:1471`) both remove entries from `grids`. This directly contradicts a claim made
in the snapshot-era `docs/world-explorer-plan-b-frontier-exploration.md` (§"What ChunkNav and
ExploredArea actually provide," under "Refinement this motivates"), which asserts `MCache.grids` "only
grows (no eviction/remove path found in the file)" — that claim was inaccurate even when written, and
remains inaccurate on current `master`.

**Consequence for design:** `MCache` must be treated as a live, mutable window onto currently-loaded
terrain — an input to re-scan every planning cycle — not as an append-only exploration memory a bot can
trust to still hold everything it has ever seen. A grid that was loaded and then trimmed will read as
unresolved again on a later query, exactly as if it had never been loaded. Any "have I already explored
this" bookkeeping needs its own persisted state (as the restored water bot's `visitedGridIds` does),
independent of whatever `MCache.grids` currently happens to contain.

## 4. Player-observed cliff behavior

**[User-reported]** The following behavior was reported by the user from in-game play, not
independently re-measured in this pass:

- A distant click across a cliff commonly makes the character walk to the base of the cliff and stop
  there, without reaching the requested target on the far/upper side of the cliff.
- The server's own auto-climb behavior triggers when the click target lands sufficiently near the
  cliff's edge — proximity-gated, not distance-independent.
- After climbing or descending a cliff, another movement click is often required to continue — climbing
  does not automatically resume the original multi-tile route.
- Real terrain can require repeated bounded attempts/re-clicks near a cliff edge before the character
  successfully traverses it.

This observed behavior is what motivated handling cliffs in the **movement execution / click-sequencing
layer** — i.e. choosing where and when to click near a detected cliff — rather than only changing the
A* search model's cost function or connectivity graph. A* alone decides *that* a route crosses a cliff;
it does not decide *how to click* to actually get the character across one, which is a separate,
execution-time problem the snapshot-era code split out into `CliffAwareMove`/`CliffScan` (see §7).

## 5. Surviving `CliffScan` calibration evidence

**[Snapshot-era tested]**, recovered from the source comment on `CliffScan.cliffDeltaThreshold`
(`src/nurgling/pf/CliffScan.java` in snapshot `802a8855`, not present on current `master`):

- Detection samples terrain height at **quarter-tile** spacing (`MCache.tileqsz` = 2.75 world units)
  along the movement segment, via `MCache.getcz`.
- Proposed threshold: `cliffDeltaThreshold = 4.0` (world z-units of height delta between consecutive
  quarter-tile samples, above which the segment is classified as a cliff rather than a walkable slope).
- One real cliff was measured: a clean single-tile (11-unit) climb step measured **approximately
  22.7–26.0 z** total.
- That converts to approximately **2.06–2.36 z per world unit**, which scaled down to the 2.75-unit
  quarter-tile sampling step gives approximately **5.7–6.5 z per quarter-tile sample** on the measured
  cliff.
- One adjacent graded (non-cliff) ramp was also measured: its steepest observed point was approximately
  **0.74 z per world unit**, i.e. approximately **2.0 z per quarter-tile sample**.
- `4.0` was chosen to sit with margin between the ramp's ~2.0 and the cliff's ~5.7–6.5 per-sample
  figures.
- **Only one cliff/ramp pair was ever measured.** The source comment itself flags this: other cliff
  materials — the comment names "Sand Cliff" specifically, citing `TerrainSearchWindow.java`'s preset
  list as the source of that material name — may need a fresh calibration pass with `CliffCalibrate`
  before `4.0` can be trusted generally. This threshold should be treated as a single-sample starting
  point, not a validated constant.

## 6. The "plan file's progress log" — not found, availability unresolved

**[Current source-confirmed, scoped to the searches actually performed]** The
`CliffScan.cliffDeltaThreshold` source comment (quoted in §5) says to "see plan file's progress log for
the raw data." **No such raw log was found in snapshot `802a8855` or in the referenced Git history
searched in this pass**:

- `git ls-tree -r --name-only 802a8855` contains no file matching a raw calibration log under any
  cliff-related or progress-log-like name.
- `git log --all --diff-filter=A --name-only -- "*progress*"` (searching every branch/commit ever added
  to this repository, as currently known to this local clone, for a file with "progress" in its name)
  returns nothing.
- The only **World Explorer planning documents** examined were Plan A and Plan B
  (`world-explorer-plan-a-fix-wall-following.md`, `world-explorer-plan-b-frontier-exploration.md`) —
  neither contains cliff calibration raw data. (The repository contains many other files with "plan" or
  "planning" in their name — e.g. `src/nurgling/planning/**`, `PlanningLayerManager.java` — unrelated to
  World Explorer; those were not examined for cliff data and are not the subject of this claim.)

**These searches do not prove the raw log never existed.** A case-sensitivity mismatch, a different
filename entirely, an unavailable conversation/session artifact, or an unreachable/unpushed Git object
(a commit never merged into any ref this clone can see, a dropped stash, reflog-expired history) could
all hide a real file from the searches actually performed. **Conclusion: treat the raw calibration log
as unavailable given what has been searched, not as proven never to have existed.** Only the
*summarized* numbers quoted in §5 (transcribed into the source comment itself) are confirmed to survive.
If the user later supplies the raw log or another calibration artifact from elsewhere, treat that as new
evidence superseding this section — do not assume it cannot exist because this pass didn't find it.

## 7. Snapshot-only cliff/land research inventory

**[Current source-confirmed]** These exist only in snapshot `802a8855`, are recoverable from it, and
are **not current implementation** on `master` or on `feat/world-explorer-reconstruction`. They must
**not** be merged with, or treated as dependencies of, the restored water bot:

| File/change | Role |
|---|---|
| `src/nurgling/pf/CliffScan.java` | Cliff-edge detection via `MCache.getcz` sampling (§5) |
| `src/nurgling/actions/CliffAwareMove.java` | Movement-execution layer that consumes `CliffScan` to aim an approach point short of a detected edge instead of clicking straight past it |
| `src/nurgling/actions/bots/CliffCalibrate.java` | Debug-only bot: live `pos/tile/z/deltaZ` chat readout for manually calibrating `CliffScan.cliffDeltaThreshold` |
| `src/nurgling/actions/DynamicPf.java`'s `cliffAware` opt-in diff | Adds an opt-in (`default false`) flag to `DynamicPf` that, when set, invokes `CliffAwareMove` between path-vertex hops instead of clicking the raw vertex coordinate. **Confirmed general pathfinding-movement infrastructure, not World-Explorer-specific** — the restored water bot never uses `DynamicPf` at all, and nothing in the snapshot ever sets `cliffAware = true` |
| The excluded `cliff_calibrate` debug registry entry | A `BotDescriptor` entry in the snapshot's `BotRegistry.java` registering `CliffCalibrate` under `BotType.UTILS`, reusing the existing `"pause"` icon. **Deliberately excluded** from the water bot's registry restoration |

None of the above were touched during the water World Explorer reconstruction. They remain fully
recoverable from `802a8855` by the same means used to restore the water bot's 12 core files, whenever a
land-navigation reconstruction is separately approved and scoped.

## 8. Reusable candidates — not promises of readiness

**[Current source-confirmed + Proposal]** These are candidates worth evaluating when land-navigation
work is scoped, not endorsements that they are ready to use as-is:

- **`PathFinder` / `NPFMap`** (`src/nurgling/actions/PathFinder.java`, `src/nurgling/pf/NPFMap.java`,
  current `master`) — existing local A* pathfinding over currently-known/loaded terrain. `waterMode`
  (boolean field, `PathFinder.java:24`, consumed by `NPFMap.java:~308-316`) already exists for
  water-passable-tile routing; land routing would use the default (non-water) mode. This is the most
  direct candidate for step 2 of the rolling-horizon loop in §2 — it already operates on locally loaded
  terrain and does not depend on `ChunkNav`.
- **`FrontierPicker`** (`src/nurgling/pf/FrontierPicker.java`, snapshot-only, restored on
  `feat/world-explorer-reconstruction` as part of the water bot's dormant infrastructure — see
  `world-explorer-system.md`) — a possible pattern for the rolling-frontier target-selection step (§2
  step 3), *if* two things are corrected/supplied first: (a) its class doc's assumption that
  `MCache.grids` "only grows" is false (§3) — any land use of its "unresolved tile = frontier" signal
  must account for trimmed grids reading as unresolved again, not treat that as evidence of genuinely
  new terrain; (b) its `TileAcceptor` is currently only ever supplied `WaterTiles.isSafe` by its one
  real caller (`WorldExplorerFrontier`) — a land bot needs its own acceptor (the class's own doc names
  an intended `LandFrontier` consumer that does not exist anywhere, in the snapshot or on `master`).
- **`StuckDetector`** (`src/nurgling/actions/StuckDetector.java`, restored on
  `feat/world-explorer-reconstruction`) — generic no-progress detector, reusable as-is for detection.
  `StuckDetector` itself has no defect: `reset()` setting `lastTile = null`, followed by the next
  `check()` returning `false` while it establishes a new post-reset baseline, is normal, correct
  detector behavior. **The defect is in the restored water bot's orchestration around it, not in the
  detector** (see `world-explorer-system.md` §8 finding 2): after a recovery attempt, `WorldExplorer`
  calls `stuck.reset()`, the next `check()` correctly returns `false`, and `WorldExplorer` then resets
  its own `consecutiveStuck` counter to zero on that `false` — so its intended three-strikes escalation
  can never accumulate. Not fixed in the water bot reconstruction per that branch's "preserve the
  baseline" mandate. **A future land-bot caller does not automatically inherit this defect merely by
  using `StuckDetector`** — it must design its own recovery-attempt counter independently and avoid
  clearing that counter just because the detector is establishing a post-reset baseline (i.e. don't
  treat "detector currently reports not-stuck right after I told it to reset" as "the attempt
  succeeded").
- **Existing Gob/hitbox obstacle handling** — the codebase already has Gob-position and hitbox query
  infrastructure used elsewhere (e.g. `Finder`, various bot obstacle checks); a land bot's "route around
  objects" capability (§9) would build on this rather than reinvent it. Not further inventoried in this
  pass — flagged as a research starting point only.
- **`NConfig.Key.animalrad` + `Forager`'s detection pattern** — **[Current source-confirmed, research
  only]** `NConfig.Key.animalrad` (`src/nurgling/NConfig.java:103`) persists an `ArrayList<NAreaRad>`
  (name + radius per dangerous-animal pattern), read by `Forager.java:98` and `BoughBee.java:37` to
  build a list of dangerous-animal name patterns to react to, and edited via
  `src/nurgling/widgets/options/NRingSettings.java`. This is recorded here purely as **existing
  precedent for how another bot represents "dangerous animal" configuration** — it is not
  automatically correct or sufficient behavior for a land explorer, which may need different detection
  radius, different animal lists, or a different reaction (see §9's explicit open decision on this).

## 9. Desired eventual capabilities

**[Proposal]** The following capabilities are the eventual target for a land-based World Explorer, none
implemented:

- Exploration toward new/unresolved terrain (the core rolling-horizon loop, §2).
- Difficult-terrain and cliff traversal with bounded retries (§4 motivates why this lives in movement
  execution, not just pathfinding search).
- Routing around objects and obstacles (Gobs, terrain features blocking a direct path).
- Hostile-animal detection and avoidance.
- Cancellation, stuck recovery, and bounded failure behavior (a land analogue of the water bot's
  `StuckDetector` + backoff pattern — `StuckDetector` itself needs no fix; the escalation-counter defect
  noted in §8 is in the water bot's own orchestration around it, and a land caller must simply design
  its recovery-attempt counter to not repeat that mistake).

**Explicitly left as a future user decision, not decided here:** the exact hostile-animal *response* —
whether the bot should stop, reroute around the threat, retreat, or take some other action — is
unresolved. Do not implement a specific choice without that decision being made first.

## 10. `Graph.java`'s priority-queue change is unrelated

**[Current source-confirmed]** `src/nurgling/pf/Graph.java`'s `LinkedList`→`PriorityQueue` open-set
change (tracked separately as `perf/pathfinder-priority-queue`, see `mixed-wip-recovery.md`) is a
**general shared-pathfinding performance proposal** — it backs `DynamicPf`, used by many bots — and must
**not** be assumed to be a land-explorer dependency. A future land bot may or may not benefit from it
like any other `DynamicPf` consumer would, but nothing about the land-navigation research in this
document requires it, and it should continue to be evaluated and merged (if ever) on its own,
independent of any land-explorer work.

## Scope boundary (repeated for emphasis)

This document is about a **separate, future, proposed** bot. It does not describe, does not modify, and
must not be used to justify changes to the restored water-based World Explorer
(`src/nurgling/actions/bots/WorldExplorer.java` and its 11 companion core files — see
`world-explorer-system.md`). Applying anything from this document to the water bot requires separate,
explicit approval.
