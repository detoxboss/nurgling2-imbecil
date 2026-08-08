package nurgling.actions.bots;

import haven.Coord;
import haven.Coord2d;
import haven.MCache;
import nurgling.NUtils;
import nurgling.conf.NWorldExplorerProp;
import nurgling.pf.FrontierPicker;
import nurgling.pf.WaterTiles;
import nurgling.tools.NDebugLog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Water frontier target selection for WorldExplorer: built on FrontierPicker
 * (shared ray-scan/chunk-fallback), gated by WaterTiles.isSafe, with a
 * persisted per-character visited-grid set so a resumed run doesn't
 * immediately re-explore the same water after a relogin.
 *
 * Grid identity is MCache.Grid.id, not grid coordinate - ChunkNavData
 * explicitly persists only .id and drops the session-relative gc/ul fields
 * (confirmed by direct read of ChunkNavData.java), so this follows the same
 * convention.
 */
public class WorldExplorerFrontier
{
    // Mirrors ChunkNavManager's own 2s save-throttle to avoid excessive disk writes.
    private static final long SAVE_THROTTLE_MS = 2000;

    private final NWorldExplorerProp prop;
    private final Set<Long> visitedGridIds;
    private long lastSaveTime = 0;

    public WorldExplorerFrontier(NWorldExplorerProp prop)
    {
        this.prop = prop;
        this.visitedGridIds = new HashSet<>(prop.visitedGridIds);
    }

    public int visitedCount()
    {
        return visitedGridIds.size();
    }

    /**
     * Marks the grid at worldPos visited, persisting (throttled) to prop.
     */
    public void markVisited(Coord2d worldPos)
    {
        MCache.Grid g = gridAt(worldPos);
        if (g == null || !visitedGridIds.add(g.id))
            return; // already known, or grid not loaded yet - nothing new to persist
        long now = System.currentTimeMillis();
        if (now - lastSaveTime >= SAVE_THROTTLE_MS)
        {
            lastSaveTime = now;
            prop.visitedGridIds = new ArrayList<>(visitedGridIds);
            NWorldExplorerProp.set(prop);
            NDebugLog.log("WorldExplorerFrontier: saved " + visitedGridIds.size() + " visited grids");
        }
    }

    // PathFinder/NPFMap cannot actually route arbitrarily far. NPFMap's search
    // box half-width (dsize) is computed from the FULL pos-target distance,
    // not half of it, and is centered on the pos-target MIDPOINT - so the
    // box's far corner sits at roughly 1.5x the pos-target distance from pos.
    // That corner must stay within the player's ~400-unit worst-case
    // guaranteed "visible area" radius (Utils.inVisibleArea) or NPFMap falls
    // back to a single fixed-size box with NO retry/grow via mul - and A*
    // additionally treats that box's own border cells as permanently blocked,
    // so a target sitting close to the edge is unreliable even when nominally
    // "in range". 400 (the first value tried) was already too far - a real
    // run confirmed "Can't find path" persisted even after clamping to it.
    // 200 keeps the 1.5x far corner under ~300, with real margin.
    private static final double MAX_ROUTE_DIST = 200;

    /**
     * Picks the next frontier target, in order: (1) FrontierPicker's live
     * ray-fan (cheap, works over open water); (2) a full scan of currently-
     * loaded grids' edges for the nearest real frontier tile (handles small
     * enclosed lakes/bays where no straight ray reaches unresolved terrain);
     * (3) the persisted-visited-aware neighbor-chunk search; (4) a
     * guaranteed-non-null "keep moving" fallback. Per explicit requirement,
     * this never returns null - the bot must not stop just because nothing
     * new is in view right now.
     *
     * A tier's result is skipped (falling through to the next tier) if it's
     * closer than minTargetDist to pos - a target that close isn't worth
     * actually moving to and is exactly what makes PathFinder's "already
     * there" case fire with no real movement, which starved the caller's
     * loop-pacing safety net during a real incident (see WorldExplorer's
     * MIN_ITERATION_MS doc). Tier 4 is exempted since it must never return
     * null; the caller's hard iteration-rate cap is the backstop for that case.
     *
     * Whatever tier succeeds, the result is then clamped to MAX_ROUTE_DIST
     * (see its doc) before being returned - scanning far to find the true
     * direction of the frontier is fine and desirable, but only a reachable
     * step toward it is ever handed to PathFinder. The boat closes the
     * remaining distance over subsequent iterations as new terrain loads in.
     */
    public Coord2d pickTarget(Coord2d pos, Coord2d heading, boolean deeperMode, double maxRayDist,
                               int maxRingRadius, double minTargetDist)
    {
        FrontierPicker.TileAcceptor acceptor = name -> WaterTiles.isSafe(name, deeperMode);

        Coord2d target = FrontierPicker.pickFrontierPoint(pos, heading, acceptor, maxRayDist);
        if (target != null && pos.dist(target) >= minTargetDist)
            return clampToRoutable(pos, target);

        target = FrontierPicker.scanLoadedGridsForFrontier(pos, acceptor);
        if (target != null && pos.dist(target) >= minTargetDist)
            return clampToRoutable(pos, target);

        target = FrontierPicker.pickFrontierChunk(pos, heading, acceptor, visitedGridIds, maxRingRadius);
        if (target != null && pos.dist(target) >= minTargetDist)
            return clampToRoutable(pos, target);

        return clampToRoutable(pos, FrontierPicker.fallbackKeepMoving(pos, heading, acceptor));
    }

    private static Coord2d clampToRoutable(Coord2d pos, Coord2d target)
    {
        if (target == null)
            return null; // only possible from fallbackKeepMoving's own documented edge case
        double dist = pos.dist(target);
        if (dist <= MAX_ROUTE_DIST)
            return target;
        Coord2d clamped = pos.add(target.sub(pos).div(dist).mul(MAX_ROUTE_DIST));
        NDebugLog.log("WorldExplorerFrontier: clamped target (dist=" + (int) dist
                + ") to routable range -> " + clamped);
        return clamped;
    }

    private static MCache.Grid gridAt(Coord2d worldPos)
    {
        MCache map = NUtils.getGameUI().ui.sess.glob.map;
        Coord tc = worldPos.div(MCache.tilesz).floor();
        return map.grids.get(tc.div(MCache.cmaps));
    }
}
