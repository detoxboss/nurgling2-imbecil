# Fork maintenance backlog

Current, non-historical record of known deferred work in this fork that is **not** an intentional
customization invariant (those live in `docs/fork-customization-ledger.md`) and **not** a
per-cycle sync record (those live in `docs/upstream-sync-history/`). This is where a known problem
lives while it's still open — update an entry in place as its status changes; move nothing here
automatically just because a sync cycle mentioned it.

Nothing in this document has been fixed as part of drafting it. Do not treat an entry's presence here
as authorization to fix it without separate review.

## 1. World Explorer — unsynchronized `MCache.grids` access

**Where:** `src/nurgling/actions/bots/WorldExplorerFrontier.java`,
`src/nurgling/pf/FrontierPicker.java` (and anywhere else in the World Explorer path that reads
`MCache.grids` directly).

**Problem:** reads `MCache.grids` without the synchronization the rest of the client's grid-loading
path uses, which is a latent race with concurrent grid load/unload.

**Status:** known, not fixed, not scheduled.

## 2. Broader multi-session architecture/reliability investigation

**Where:** `src/nurgling/sessions/**` and everything the session-ownership invariants in
`docs/fork-customization-ledger.md` currently patch around (`MapFile` locking, `NGameUI` teardown,
`NMiniMap` resolution, explicit-session persistence).

**Problem:** those ledger entries are targeted fixes for specific symptoms of running multiple
sessions in one process. Whether the underlying multi-session architecture has other, not-yet-observed
reliability gaps hasn't been systematically investigated.

**Status:** known gap in investigation depth, not scheduled. Not the same task as any single ledger
entry above — those are confirmed, verified fixes; this is "what else might be wrong that hasn't
surfaced yet."

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

**Status:** known, not fixed, not scheduled. This is a rewrite, not a small patch — treat it as its own
reviewed piece of work when picked up, not a drive-by fix folded into an unrelated sync.

## 4. `OpenTargetContainer` nullable-`gob` NPE

**Where:** `src/nurgling/actions/OpenTargetContainer.java`, `run()`.

**Problem:** when constructed from a `Container` whose gob isn't currently resolvable
(`Finder.findGob(...)` returns null), `run()` still unconditionally dereferences `gob.rc` in the
`gui.map.wdgmsg("click", ...)` call when no matching window is already open — producing
`NullPointerException: Cannot read field "rc" because "this.gob" is null`.

**Status:** confirmed still present as of the 2026-09-04 sync. Upstream's own change to this file in
that sync's range touched a different, unrelated stockpile-lifecycle race (passing `gob` into
`FindNISBox`'s constructor) — it does not fix this one. Not fixed, not scheduled.

## 5. Linux/JOGL/Java launch cleanup

**Where:** launch scripts / JOGL toolkit selection for Linux.

**Problem:** outstanding cleanup noted in prior work; specifics not re-audited as part of this
document's drafting.

**Status:** unconfirmed whether still outstanding — verify current state before scheduling, don't
assume this entry is still accurate.

## Minor / not urgent

- **`messages.properties` / `messages_ru.properties` duplicate-key quirk.** Both files contain ~21–23
  keys defined twice with identical values (confirmed pre-existing on both fork and upstream before the
  2026-09-04 sync — not a fork customization, not recorded in the ledger). Harmless (Java `Properties`
  loading just keeps the last occurrence, and the values match), but a candidate for a small
  independent cleanup PR against both this fork and upstream if anyone wants to do it.
- **Local `master` ref staleness.** The local `master` branch pointer can silently fall behind
  `origin/master` between syncs (observed 266 commits stale before the 2026-09-04 sync). Harmless as
  long as nothing branches from it while stale — `docs/fork-sync-guide.md` Phase 1 now checks and
  repairs this every cycle, so this should self-correct going forward rather than needing standalone
  tracking.
