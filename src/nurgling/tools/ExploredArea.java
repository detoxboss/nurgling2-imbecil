package nurgling.tools;

import haven.*;
import nurgling.NConfig;
import nurgling.widgets.NMiniMap;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Tracks explored (visible) area on the minimap.
 * Uses grid-based boolean masks for efficient storage and fast updates.
 * Each grid (100x100 tiles) has its own mask marking explored tiles.
 * 
 * Supports session layers - temporary explored areas that can be created
 * and deleted without affecting the main persistent explored area.
 */
public class ExploredArea {
    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "exploration-save"); t.setDaemon(true); return t;
    });
    private final Object dataLock = new Object();
    private long dataEpoch;

    /** The caller captures its profile path; the worker never consults UI globals. */
    public CompletableFuture<Void> saveAsync(String path) {
        return CompletableFuture.runAsync(() -> {
            try {mergeAndSaveToFile(path);}
            catch(IOException e) {throw new java.util.concurrent.CompletionException(e);}
        }, io);
    }
    // Version tracking for cache invalidation (similar to TileHighlight.seq)
    public static volatile long seq = 0;
    // Separate version tracking for session layer
    public static volatile long sessionSeq = 0;
    
    private static final int GRID_SIZE = 100; // MCache.cmaps.x
    private static final int MASK_SIZE = GRID_SIZE * GRID_SIZE;
    
    /**
     * Key for identifying a grid in a specific segment.
     */
    private static class GridKey {
        final long segmentId;
        final Coord gridCoord;  // Grid coordinate at data level 0
        
        GridKey(long segmentId, Coord gridCoord) {
            this.segmentId = segmentId;
            this.gridCoord = gridCoord;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GridKey)) return false;
            GridKey key = (GridKey) o;
            return segmentId == key.segmentId && gridCoord.equals(key.gridCoord);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(segmentId, gridCoord);
        }
    }
    
    final NMiniMap miniMap;
    
    // Main storage: grid-based masks (persistent)
    private final ConcurrentHashMap<GridKey, boolean[]> gridMasks = new ConcurrentHashMap<>();
    
    // Session layer storage: grid-based masks (temporary, not saved)
    private final ConcurrentHashMap<GridKey, boolean[]> sessionGridMasks = new ConcurrentHashMap<>();
    
    // Flag indicating if session layer is active
    private volatile boolean sessionActive = false;
    
    /**
     * Get the appropriate NConfig instance for this ExploredArea's own session.
     * Resolved via the owning minimap's own ui/core, NOT an ambient "current UI"
     * lookup - an ambient lookup returns whichever session happens to be the
     * foreground/active one, which is a different session than this ExploredArea
     * whenever it belongs to a background multi-session tab. That mismatch was
     * the root cause of explored-area data being loaded from/saved to the wrong
     * session's config in multi-account/multi-character setups.
     */
    private NConfig getConfig() {
        if(miniMap != null && miniMap.ui != null && miniMap.ui.core != null)
            return miniMap.ui.core.config;
        try {
            if (miniMap.ui != null && miniMap.ui.core != null) {
                return miniMap.ui.core.config;
            }
        } catch (Exception e) {
            // Fallback to global config
        }
        return NConfig.current;
    }
    
    // Track last update position to avoid redundant updates
    private Coord lastTileUL, lastTileBR;
    private long lastSegmentId = -1;

    /* ---------------- Save scheduling ----------------
     *
     * Two things are combined here deliberately, and both halves are load-bearing:
     *
     *  - Ownership is this fork's. The dirty flag, the debounce window and the retry backoff all
     *    live on THIS session's own ExploredArea, not on the genus-shared NConfig, so two
     *    same-world sessions in one client cannot clear each other's save trigger before their own
     *    tiles reach disk (same reasoning as MCache.markAreasDirty()). The trigger is pulled from
     *    this session's own NCore.tick(); nothing here ever asks "which session is active".
     *
     *  - The write itself is upstream's. saveAsync() runs mergeAndSaveToFile() on a dedicated
     *    single-thread executor, so a multi-megabyte merge never stalls the frame, and
     *    mergeAndSaveToFile() takes a real file lock and refuses to write at all rather than
     *    clobbering another client's exploration.
     *
     * The seam between them is the revision counter. Every change bumps `revision`; a save captures
     * the revision it is about to persist, and on success clears the dirty flag ONLY if the
     * revision has not moved meanwhile. A write that completes after newer tiles were discovered
     * therefore cannot mark those tiles clean.
     */

    private final Object saveLock = new Object();
    /** True when this session has tiles that are not on disk yet. Guarded by {@link #saveLock}. */
    private boolean needSave = false;
    /** When the most recent change happened, for the debounce window. Guarded by saveLock. */
    private long lastChangeTime = 0;
    /** Bumped on every change. The identity a completed save is checked against. Guarded by saveLock. */
    private long revision = 0;
    /** The in-flight save, or null when idle. Guarded by saveLock. */
    private CompletableFuture<Void> pendingSave = null;
    /** The revision {@link #pendingSave} is persisting. Guarded by saveLock. */
    private long pendingRevision = -1;
    /** Earliest time a new attempt may start, after a failure. Guarded by saveLock. */
    private long nextAttemptAt = 0;
    /** Consecutive failed attempts, for the backoff curve. Guarded by saveLock. */
    private int failures = 0;

    /** Batch rapid changes into one write. */
    private static final long SAVE_DEBOUNCE_MS = 5000;
    /** How long mergeAndSaveToFile() waits before its one retry of the file lock. */
    static final long LOCK_RETRY_SLEEP_MS = 100;
    /** First backoff step after a failed save; doubles per consecutive failure. */
    private static final long RETRY_BACKOFF_BASE_MS = 2000;
    /** Ceiling for the backoff, so a permanently unwritable file costs one attempt a minute. */
    private static final long RETRY_BACKOFF_MAX_MS = 60000;

    private void markDirty() {
        synchronized (saveLock) {
            needSave = true;
            lastChangeTime = System.currentTimeMillis();
            revision++;
        }
    }

    /**
     * True once this session's own explored area has unsaved changes older than the debounce
     * window, with no save already running and no backoff outstanding.
     */
    public boolean isSaveDue() {
        synchronized (saveLock) {
            return isSaveDueLocked(System.currentTimeMillis());
        }
    }

    private boolean isSaveDueLocked(long now) {
        return needSave
            && lastChangeTime > 0
            && pendingSave == null
            && now >= nextAttemptAt
            && (now - lastChangeTime) >= SAVE_DEBOUNCE_MS;
    }

    /**
     * Start a merge-save if a debounced change is pending. Called from this session's own
     * NCore.tick(), and returns immediately - the write runs on the exploration-save executor.
     */
    public void saveIfDue() {
        synchronized (saveLock) {
            if (!isSaveDueLocked(System.currentTimeMillis())) {
                return;
            }
            beginSaveLocked();
        }
    }

    /**
     * Start a merge-save now, ignoring the debounce window but not an already-running save.
     * Returns the future for the write that will persist the current revision, or null when there
     * is nothing to do.
     */
    private CompletableFuture<Void> beginSaveLocked() {
        /* The profile path is resolved HERE, on the caller's thread, from this ExploredArea's own
         * owning session - never inside the worker. A background task that looked the path up for
         * itself would have to ask which session is current, and would write one session's tiles
         * into another session's file. */
        String path;
        try {
            path = getConfig().getExploredPath();
        } catch (Exception e) {
            System.err.println("Error resolving explored-area path: " + e.getMessage());
            return null;
        }
        if (path == null) {
            return null;
        }

        final long rev = revision;
        pendingRevision = rev;
        CompletableFuture<Void> future = saveAsync(path).whenComplete((unused, failure) -> {
            synchronized (saveLock) {
                pendingSave = null;
                pendingRevision = -1;
                if (failure == null) {
                    failures = 0;
                    nextAttemptAt = 0;
                    /* Only the revision this write captured is on disk. If exploration happened
                     * while it was in flight, `revision` has moved past it and the dirty flag must
                     * stay set, or those newer tiles are stranded in memory with nothing scheduled
                     * to ever write them. */
                    if (revision == rev) {
                        needSave = false;
                        lastChangeTime = 0;
                    }
                } else {
                    /* needSave is deliberately left alone: nothing was lost, the data is still in
                     * memory, and the next attempt must happen. What changes is WHEN - without a
                     * backoff, a file another client holds the lock on would be retried on every
                     * single tick, each attempt paying a tryLock plus LOCK_RETRY_SLEEP_MS. */
                    failures++;
                    long step = RETRY_BACKOFF_BASE_MS << Math.min(failures - 1, 10);
                    nextAttemptAt = System.currentTimeMillis()
                        + Math.min(step, RETRY_BACKOFF_MAX_MS);
                }
            }
            if (failure != null) {
                System.err.println("Error saving explored area: " + failure.getMessage());
            }
        });
        pendingSave = future;
        return future;
    }

    /**
     * Flush on session teardown/logout, as a bounded best effort.
     *
     * <p>Teardown is the one place this cannot simply hand the write to the executor and return.
     * The executor's thread is a daemon, so on a full client exit the JVM will not wait for it:
     * fire-and-forget here means a pending change is lost outright. So this waits - but a logout
     * that hangs is worse than losing up to {@link #SAVE_DEBOUNCE_MS} of exploration, which is
     * re-derivable by walking there again and, because every write is a merge, is never corrupting.
     *
     * <p>Three states, each needing something different:
     * <ul>
     *   <li><b>Nothing dirty.</b> Return at once; there is nothing to write.</li>
     *   <li><b>A save already running that covers the current revision.</b> Do not start a second
     *       one. It would queue behind the first on the single-thread executor, then find the file
     *       lock still held and fail for no reason. Wait for the one in flight instead.</li>
     *   <li><b>A save already running that is now stale</b> (tiles were discovered after it
     *       captured its revision). Waiting is still required first, because that save holds the
     *       file lock and a second write would just be refused - so wait for it, then issue exactly
     *       one more write for the newer revision and wait for that.</li>
     * </ul>
     *
     * <p>On timeout, the dirty flag is left set. If this client is still alive (one of several
     * sessions closing), the periodic saver picks it up on a later tick; if the client is exiting,
     * the data was going to be lost either way and nothing has been falsely marked clean.
     */
    public void saveOnTeardown() {
        CompletableFuture<Void> inFlight;
        boolean stale;
        synchronized (saveLock) {
            if (!needSave && pendingSave == null) {
                return;
            }
            inFlight = pendingSave;
            stale = (inFlight != null) && (revision != pendingRevision);
        }

        if (inFlight != null) {
            if (!awaitBounded(inFlight, "in-flight") || !stale) {
                /* Either it did not finish in budget - in which case starting another write is
                 * pointless, it would contend with one still holding the lock - or it finished and
                 * covered everything. */
                return;
            }
        }

        CompletableFuture<Void> last;
        synchronized (saveLock) {
            if (!needSave || pendingSave != null) {
                return;
            }
            /* Teardown ignores both the debounce window and the retry backoff: this is the last
             * chance this data gets, so a pending backoff must not be what loses it. */
            last = beginSaveLocked();
        }
        if (last != null) {
            awaitBounded(last, "teardown");
        }
    }

    /**
     * The budget for one wait at teardown.
     *
     * <p>Derived rather than picked: a single save attempt is already internally bounded -
     * mergeAndSaveToFile() does one tryLock, sleeps {@link #LOCK_RETRY_SLEEP_MS}, tries once more
     * and then gives up - so the lock phase can cost at most two of those sleeps even when another
     * client is mid-write. What is not internally bounded is queueing: the executor is one thread
     * shared by every ExploredArea in the process, so this write may sit behind another session's.
     * The budget is therefore two lock-retry windows plus one allowance for the actual merge and
     * atomic write, which is the longest a legitimate save can take.
     */
    private static final long TEARDOWN_IO_BUDGET_MS = 2000;
    private static final long TEARDOWN_WAIT_MS = (2 * LOCK_RETRY_SLEEP_MS) + TEARDOWN_IO_BUDGET_MS;

    /** @return true when the future completed (successfully or not) inside the budget */
    private static boolean awaitBounded(CompletableFuture<Void> future, String what) {
        try {
            future.get(TEARDOWN_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            System.err.println("Explored-area " + what + " save did not finish within "
                + TEARDOWN_WAIT_MS + "ms; left pending rather than marked saved");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            /* The completion handler above has already logged it and set the backoff. */
            return true;
        }
    }


    public ExploredArea(NMiniMap miniMap) {
        this.miniMap = miniMap;
        // Note: Don't load from file here! The profile may not be initialized yet.
        // Data is loaded later via reloadFromFile() when the profile is ready.
        // This prevents loading from the wrong path and losing profile-specific data.
    }
    
    /**
     * Update explored area with current view bounds.
     * This is called every tick when player moves.
     * Very fast - just sets bits in the mask, no complex Rectangle operations.
     * Also updates session layer if active.
     */
    public void updateExploredTiles(Coord tileUL, Coord tileBR, long segmentId) {
        // Skip if same as last update
        if (Objects.equals(tileUL, lastTileUL) && Objects.equals(tileBR, lastTileBR) && segmentId == lastSegmentId) {
            return;
        }
        
        lastTileUL = tileUL;
        lastTileBR = tileBR;
        lastSegmentId = segmentId;
        
        // Calculate which grids are affected
        Coord gridUL = tileUL.div(GRID_SIZE);
        Coord gridBR = tileBR.sub(1, 1).div(GRID_SIZE); // Inclusive end
        
        boolean changed = false;
        boolean sessionChanged = false;
        
        // Update each affected grid
        for (int gy = gridUL.y; gy <= gridBR.y; gy++) {
            for (int gx = gridUL.x; gx <= gridBR.x; gx++) {
                Coord gridCoord = new Coord(gx, gy);
                GridKey key = new GridKey(segmentId, gridCoord);
                
                // Calculate tile bounds within this grid
                Coord gridTileStart = gridCoord.mul(GRID_SIZE);
                int localULX = Math.max(0, tileUL.x - gridTileStart.x);
                int localULY = Math.max(0, tileUL.y - gridTileStart.y);
                int localBRX = Math.min(GRID_SIZE, tileBR.x - gridTileStart.x);
                int localBRY = Math.min(GRID_SIZE, tileBR.y - gridTileStart.y);
                
                synchronized(dataLock) {
                    boolean[] old = gridMasks.get(key);
                    boolean[] mask = reveal(old, localULX, localULY, localBRX, localBRY);
                    if(mask != old) {gridMasks.put(key, mask); changed = true;}
                    if(sessionActive) {
                        old = sessionGridMasks.get(key);
                        mask = reveal(old, localULX, localULY, localBRX, localBRY);
                        if(mask != old) {sessionGridMasks.put(key, mask); sessionChanged = true;}
                    }
                }
            }
        }
        
        if (changed) {
            seq++;
            markDirty();
        }
        if (sessionChanged) {
            sessionSeq++;
            needSessionUpdate = true;
        }
    }

    /* Published masks are immutable snapshots. Identity is a per-grid revision:
     * revealing another grid must not invalidate every visible overlay. */
    private static boolean[] reveal(boolean[] original, int x0, int y0, int x1, int y1) {
        boolean[] result = original;
        for(int y = y0; y < y1; y++) for(int x = x0; x < x1; x++) {
            int i = x + y * GRID_SIZE;
            if(result == null || !result[i]) {
                if(result == original) result = original == null ? new boolean[MASK_SIZE] : original.clone();
                result[i] = true;
            }
        }
        return result;
    }

    private static boolean[] union(boolean[] original, boolean[] extra) {
        if(original == null) return extra;
        boolean[] result = original;
        for(int i = 0; i < MASK_SIZE; i++) if(extra[i] && !result[i]) {
            if(result == original) result = original.clone();
            result[i] = true;
        }
        return result;
    }
    
    // Flag for session save
    private volatile boolean needSessionUpdate = false;
    private long lastSessionSaveTime = 0;
    private static final long SESSION_SAVE_INTERVAL = 5000; // Save every 5 seconds max
    
    /**
     * Get explored mask for a specific grid at base level (dataLevel 0).
     * Used by MinimapExploredAreaRenderer for rendering.
     * Very fast - just returns the stored mask.
     * 
     * @param gridCoord Grid coordinate at base level
     * @param segmentId Segment ID
     * @param dataLevel Must be 0 (aggregation is done by renderer)
     * @return boolean[] mask or null if no data
     */
    public boolean[] getExploredMaskForGrid(Coord gridCoord, long segmentId, int dataLevel) {
        GridKey key = new GridKey(segmentId, gridCoord);
        return gridMasks.get(key);
    }
    
    /**
     * Clear all explored data.
     */
    public void clear() {
        synchronized(dataLock) {
        dataEpoch++;
        if (!gridMasks.isEmpty()) {
            gridMasks.clear();
            lastTileUL = null;
            lastTileBR = null;
            lastSegmentId = -1;
            seq++;
            markDirty();
        }
        }
    }
    
    /**
     * Check if session layer is currently active.
     */
    public boolean isSessionActive() {
        return sessionActive;
    }
    
    /**
     * Start a new session layer.
     * Clears any existing session data and starts fresh.
     * Resets last position to force immediate update of current view.
     */
    public void startSession() {
        sessionGridMasks.clear();
        sessionActive = true;
        // Reset last position to force immediate coloring of current view
        lastTileUL = null;
        lastTileBR = null;
        lastSegmentId = -1;
        sessionSeq++;
        // Save session state
        saveSessionToFile();
    }
    
    /**
     * End and delete the session layer.
     * All session data is discarded.
     */
    public void endSession() {
        sessionGridMasks.clear();
        sessionActive = false;
        sessionSeq++;
        // Delete session file
        deleteSessionFile();
    }
    
    /**
     * Get session mask for a specific grid at base level (dataLevel 0).
     * Used by MinimapExploredAreaRenderer for rendering session overlay.
     * 
     * @param gridCoord Grid coordinate at base level
     * @param segmentId Segment ID
     * @return boolean[] mask or null if no data or session not active
     */
    public boolean[] getSessionMaskForGrid(Coord gridCoord, long segmentId) {
        if (!sessionActive) {
            return null;
        }
        GridKey key = new GridKey(segmentId, gridCoord);
        return sessionGridMasks.get(key);
    }
    
    /**
     * Tick method - handles periodic session saving.
     */
    public void tick(double dt) {
        // Periodically save session data if needed
        if (needSessionUpdate && sessionActive) {
            long now = System.currentTimeMillis();
            if (now - lastSessionSaveTime > SESSION_SAVE_INTERVAL) {
                needSessionUpdate = false;
                lastSessionSaveTime = now;
                saveSessionToFile();
            }
        }
    }
    
    /**
     * Reload explored area data from file.
     * Call this after profile initialization to load profile-specific data.
     * Merges file data with any in-memory data (in case exploration happened before profile init).
     */
    public void reloadFromFile() {
        synchronized(dataLock) {dataEpoch++;}
        // Save current in-memory data before loading
        Map<GridKey, boolean[]> currentData = new HashMap<>(gridMasks);
        
        // Load from file
        gridMasks.clear();
        loadFromFile();
        
        // Merge in-memory data back (OR operation - keep explored tiles from both)
        for (Map.Entry<GridKey, boolean[]> entry : currentData.entrySet()) {
            GridKey key = entry.getKey();
            boolean[] memoryMask = entry.getValue();
            
            boolean[] fileMask = gridMasks.get(key);
            if (fileMask == null) {
                // Grid only in memory, add it
                gridMasks.put(key, memoryMask);
            } else {
                // Merge: OR the masks
                gridMasks.put(key, union(fileMask, memoryMask));
            }
        }
        
        // Also reload session data (session doesn't need merge - it's temporary)
        sessionGridMasks.clear();
        loadSessionFromFile();
        
        seq++;
    }
    
    /**
     * Load explored area from JSON file.
     */
    private void loadFromFile() {
        // Use profile-specific config from NCore if available, otherwise fallback to global
        NConfig config = getConfig();

        try {
            String content = NFileUtils.readWithBackupFallback(config.getExploredPath());
            if (content == null || content.isEmpty()) {
                return;
            }

            JSONObject json = new JSONObject(content);
            if (!json.has("grids")) {
                return;
            }
            
            JSONArray gridsArray = json.getJSONArray("grids");
            for (int i = 0; i < gridsArray.length(); i++) {
                JSONObject gridJson = gridsArray.getJSONObject(i);
                
                long segmentId = gridJson.getLong("seg");
                int gx = gridJson.getInt("gx");
                int gy = gridJson.getInt("gy");
                
                GridKey key = new GridKey(segmentId, new Coord(gx, gy));
                
                // Decode RLE compressed mask
                String rle = gridJson.getString("mask");
                boolean[] mask = decodeRLE(rle);
                
                if (mask != null) {
                    gridMasks.put(key, mask);
                }
            }
            
            seq++;
        } catch (Exception e) {
            // Ignore load errors
        }
    }
    
    /**
     * Save explored area to JSON file.
     */
    public JSONObject toJson() {
        JSONArray gridsArray = new JSONArray();
        
        for (Map.Entry<GridKey, boolean[]> entry : gridMasks.entrySet()) {
            GridKey key = entry.getKey();
            boolean[] mask = entry.getValue();
            
            // Skip empty masks
            if (!hasAnyExploredTiles(mask)) {
                continue;
            }
            
            JSONObject gridJson = new JSONObject();
            gridJson.put("seg", key.segmentId);
            gridJson.put("gx", key.gridCoord.x);
            gridJson.put("gy", key.gridCoord.y);
            
            // Encode mask with RLE compression
            gridJson.put("mask", encodeRLE(mask));
            
            gridsArray.put(gridJson);
        }
        
        JSONObject doc = new JSONObject();
        doc.put("grids", gridsArray);
        return doc;
    }
    
    /**
     * Convert session data to JSON for saving.
     */
    private JSONObject sessionToJson(Map<GridKey, boolean[]> data, boolean active) {
        JSONArray gridsArray = new JSONArray();
        
        for (Map.Entry<GridKey, boolean[]> entry : data.entrySet()) {
            GridKey key = entry.getKey();
            boolean[] mask = entry.getValue();
            
            // Skip empty masks
            if (!hasAnyExploredTiles(mask)) {
                continue;
            }
            
            JSONObject gridJson = new JSONObject();
            gridJson.put("seg", key.segmentId);
            gridJson.put("gx", key.gridCoord.x);
            gridJson.put("gy", key.gridCoord.y);
            
            // Encode mask with RLE compression
            gridJson.put("mask", encodeRLE(mask));
            
            gridsArray.put(gridJson);
        }
        
        JSONObject doc = new JSONObject();
        doc.put("active", active);
        doc.put("grids", gridsArray);
        return doc;
    }
    
    /**
     * Save session data to file.
     */
    private void saveSessionToFile() {
        String path = getConfig().getSessionExploredPath();
        Map<GridKey, boolean[]> snapshot = new HashMap<>(sessionGridMasks);
        boolean active = sessionActive;
        queueSessionWrite(() -> {
            try {NFileUtils.writeAtomically(path, sessionToJson(snapshot, active).toString());}
            catch(IOException e) {needSessionUpdate = true; System.err.println("Session exploration save failed: " + e.getMessage());}
        });
    }

    private Runnable pendingSessionWrite;
    private boolean sessionWriterRunning;
    /** One running and one replaceable request: slow disks cannot grow a task backlog. */
    private synchronized void queueSessionWrite(Runnable action) {
        pendingSessionWrite = action;
        if(sessionWriterRunning) return;
        sessionWriterRunning = true;
        io.execute(() -> {
            while(true) {
                Runnable next;
                synchronized(ExploredArea.this) {
                    next = pendingSessionWrite;
                    pendingSessionWrite = null;
                    if(next == null) {sessionWriterRunning = false; return;}
                }
                try {next.run();}
                catch(RuntimeException e) {System.err.println("Session exploration write failed: " + e.getMessage());}
            }
        });
    }
    
    /**
     * Load session data from file.
     */
    private void loadSessionFromFile() {
        NConfig config = getConfig();

        try {
            String content = NFileUtils.readWithBackupFallback(config.getSessionExploredPath());
            if (content == null || content.isEmpty()) {
                return;
            }

            JSONObject json = new JSONObject(content);
            
            // Load active state
            if (json.has("active")) {
                sessionActive = json.getBoolean("active");
            }
            
            if (!json.has("grids")) {
                return;
            }
            
            JSONArray gridsArray = json.getJSONArray("grids");
            for (int i = 0; i < gridsArray.length(); i++) {
                JSONObject gridJson = gridsArray.getJSONObject(i);
                
                long segmentId = gridJson.getLong("seg");
                int gx = gridJson.getInt("gx");
                int gy = gridJson.getInt("gy");
                
                GridKey key = new GridKey(segmentId, new Coord(gx, gy));
                
                // Decode RLE compressed mask
                String rle = gridJson.getString("mask");
                boolean[] mask = decodeRLE(rle);
                
                if (mask != null) {
                    sessionGridMasks.put(key, mask);
                }
            }
            
            sessionSeq++;
        } catch (Exception e) {
            // Ignore load errors
        }
    }
    
    /**
     * Delete session file.
     */
    private void deleteSessionFile() {
        String path = getConfig().getSessionExploredPath();
        queueSessionWrite(() -> {
            try {Files.deleteIfExists(Paths.get(path));}
            catch(IOException e) {System.err.println("Session exploration delete failed: " + e.getMessage());}
        });
    }
    
    /**
     * Check if mask has any explored tiles.
     */
    private boolean hasAnyExploredTiles(boolean[] mask) {
        for (boolean tile : mask) {
            if (tile) return true;
        }
        return false;
    }
    
    /**
     * Encode boolean mask with RLE (Run-Length Encoding) for compression.
     * Format: "startBit:count1,count2,count3..." where startBit (0 or 1) indicates first value.
     */
    private String encodeRLE(boolean[] mask) {
        StringBuilder sb = new StringBuilder();
        
        // Store the starting value (0 for false, 1 for true)
        sb.append(mask[0] ? '1' : '0').append(':');
        
        boolean currentValue = mask[0];
        int count = 1;
        
        for (int i = 1; i < mask.length; i++) {
            if (mask[i] == currentValue) {
                count++;
            } else {
                sb.append(count).append(',');
                currentValue = mask[i];
                count = 1;
            }
        }
        sb.append(count); // Last run
        
        return sb.toString();
    }
    
    /**
     * Decode RLE compressed mask.
     */
    private boolean[] decodeRLE(String rle) {
        try {
            // Split by colon to get starting bit and run counts
            String[] mainParts = rle.split(":", 2);
            if (mainParts.length != 2) {
                return null;
            }
            
            // Get starting value (0 = false, 1 = true)
            boolean currentValue = mainParts[0].equals("1");
            
            // Parse run counts
            String[] parts = mainParts[1].split(",");
            boolean[] mask = new boolean[MASK_SIZE];
            
            int idx = 0;
            
            for (String part : parts) {
                int count = Integer.parseInt(part.trim());
                for (int i = 0; i < count && idx < MASK_SIZE; i++) {
                    mask[idx++] = currentValue;
                }
                currentValue = !currentValue; // Toggle
            }
            
            return mask;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Merge in-memory data with existing file data and save with file locking.
     * This prevents data loss when multiple clients run simultaneously.
     * 
     * Algorithm:
     * 1. Acquire exclusive file lock
     * 2. Read existing data from file
     * 3. Merge: for each grid, OR the masks together (explored in file OR explored in memory)
     * 4. Write merged result
     * 5. Update in-memory data with merged result
     * 6. Release lock
     */
    public void mergeAndSaveToFile(String filePath) throws IOException {
        final long epoch;
        synchronized(dataLock) {epoch = dataEpoch;}
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        
        // Create lock file to coordinate access
        File lockFile = new File(filePath + ".lock");
        
        try (RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
             FileChannel channel = raf.getChannel()) {
            
            // Acquire exclusive lock (blocks until available)
            FileLock lock = null;
            try {
                lock = channel.tryLock();
                if (lock == null) {
                    // Could not acquire lock immediately, wait a bit and try again
                    try {
                        Thread.sleep(LOCK_RETRY_SLEEP_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    lock = channel.tryLock();
                }
                
                if (lock == null) {
                    throw new IOException("Exploration file is busy; retry without overwriting another client's data");
                }
                
                // Read existing data from file
                Map<GridKey, boolean[]> diskData = readFromDisk(filePath);
                
                // Merge: OR the masks together
                // First, copy disk data to merged result
                Map<GridKey, boolean[]> mergedData = new HashMap<>();
                for (Map.Entry<GridKey, boolean[]> entry : diskData.entrySet()) {
                    boolean[] copy = new boolean[MASK_SIZE];
                    System.arraycopy(entry.getValue(), 0, copy, 0, MASK_SIZE);
                    mergedData.put(entry.getKey(), copy);
                }
                
                // Then, merge in-memory data
                for (Map.Entry<GridKey, boolean[]> entry : gridMasks.entrySet()) {
                    GridKey key = entry.getKey();
                    boolean[] memoryMask = entry.getValue();
                    
                    boolean[] existingMask = mergedData.get(key);
                    if (existingMask == null) {
                        // New grid, just add it
                        boolean[] copy = new boolean[MASK_SIZE];
                        System.arraycopy(memoryMask, 0, copy, 0, MASK_SIZE);
                        mergedData.put(key, copy);
                    } else {
                        // Merge: OR the masks
                        for (int i = 0; i < MASK_SIZE; i++) {
                            existingMask[i] = existingMask[i] || memoryMask[i];
                        }
                    }
                }
                
                // Write merged result to file
                JSONObject doc = toJsonFromData(mergedData);
                NFileUtils.writeAtomically(filePath, doc.toString());
                
                // Update in-memory data with merged result (so we have the latest data)
                // This is important to prevent re-saving stale data
                for (Map.Entry<GridKey, boolean[]> entry : mergedData.entrySet()) {
                    GridKey key = entry.getKey();
                    boolean[] mergedMask = entry.getValue();
                    synchronized(dataLock) {
                        if(dataEpoch != epoch) break;
                        // Preserve exploration revealed while the worker was saving.
                        gridMasks.put(key, union(gridMasks.get(key), mergedMask));
                    }
                }
                
            } finally {
                if (lock != null) {
                    try {
                        lock.release();
                    } catch (Exception e) {
                        // Ignore
                    }
                }
            }
        } catch (Exception e) {
            throw e instanceof IOException ? (IOException)e : new IOException("Exploration save failed", e);
        }
    }
    
    /**
     * Read grid data from disk file.
     */
    private Map<GridKey, boolean[]> readFromDisk(String filePath) {
        Map<GridKey, boolean[]> result = new HashMap<>();

        try {
            String content = NFileUtils.readWithBackupFallback(filePath);
            if (content == null || content.isEmpty()) {
                return result;
            }

            JSONObject json = new JSONObject(content);
            if (!json.has("grids")) {
                return result;
            }
            
            JSONArray gridsArray = json.getJSONArray("grids");
            for (int i = 0; i < gridsArray.length(); i++) {
                JSONObject gridJson = gridsArray.getJSONObject(i);
                
                long segmentId = gridJson.getLong("seg");
                int gx = gridJson.getInt("gx");
                int gy = gridJson.getInt("gy");
                
                GridKey key = new GridKey(segmentId, new Coord(gx, gy));
                
                // Decode RLE compressed mask
                String rle = gridJson.getString("mask");
                boolean[] mask = decodeRLE(rle);
                
                if (mask != null) {
                    result.put(key, mask);
                }
            }
        } catch (Exception e) {
            // Ignore load errors, return what we have
        }
        
        return result;
    }
    
    /**
     * Convert given data to JSON (for saving merged data).
     */
    private JSONObject toJsonFromData(Map<GridKey, boolean[]> data) {
        JSONArray gridsArray = new JSONArray();
        
        for (Map.Entry<GridKey, boolean[]> entry : data.entrySet()) {
            GridKey key = entry.getKey();
            boolean[] mask = entry.getValue();
            
            // Skip empty masks
            if (!hasAnyExploredTiles(mask)) {
                continue;
            }
            
            JSONObject gridJson = new JSONObject();
            gridJson.put("seg", key.segmentId);
            gridJson.put("gx", key.gridCoord.x);
            gridJson.put("gy", key.gridCoord.y);
            
            // Encode mask with RLE compression
            gridJson.put("mask", encodeRLE(mask));
            
            gridsArray.put(gridJson);
        }
        
        JSONObject doc = new JSONObject();
        doc.put("grids", gridsArray);
        return doc;
    }
}
