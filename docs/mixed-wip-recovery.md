# Mixed-WIP recovery ledger

**Status: recovery/backlog tracking — not a description of current implementation.**

On 2026-08-07 the working tree at `backup/mixed-wip-before-upstream-2026-08-07` (commit `802a8855`,
branched from `631edc1a2` — the same commit as the current `master` history) held several unrelated
features mixed together, uncommitted, before an upstream merge. Rather than lose any of it, the whole
tree was snapshotted to that branch/commit (kept checked out in a separate local rescue worktree —
see `git worktree list`) and the features are being reconstructed one at a time on `master`, verified
individually, instead of merged wholesale. See `docs/development-workflow.md` for why a wholesale
merge of mixed WIP is avoided.

**Do not treat snapshot `802a8855` as ground truth for current behavior.** It is a point-in-time WIP
capture; some of it has since been superseded by better fixes already on `master` (noted per group
below), and none of the unreconstructed parts have been re-verified against current `master`.

## Reconstruction status

### Done — LP Assistant (merged)

LP Assistant (bot + LP discovery-marker system) has been reconstructed and merged through `master`
commit `247f1997`. Canonical docs: `docs/lp-assistant-bot.md`, `docs/inventory-grid-system.md` §7,
routed via `.claude/rules/lp-assistant.md`. The reconstructed version is **ahead of**, not identical
to, the snapshot's copy — e.g. `src/nurgling/actions/Equip.java`'s non-empty-bucket protection exists
on current `master` and is absent from the snapshot's older WIP state; don't pull snapshot code over
current master code for this group.

`docs/live-harvest-availability.md` is byte-identical between `master` and the snapshot — already
fully carried forward, nothing left to reconstruct for it.

### Next — World Explorer (not yet reconstructed)

Target: a rewritten `WorldExplorer` bot (boat-based coastline/frontier exploration). Current `master`
still has the pre-rewrite `WorldExplorer.java` (122 lines); the snapshot's version is a 178-line
rewrite built on new supporting infrastructure. **Do not copy this code or its planning docs into the
repo wholesale.** When this is picked up, audit it against current `master` and re-implement/verify,
rather than pasting the snapshot version in.

**Not all of these paths are absent from `master`.** Some already exist there in an older/pre-rewrite
form; the rescue snapshot holds a *modified* implementation on top of that same path, plus a set of
genuinely new companion files with no `master` counterpart at all. Treat "exists on `master`" and
"matches the snapshot" as two separate questions — a path can be true on the first and false on the
second.

Already on `master`, modified further in the snapshot (do not assume the `master` copy is what's
described here — the snapshot's version is the one with the behavior below):

| File | Snapshot changes on top of the current `master` version |
|---|---|
| `src/nurgling/actions/bots/WorldExplorer.java` | Rewritten bot main loop (122 lines on `master` → 178 lines in the snapshot) |
| `src/nurgling/conf/NWorldExplorerProp.java` | Adds lookahead/stuck-timeout/backup/swing/band tuning fields and persisted visited-grid-id memory |
| `src/nurgling/widgets/bots/WorldExplorerWnd.java` | Adds UI entries for the above tuning fields |
| `src/nurgling/widgets/NBotsMenu.java` | Modified alongside the above |
| `src/nurgling/actions/bots/registry/BotRegistry.java` | Adds the `cliff_calibrate` debug-bot registry entry |
| `src/nurgling/pf/Graph.java`, `src/nurgling/actions/DynamicPf.java` | See the priority-queue group below; `DynamicPf`'s own `cliffAware` opt-in flag (default `false`) is World-Explorer-specific |
| `src/lang/messages.properties`, `messages_ru.properties` | Adds WE-only keys, see below |

New files, no `master` counterpart at all:

| File | Role |
|---|---|
| `src/nurgling/actions/bots/WorldExplorerMove.java` | Movement/momentum layer (Plan A) |
| `src/nurgling/actions/bots/WorldExplorerFrontier.java` | Frontier-exploration mode (Plan B) |
| `src/nurgling/actions/bots/CrossingCandidateTracker.java` | Tracks candidate inter-continent water crossings |
| `src/nurgling/actions/bots/CliffCalibrate.java` | Debug bot: live terrain-height readout to calibrate `CliffScan` |
| `src/nurgling/actions/CliffAwareMove.java`, `StuckDetector.java` | See "Reusable systems awaiting reconstruction" below |
| `src/nurgling/pf/CliffScan.java`, `CoastFollower.java`, `FrontierPicker.java`, `TileField.java`, `WaterTiles.java` | See "Reusable systems awaiting reconstruction" below |
| `src/nurgling/tools/NDebugLog.java` | See "Reusable systems awaiting reconstruction" below |
| `docs/world-explorer-plan-a-fix-wall-following.md` | Plan A: fix the existing wall-following algorithm's bugs (jerky motion, stuck-on-obstacle, river-mouth 180° flip) — root causes confirmed by code reading against `GoTo.java`/`MovingCompleted*.java` |
| `docs/world-explorer-plan-b-frontier-exploration.md` | Plan B: replace wall-following with frontier-directed travel; investigates reusing `ChunkNav`/`ExploredArea` and finds neither directly reusable (ChunkNav conflates water with wall/rock blockage) but both offer reusable primitives. Explicitly not chosen over Plan A at time of writing — evidence in the snapshot suggests **both** were ultimately built (`WorldExplorerMove` = Plan A, `WorldExplorerFrontier` = Plan B), not an either/or choice as the doc originally framed it — **unresolved: not independently re-verified this pass**, since the WE reconstruction itself is deferred |

Localization: `src/lang/messages.properties`/`messages_ru.properties` gained WE-only keys
(`explorer.lookahead`, `explorer.stuck_timeout`, `explorer.backup_tiles`, `explorer.swing_tiles`,
`explorer.band_tiles`) in the snapshot, not yet on `master`.

### Separate — `perf/pathfinder-priority-queue`

`perf/pathfinder-priority-queue` is a **proposed future reconstruction branch name from the original
audit, not a branch or ref that exists (or should be assumed to have existed) anywhere** — none found
under this name in local refs, reflog, or remote branches as of this pass. Treat it as a label for
work not yet started, not as history to look up. It refers to one self-contained change bundled inside
the same snapshot commit as the World Explorer work: `src/nurgling/pf/Graph.java`'s open-set data
structure changes from a
`LinkedList` that gets fully re-sorted every iteration to a `PriorityQueue<Vertex>` with the same
ordering comparator. Confirmed by direct diff read: the change is self-contained (same `Vertex`/
`Comparator` types, no World-Explorer-specific type leaks into `Graph.java`) and carries an inline
comment arguing correctness (no decrease-key needed, since a `Vertex`'s `len`/`dist` is only ever
assigned once, before being queued).

`Graph.java` backs `DynamicPf.java`, which is shared pathfinding infrastructure used by many bots, not
just World Explorer — so this is plausibly extractable and mergeable independently of the World
Explorer reconstruction. **Before doing so, verify the dependency relationship explicitly**: confirm
`DynamicPf.java`'s own snapshot-only changes (the `cliffAware` opt-in flag, default `false`) aren't
required for the `Graph.java` change to apply cleanly or behave correctly, and re-run whatever
pathfinding exercises this — no automated coverage for it exists yet. This has not been attempted in
this pass.

### Other remaining document group — Hurricane combat-reactor port

`docs/combat/hurricane-port-brief.md` (760 lines) and `docs/combat/hurricane-ready-files.md` (588
lines), present only in the snapshot. These are a **handoff package for porting this repo's combat
reactor into a different H&H client project ("Hurricane"), not Nurgling2 work** — they describe
target file paths under `haven.combat` in that other codebase and instruct a future agent working
*there* to paste code verbatim. Not reconstruction work for this repo. If ever acted on, they belong
in the Hurricane project, not here; recorded here only so the snapshot's contents aren't silently
dropped. Not evaluated for accuracy against this repo's actual combat reactor implementation in this
pass.

### Excluded from recovery tracking

Per scope: generated/binary artifacts in the snapshot (`build.num`, `tmp_ver`, `ver`, and
`docs/Recording 2026-08-04 084913.mp4`) are not feature source and are not tracked here.

## Reusable systems awaiting reconstruction

These snapshot-only classes were written with explicit "shared by multiple bots" intent in their own
class docs, or have a real precedent already on `master`. None are implemented on current `master`;
none have been re-verified against current code or a real run in this pass. Do not cite them as
current behavior. This section is a decision-input for whoever reconstructs World Explorer — promote
what's still useful into a canonical doc at that point, don't leave it parked here indefinitely.

| System | Snapshot location | Reusable purpose | Known consumers | Verification status | Disposition |
|---|---|---|---|---|---|
| `StuckDetector` | `src/nurgling/actions/StuckDetector.java` | Generic "no net tile progress for N seconds" detector, decoupled from any one bot | Snapshot: `WorldExplorer`. Its own class doc names a real precedent already on `master`: `src/nurgling/WaypointMovementService.java` has an inline >2.0s no-progress stuck check that this class claims to generalize — **that inline check is current, real code; the extracted shared class is snapshot-only** | Code-read only (this pass); the generalization itself not re-verified against `WaypointMovementService.java`'s actual current logic | Candidate for shared infrastructure — has a working non-WE precedent, decide during WE reconstruction |
| `CliffAwareMove` + `CliffScan` | `src/nurgling/actions/CliffAwareMove.java`, `src/nurgling/pf/CliffScan.java` | Detects terrain-height discontinuities along a movement segment and aims an approach point short of the edge, so the server's own auto-climb proximity check fires instead of walking into a cliff base and stopping | Snapshot: `DynamicPf.java` (opt-in `cliffAware` flag, default off), `CliffCalibrate.java` (debug bot for threshold tuning) | `CliffScan`'s threshold constant carries an in-code note claiming it was calibrated from live `CliffCalibrate` readings during snapshot-era testing — **not independently re-verified this pass**; classify as runtime-tested-per-snapshot-history, not re-confirmed | Currently only wired for boat/WorldExplorer movement, but the elevation-discontinuity detection itself has no WE-specific coupling — plausibly reusable for any bot that walks near cliffs; needs a decision, not yet made |
| `FrontierPicker` | `src/nurgling/pf/FrontierPicker.java` | Terrain-agnostic frontier-exploration primitives (ray-scan to the nearest unresolved/unloaded map edge, with loaded-grid-edge and expanding-ring fallbacks), gated by a caller-supplied acceptor predicate so land vs. water logic is the caller's problem | Snapshot: `WorldExplorerFrontier` (water). Its own class doc names an intended second consumer, `LandFrontier` — **that class does not exist anywhere, in the snapshot or on `master`; it is a proposal referenced in a doc comment, not implemented code** | Code-read only | Reusable pattern in principle (explicitly designed caller-agnostic); the "shared by land and water bots" claim is aspirational until a land consumer actually exists |
| `TileField` | `src/nurgling/pf/TileField.java` | Chamfer distance-transform field over locally-scanned navigable terrain (distance-to-nearest-obstacle), used to derive shoreline-following gradients without depending on a specific tile-type boundary | Snapshot: `CoastFollower` | Code-read only | Reusable terrain-scanning pattern; currently only consumed by the coast-following code below, no second consumer yet |
| `WaterTiles` | `src/nurgling/pf/WaterTiles.java` | Small tile-name classification helper (fresh vs. ocean, shallow/deep/deeper tiers) | Snapshot: `TileField`, `CrossingCandidateTracker`, `WorldExplorerFrontier` | Code-read only; class doc states the two-category/three-tier taxonomy was confirmed directly by the user, not derived from any in-repo source | Boat/water-bot-scoped utility; reusable for any future water bot, narrow enough it may not need its own doc |
| `CoastFollower` | `src/nurgling/pf/CoastFollower.java` | Steers along an iso-contour of `TileField`'s distance field at a chosen offset from shore (gradient-tangent steering with band-correction), so a traced coastline can't reverse direction on itself and doesn't cut across bays | Snapshot: `WorldExplorer` (Plan A machinery) | Code-read only | General shoreline-hugging steering pattern parameterized by `TileField`/chirality/band — plausibly reusable beyond World Explorer for any coast-hugging bot, not yet decided |
| `NDebugLog` | `src/nurgling/tools/NDebugLog.java` | File-based, timestamped diagnostic logging as an alternative to in-game chat, which the class doc states becomes physically uncopyable during long bot runs (no select-all/scrollbar-drag) | Snapshot: `WorldExplorer` and related classes (`FrontierPicker`, `CrossingCandidateTracker`, `StuckDetector`) | Code-read only | Logging pattern itself is generic and matches `docs/development-workflow.md`'s "opt-in, scoped diagnostics over permanent chat spam" guidance, but the class currently hardcodes a `worldexplorer-debug` file prefix — would need generalizing (a caller-supplied prefix) before being genuinely bot-agnostic |
