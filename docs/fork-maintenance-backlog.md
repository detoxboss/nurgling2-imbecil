# Fork maintenance backlog

Current, non-historical record of known deferred work in this fork that is **not** an intentional
customization invariant (those live in `docs/fork-customization-ledger.md`) and **not** a
per-cycle sync record (those live in `docs/upstream-sync-history/`). This is where a known problem
lives while it's still open — update an entry in place as its status changes; move nothing here
automatically just because a sync cycle mentioned it.

Nothing in this document has been fixed as part of drafting it. Do not treat an entry's presence here
as authorization to fix it without separate review.

## 1. World Explorer — unsynchronized `MCache.grids` access

**Where:** `src/nurgling/actions/bots/WorldExplorerFrontier.java` (line ~140),
`src/nurgling/pf/FrontierPicker.java` (lines ~139/210/267 as of the 2026-09-30 sync — and anywhere
else in the World Explorer path that reads `MCache.grids` directly).

**Problem:** reads `MCache.grids` without the synchronization the rest of the client's grid-loading
path uses, which is a latent race with concurrent grid load/unload.

**Status:** known, not fixed, not scheduled — but the 2026-09-30 upstream sync added a safer
remediation path worth using when this is picked up: `MCache.gridsById()` (`MCache.java:1356`), a
new upstream method that takes `synchronized(grids)` once and returns a snapshot `Map<Long, Grid>`
for callers that look up many grid ids per frame — built for exactly this area-overlay access
pattern (upstream's own area-overlay-performance commit uses it internally). Both fork call sites
above are fork-only files upstream cannot have touched, so they still read `map.grids` directly and
unsynchronized; switching them to a `gridsById()` snapshot is now a small, low-risk fix rather than
a design problem to solve from scratch.

## 2. Broader multi-session architecture/reliability investigation

**Where:** `src/nurgling/sessions/**` and everything the session-ownership invariants in
`docs/fork-customization-ledger.md` currently patch around (`MapFile` locking, `NGameUI` teardown,
`NMiniMap` resolution, explicit-session persistence).

**Problem:** those ledger entries are targeted fixes for specific symptoms of running multiple
sessions in one process. Whether the underlying multi-session architecture has other, not-yet-observed
reliability gaps hasn't been systematically investigated.

**Status:** known gap in investigation depth, not scheduled. Not the same task as any single ledger
entry above — those are confirmed, verified fixes; this is "what else might be wrong that hasn't
surfaced yet." Update (2026-09-30 sync): upstream is showing partial, ongoing convergence toward
owning-session resolution in scattered call sites it touched this range (`MCache.getnolcut`,
`NGItem`'s quest framing, a new `NArea.getArea(MCache)` overload) — see
`docs/fork-customization-ledger.md`'s closing note. This narrows, but does not close, the gap this
item tracks: upstream has not shipped a general multi-session architecture, and the same sync range
also introduced a *new* ambient-lookup regression in `NMiniMap.drawTimers()` that had to be
corrected during the merge (see the ledger's `NMiniMap` entry). The audit this item asks for remains
open.

## 3. `FreeContainersInUnboxZone` / Unboxing From Area — full ChunkNav-era rewrite

**Where:** `src/nurgling/actions/bots/FreeContainersInUnboxZone.java`.

**Problem:** hand-rolls the same problems the September 2026 upstream gathering-bot rework (see
`docs/upstream-sync-history/2026-09-04.md`) now solves centrally: its own scattered
`Finder.findGob(pile.id) != null` re-checks, its own re-open logic when a pile gob disappears
mid-drain, and stockpile-take sizing based only on `StockpileUtils.itemMaxSize` with no
stack-depth awareness. Also carries a commented-out, apparently-abandoned
`RoutePointNavigator`/`closestRoutePoint` field.

**Patterns to study before rewriting** (present in the tree now, unmodified by the fork, from the
2026-09-04 sync):

- `NContext.goToArea(...)` and `NContext.getSpecStorages(...)` — area/storage resolution with
  ChunkNav-aware navigation fallback.
- `Container.pathTo()` — per-container pathing that falls back to `NContext.navigateToArea` when the
  target gob isn't currently resolvable.
- `TakeItems2` / `TakeItems2.takeAny(...)` — capacity-aware, absolute-target item fetching.
- The **sum-need → bulk-take → distribute** idiom common to `SmelterAction`, `LeatherAction`,
  `DFrameFishAction`, and `DFrameHidesAction`: compute total free capacity across every not-yet-full
  container first, issue one bulk take sized to that total, distribute, and stop on a round that places
  nothing — rather than one round-trip per container.
- **Progress/termination handling for a stockpile that can be destroyed mid-drain:**
  `TakeItems2.takeFromPile` re-resolving the pile gob via `Finder.findGob` on every visit, and
  `FindNISBox`'s tracked-gob constructor giving up cleanly (instead of hanging) once the tracked gob is
  confirmed gone — the direct, better-tested replacement for this bot's own hand-rolled version of the
  same problem.

**Status:** PARTIALLY FIXED upstream, as of the 2026-09-30 sync. Upstream's "Ignore unbox zone's own
PUT prefs when unboxing it" commit fixed exactly the self-routing failure mode: `NContext` gained
`excludeOutArea(NArea)`, `FreeContainers` gained a 3-arg constructor accepting an area to exclude as
a PUT destination, and `FreeContainersInUnboxZone` now calls both so items taken from the unbox zone
never route straight back into it even when that zone itself carries matching PUT preferences
(e.g. when it doubles as a local storage buffer). Taken as-is in the 2026-09-30 merge.

**Still open, not touched by that fix or by this sync:** the full ChunkNav-era rewrite below remains
unimplemented — `TakeItemsFromPile` (not `TakeItems2`), hand-rolled `Finder.findGob(pile.id) != null`
re-checks, stockpile-take sizing based only on `StockpileUtils.itemMaxSize` with no stack-depth
awareness, no sum-need→bulk-take→distribute idiom, no `FindNISBox`-style clean giveup on a destroyed
pile, and the commented-out `RoutePointNavigator`/`closestRoutePoint` remnant are all still present
verbatim in the merged file. This is a rewrite, not a small patch — treat it as its own reviewed piece
of work when picked up, not a drive-by fix folded into an unrelated sync.

## 4. `OpenTargetContainer` nullable-`gob` NPE

**Where:** `src/nurgling/actions/OpenTargetContainer.java`, `run()`.

**Problem:** when constructed from a `Container` whose gob isn't currently resolvable
(`Finder.findGob(...)` returns null), `run()` still unconditionally dereferences `gob.rc` in the
`gui.map.wdgmsg("click", ...)` call when no matching window is already open — producing
`NullPointerException: Cannot read field "rc" because "this.gob" is null`.

**Status:** confirmed still present as of the 2026-09-04 sync; re-confirmed untouched as of the
2026-09-30 sync — `src/nurgling/actions/OpenTargetContainer.java` is not in upstream's 354-file
changed-path set for `6980c6fa9..0b37d02b`, and `run()` still unconditionally dereferences `gob.rc`
at the `gui.map.wdgmsg("click", ...)` call site (lines ~34-35) when no matching window is already
open. Not fixed, not scheduled.

## 5. Linux/JOGL/Java launch cleanup

**Where:** launch scripts / JOGL toolkit selection for Linux.

**Problem:** outstanding cleanup noted in prior work; specifics not re-audited as part of this
document's drafting.

**Status:** confirmed still outstanding as of the 2026-09-30 sync, for this fork's own portable
release specifically. Upstream fixed this for *its own* distribution: `build.xml`'s `pre-release`
target now writes `release/platform/linux/haven-config.properties` = this fork's own
`etc/ansgar-config.properties` plus an appended `haven.toolkit=lwjgl` line, with the comment "Linux:
JOGL crashes in Mesa's GLX, so every launcher ... must pick the LWJGL toolkit" — installed on Linux
only by upstream's own updater. This fork's portable release builds via `ant bin`, never
`pre-release`, and `etc/release-run.sh` sets no `haven.toolkit` property at all, so the fork's Linux
portable release still gets the default JOGL toolkit and the Mesa/GLX crash it causes.

**Do not fix by hardcoding `-Dhaven.toolkit=lwjgl` into `etc/release-run.sh`** — an explicit
`-Dhaven.toolkit=...` JVM property unconditionally wins over the saved `RendererPref` preference
(`RendererPref.apply()`'s early-return check), so a hardcoded property would permanently prevent a
Linux player from ever selecting Vulkan in Video settings, defeating the whole point of the
2026-09-30 sync's dual-renderer adoption. The correct shape mirrors upstream's: a Linux-specific
**config default** (a `haven-config.properties` value, not a JVM system property), which
`RendererPref` can still override when the player picks something else. Tracked as part of the
release-workflow follow-up alongside the Vulkan-jars `required=()` gap — see
`docs/upstream-sync-history/2026-09-30.md`.

## Minor / not urgent

- **`messages.properties` / `messages_ru.properties` duplicate-key quirk.** Both files contain ~21–23
  keys defined twice with identical values (confirmed pre-existing on both fork and upstream before the
  2026-09-04 sync — not a fork customization, not recorded in the ledger). Harmless (Java `Properties`
  loading just keeps the last occurrence, and the values match), but a candidate for a small
  independent cleanup PR against both this fork and upstream if anyone wants to do it. Update
  (2026-09-30 sync): duplicate count is now 22 in both files (upstream removed one of the
  pre-existing duplicates in this range; no new ones introduced).
- **Local `master` ref staleness.** The local `master` branch pointer can silently fall behind
  `origin/master` between syncs (observed 266 commits stale before the 2026-09-04 sync). Harmless as
  long as nothing branches from it while stale — `docs/fork-sync-guide.md` Phase 1 now checks and
  repairs this every cycle, so this should self-correct going forward rather than needing standalone
  tracking.
