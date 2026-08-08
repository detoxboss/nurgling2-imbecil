package nurgling.pf;

import haven.Coord;
import haven.Coord2d;
import haven.MCache;
import nurgling.NUtils;

/**
 * A snapshot of the navigable terrain around the player, plus a
 * distance-to-obstacle field computed over it.
 *
 * This exists because keying navigation off a specific tile-type boundary
 * (the shallow/deep waterline) is unreliable: that boundary is optional in
 * the world. Real coastlines frequently touch deep water directly, and the
 * boundary also disappears across beaches and wide shallow flats. Land, by
 * contrast, is always present wherever there is a coast. So the coast is
 * defined here as an iso-contour of "distance to nearest blocked tile"
 * rather than as a tile-type transition, which makes it well-defined
 * everywhere regardless of whether shallow water happens to be there.
 *
 * Classification is deliberately fail-closed: an unloaded tile counts as
 * BLOCKED, so the bot never steers into terrain it cannot see. That does
 * mean the distance field is wrong near the window's outer edge (it is
 * measuring distance to the edge of knowledge, not to real land), which is
 * why {@link #trusted} exists and why callers must not place waypoints
 * outside it.
 */
public class TileField
{
    /** Window half-width in tiles; the scan is (2*RADIUS+1) square. */
    public static final int RADIUS = 50;
    /**
     * Waypoints must stay this far inside the window edge, because within
     * this margin the distance field is dominated by the artificial
     * "unloaded == blocked" boundary rather than by real terrain.
     */
    private static final int EDGE_MARGIN = 8;

    // Chamfer 3-4 distance transform: an orthogonal step costs 3 and a
    // diagonal 4, so raw values approximate 3x the true euclidean distance.
    // Cheaper and much smoother than a 4-connected BFS, whose diamond-shaped
    // contours produce a blocky gradient that makes the follower wobble.
    private static final int ORTHO = 3;
    private static final int DIAG = 4;
    private static final int UNREACHED = Integer.MAX_VALUE / 4;

    public final Coord origin;      // tile coord of cell (0,0)
    public final int size;          // window edge length in tiles
    private final boolean[] nav;    // navigable (safe water for the mode)
    private final int[] dist;       // chamfer distance to nearest blocked cell

    private TileField(Coord origin, int size, boolean[] nav, int[] dist)
    {
        this.origin = origin;
        this.size = size;
        this.nav = nav;
        this.dist = dist;
    }

    /**
     * Reads the loaded terrain around pos and builds the field. Never calls
     * the throwing MCache.getgrid()/gettile() path - see
     * FrontierPicker.safeTileName's doc for why that path is wrong here.
     */
    public static TileField scan(Coord2d pos, boolean deeperMode)
    {
        MCache map = NUtils.getGameUI().ui.sess.glob.map;
        Coord center = pos.div(MCache.tilesz).floor();
        Coord origin = center.sub(RADIUS, RADIUS);
        int size = RADIUS * 2 + 1;

        boolean[] nav = new boolean[size * size];
        int[] dist = new int[size * size];

        // Grid lookups are cached across the row-major walk because the scan
        // crosses only a handful of distinct grids, and MCache.grids is a
        // hash lookup per call otherwise.
        Coord lastGc = null;
        MCache.Grid lastGrid = null;
        for (int y = 0; y < size; y++)
        {
            for (int x = 0; x < size; x++)
            {
                Coord tc = origin.add(x, y);
                Coord gc = tc.div(MCache.cmaps);
                if (!gc.equals(lastGc))
                {
                    lastGc = gc;
                    lastGrid = map.grids.get(gc);
                }
                boolean loaded = lastGrid != null;
                boolean navigable = false;
                if (loaded)
                {
                    String name = map.tilesetname(lastGrid.gettile(tc.sub(lastGrid.ul)));
                    navigable = WaterTiles.isSafe(name, deeperMode);
                }
                int i = y * size + x;
                // Unloaded ground is impassable (fail-closed) but deliberately
                // does NOT seed the distance field. Seeding from it would make
                // the edge of what we happen to have loaded look like a
                // shoreline, and the follower would then trace that instead of
                // real coast - a coastline made of nothing but ignorance.
                nav[i] = navigable;
                dist[i] = (loaded && !navigable) ? 0 : UNREACHED;
            }
        }

        chamfer(dist, size);
        return new TileField(origin, size, nav, dist);
    }

    /** Standard two-pass chamfer distance transform. */
    private static void chamfer(int[] d, int size)
    {
        for (int y = 0; y < size; y++)
        {
            for (int x = 0; x < size; x++)
            {
                int i = y * size + x;
                if (d[i] == 0)
                    continue;
                int best = d[i];
                if (y > 0)
                {
                    best = Math.min(best, d[i - size] + ORTHO);
                    if (x > 0) best = Math.min(best, d[i - size - 1] + DIAG);
                    if (x < size - 1) best = Math.min(best, d[i - size + 1] + DIAG);
                }
                if (x > 0) best = Math.min(best, d[i - 1] + ORTHO);
                d[i] = best;
            }
        }
        for (int y = size - 1; y >= 0; y--)
        {
            for (int x = size - 1; x >= 0; x--)
            {
                int i = y * size + x;
                if (d[i] == 0)
                    continue;
                int best = d[i];
                if (y < size - 1)
                {
                    best = Math.min(best, d[i + size] + ORTHO);
                    if (x > 0) best = Math.min(best, d[i + size - 1] + DIAG);
                    if (x < size - 1) best = Math.min(best, d[i + size + 1] + DIAG);
                }
                if (x < size - 1) best = Math.min(best, d[i + 1] + ORTHO);
                d[i] = best;
            }
        }
    }

    private int idx(int lx, int ly)
    {
        return ly * size + lx;
    }

    private boolean inWindow(int lx, int ly)
    {
        return lx >= 0 && ly >= 0 && lx < size && ly < size;
    }

    /** Distance to nearest blocked tile, in tiles. 0 on blocked/unknown ground. */
    public double distanceAt(Coord2d world)
    {
        Coord tc = world.div(MCache.tilesz).floor().sub(origin);
        if (!inWindow(tc.x, tc.y))
            return 0;
        return dist[idx(tc.x, tc.y)] / (double) ORTHO;
    }

    public boolean navigableAt(Coord2d world)
    {
        Coord tc = world.div(MCache.tilesz).floor().sub(origin);
        if (!inWindow(tc.x, tc.y))
            return false;
        return nav[idx(tc.x, tc.y)];
    }

    /**
     * True when world is far enough inside the window for the distance field
     * there to reflect real terrain rather than the edge of loaded ground.
     */
    public boolean trusted(Coord2d world)
    {
        Coord tc = world.div(MCache.tilesz).floor().sub(origin);
        return tc.x >= EDGE_MARGIN && tc.y >= EDGE_MARGIN
                && tc.x < size - EDGE_MARGIN && tc.y < size - EDGE_MARGIN;
    }

    /**
     * Unit vector pointing directly away from the nearest blocked tile
     * (central difference of the distance field). Returns null where the
     * field is flat - i.e. no land anywhere nearby, so "away from land" is
     * meaningless and the caller must fall back to open-water behaviour.
     */
    public Coord2d awayFromBlocked(Coord2d world)
    {
        Coord tc = world.div(MCache.tilesz).floor().sub(origin);
        if (!inWindow(tc.x - 1, tc.y - 1) || !inWindow(tc.x + 1, tc.y + 1))
            return null;
        double gx = dist[idx(tc.x + 1, tc.y)] - dist[idx(tc.x - 1, tc.y)];
        double gy = dist[idx(tc.x, tc.y + 1)] - dist[idx(tc.x, tc.y - 1)];
        if (gx == 0 && gy == 0)
            return null;
        return Coord2d.of(gx, gy).norm();
    }

    /**
     * Largest achievable distance-to-land within a few tiles of world. The
     * follower clamps its target band to this so a narrow river (where no
     * tile is far enough from land to sit on the requested band) is ridden
     * down the middle instead of failing to find the contour at all.
     */
    public double localMaxDistance(Coord2d world, int radiusTiles)
    {
        Coord tc = world.div(MCache.tilesz).floor().sub(origin);
        int best = 0;
        for (int dy = -radiusTiles; dy <= radiusTiles; dy++)
        {
            for (int dx = -radiusTiles; dx <= radiusTiles; dx++)
            {
                int lx = tc.x + dx, ly = tc.y + dy;
                if (!inWindow(lx, ly))
                    continue;
                int v = dist[idx(lx, ly)];
                if (v < UNREACHED && v > best)
                    best = v;
            }
        }
        return best / (double) ORTHO;
    }

    /**
     * True when every tile on the straight segment a..b is navigable. This
     * is what makes a long single click safe: without it a distant waypoint
     * can be perfectly valid in itself yet have land between here and there,
     * which is how earlier versions of this bot walked into a shore and
     * stalled.
     */
    public boolean lineClear(Coord2d a, Coord2d b)
    {
        double total = a.dist(b);
        if (total <= 0)
            return navigableAt(a);
        Coord2d step = b.sub(a).div(total).mul(MCache.tilesz.x / 2.0);
        int steps = (int) Math.ceil(total / (MCache.tilesz.x / 2.0));
        Coord2d p = a;
        for (int i = 0; i <= steps; i++)
        {
            if (!navigableAt(p))
                return false;
            p = p.add(step);
        }
        return navigableAt(b);
    }
}
