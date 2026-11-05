# Capability area: navigation, movement, obstacle awareness, and recovery

**Status: recovery/current, sessions 1-2, CLOSED.** The architecture/discovery phase for this
capability area is closed as of session 2 (see "Closure" at the end of this document) — runtime
verification and backlog implementation remain open, separately-tracked future work, not further
discovery. Applies the four-level methodology
(`methodology.md`) to the navigation/movement/pathfinding capability area. Session 1 was a
**breadth-first discovery pass plus targeted tracing of the highest-value, least-documented
subsystems**. Session 2 corrected a wrong upstream-compatibility assumption from session 1 (see
§14) and traced the `ChunkNav` internals session 1 had left at DISCOVERED-only (`ChunkNavPlanner`,
`ChunkNavGraph`, `ChunkNavData`, `ChunkPath`, `ChunkNavRecorder`, `UnifiedTilePathfinder`), finding
several concrete reliability and dead-code issues — see §13. This is still not full coverage of
every file in §1's registry; see §16 for the current per-subsystem table and what remains.

This document does **not** restate or supersede the existing, already-current navigation docs:

- [`world-explorer-system.md`](../world-explorer-system.md) — the water-based shore-tracing
  World Explorer bot (`TileField`/`CoastFollower`). Already TRACED/VALIDATED in detail (runtime
  smoke-tested 2026-08-08). Referenced here for cross-comparison only; not re-audited.
- [`land-navigation-research.md`](../land-navigation-research.md) — research for a *future,
  unimplemented* land-based long-distance explorer. Referenced here because this session found
  a **factual correction** to one of its claims (§11) and because the fully-implemented
  `ChunkNav` subsystem it describes as "current master" turns out to be more deeply wired into
  every session than that document implies — see §11.

Everything else below (core local pathfinding, `ChunkNav`, movement primitives, the Forager
route-walking layer, and the legacy `PatrolArea` bot) had **no prior audit documentation** before
this session.

## 1. Navigation architecture and capability registry (Phase 1 — discovery)

Full breadth-first inventory, grouped by role. Every file was located and its package-level
purpose confirmed (DISCOVERED at minimum); the level column states how far past that this session
actually got — see §16 for the formal per-file table.

| Group | Files | Role |
|---|---|---|
| **Core local pathfinding** | `actions/PathFinder.java`, `pf/NPFMap.java`, `pf/Graph.java`, `actions/DynamicPf.java` | Grid-based A* over a bounded window of currently-loaded terrain around a begin/end pair. The base layer nearly every bot's movement ultimately goes through. **TRACED** |
| **Movement primitives** | `actions/GoTo.java`, `WaypointMovementService.java`, `actions/SetDir.java`, `actions/SetDirTrol.java`, `actions/RunToSafe.java` | One-click-and-wait movement commands, vehicle-mode-aware waiting, the minimap/map-window waypoint queue. **TRACED** (`GoTo`, `WaypointMovementService`); **DISCOVERED** (`SetDir`/`SetDirTrol`/`RunToSafe` — trivial one-shot wrappers, not traced this session) |
| **Chunk-based global navigation (`ChunkNav`)** | `navigation/ChunkNavManager.java`, `ChunkNavPlanner.java`, `ChunkNavExecutor.java`, `ChunkNavGraph.java`, `ChunkNavData.java`, `ChunkPath.java`, `ChunkNavRecorder.java`, `ChunkNavIntraPathfinder.java`, `UnifiedTilePathfinder.java`, `ChunkNavBinaryFormat.java`, `ChunkNavFileStore.java`, `ChunkNavConfig.java`, `AreaNavigationHelper.java` | Persisted, cross-session, multi-layer (outside/inside/cellar) chunk graph with recorded portals (doors/stairs/mines/cellars), background recording, confidence decay (computed but **not actually consumed** by the live planner — see §13), and an implicit tile-level A* across recorded-but-not-currently-loaded chunks (`UnifiedTilePathfinder`, confirmed as the sole live planning path — see §13). **TRACED/EVALUATED as of session 2** (`ChunkNavExecutor`, `ChunkNavManager`, `ChunkNavPlanner`, `ChunkNavGraph`, `ChunkNavData`, `ChunkPath`, `ChunkNavRecorder`, `UnifiedTilePathfinder`, `ChunkNavConfig`, `AreaNavigationHelper`); **DISCOVERED** still (`ChunkNavIntraPathfinder` — confirmed in session 2 to be dead/unreachable code, see §13, so not traced further; `ChunkNavBinaryFormat`, `ChunkNavFileStore` — persistence serialization, not read) |
| **ChunkNav UI/diagnostics** | `widgets/ChunkNavVisualizerWindow.java`, `widgets/ChunkNavNavigatorWindow.java`, `bots/ChunkNavNavigatorBot.java`, `overlays/map/MinimapChunkNavRenderer.java`, `widgets/nsettings/Navigation.java` | Interactive debug/visualization tooling and the user-facing Navigation settings panel. **TRACED** (`Navigation.java` settings panel, for its config-wiring implications — see §11); **DISCOVERED** only for the visualizer/renderer windows themselves (not read) |
| **Forager route-recording/walking layer** | `actions/FollowRoute.java`, `actions/RouteWalkControl.java`, `widgets/WaypointStepsWindow.java`, `routes/ForagerWaypoint.java`, `overlays/NWaypointOverlay.java`, `actions/bots/forager/ForagerRouteConstraints.java`, `actions/bots/forager/CliffCorridorChecker.java`, `actions/bots/forager/LandCorridorChecker.java` | A *third*, independent navigation concept: user-recorded, segment-relative waypoint routes with milestone-teleport legs, dynamic no-walk zones around dangerous animals, and cliff/land corridor exclusion checks — distinct from both local `PathFinder` and `ChunkNav`, though it calls into both. **TRACED** (`FollowRoute`, `RouteWalkControl`, `ForagerRouteConstraints`, `CliffCorridorChecker`, `LandCorridorChecker`); **DISCOVERED** (`WaypointStepsWindow`, `ForagerWaypoint`, `NWaypointOverlay` — UI/data-model support, not read) |
| **Storage/container navigation bridge** | `actions/StorageNav.java` | Walks to a container known only from the DB index; falls through to `ChunkNav` when the container's grid isn't currently loaded. **TRACED** |
| **Legacy direct-click bot movement** | `actions/PatrolArea.java` | Raw `wdgmsg("click",...)` lawnmower-pattern vehicle patrol with no `PathFinder`/`GoTo` involvement at all. **TRACED** |
| **Generic stuck detection** | `actions/StuckDetector.java` | Already TRACED/VALIDATED by `world-explorer-system.md`/`land-navigation-research.md` (no-net-progress-for-N-seconds detector). Not re-traced; referenced for the cross-system comparison in §7 |
| **Diagnostics/stress test** | `actions/bots/NavigationStressTest.java` | A debug bot exercising navigation paths for manual testing. **DISCOVERED** only (class purpose confirmed via its doc comment/method names; body not read) |
| **Forager's dangerous-animal detour, cliff/water corridor checks** | see Forager route-recording layer row above | Forager is the **only** caller of `PathFinder.avoidZones` (dynamic obstacle avoidance) anywhere in the codebase — see §9 |
| **Water-based World Explorer** | `pf/TileField.java`, `pf/CoastFollower.java`, + 10 companion files | Out of scope for re-audit — see `world-explorer-system.md`. Confirmed still present at the expected paths; not re-read |

**Not found anywhere in current source** (confirms `land-navigation-research.md`'s own claim,
independently re-checked this session): `pf/CliffScan.java`, `actions/CliffAwareMove.java`,
`actions/bots/CliffCalibrate.java`, and `DynamicPf`'s `cliffAware` opt-in field. `DynamicPf.java`
as it exists on current `master` (read in full, §2) has no `cliffAware` field or any cliff-specific
logic at all — the cliff-handling group really is snapshot-only, exactly as that document states.

## 2. Core local pathfinding — `PathFinder`/`NPFMap`/`Graph`/`DynamicPf` (TRACED)

`PathFinder` (1051 lines, read in full) is the base movement action nearly everything else in
this registry either calls directly or falls back to:

- Builds a bounded square grid (`NPFMap`) centered between start and end, sized from their
  distance (capped, with a fallback-radius growth path for out-of-visible-area targets, itself
  capped at `MAX_FALLBACK_RADIUS=3000` world units to avoid an unbounded allocation stall).
- Populates the grid from live `Gob` hitboxes (`addGob`, skipping the object currently being
  ridden/towed via the `Following` attribute) and from tile terrain (land/water classification,
  optional cliff-buffer blocking via `Ridges.brokenp`, optional gate-always-closed mode for
  route planning that must not assume a door stays open).
- Runs `Graph` (an A*-shaped search, §2a) on a separate thread per candidate end-point when there
  are several equally valid approach points (`Mode.NEAREST` picks the shortest of all of them).
- Walks the resulting corner list one `GoTo` call at a time, re-planning from the character's
  actual position whenever a step comes back short.
- Has three distinct **opt-in** extension points, each used by exactly one caller group today:
  - `avoidZones` (dynamic no-walk capsules) — only ever set by Forager (§9).
  - `learnedBlocks` (invisible-obstacle memory, e.g. a young tree with no client hitbox) — set by
    Forager's walk helper and by `FollowRoute` (§5); every other caller leaves it `null` and gets
    no stall-learning at all.
  - `blockCliffBuffer` — opt-in cliff-tile blocking via `NPFMap.cliffBuffer`; not found set to
    anything but the default (`-1`, cliffs walkable) by any caller read this session.
- Static helpers (`isAvailable`, `walkLength`, `getPathCost`, `getNearestHardPoints`,
  `findCommonApproachPoints`) reuse the same `construct()` machinery to answer "can I get there"/
  "how far" questions without actually walking — used by callers outside this registry (not
  inventoried this session) for candidate ranking.

**`Graph.java`'s open-set is a `LinkedList` re-sorted by `Comparator` on every pop** (`run()`,
line 35) rather than a `PriorityQueue` — this is the **same O(n·log n)-per-pop cost already
tracked** as `perf/pathfinder-priority-queue` in `mixed-wip-recovery.md` and referenced by
`land-navigation-research.md` §10. Not a new finding — cross-referenced here because it directly
backs the core pathfinder every navigation system in this registry ultimately calls into, not just
the land-explorer proposal that document discusses it under.

**`DynamicPf.java`** (155 lines, read in full) wraps `PathFinder.construct()` in a background
`WorkerPf` task for moving targets: it re-plans on a worker thread, trims the planned path by
collision-testing straight-line shortcuts between remaining corners (`updatePath`), and loops until
the target is reached or determined unreachable (`checkDN`). No `cliffAware` field exists on this
class on current `master` (see §1's "not found" note) — the field `land-navigation-research.md` §7
describes is entirely snapshot-only.

**`GoTo.java`** (98 lines, read in full) is the actual click-and-wait primitive `PathFinder`
delegates each corner to. It dispatches movement-completion waiting through a **hardcoded
if/else chain keyed on vehicle name** (`horse`/`dugout`/`coracle`/`skis-wilderness`/`rowboat`/
`snekkja`, each with its own pose-task pair) before falling back to the default on-foot
`IsMoving`/`MovingCompleted` pair. Adding a new mounted/vehicle movement mode requires editing this
method directly — there is no registry or strategy object for "how do I know this vehicle finished
moving." See §12 finding N1.

## 3. Movement primitives: `WaypointMovementService` and the minimap waypoint queue (TRACED)

`WaypointMovementService.java` (234 lines, read in full) is the shared queue backing both the full
map window's and the corner minimap's click-to-add-waypoint movement, independent of `PathFinder`
entirely — it sends raw `wdgmsg("click",...)` to the target and polls tile-coordinate equality
itself (`processMovementQueue`) rather than using `PathFinder` or `StuckDetector`. Its own inline
stuck check (same 2.0-second threshold as `StuckDetector`'s default) is gated by
`NConfig.Key.waypointRetryOnStuck`, which **defaults to `true`** (`NConfig.java:496`): the
out-of-the-box behavior on getting stuck is to blindly resend the identical movement command to the
identical target, with no check of whether the obstacle that caused the stall has changed. If the
setting is off, the entire remaining queue is dropped outright instead. See §7 and §12 finding N4.

## 4. `ChunkNav` — persisted, multi-layer, portal-aware global navigation

This is the single largest undocumented navigation subsystem in the codebase — roughly 6,000 lines
across 13 files, plus ~1,400 more in its two visualizer/navigator windows. It is **not** the
proposed future land-explorer from `land-navigation-research.md` (that document is explicit this
is a separate, already-current system it merely depends on) and it is **not** mentioned anywhere
in `world-explorer-system.md` (the water bot never uses it). No prior audit doc covers it at all.

### 4a. `ChunkNavExecutor` (1182 lines, read in full) — TRACED

Executes a planned `ChunkPath` by delegating every actual tile-to-tile hop to `PathFinder`, and
layers chunk-specific concerns on top:

- **Multi-layer awareness**: tracks `Layer` (OUTSIDE/INSIDE/CELLAR/UNKNOWN) per segment and
  dispatches differently for same-layer vs. cross-layer (portal) segments.
- **Portal traversal with verification**: after clicking/using a door, stairs, ladder, cellar, or
  mine entrance, it explicitly re-reads the player's grid ID and compares it to the pre-traversal
  grid ID (`traversePortalGob`, line ~506) — **if the grid didn't actually change, this is treated
  as a failure**, not a silent success. This is a genuine "verify the action actually happened"
  check of exactly the kind Phase 4 of this audit asked about, and it is better than several
  patterns found elsewhere in the registry (§12 finding N2 for the contrast).
- **Bounded replanning**: `replanAndContinue` is only invoked up to `ChunkNavConfig.
  MAX_REPLAN_ATTEMPTS=3` times per executor instance before giving up with `Results.FAIL()` —
  bounded, not an infinite retry loop.
- **Portal-type-vs-target-layer compatibility filtering** (`isPortalTypeCompatible`) and a scored
  priority ranking of candidate portals (`rankPortalCandidates`) before falling back to a
  nearest-visible-gob-by-name-substring heuristic (`findNearestPortalGob`) if no recorded portal
  resolves to a live gob.
- **Two independent, divergent implementations of "where do I stand to use this portal"**: a
  `Gob`-based `getPortalAccessPoint(Gob)` (line 584) that computes the offset from the gob's actual
  `hitBox` extent, used by the `followDetailedPath`/`tryTraversePortal` code path; and a
  `ChunkPortal`-based `getPortalAccessPoint(ChunkPortal, long, NGameUI)` (line 1238) that instead
  looks up a **hardcoded per-building-name tile-offset table** (`getBuildingAccessOffset`:
  stonemansion=6, logcabin=2, timberhouse=2, stonestead=2, greathall=3, stonetower=3, windmill=2,
  primitivetent=2, minehole=2), used by the `followWaypointPath`/`traversePortal` code path. These
  two methods can drift independently and already encode the building list twice in two different
  shapes (one keyed off the real hitbox, one off a maintained name list that must be kept in sync
  by hand whenever a new building type needs portal support). See §12 finding N3.
- Falls back, when a target isn't in the planned chunk path or the plan is empty, to a bare
  incremental walk-toward-target loop (`walkTowardTarget`) with a hardcoded `Thread.sleep(100)` per
  iteration — a flat 100ms poll regardless of how far the remaining distance is.

### 4b. `ChunkNavManager` — public API surface TRACED, internals DISCOVERED only (session 1; superseded in part by §13)

Read in full for its public method signatures and class-level javadocs (924 lines; method bodies
not read this session except where excerpted above). Confirmed design:

- **Background recording is unconditional, not opt-in** — see §11, a correction to
  `land-navigation-research.md`.
- Throttled disk saves (`SAVE_THROTTLE_MS=60000`), throttled visible-grid recording
  (`RECORD_THROTTLE_MS=2000`), throttled portal-display refresh (`PORTAL_REFRESH_MS=2000`).
- **Confidence decay**: `ChunkNavConfig.CONFIDENCE_HALF_LIFE_HOURS=48` — recorded chunk data loses
  trust over time, presumably to handle world changes (building removed, terrain reshaped) without
  requiring a manual re-record. **Session 2 read the decay function (`ChunkNavData.
  getCurrentConfidence()`) and traced its consumers in full: the math is correct, but nothing in the
  live planning path reads it — see §13.1, finding N11.** This session-1 note is left here with the
  correction rather than deleted, since it correctly flagged the mechanism as worth checking and
  that follow-up is exactly what session 2 did.
- **Cheap chunk-level reachability pre-checks** (`isGridReachable`, `isAreaReachableByChunks`,
  `chunkHopsTo`, `chunkHopsFromPlayer`) via BFS over the chunk graph, explicitly documented in
  their own javadoc as cheaper than a real path search and meant to gate it ("If this returns
  false, tile-level A* will also fail... worth calling before paying for a plan"). `chunkHopsFromPlayer`
  is explicitly documented as the batched form callers ranking many candidates **must** use instead
  of calling `chunkHopsTo` per candidate, to avoid one BFS per candidate. **Session 2 checked this:
  `StorageTrailService` and `BlueprintBuilder` (the two real candidate-ranking callers found) both
  call the batched `chunkHopsFromPlayer` form, not `chunkHopsTo` in a per-candidate loop — the
  documented guidance is followed in practice, not just in the javadoc. See §16.**
- Binary on-disk format with a documented migration path from a legacy JSON format
  (`STORAGE_FILENAME`/`STORAGE_DIRNAME`), multi-instance support (`SURFACE_INSTANCE` constant,
  implying cave/instance-aware storage), and destructive operations (`deleteChunk`,
  `deleteAllChunks`) exposed as public API.
- A `@deprecated` static singleton accessor (`getInstance()`) retained specifically for code paths
  without UI access (e.g. `MCache` callbacks), with the preferred path (`gui.map.
  getChunkNavManager()`) documented right next to it — a deliberate, self-documented transition in
  progress, not an oversight.

### 4c. Supporting classes — DISCOVERED only in session 1 (method-signature/javadoc mapping); **traced in full in session 2, see §13**

Session 1 left `ChunkNavPlanner`, `ChunkNavGraph`, `ChunkNavData`, `ChunkPath`, `ChunkNavRecorder`,
and `UnifiedTilePathfinder` at DISCOVERED only (signatures/javadocs, bodies unread). **Session 2
read all of their bodies in full** — see §13 for the actual trace (route planning, staleness
handling, dead-code findings N11/N13/N14). The paragraph below is preserved as originally written
in session 1, for the historical record of what was known before that trace; treat §13 as
authoritative over anything here that it supersedes.

`ChunkNavIntraPathfinder` (A* within a single chunk when the target chunk is already loaded, used
by `ChunkNavExecutor.navigateToCoord`'s same-chunk fast path) remains DISCOVERED only — session 2
confirmed it unreachable under current wiring (§13.3, N13) and deliberately did not trace it
further, since nothing currently exercises it. `ChunkNavBinaryFormat`, `ChunkNavFileStore` also
remain DISCOVERED only (persistence serialization, not read in either session). `AreaNavigationHelper`
was traced in session 2 (§13.5, N16) — no longer DISCOVERED-only.

**`UnifiedTilePathfinder`** deserves a specific note: its class javadoc describes an **implicit
tile-level A* graph spanning every recorded chunk**, with nodes/edges computed on demand so it can
plan "through chunks that aren't currently visible, using only stored data" (line 11-16) — i.e., it
already does long-distance planning across not-currently-loaded terrain, using *pre-recorded* data.
This is a different premise from `land-navigation-research.md`'s proposed rolling-horizon design
(which explicitly must not depend on pre-recorded `ChunkNav` data, §2 of that document) — the two
are not duplicates of each other, but any future land-navigation scoping should be aware this
existing implicit-graph-A* pattern exists, in case parts of its approach (on-demand edge
computation over stored, not necessarily live, data) turn out to be relevant. Session 2 confirmed
this class is a correctly-implemented `PriorityQueue`-based A* (§13.1) with one unverified
admissibility caveat in its cross-layer heuristic (not a correctness defect — see
`modernization-backlog.md`'s "Explicitly not backlogged" note).

## 5. Forager's route-recording/walking layer — a third, independent navigation concept

`FollowRoute.java` (251 lines, read in full) walks a previously-recorded, segment-relative Forager
route (`ForagerPath`/`ForagerWaypoint`/`ForagerSection`) — this is conceptually distinct from both
§2's ad hoc point-to-point `PathFinder` calls and §4's `ChunkNav` chunk-graph planning, though it
uses both: `PathFinder` for each hop (split at `MAX_HOP_DISTANCE=250` world units, because
`PathFinder`'s own search grid fails past that span — a second magic-number distance cap
independent of `NPFMap`'s own internal sizing logic, §2), and `ChunkNavManager.planToGridCoord`/
`navigateWithPath` as a one-time bridge when the player starts the route from off-segment
(`bridgeToRoute`).

Notable design points, read in full:

- **Cooperative pause**: `control.isPaused()` is wired directly into `PathFinder.abort`, so a pause
  takes effect within the current step rather than only at a leg boundary, and the bot thread
  parks on an infinite `NTask` while paused (`awaitResume`) — `Stop` (thread interrupt) still works
  because `NCore.addTask` rethrows `InterruptedException` even through an infinite wait. This is a
  clean reuse of the same abort mechanism `PathFinder.avoidZones`'s mid-step check uses (§2) for an
  entirely different purpose (user pause vs. dynamic obstacle), rather than a parallel
  reimplementation.
- **Stall-spot learning persists for the whole route run**: `stallSpots` (the `learnedBlocks` list
  passed to every hop's `PathFinder`) accumulates across the entire route, not just within one hop
  — a spot learned on leg 3 stays blocked for leg 40.
- **Milestone-teleport legs** are detected by two adjacent waypoints sharing a `milestoneHash` and
  handled via `UseMilestone`, not walked at all.
- **Per-waypoint failure does not fail the route.** A failed hop logs a message, records the
  waypoint index in `gui.activeBotFailedWaypoints` (visible to the UI), and the loop **continues to
  the next waypoint** — the route as a whole still returns `Results.SUCCESS()` at the end
  regardless of how many waypoints failed along the way (`run()`'s final `return Results.
  SUCCESS()` after the section loop, unconditional). A caller that only checks the returned
  `Results` cannot tell "walked the whole route" from "skipped every single waypoint and arrived
  nowhere useful." See §7 and §12 finding N5.

`ForagerRouteConstraints.java` (253 lines, read in full) is the most sophisticated **dynamic**
obstacle-awareness layer found anywhere in this registry: it builds `PathFinder.AvoidZone`
instances live, from currently-loaded dangerous-animal `Gob`s (per the user's Ring Settings
dangerous-animal radii), projected forward along the animal's own observed movement vector
(`Homing`/`LinMove` heading extrapolation, `DANGER_LOOKAHEAD_S=3.0` seconds ahead) — and also
performs straight-line corridor checks against cliffs (`CliffCorridorChecker`, tile-sampled
`Ridges.brokenp`), land-vs-water (`LandCorridorChecker`, for a coracle that can't cross land), and
brush-painted exclusion zones, each capped to `MAX_*_CORRIDOR_SAMPLE_TILES=300` tiles of worst-case
sampling. **Forager is the only caller of `PathFinder.avoidZones` found anywhere in the codebase**
(confirmed by `PathFinder.java`'s own comment: "null for every bot but Forager") — every other bot
that might walk near a dangerous animal gets none of this dynamic-obstacle handling. See §9 and
§12 finding N6.

`CliffCorridorChecker`/`LandCorridorChecker` (both read in full, ~60 lines each) are small,
stateless, nearly-identical-in-shape tile-sampling corridor checks — one checking
`Ridges.brokenp`, the other `NPFMap.isValidWaterTileName` — explicitly documented in their own
class comments as deliberately *not* folded into `PathFinder`/`NPFMap` because neither has a
"forbidden tile" concept, only "blocked"/"not blocked." This is a documented, deliberate design
choice, not an oversight — recorded here as EVALUATED (compared against `PathFinder`/`NPFMap`'s
actual cell-state model, which indeed has no concept besides blocked/free), not a defect.

`StorageNav.java` (144 lines, read in full) is a third independent "bridge to `ChunkNav` when the
local grid isn't loaded" implementation, alongside `FollowRoute.bridgeToRoute` — a smaller, more
direct version doing the same `planToGridCoord`/`navigateWithPath` pattern for a single known
container coordinate rather than a route's first waypoint. Not wrong, but a second occurrence of
the same bridging pattern written independently; see §12 finding N7 for whether this is worth a
shared helper.

## 6. Legacy direct-click movement: `PatrolArea`

`PatrolArea.java` (152 lines, read in full) is a raw lawnmower-pattern vehicle patrol bot that
**never calls `PathFinder`, `GoTo`, or any other movement action** — every move is a direct
`gui.map.wdgmsg("click", ...)` to a grid coordinate, with an inline anonymous `NTask` polling
Euclidean distance-to-target, repeated **four separate times with near-identical code** in the
same method (the north/south sweep legs and the two east-step legs each have their own copy of a
`count++ > 200 && !pose.contains("walking")` give-up check plus a `dist <= 1` arrival check). No
obstacle awareness of any kind — it assumes the rectangular area is fully clear and will drive the
vehicle into anything in the pattern's path. See §12 finding N8.

## 7. Stuck detection and recovery — comparison across systems

Four independent "has movement stalled" implementations exist in the registry, with no shared base
beyond coincidentally similar thresholds:

| Implementation | Used by | Threshold | On stuck | Learns the obstacle? |
|---|---|---|---|---|
| `StuckDetector` (generic class) | Water World Explorer only (per `world-explorer-system.md`) | 2.0s, no net tile progress | Caller-driven recovery (shore back-off); escalation-to-abort confirmed dead (`world-explorer-system.md` §8 finding 2) | No — pure detection, no memory of *why* |
| `PathFinder`'s internal stall counter (`learnedBlocks`/`stalls`/`maxStalls`) | Forager (`dangerZones` caller) and `FollowRoute` only — every other `PathFinder` caller leaves `learnedBlocks` `null` | A step stopping short without a zone-abort reason, up to `maxStalls=3` per run | Blocks a small radius (`STALL_BLOCK_R`) around the stall spot for the **rest of this run and any later run sharing the same list**, then re-plans | **Yes** — the only one of the four that remembers the specific spot |
| `WaypointMovementService`'s inline check | Minimap/map-window waypoint queue | 2.0s, tile-coordinate equality | `waypointRetryOnStuck` config (**default true**): blind resend of the identical command to the identical target; if false, drops the entire remaining queue | No |
| `ChunkNavExecutor`'s portal-grid-change check | `ChunkNavExecutor` only | N/A — not a timeout, a post-condition check (did the grid actually change) | Treated as a traversal failure, triggers bounded replan (max 3) or `Results.FAIL()` | No (not applicable — this is outcome verification, not stall detection) |

None of these four share code or a common interface. `StuckDetector`'s own class doc says it was
"generalized out of `WaypointMovementService`'s existing inline check" — but `WaypointMovementService`
still has its own separate, un-migrated inline copy of that exact check on current `master`, not a
call into `StuckDetector`. See §12 finding N4.

**Outcome vocabulary**: nothing in this registry distinguishes SUCCESS/BLOCKED/UNREACHABLE/
TIMEOUT/CANCELLED/TARGET_INVALID as the audit brief's conceptual categories. Every implementation
collapses to `Results.SUCCESS()`/`Results.FAIL()`/`Results.ERROR(String)` (the shared `Action`
contract — not re-audited here, already covered in `capability-bot-lifecycle.md`). `PathFinder`
does carry richer internal state a caller *could* inspect (`blockedByAvoidZones`, `abortedByCaller`,
`dn` for "already there") but these are public fields on the `PathFinder` instance itself, not part
of the returned `Results`, so a caller must know to check the specific `PathFinder` object rather
than just the `Action` return value — an easy thing to forget, and `FollowRoute`'s own unconditional
`Results.SUCCESS()` (§5) is a concrete case where a richer per-waypoint outcome exists internally
(`gui.activeBotFailedWaypoints`) but is not reflected in what the caller actually receives back.

## 8. Destination and interaction correctness — partial, cross-referencing the workstation audit

`capability-workstation-production.md` (session 2, Phase 2) already traced position-changing
station interaction range as gated on the server-reported `Following` attribute, and the
quern/loom/etc. hitbox-but-zero-automation gaps — not re-traced here.

This session's own, new findings on approach-point selection:

- `PathFinder.findFreeNearByHB`/`findFreeNearByPos`/`getNearestHardPoints`/
  `findCommonApproachPoints` (all read in full, §2) implement "walk to a valid tile adjacent to
  this gob's hitbox," including a specific fallback for gobs with no hitbox of their own (pick-gobs
  like a mushroom rendered attached to a tree) and a dedicated `findCommonApproachPoints` for two
  objects that must both be reachable from the same standing tile (e.g. workstation + barrel) —
  this is the same category of problem `capability-workstation-production.md` found solved three
  separate ways across the workstation layer (fuel-loop bots, generic crafting path, Roastspit);
  `PathFinder` offers a fourth, more general mechanism for it, not wired into any of those three as
  far as this session checked.
- `ChunkNavExecutor`'s portal access-point calculation (§4a) is the clearest case in this whole
  registry of "reaching a valid tile vs. reaching an interactable position" being solved **twice,
  inconsistently** within one class (finding N3).

## 9. Comparison matrix — local vs. global vs. ChunkNav vs. route-walking (Phase 5, partial)

| System | Scope | Obstacle awareness | Works on unloaded/not-currently-visible terrain? | Persists across sessions? | Primary caller(s) found this session |
|---|---|---|---|---|---|
| `PathFinder`/`NPFMap`/`Graph` | Bounded local grid (sized to the begin/end span, capped) | Live `Gob` hitboxes + tile terrain, every `construct()` call; optional dynamic zones/learned blocks/cliff buffer (all opt-in, mostly unused) | No — grid is built from currently-loaded `MCache`/`Gob` state only | No | Nearly everything, directly or via `GoTo` |
| `DynamicPf` | Same as `PathFinder`, re-planned on a worker thread for moving targets | Same as `PathFinder` | No | No | Callers tracking a moving target (not inventoried individually this session) |
| `ChunkNav` (`ChunkNavManager`/`Planner`/`Executor`/`UnifiedTilePathfinder`) | Every chunk ever recorded, across the whole `MCache` session history, multi-layer | Chunk-level walkability + recorded portals; no live dynamic-obstacle awareness found (portal traversal does re-verify grid-change, §4a, but that's outcome checking, not obstacle avoidance) | **Yes** — by design, this is its reason to exist (`UnifiedTilePathfinder`'s implicit graph over stored data) | **Yes** — binary on-disk store, confidence-decayed over 48h half-life | `FollowRoute.bridgeToRoute`, `StorageNav`, the ChunkNav Navigator/Visualizer debug windows, and (narrowly) `FreeContainersInUnboxZone` via `useGlobalPf` (§11) |
| Forager route-walking (`FollowRoute` + `ForagerPath`) | A specific previously-recorded route, segment-relative | The most sophisticated *dynamic* obstacle layer in the registry (§5/§9's `ForagerRouteConstraints`), but only for Forager itself | Partially — bridges to `ChunkNav` once at route start if off-segment, then relies on `PathFinder` for every hop | The route itself persists (it's the recorded data); live obstacle state does not | Forager only |
| `WaypointMovementService` | Single current target, map/minimap click queue | None beyond the retry-or-drop stuck check (§7) | No | No | Map window + corner minimap waypoint UI |
| `PatrolArea` | Fixed rectangular area, raw clicks | None | No | No | `PatrolArea` bot only |

**Is global (`ChunkNav`) navigation under-used relative to what it could offer?** Partially
confirmed, not fully validated this session: `useGlobalPf` is presented in the Navigation settings
panel as a general pathfinding preference (§11) but is read by exactly one bot
(`FreeContainersInUnboxZone`) at exactly two call sites, both gating the same narrow scenario
(walking to a storage pile whose gob isn't currently loaded). Every other bot that could plausibly
benefit from `ChunkNav`'s cross-chunk, works-when-unloaded reach — most obviously any bot that
walks to a DB-indexed location outside the currently-loaded grid, which `StorageNav` already solves
with a direct `ChunkNavManager` call rather than this config flag — does not consult `useGlobalPf`
at all. Whether that is because `useGlobalPf` is an intentionally narrow, scenario-specific toggle
that happens to share a confusingly general-sounding name, or a half-finished wiring job, was
resolved in session 2 (§13.4, finding N15): the evidence (a commented-out sibling call at both
read sites) points to an incomplete/abandoned migration, not deliberate scope.

## 10. Performance hypotheses (Phase 6 — not measured, static-code only)

- **`ChunkNavManager.tick()` runs unconditionally every map-view tick for every player**, regardless
  of whether `ChunkNav` overlay/visualization is ever opened (§11) — background grid recording
  (throttled to once per 2s), portal-display refresh (throttled to once per 2s), and periodic saves
  (throttled to once per 60s) are therefore steady-state background cost for every session, not
  opt-in cost for players who actually use chunk-aware bots. Not measured; a profiling candidate.
- `Graph.java`'s `LinkedList`-resorted-every-pop open set (§2) — already tracked elsewhere
  (`perf/pathfinder-priority-queue`), repeated here because it is now confirmed to back *every*
  system in this registry that calls `PathFinder`/`DynamicPf`, not just the land-explorer proposal.
- `ChunkNavExecutor.walkTowardTarget`'s flat `Thread.sleep(100)` per iteration (§4a) is a fixed poll
  interval regardless of remaining distance or `PathFinder` call cost — not necessarily wasteful,
  but not instrumented either.
- `PatrolArea`'s four duplicated inline `NTask` polls (§6) are a negligible perf concern on their
  own (one active bot) but are a straightforward simplification target since they're identical.
- Not measured, not instrumented, no runtime numbers produced this session — all of the above are
  static-code hypotheses per the methodology's VALIDATED-requires-explicit-caveats rule, not
  measured bottlenecks.

## 11. Correction to existing documentation — `ChunkNav` is not opt-in at the recording layer

`land-navigation-research.md` §2 describes `ChunkNav` as "an opt-in, persisted, background-recorded
navigation database (`NConfig.Key.chunkNavOverlay`, default off...)". Direct source reading this
session shows this conflates two different things:

- `NConfig.Key.chunkNavOverlay` (default `false`) gates only the **visual overlay** — `NMapView.
  java` reads it solely to decide whether to build overlay labels (comment: "Only creates labels if
  chunkNavOverlay config is enabled," line ~811).
- The recording/tracking engine itself (`ChunkNavManager`) is **constructed unconditionally** in
  `NMapView`'s initialization (`chunkNavManager = new ChunkNavManager()`, unconditional, no config
  check) and its `tick()` is called **unconditionally every map-view tick**
  (`NMapView.java:1362-1368`, inside a `MovementTrace` diagnostics stage, with no surrounding
  `if (chunkNavOverlay)` or similar guard). Background grid recording, portal tracking, and
  throttled disk saves therefore run for every player, every session, whether or not they have ever
  opened a `ChunkNav`-aware window or bot.

This does not change `land-navigation-research.md`'s own conclusions (it was discussing whether a
*future land bot* should depend on pre-recorded `ChunkNav` data as a prerequisite, which is an
orthogonal question to whether the recorder itself runs by default) — but the specific "default
off" characterization of the system as a whole is incorrect for the recording layer, only accurate
for the overlay visualization. Flagging per this audit's authority model: code wins over a stale
doc characterization. Recommend `land-navigation-research.md` §2 be corrected to separate "the
overlay is opt-in" from "the recorder runs unconditionally" the next time that document is touched;
not fixed in this pass per this audit's own no-production-changes/docs-are-the-deliverable scope
(and because this document, not that one, is this session's actual assignment).

## 12. Verified findings and modernization candidates (Phase 7)

Confidence tiers per this audit's established convention: **CONFIRMED** (read directly, cross-checked
across call sites where relevant) vs. **PLAUSIBLE** (strong code-level evidence, not independently
re-derived from a second angle).

| # | Finding | Tier | Evidence |
|---|---|---|---|
| N1 | `GoTo`'s vehicle-mode wait dispatch is a hardcoded name-keyed if/else chain, not a registry/strategy — adding a new mount/vehicle type means editing this method directly | CONFIRMED | `GoTo.java:47-77`, §2 |
| N2 | `ChunkNavExecutor` verifies portal traversal actually changed the player's grid before treating it as success (good pattern); most of the rest of the registry has no equivalent "did the action actually take effect" check beyond distance-to-target | CONFIRMED (the good pattern); PLAUSIBLE (the "most of the rest" generalization — not every caller in the registry was individually checked for this) | `ChunkNavExecutor.java` `traversePortalGob`, §4a |
| N3 | Two independent, divergently-implemented `getPortalAccessPoint` methods inside `ChunkNavExecutor` — one hitbox-derived, one a hand-maintained per-building-name magic-number table — reachable from two different code paths in the same class | CONFIRMED | `ChunkNavExecutor.java` lines 584 and 1238, §4a |
| N4 | Three independent stuck-detection implementations with no shared interface (`StuckDetector`, `PathFinder`'s internal stall counter, `WaypointMovementService`'s inline check); `StuckDetector`'s own doc claims it was generalized out of `WaypointMovementService`'s check, but that class still has its own un-migrated copy | CONFIRMED | §7 table, `StuckDetector.java` class doc vs. `WaypointMovementService.java:195-216` |
| N5 | `FollowRoute` returns unconditional `Results.SUCCESS()` regardless of how many waypoints along the route failed; per-waypoint failures are only visible via a UI-facing set (`gui.activeBotFailedWaypoints`), not the returned `Results` | CONFIRMED | `FollowRoute.java` full method body, §5 |
| N6 | `PathFinder.avoidZones` (dynamic obstacle avoidance) has exactly one caller group (Forager) despite being a generic opt-in field on a widely-used class | CONFIRMED (by `PathFinder`'s own source comment plus this session's registry-wide read) | `PathFinder.java:38`, §9 |
| N7 | The "bridge to `ChunkNav` when the local grid isn't loaded" pattern is implemented independently twice (`FollowRoute.bridgeToRoute`, `StorageNav.navigateToContainer`) | CONFIRMED | §5 |
| N8 | `PatrolArea` has zero `PathFinder`/obstacle-awareness integration and four near-identical duplicated inline stuck/arrival polls within one method | CONFIRMED | `PatrolArea.java` full read, §6 |
| N9 | `useGlobalPf` is presented as a general Navigation-settings preference but is consumed by exactly one bot at two call sites gating one narrow scenario; `useHFinGlobalPF` (a second, similarly-named config key) is declared and defaulted but has **zero** consumers anywhere in `src/` | CONFIRMED (both halves independently grepped across the full source tree) | `NConfig.java:131,441-442`; `FreeContainersInUnboxZone.java:69,91`; `Navigation.java` §11, §9 |
| N10 | `ChunkNavManager`'s background recording/tick runs unconditionally for every session regardless of the `chunkNavOverlay` opt-in flag's state — a correction to `land-navigation-research.md`'s "default off" characterization | CONFIRMED | §11 |

**None of these findings have been implemented or fixed** — per this audit's execution
requirements, no production Java was changed. They are candidates for
`modernization-backlog.md`, not completed work.

### Session 2 additions to this table

| # | Finding | Tier | Evidence |
|---|---|---|---|
| N11 | `ChunkNavData`'s time-decayed confidence (`getCurrentConfidence()`, 48h half-life) and `ChunkNavConfig.UNCERTAINTY_PENALTY` are computed and wired into `ChunkNavGraph.calculateEdgeCost()`, but that method and its caller `getEdges()` have **zero callers anywhere in `src/`** — confirmed by exhaustive grep. The actual live planning path (`ChunkNavPlanner` → `UnifiedTilePathfinder.findPath()`) never reads confidence at all; it treats every recorded cell's binary walkability (0/1/2) as equally trustworthy regardless of age. A chunk recorded weeks ago and a chunk recorded a minute ago are planned through identically | CONFIRMED | §13.1 |
| N12 | `ChunkNavExecutor.replanAndContinue()` unconditionally returns `Results.FAIL()` when `targetArea == null` — before attempting any replan. Both of the registry's two callers that plan via grid-coordinate (not area) targets — `FollowRoute.bridgeToRoute` and `StorageNav.navigateToContainer` — call `ChunkNavManager.navigateWithPath(path, null, gui)`, i.e. with a null area. For those two callers, every `MAX_REPLAN_ATTEMPTS`-gated replan branch in `followDetailedPath`/`followWaypointPath` is dead: the `replanAttempts < MAX_REPLAN_ATTEMPTS` check passes, `replanAndContinue` is called, and it fails immediately regardless of how many attempts remain. A single failed segment, portal, or cross-layer walk during a Forager route-bridge or a storage-container walk ends that `ChunkNav` traversal with zero resilience, even though the budget exists and is checked | CONFIRMED | §13.2 |
| N13 | `ChunkPath.waypoints` is never written anywhere in `src/nurgling/navigation/**` — every current planner method (`planToArea`/`planToCoord`/`planToAreaCorner`/`planToGridCoord`) populates only `path.segments` via `UnifiedTilePathfinder.UnifiedPath.populateChunkPath()`. Since `ChunkPath.isEmpty()` checks `waypoints.isEmpty()` (always true) and `ChunkNavExecutor.run()` dispatches to `followWaypointPath()` only when `!path.isEmpty()`, that branch — and everything it exclusively reaches (`followWaypointPath`, `navigateToCoord`'s intra-chunk branch, `ChunkNavIntraPathfinder`, the hardcoded-building-offset `getPortalAccessPoint(ChunkPortal,...)` overload from N3) — is unreachable dead code under every currently-producible `ChunkPath`. This **downgrades N3's practical severity** (the divergent second implementation never actually executes) but adds its own finding: a large, plausible-looking, never-exercised code path that both this fork and upstream still carry and that a future bugfix could easily be misapplied to | CONFIRMED (static reachability traced via direct data-flow read, not inferred) | §13.3 |
| N14 | `ChunkNavGraph`'s "legacy chunk-level A*" methods (`getEdges()`, `ChunkEdge`, `calculateEdgeCost()`) and `ChunkNavPlanner`'s own-labeled "Legacy chunk-level A* methods (kept for compatibility)" section (`findChunksNearArea`, `getAreaLocalCoord`) are, like N13, unreachable from the current planning path — `planToArea`/`planToCoord`/`planToAreaCorner`/`planToGridCoord` all route through `UnifiedTilePathfinder` and only fall back to `findChunksNearArea`/`getAreaLocalCoord` inside `findTargetLocation`'s own Strategy 3, which *is* reachable (this one is live, unlike N13) | CONFIRMED for `ChunkNavGraph.getEdges()`/`calculateEdgeCost()` (zero callers); the `ChunkNavPlanner` "legacy" methods are live, not dead — corrected distinction from an earlier draft of this finding | §13.1, §13.3 |
| N15 | `useGlobalPf` resolved: reading `FreeContainersInUnboxZone.java`'s two call sites in full shows both gate the identical scenario (stockpile gob vanishes mid-unbox; optionally walk back to the unbox-specialization area) and sit next to a commented-out `RoutePointNavigator` call at both sites — the surrounding code reads as mid-refactor/abandoned, not a deliberately scoped feature. With the config's documented default of `false`, the one behavior it gates (returning to the unbox area when the pile disappears) never fires out of the box | CONFIRMED | §13.4 |
| N16 | `NUtils.navigateToArea`/`navigateTo`/`navigateToArea(Specialisation)` already form a clean, correctly-layered shared dispatcher — try `AreaNavigationHelper.isAreaReachableByLocalPF` (which itself bails immediately if the area isn't loaded, explicitly to avoid running local PF on an unloaded target) first, fall back to `ChunkNavManager.navigateWithPath` across a parallel 4-corner `ChunkNavPlanner` search otherwise. This is the "right" pattern several of this document's other findings were implicitly measuring against. `FollowRoute.bridgeToRoute` and `StorageNav` (N7) do not use it — they hand-roll their own `planToGridCoord`+`navigateWithPath(path, null, ...)` call, which is both duplicated logic (N7) and, per N12, loses all replan resilience that routing through the canonical path would not have | CONFIRMED (positive finding — this is evidence the registry is not uniformly primitive; one dispatcher is already good) | §13.5 |

## 13. ChunkNav internals — deep trace (Phase 2, session 2)

Session 1 stopped at the public API surface of `ChunkNavPlanner`/`ChunkNavGraph`/`ChunkNavData`/
`ChunkPath`/`ChunkNavRecorder`/`UnifiedTilePathfinder` (DISCOVERED only). Session 2 read every one
of those files' bodies in full. This section answers the user's specific questions about how
routing, staleness, portals, stuck/recovery, and replanning actually work — not just what the
classes are named.

### 13.1 How routes are planned, and how stale data is (and isn't) accounted for

All four public planning entry points on `ChunkNavPlanner` (`planToArea`, `planToCoord`,
`planToAreaCorner`, `planToGridCoord`) resolve a start/target grid+local-tile pair and delegate to
`UnifiedTilePathfinder.findPath()`, a proper `PriorityQueue`-based A* (not the `LinkedList`
re-sort pattern flagged for `pf/Graph.java` in session 1 — this is a different, correctly
implemented search) over an **implicit tile graph**: nodes are walkable tiles in any recorded
chunk (not just currently-loaded ones), edges are same-chunk 8-directional steps, cross-chunk edge
crossings via persisted `neighborNorth/South/East/West` grid-ID links, and portal connections
(door/stairs/mine/cellar) scored by proximity (2 tiles normally, 6 for building exteriors) and
validated against the target chunk's layer (`isPortalTargetLayerValid` — e.g. a `MINEHOLE` portal
is only considered valid if it leads to an `"outside"`-layer chunk; this skips "stale connections"
where a previously-recorded portal link no longer makes sense). `maxIterations = 500000` bounds
the search but is a safety valve, not a normal-case limit.

**Staleness handling is binary walkability only, not confidence-weighted, despite the
infrastructure existing for the latter (N11).** `ChunkNavData.walkability[cx][cy]` is a plain
0/1/2 byte per half-tile cell, set once when the cell is observed and left as-is until the player
revisits and re-observes it (`ChunkNavRecorder.mergeWalkability`/`sampleWalkability` fully
re-sample every visible cell on every throttled recording tick — see §13.6 — so data *can* self-
heal on a revisit, but nothing proactively re-verifies a chunk the player hasn't walked back
through). `ChunkNavData.getCurrentConfidence()` computes a real, correctly-implemented exponential
decay (`confidence * 0.5^(ageHours/48)`) and `ChunkNavConfig.UNCERTAINTY_PENALTY` exists
specifically to let low-confidence chunks cost more in path selection — but neither is read by
`UnifiedTilePathfinder` or by any reachable code path in `ChunkNavPlanner`. The only place
confidence is consumed is `ChunkNavGraph.calculateEdgeCost()`, called only from
`ChunkNavGraph.getEdges()`, which — per exhaustive grep — has no callers anywhere in `src/` (N11).
**Answer to "how reliable is navigation when cached info is outdated": the system computes
exactly the signal needed to answer that question (confidence per chunk) and then does not use it
anywhere live.** A chunk last recorded 10 minutes ago and one last recorded 3 weeks ago (whose
confidence has decayed to a small fraction under the 48h half-life) are indistinguishable to the
planner. This is a real design gap, not a hypothesis — the dead code path is the direct evidence.

**What does partially compensate for staleness: live local re-pathfinding during execution** (see
§13.2) — because `ChunkNavExecutor` re-derives a fresh `PathFinder` search for roughly every 20
tiles of travel using live, currently-visible `Gob` hitboxes, a genuinely new obstacle within that
stretch (a built fence, a dropped pile, a moved cart) is avoided at the local level even though the
global route was planned from a walkability snapshot that might predate it. What is **not**
compensated for: the global route *shape* itself being wrong (e.g. a building was added across
what the stale data still thinks is open ground, so the long-range route choice itself — which
chunk/portal to use — never gets reconsidered just because it's stale; only a *local* execution
failure forces a replan, and even then only for area-based targets, see N12).

### 13.2 Reliability, recovery, and replanning during execution

`ChunkNavExecutor.followDetailedPath()` (the only path actually reachable — see §13.3) walks each
`ChunkPath.PathSegment` in order. For same-layer segments it calls `followSegmentTiles()`, which:

- Samples target waypoints roughly every 20 tile-steps along the segment (not every tile — this
  bounds how many local `PathFinder` searches a long segment costs, directly answering the "is
  there unnecessary repeated path computation" performance question: no, cross-chunk segments are
  deliberately coarsened before each local search, not searched tile-by-tile).
- Checks `ChunkNavData.getWalkability()` on the sampled target *before* spending a `PathFinder`
  call on it; if blocked, it scans backward through the segment's own step list for the nearest
  walkable step instead of blindly retrying the same blocked point.
- On local-`PathFinder` failure, retries at 5-tile sub-intervals, then single-tile steps, before
  giving up on the segment — a real, graduated fallback, not a blind retry of an unchanged command.
- For `PORTAL`-type segments, actively polls for the recorded portal's gob becoming visible and,
  once visible, pathfinds to a building-specific access point (hitbox-derived
  `getPortalAccessPoint(Gob)` — the live implementation, see N13) rather than the portal's raw
  coordinate.

Portal traversal itself (`traversePortalGob`) verifies the player's grid ID actually changed after
interacting with the portal gob — i.e. it checks the action *took effect*, not just that the click
succeeded (session 1's N2, re-confirmed). Portal candidate selection (`rankPortalCandidates`)
scores by "connects to the expected target grid" (+100), "has any recorded connection" (+10), and
"name matches the expected portal type for this layer transition" (+50) — a real prioritization,
not a first-match.

**Bounded replanning exists and is a real recovery mechanism — for area-based targets.**
`MAX_REPLAN_ATTEMPTS = 3` gates every failure branch in `followDetailedPath`/`followWaypointPath`.
On a replan, `replanAndContinue()` calls `ChunkNavManager.forceRecordVisibleGrids()` first — a live
re-scan of whatever's currently visible — before re-planning with
`setExcludedPortalChunks(failedPortalChunks)`, so a portal that just failed won't be re-selected on
the retry (a genuinely intelligent replan, not a repeat of the same action: failed portal chunks
accumulate in `failedPortalChunks` and are passed forward through every subsequent replan
attempt via `newExecutor.failedPortalChunks.addAll(this.failedPortalChunks)`).

**But `replanAndContinue()` immediately returns `Results.FAIL()` if `targetArea == null` — before
calling `forceRecordVisibleGrids()` or attempting anything (N12).** Both callers that plan via
grid-coordinate rather than area (`FollowRoute.bridgeToRoute`, `StorageNav.navigateToContainer`)
pass `area = null` into `ChunkNavManager.navigateWithPath(path, null, gui)`. For those two callers
specifically, the entire replan budget is dead: a single failed segment, portal, or cross-layer
walk ends the traversal immediately, with the `MAX_REPLAN_ATTEMPTS` check having already passed
(so the code *looks* like it's about to retry, and doesn't). This is the concrete, source-level
answer to "does it replan intelligently or repeat ineffective actions, and does that differ by
caller" — yes, and the difference is not by design, it's a null-check gap in a shared executor
that two different callers exercise differently.

**Correction (PR #30 review, 2026-11-05):** the earlier "two callers" scope claim above and in
N12 is incomplete. At `2d57f8331`, `Forager.run` (`Forager.java:225`), `GhostConstruction.goTo`
(`GhostConstruction.java:129`), and `NUtils.navigateTo(NGlobalCoord)` (`NUtils.java:958`) also call
`navigateWithPath(path, null, gui)`. `ChunkNavManager.navigateWithPath` passes that area unchanged
to `ChunkNavExecutor`, so these paths encounter the same immediate failure if replanning is
needed. `FollowRoute` and `StorageNav` are examples, not an exhaustive caller list. This expands
the affected scope without changing the underlying defect or claiming a live reproduction.

### 13.3 Dead code: two things that look like alternate implementations are actually one live path and one unreachable one

`ChunkNavExecutor.run()`'s top-level dispatch is:

```java
if (path.hasDetailedPath()) return followDetailedPath(gui);      // segments non-empty
if (!path.isEmpty()) return followWaypointPath(gui);             // waypoints non-empty
if (path.isEmpty() && targetArea != null) return navigateToTargetArea(gui);
return Results.FAIL();
```

`ChunkPath.hasDetailedPath()` checks `!segments.isEmpty()`; `ChunkPath.isEmpty()` checks
`waypoints.isEmpty()`. Grepping all of `src/nurgling/navigation/**` for writes to `.waypoints`
(`waypoints.add`, `.waypoints =`) returns **zero matches** — no current planner method ever
populates `ChunkPath.waypoints`; every one of them builds `path.segments` instead via
`UnifiedTilePathfinder.UnifiedPath.populateChunkPath()`. Since `waypoints` is always empty,
`isEmpty()` always returns `true`, so `!path.isEmpty()` is always `false`: **`followWaypointPath()`
cannot be reached from any currently-producible `ChunkPath`.** This is a static data-flow fact, not
an inference from naming or comments (the methodology.md "Oven/Bread" lesson applied directly: the
class/method names and javadocs all read like a live alternate path, and only tracing where the
field is actually written reveals it isn't).

This means session 1's N3 (two divergent `getPortalAccessPoint` implementations) needs a
**severity correction**, not a retraction: the hitbox-derived overload (used by
`followSegmentTiles`/`findAndTraversePortalToGrid`, both inside the live `followDetailedPath` path)
is the only one that ever executes. The hand-maintained per-building-offset overload (used only by
`followWaypointPath`/`traversePortal`) is unreachable. The dead-code surface this drags in is
larger than just that one method: `followWaypointPath`, `navigateToTargetArea`'s only caller inside
it, `navigateToCoord`'s intra-chunk branch, and all of `ChunkNavIntraPathfinder` are unreachable
under present wiring. **Practical consequence: this is a correctness non-issue (nothing is actually
inconsistent at runtime) but a maintenance hazard** — a future contributor (fork-side or, since this
code is upstream-identical per §14, upstream itself) fixing a reported portal-access bug has a
roughly 50/50 chance of editing the implementation that never runs, because nothing in the source
flags one as live and the other as dead.

A second, smaller dead-code pocket exists in `ChunkNavGraph`: `getEdges()`, the `ChunkEdge` class,
and `calculateEdgeCost()` (the method that reads confidence/walkability/portal cost into a single
edge weight, see N11) have zero callers in `src/`. `ChunkNavPlanner`'s own "Legacy chunk-level A*
methods (kept for compatibility)" section is *not* in the same situation — `findChunksNearArea`/
`getAreaLocalCoord` are reachable, as Strategy 2/3 fallbacks inside `findTargetLocation` when an
area's grid references aren't directly in the graph yet. Don't conflate the two: one "legacy"-
labeled block is live, the other unlabeled one is not.

### 13.4 `useGlobalPf` resolved

Reading `FreeContainersInUnboxZone.java`'s two call sites (lines 69 and 91) in full: both occur
after a stockpile gob (`pile`) the bot was unboxing disappears mid-loop (`Finder.findGob(pile.id)
== null`), and both are guarded identically: `if (Finder.findGob(pile.id) == null && (Boolean)
NConfig.get(NConfig.Key.useGlobalPf)) { context.goToArea(Specialisation.SpecName.unbox); }`. Both
sit immediately above a commented-out `new RoutePointNavigator(this.closestRoutePoint).run(...)`
call. This reads as an in-progress or abandoned migration (the commented sibling call is the
strongest signal), not a deliberately scoped feature flag — and with the config's own documented
default of `false`, this one behavior (walk back to the unbox area when the pile vanishes) simply
never fires for a user who hasn't discovered and manually enabled a checkbox labeled as a general
"Pathfinding" preference in the Navigation settings panel. This answers session 1's open N9
question directly: not intentional scope, an incomplete migration.

### 13.5 A working example of good layering: `NUtils`'s shared area-navigation dispatcher

`NUtils.navigateToArea(NArea, boolean)` / `navigateToArea(Specialisation)` / `navigateTo
(NGlobalCoord)` are a shared, correctly-layered entry point several bots already call: try
`AreaNavigationHelper.isAreaReachableByLocalPF(area)` first — which itself checks `area.isVisible()`
before doing anything else, explicitly to avoid ever running expensive local `PathFinder` searches
against a target outside the loaded world — and only when that fails, fall back to
`ChunkNavManager`: plan to all 4 corners of the area in parallel (`findShortestPathToAreaCorners`)
and execute the shortest one via `navigateWithPath(bestPath, area, gui)` (note: `area`, not `null`
— this caller gets full replan resilience per N12). `navigateTo(NGlobalCoord)` does the analogous
thing for a single bookmarked point: try local `PathFinder.isAvailable` first, fall back to
`ChunkNavManager.planToGridCoord`+`navigateWithPath(path, null, gui)` (this one *does* pass `null`,
so a bookmark-return mid-journey failure has the same N12 gap), then snap onto the exact spot with
a final local `PathFinder` call.

This resolves several of the user's §3 questions about "is local pathfinding unnecessarily used
outside the loaded world" and "is ChunkNav functionality overlooked" in the **positive** direction
for this particular dispatcher: it is not overlooked, and local PF is not misapplied here. The
negative finding is narrower and already covered by N7/N12: `FollowRoute`/`StorageNav` don't go
through this shared dispatcher at all — they reimplement a narrower version of the same "try
local, fall back to chunk nav" idea themselves, independently, and the version they wrote happens
to drop the replan resilience that routing through `NUtils`'s pattern (or at least passing a
non-null area / fixing N12 directly) would preserve.

### 13.6 Background recording cost (confirms, doesn't just hypothesize, part of session 1's N10)

`ChunkNavRecorder.sampleWalkability`/`mergeWalkability` each iterate the full `CELLS_PER_EDGE ×
CELLS_PER_EDGE` (200×200 = 40,000) cell grid for every grid passed to `recordGrid()`, checking
terrain tile type and gob-hitbox overlap for every cell inside the ~50-cell (~25-tile) visibility
radius; cells outside that radius are left unobserved (cheap to skip) but the double loop itself
always runs all 40,000 iterations with an early-skip check inside, not a radius-bounded loop. This
is called from `ChunkNavManager.tick()` subject to `RECORD_THROTTLE_MS = 2000`, for every grid
currently loaded and visible — typically several at once. **This confirms the shape of session 1's
"background recording could incur unnecessary overhead" concern at the source level**: the work is
O(40,000 × loaded-grid-count) every 2 seconds, unconditionally, for every player, whether or not
any bot is doing `ChunkNav`-based navigation at that moment (per session 1's N10/§11, the tick
itself is not gated by any opt-in setting). Whether this is *actually* a measurable frame-time
problem is still a hypothesis — no profiling was run — but the existence and rough cost shape of
the work is now a direct source citation, not a guess.

## 14. Upstream compatibility (Phase 8 — corrected in session 2)

**Session 1 got this wrong and session 2 corrects it.** Session 1 reasoned that because every file
in this registry lives under `src/nurgling/**` — the fork's own automation layer per `CLAUDE.md`,
not Hafen-upstream-derived `src/haven/**` — there was no merge risk to assess and the only open
question was whether these files were fork-exclusive relative to the separate Nurgling2 `upstream`
remote (`aleksandrsvoboda/nurgling2`). **That reasoning is invalid.** `src/nurgling/**` is not
fork-exclusive by construction: the Nurgling2 project this fork tracks (`upstream`) *also* has a
full `src/nurgling/**` tree, including this exact navigation subsystem. Directory alone never
tells you fork-vs-upstream; only a diff against the `upstream` remote does.

**What was actually checked this session**, via `git fetch upstream` and direct diff against
`upstream/master` (commit `7eceaea6f` at the time of this session):

- Every file in this registry's inventory (§1) — all 34 files across `actions/`, `pf/`,
  `navigation/`, `widgets/nsettings/`, `overlays/map/`, `bots/`, and `WaypointMovementService.java`
  — is **byte-identical to `upstream/master`** (`git diff --stat upstream/master HEAD -- <file>`
  returns empty for every one of them). This is the same "shared and unmodified" classification the
  bot-lifecycle and workstation-automation capability areas already found for their own core
  contracts (`upstream-compatibility.md`, `capability-workstation-production.md` Phase 5) — the
  navigation registry is no exception.
- The merge-base between this fork's `HEAD` and `upstream/master` is `2ce51342e` (matching the
  "Merge upstream Nurgling2 through 2ce51342" commit visible in this branch's recent history).
  `upstream/master` is 30 commits ahead of that merge-base; **none of those 30 commits touch any
  file in this registry** (`git diff --stat 2ce51342e upstream/master -- <every file in §1>` is
  empty) — confirmed by diffing the actual file list, not by assumption. So this fork is not just
  historically in sync with upstream for navigation, it is *currently* in sync: there is nothing to
  pull.
- **Conclusion for every file in this registry: shared and identical to Nurgling2 upstream.** Zero
  files are fork-exclusive. Zero files are shared-but-modified. This downgrades every
  upstream-merge-risk rating in `modernization-backlog.md`'s navigation section from "no upstream
  Nurgling2 counterpart, low risk" (session 1's guess) to a different and more important risk: **any
  of our own future fork-side edit to these files is a fork-side fix for a bug that also exists
  upstream**, in code upstream itself is actively developing (see below) — so an un-upstreamed local
  patch risks a conflict the next time this fork syncs, in direct proportion to how much upstream
  keeps touching that file.

- **Churn and recency, from `upstream/master`'s own commit history** (this determines actual
  merge-conflict risk, not directory location):
  - `ChunkNav` (`src/nurgling/navigation/**`) was created upstream on 2025-12-29 (`Feat/one more
    navigation system (#259)`) and is **still under active upstream development**: 18 commits
    touched `src/nurgling/navigation/` in the 90 days before this session, and the most recent one
    (`ced46c17d`, "pathfinding") landed 2026-10-22 — about two weeks before this session. Commit
    messages in that history (`Remove ChunkNav gate-handling: out of scope for Forager PR`,
    `ChunkNavExecutor.waitForGateState: use the task queue, not a raw sleep loop`) show upstream
    treats this subsystem as still being actively shaped, partly in service of the Forager bot —
    consistent with this audit's own finding that Forager is `ChunkNav`'s dominant real consumer
    (§5, §9).
  - `src/nurgling/actions/PathFinder.java` and `src/nurgling/pf/**` were also touched on
    2026-10-22, same day as the most recent `ChunkNav` commit (`Plugin hooks for map tools, opt-in
    cliff blocking in PathFinder`) — this is the commit that introduced the `blockCliffBuffer`
    field session 1 found unused by any caller (§2/backlog); it is unused because it is **brand
    new upstream, not fork-neglected**. `PathFinder.java`/`pf/**` have 134 commits total across
    their history, making them upstream's most mature, highest-traffic files in this registry.
  - **Practical implication for any recommendation in this document**: treat every ChunkNav/
    PathFinder recommendation as **high upstream-churn, not low-upstream-risk**. A local fork patch
    to `ChunkNavExecutor.java` or `PathFinder.java` has real odds of colliding with an upstream
    commit on the next sync, higher than for most other files in this fork. Where a fix is general
    (not fork-specific behavior), the lower-conflict-risk path is contributing it upstream rather
    than patching the fork in place — this document doesn't decide that trade-off, but every backlog
    item below states it explicitly per-item rather than defaulting to "low risk" by directory.

What **remains unchecked**: whether any *other* capability area's files (inventory, UI, etc.) share
this same "looks fork-exclusive by directory, is actually upstream-identical" trap — this session
only re-verified navigation. Any future capability area should diff against `upstream` directly
before asserting fork-exclusivity, rather than relying on directory location as session 1 did here.

## 16. Coverage table and what remains

| Subsystem / implementation | Level | Why | Evidence location |
|---|---|---|---|
| `PathFinder` | **EVALUATED** | Traced in full; compared against `DynamicPf`, `ChunkNavExecutor`'s per-hop delegation, and `GoTo`'s blocking-wait pattern vs. `WorldExplorerMove`'s non-blocking one (cross-referenced from `world-explorer-system.md`) | §2 |
| `NPFMap`/`Graph` | **EVALUATED** | Traced in full; `Graph`'s open-set structure cross-referenced against the already-tracked perf proposal | §2 |
| `DynamicPf` | **TRACED** | Read in full; not separately compared against alternatives beyond noting it shares `PathFinder`'s machinery | §2 |
| `GoTo` | **EVALUATED** | Traced in full; compared against `WorldExplorerMove.clickAndChase`'s non-blocking design (cross-ref) and flagged for its hardcoded vehicle dispatch | §2, §12 N1 |
| `WaypointMovementService` | **EVALUATED** | Traced in full; compared against `StuckDetector` and `PathFinder`'s stall handling | §3, §7 |
| `ChunkNavExecutor` | **EVALUATED** | Traced in full; internal inconsistency (N3) found by direct comparison of its own two access-point methods | §4a, §12 |
| `ChunkNavManager` | **EVALUATED** (session 2 — was API-surface-only) | `chunkHopsToAny`/`chunkHopsFromPlayer`/`isGridReachable`/`isAreaReachableByChunks` bodies read in full: confirmed to use direct neighbor/portal BFS, not `ChunkNavGraph.getEdges()` (which is dead, N11/N14) — a correct, cheap reachability pre-check deliberately kept separate from cost-weighted planning. Background-tick-unconditional finding (N10) remains confirmed from caller-site (`NMapView`) reading | §4b, §11, §13 |
| `ChunkNavPlanner` | **EVALUATED** (session 2 — was DISCOVERED) | Read in full; all four planning entry points traced to `UnifiedTilePathfinder.findPath()`; the "legacy chunk-level A*" fallback strategies inside `findTargetLocation` confirmed live (N14) | §13.1, §13.3 |
| `ChunkNavGraph` | **EVALUATED** (session 2 — was DISCOVERED) | Read in full; `getEdges()`/`ChunkEdge`/`calculateEdgeCost()` confirmed dead code via exhaustive grep (N11, N14) | §13.1, §13.3 |
| `ChunkNavData`/`ChunkPath` | **TRACED** (session 2 — was DISCOVERED) | Read in full; confidence-decay math and the `walkability`/`observed`/binary-format version-migration logic confirmed correct in isolation, just unused by the live planner (N11); `ChunkPath.waypoints` confirmed never written (N13) | §13.1, §13.3 |
| `ChunkNavRecorder` | **EVALUATED** (session 2 — was DISCOVERED) | Read in full; per-tick recording cost (40,000-cell double loop per visible grid) quantified from source (§13.6), confirming rather than just hypothesizing session 1's N10 overhead concern | §13.6 |
| `UnifiedTilePathfinder` | **EVALUATED** (session 2 — was DISCOVERED) | Read in full; confirmed to be a correctly-implemented `PriorityQueue`-based A* (not sharing `pf/Graph.java`'s `LinkedList` issue), with portal-type/layer validation and an admissibility caveat in the cross-layer heuristic (not independently re-verified for optimality, only for termination/correctness) | §13.1 |
| `ChunkNavIntraPathfinder` | **DISCOVERED only, now explained** | Confirmed unreachable from any current `ChunkPath` (N13) — not traced further since nothing currently exercises it; worth tracing only if `followWaypointPath` is ever revived or deliberately removed | §13.3 |
| `ChunkNavBinaryFormat`/`ChunkNavFileStore` | **DISCOVERED** only | Not read this session either — persistence serialization, lower priority than the live planning/execution path | §1 |
| `AreaNavigationHelper` | **EVALUATED** (session 2 — was DISCOVERED) | `isAreaReachableByLocalPF`/`findShortestPathToAreaCorners` read in full; confirmed as the local-PF-first half of `NUtils`'s canonical dispatcher (N16) | §13.5 |
| `NUtils.navigateToArea`/`navigateTo`/`navigateToArea(Specialisation)` | **EVALUATED** (new, session 2) | Read in full; confirmed as a correctly-layered shared dispatcher most bots route through — a positive finding (N16) | §13.5 |
| `FreeContainersInUnboxZone` (the two `useGlobalPf` call sites) | **TRACED** (new, session 2) | Both call sites read in full, resolving N9 | §13.4 |
| `FollowRoute`/`RouteWalkControl` | **EVALUATED** | Traced in full; the unconditional-success return (N5) found by direct comparison of the per-waypoint failure bookkeeping against what's actually returned | §5, §12 |
| `ForagerRouteConstraints`/`CliffCorridorChecker`/`LandCorridorChecker` | **EVALUATED** | Traced in full; compared against `PathFinder.avoidZones`' single-caller status and against each other (confirmed deliberately-separate-from-`NPFMap` design, not a defect) | §5, §9 |
| `StorageNav` | **EVALUATED** | Traced in full; compared against `FollowRoute.bridgeToRoute` as a duplicated pattern (N7) | §5, §12 |
| `PatrolArea` | **EVALUATED** | Traced in full; compared against every other movement primitive in the registry as the clear outlier with zero shared-primitive reuse | §6, §12 |
| `StuckDetector` | Not re-traced — already EVALUATED/VALIDATED by `world-explorer-system.md` | Cross-referenced into §7's comparison table, not independently re-read this session | §7 |
| `useGlobalPf`/`useHFinGlobalPF` config wiring | **VALIDATED** | Confirmed via exhaustive grep of the entire `src/` tree for all consumers of both keys | §11, §12 N9 |
| `Navigation.java` (settings panel) | **TRACED** | Read in full for its config-wiring implications | §11 |
| `SetDir`/`SetDirTrol`/`RunToSafe` | **DISCOVERED** only | Located, not read | §1 |
| `NavigationStressTest` | **DISCOVERED** only | Class purpose inferred from doc comment/method-name grep, not read | §1 |
| `ChunkNavVisualizerWindow`/`ChunkNavNavigatorWindow`/`MinimapChunkNavRenderer`/`WaypointStepsWindow`/`ForagerWaypoint`/`NWaypointOverlay` | **DISCOVERED** only | Located and role inferred from file/class names and neighboring code; not read | §1 |
| Water World Explorer (`TileField`/`CoastFollower`/companions) | Out of scope, already current | Not re-audited; `world-explorer-system.md` stands as-is | §1 |
| Cliff-specific group (`CliffScan`/`CliffAwareMove`/`CliffCalibrate`/`DynamicPf.cliffAware`) | Confirmed **still absent** from current `master` | Independently re-checked via grep this session, matching `land-navigation-research.md`'s own claim | §1 |
| Upstream-Nurgling2 divergence check for this registry | **Done (session 2)** | Every file in §1's registry diffed against `upstream/master` directly — all byte-identical, zero fork-exclusive, zero shared-but-modified | §14 |
| `chunkHopsFromPlayer` batching guidance followed by its real callers | **Done (session 2)** | `StorageTrailService`/`BlueprintBuilder` confirmed to call the batched form, not `chunkHopsTo` in a per-candidate loop; `PathFinder.isAvailable`'s ~45 call sites across the registry confirmed to operate on already-loaded, already-visible `Gob`s — a different problem domain than cross-chunk reachability, not a missed optimization | §4b (and direct grep of all `PathFinder.isAvailable` call sites) |
| `useGlobalPf`/`useHFinGlobalPF` intentionality (N9) | **Done (session 2)** | Resolved as an incomplete/abandoned migration, not deliberate scope | §13.4, §12 N15 |
| Live/runtime verification of any finding in this document | **Not started** | Everything in §12/§13 is static-code analysis; nothing was run in-client in either session | throughout |
| Performance hypotheses (§10, §13.6) | **Not measured** | Static-code only; the recorder's per-tick cost shape is now source-confirmed (§13.6) but not profiled for actual frame-time impact | §10, §13.6 |
| `ChunkNavBinaryFormat`/`ChunkNavFileStore` persistence layer | **Not started** | Not read in either session | §1 |
| Whether `UnifiedTilePathfinder`'s cross-layer heuristic (`heuristic()`, `getPortalPathDepth`) is admissible | **Not started** | Noted as a possible non-admissibility (overestimating heuristic via `100.0 + (depth-1)*400.0`) in §13.1; would only matter for path *optimality*, not correctness — A* still terminates and still finds *a* valid path either way | §13.1 |
| Live-runtime experiments needed before implementing any backlog item from this document | **Not started, itemized below** | See "Remaining runtime work" | — |

### Remaining runtime work before implementation (not performable in this documentation-only audit)

Per the methodology, these are the specific experiments/measurements that would need to happen in
a running client before any of this document's N11-N16 findings should be turned into a code
change — listed so a future implementation session knows what to run, not as a blocker on closing
the discovery phase itself:

1. **N11 (confidence never consumed):** before wiring confidence into `UnifiedTilePathfinder`'s
   cost function, need a live test of what actually happens when a bot is sent through a chunk
   whose recorded data is deliberately stale (e.g. a door that's been removed) — does the local
   `PathFinder` fallback during execution (§13.2) already mask the problem often enough in practice
   that the fix's benefit is marginal, or does it visibly fail? No in-client reproduction attempted
   this session.
2. **N12 (`replanAndContinue` null-area fail):** reproduce by forcing a `FollowRoute` bridge or a
   `StorageNav` walk to hit a mid-journey portal failure (e.g. a gate that's closed) and confirm the
   traversal aborts immediately rather than retrying — static trace is conclusive on the code path,
   but a live reproduction would confirm the player-visible symptom (and give a concrete repro for
   a regression test).
3. **N14/N13 (dead code):** before removing `followWaypointPath`/`ChunkNavIntraPathfinder`/
   `ChunkNavGraph.getEdges()`, a build-time reachability check (e.g. a coverage run exercising every
   `ChunkNav`-using bot) would be the appropriate verification that nothing outside this audit's grep
   scope constructs a `ChunkPath` with `waypoints` populated — this session's static trace covers
   everything under `src/nurgling/navigation/**` but not every caller in the wider tree that might
   construct a `ChunkPath` by hand.
4. **§13.6 (recording overhead):** an actual profiler run (or even a coarse `System.nanoTime()`
   bracket around `ChunkNavRecorder.sampleWalkability`/`mergeWalkability`) while standing in a
   grid-dense area (e.g. a multi-building settlement with several loaded grids) would turn the
   source-confirmed cost *shape* into a measured frame-time number.

### Deferred, optional follow-up (not scheduled — discovery phase is closed, see below)

Session 2 closed out the ChunkNav-internals and upstream-compatibility gaps session 1 had flagged
as open. What remains is deliberately **not** being picked up as a session 3 — the discovery phase
for this capability area is closed (see "Closure" below), and more DISCOVERED→TRACED coverage is
not itself a goal of this audit. If a future need (not a coverage target) brings any of these back
into scope: (1) the four runtime experiments in the list above, if/when implementation work on any
of N11-N16 is actually scheduled; (2) `ChunkNavBinaryFormat`/`ChunkNavFileStore` (persistence
serialization — lowest-priority remaining DISCOVERED-only files in the registry); (3) the small set
of still-DISCOVERED-only UI/diagnostic files (`ChunkNavVisualizerWindow`, `ChunkNavNavigatorWindow`,
`MinimapChunkNavRenderer`, `WaypointStepsWindow`, `ForagerWaypoint`, `NWaypointOverlay`,
`SetDir`/`SetDirTrol`/`RunToSafe`, `NavigationStressTest`) — all low-value relative to what's
already been traced.

## Closure

**This capability area's architecture/discovery audit is closed as of session 2.** This means: the
breadth-first registry (§1), the comparison/evaluation work (§7-9, §13), and the upstream-compatibility
classification (§14) are considered sufficient to stop general-purpose discovery work here. It does
**not** mean every navigation behavior has been runtime-tested, every performance hypothesis (§10,
§13.6) has been measured, or any backlog item (`modernization-backlog.md`, items 14-25) has been
implemented — those remain explicit, separately-tracked future work, itemized in "Remaining runtime
work" above and in the backlog's own closing assessment. A future session should not reopen this
document merely to raise a coverage percentage; it should be reopened only when a specific
implementation, bug report, or new capability actually requires revisiting a specific finding here.
