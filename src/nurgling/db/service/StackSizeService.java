package nurgling.db.service;

import nurgling.NGameUI;
import nurgling.db.DatabaseManager;
import nurgling.db.dao.StackSizeDao;
import nurgling.sessions.SessionContext;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Shared, self-correcting override for {@code nurgling.tools.StackSupporter}'s static stack-size
 * table. See {@code docs/inventory-grid-system.md} for the feature this backs.
 *
 * <p>Stack-size knowledge is a fact about the <em>item</em>, not per-character/per-window data, so
 * unlike {@link FishLocationDbService} (per-session) this keeps one shared cache and syncs once per
 * distinct live <em>profile</em> (world/genus) - the same "group by profile, not session" choice
 * {@link PeerPositionDbService} already makes, and for the same reason: every character logged into
 * the same world would otherwise duplicate identical sync work.
 *
 * <p>{@link #lookup} is the hot path - {@code StackSupporter.isStackable}/{@code getFullStackSize}
 * are called on bots' inner loops - and is a pure synchronous in-memory read, exactly like {@link
 * AreaService}'s live map never queries the database on a lookup. The database is only touched by
 * the background poller and by explicit async writes (never blocking the caller).
 */
public class StackSizeService {

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

    /** profile -> name -> info. The live cache; reads never touch the database. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, StackInfo>> cache = new ConcurrentHashMap<>();

    /** Profiles that have had their bulk load. Clearing forces every profile to bulk-load again. */
    private final Set<String> bulkLoadedProfiles = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Per-profile view of the row versions already applied, so a poll only fetches changes. */
    private final ConcurrentHashMap<String, Map<String, Integer>> knownVersions = new ConcurrentHashMap<>();

    private volatile boolean syncEnabled = false;
    private ScheduledExecutorService syncScheduler = null;

    public StackSizeService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    // -------------------- Hot-path read --------------------

    /**
     * Synchronous, in-memory only - never touches the database. Null means "this profile's cache
     * has no opinion on this name," in which case the caller (StackSupporter) falls back to its
     * static table, not "this item doesn't stack."
     */
    public StackInfo lookup(String name) {
        Map<String, StackInfo> byName = cache.get(currentProfile());
        return (byName != null) ? byName.get(name) : null;
    }

    private static String currentProfile() {
        try {
            NGameUI gui = nurgling.NUtils.getGameUI();
            if (gui != null) {
                String genus = gui.getGenus();
                if (genus != null && !genus.isEmpty()) return genus;
            }
        } catch (RuntimeException ignore) {
            // No session bound on this thread, or genus not resolved yet - fall through.
        }
        return "global";
    }

    // -------------------- Write path --------------------

    /**
     * Passive-learning observation (see {@code nurgling.NInventory}'s periodic call site). A pure
     * no-op - no database call at all - unless {@code observedCount} is strictly bigger than what's
     * currently cached for this name; after the first correction, every later observation of the
     * same or a smaller stack is again a pure in-memory compare.
     */
    public CompletableFuture<Void> observeAndMaybeLearnAsync(String name, int observedCount) {
        String profile = currentProfile();
        ConcurrentHashMap<String, StackInfo> byName = cache.computeIfAbsent(profile, p -> new ConcurrentHashMap<>());
        StackInfo current = byName.get(name);
        if (current != null && observedCount <= current.maxStack) {
            return CompletableFuture.completedFuture(null);
        }
        // Optimistic in-memory update first, so this same session sees the fix immediately
        // without waiting for the next poll.
        byName.put(name, new StackInfo(observedCount, true, "learned"));
        String touchedBy = currentPlayerName();
        return databaseManager.executeWithRetry(adapter -> {
            dao.upsertIfBigger(adapter, profile, name, observedCount, touchedBy);
            return (Void) null;
        }, "learn stack size " + name);
    }

    /** Calibration-UI write: unconditionally set an item's values, provenance forced to 'manual'. */
    public CompletableFuture<Void> setManualAsync(String name, int maxStack, boolean stackable) {
        String profile = currentProfile();
        cache.computeIfAbsent(profile, p -> new ConcurrentHashMap<>())
            .put(name, new StackInfo(maxStack, stackable, "manual"));
        String touchedBy = currentPlayerName();
        return databaseManager.executeWithRetry(adapter -> {
            dao.setManual(adapter, profile, name, maxStack, stackable, touchedBy);
            return (Void) null;
        }, "set stack size " + name);
    }

    /** Calibration-UI delete: the item falls back to StackSupporter's static table again. */
    public CompletableFuture<Void> deleteAsync(String name) {
        String profile = currentProfile();
        Map<String, StackInfo> byName = cache.get(profile);
        if (byName != null) byName.remove(name);
        String touchedBy = currentPlayerName();
        return databaseManager.executeWithRetry(adapter -> {
            dao.tombstone(adapter, profile, name, touchedBy);
            return (Void) null;
        }, "delete stack size " + name);
    }

    /** Defensive snapshot of the current profile's known entries, for the calibration UI. */
    public Map<String, StackInfo> snapshotForUi() {
        Map<String, StackInfo> byName = cache.get(currentProfile());
        return (byName != null) ? new HashMap<>(byName) : Collections.emptyMap();
    }

    // -------------------- Sync --------------------

    public void startSync(long intervalSeconds) {
        if (syncEnabled) stopSync();
        this.syncEnabled = true;
        this.bulkLoadedProfiles.clear();
        this.knownVersions.clear();
        this.syncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "StackSize-Sync-Worker");
            t.setDaemon(true);
            return t;
        });
        syncScheduler.scheduleAtFixedRate(this::syncTick, 1, intervalSeconds, TimeUnit.SECONDS);
        System.out.println("Stack size sync started, interval=" + intervalSeconds + "s (by profile)");
    }

    public void stopSync() {
        syncEnabled = false;
        bulkLoadedProfiles.clear();
        knownVersions.clear();
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

        Collection<SessionContext> sessions;
        try {
            sessions = nurgling.sessions.SessionManager.getInstance().getAllSessions();
        } catch (RuntimeException e) {
            return;
        }
        if (sessions == null || sessions.isEmpty()) return;

        // Every session logged into the same world shares one profile's worth of sync work -
        // see the class doc for why this is grouped by profile rather than by session.
        Set<String> liveProfiles = new HashSet<>();
        for (SessionContext sc : sessions) {
            if (sc == null || sc.ui == null) continue;
            NGameUI gui = sc.getGameUI();
            if (gui == null) continue;
            String profile = gui.getGenus();
            if (profile == null || profile.isEmpty()) profile = "global";
            liveProfiles.add(profile);
        }
        if (liveProfiles.isEmpty()) return;

        // Drop tracking (and cached data) for profiles nobody is in anymore, so these maps don't
        // grow across logouts.
        bulkLoadedProfiles.retainAll(liveProfiles);
        knownVersions.keySet().retainAll(liveProfiles);
        cache.keySet().retainAll(liveProfiles);

        for (String profile : liveProfiles) {
            try {
                if (bulkLoadedProfiles.add(profile)) {
                    runBulkLoad(profile);
                } else {
                    runDeltaPoll(profile);
                }
            } catch (SQLException | RuntimeException e) {
                // Let the next tick retry (bulk-loading again if that is what failed).
                bulkLoadedProfiles.remove(profile);
                String msg = e.getMessage();
                if (msg != null && !msg.contains("no such table") && !msg.contains("no such column")
                    && !msg.contains("does not exist")) {
                    System.err.println("Stack size sync error (profile=" + profile + "): " + msg);
                }
            }
        }
    }

    private void runBulkLoad(String profile) throws SQLException {
        long t0 = System.currentTimeMillis();
        List<StackSizeDao.StackSizeRow> rows = databaseManager.executeOperation(
            adapter -> dao.loadAll(adapter, profile));

        ConcurrentHashMap<String, StackInfo> byName = new ConcurrentHashMap<>();
        Map<String, Integer> versions = new HashMap<>();
        for (StackSizeDao.StackSizeRow row : rows) {
            byName.put(row.name, new StackInfo(row.maxStack, row.stackable, row.provenance));
            versions.put(row.name, row.version);
        }
        cache.put(profile, byName);
        knownVersions.put(profile, versions);

        System.out.println("Stack size sync: bulk-loaded " + rows.size() + " rows in "
            + (System.currentTimeMillis() - t0) + "ms (profile=" + profile + ")");
    }

    private void runDeltaPoll(String profile) throws SQLException {
        Map<String, Integer> known = knownVersions.computeIfAbsent(profile, k -> new HashMap<>());
        ConcurrentHashMap<String, StackInfo> byName = cache.computeIfAbsent(profile, p -> new ConcurrentHashMap<>());

        Map<String, StackSizeDao.VersionInfo> dbVersions = databaseManager.executeOperation(
            adapter -> dao.getAllVersions(adapter, profile));

        List<String> fetch = new ArrayList<>();
        for (Map.Entry<String, StackSizeDao.VersionInfo> e : dbVersions.entrySet()) {
            String name = e.getKey();
            StackSizeDao.VersionInfo info = e.getValue();
            Integer localVersion = known.get(name);

            if (info.tombstoned) {
                if (localVersion != null) {
                    byName.remove(name);
                    known.remove(name);
                }
                continue;
            }
            if (localVersion == null || info.version > localVersion) {
                fetch.add(name);
            }
        }
        if (fetch.isEmpty()) return;

        List<StackSizeDao.StackSizeRow> rows = databaseManager.executeOperation(
            adapter -> dao.loadByNames(adapter, profile, fetch));
        for (StackSizeDao.StackSizeRow row : rows) {
            byName.put(row.name, new StackInfo(row.maxStack, row.stackable, row.provenance));
            known.put(row.name, row.version);
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
