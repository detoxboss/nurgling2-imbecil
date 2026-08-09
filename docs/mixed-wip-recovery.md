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

### Done — World Explorer (restored, runtime-smoke-tested)

The 12 core files (`WorldExplorer.java`, `NWorldExplorerProp.java`, `WorldExplorerWnd.java`,
`WorldExplorerMove.java`, `WorldExplorerFrontier.java`, `CrossingCandidateTracker.java`,
`StuckDetector.java`, `CoastFollower.java`, `FrontierPicker.java`, `TileField.java`, `WaterTiles.java`,
`NDebugLog.java`) were restored byte-identical to snapshot `802a8855`, via branch
`feat/world-explorer-reconstruction` (commit `a8070ebb1`), and confirmed to compile/build
(`ant jar`/`ant test`) against current `master`. Runtime-smoke-tested in-client 2026-08-08 — see
`docs/world-explorer-system.md` §7-8 for the active/dormant component breakdown and recorded-but-unfixed
latent findings, and §10 for the smoke test's exact scope (what was and was not verified). The existing
`NBotsMenu.java` and `BotRegistry.java` `"worldexplorer"` descriptor were left unmodified — the existing
menu button launches the restored bot without any registry change; the `cliff_calibrate` debug-bot
registry entry was **not** added (tracked separately, see the cliff-aware-movement row below). Canonical
doc: [`world-explorer-system.md`](world-explorer-system.md).

Target: a rewritten `WorldExplorer` bot (boat-based coastline exploration). Current `master` (prior to
this branch) has the pre-rewrite `WorldExplorer.java` (134 lines, confirmed via direct line count —
correcting this document's earlier inaccurate 122-line figure); the snapshot's version is a 199-line
rewrite (confirmed via direct line count — correcting this document's earlier inaccurate 178-line
figure) built on new supporting infrastructure. When reconstructing, audit it against current `master`
and re-implement/verify, rather than pasting the snapshot version in wholesale — this was done for this
branch; see `world-explorer-system.md` for the audit findings that came out of that process.

**Not all of these paths are absent from `master`.** Some already exist there in an older/pre-rewrite
form; the rescue snapshot holds a *modified* implementation on top of that same path, plus a set of
genuinely new companion files with no `master` counterpart at all. Treat "exists on `master`" and
"matches the snapshot" as two separate questions — a path can be true on the first and false on the
second.

Already on `master`, modified further in the snapshot (do not assume the `master` copy is what's
described here — the snapshot's version is the one with the behavior below):

| File | Snapshot changes on top of the current `master` version |
|---|---|
| `src/nurgling/actions/bots/WorldExplorer.java` | Rewritten bot main loop (134 lines on `master` → 199 lines in the snapshot). **Reconstructed byte-identical on `feat/world-explorer-reconstruction`.** |
| `src/nurgling/conf/NWorldExplorerProp.java` | Adds lookahead/stuck-timeout/backup/swing/band tuning fields and persisted visited-grid-id memory. **Reconstructed byte-identical on `feat/world-explorer-reconstruction`.** |
| `src/nurgling/widgets/bots/WorldExplorerWnd.java` | Adds UI entries for the above tuning fields. **Reconstructed byte-identical on `feat/world-explorer-reconstruction`.** |
| `src/nurgling/widgets/NBotsMenu.java` | **Not actually World-Explorer-specific** — confirmed by direct diff read: the snapshot's copy reverts `NButton.find()`/`dropthing()` to a pre-fix state that current `master` has already fixed (the LP Assistant round's id/path lookup fix). Snapshot staleness from a shared ancestor, not WE work. **Deliberately excluded from reconstruction** — applying it would regress a merged bug fix. |
| `src/nurgling/actions/bots/registry/BotRegistry.java` | Adds the `cliff_calibrate` debug-bot registry entry. **Not added in the World Explorer reconstruction pass** — `CliffCalibrate`/`CliffScan`/`CliffAwareMove` are tracked separately below since `WorldExplorer` never calls them (see next row). The existing `"worldexplorer"` descriptor itself needed no change and was left untouched. |
| `src/nurgling/pf/Graph.java`, `src/nurgling/actions/DynamicPf.java` | See the priority-queue group below. **Correction:** `DynamicPf`'s own `cliffAware` opt-in flag (default `false`) is **not** World-Explorer-specific — confirmed by direct call-site trace: `WorldExplorer` does not use `DynamicPf` at all, and nothing in the snapshot ever sets `cliffAware = true`. It is general (currently unreached) pathfinding-movement infrastructure that happens to share this commit with the World Explorer work, not part of it. Tracked with `CliffAwareMove`/`CliffScan`/`CliffCalibrate` below. |
| `src/lang/messages.properties`, `messages_ru.properties` | Adds WE-only keys, see below. **The 5 `explorer.*` tuning-field keys were hand-applied on `feat/world-explorer-reconstruction`; `bot.worldexplorer.desc` was deliberately left unchanged** — it has a real runtime consumer (the bot icon's tooltip resource) and the snapshot's replacement text does not match the reconstructed algorithm's actual behavior (see `world-explorer-system.md` finding 10). |

New files, no `master` counterpart at all:

| File | Role |
|---|---|
| `src/nurgling/actions/bots/WorldExplorerMove.java` | Momentum-preserving movement primitives. **Reconstructed** on `feat/world-explorer-reconstruction`; only `clickAndChase` is actually called by `WorldExplorer` — `scanAhead`/`scanHeading` remain dormant, see `world-explorer-system.md` |
| `src/nurgling/actions/bots/WorldExplorerFrontier.java` | Visited-grid bookkeeping + frontier target selection. **Reconstructed** on `feat/world-explorer-reconstruction`; only `markVisited` is actually called — `pickTarget` (the frontier-selection logic) is dormant, see `world-explorer-system.md` |
| `src/nurgling/actions/bots/CrossingCandidateTracker.java` | Tracks candidate inter-continent water crossings. **Reconstructed** on `feat/world-explorer-reconstruction`; write-only, `getCandidates()` has no consumer |
| `src/nurgling/actions/bots/CliffCalibrate.java` | Debug bot: live terrain-height readout to calibrate `CliffScan`. **Not reconstructed** — tracked with `CliffAwareMove`/`CliffScan` below, since `WorldExplorer` never calls into that group |
| `src/nurgling/actions/CliffAwareMove.java` | Cliff-edge movement handling for `DynamicPf`. **Not reconstructed** — see "Reusable systems awaiting reconstruction" below |
| `src/nurgling/actions/StuckDetector.java` | Generic no-progress detector. **Reconstructed** on `feat/world-explorer-reconstruction` |
| `src/nurgling/pf/CliffScan.java` | Terrain-height discontinuity detection. **Not reconstructed** — see "Reusable systems awaiting reconstruction" below |
| `src/nurgling/pf/CoastFollower.java`, `FrontierPicker.java`, `TileField.java`, `WaterTiles.java` | Coast-following steering, frontier primitives, distance field, water classification. **Reconstructed** on `feat/world-explorer-reconstruction` — see `world-explorer-system.md` for which parts are actually reached at runtime |
| `src/nurgling/tools/NDebugLog.java` | File-based diagnostic logging. **Reconstructed** on `feat/world-explorer-reconstruction`, verbatim (still hardcodes a `worldexplorer-debug` prefix — not generalized in this pass, per the "preserve the baseline" mandate) |
| `docs/world-explorer-plan-a-fix-wall-following.md` | Plan A: fix the existing wall-following algorithm's bugs (jerky motion, stuck-on-obstacle, river-mouth 180° flip) — root causes confirmed by code reading against `GoTo.java`/`MovingCompleted*.java`. **Restored on `feat/world-explorer-reconstruction` as a historical document** (status banner added — see the doc itself) |
| `docs/world-explorer-plan-b-frontier-exploration.md` | Plan B: replace wall-following with frontier-directed travel; investigates reusing `ChunkNav`/`ExploredArea` and finds neither directly reusable (ChunkNav conflates water with wall/rock blockage) but both offer reusable primitives. **Correction, resolved by the reconstruction pass:** neither Plan A nor Plan B was implemented as literally specified. What was actually built is a third design (`CoastFollower`/`TileField`, a distance-to-land iso-contour follower) that supersedes both. Plan B's own deliverable class (`WorldExplorerFrontier.pickTarget`) exists in the final code but is not called for target selection — see `world-explorer-system.md` §7. **Restored on `feat/world-explorer-reconstruction` as a historical document** (status banner added) |

Localization: `src/lang/messages.properties`/`messages_ru.properties` gained WE-only keys
(`explorer.lookahead`, `explorer.stuck_timeout`, `explorer.backup_tiles`, `explorer.swing_tiles`,
`explorer.band_tiles`), hand-applied during the `feat/world-explorer-reconstruction` reconstruction.
`bot.worldexplorer.desc` was deliberately left at its pre-reconstruction text — see the table above.

### Separate — land-based World Explorer research (proposal, not reconstructed)

**A distinct, future, proposed bot — not a mode or dependency of the water World Explorer above.**
`CliffAwareMove.java`, `CliffScan.java`, `CliffCalibrate.java`, `DynamicPf.java`'s `cliffAware` opt-in
diff, and the excluded `cliff_calibrate` registry entry exist **only** in rescue snapshot `802a8855`
(the same one the water bot was reconstructed from) — none of that cliff-specific group has been
reconstructed on `master` or on `feat/world-explorer-reconstruction`, or anywhere else. Player-reported
cliff behavior, snapshot-era `CliffScan` calibration evidence, the rolling-horizon
(non-`ChunkNav`-dependent) design direction, and a reusable-candidate inventory (`PathFinder`/`NPFMap`,
`FrontierPicker`, `StuckDetector`, `NConfig.Key.animalrad`/`Forager`'s pattern) are recorded in
[`land-navigation-research.md`](land-navigation-research.md), routed via
[`.claude/rules/land-navigation.md`](../.claude/rules/land-navigation.md).

**Precise status, not an absolute "nothing implemented" claim:** `FrontierPicker` and `StuckDetector`
*were* restored, unchanged, on `feat/world-explorer-reconstruction` — as part of the water bot's own
core-file set (see the "New files, no `master` counterpart at all" table above and
`docs/world-explorer-system.md` §7 for exactly which parts of each are active vs. dormant there). That
restoration is **not** a land-bot implementation — no land explorer and no land-specific consumer of
either class exists on `master` or on `feat/world-explorer-reconstruction` or anywhere else. Do not
apply `land-navigation-research.md`'s conclusions to the water World Explorer without separate, explicit
approval.

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
class docs, or have a real precedent already on `master`. **Most are now reconstructed**, via branch
`feat/world-explorer-reconstruction`, as part of the World Explorer group — see
`docs/world-explorer-system.md` for their active/dormant status there. `CliffAwareMove` + `CliffScan`
(+ `CliffCalibrate`) remain fully unreconstructed and are not implemented anywhere on `master` or any
feature branch — do not cite that row below as current `master` behavior. This section is now a
decision-input for whoever reconstructs the remaining cliff-aware-movement group — promote what's still
useful into a canonical doc at that point.

| System | Snapshot location | Reusable purpose | Known consumers | Verification status | Disposition |
|---|---|---|---|---|---|
| `StuckDetector` | `src/nurgling/actions/StuckDetector.java` | Generic "no net tile progress for N seconds" detector, decoupled from any one bot | Snapshot: `WorldExplorer`. Its own class doc names a real precedent already on `master`: `src/nurgling/WaypointMovementService.java` has an inline >2.0s no-progress stuck check that this class claims to generalize — **that inline check is current, real code; the extracted shared class was snapshot-only, now reconstructed** | **Reconstructed byte-identical to snapshot.** Compiles/builds; runtime-smoke-tested 2026-08-08 — 6 timed stuck events recorded, all correctly detected (see `world-explorer-system.md` §10) | Promoted onto the World Explorer branch as-is; a later refactor of `WaypointMovementService`'s inline copy onto this class is out of scope for that branch |
| `CliffAwareMove` + `CliffScan` | `src/nurgling/actions/CliffAwareMove.java`, `src/nurgling/pf/CliffScan.java` | Detects terrain-height discontinuities along a movement segment and aims an approach point short of the edge, so the server's own auto-climb proximity check fires instead of walking into a cliff base and stopping | Snapshot: `DynamicPf.java` (opt-in `cliffAware` flag, default off), `CliffCalibrate.java` (debug bot for threshold tuning). **Confirmed: not a World Explorer consumer** — `WorldExplorer` never uses `DynamicPf` | `CliffScan`'s threshold constant carries an in-code note claiming it was calibrated from live `CliffCalibrate` readings during snapshot-era testing — **not independently re-verified this pass**; classify as runtime-tested-per-snapshot-history, not re-confirmed | **Not reconstructed** — deliberately kept out of the World Explorer branch since it has no WE call site; the elevation-discontinuity detection has no WE-specific coupling and is plausibly reusable for any bot that walks near cliffs, but needs its own separate reconstruction decision |
| `FrontierPicker` | `src/nurgling/pf/FrontierPicker.java` | Terrain-agnostic frontier-exploration primitives (ray-scan to the nearest unresolved/unloaded map edge, with loaded-grid-edge and expanding-ring fallbacks), gated by a caller-supplied acceptor predicate so land vs. water logic is the caller's problem | Snapshot: `WorldExplorerFrontier` (water). Its own class doc names an intended second consumer, `LandFrontier` — **that class does not exist anywhere, in the snapshot or on `master`; it is a proposal referenced in a doc comment, not implemented code** | **Reconstructed on `feat/world-explorer-reconstruction`, byte-identical to snapshot.** **Correction: not entirely dormant.** Its frontier-*selection* tiers (`pickFrontierPoint`, `scanLoadedGridsForFrontier`, `pickFrontierChunk`) are only reachable via `WorldExplorerFrontier.pickTarget`, which `WorldExplorer` never calls — those tiers are dormant. But `FrontierPicker.safeTileName()` specifically **is active at runtime**, called directly by `CrossingCandidateTracker.scanForCrossing` (which `WorldExplorer` does call every successful-plan iteration) and referenced by `WorldExplorerMove`'s dormant `scanAhead`/`scanHeading` helpers | Reusable pattern in principle (explicitly designed caller-agnostic); the "shared by land and water bots" claim is aspirational until a land consumer actually exists. Candidate for the future land bot researched in `docs/land-navigation-research.md` §8, pending correction of its `MCache.grids`-only-grows assumption and a land-specific acceptor |
| `TileField` | `src/nurgling/pf/TileField.java` | Chamfer distance-transform field over locally-scanned navigable terrain (distance-to-nearest-obstacle), used to derive shoreline-following gradients without depending on a specific tile-type boundary | Snapshot: `CoastFollower` | **Reconstructed on `feat/world-explorer-reconstruction`, byte-identical to snapshot, and active** — core of the reconstructed bot's real steering path | Reusable terrain-scanning pattern; currently only consumed by `CoastFollower`, no second consumer yet |
| `WaterTiles` | `src/nurgling/pf/WaterTiles.java` | Small tile-name classification helper (fresh vs. ocean, shallow/deep/deeper tiers) | Snapshot: `TileField`, `CrossingCandidateTracker`, `WorldExplorerFrontier` | **Reconstructed on `feat/world-explorer-reconstruction`, byte-identical to snapshot, and active.** Taxonomy still user-confirmed rather than independently re-derived this pass | Boat/water-bot-scoped utility; reusable for any future water bot, narrow enough it may not need its own doc |
| `CoastFollower` | `src/nurgling/pf/CoastFollower.java` | Steers along an iso-contour of `TileField`'s distance field at a chosen offset from shore (gradient-tangent steering with band-correction), so a traced coastline can't reverse direction on itself and doesn't cut across bays | Snapshot: `WorldExplorer` (the algorithm that actually superseded both planning docs — see the note above) | **Reconstructed on `feat/world-explorer-reconstruction`, byte-identical to snapshot, and active** — this is the reconstructed bot's real steering law | General shoreline-hugging steering pattern parameterized by `TileField`/chirality/band — plausibly reusable beyond World Explorer for any coast-hugging bot, not yet decided |
| `NDebugLog` | `src/nurgling/tools/NDebugLog.java` | File-based, timestamped diagnostic logging as an alternative to in-game chat, which the class doc states becomes physically uncopyable during long bot runs (no select-all/scrollbar-drag) | Snapshot: `WorldExplorer` and related classes (`FrontierPicker`, `CrossingCandidateTracker`, `StuckDetector`) | **Reconstructed on `feat/world-explorer-reconstruction`, byte-identical to snapshot** — still hardcodes the `worldexplorer-debug` prefix and per-call file open/close (deliberately not generalized or buffered in this pass, per the "preserve the baseline" mandate) | Logging pattern itself is generic and matches `docs/development-workflow.md`'s "opt-in, scoped diagnostics over permanent chat spam" guidance; generalizing the prefix (and buffering the writer) remain a future, separately-approved change |
