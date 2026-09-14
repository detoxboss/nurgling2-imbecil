# NArea Sync Analysis

Analysis only. No source files were modified while producing this report.

## Executive answer

The "sync" feature is real, not a stub: it is a full client/DB replication system with UUID identity,
optimistic-concurrency versioning, tombstoned deletes, and a field-group three-way merge. It already
does almost everything the desired workflow needs, **provided every player points their client at the
same PostgreSQL database and plays the same game world**. There is no separate "sync server" — the
shared Postgres database *is* the shared dataset, and syncing is just "everyone's `AreaService` polls
and writes the same `areas` table."

What it does **not** have is a shared/private flag — every area in a profile (world) is visible to
every client connected to that database for that world. There is also one identified correctness gap:
areas re-imported from an exported JSON file can carry a stale `uuid` bound to a different database
row, which will fail to save under the unique index on `uuid` (see Gaps). Practically, for the
requested workflow, the fix is operational (all three players connect to one shared Postgres instance)
rather than a rewrite.

## Relevant source files

| File | Class | Responsibility | Why it matters |
|---|---|---|---|
| [src/nurgling/areas/NArea.java](src/nurgling/areas/NArea.java) | `NArea` | In-memory area model: geometry, name, color, ingredient in/out lists, specializations, plus sync metadata (`uuid`, `version`, `baselineVersion`, `baselineSnapshot`, `dirtyGroups`, `lastTouchedBy/At`) | The core entity being synced |
| [src/nurgling/areas/AreaFieldGroup.java](src/nurgling/areas/AreaFieldGroup.java) | `AreaFieldGroup` | Enum: `GEOMETRY`, `IDENTITY`, `COSMETIC`, `ROUTING` | Unit of conflict detection/merge |
| [src/nurgling/areas/AreaSnapshot.java](src/nurgling/areas/AreaSnapshot.java) | `AreaSnapshot` | Frozen field-group snapshot + diffing + merged-JSON builder | Basis of the three-way merge |
| [src/nurgling/db/dao/AreaDao.java](src/nurgling/db/dao/AreaDao.java) | `AreaDao` | Raw SQL: OCC save, tombstone, version map, max-id lookup | The only place SQL touches the `areas` table |
| [src/nurgling/db/service/AreaService.java](src/nurgling/db/service/AreaService.java) | `AreaService` | Push (`saveArea`), bulk load, periodic delta poll, sync scheduler, id-watermark tracking | Orchestrates the whole sync loop |
| [src/nurgling/db/service/AreaMerger.java](src/nurgling/db/service/AreaMerger.java) | `AreaMerger` | Three-way merge algorithm (baseline vs local vs remote, per field group) | Conflict-resolution logic |
| [src/nurgling/db/service/AreaSyncEvent.java](src/nurgling/db/service/AreaSyncEvent.java) | `AreaSyncEvent` | User-facing description of a sync outcome (toast text) | Explains what the merge did |
| [src/nurgling/widgets/NAreaSyncHistoryWidget.java](src/nurgling/widgets/NAreaSyncHistoryWidget.java) | `NAreaSyncHistoryWidget` | "Sync log" window listing recent `AreaSyncEvent`s | Only sync-specific UI surface |
| [src/nurgling/widgets/NAreasWidget.java](src/nurgling/widgets/NAreasWidget.java) | `NAreasWidget` | Areas list/tree window: create, delete, rename, recolor, import, export buttons | Where users actually touch areas |
| [src/nurgling/widgets/NImportStrategyDialog.java](src/nurgling/widgets/NImportStrategyDialog.java) | `NImportStrategyDialog` | Picks one of 3 JSON-file import strategies | File import, unrelated to DB sync |
| [src/nurgling/NConfig.java](src/nurgling/NConfig.java) | `NConfig` | `writeAreas`/`mergeAreas`/`replaceAreas`/`overwriteAreas` — JSON file I/O and the debounced auto-save trigger | Bridges local edits to the DB push path |
| [src/haven/MCache.java](src/haven/MCache.java) | `MCache` | Owns `areas` map (`HashMap<Integer, NArea>`), file-mode load, dirty-flag debounce timer | Where areas actually live at runtime |
| [src/nurgling/NMapView.java](src/nurgling/NMapView.java) | `NMapView` | `addArea`, `removeAreaById` — id allocation, local delete + DB tombstone call | Create/delete lifecycle entry points |
| [src/nurgling/NCore.java](src/nurgling/NCore.java) | `NCore` | `startAreaSync`/`stopAreaSync`, wires `AreaService.AreaSyncCallback` into the live `MCache.areas` map | Where sync results get applied to what you see |
| [src/nurgling/db/migration/MigrationManager.java](src/nurgling/db/migration/MigrationManager.java) | `MigrationManager` | Schema definition/migrations for the `areas` table | Ground truth for storage schema |
| [src/nurgling/widgets/options/DatabaseSettings.java](src/nurgling/widgets/options/DatabaseSettings.java) | `DatabaseSettings` | UI for DB type/host/port/user/pass/file, enable checkbox, reload-on-save | Where "point at a shared DB" is configured |

## NArea local storage

Two storage backends, mutually exclusive per profile, selected by `NConfig.Key.ndbenable`:

**File mode** (DB disabled): one JSON file per profile (world), `{"areas": [ {...}, {...} ]}`, path
from `MCache.getAreasPath()` → `ConfigFactory.getConfig(genus).getAreasPath()`. Loaded once via
`MCache.loadAreasIfNeeded()` ([src/haven/MCache.java:152-190](src/haven/MCache.java#L152-L190)), written by
`NConfig.writeAreasToFile()` ([src/nurgling/NConfig.java:1510-1526](src/nurgling/NConfig.java#L1510-L1526)).
Nothing here is shared between machines except by manually copying/importing the file.

**Database mode** (DB enabled): a single `areas` table, created in
[MigrationManager.java:259-279](src/nurgling/db/migration/MigrationManager.java#L259-L279) and evolved by
later migrations:

```
areas (
  id            INTEGER PRIMARY KEY,      -- NOT composite with profile
  name          VARCHAR(255) NOT NULL,
  path          VARCHAR(512) DEFAULT '',
  hide          BOOLEAN/INTEGER DEFAULT false,
  color_r/g/b/a INTEGER,
  data          TEXT NOT NULL,            -- JSON: space (grid polygons), in, out, spec
  profile       VARCHAR(255) DEFAULT 'global',  -- world/genus scoping
  updated_at    TIMESTAMP,
  version       INTEGER DEFAULT 1,        -- migration 4
  uuid          VARCHAR(36),              -- migration 6, UNIQUE INDEX idx_areas_uuid
  deleted_at    TIMESTAMP,                -- migration 6, tombstone
  last_touched_by  VARCHAR(255),          -- migration 7
  last_touched_at  TIMESTAMP              -- migration 7
)
```

Supports both PostgreSQL (network-shared) and SQLite (single local file) through the same DAO code —
see `DatabaseSettings` for the type selector. **SQLite is not multi-player shared** unless the file
itself sits on a network share, which the code does not assume or support gracefully; PostgreSQL is
the backend that actually makes cross-player sync possible.

## Identity and conflict handling — how areas are identified

Three identifiers exist, each doing a different job:

- **`id` (int)** — the DB primary key and the key `MCache.areas` is a `HashMap<Integer, NArea>` on.
  Allocated client-side as "one past every id this client or the DB has ever seen for this profile"
  ([src/nurgling/NMapView.java:1178-1236](src/nurgling/NMapView.java#L1178-L1236),
  `getMaxKnownAreaId`/`getMaxAreaId`). **Not inherently stable across clients** — it is a race-prone,
  best-effort sequential counter, made safe only by watermark tracking that also considers tombstoned
  ids (`AreaDao.getMaxAreaId`, `AreaService.getMaxKnownAreaId`). Two brand-new areas created offline by
  two different clients before their first sync could, in principle, both pick the same `id` at almost
  the same moment; OCC catches the resulting write race (see below) but this is the fragile part of
  identity.
- **`uuid` (string)** — the actually stable identifier, generated client-side with
  `java.util.UUID.randomUUID()` the moment an area is created
  ([NMapView.java:1209](src/nurgling/NMapView.java#L1209)) or the first time it's pushed to the DB if it
  somehow lacks one ([AreaService.java:118-120](src/nurgling/db/service/AreaService.java#L118-L120)).
  Enforced unique at the DB level (`idx_areas_uuid`). **However, nothing in the DAO layer actually
  looks an area up or writes to it *by* `uuid`** — every read/write/OCC-check in `AreaDao` is keyed on
  `id`. `uuid` is carried along as a payload column, not used as the primary sync key. This is the root
  of the one identified bug (see Gaps).
- **`profile` (string)** — the world identifier (`NGameUI.getGenus()`, a hash like
  `c646473983afec09`), or `"global"` if unknown. This is the actual sharing/namespace boundary: every
  query filters `WHERE profile = ?`. Two players on the same world with the same DB see the same rows;
  two players on different worlds sharing one DB do not (subject to the id-collision caveat in Gaps,
  since `id` alone — not `id+profile` — is the primary key).
- **Name** is not an identifier at all — it is just the `IDENTITY` field group's payload, and is never
  used to match areas across clients except in the *file-import* paths (see below).

## NArea lifecycle

**Create** — `NMapView.addArea()` ([NMapView.java:1178](src/nurgling/NMapView.java#L1178)): allocates
`id`, assigns a fresh `uuid`, marks all four field groups dirty, `baselineVersion = 0`. Nothing is
written to the DB yet.

**Save (push)** — triggered indirectly. Any UI edit calls `area.markDirty(group)` then
`NConfig.needAreasUpdate()` ([NConfig.java:781](src/nurgling/NConfig.java#L781)), which just sets a flag
on `MCache` (`markAreasDirty()`). `NCore.tick()` checks `MCache.isAreasUpdated()` (a 3-second debounce,
[MCache.java:122](src/haven/MCache.java#L122)) and, once elapsed, calls
`NConfig.writeAreas(null, ui.gui)` → in DB mode →
`AreaService.exportAreasToDatabaseAsync(mcache.areas, profile)` → `AreaService.saveArea()` per area
([AreaService.java:106-198](src/nurgling/db/service/AreaService.java#L106-L198)). `saveArea` is a no-op
if `dirtyGroups` is empty, otherwise does an OCC `UPDATE ... WHERE id=? AND version=?`
([AreaDao.java:203-228](src/nurgling/db/dao/AreaDao.java#L203-L228)); on 0 rows affected (someone else
wrote first) it re-reads the row, three-way merges via `AreaMerger`, applies the merge locally, and
retries (up to `MAX_OCC_RETRIES = 4`).

**Edit / Rename** — same push path. Renaming only marks the `IDENTITY` group dirty
([NEditAreaName.java](src/nurgling/widgets/NEditAreaName.java) → `NConfig.needAreasUpdate()`); recoloring
marks `COSMETIC`; geometry edits mark `GEOMETRY`; ingredient in/out list edits mark `ROUTING`
([IngredientContainer.java](src/nurgling/widgets/IngredientContainer.java)). This group-level
granularity is what lets two players edit different aspects of the same area concurrently without
clobbering each other.

**Delete** — `NMapView.removeAreaById()`
([NMapView.java:2327-2356](src/nurgling/NMapView.java#L2327-L2356)): removes from the local map
immediately, adds the id to a `locallyDeletedAreas` set (so the next pull doesn't resurrect it before
the tombstone lands), then in DB mode calls `AreaService.deleteAreaAsync()` →
`AreaDao.tombstoneArea()` — an `UPDATE areas SET deleted_at = CURRENT_TIMESTAMP, version = version+1 ...`
([AreaDao.java:287-293](src/nurgling/db/dao/AreaDao.java#L287-L293)). The row is **never physically
deleted** by normal use; `AreaDao.purgeTombstonesOlderThan()` exists for a hard-delete sweep but is not
wired into the periodic sync tick — it would need to be invoked from somewhere else (e.g. an admin
task) to actually run. Not found to be called anywhere in the codebase.

**Export (file)** — the Export button in `NAreasWidget` calls
`NUtils.getUI().core.config.writeAreas(path)` with a caller-supplied path
([NAreasWidget.java:120-135](src/nurgling/widgets/NAreasWidget.java#L120-L135)), which serializes the
*entire current in-memory `areas` map* (DB-backed or not) to a JSON file via `area.toJson()` — including
each area's `uuid` if it has one. This is a full snapshot, not incremental, and there is no "select
which areas" step — it is all-or-nothing.

**Import (file)** — the Import button opens `NImportStrategyDialog`
([NImportStrategyDialog.java](src/nurgling/widgets/NImportStrategyDialog.java)) with three strategies,
all acting on the local in-memory map only (see Sync architecture below for why this still ends up in
the DB indirectly):
- `FULL_REPLACE` → `NConfig.replaceAreas()`: clears every existing area, re-numbers imported areas
  `1..N` sequentially.
- `DUPLICATE` → `NConfig.mergeAreas()`: keeps existing areas, appends imported ones; **renames on name
  collision** (`"Other_" + name`), assigns new sequential ids.
- `OVERWRITE` → `NConfig.overwriteAreas()`: matches by **name** only; replaces the matched area's
  content but keeps its existing `id`; unmatched imported areas get a new id above the DB watermark.

None of the three strategies clear or regenerate `uuid` on the imported `NArea` objects — see Gaps for
the consequence.

**Sync (DB mode only)** — see next section.

## Sync architecture

There is no dedicated sync server, protocol, or REST endpoint. The "remote" is literally the same
relational database every player's `DatabaseAdapter` connects to (PostgreSQL — see
`DatabaseSettings`'s host/port/user/password fields — or a local SQLite file, which is not multi-player
usable). All "sync" logic lives client-side in `AreaService`; every connected client independently
polls and writes the same table.

```
Client A                                Shared PostgreSQL             Client B
--------                                -------------------           --------
NArea edit (UI)
  -> markDirty(group)
  -> NConfig.needAreasUpdate()
  -> MCache dirty flag (3s debounce)
  -> AreaService.saveArea()   ------->  UPDATE ... WHERE id=? AND
                                         version=? (OCC)
                                         areas table (id, uuid, name,
                                         data JSON, profile, version,
                                         deleted_at, last_touched_by)
                                                                        AreaService.syncTick()
                                                                        (4s scheduled poll, every
                                                                        live session)
                                                                          -> getAllAreaVersions()
                                                                          -> per-area: newer version?
                                                                             tombstoned?
                                                                          -> AreaMerger 3-way merge
                                                                          <-------- SELECT
                                                                        NCore callback applies result
                                                                        to MCache.areas (live map)
                                                                        -> overlay/widget refresh
```

## Sync triggers

- **Push**: any UI action that calls `NConfig.needAreasUpdate()`, applied after a fixed 3-second
  debounce per session (`MCache.AREAS_DEBOUNCE_MS`), not immediate and not user-button-driven for
  normal edits.
- **Pull**: `AreaService.startSync(profile, 4, callback)` is called once from
  `NCore.startAreaSync()` ([NCore.java:930-952](src/nurgling/NCore.java#L930-L952)), itself invoked the
  moment `databaseManager` is constructed with `ndbenable = true`
  ([NCore.java:261-266](src/nurgling/NCore.java#L261-L266)) — i.e., automatically at login/DB-connect
  time, no manual "sync now" action needed. It then polls **every 4 seconds for the life of the
  session**, for every live multi-session tab (`syncTick()` iterates `SessionManager.getAllSessions()`).
- **First-run bulk load**: the first tick for a given session does a full `loadAreas(profile)` instead
  of a delta poll (`bulkLoadedSessions` tracking); every tick after that is delta-only
  (`checkForUpdatesAndMerge`, which compares a lightweight `id -> (version, tombstoned?)` map before
  fetching full rows for anything changed).
- **Manual reload**: `DatabaseSettings.save()` and `AreaService.requestReload()` force the *next* tick
  to re-run the bulk load ("reload areas from database" side effect of hitting OK on DB Settings) —
  there is no separate "sync now" button, and the observable "Sync log" window is read-only history,
  not a trigger.
- **Stop**: `NCore.stopAreaSync()` when DB is disabled.

## Shared vs private areas

**Not supported.** There is no per-area flag anywhere in `NArea`, `AreaDao`, the `areas` schema, or the
UI that marks an area private/local vs shared. The only boundary is `profile` (world), which is
all-or-nothing for every area a client creates while connected to the DB for that world. The `hide`
checkbox found on `NArea`/`NAreasWidget` is a *soft-disable* flag documented explicitly in
[NArea.java:20-32](src/nurgling/areas/NArea.java#L20-L32) as "automation must not pick this area up" —
it is not a visibility/privacy control, and it syncs like every other cosmetic field (part of the
`COSMETIC` group), i.e. a disabled area is still replicated to everyone.

## Existing UI

| Control | Location | Calls |
|---|---|---|
| DB enable checkbox | `DatabaseSettings` | `NConfig.Key.ndbenable` → on save, `databaseManager.reconnect()` + `reloadAreasFromDatabase()` → `AreaService.requestReload()` |
| DB type dropdown (PostgreSQL/SQLite) | `DatabaseSettings` | `NConfig.Key.postgres`/`sqlite` |
| Host/Port/Username/Password fields | `DatabaseSettings` | `NConfig.Key.serverNode/serverUser/serverPass` |
| SQLite file path + "Init new DB" | `DatabaseSettings` | `NConfig.Key.dbFilePath`; creates an empty file, schema applied by `MigrationManager` on connect |
| Connection-string paste + Apply | `DatabaseSettings.applyConnectionString()` | Parses `postgresql://user:pass@host:port/db`, fills the fields above, calls `save()` |
| Import button (folder icon) | `NAreasWidget` | Opens `JFileChooser` → `NImportStrategyDialog.showDialog(file)` |
| Export button | `NAreasWidget` | `core.config.writeAreas(path)` — writes the whole local `areas` map to a JSON file |
| "Sync log" button | `NAreasWidget` | `NAreaSyncHistoryWidget.open()` — **read-only** list of recent `AreaSyncEvent`s, with a Clear button (`AreaSyncEvents.clear()`) |
| Per-area right-click "Delete" | `NAreasWidget` | `NMapView.removeAreaById(id)` → local remove + `AreaService.deleteAreaAsync` if DB enabled |
| Per-area right-click "Set color" / "Edit name" | `NAreasWidget` | `area.markDirty(COSMETIC/IDENTITY)` + `NConfig.needAreasUpdate()` |
| "Export to DB" button | `NAreasWidget` (source present, **commented out** at lines 146-155) | Would call `exportAreasToDatabase()`, a private method still present in the file but currently unreachable from the UI |

There is no "sync now," "connect to shared area group," or "pull latest" button anywhere — sync is
either fully automatic (DB mode, every 4s) or fully manual (file export/import).

## Example: Player A changes an area's ingredient list

1. Player A edits the "in" list in `NAreasWidget`'s `IngredientContainer` for area id 7.
2. `IngredientContainer` calls `area.markDirty(AreaFieldGroup.ROUTING)` and `NConfig.needAreasUpdate()`
   ([IngredientContainer.java:181 etc.](src/nurgling/widgets/IngredientContainer.java#L181)).
3. 3 seconds later (debounce), `NCore.tick()` sees `MCache.isAreasUpdated()` true, calls
   `writeAreas(null, gui)` → DB mode → `AreaService.saveArea(area7, profile)`.
4. `saveArea` sees `dirtyGroups = {ROUTING}`, `baselineVersion = N` (say 3), does
   `UPDATE areas SET ... version=version+1 ... WHERE id=7 AND version=3`. If no one else touched area 7
   since A's last sync, this succeeds, row becomes version 4, `last_touched_by = 'PlayerA'`.
5. On Player B and C's clients, the next `syncTick()` (within 4s) calls `getAllAreaVersions()`, sees
   area 7 is now version 4 > their locally-held version 3, fetches the full row, and since neither B
   nor C has any concurrent local edit to area 7 (`localDirtyGroups` empty), takes the simple-replace
   path: `convertToNArea(data)` → `NCore`'s `onAreasUpdated` callback → `localArea.updateFrom(newArea)`
   in the live `MCache.areas` map, and the overlay is flagged `requpdate2 = true`.
6. If B *also* had an uncommitted local edit to area 7's color (`COSMETIC`, different group from A's
   `ROUTING`), the three-way merge takes A's `ROUTING` from remote and keeps B's `COSMETIC` from local —
   both survive, `AreaSyncEvent.Kind.AUTO_MERGED` is recorded and shown as a toast/history entry. If B's
   pending edit were *also* to the `in`/`out` lists (same `ROUTING` group as A's change), the merge
   would take remote (server-timestamp/whoever-wrote-last wins) and report
   `Kind.REMOTE_OVERRODE` — B's edit is silently lost from the shared copy (B's local edit is
   overwritten in-memory too, since `applyMergeToLocal`/`updateFrom` replace the object's fields).

## Example: Player A deletes an area

1. A right-clicks area id 12 → Delete → `NMapView.removeAreaById(12)`.
2. Locally: removed from `MCache.areas` immediately, added to `locallyDeletedAreas`, overlay and label
   removed.
3. `AreaService.deleteAreaAsync(12, profile)` → `AreaDao.tombstoneArea`: `UPDATE areas SET
   deleted_at = now(), version = version+1, last_touched_by='PlayerA' WHERE id=12 AND deleted_at IS
   NULL`. The row still exists in the table (tombstoned, not dropped).
4. Player B and C's next `syncTick()` sees area 12's `AreaVersionInfo.tombstoned = true`. Since their
   local copy has `baselineVersion > 0` (it was previously synced, not a same-tick fresh local create),
   the callback fires `onAreaDeleted(12)` → `MCache.areas.remove(12)` on their clients too, plus an
   `AreaSyncEvent.Kind.DELETED` toast/history entry naming Player A.
5. The tombstone row is retained indefinitely unless something calls
   `AreaDao.purgeTombstonesOlderThan()` — not found wired into any scheduled task, so in practice
   tombstones accumulate forever and are only used to (a) block id reuse and (b) drive this
   propagation.

## Can three players maintain one shared NArea set?

**PARTIALLY — YES for the core mechanics, NO for private/local separation, with one identified bug on
JSON re-import.**

What works, confirmed from code:
- One shared PostgreSQL database + same world (`profile`) → every create/edit/delete automatically
  reaches every other connected client within ~4-7 seconds, no manual export/import.
- Edits to *different* aspects of the same area (geometry vs name vs color vs in/out lists) from
  different players auto-merge without data loss.
- Deletes propagate as tombstones and remove the area from every other client's live view.
- New areas propagate to everyone automatically (bulk load on first connect, `ADDED` events
  thereafter).
- Conflicting edits to the *same* field group are resolved deterministically (last writer as seen by
  the poll wins) and are visibly reported, not silently corrupted.

What does not work / is missing:
- No shared/private flag — every player's every area in that world becomes visible to every other
  player on that DB the moment it is saved. A player who wants some purely personal areas has no
  built-in way to keep them off the shared set while DB mode is on.
- SQLite mode does not give you this at all (it is a local file); only PostgreSQL is a real option for
  the "several of us share a dataset" scenario.
- Re-importing a JSON export (the workflow the user is currently doing manually) risks a save failure
  for any area whose JSON carries a `uuid` from a *previous* DB sync, because import reassigns a new
  `id` but keeps the old `uuid`, and the DB's unique index on `uuid` will reject that combination (see
  Gaps). This means the "export/import to merge" habit the team currently uses is not merely tedious —
  it can also quietly break sync for the affected areas going forward.

## Correct usage today

To get the "one shared dataset, near-real-time propagation" behavior the code already supports:

1. Stand up one PostgreSQL server reachable by all three players (self-hosted, VPS, etc.) — this repo
   has no bundled hosting; you provide the Postgres instance yourself.
2. Each player opens Options → Database (`DatabaseSettings`), checks "Enable database", selects
   "PostgreSQL", and fills in the same Host, Port, Username, Password (or pastes one
   `postgresql://user:pass@host:port/database` connection string and clicks Apply) — same server for
   all three.
3. Click OK/Save. This calls `databaseManager.reconnect()`, which runs `MigrationManager` to create/
   update the `areas` table (and everything else) if needed, then `reloadAreasFromDatabase()` schedules
   a bulk load.
4. Make sure all three players are on the **same game world** — sync is scoped by `profile` (the world
   hash from `getGenus()`), so two different worlds sharing one DB will not see each other's areas
   (aside from the id-collision caveat below).
5. From then on: create/edit/delete areas normally in each client. No Export/Import is needed for
   day-to-day changes — the 3-second push debounce and 4-second pull poll do the propagation.
6. Use the "Sync log" button in `NAreasWidget` to see recent merges/overrides/deletes if something
   looks off.
7. Do **not** use Export→Import between players who are all on this shared DB — it is redundant now,
   and risks the `uuid` collision described below for any area that already exists in the DB.

## Gaps / bugs / limitations

1. **No shared/private area flag.** Every area a player creates while connected to the shared DB for
   that world is visible to every other client on the same DB+world. There is no client-side or
   server-side mechanism to keep an area local-only while DB mode is on (confirmed: no such field in
   `NArea`, `AreaDao.AreaData`, the `areas` schema, or `NAreasWidget`'s per-area menu).
2. **JSON re-import can create a `uuid` collision that breaks sync for that area.** `NArea(JSONObject)`
   preserves `uuid` from the file ([NArea.java:259-261](src/nurgling/areas/NArea.java#L259-L261)), but
   every import strategy in `NConfig` (`mergeAreas`, `replaceAreas`, `overwriteAreas`) assigns a **new**
   local `id` to imported areas without clearing or regenerating `uuid`
   ([NConfig.java:1533-1561](src/nurgling/NConfig.java#L1533-L1561),
   [1566-1610](src/nurgling/NConfig.java#L1566-L1610),
   [1615-1660+](src/nurgling/NConfig.java#L1615)). On the next debounced push, `AreaService.saveArea`
   sees `baselineVersion == 0` (no baseline set by the JSON constructor path) and attempts an INSERT
   with the old `uuid` under the new `id`. Since `idx_areas_uuid` is a unique index and that `uuid`
   already belongs to a different row (its original one), this insert will violate the constraint,
   `saveArea` will exhaust its 4 OCC retries or the underlying `SQLException` will propagate, and
   `exportAreasToDatabaseAsync`'s catch block logs it to stderr and leaves the area "dirty" to retry
   forever ([AreaService.java:300-317](src/nurgling/db/service/AreaService.java#L300-L317)) — a silent,
   permanently-failing area from the user's point of view. Directly relevant to the team's current
   manual-export/import habit.
3. **`id` is the sole DB primary key, not `(id, profile)`.** The `areas` table's PK is
   `id INTEGER PRIMARY KEY` alone ([MigrationManager.java:260](src/nurgling/db/migration/MigrationManager.java#L260)),
   unlike `routes`, which explicitly uses `PRIMARY KEY (id, profile)`
   ([MigrationManager.java:328](src/nurgling/db/migration/MigrationManager.java#L328)). All read/list
   queries filter `WHERE profile = ?`, but the OCC `UPDATE`/`INSERT` in `AreaDao.saveAreaOCC` key only
   on `id`. If the same DB is ever used for more than one game world (two different `profile` values),
   two clients on different worlds can independently allocate the same integer `id` for new areas and
   collide/overwrite each other's row. Not a risk for a single shared world, which is the requested
   scenario, but worth knowing if the team ever points a second server/world at the same DB.
4. **No admin "purge tombstones" path is wired up.** `AreaDao.purgeTombstonesOlderThan()` exists but is
   not called from any scheduled task or UI control found in the codebase — deleted-area rows accumulate
   in the table forever. Not a correctness problem for sync itself (tombstones are what makes deletion
   convergence work) but a slow, unbounded growth issue for very long-lived shared databases.
5. **"Export to DB" button is commented out** in `NAreasWidget`
   ([NAreasWidget.java:146-155](src/nurgling/widgets/NAreasWidget.java#L146-L155)) though the backing
   method `exportAreasToDatabase()` is still present and functional
   ([NAreasWidget.java:1132-1205](src/nurgling/widgets/NAreasWidget.java#L1132)) — evidence of an
   abandoned/disabled UI entry point, currently unreachable by users, that once let someone manually
   push a whole local areas file into the DB.
6. **No "sync now" control.** Push is debounce-driven (3s after last local edit) and pull is
   interval-driven (4s), both automatic, with no way to force an immediate round-trip other than
   toggling DB Settings (which triggers a full reload, not a push).

## Minimal improvement proposal

Given the code already provides UUID identity (partially wired), `updated_at`/`version`, a tombstone
(`deleted_at`) state, last-write-wins-by-group conflict handling, and incremental delta polling, no
rewrite is warranted. Two small, targeted changes would close the actual gaps for this team's workflow:

1. **Fix the `uuid`-on-reimport bug**: when an `NArea` is constructed from an imported JSON file (or at
   minimum, in the three `NConfig` import strategies), either (a) clear `uuid` to `null` so
   `AreaService.saveArea` mints a fresh one, treating the import as a brand-new area server-side, or (b)
   if the intent is genuinely "restore this exact synced area," look the area up **by `uuid`** first
   (a query `AreaDao` does not currently offer) and reuse its existing `id` instead of allocating a new
   one. Option (a) is the smaller, safer change.
2. **Add an optional `is_private` (or `shared` inverse) boolean column** to `areas`, defaulting to
   `false`, exposed as a per-area toggle in `NAreasWidget`'s right-click menu next to "Delete." Filter
   it out of `AreaDao.loadAreasByProfile`/`getAllAreaVersions` reads used by other clients' `syncTick`
   (i.e., a private area is written to the DB under the owner's own row for backup/multi-session
   convenience, but the poll's version-map query excludes rows flagged private-by-someone-else). This
   is additive — it does not touch the existing merge/tombstone/versioning machinery — and directly
   satisfies "personal areas should remain local."

Neither of these requires a new server, protocol, or storage engine; both are schema + DAO + UI changes
within the existing `AreaService`/`AreaDao`/`NAreasWidget` files.

## Important code excerpts

**Stable identity is assigned client-side, not server-side** —
[src/nurgling/NMapView.java:1207-1219](src/nurgling/NMapView.java#L1207-L1219):
```java
NArea newArea = new NArea(key);
newArea.id = id;
newArea.uuid = java.util.UUID.randomUUID().toString();
newArea.space = result;
...
newArea.baselineVersion = 0;
newArea.baselineSnapshot = null;
newArea.markDirty(nurgling.areas.AreaFieldGroup.GEOMETRY);
newArea.markDirty(nurgling.areas.AreaFieldGroup.IDENTITY);
newArea.markDirty(nurgling.areas.AreaFieldGroup.COSMETIC);
newArea.markDirty(nurgling.areas.AreaFieldGroup.ROUTING);
```

**OCC write, keyed on `id` + `version`, not `uuid`** —
[src/nurgling/db/dao/AreaDao.java:203-208](src/nurgling/db/dao/AreaDao.java#L203-L208):
```java
String updateSql = "UPDATE areas SET " +
    "name = ?, path = ?, hide = ?, color_r = ?, color_g = ?, color_b = ?, color_a = ?, " +
    "data = ?, profile = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP, " +
    "last_touched_by = ?, last_touched_at = CURRENT_TIMESTAMP, deleted_at = NULL " +
    (uuid != null ? ", uuid = COALESCE(uuid, ?) " : "") +
    "WHERE id = ? AND version = ?";
```

**Field-group three-way merge decision** —
[src/nurgling/db/service/AreaMerger.java:60-71](src/nurgling/db/service/AreaMerger.java#L60-L71):
```java
for (AreaFieldGroup g : AreaFieldGroup.values()) {
    boolean l = localDirty.contains(g);
    boolean r = remoteDirty.contains(g);
    if (r && !l) {
        takeRemote.add(g);
    } else if (r && l) {
        // Conflict in this group - server timestamp wins.
        takeRemote.add(g);
        remoteOverrode.add(g);
    }
    // else: !r && !l (nothing), or !r && l (keep local)
}
```

**Tombstone, not hard delete** —
[src/nurgling/db/dao/AreaDao.java:287-293](src/nurgling/db/dao/AreaDao.java#L287-L293):
```java
public void tombstoneArea(DatabaseAdapter adapter, int id, String profile, String byPlayer) throws SQLException {
    adapter.executeUpdate(
        "UPDATE areas SET deleted_at = CURRENT_TIMESTAMP, version = version + 1, " +
        "last_touched_by = ?, last_touched_at = CURRENT_TIMESTAMP " +
        "WHERE id = ? AND profile = ? AND deleted_at IS NULL",
        byPlayer, id, profile);
}
```

**Automatic sync start — no manual trigger required** —
[src/nurgling/NCore.java:930-952](src/nurgling/NCore.java#L930-L952):
```java
private void startAreaSync() {
    if (areaSyncStarted || databaseManager == null || !databaseManager.isReady()) return;
    ...
    databaseManager.getAreaService().startSync(syncProfile, 4,
        new nurgling.db.service.AreaService.AreaSyncCallback() { ... });
}
```
called from [NCore.java:261-266](src/nurgling/NCore.java#L261-L266), the moment `databaseManager` is
constructed with `ndbenable = true` — i.e. automatically, not via any button.

**Import strategies keep the old `uuid` under a new `id`** —
[src/nurgling/NConfig.java:1546-1558](src/nurgling/NConfig.java#L1546-L1558):
```java
NArea a = new NArea((JSONObject) array.get(i));   // uuid, if present in file, is preserved here
int id = 1;
for (NArea area : ((NMapView) NUtils.getGameUI().map).glob.map.areas.values()) {
    if (area.name.equals(a.name)) { a.name = "Other_" + a.name; }
    if (area.id >= id) { id = area.id + 1; }
}
a.id = id;   // new local id assigned; a.uuid is never touched
((NMapView) NUtils.getGameUI().map).glob.map.areas.put(a.id, a);
```

## Git history: original intent vs current state

- `#150 Merge pull request #150 from aleksandrsvoboda/fix/db-sync-of-areas` and
  `#186 fix/sqlite areas` — the DB-backed area storage/sync was introduced upstream (not by this fork)
  as a fix/feature, not a from-scratch design; the earliest working history is a bugfix commit,
  suggesting the DB sync path predates any written design doc in this repo.
- `#192 fix/multisession area sync` (`a295ae0bf`) — multi-session correctness (the `ThreadLocalUI`
  binding, per-session bulk-load tracking seen in `AreaService.syncTick`) was a later fix, implying the
  original implementation did not handle multiple concurrent sessions correctly.
- Commit `bb3b7524b "Stop new areas from reusing tombstoned ids"` and PR
  `#271 fix/area-id-reuse-tombstone` (RegulumDreik) — this fork specifically hardened the id-allocation
  watermark logic (`getMaxAreaId`/`getMaxKnownAreaId`) after discovering that reusing a tombstoned id
  made a brand-new area vanish on the next sync poll — direct evidence that the "id" identity scheme is
  the fragile part of this design, consistent with the `uuid`-not-really-used-as-a-key gap noted above.
- Commit `d9311752c "Fix area disable checkbox being ignored by bot area lookups"` and PR
  `#267 fix/area-disable-flag-ignored` — confirms the `hide` flag's javadoc warning in
  [NArea.java:20-32](src/nurgling/areas/NArea.java#L20-L32) reflects a real, previously-shipped bug, not
  speculative caution.
- In-code comments reference "Phase 1/2/3/5 area-sync refactor"
  ([NArea.java:325](src/nurgling/areas/NArea.java#L325),
  [AreaService.java:325-330](src/nurgling/db/service/AreaService.java#L325-L330)) indicating the
  uuid/tombstone/merge/presence system was built incrementally in this fork after the original upstream
  DB-sync feature landed — no separate phase-numbered design document was found in the repo; the phase
  numbers exist only as code comments.

## Questions for ChatGPT

1. Given the confirmed `uuid`-not-used-as-primary-key gap, is the safer immediate mitigation "stop
   using JSON export/import entirely once all three players are on the shared DB" (operational, zero
   code change) or "patch the three `NConfig` import methods to null out `uuid`" (small code change,
   removes the trap for anyone who imports a file later)? Both were proposed; which risk profile does
   the team prefer given they don't want a large rewrite?
2. Is a private/local-area flag actually needed given the team's stated use case, or would "just don't
   connect personal-area sessions to the shared DB profile" (e.g. keep personal scratch areas in a
   second, unshared world/profile) already solve it without a schema change? Worth discussing before
   committing to the `is_private` column proposal.
3. The `id` PK collision risk (item 3 in Gaps) only matters if this DB is ever reused for a second
   world. Confirm whether that's a real future plan for this team or a purely theoretical concern not
   worth fixing now.
4. No evidence was found of authentication/authorization on the shared Postgres connection beyond
   whatever Postgres itself enforces (username/password in `DatabaseSettings`) — i.e., anyone with the
   connection string can read/write every area (and everything else in that DB: hearth secrets,
   positions, planning layers, etc., per the other checkboxes visible in `DatabaseSettings`). Confirm
   this is an acceptable trust model for the team (a private DB reachable only by the three of them) —
   this report did not audit network/DB-level access control, since that's outside this repo.
