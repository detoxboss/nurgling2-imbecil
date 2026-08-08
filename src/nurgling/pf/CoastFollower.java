package nurgling.pf;

import haven.Coord2d;
import haven.MCache;

/**
 * Traces a coastline by following an iso-contour of {@link TileField}'s
 * distance-to-land field, at a caller-chosen offset from shore.
 *
 * The steering law per step is:
 *
 *   away    = unit vector away from nearest land   (gradient of the field)
 *   along   = away rotated 90 degrees * chirality  (tangent to the contour)
 *   correct = away * (targetBand - currentDistance)
 *   step    = normalize(along + gain * correct)
 *
 * "along" carries it around the coast and "correct" pulls it back onto the
 * requested band, so the path both advances and self-corrects. Because
 * chirality is a constant chosen once, the tangent can never flip sign, and
 * the boat therefore cannot decide to reverse along a coast it has already
 * traced - the failure that dominated every earlier version of this bot.
 *
 * A dead-end inlet needs no special case: the contour simply runs in along
 * one wall, around the tip, and back out the other, which is what a human
 * would do. Only a fully enclosed body of water produces a closed loop, and
 * that is genuinely correct behaviour for one.
 */
public class CoastFollower
{
    /**
     * How hard the band-correction term competes with the tangent. The
     * error is clamped first, so this bounds how far off-tangent a single
     * step can steer (here up to roughly 45 degrees), keeping the traced
     * path smooth rather than letting it oscillate across the band.
     */
    private static final double CORRECTION_GAIN = 0.5;
    private static final double MAX_BAND_ERROR = 2.0;

    /**
     * How far outside the band counts as "lost the shore" and switches to
     * heading straight back to it. Contour-following is only meaningful near
     * the contour; far out in open water the distance field has a ridge
     * midway between opposing shores where the gradient is ill-defined and
     * flips between neighbouring cells, so the tangent there is noise.
     */
    private static final double LOST_MARGIN = 4.0;

    /**
     * Minimum ratio of straight-line distance to distance travelled along the
     * contour for a click target to be accepted.
     *
     * Without this, "farthest point with clear line of sight" silently
     * abandons the coast: around a bay the contour curves inland while the
     * chord across the bay is open water, so the check passes and the boat
     * cuts straight across the middle instead of following the shore. That is
     * exactly what happened in testing - the trace ran neatly down one shore,
     * then struck out diagonally across open water and lost the coast
     * entirely. Requiring the chord to stay close to the arc keeps runs long
     * on straight coast (where the two are equal) and automatically shortens
     * them through bends, which is also the correct speed behaviour.
     */
    private static final double MIN_CHORD_RATIO = 0.9;

    public static class Plan
    {
        /** Point to click - always straight-line navigable from the origin. */
        public final Coord2d target;
        /** Committed heading (unit) to carry into the next iteration. */
        public final Coord2d heading;
        /** Tiles of contour actually traced, for diagnostics. */
        public final int contourTiles;
        /** Distance to land at the origin, in tiles, for diagnostics. */
        public final double shoreDistance;

        Plan(Coord2d target, Coord2d heading, int contourTiles, double shoreDistance)
        {
            this.target = target;
            this.heading = heading;
            this.contourTiles = contourTiles;
            this.shoreDistance = shoreDistance;
        }
    }

    /**
     * Picks the initial heading so the configured chirality is honoured from
     * the very first move: tangent to the local contour, in the requested
     * rotational sense. Falls back to null when there is no land in range to
     * be tangent to.
     */
    public static Coord2d initialHeading(TileField field, Coord2d pos, int chirality)
    {
        Coord2d away = field.awayFromBlocked(pos);
        if (away == null)
            return null;
        return away.rot(chirality * Math.PI / 2);
    }

    /**
     * Walks the contour ahead and returns the farthest point along it that
     * is reachable in a straight line, so one click covers many tiles and
     * the boat keeps its momentum instead of stopping every tile.
     *
     * Returns null when no usable step exists (no navigable contour ahead),
     * which the caller should treat as "need recovery", not as an error.
     */
    public static Plan plan(TileField field, Coord2d pos, Coord2d heading,
                            int chirality, double bandTiles, int maxSteps)
    {
        double tile = MCache.tilesz.x;
        double shoreDist = field.distanceAt(pos);

        // A narrow channel may have no water far enough from land to sit on
        // the requested band at all; ride its centreline instead of failing.
        double band = Math.min(bandTiles, field.localMaxDistance(pos, (int) Math.ceil(bandTiles) + 2));

        Coord2d away = field.awayFromBlocked(pos);
        if (away != null && shoreDist > band + LOST_MARGIN)
        {
            // Too far out to trust the tangent. Head straight back to the
            // shore; contour-following resumes on its own once inside the
            // band. Any shore will do - regaining one is what matters.
            return runToBand(field, pos, away.mul(-1), maxSteps, band, shoreDist);
        }
        if (away == null)
        {
            // No land anywhere in range: nothing to hug. Hold course and let
            // terrain stream in - reaching new coast from open water is a
            // matter of continuing, not of steering.
            return runStraight(field, pos, heading, maxSteps, shoreDist);
        }

        Coord2d[] path = new Coord2d[maxSteps];
        int n = 0;
        Coord2d p = pos;
        Coord2d prevDir = heading;

        for (int step = 0; step < maxSteps; step++)
        {
            Coord2d g = field.awayFromBlocked(p);
            if (g == null)
                break;
            Coord2d along = g.rot(chirality * Math.PI / 2);
            double err = field.distanceAt(p) - band;
            if (err > MAX_BAND_ERROR) err = MAX_BAND_ERROR;
            if (err < -MAX_BAND_ERROR) err = -MAX_BAND_ERROR;
            // err > 0 means too far from land, so steer back toward it.
            Coord2d dir = along.sub(g.mul(err * CORRECTION_GAIN));
            if (dir.abs() < 1e-9)
                break;
            dir = dir.norm();
            // Guard against a gradient discontinuity (e.g. stepping over a
            // ridge between two shores) throwing the path backwards. Only
            // applied once under way: tripping on the very first step used to
            // yield an empty path and abort the bot outright.
            if (step > 0 && dot(dir, prevDir) < 0)
                break;

            Coord2d next = p.add(dir.mul(tile));
            if (!field.navigableAt(next) || !field.trusted(next))
                break;
            path[n++] = next;
            p = next;
            prevDir = dir;
        }

        // Farthest contour point that is both reachable in a straight line and
        // still faithful to the traced arc (see MIN_CHORD_RATIO).
        for (int i = n - 1; i >= 0; i--)
        {
            double chord = pos.dist(path[i]);
            double arc = (i + 1) * tile;
            if (chord < arc * MIN_CHORD_RATIO)
                continue;
            if (!field.lineClear(pos, path[i]))
                continue;
            return new Plan(path[i], path[i].sub(pos).norm(), n, shoreDist);
        }
        return null;
    }

    /** Longest clear straight run from pos along dir, or null if even one tile is blocked. */
    private static Plan runStraight(TileField field, Coord2d pos, Coord2d dir, int maxSteps, double shoreDist)
    {
        double tile = MCache.tilesz.x;
        Coord2d unit = dir.norm();
        Coord2d best = null;
        int taken = 0;
        for (int step = 1; step <= maxSteps; step++)
        {
            Coord2d cand = pos.add(unit.mul(step * tile));
            if (!field.navigableAt(cand) || !field.trusted(cand))
                break;
            best = cand;
            taken = step;
        }
        if (best == null)
            return null;
        return new Plan(best, unit, taken, shoreDist);
    }

    /**
     * Like {@link #runStraight}, but also stops as soon as the requested band
     * is reached rather than running the full distance toward land. Without
     * this, closing a large gap (e.g. the bot starting far out from shore)
     * would aim straight at the coast and could run the boat aground the
     * moment it crossed into the band instead of handing off to normal
     * contour-following there.
     */
    private static Plan runToBand(TileField field, Coord2d pos, Coord2d dir, int maxSteps, double band, double shoreDist)
    {
        double tile = MCache.tilesz.x;
        Coord2d unit = dir.norm();
        Coord2d best = null;
        int taken = 0;
        for (int step = 1; step <= maxSteps; step++)
        {
            Coord2d cand = pos.add(unit.mul(step * tile));
            if (!field.navigableAt(cand) || !field.trusted(cand))
                break;
            best = cand;
            taken = step;
            if (field.distanceAt(cand) <= band)
                break;
        }
        if (best == null)
            return null;
        return new Plan(best, unit, taken, shoreDist);
    }

    private static double dot(Coord2d a, Coord2d b)
    {
        return (a.x * b.x) + (a.y * b.y);
    }
}
