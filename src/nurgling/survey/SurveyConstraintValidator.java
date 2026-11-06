package nurgling.survey;

import haven.Area;
import haven.Coord;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, render-independent slope validator for the Land Survey editor. Models the
 * game's terraforming limit (approximately 2.0 m between connected terrain vertices, per
 * https://ringofbrodgar.com/wiki/Terraforming and a manual soil-placement test -- see the
 * Phase 2 investigation report for exactly what was and wasn't verified) as a per-edge check
 * over a candidate target-height shape.
 *
 * Works entirely in the survey's own integer dz units (see haven.res.ui.surv.Data) plus the
 * float {@code gran} needed to convert those to metres; never touches MCache, NConfig, or the
 * renderer, so it can be exercised with synthetic grids in a unit test.
 */
public final class SurveyConstraintValidator {
    public enum Mode { OFF, WARN, CONSTRAIN }

    /**
     * Whether diagonal (corner-to-corner) vertex pairs are checked with the same limit as
     * adjacent edges. <b>Unverified against the server</b> -- the wiki's "connected corners"
     * wording and the manual soil-placement test are both consistent with edge-only (the
     * default, {@code false}), but neither rules out diagonals being checked too. The in-game
     * test that would resolve this: raise one corner of a 1x1 survey tile until edge-adjacent
     * corners hit the dig/fill limit indicator, then check whether the untouched diagonal corner
     * also shows a limit indicator at a different (larger, since it's ~1.41x the distance)
     * threshold. Left as an internal switch rather than a user-facing setting, since flipping it
     * on an unverified basis would let the validator block or wave through shapes the server's
     * own rule disagrees with either way.
     */
    public static boolean CHECK_DIAGONALS = false;

    private SurveyConstraintValidator() {}

    public static final class Edge {
        public final Coord a, b;
        public Edge(Coord a, Coord b) { this.a = a; this.b = b; }
    }

    private static final Coord[] NEIGHBOR_OFFSETS = {
        Coord.of(1, 0), Coord.of(-1, 0), Coord.of(0, 1), Coord.of(0, -1),
    };

    /** Every adjacent-vertex edge within {@code area} (both axes), plus diagonals when
     *  {@link #CHECK_DIAGONALS} is set. Shared by the validator and the drag solver so both
     *  agree on exactly which pairs are constrained. */
    public static List<Edge> edgesWithin(Area area) {
        List<Edge> edges = new ArrayList<>();
        for(int y = area.ul.y; y < area.br.y; y++) {
            for(int x = area.ul.x; x < area.br.x; x++) {
                Coord c = Coord.of(x, y);
                if(x + 1 < area.br.x)
                    edges.add(new Edge(c, Coord.of(x + 1, y)));
                if(y + 1 < area.br.y)
                    edges.add(new Edge(c, Coord.of(x, y + 1)));
                if(CHECK_DIAGONALS) {
                    if((x + 1 < area.br.x) && (y + 1 < area.br.y)) {
                        edges.add(new Edge(c, Coord.of(x + 1, y + 1)));
                        edges.add(new Edge(Coord.of(x + 1, y), Coord.of(x, y + 1)));
                    }
                }
            }
        }
        return edges;
    }

    /**
     * Validates a full candidate target shape ({@code candidateDz}, same index layout as
     * haven.res.ui.surv.Data#dz over {@code varea}) against every interior edge and every edge
     * crossing the survey boundary into undisturbed ground just outside it.
     *
     * {@code priorDz} is the shape committed before this candidate (not necessarily "legal" --
     * may itself contain a pre-existing over-limit cliff) and is used only to distinguish a
     * worsened violation from one that was already there; it is never used as a safe fallback
     * height. Missing outside terrain ({@code terrain.terrainHeight(oc) == null}) always yields
     * {@link SurveyConstraintResult.Status#UNKNOWN} for that edge, never VALID.
     */
    public static SurveyConstraintResult validate(Area varea, float gran, int[] priorDz, int[] candidateDz,
                                                    SurveyTerrainSnapshot terrain, double maxSlopeMeters) {
        return validate(varea, gran, priorDz, candidateDz, terrain, maxSlopeMeters, false);
    }

    /**
     * Informational variant for when there is no "candidate drag" -- just the survey's current
     * committed shape, checked for any edge over the limit at all (an unresolved pre-existing
     * cliff included), rather than only newly-worsened ones. Used to drive the idle status
     * display; never used to decide whether an edit itself should be blocked.
     */
    public static SurveyConstraintResult validateAbsolute(Area varea, float gran, int[] dz,
                                                             SurveyTerrainSnapshot terrain, double maxSlopeMeters) {
        return validate(varea, gran, dz, dz, terrain, maxSlopeMeters, true);
    }

    private static SurveyConstraintResult validate(Area varea, float gran, int[] priorDz, int[] candidateDz,
                                                     SurveyTerrainSnapshot terrain, double maxSlopeMeters, boolean strict) {
        List<SurveyConstraintResult.EdgeFinding> findings = new ArrayList<>();
        SurveyConstraintResult.Status overall = SurveyConstraintResult.Status.VALID;

        for(Edge e : edgesWithin(varea)) {
            int ia = varea.ridx(e.a), ib = varea.ridx(e.b);
            double candDiff = Math.abs(candidateDz[ia] - candidateDz[ib]) / (double)gran;
            double priorDiff = Math.abs(priorDz[ia] - priorDz[ib]) / (double)gran;
            SurveyConstraintResult.Status s = classify(candDiff, strict ? 0 : priorDiff, maxSlopeMeters);
            if(s != SurveyConstraintResult.Status.VALID) {
                findings.add(new SurveyConstraintResult.EdgeFinding(e.a, e.b, s, candDiff, maxSlopeMeters, !strict && (priorDiff > maxSlopeMeters)));
                overall = worse(overall, s);
            }
        }

        for(Coord vc : varea) {
            boolean onBoundary = (vc.x == varea.ul.x) || (vc.x == varea.br.x - 1) || (vc.y == varea.ul.y) || (vc.y == varea.br.y - 1);
            if(!onBoundary)
                continue;
            int iv = varea.ridx(vc);
            for(Coord off : NEIGHBOR_OFFSETS) {
                Coord oc = vc.add(off);
                if(varea.contains(oc))
                    continue;
                Double oh = terrain.terrainHeight(oc);
                if(oh == null) {
                    findings.add(new SurveyConstraintResult.EdgeFinding(vc, oc, SurveyConstraintResult.Status.UNKNOWN,
                                                                         Double.NaN, maxSlopeMeters, false));
                    overall = worse(overall, SurveyConstraintResult.Status.UNKNOWN);
                    continue;
                }
                double candDiff = Math.abs((candidateDz[iv] / (double)gran) - oh);
                double priorDiff = Math.abs((priorDz[iv] / (double)gran) - oh);
                SurveyConstraintResult.Status s = classify(candDiff, strict ? 0 : priorDiff, maxSlopeMeters);
                if(s != SurveyConstraintResult.Status.VALID) {
                    findings.add(new SurveyConstraintResult.EdgeFinding(vc, oc, s, candDiff, maxSlopeMeters, !strict && (priorDiff > maxSlopeMeters)));
                    overall = worse(overall, s);
                }
            }
        }

        return new SurveyConstraintResult(overall, findings);
    }

    /** Violation iff the candidate exceeds both the hard limit and whatever this edge's
     *  difference already was -- i.e. it exceeds {@code max(limit, priorDiff)}. That single
     *  comparison is equivalent to "exceeds the limit AND is worse than before": if there was no
     *  pre-existing violation (priorDiff &lt;= limit), it reduces to "exceeds the limit"; if
     *  there was one, it reduces to "worse than the existing violation". */
    static SurveyConstraintResult.Status classify(double candDiff, double priorDiff, double limit) {
        double threshold = Math.max(limit, priorDiff);
        return (candDiff > threshold + 1e-9) ? SurveyConstraintResult.Status.VIOLATION : SurveyConstraintResult.Status.VALID;
    }

    private static SurveyConstraintResult.Status worse(SurveyConstraintResult.Status a, SurveyConstraintResult.Status b) {
        if((a == SurveyConstraintResult.Status.VIOLATION) || (b == SurveyConstraintResult.Status.VIOLATION))
            return SurveyConstraintResult.Status.VIOLATION;
        if((a == SurveyConstraintResult.Status.UNKNOWN) || (b == SurveyConstraintResult.Status.UNKNOWN))
            return SurveyConstraintResult.Status.UNKNOWN;
        return SurveyConstraintResult.Status.VALID;
    }
}
