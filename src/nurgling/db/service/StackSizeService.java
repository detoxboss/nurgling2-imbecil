package nurgling.db.service;

import nurgling.db.DatabaseManager;
import nurgling.db.dao.StackSizeDao;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared, self-correcting override for {@code nurgling.tools.StackSupporter}'s static stack-size
 * table. See {@code docs/inventory-grid-system.md} for the feature this backs.
 *
 * <p><b>Not profile/genus-scoped, unlike {@link AreaService}/{@link PeerPositionDbService}.</b> An
 * area or a player position is inherently a fact about a specific world; an item's max stack size
 * is a fact about the game's item definitions, which - barring a server deliberately running
 * modified game balance - is the same everywhere. Everything here uses one fixed row key
 * ({@link #PROFILE}), matching the {@code profile='global'} the seeding migration already writes
 * under (migration 13, {@code nurgling/db/migration/MigrationManager.java}).
 *
 * <p>An earlier version of this class scoped rows by the session's live genus instead, following
 * the PeerPositionDbService pattern uncritically. That silently broke the seeding goal: the 801
 * seeded rows sat under {@code profile='global'} while every real lookup/write resolved the
 * player's actual genus, so the bulk load for any real world found zero rows, and every stackable
 * item a player had ever seen looked "unknown" every session - each one firing a real passive-
 * learning write on first sight instead of a cheap cache hit. Harmless per {@link #lookup}'s
 * null-means-fall-back-to-static-table contract, but it defeated most of the point of seeding and
 * added avoidable background DB writes that could contend with every other feature's fire-and-
 * forget saves (they all share one single-threaded queue, see {@link DatabaseManager#executeWithRetry}).
 *
 * <p>{@link #lookup} is the hot path - {@code StackSupporter.isStackable}/{@code getFullStackSize}
 * are called on bots' inner loops - and is a pure synchronous in-memory read, exactly like {@link
 * AreaService}'s live map never queries the database on a lookup. The database is only touched by
 * the background poller and by explicit async writes (never blocking the caller).
 */
public class StackSizeService {

    /** The single row-scope every {@code stack_sizes} row lives under - see the class doc. */
    private static final String PROFILE = "global";

    /** One item's cached stack-size facts. Immutable; a change replaces the cache entry wholesale. */
    public static final class StackInfo {
        public final int maxStack;
        public final boolean stackable;
        public final String provenance;

        public StackInfo(int maxStack, boolean stackable, String provenance) {
            this.maxStack = maxStack;
            this.stackable = stackable;
            this.provenance = provenance;
        }
    }

    private final DatabaseManager databaseManager;
    private final StackSizeDao dao = new StackSizeDao();

    /** name -> info. The live cache; reads never touch the database. */
    private final ConcurrentHashMap<String, StackInfo> cache = new ConcurrentHashMap<>();

    /** The row versions already applied, so a delta poll only fetches what actually changed. */
    private final Map<String, Integer> knownVersions = new HashMap<>();

    private final AtomicBoolean bulkLoaded = new AtomicBoolean(false);
    private volatile boolean syncEnabled = false;
    private ScheduledExecutorService syncScheduler = null;

    public StackSizeService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    // -------------------- Hot-path read --------------------

    /**
     * Synchronous, in-memory only - never touches the database. Null means "the cache has no
     * opinion on this name," in which case the caller (StackSupporter) falls back to its static
     * table, not "this item doesn't stack."
     */
    public StackInfo lookup(String name) {
        return cache.get(name);
    }

    // -------------------- Write path --------------------

    /**
     * Passive-learning observation (see {@code nurgling.NInventory}'s periodic call site). A pure
     * no-op - no database call at all - unless {@code observedCount} is strictly bigger than what's
     * currently cached for this name; after the first correction, every later observation of the
     * same or a smaller stack is again a pure in-memory compare.
     */
    public CompletableFuture<Void> observeAndMaybeLearnAsync(String name, int observedCount) {
        StackInfo current = cache.get(name);
        if (current != null && observedCount <= current.maxStack) {
            return CompletableFuture.completedFuture(null);
        }
        // Optimistic in-memory update first, so this same session sees the fix immediately
        // without waiting for the next poll.
        cache.put(name, new StackInfo(observedCount, true, "learned"));
        String touchedBy = currentPlayerName();
        return databaseManager.executeWithRetry(adapter -> {
            dao.upsertIfBigger(adapter, PROFILE, name, observedCount, touchedBy);
            return (Void) null;
        }, "learn stack size " + name);
    }

    /** Calibration-UI write: unconditionally set an item's values, provenance forced to 'manual'. */
    public CompletableFuture<Void> setManualAsync(String name, int maxStack, boolean stackable) {
        cache.put(name, new StackInfo(maxStack, stackable, "manual"));
        String touchedBy = currentPlayerName();
        return databaseManager.executeWithRetry(adapter -> {
            dao.setManual(adapter, PROFILE, name, maxStack, stackable, touchedBy);
            return (Void) null;
        }, "set stack size " + name);
    }

    /** Calibration-UI delete: the item falls back to StackSupporter's static table again. */
    public CompletableFuture<Void> deleteAsync(String name) {
        cache.remove(name);
        String touchedBy = currentPlayerName();
        return databaseManager.executeWithRetry(adapter -> {
            dao.tombstone(adapter, PROFILE, name, touchedBy);
            return (Void) null;
        }, "delete stack size " + name);
    }

    /** Defensive snapshot of every known entry, for the calibration UI. */
    public Map<String, StackInfo> snapshotForUi() {
        return new HashMap<>(cache);
    }

    // -------------------- Sync --------------------

    public void startSync(long intervalSeconds) {
        if (syncEnabled) stopSync();
        this.syncEnabled = true;
        this.bulkLoaded.set(false);
        synchronized (knownVersions) {
            knownVersions.clear();
        }
        this.syncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "StackSize-Sync-Worker");
            t.setDaemon(true);
            return t;
        });
        syncScheduler.scheduleAtFixedRate(this::syncTick, 1, intervalSeconds, TimeUnit.SECONDS);
        System.out.println("Stack size sync started, interval=" + intervalSeconds + "s");
    }

    public void stopSync() {
        syncEnabled = false;
        bulkLoaded.set(false);
        if (syncScheduler != null) {
            syncScheduler.shutdown();
            try {
                syncScheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                syncScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            syncScheduler = null;
        }
        System.out.println("Stack size sync stopped");
    }

    public boolean isSyncRunning() { return syncEnabled; }

    private void syncTick() {
        if (!syncEnabled) return;
        if (databaseManager == null || !databaseManager.isReady()) return;

        try {
            if (bulkLoaded.compareAndSet(false, true)) {
                runBulkLoad();
            } else {
                runDeltaPoll();
            }
        } catch (SQLException | RuntimeException e) {
            // Let the next tick retry (bulk-loading again if that is what failed).
            bulkLoaded.set(false);
            String msg = e.getMessage();
            if (msg != null && !msg.contains("no such table") && !msg.contains("no such column")
                && !msg.contains("does not exist")) {
                System.err.println("Stack size sync error: " + msg);
            }
        }
    }

    private void runBulkLoad() throws SQLException {
        long t0 = System.currentTimeMillis();
        List<StackSizeDao.StackSizeRow> rows = databaseManager.executeOperation(
            adapter -> dao.loadAll(adapter, PROFILE));

        Map<String, Integer> versions = new HashMap<>();
        for (StackSizeDao.StackSizeRow row : rows) {
            cache.put(row.name, new StackInfo(row.maxStack, row.stackable, row.provenance));
            versions.put(row.name, row.version);
        }
        synchronized (knownVersions) {
            knownVersions.clear();
            knownVersions.putAll(versions);
        }

        System.out.println("Stack size sync: bulk-loaded " + rows.size() + " rows in "
            + (System.currentTimeMillis() - t0) + "ms");
    }

    private void runDeltaPoll() throws SQLException {
        Map<String, StackSizeDao.VersionInfo> dbVersions = databaseManager.executeOperation(
            adapter -> dao.getAllVersions(adapter, PROFILE));

        List<String> fetch = new ArrayList<>();
        synchronized (knownVersions) {
            for (Map.Entry<String, StackSizeDao.VersionInfo> e : dbVersions.entrySet()) {
                String name = e.getKey();
                StackSizeDao.VersionInfo info = e.getValue();
                Integer localVersion = knownVersions.get(name);

                if (info.tombstoned) {
                    if (localVersion != null) {
                        cache.remove(name);
                        knownVersions.remove(name);
                    }
                    continue;
                }
                if (localVersion == null || info.version > localVersion) {
                    fetch.add(name);
                }
            }
        }
        if (fetch.isEmpty()) return;

        List<StackSizeDao.StackSizeRow> rows = databaseManager.executeOperation(
            adapter -> dao.loadByNames(adapter, PROFILE, fetch));
        synchronized (knownVersions) {
            for (StackSizeDao.StackSizeRow row : rows) {
                cache.put(row.name, new StackInfo(row.maxStack, row.stackable, row.provenance));
                knownVersions.put(row.name, row.version);
            }
        }
    }

    /** Best-effort player name for the last_touched_by column. */
    private static String currentPlayerName() {
        try {
            if (nurgling.NUtils.getUI() != null && nurgling.NUtils.getUI().sess != null
                && nurgling.NUtils.getUI().sess.user != null) {
                String name = nurgling.NUtils.getUI().sess.user.name;
                if (name != null && !name.isEmpty()) return name;
            }
            if (nurgling.NUtils.getGameUI() != null && nurgling.NUtils.getGameUI().chrid != null) {
                return nurgling.NUtils.getGameUI().chrid;
            }
        } catch (RuntimeException ignore) {
        }
        return "unknown";
    }
}
