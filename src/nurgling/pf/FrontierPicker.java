package nurgling.pf;

import haven.Coord;
import haven.Coord2d;
import haven.MCache;
import nurgling.NUtils;
import nurgling.tools.NDebugLog;

import java.util.Set;

/**
 * Terrain-agnostic frontier-exploration primitives (Yamauchi-style: steer
 * toward the boundary between known-safe and unknown space), shared by both
 * the water (WorldExplorerFrontier) and land (LandFrontier) bots rather than
 * writing the same ray-scan twice. Gated by a caller-supplied TileAcceptor so
 * "safe" means whatever the caller needs (WaterTiles.isSafe, TerrainFilter).
 *
 * The live, always-on frontier signal is MCache's own loaded-vs-not-yet-
 * loaded grid boundary: MCache.grids only grows during a session, and a
 * coordinate whose grid hasn't arrived yet simply has no entry -
 * safeTileName() below treats a missing grid entry as "unresolved," which is
 * the actual frontier edge. This deliberately never calls the throwing
 * MCache.getgrid()/gettile() path - that path throws haven.Loading for
 * exactly this case, because it's meant to trigger a server request+retry,
 * not signal "this is the edge of the map."
 */
public class FrontierPicker
{
    public interface TileAcceptor
    {
        boolean accept(String tileName);
    }

    private static final double STEP = MCache.tilesz.x;
    // Primary heading first, then a few neighboring headings, per the
    // original frontier-exploration design's ray-fan spec.
    private static final double[] HEADING_OFFSETS_DEG = {0, 30, -30, 60, -60};

    /**
     * Primary signal: ray-scan outward from pos along heading (and a few
     * neighboring headings) up to maxRayDist, looking for the first tile
     * whose grid hasn't been received this session - that's the live
     * frontier edge. Walks back inward from the edge along the same ray
     * until acceptor holds, and returns that point. Returns null if no ray
     * reaches unresolved terrain within maxRayDist (caller should fall back
     * to pickFrontierChunk below).
     */
    public static Coord2d pickFrontierPoint(Coord2d pos, Coord2d heading, TileAcceptor acceptor, double maxRayDist)
    {
        Coord2d dirBase = heading.norm();
        for (double offsetDeg : HEADING_OFFSETS_DEG)
        {
            Coord2d dir = dirBase.rot(Math.toRadians(offsetDeg));
            Coord2d edge = rayToUnresolved(pos, dir, maxRayDist, acceptor);
            if (edge == null)
                continue;
            Coord2d target = walkBackToAcceptable(pos, edge, acceptor);
            if (target != null)
            {
                debug("frontier edge at " + edge + " (heading offset " + offsetDeg + " deg) -> target " + target);
                return target;
            }
        }
        return null;
    }

    /**
     * Walks along dir from pos looking for the frontier edge (first
     * unresolved tile). Bails out (returns null, rejecting this heading)
     * the moment it crosses a resolved-but-unacceptable tile (land, in the
     * water case) before reaching unresolved territory - without this check,
     * a straight ray that runs over land partway through would still count
     * as a "successful" heading as long as it eventually reaches unresolved
     * ground far past the shore, which locks the caller onto whatever
     * heading it started with instead of ever trying the other headings in
     * the ray-fan to actually follow the coastline's shape.
     */
    private static Coord2d rayToUnresolved(Coord2d pos, Coord2d dir, double maxDist, TileAcceptor acceptor)
    {
        double travelled = 0;
        while (travelled < maxDist)
        {
            travelled += STEP;
            Coord2d sample = pos.add(dir.mul(travelled));
            String name = safeTileName(sample);
            if (name == null)
                return sample; // genuine frontier edge
            if (!acceptor.accept(name))
                return null; // runs into unsuitable terrain before reaching new territory - reject this heading
        }
        return null;
    }

    private static Coord2d walkBackToAcceptable(Coord2d pos, Coord2d edge, TileAcceptor acceptor)
    {
        double total = pos.dist(edge);
        if (total < 1)
            return null;
        Coord2d back = pos.sub(edge).div(total);
        double travelled = 0;
        while (travelled < total)
        {
            Coord2d sample = edge.add(back.mul(travelled));
            String name = safeTileName(sample);
            if (name != null && acceptor.accept(name))
                return sample;
            travelled += STEP;
        }
        return null;
    }

    /**
     * Second-tier signal, for when the straight-line ray-fan above finds
     * nothing (common in a small enclosed lake/bay, where none of the 5
     * fixed headings can stay in water all the way to unresolved terrain).
     * Scans the actual edges of every currently-loaded grid for the nearest
     * acceptable tile that borders an unloaded neighbor grid - a genuine
     * frontier cell found by examining all known space, not guessing a
     * handful of directions. Cheap: only currently-loaded grids' edge rows
     * are scanned (not their full interior, and never unloaded grids), so
     * this stays bounded regardless of world size.
     */
    public static Coord2d scanLoadedGridsForFrontier(Coord2d pos, TileAcceptor acceptor)
    {
        MCache map = NUtils.getGameUI().ui.sess.glob.map;
        java.util.List<MCache.Grid> grids;
        synchronized (map.grids)
        {
            grids = new java.util.ArrayList<>(map.grids.values());
        }

        Coord2d best = null;
        double bestDist = Double.MAX_VALUE;
        int[][] dirs = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        for (MCache.Grid g : grids)
        {
            for (int[] d : dirs)
            {
                if (map.grids.containsKey(g.gc.add(d[0], d[1])))
                    continue; // that neighbor is loaded too - this edge isn't a frontier
                for (int i = 0; i < MCache.cmaps.x; i++)
                {
                    Coord local = (d[1] != 0)
                            ? new Coord(i, d[1] < 0 ? 0 : MCache.cmaps.y - 1)
                            : new Coord(d[0] < 0 ? 0 : MCache.cmaps.x - 1, i);
                    String name = map.tilesetname(g.gettile(local));
                    if (name == null || !acceptor.accept(name))
                        continue;
                    Coord2d worldPos = g.ul.add(local).mul(MCache.tilesz).add(MCache.tilehsz);
                    double dist = pos.dist(worldPos);
                    if (dist < bestDist)
                    {
                        bestDist = dist;
                        best = worldPos;
                    }
                }
            }
        }
        if (best != null)
            debug("edge-scan frontier tile at " + best + " (dist=" + String.format("%.0f", bestDist) + ")");
        return best;
    }

    /**
     * Last resort, guaranteed non-null as long as ANY acceptable tile exists
     * anywhere near pos (true in practice - the player is standing in one).
     * Per explicit requirement: this bot must never just stop because
     * nothing "new" is in view right now - if every other signal comes up
     * empty, keep moving along heading anyway (biased search center ahead of
     * pos, not at it) so new grids keep streaming in as the boat travels,
     * which will surface a real frontier again on a later iteration.
     */
    public static Coord2d fallbackKeepMoving(Coord2d pos, Coord2d heading, TileAcceptor acceptor)
    {
        Coord2d aheadCenter = pos.add(heading.norm().mul(STEP * 10));
        Coord2d found = ringSearch(aheadCenter, acceptor, 30);
        if (found == null)
            found = ringSearch(pos, acceptor, 30);
        debug("keep-moving fallback -> " + found);
        return found;
    }

    /**
     * Fallback for when every nearby ray stays inside already-visited
     * territory before reaching unresolved ground (e.g. resuming a run after
     * a relogin, deep inside previously-explored space): picks the unvisited
     * neighbor chunk (of the 8 around pos's own chunk) closest to heading,
     * then ring-searches outward from that chunk's center (capped at
     * maxRingRadius tiles) for a point satisfying acceptor - chunk centers
     * can land on unsuitable terrain, so this search is required, not
     * optional. Returns null if every neighbor chunk is already visited or
     * no acceptable point is found near the chosen one.
     */
    public static Coord2d pickFrontierChunk(Coord2d pos, Coord2d heading, TileAcceptor acceptor,
                                             Set<Long> visitedGridIds, int maxRingRadius)
    {
        MCache map = NUtils.getGameUI().ui.sess.glob.map;
        Coord tc = pos.div(MCache.tilesz).floor();
        Coord gc = tc.div(MCache.cmaps);
        Coord2d dirBase = heading.norm();

        MCache.Grid best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dy = -1; dy <= 1; dy++)
            {
                if (dx == 0 && dy == 0)
                    continue;
                MCache.Grid g = map.grids.get(gc.add(dx, dy));
                if (g == null || visitedGridIds.contains(g.id))
                    continue;
                Coord2d center = g.ul.add(MCache.cmaps.div(2)).mul(MCache.tilesz);
                double score = center.sub(pos).norm().dot(dirBase);
                if (score > bestScore)
                {
                    bestScore = score;
                    best = g;
                }
            }
        }
        if (best == null)
        {
            debug("no unvisited neighbor chunk found near grid " + gc);
            return null;
        }

        Coord2d center = best.ul.add(MCache.cmaps.div(2)).mul(MCache.tilesz);
        Coord2d found = ringSearch(center, acceptor, maxRingRadius);
        debug("fallback to chunk id=" + best.id + " center=" + center + " -> " + found);
        return found;
    }

    private static Coord2d ringSearch(Coord2d center, TileAcceptor acceptor, int maxRadius)
    {
        String centerName = safeTileName(center);
        if (centerName != null && acceptor.accept(centerName))
            return center;
        for (int r = 1; r <= maxRadius; r++)
        {
            for (int dx = -r; dx <= r; dx++)
            {
                for (int dy = -r; dy <= r; dy++)
                {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r)
                        continue; // only the ring's outer edge at this radius
                    Coord2d sample = center.add(dx * MCache.tilesz.x, dy * MCache.tilesz.y);
                    String name = safeTileName(sample);
                    if (name != null && acceptor.accept(name))
                        return sample;
                }
            }
        }
        return null;
    }

    /**
     * Null-safe tile-name lookup: returns null both for a coordinate whose
     * grid hasn't arrived this session (the frontier signal) and for a
     * resolved-but-untyped tile, without ever risking the throwing
     * MCache.getgrid()/gettile() path.
     */
    public static String safeTileName(Coord2d worldPos)
    {
        MCache map = NUtils.getGameUI().ui.sess.glob.map;
        Coord tc = worldPos.div(MCache.tilesz).floor();
        MCache.Grid g = map.grids.get(tc.div(MCache.cmaps));
        if (g == null)
            return null;
        return map.tilesetname(g.gettile(tc.sub(g.ul)));
    }

    // File-only (see NDebugLog's doc) - this fires every iteration, chat
    // became physically uncopyable during a real testing session.
    private static void debug(String msg)
    {
        NDebugLog.log("FrontierPicker: " + msg);
    }
}
