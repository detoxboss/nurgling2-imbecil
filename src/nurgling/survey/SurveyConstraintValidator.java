package nurgling.survey;

import haven.Area;
import haven.Coord;
import haven.MCache;

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

    /**
     * dz units to metres. Confirmed regression (see Phase 2 follow-up investigation): {@code
     * haven.res.ui.surv.Data#dz} is {@code round(MCache.getfz() * gran)} (LandSurvey#initsurf),
     * i.e. dz is in "world-Z units * gran", NOT metres -- dividing a dz difference by {@code
     * gran} alone (the original bug here) recovers the difference in world-Z units, which are
     * themselves {@code MCache.tilesz.x} times larger than a real metre: MCache's z buffer is
     * stored pre-multiplied by {@code tilesz.x} to align its scale with the x/y grid (see the
     * {@code * 11} factor in MCache.Grid's z-format decoding). Dividing world-Z back out by
     * {@code tilesz.x} recovers the original server-sent value in real metres.
     *
     * <p><b>Follow-up #4 correction:</b> an earlier version of this comment asserted live
     * {@code gran} was "always" {@code 10f/11f} and called that "verified empirically." That was
     * wrong, or at least not generally true: an in-game diagnostic session (see
     * docs/land-survey-editor.md, Follow-up #4) logged {@code mkwidget}'s raw {@code args[2]} as
     * {@code 11} -- i.e. {@code gran == 1}, not {@code 10/11} -- and the observed per-dz-unit
     * step in that session was {@code 1/11 m ≈ 0.0909 m}, not {@code 0.1 m}. {@code gran} is
     * sent by the server per survey ({@code LandSurvey#mkwidget}, {@code args[2] / 11}) and must
     * never be assumed constant across surveys or builds; this method already takes it as a
     * parameter for exactly that reason, so no arithmetic here was wrong -- only the stale prose
     * above (and, separately, a real hardcoded-constant bug it describes: see
     * {@code LandSurvey#updmap}'s "Peak to trough" readout, which hardcoded {@code / 10.0}
     * instead of calling this method, and has been fixed to do so). The 2.0 m limit itself still
     * traces only to the wiki citation in this class's doc comment -- the manual soil-placement
     * test that seemed to corroborate it was run under whatever {@code gran} was live at the
     * time, which was never logged, so it does not independently confirm 2.0 m under a
     * <i>different</i> gran. Treat 2.0 m as the best current guess, not as re-verified here.
     *
     * <p><b>Follow-up #6 correction:</b> the 2.0 m figure above is no longer the production
     * limit at all -- see {@link #RAW_TICK_LIMIT} and {@link #limitMetersFor}. This method
     * itself was never wrong and remains the sole dz-to-metres conversion; it's just no longer
     * multiplied by a fixed metres constant to get the enforced limit.
     */
    public static double metersPerDzUnit(float gran) {
        return 1.0 / (gran * MCache.tilesz.x);
    }

    /**
     * Follow-up #6: the authoritative, production-enforced terraforming limit, expressed in the
     * survey's own raw {@code dz} ticks rather than metres. This is the single shared policy
     * value for {@link #validate}, {@link #validateAbsolute}, {@link SurveyDragConstraintSolver},
     * the idle-status text, the vertex markers, and {@code SurveyVertexDiagnostics}'s
     * permitted-raise figures -- every one of those now derives its working limit via {@link
     * #limitMetersFor(float)}, never a fixed metres constant.
     *
     * <p><b>Height representation this policy is defined against:</b> {@code dz} is the survey's
     * own integer quantization unit (see {@code haven.res.ui.surv.Data#dz}); dividing it by
     * {@code gran} recovers world-Z, and dividing world-Z by {@code MCache.tilesz.x} recovers real
     * metres ({@link #metersPerDzUnit}). This constant is defined in raw {@code dz} ticks -- the
     * survey's own quantization -- not world-Z and not metres. At {@code gran=1} those three
     * scales happen to coincide numerically for dz itself (dz IS world-Z when gran=1), but that
     * coincidence is specific to {@code gran=1} and is not by itself evidence that the server's
     * real rule is dz-tick-native at every {@code gran} -- see the scope caveat below.
     *
     * <p>Evidence: an in-game test under {@code gran=1} (raw {@code mkwidget args[2]==11}) found
     * the real achievable edge difference against flat, untouched neighbor terrain was exactly
     * 20 raw dz-ticks (1.818 m under this gran's mpd) -- two ticks short of the 22 ticks / 2.0 m
     * the old fixed-metres model ({@code SurveySettings.TERRAIN_MAX_SLOPE_METERS}, now
     * superseded) predicted at that gran, which is a confirmed, reproduced-by-arithmetic
     * discrepancy, not a hypothesis. If the historical "2.0 m" wiki figure was itself measured
     * under the long-assumed {@code gran=10/11} convention, 20 ticks * {@code
     * metersPerDzUnit(10f/11f)} (0.1) also equals 2.0 m exactly -- the same raw-tick count
     * reconciles both data points under two different {@code gran} values, something a
     * metres-native limit re-divided by the current gran cannot do.
     *
     * <p><b>Scope of what is and isn't established.</b> Confirmed: at {@code gran=1}, 20 raw
     * dz-ticks is the real enforced boundary, and the old fixed-2.0 m-everywhere model is wrong
     * there. Adopted as policy, not independently confirmed: that the same raw-dz-tick count
     * (rather than, say, a fixed real-metres distance, or a fixed world-Z distance that would
     * only coincide with dz at gran=1) holds at every other {@code gran}. This constant is applied
     * universally anyway because it is the best current model -- it is strictly consistent with
     * every data point gathered so far, including the one that disproves the alternative -- not
     * because cross-gran invariance has itself been separately verified. A future data point at a
     * second, different {@code gran} that disagrees with {@link #limitMetersFor} is the thing that
     * would overturn this constant, not undermine the gran=1 fix below it.
     *
     * <p><b>What a refusal-message log can and cannot show.</b> Capturing the exact in-game
     * refusal at the 21st placement on a {@code gran=1} plateau (checklist item 15) would confirm
     * the boundary is 20 ticks there -- it would <i>not</i> distinguish "the real limit is a fixed
     * 20 dz-ticks, gran-invariant" from "the real limit is a fixed ~1.818 m, and the 2.0 m wiki
     * figure was always an imprecise rounding": both predict the exact same 20-tick boundary at
     * {@code gran=1}, since 20 ticks * {@code metersPerDzUnit(1)} IS 1.818 m. Only a second,
     * different live {@code gran} where the two predictions diverge (checklist item 16) can
     * actually settle that question; a gran=1 refusal log verifies the boundary, not gran
     * invariance.
     */
    public static final int RAW_TICK_LIMIT = 20;

    /**
     * The single shared conversion of {@link #RAW_TICK_LIMIT} into this survey's current-gran
     * metres -- the one function every production call site (validate/clamp/drag-solver/markers/
     * status text/diagnostics) now goes through to get its working limit, so they can never drift
     * out of sync with each other the way the old fixed {@code SurveySettings.TERRAIN_MAX_SLOPE_METERS}
     * constant risked once gran stopped being assumed constant (Follow-up #4) and then was found
     * to be the wrong unit basis entirely (Follow-up #5/#6).
     */
    public static double limitMetersFor(float gran) {
        return RAW_TICK_LIMIT * metersPerDzUnit(gran);
    }

    private SurveyConstraintValidator() {}

    public static final class Edge {
        public final Coord a, b;
        public Edge(Coord a, Coord b) { this.a = a; this.b = b; }
    }

    private static final Coord[] NEIGHBOR_OFFSETS = {
        Coord.of(1, 0), Coord.of(-1, 0), Coord.of(0, 1), Coord.of(0, -1),
    };

    private static final Coord[] DIAGONAL_OFFSETS = {
        Coord.of(1, 1), Coord.of(1, -1), Coord.of(-1, 1), Coord.of(-1, -1),
    };

    private static final Coord[] ALL_OFFSETS = {
        Coord.of(1, 0), Coord.of(-1, 0), Coord.of(0, 1), Coord.of(0, -1),
        Coord.of(1, 1), Coord.of(1, -1), Coord.of(-1, 1), Coord.of(-1, -1),
    };

    /** Every adjacent-vertex edge within {@code area} (both axes), plus diagonals when
     *  {@link #CHECK_DIAGONALS} is set. Shared by the validator and the drag solver so both
     *  agree on exactly which pairs are constrained. */
    public static List<Edge> edgesWithin(Area area) {
        return edgesWithin(area, CHECK_DIAGONALS);
    }

    /** Same as {@link #edgesWithin(Area)}, but with the diagonal switch passed explicitly
     *  instead of read from the static {@link #CHECK_DIAGONALS} flag -- used by
     *  {@link #compareModels} to evaluate Model B (all six corner pairs per tile) without
     *  touching the flag that governs production validate()/clamp() behavior. For a full tile
     *  (corners c, c+(1,0), c+(0,1), c+(1,1)) the diagonals-on case adds exactly the two
     *  corner-to-corner pairs the edges-only case misses; combined with the edges every vertex
     *  iteration already contributes, that's all six unique corner pairs per tile, i.e.
     *  equivalent to "max(four corners) - min(four corners)" per tile. */
    public static List<Edge> edgesWithin(Area area, boolean diagonals) {
        List<Edge> edges = new ArrayList<>();
        for(int y = area.ul.y; y < area.br.y; y++) {
            for(int x = area.ul.x; x < area.br.x; x++) {
                Coord c = Coord.of(x, y);
                if(x + 1 < area.br.x)
                    edges.add(new Edge(c, Coord.of(x + 1, y)));
                if(y + 1 < area.br.y)
                    edges.add(new Edge(c, Coord.of(x, y + 1)));
                if(diagonals) {
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

        double mpd = metersPerDzUnit(gran);
        // Half a dz unit's worth of metres: the band just under the limit that counts as
        // "AT_LIMIT" rather than "BELOW_LIMIT", so the exact 2.0 m boundary (and the single
        // increment just short of it, where quantization means nothing finer is reachable
        // anyway) both get flagged, without flickering on float noise right at the line.
        double atLimitBand = mpd / 2;
        for(Edge e : edgesWithin(varea)) {
            int ia = varea.ridx(e.a), ib = varea.ridx(e.b);
            double candDiff = Math.abs(candidateDz[ia] - candidateDz[ib]) * mpd;
            double priorDiff = Math.abs(priorDz[ia] - priorDz[ib]) * mpd;
            SurveyConstraintResult.Status s = classify(candDiff, strict ? 0 : priorDiff, maxSlopeMeters);
            SurveyConstraintResult.Severity sev = severity(candDiff, maxSlopeMeters, atLimitBand);
            if((s != SurveyConstraintResult.Status.VALID) || (sev != SurveyConstraintResult.Severity.BELOW_LIMIT)) {
                Boolean actualOver = actualOverLimit(terrain, e.a, e.b, maxSlopeMeters);
                findings.add(new SurveyConstraintResult.EdgeFinding(e.a, e.b, s, sev, candDiff, maxSlopeMeters,
                                                                     !strict && (priorDiff > maxSlopeMeters), actualOver));
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
                                                                         SurveyConstraintResult.Severity.UNKNOWN,
                                                                         Double.NaN, maxSlopeMeters, false, null));
                    overall = worse(overall, SurveyConstraintResult.Status.UNKNOWN);
                    continue;
                }
                double candDiff = Math.abs((candidateDz[iv] * mpd) - oh);
                double priorDiff = Math.abs((priorDz[iv] * mpd) - oh);
                SurveyConstraintResult.Status s = classify(candDiff, strict ? 0 : priorDiff, maxSlopeMeters);
                SurveyConstraintResult.Severity sev = severity(candDiff, maxSlopeMeters, atLimitBand);
                if((s != SurveyConstraintResult.Status.VALID) || (sev != SurveyConstraintResult.Severity.BELOW_LIMIT)) {
                    Double vch = terrain.terrainHeight(vc);
                    Boolean actualOver = (vch == null) ? null : (Math.abs(vch - oh) > maxSlopeMeters + 1e-9);
                    findings.add(new SurveyConstraintResult.EdgeFinding(vc, oc, s, sev, candDiff, maxSlopeMeters,
                                                                         !strict && (priorDiff > maxSlopeMeters), actualOver));
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

    /** Absolute severity against the real limit, ignoring worsening -- deliberately independent
     *  of {@link #classify}, so a pre-existing (and therefore non-blocking) over-limit edge still
     *  reports {@code OVER_LIMIT} rather than looking indistinguishable from a comfortably legal
     *  one. */
    static SurveyConstraintResult.Severity severity(double candDiff, double limit, double atLimitBand) {
        if(candDiff > limit + 1e-9)
            return SurveyConstraintResult.Severity.OVER_LIMIT;
        if(candDiff >= limit - atLimitBand - 1e-9)
            return SurveyConstraintResult.Severity.AT_LIMIT;
        return SurveyConstraintResult.Severity.BELOW_LIMIT;
    }

    /** Whether the real, unmodified terrain at both endpoints is itself already over the limit
     *  -- null (never a safe "false") if either endpoint's real height is unknown. */
    private static Boolean actualOverLimit(SurveyTerrainSnapshot terrain, Coord a, Coord b, double limit) {
        Double ha = terrain.terrainHeight(a), hb = terrain.terrainHeight(b);
        if((ha == null) || (hb == null))
            return null;
        return Math.abs(ha - hb) > limit + 1e-9;
    }

    private static SurveyConstraintResult.Status worse(SurveyConstraintResult.Status a, SurveyConstraintResult.Status b) {
        if((a == SurveyConstraintResult.Status.VIOLATION) || (b == SurveyConstraintResult.Status.VIOLATION))
            return SurveyConstraintResult.Status.VIOLATION;
        if((a == SurveyConstraintResult.Status.UNKNOWN) || (b == SurveyConstraintResult.Status.UNKNOWN))
            return SurveyConstraintResult.Status.UNKNOWN;
        return SurveyConstraintResult.Status.VALID;
    }

    /** Read-only result of {@link #compareModels}: how many edges (interior + boundary) are
     *  over the limit, and the worst single difference, under each model -- against the exact
     *  same snapshot and dz shape. Never derived from or fed back into validate()/clamp(). */
    public static final class ModelComparison {
        public final int modelAViolations, modelBViolations;
        public final double modelAWorstMeters, modelBWorstMeters;
        /** Model C (Follow-up #5/#6): same edge topology as Model A (cardinal-only --
         *  independent of the still-open diagonal question), but compared as raw dz-tick
         *  differences against {@link #RAW_TICK_LIMIT} directly, with no gran/mpd conversion
         *  between two in-survey endpoints. Now that Model A's own {@code maxSlopeMeters} input
         *  is itself derived from {@link #limitMetersFor} (production policy), Model A and Model
         *  C agree on every edge entirely inside the survey by construction, modulo float
         *  rounding -- Model C's value is kept as an integer-exact cross-check free of that
         *  rounding, not as a rival hypothesis anymore. A boundary edge against undisturbed
         *  outside terrain still converts that real-metres height into this survey's own
         *  current-gran tick-equivalent (outside terrain has no raw dz of its own), so this model
         *  is only fully gran-invariant for edges entirely inside the survey. */
        public final int modelCViolations, modelCWorstTicks;

        ModelComparison(int modelAViolations, int modelBViolations, double modelAWorstMeters, double modelBWorstMeters,
                        int modelCViolations, int modelCWorstTicks) {
            this.modelAViolations = modelAViolations; this.modelBViolations = modelBViolations;
            this.modelAWorstMeters = modelAWorstMeters; this.modelBWorstMeters = modelBWorstMeters;
            this.modelCViolations = modelCViolations; this.modelCWorstTicks = modelCWorstTicks;
        }
    }

    private static final class ModelAccum {
        int violations = 0;
        double worst = 0;
        void see(double diff, double limit) {
            worst = Math.max(worst, diff);
            if(diff > limit + 1e-9)
                violations++;
        }
    }

    private static final class ModelAccumTicks {
        int violations = 0;
        int worst = 0;
        void see(int diffTicks, int limitTicks) {
            worst = Math.max(worst, diffTicks);
            if(diffTicks > limitTicks)
                violations++;
        }
    }

    /** Raw dz-tick value at {@code c} for Model C: the survey's own dz entry if {@code c} is
     *  inside {@code varea}, otherwise the outside terrain's real height converted into this
     *  survey's current-gran tick-equivalent. Null only if outside terrain height is unknown. */
    static Integer rawTicksAt(Area varea, int[] dz, double mpd, SurveyTerrainSnapshot terrain, Coord c) {
        if(varea.contains(c))
            return dz[varea.ridx(c)];
        Double h = terrain.terrainHeight(c);
        return (h == null) ? null : (int)Math.round(h / mpd);
    }

    /**
     * Evaluates the current committed shape ({@code dz}) under both Model A (edge-only --
     * exactly what validate()/clamp() use today) and Model B (every corner pair of every tile,
     * i.e. including diagonals, and the same for boundary tiles against undisturbed outside
     * ground) over the whole survey, from the same {@code terrain} snapshot. Purpose: gather
     * in-game evidence on whether the server's real rule includes diagonal corner pairs, without
     * changing what CONSTRAIN mode actually blocks ({@link #CHECK_DIAGONALS} stays whatever it
     * already was -- this never reads or writes it).
     */
    public static ModelComparison compareModels(Area varea, float gran, int[] dz,
                                                   SurveyTerrainSnapshot terrain, double maxSlopeMeters) {
        double mpd = metersPerDzUnit(gran);
        ModelAccum a = new ModelAccum(), b = new ModelAccum();
        ModelAccumTicks c = new ModelAccumTicks();

        for(Edge e : edgesWithin(varea, false)) {
            int diffTicks = Math.abs(dz[varea.ridx(e.a)] - dz[varea.ridx(e.b)]);
            a.see(diffTicks * mpd, maxSlopeMeters);
            c.see(diffTicks, RAW_TICK_LIMIT);
        }
        for(Edge e : edgesWithin(varea, true)) {
            double diff = Math.abs(dz[varea.ridx(e.a)] - dz[varea.ridx(e.b)]) * mpd;
            b.see(diff, maxSlopeMeters);
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
                if(oh == null)
                    continue;
                a.see(Math.abs((dz[iv] * mpd) - oh), maxSlopeMeters);
                Integer ocTicks = rawTicksAt(varea, dz, mpd, terrain, oc);
                if(ocTicks != null)
                    c.see(Math.abs(dz[iv] - ocTicks), RAW_TICK_LIMIT);
            }
            for(Coord off : ALL_OFFSETS) {
                Coord oc = vc.add(off);
                if(varea.contains(oc))
                    continue;
                Double oh = terrain.terrainHeight(oc);
                if(oh == null)
                    continue;
                b.see(Math.abs((dz[iv] * mpd) - oh), maxSlopeMeters);
            }
        }

        return new ModelComparison(a.violations, b.violations, a.worst, b.worst, c.violations, c.worst);
    }
}
