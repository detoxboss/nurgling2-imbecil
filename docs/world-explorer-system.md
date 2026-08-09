# World Explorer system reference

**Status: current — describes the implemented World Explorer bot.** Source is byte-identical to rescue
snapshot `802a885500a76b68697fb6450bd35bcc82355cab` for the 12 core files listed below, reconstructed via
branch `feat/world-explorer-reconstruction` (commit `a8070ebb1`). Runtime-smoke-tested in-client
2026-08-08 — see §10 for precisely what was and was not verified. Do not cite this document as proof of
complete validation beyond §10's stated scope.

This is the canonical document for the World Explorer bot. It supersedes
[`world-explorer-plan-a-fix-wall-following.md`](world-explorer-plan-a-fix-wall-following.md) and
[`world-explorer-plan-b-frontier-exploration.md`](world-explorer-plan-b-frontier-exploration.md), both of
which are historical planning documents that do not describe what was actually built — see the status
notice at the top of each.

**Scope boundary:** this document describes the **water-based, shore-tracing** World Explorer only —
a boat bot that hugs a coastline via `TileField`/`CoastFollower`. A separate, future,
**land**-based long-distance exploration bot has been researched but not implemented — see
[`land-navigation-research.md`](land-navigation-research.md) (routed via
[`.claude/rules/land-navigation.md`](../.claude/rules/land-navigation.md)). The land bot is not a mode,
extension, or dependency of this one; do not conflate the two or apply land-navigation research to this
bot without separate, explicit approval.

## 1. Menu route and identity

Launched from the existing UTILS-menu button. **`BotRegistry.java` and `NBotsMenu.java` were not
modified** during reconstruction — the existing `BotDescriptor` entry is unchanged:

```
id="worldexplorer", type=BotType.UTILS, class=WorldExplorer.class, iconPath="worldexplorer"
```

Replacing `WorldExplorer.java`'s implementation is sufficient for this existing button to launch the
reconstructed bot; no registry or menu change was needed or made.

## 2. Runtime path

```
WorldExplorer.run(gui)
  -> WorldExplorerWnd            (existing config window, blocks on WaitCheckable until closed)
  -> TileField.scan(pos, deeperMode)      [every iteration]
  -> CoastFollower.initialHeading(...)    [once, at start]
  -> CoastFollower.plan(field, pos, heading, chirality, bandTiles, CONTOUR_STEPS)   [every iteration]
       -> on success: WorldExplorerMove.clickAndChase(plan.target, gui)
       -> on failure: WorldExplorer.backOffFromShore(...)
  -> StuckDetector.check(...)             [every iteration; see §8 finding 2]
  -> WorldExplorerFrontier.markVisited(pos)   [every iteration — bookkeeping only, see §8 finding 6]
  -> CrossingCandidateTracker.scanForCrossing(...)   [every iteration a plan succeeds, see §8 findings 3-4]
  -> NDebugLog.log / .logAndChat          [throughout]
```

Actual steering is entirely driven by `CoastFollower`'s iso-contour tracing over `TileField`'s
chamfer distance-to-land field — **not** by chasing a tile-type boundary (the pre-reconstruction
master implementation's approach) and **not** by frontier-directed target selection (Plan B's
approach). See §7 for which parts of the restored code are live versus dormant.

## 3. `WorldExplorerMove.clickAndChase`

The only method of `WorldExplorerMove` actually called by `WorldExplorer`. Sends one click toward the
target, then returns as soon as **either** of two conditions holds: the character is detected moving or
already within the close-enough threshold (`IsMovingBySpeed` for a `snekkja` via the `Following`
attribute, `IsMoving` otherwise), **or** the bounded wait cap `MOVE_TH=20` (ticks) expires — whichever
comes first. It never waits for a full stop. This is the non-blocking movement primitive that lets the
boat's momentum carry across a run instead of stopping every tile, in contrast to `GoTo.java`'s
blocking-to-full-stop pattern (unmodified, still used elsewhere in the codebase).

## 4. Backoff and stuck detection

- **Stuck detection**: `StuckDetector` — generic "no net tile progress for `timeoutS` seconds"
  detector (default 2.0s), same threshold precedent as `WaypointMovementService.java`'s existing
  inline no-progress check.
- **Recovery**: `WorldExplorer.backOffFromShore` — moves away from the nearest land along
  `TileField.awayFromBlocked`'s gradient. Iterates a fixed angle fan **in order**
  `{0, 25, -25, 50, -50}` degrees; for each angle, tries distances **descending** from
  `max(2, prop.backupTiles)` tiles down to 1, and returns on the **first** candidate that passes
  `TileField.navigableAt`/`lineClear`. Net effect: within the first angle that has any valid
  candidate at all, it prefers the longest clear distance at that angle — but it does **not** compare
  across all 5 angles to find the single longest run in the whole fan; angle `0` wins over a longer
  run at `25`/`-25`/`50`/`-50` whenever angle `0` has any valid distance at all.
- Fires both when `CoastFollower.plan` returns no usable step, and when `StuckDetector.check` reports
  no progress — see §8 finding 2 for why the escalation-to-abort path is effectively dead.

## 5. Water-mode classification

`WaterTiles` — two categories (fresh, ocean), each with 3 tiers (shallow/deep/deeper). `isSafe(name,
deeperMode)`: `deeperMode=true` includes all 3 tiers; `false` excludes the deeper (unsafe/damaging)
tier. Used by `TileField.scan` to build the navigable-cell mask, and by `CrossingCandidateTracker` to
detect genuine open-ocean crossings (as opposed to a river, which is never treated as continent-crossing
evidence).

## 6. Configuration and persistence

`NWorldExplorerProp` — persisted per-character, keyed by `(username, chrid)`, stored in
`NConfig.Key.worldexplorerprop` (a flat list across all characters, filtered by identity on read).
Fields: `clockwise`, `deeper` (pre-existing), plus `lookaheadTiles`, `stuckTimeoutS`, `backupTiles`,
`swingTiles`, `bandTiles`, `visitedGridIds` (restored). **`WorldExplorerWnd` exposes `clockwise`,
`deeper`, and the five numeric tunables (`lookaheadTiles`, `stuckTimeoutS`, `backupTiles`,
`swingTiles`, `bandTiles`) as UI fields — it does not expose `visitedGridIds`**, which is
written/read only by `WorldExplorerFrontier.markVisited`/its constructor, with no UI control. All
persisted fields round-trip through `toJson()`/the `HashMap` constructor exactly like the pre-existing
`clockwise`/`deeper` fields.

## 7. Component status: active / partially active / dormant

| Component | Status | Notes |
|---|---|---|
| `TileField` | **Active** | Core distance-field scan, every iteration |
| `CoastFollower` | **Active** | Core steering law, every iteration |
| `WorldExplorerMove.clickAndChase` | **Active** | Only method of this class actually called |
| `WorldExplorerMove.scanAhead` / `.scanHeading` | **Dormant** | Defined, never called by anything in the restored graph |
| `StuckDetector` | **Active** (detection) | Recovery trigger works; escalation logic effectively unreachable, see finding 2 below |
| `WaterTiles` | **Active** | Consumed by `TileField.scan` and `CrossingCandidateTracker` |
| `WorldExplorerFrontier.markVisited` | **Active** (bookkeeping only) | Persists visited grid IDs; does not influence steering |
| `WorldExplorerFrontier.pickTarget` / `.visitedCount` | **Dormant** | Never called |
| `FrontierPicker`'s frontier-selection tiers (`pickFrontierPoint`, `scanLoadedGridsForFrontier`, `pickFrontierChunk`, `fallbackKeepMoving`) | **Dormant** | Only reachable via `WorldExplorerFrontier.pickTarget`, which is unreached |
| `FrontierPicker.safeTileName` | **Active** | Called directly by `CrossingCandidateTracker.scanForCrossing` (which runs every successful-plan iteration); also referenced by `WorldExplorerMove`'s dormant `scanAhead`/`scanHeading` helpers |
| `CrossingCandidateTracker.scanForCrossing` | **Active** (write-only) | Logs candidates; see finding 3-4 |
| `CrossingCandidateTracker.getCandidates` | **Dormant** | No consumer |
| `NDebugLog` | **Active** | File + chat diagnostic logging, per-call file open/close |

**Snapshot-era status vs. this reconstruction**: the mixed-WIP recovery ledger records this code as
having been runtime-tested during the original 2026-08-07 snapshot-era development (the class doc
comments throughout this group reference specific real testing incidents — e.g. `WorldExplorer`'s
`MIN_ITERATION_MS` doc, `CoastFollower`'s `MIN_CHORD_RATIO` doc). That testing predates this
reconstruction and was performed against a different (now-superseded) `master` state. This
reconstruction has been verified to compile and build (`ant jar`/`ant test`) against current `master`
and was runtime-smoke-tested in-client on 2026-08-08 — see §10 for the precise scope of what that
verified. Treat prior "confirmed in testing" claims embedded in code comments as historical evidence the
design worked once, not as a substitute for §10's own findings.

## 8. Recorded latent findings (not fixed in this pass)

These were confirmed by code reading during reconstruction and are recorded here deliberately
unfixed, per the "preserve the runtime-tested baseline" mandate for this branch:

1. **`lookaheadTiles` and `swingTiles` are persisted and shown in the UI but unused.** `CoastFollower`
   uses `WorldExplorer`'s hardcoded `CONTOUR_STEPS=60` constant, not `prop.lookaheadTiles`;
   `backOffFromShore`'s fan uses a fixed `{0,25,-25,50,-50}` degree set and `prop.backupTiles` for
   distance only, never `prop.swingTiles`.
2. **The three-strikes stuck-abort is effectively unreachable.** `StuckDetector.reset()` sets
   `lastTile = null`. The next call to `check()` after a reset takes the `lastTile == null` branch,
   which unconditionally returns `false` (not stuck) and reseeds `lastTile`/`lastProgressTime` — so
   the very next iteration after any backoff-triggered `stuck.reset()` cannot itself report stuck,
   which clears `consecutiveStuck` back to 0 in `WorldExplorer`'s `else` branch before three
   consecutive failures can ever be counted. **Empirically confirmed in the 2026-08-08 runtime smoke
   test (§10):** all 6 recorded stuck events remained `attempt 1` — none ever escalated.
3. **`CrossingCandidateTracker.getCandidates()` has no consumer.** Candidates are detected, deduped,
   sorted, and logged, but nothing reads the list back for any navigation decision.
4. **The crossing scan's perpendicular direction likely points shoreward, not seaward.** In
   `WorldExplorer.run()`, `heading` at the point `perp` is computed is `plan.heading`, which
   `CoastFollower.plan` sets to approximately `awayFromBlocked(pos).rot(chirality * 90°)` (the
   contour tangent). Rotating that heading by another `chirality * 90°` (as `WorldExplorer` does to
   get `perp`) composes to approximately `awayFromBlocked(pos).rot(chirality * 180°)`, i.e.
   approximately the *negation* of "away from land" — pointing back toward the shore rather than out
   across open water. This does not affect steering (only `CrossingCandidateTracker`'s scan direction
   uses `perp`), so it degrades crossing-candidate detection quality without being a stability risk.
5. **`WorldExplorerFrontier.pickTarget()` and `FrontierPicker`'s frontier-*selection* tiers are not
   called at runtime.** Only `WorldExplorerFrontier.markVisited()` runs from that class. **Correction:
   `FrontierPicker` is not entirely dormant** — its `safeTileName()` helper is called directly by
   `CrossingCandidateTracker.scanForCrossing` (active, see finding 3-4) independently of
   `WorldExplorerFrontier.pickTarget`. Only the tiered target-*selection* logic
   (`pickFrontierPoint`/`scanLoadedGridsForFrontier`/`pickFrontierChunk`/`fallbackKeepMoving`) is
   dormant; see §7's component table for the precise split.
6. **Visited grids are recorded but do not influence navigation.** `markVisited()` persists
   `visitedGridIds`, but nothing in `CoastFollower`/`TileField` consults that set — it has no effect
   on the bot's actual path.
7. **`MCache.grids` does not only grow.** `MCache.trim(Coord, Coord)` and `MCache.trimall()`
   (`src/haven/MCache.java:1471,1483`) remove entries from `grids`. This contradicts a claim in the
   snapshot-era Plan B document (`world-explorer-plan-b-frontier-exploration.md`) that `grids` "only
   grows (no eviction/remove path found in the file)" — that claim was inaccurate even at the time it
   was written for this codebase's actual `MCache`, and remains inaccurate on current `master`.
   Relevant to `FrontierPicker`'s "unresolved tile = frontier" signal if that dormant code is ever
   activated: a trimmed grid would read as unresolved again, not genuinely unexplored.
8. **A log line can be written before `NDebugLog.newRun()` is called.** `NDebugLog.log()` lazily calls
   `newRun()` if `resolvedPath` is still null, and `NDebugLog.path()` does the same. If any log call
   happens to fire before `WorldExplorer.run()`'s own explicit `NDebugLog.newRun()` call, that line
   goes to a file separate from the one the rest of the run's diagnostics land in.
9. **`TileField.scan` performs a full 101×101 (`RADIUS=50`) scan and two-pass chamfer distance
   transform every main-loop iteration**, allocating fresh `boolean[]`/`int[]` arrays and doing
   temporary `Coord`/`Coord2d` allocation throughout the scan and `CoastFollower.plan`'s per-step
   walk. This is a profiling candidate, not an approved change — do not optimize, cache, or
   incrementally update this without separate approval; the 2026-08-08 smoke test (§10) did not
   measure performance, only functional behavior.
10. **The snapshot's `bot.worldexplorer.desc` text ("sails toward genuinely unexplored water,
    discovering the coastline as it goes") does not match the active algorithm**, which coast-follows
    via a distance-to-land field and does not call `WorldExplorerFrontier.pickTarget()` (finding 5).
    This key has a real runtime consumer — the World Explorer bot icon resource's tooltip contains
    `@bot.worldexplorer.desc`, resolved through `L10n` by `Resource.Tooltip.text()` — so changing it
    to inaccurate text would be user-visible. **Left unchanged**, pending an accurate replacement
    description as a separate, explicitly approved change — the 2026-08-08 smoke test (§10) confirmed
    the bot's actual behavior (coast-following) but did not include an approved rewrite of this text.

## 9. Performance observations (profiling candidates only)

Not approved changes; listed here so a future profiling pass has a starting point:

- `TileField.scan`'s full rescan every iteration (finding 9 above) is the largest recurring cost in
  the group.
- `NDebugLog.log()` opens and closes a `FileWriter` on every call rather than holding one open writer
  per run.
- `CrossingCandidateTracker.scanForCrossing` walks up to 150 one-tile steps per successful-plan
  iteration.

None of these have been measured against current `master` in this pass — treat as hypotheses, not
confirmed bottlenecks.

## 10. Runtime verification — 2026-08-08 smoke test

Distinguishes what the user directly observed in-client from what was independently re-derived from 9
`worldexplorer-debug-*.log` files recorded during the same session. Per data-handling policy, raw logs
are not reproduced in this repository and no absolute user path or world coordinate appears below — the
figures here were recomputed from the session's log files, not copied on faith.

**User-observed, runtime-smoke-tested 2026-08-08:**

- The existing UTILS-menu "World Explorer" button launches the restored bot (§1's menu route, unchanged).
- Clockwise direction works.
- Counterclockwise direction works.
- Larger `bandTiles` values produce a visibly wider path from shore.
- `bandTiles=1` closely hugs the shore; catches on difficult corners as expected, and successfully
  performs the same first-level recovery behavior as the previous known-good bot.
- Debug-log file creation works.

**Log-supported, independently re-verified across all 9 session runs (8 Deep&Deeper, 1 Deep&Shallow;
`bandTiles` of 1, 5, and 15 all exercised, both chiralities exercised):**

- Normal planning continued across all 9 runs — 1,037 successful `WorldExplorer: shore=...` planning
  records total, with zero exceptions, crashes, fatal errors, or terminal-abort messages (`No navigable
  coast ahead`, `no open water to back off into`, `Stuck: unable to clear obstacle`) in any run.
- The `MIN_ITERATION_MS=300` loop floor (§2) holds in practice: the minimum interval between consecutive
  normal planning records across all 9 runs was ≈0.301s.
- `bandTiles` measurably affects the actual shore-following contour, not just theoretically: recorded
  average shore-distance was ≈1.48 tiles at `bandTiles=1`, ≈5.54 tiles at `bandTiles=5`, and ≈13.32 tiles
  at `bandTiles=15` — each within the expected band for its configured value.
- Recovery reliably resumes normal planning: 6 timed `StuckDetector` events (§4) and 10 `no contour
  ahead` events (§2's `CoastFollower.plan`-returned-null path), all followed by a successful backoff (16
  total, every one a `5t at 0deg` recovery — `backOffFromShore`'s first angle at its configured backup
  distance succeeded every time this session) and a resumed `shore=` planning line with no gap.

**Known limitation confirmed, not fixed:** all 6 recorded stuck events remained `attempt 1` (see §8
finding 2's empirical-confirmation note) — the intended three-strikes escalation-to-abort never fired,
consistent with the documented orchestration defect, not a new issue. This session verifies first-level
stuck recovery only; it neither exercises nor fixes the three-strike abort path.

**Explicitly not verified by this session — do not cite these as tested:** dedicated river-mouth
behavior, dedicated dead-end-inlet behavior, long-duration soak testing, relog/`visitedGridIds`
persistence across sessions, crossing-candidate usefulness or consumption (§8 finding 3), repeated
recovery failure and three-strike-abort behavior, or every coastline/obstacle/boat/terrain
configuration. This is a successful restoration/runtime-smoke test, not complete validation of every
dormant or unfinished subsystem — every dormant/partially-active component in §7 and every unfixed
finding in §8 remains exactly as documented there; none were fixed or fully exercised by this session.
