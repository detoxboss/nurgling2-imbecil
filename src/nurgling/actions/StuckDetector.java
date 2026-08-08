package nurgling.actions;

import haven.Coord;
import haven.Coord2d;
import haven.MCache;
import haven.Utils;
import nurgling.tools.NDebugLog;

/**
 * Generic "made no net progress for N seconds" detector, shared by any
 * live-exploration bot (water or land) rather than duplicated per bot.
 * Detection only - recovery (e.g. back-up-and-swing) is caller-specific.
 *
 * Precedent: WaypointMovementService.java's existing >2.0s no-progress stuck
 * check (tile-coord equality + Utils.rtime()), generalized out of that class.
 */
public class StuckDetector
{
    public double timeoutS = 2.0;

    private Coord lastTile = null;
    private double lastProgressTime = 0;

    public void reset()
    {
        lastTile = null;
        lastProgressTime = Utils.rtime();
    }

    /**
     * Call once per tick/iteration with the current tracked position (e.g.
     * player.rc). Returns true once timeoutS seconds have passed with the
     * position stuck on the same tile.
     */
    public boolean check(Coord2d currentPos)
    {
        Coord tile = currentPos.div(MCache.tilesz).floor();
        double now = Utils.rtime();
        if (lastTile == null || !lastTile.equals(tile.x, tile.y))
        {
            lastTile = tile;
            lastProgressTime = now;
            return false;
        }
        boolean stuck = (now - lastProgressTime) > timeoutS;
        // File-only: the caller (e.g. WorldExplorer) already posts its own
        // sparser "stuck (attempt N)" chat message right after this fires.
        if (stuck)
            NDebugLog.log("StuckDetector: no progress for " + String.format("%.1f", now - lastProgressTime) + "s at tile " + tile);
        return stuck;
    }
}
