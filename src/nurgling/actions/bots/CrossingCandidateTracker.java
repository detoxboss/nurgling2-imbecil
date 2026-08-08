package nurgling.actions.bots;

import haven.Coord2d;
import haven.MCache;
import nurgling.pf.FrontierPicker;
import nurgling.pf.WaterTiles;
import nurgling.tools.NDebugLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Tracks candidate inter-continent crossing points while WorldExplorer traces
 * a coastline: perpendicular ray-scans outward from the hug direction, into
 * deep OCEAN water only (never freshwater - a river is not a continent
 * crossing, per the corrected WaterTiles taxonomy), looking for a far shore
 * within sensor range. Maintains a small bounded nearest-first list rather
 * than a spatial index - candidate count stays tiny at this scale.
 */
public class CrossingCandidateTracker
{
    public static class Candidate
    {
        public final Coord2d nearPoint;
        public final Coord2d farPoint;
        public final double gapDistance;

        Candidate(Coord2d nearPoint, Coord2d farPoint, double gapDistance)
        {
            this.nearPoint = nearPoint;
            this.farPoint = farPoint;
            this.gapDistance = gapDistance;
        }

        @Override
        public String toString()
        {
            return String.format("gap=%.0f near=%s far=%s", gapDistance, nearPoint, farPoint);
        }
    }

    private static final int MAX_CANDIDATES = 20;
    private static final double DEDUP_RADIUS = MCache.tilesz.x * 5;
    private static final double STEP = MCache.tilesz.x;

    private final List<Candidate> candidates = new ArrayList<>();

    /**
     * Scans outward from nearPoint along outwardHeading, up to sensorRange,
     * looking for a far shore across open ocean. No-ops (no candidate) if the
     * scan runs into unresolved terrain first, never leaves ocean water, or
     * exceeds sensorRange without finding land.
     */
    public void scanForCrossing(Coord2d nearPoint, Coord2d outwardHeading, double sensorRange)
    {
        Coord2d dir = outwardHeading.norm();
        double travelled = 0;
        boolean crossedOpenSea = false;
        while (travelled < sensorRange)
        {
            travelled += STEP;
            Coord2d sample = nearPoint.add(dir.mul(travelled));
            String name = FrontierPicker.safeTileName(sample);
            if (name == null)
                return; // unresolved terrain before finding a far shore - nothing to record yet
            if (WaterTiles.isOcean(name))
            {
                if (WaterTiles.isDeep(name) || WaterTiles.isDeeper(name))
                    crossedOpenSea = true; // confirms genuine open sea, not just a cove
                continue;
            }
            // Reached a non-ocean tile: a genuine far shore, but only counts
            // once real open water was actually crossed first (rules out a
            // single-tile inlet reading as a "crossing").
            if (crossedOpenSea)
                addCandidate(nearPoint, sample, travelled);
            return;
        }
    }

    private void addCandidate(Coord2d nearPoint, Coord2d farPoint, double gapDistance)
    {
        for (Candidate c : candidates)
        {
            if (c.nearPoint.dist(nearPoint) < DEDUP_RADIUS)
                return; // near-identical candidate already tracked
        }
        candidates.add(new Candidate(nearPoint, farPoint, gapDistance));
        candidates.sort(Comparator.comparingDouble(c -> c.gapDistance));
        while (candidates.size() > MAX_CANDIDATES)
            candidates.remove(candidates.size() - 1);
        NDebugLog.logAndChat("CrossingCandidateTracker: new candidate (" + candidates.size()
                + " tracked), best so far: " + candidates.get(0));
    }

    public List<Candidate> getCandidates()
    {
        return Collections.unmodifiableList(candidates);
    }
}
