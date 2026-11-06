package nurgling.survey;

import haven.Area;
import haven.Coord;

import java.util.HashMap;
import java.util.Map;

/**
 * Finds the largest in-bounds drag amount for a single gesture (LandSurvey.Mover or
 * LandSurvey.CMover) that doesn't newly violate (or worsen) {@link SurveyConstraintValidator}'s
 * slope rule, instead of clamping every vertex independently -- which would distort a
 * selected-region translation/tilt into a different shape instead of stopping it in place.
 *
 * Both gestures apply a single scalar {@code diff} (the mouse-driven amount) to their affected
 * vertices through a fixed per-vertex coefficient (1 for a plain Mover; a bilinear corner weight
 * for CMover): {@code wz(v) = startWz(v) + diff * coeff(v)}. That makes every edge's height
 * difference an affine (constant-slope) function of {@code diff} alone, so each edge's own
 * clamping threshold is solved for directly -- no search, and no assumption that the *overall*
 * clamp is monotonic across edges is needed, since the final answer is just the tightest
 * (smallest-magnitude) of each edge's independently exact threshold.
 */
public final class SurveyDragConstraintSolver {
    private SurveyDragConstraintSolver() {}

    private static final Coord[] NEIGHBOR_OFFSETS = {
        Coord.of(1, 0), Coord.of(-1, 0), Coord.of(0, 1), Coord.of(0, -1),
    };

    public static final class ClampResult {
        /** Same sign as the requested diff (or 0); magnitude <= |requested diff|. */
        public final float diff;
        /** Null if the requested diff was not clamped. */
        public final SurveyConstraintValidator.Edge limitingEdge;
        /** True if unloaded outside terrain was found near the gesture -- never itself a reason
         *  to clamp, but worth surfacing as a warning (an unverifiable restriction). */
        public final boolean sawUnknownTerrain;

        ClampResult(float diff, SurveyConstraintValidator.Edge limitingEdge, boolean sawUnknownTerrain) {
            this.diff = diff;
            this.limitingEdge = limitingEdge;
            this.sawUnknownTerrain = sawUnknownTerrain;
        }
    }

    /**
     * @param varea            the survey's full vertex area (Data#varea)
     * @param gran             Data#gran
     * @param priorDzFull      the survey's committed dz shape as of gesture start (over varea)
     * @param regionGidx       for each region-local index, the corresponding global varea index
     *                         (LandSurvey.Mover/CMover#gidx)
     * @param regionStartWz    each region vertex's wz at gesture start (LandSurvey.Mover/CMover#sz)
     * @param regionCoeff      each region vertex's coefficient of {@code diff} (1 for Mover; the
     *                         bilinear corner weight for CMover)
     * @param requestedDiff    the raw, unclamped diff the mouse position currently asks for
     */
    public static ClampResult clamp(Area varea, float gran, int[] priorDzFull, int[] regionGidx,
                                      float[] regionStartWz, float[] regionCoeff, float requestedDiff,
                                      SurveyTerrainSnapshot terrain, double maxSlopeMeters) {
        if(requestedDiff == 0)
            return new ClampResult(0, null, false);
        float sign = Math.signum(requestedDiff);
        float best = requestedDiff;
        SurveyConstraintValidator.Edge limiting = null;
        boolean sawUnknown = false;

        double mpd = SurveyConstraintValidator.metersPerDzUnit(gran);
        Map<Integer, Integer> globalToRegion = new HashMap<>();
        for(int i = 0; i < regionGidx.length; i++)
            globalToRegion.put(regionGidx[i], i);

        for(SurveyConstraintValidator.Edge e : SurveyConstraintValidator.edgesWithin(varea)) {
            int ia = varea.ridx(e.a), ib = varea.ridx(e.b);
            Integer ra = globalToRegion.get(ia), rb = globalToRegion.get(ib);
            if((ra == null) && (rb == null))
                continue;
            double baseA = (ra != null) ? regionStartWz[ra] : priorDzFull[ia];
            double baseB = (rb != null) ? regionStartWz[rb] : priorDzFull[ib];
            double coA = (ra != null) ? regionCoeff[ra] : 0;
            double coB = (rb != null) ? regionCoeff[rb] : 0;
            double C = (baseA - baseB) * mpd;
            double D = (coA - coB) * mpd;
            double priorDiff = Math.abs(priorDzFull[ia] - priorDzFull[ib]) * mpd;

            Float boundary = edgeBoundaryT(C, D, sign, Math.max(maxSlopeMeters, priorDiff));
            if((boundary != null) && tighter(boundary, best, sign)) {
                best = boundary;
                limiting = e;
            }
        }

        for(Coord vc : varea) {
            int iv = varea.ridx(vc);
            Integer rv = globalToRegion.get(iv);
            if(rv == null)
                continue;
            for(Coord off : NEIGHBOR_OFFSETS) {
                Coord oc = vc.add(off);
                if(varea.contains(oc))
                    continue;
                Double oh = terrain.terrainHeight(oc);
                if(oh == null) {
                    sawUnknown = true;
                    continue;
                }
                double C = (regionStartWz[rv] * mpd) - oh;
                double D = regionCoeff[rv] * mpd;
                double priorDiff = Math.abs((priorDzFull[iv] * mpd) - oh);

                Float boundary = edgeBoundaryT(C, D, sign, Math.max(maxSlopeMeters, priorDiff));
                if((boundary != null) && tighter(boundary, best, sign)) {
                    best = boundary;
                    limiting = new SurveyConstraintValidator.Edge(vc, oc);
                }
            }
        }

        // Conservative integer alignment: the final committed shape rounds wz to dz, so round
        // the clamp towards zero (never outward) to guarantee the rounded result still complies.
        float rounded = (sign > 0) ? (float)Math.floor(best) : (float)Math.ceil(best);
        if(Math.abs(rounded) > Math.abs(requestedDiff))
            rounded = requestedDiff;
        return new ClampResult(rounded, limiting, sawUnknown);
    }

    private static boolean tighter(float candidate, float currentBest, float sign) {
        return (sign > 0) ? (candidate < currentBest) : (candidate > currentBest);
    }

    /**
     * d(t) = C + t*D is one edge's signed height difference (metres) as the gesture's scalar
     * parameter moves away from 0. Since d(0) always equals the edge's signed pre-gesture
     * difference, {@code |d(0)| <= threshold} holds by construction (threshold is at least
     * {@code priorDiff}), so the set of t where {@code |d(t)| <= threshold} is a single interval
     * containing 0; this returns the edge of that interval on the requested side, i.e. the
     * largest-magnitude t (same sign as {@code sign}) still inside it. Null if D == 0 (this edge
     * doesn't move with this gesture at all).
     */
    private static Float edgeBoundaryT(double C, double D, float sign, double threshold) {
        if(D == 0)
            return null;
        double r1 = (threshold - C) / D;
        double r2 = (-threshold - C) / D;
        double boundary = (sign > 0) ? Math.max(r1, r2) : Math.min(r1, r2);
        return (float)boundary;
    }
}
