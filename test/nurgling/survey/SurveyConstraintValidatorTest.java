package nurgling.survey;

import haven.Area;
import haven.Coord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises SurveyConstraintValidator against synthetic height grids. Internal units throughout
 * are "dz" (haven.res.ui.surv.Data#dz) with {@code gran = 10f/11f} -- the real value the live
 * client sends (see haven.res.ui.surv.LandSurvey#mkwidget, {@code args[2] / 11}), NOT a
 * convenient round number. 1 dz unit is 0.1 m (matching the manual soil-placement test in the
 * Phase 2 investigation report: 0.1 m per soil, blocked at ~20 units = 2.0 m), but only because
 * {@link SurveyConstraintValidator#metersPerDzUnit} divides by {@code gran * MCache.tilesz.x}
 * -- a prior version of this test used an unrealistic {@code gran = 10f}, which combined with a
 * then-buggy validator that divided by {@code gran} alone to produce the right-looking 0.1 m/unit
 * number for the wrong reason, masking the regression this gran value is chosen to catch (with
 * the real 10f/11f, the same buggy single-division code computes 1.1 m/unit instead -- wildly
 * over-restrictive, matching the reported in-game symptom). All terrain snapshots are built with
 * {@link SurveyTerrainSnapshot#forTesting} so nothing here touches MCache or any other game
 * resource.
 */
class SurveyConstraintValidatorTest {
    private static final float GRAN = 10f / 11f;
    private static final double LIMIT = SurveySettings.TERRAIN_MAX_SLOPE_METERS; // 2.0 m

    @AfterEach
    void resetDiagonalFlag() {
        SurveyConstraintValidator.CHECK_DIAGONALS = false;
    }

    private static SurveyTerrainSnapshot flatTerrain(Area probed, double heightMeters) {
        Map<Coord, Double> h = new HashMap<>();
        for(Coord c : probed)
            h.put(c, heightMeters);
        return SurveyTerrainSnapshot.forTesting(h, new HashMap<>(), probed);
    }

    /** 1x1 tile: a 2x2 vertex area. */
    private static Area oneTile() {
        return Area.sized(Coord.of(0, 0), Coord.of(2, 2));
    }

    // 1. Completely flat 1x1 tile.
    @Test
    void flatTileIsValid() {
        Area varea = oneTile();
        int[] dz = {0, 0, 0, 0};
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyConstraintResult res = SurveyConstraintValidator.validate(varea, GRAN, dz, dz, terrain, LIMIT);
        assertEquals(SurveyConstraintResult.Status.VALID, res.overall);
        assertTrue(res.findings.isEmpty());
    }

    // Regression cases 1-6: incremental raises on flat ground at the real client gran
    // (10f/11f), each independently, confirming the whole 0-20 unit range is usable (not just
    // the single boundary points) and that the realistic gran doesn't throw off smaller values.
    @Test
    void realisticGranIncrementalRaises() {
        Area varea = oneTile();
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        int[] prior = {0, 0, 0, 0};
        int[][] validRaises = {{1}, {2}, {10}, {19}, {20}}; // 0.1, 0.2, 1.0, 1.9, 2.0 m
        for(int[] units : validRaises) {
            int[] cand = {units[0], 0, 0, 0};
            SurveyConstraintResult res = SurveyConstraintValidator.validate(varea, GRAN, prior, cand, terrain, LIMIT);
            assertEquals(SurveyConstraintResult.Status.VALID, res.overall,
                         units[0] + " units (" + (units[0] * 0.1) + " m) should be valid");
        }
        int[] cand21 = {21, 0, 0, 0}; // 2.1 m
        assertEquals(SurveyConstraintResult.Status.VIOLATION,
                     SurveyConstraintValidator.validate(varea, GRAN, prior, cand21, terrain, LIMIT).overall);
    }

    // Outside-boundary case: a 1.0 m difference to undisturbed ground must not be a violation;
    // a >2.0 m one must be recognized. Both at the real client gran.
    @Test
    void outsideBoundaryDifferenceRecognizedAtCorrectThreshold() {
        Area varea = oneTile();
        int[] dz = {0, 0, 0, 0};
        Map<Coord, Double> within = new HashMap<>();
        for(Coord c : varea.margin(1))
            within.put(c, 0.0);
        within.put(Coord.of(-1, 0), 1.0); // 1.0 m -- within the limit
        SurveyConstraintResult ok = SurveyConstraintValidator.validateAbsolute(
            varea, GRAN, dz, SurveyTerrainSnapshot.forTesting(within, new HashMap<>(), varea.margin(1)), LIMIT);
        assertEquals(SurveyConstraintResult.Status.VALID, ok.overall);

        Map<Coord, Double> beyond = new HashMap<>(within);
        beyond.put(Coord.of(-1, 0), 2.5); // 2.5 m -- over the limit
        SurveyConstraintResult bad = SurveyConstraintValidator.validateAbsolute(
            varea, GRAN, dz, SurveyTerrainSnapshot.forTesting(beyond, new HashMap<>(), varea.margin(1)), LIMIT);
        assertEquals(SurveyConstraintResult.Status.VIOLATION, bad.overall);
    }

    // Diagnostics must report metres, not the raw world-Z units MCache.getfz() returns (the
    // actual regression): a 2.1 m violation must report candidateDiffMeters near 2.1, not near
    // 2.1 * MCache.tilesz.x (~23.1), which is what the pre-fix code would have produced.
    @Test
    void diagnosticsReportMetersNotWorldZUnits() {
        Area varea = oneTile();
        int[] prior = {0, 0, 0, 0};
        int[] cand = {21, 0, 0, 0}; // 2.1 m
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyConstraintResult res = SurveyConstraintValidator.validate(varea, GRAN, prior, cand, terrain, LIMIT);
        boolean found = false;
        for(SurveyConstraintResult.EdgeFinding f : res.findings) {
            if((f.status == SurveyConstraintResult.Status.VIOLATION) && varea.contains(f.a) && varea.contains(f.b)) {
                assertEquals(2.1, f.candidateDiffMeters, 0.01);
                found = true;
            }
        }
        assertTrue(found);
    }

    // 2/5. Raising one vertex to exactly the permitted maximum (20 units = 2.0 m) is valid.
    @Test
    void exactlyAtLimitIsValid() {
        Area varea = oneTile();
        int[] prior = {0, 0, 0, 0};
        int[] cand = {20, 0, 0, 0};
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyConstraintResult res = SurveyConstraintValidator.validate(varea, GRAN, prior, cand, terrain, LIMIT);
        assertEquals(SurveyConstraintResult.Status.VALID, res.overall);
    }

    // 3. The next increment (21 units = 2.1 m) is a violation.
    @Test
    void oneUnitPastLimitIsViolation() {
        Area varea = oneTile();
        int[] prior = {0, 0, 0, 0};
        int[] cand = {21, 0, 0, 0};
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyConstraintResult res = SurveyConstraintValidator.validate(varea, GRAN, prior, cand, terrain, LIMIT);
        assertEquals(SurveyConstraintResult.Status.VIOLATION, res.overall);
        // The two interior edges from vertex 0 to its edge-adjacent neighbours (1,0) and (0,1),
        // plus the two boundary edges from vertex 0 out to the (flat, 0 m) ground beyond it.
        assertEquals(4, res.findings.size());
        int interior = 0;
        for(SurveyConstraintResult.EdgeFinding f : res.findings)
            if(varea.contains(f.a) && varea.contains(f.b))
                interior++;
        assertEquals(2, interior);
    }

    // 4. Diagonal corners distinguish edge-only (default) from whole-tile checking.
    @Test
    void diagonalOnlyExceedsLimit_notFlaggedByDefault_flaggedWhenEnabled() {
        Area varea = oneTile();
        // (0,0)=0, (1,0)=10, (0,1)=10, (1,1)=21: every edge-adjacent pair differs by exactly 10
        // or 11 (both within the 20-unit limit); only the (0,0)-(1,1) diagonal differs by 21.
        int[] dz = {0, 10, 10, 21};
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);

        SurveyConstraintValidator.CHECK_DIAGONALS = false;
        SurveyConstraintResult off = SurveyConstraintValidator.validate(varea, GRAN, dz, dz, terrain, LIMIT);
        assertEquals(SurveyConstraintResult.Status.VALID, off.overall);

        SurveyConstraintValidator.CHECK_DIAGONALS = true;
        int[] prior = {0, 10, 10, 0};
        SurveyConstraintResult on = SurveyConstraintValidator.validate(varea, GRAN, prior, dz, terrain, LIMIT);
        assertEquals(SurveyConstraintResult.Status.VIOLATION, on.overall);
    }

    // 6. Moving a single vertex downward is symmetric with moving it upward.
    @Test
    void downwardMoveSymmetric() {
        Area varea = oneTile();
        int[] prior = {0, 0, 0, 0};
        int[] cand = {-21, 0, 0, 0};
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyConstraintResult res = SurveyConstraintValidator.validate(varea, GRAN, prior, cand, terrain, LIMIT);
        assertEquals(SurveyConstraintResult.Status.VIOLATION, res.overall);
    }

    // 7. Moving an entire region (uniform translation) preserves its shape: interior differences
    // are unchanged, so only the boundary-to-outside-terrain edges can newly violate.
    @Test
    void uniformTranslationPreservesInteriorShape() {
        Area varea = oneTile();
        int[] prior = {0, 5, 5, 10};
        int[] cand = {15, 20, 20, 25}; // +15 to every vertex -- interior diffs (5, 5, 10) unchanged
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyConstraintResult res = SurveyConstraintValidator.validate(varea, GRAN, prior, cand, terrain, LIMIT);
        // Boundary edges now differ from the (flat, 0 m) outside ground by up to 2.5 m -- a new
        // violation there, but none among the interior edges.
        for(SurveyConstraintResult.EdgeFinding f : res.findings)
            assertFalse(varea.contains(f.a) && varea.contains(f.b), "interior edge should not have changed");
    }

    // 9. A restriction located just outside the survey boundary is still caught.
    @Test
    void outsideBoundaryRestrictionDetected() {
        Area varea = oneTile();
        Map<Coord, Double> heights = new HashMap<>();
        for(Coord c : varea.margin(1))
            heights.put(c, 0.0);
        heights.put(Coord.of(-1, 0), 3.0); // 3 m cliff just outside the left edge
        SurveyTerrainSnapshot terrain = SurveyTerrainSnapshot.forTesting(heights, new HashMap<>(), varea.margin(1));
        int[] dz = {0, 0, 0, 0};
        // validateAbsolute, not validate(dz, dz, ...): the latter would see candDiff == priorDiff
        // (an unchanged shape) and correctly call that "not worsened", which is a different
        // question from "is this edge over the limit at all".
        SurveyConstraintResult res = SurveyConstraintValidator.validateAbsolute(varea, GRAN, dz, terrain, LIMIT);
        assertEquals(SurveyConstraintResult.Status.VIOLATION, res.overall);
        boolean found = false;
        for(SurveyConstraintResult.EdgeFinding f : res.findings)
            found |= f.b.equals(Coord.of(-1, 0)) || f.a.equals(Coord.of(-1, 0));
        assertTrue(found);
    }

    // 10. A pre-existing cliff doesn't block the whole survey from being edited.
    @Test
    void preexistingCliffDoesNotBlockUnrelatedEdits() {
        Area varea = oneTile();
        int[] prior = {30, 0, 0, 0}; // already a 3.0 m cliff on two edges
        int[] cand = {30, 0, 0, 5}; // edits the far corner, well away from the cliff
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyConstraintResult res = SurveyConstraintValidator.validate(varea, GRAN, prior, cand, terrain, LIMIT);
        assertEquals(SurveyConstraintResult.Status.VALID, res.overall);
    }

    // 11. Editing an over-limit slope towards (but not all the way to) legal is allowed; editing
    // it further past where it already was is not.
    @Test
    void improvingOverLimitSlopeIsAllowed_worseningIsNot() {
        Area varea = oneTile();
        int[] prior = {30, 0, 0, 0}; // 3.0 m
        int[] improved = {25, 0, 0, 0}; // 2.5 m: still over the 2.0 m limit, but better than before
        int[] worsened = {31, 0, 0, 0}; // 3.1 m: worse than before
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);

        assertEquals(SurveyConstraintResult.Status.VALID,
                     SurveyConstraintValidator.validate(varea, GRAN, prior, improved, terrain, LIMIT).overall);
        assertEquals(SurveyConstraintResult.Status.VIOLATION,
                     SurveyConstraintValidator.validate(varea, GRAN, prior, worsened, terrain, LIMIT).overall);
    }

    // 12. Unavailable external terrain height data is reported as unknown, never as zero.
    @Test
    void missingOutsideTerrainIsUnknownNotZero() {
        Area varea = oneTile();
        Map<Coord, Double> heights = new HashMap<>();
        for(Coord c : varea) // only the survey's own vertices are known; everything outside is not
            heights.put(c, 0.0);
        SurveyTerrainSnapshot terrain = SurveyTerrainSnapshot.forTesting(heights, new HashMap<>(), varea.margin(1));
        int[] dz = {0, 0, 0, 0};
        SurveyConstraintResult res = SurveyConstraintValidator.validate(varea, GRAN, dz, dz, terrain, LIMIT);
        assertEquals(SurveyConstraintResult.Status.UNKNOWN, res.overall);
        for(SurveyConstraintResult.EdgeFinding f : res.findings)
            assertEquals(SurveyConstraintResult.Status.UNKNOWN, f.status);
    }

    // validateAbsolute: baseline/idle status flags an unresolved pre-existing cliff even though
    // it isn't "worsened" by anything (there is no candidate edit at all).
    @Test
    void validateAbsoluteFlagsUnresolvedPreexistingCliff() {
        Area varea = oneTile();
        int[] dz = {30, 0, 0, 0};
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyConstraintResult res = SurveyConstraintValidator.validateAbsolute(varea, GRAN, dz, terrain, LIMIT);
        assertEquals(SurveyConstraintResult.Status.VIOLATION, res.overall);
    }

    // The 255-unit height-span serialization guard (haven.res.ui.surv.Data#MAX_SPAN) is untouched
    // by this engine -- it operates purely on dz arrays the caller already has, and never
    // constructs or widens one itself.
    @Test
    void validatorNeverTouchesSpanEncoding() {
        Area varea = oneTile();
        int[] dz = {0, 0, 0, 255}; // at Data.MAX_SPAN, legal to encode regardless of slope rule
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        // Runs to completion without throwing or needing to know about Data.MAX_SPAN; VALID here
        // specifically because dz == priorDz (no candidate change), not because 25.5 m is legal.
        SurveyConstraintResult res = SurveyConstraintValidator.validate(varea, GRAN, dz, dz, terrain, LIMIT);
        assertEquals(SurveyConstraintResult.Status.VALID, res.overall);
    }

    // --- Follow-up: actual terrain slopes vs. target slopes, and AT_LIMIT reporting ---

    /** Two adjacent vertices whose real (unmodified) terrain already differs by 0.3 m -- the
     *  scenario from the follow-up report ("raising that vertex alone by 2.0 m would produce a
     *  2.3 m difference"). 0.3 m is 3 dz units at the real client gran (0.1 m/unit). */
    private static SurveyTerrainSnapshot slopedTerrain(Area probed) {
        Map<Coord, Double> h = new HashMap<>();
        for(Coord c : probed)
            h.put(c, (c.x == 0) ? 0.0 : 0.3); // vertex (0,y) at 0 m, vertex (1,y) at 0.3 m
        return SurveyTerrainSnapshot.forTesting(h, new HashMap<>(), probed);
    }

    // Ground Level starts the target at actual terrain heights, so the pre-existing 0.3 m real
    // offset is already baked into priorDz. Vertex (1,0) is the one already 0.3 m *above* its
    // neighbour (0,0) (per the follow-up report's exact example); raising it alone by the
    // "usual" full 20 units (2.0 m) reaches 23 units = 2.3 m against the untouched (0,0) --
    // correctly a violation -- and the largest legal raise is 17 more units (to 20 = 2.0 m).
    @Test
    void groundLevelInit_raisingAloneRespectsExistingActualSlope() {
        Area varea = oneTile();
        SurveyTerrainSnapshot terrain = slopedTerrain(varea.margin(1));
        // Ground Level: dz initialized from actual terrain -- (0,0)=0, (1,0)=3 (0.3 m), etc.
        int[] prior = {0, 3, 0, 3};
        int[] fullRaise = {0, 23, 0, 3}; // (1,0): 3 -> 23, a further +20 units (+2.0 m)
        SurveyConstraintResult full = SurveyConstraintValidator.validate(varea, GRAN, prior, fullRaise, terrain, LIMIT);
        assertEquals(SurveyConstraintResult.Status.VIOLATION, full.overall);

        int[] maxLegalRaise = {0, 20, 0, 3}; // (1,0): 3 -> 20, +17 units -- lands exactly at 2.0 m
        SurveyConstraintResult legal = SurveyConstraintValidator.validate(varea, GRAN, prior, maxLegalRaise, terrain, LIMIT);
        assertEquals(SurveyConstraintResult.Status.VALID, legal.overall);

        int[] oneOver = {0, 21, 0, 3}; // (1,0): 3 -> 21, +18 units -- 2.1 m against (0,0)
        assertEquals(SurveyConstraintResult.Status.VIOLATION,
                     SurveyConstraintValidator.validate(varea, GRAN, prior, oneOver, terrain, LIMIT).overall);
    }

    // Ground Plane starts the target flat (all four corners equal), regardless of the real 0.3 m
    // terrain offset -- intentionally different from Ground Level (per spec, both modes must
    // keep working). Raising one corner by the full 20 units is legal: the *final target shape*
    // (which is what will actually get dug/filled) is flat elsewhere and only 2.0 m at the
    // raised corner, which is exactly at, not over, the limit. The real terrain's current 0.3 m
    // offset is irrelevant to the final shape's own legality -- only to how much digging/filling
    // reaching it will take (a separate, unverified "intermediate reachability" question, not
    // this check's job).
    @Test
    void groundPlaneInit_flatTargetIgnoresActualSlopeForFinalShapeLegality() {
        Area varea = oneTile();
        // Real terrain inside the survey has the same 0.3 m offset as above (irrelevant to this
        // check -- interior legality only ever compares target-vs-target); the ring just outside
        // the survey is flat at 0.5 m, matching this test's flat Ground Plane target average, so
        // the boundary-vs-real-ground edges stay uninvolved and this test isolates the interior
        // question it's actually about.
        Map<Coord, Double> heights = new HashMap<>();
        for(Coord c : varea.margin(1))
            heights.put(c, varea.contains(c) ? ((c.x == 0) ? 0.0 : 0.3) : 0.5);
        SurveyTerrainSnapshot terrain = SurveyTerrainSnapshot.forTesting(heights, new HashMap<>(), varea.margin(1));
        int[] prior = {5, 5, 5, 5}; // Ground Plane: flat target at some average, unrelated to 0.3 m real slope
        int[] cand = {25, 5, 5, 5}; // +20 units on one corner only -- reaches exactly 2.0 m everywhere it touches
        SurveyConstraintResult res = SurveyConstraintValidator.validate(varea, GRAN, prior, cand, terrain, LIMIT);
        assertEquals(SurveyConstraintResult.Status.VALID, res.overall);
    }

    // An edge already at exactly 2.0 m must be reported AT_LIMIT, not silently VALID with no
    // marker at all (the gap this follow-up calls out in the original validateAbsolute).
    @Test
    void edgeExactlyAtLimitReportsAtLimitSeverity() {
        Area varea = oneTile();
        int[] dz = {20, 0, 0, 0}; // exactly 2.0 m
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyConstraintResult res = SurveyConstraintValidator.validateAbsolute(varea, GRAN, dz, terrain, LIMIT);
        assertEquals(SurveyConstraintResult.Status.VALID, res.overall); // not a blocking violation
        assertTrue(res.hasAtLimit());
        boolean found = false;
        for(SurveyConstraintResult.EdgeFinding f : res.findings) {
            if(varea.contains(f.a) && varea.contains(f.b)) {
                assertEquals(SurveyConstraintResult.Severity.AT_LIMIT, f.severity);
                found = true;
            }
        }
        assertTrue(found);
    }

    // Raising an already-maxed vertex (prior target diff already exactly 2.0 m) by one more
    // 0.1 m unit must become a confirmed, OVER_LIMIT violation.
    @Test
    void raisingAlreadyMaxedVertexByOneUnitViolates() {
        Area varea = oneTile();
        int[] prior = {20, 0, 0, 0};
        int[] cand = {21, 0, 0, 0};
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyConstraintResult res = SurveyConstraintValidator.validate(varea, GRAN, prior, cand, terrain, LIMIT);
        assertEquals(SurveyConstraintResult.Status.VIOLATION, res.overall);
        boolean found = false;
        for(SurveyConstraintResult.EdgeFinding f : res.findings)
            if(varea.contains(f.a) && varea.contains(f.b) && (f.status == SurveyConstraintResult.Status.VIOLATION)) {
                assertEquals(SurveyConstraintResult.Severity.OVER_LIMIT, f.severity);
                found = true;
            }
        assertTrue(found);
    }

    // Lowering that already-maxed vertex to reduce the slope is always legal -- AT_LIMIT must
    // never mean "frozen in every direction".
    @Test
    void loweringAlreadyMaxedVertexReducesSlopeAndIsLegal() {
        Area varea = oneTile();
        int[] prior = {20, 0, 0, 0};
        int[] cand = {15, 0, 0, 0}; // 1.5 m -- comfortably below the limit now
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyConstraintResult res = SurveyConstraintValidator.validate(varea, GRAN, prior, cand, terrain, LIMIT);
        assertEquals(SurveyConstraintResult.Status.VALID, res.overall);
    }

    // Reopening a survey on a previously player-created maximum slope: validateAbsolute (the
    // idle/on-open status check) must surface AT_LIMIT, not stay silent just because nothing is
    // being actively edited.
    @Test
    void reopeningSurveyOnPreviousMaxSlopeReportsAtLimit() {
        Area varea = oneTile();
        int[] savedDz = {20, 0, 0, 0}; // as loaded by Data.decode() from a prior session
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyConstraintResult res = SurveyConstraintValidator.validateAbsolute(varea, GRAN, savedDz, terrain, LIMIT);
        assertTrue(res.hasAtLimit());
        assertFalse(res.hasViolation());
    }

    // Existing naturally steep/cliff terrain: when the target matches actual terrain (Ground
    // Level) and the real terrain itself is already over the limit, actualTerrainOverLimit must
    // be true -- a genuine existing feature, not a target-only artifact.
    @Test
    void existingCliffTerrainMarkedAsActualNotTargetOnly() {
        Area varea = oneTile();
        Map<Coord, Double> heights = new HashMap<>();
        for(Coord c : varea.margin(1))
            heights.put(c, (c.x == 0) ? 0.0 : 3.0); // a genuine 3.0 m natural cliff
        SurveyTerrainSnapshot terrain = SurveyTerrainSnapshot.forTesting(heights, new HashMap<>(), varea.margin(1));
        int[] dz = {0, 30, 0, 30}; // Ground Level: target matches the real cliff exactly
        SurveyConstraintResult res = SurveyConstraintValidator.validateAbsolute(varea, GRAN, dz, terrain, LIMIT);
        boolean found = false;
        for(SurveyConstraintResult.EdgeFinding f : res.findings) {
            if(varea.contains(f.a) && varea.contains(f.b)) {
                assertEquals(Boolean.TRUE, f.actualTerrainOverLimit);
                found = true;
            }
        }
        assertTrue(found);
    }

    // A previously invalid *survey target* (e.g. saved by an older/less-strict validator or
    // under Off mode) that does NOT correspond to a genuinely steep real terrain feature must be
    // distinguishable: actualTerrainOverLimit is false even though the target itself is over the
    // limit and "preexisting".
    @Test
    void previouslyInvalidTargetDistinctFromGenuineActualCliff() {
        Area varea = oneTile();
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0); // real terrain is flat
        int[] dz = {0, 30, 0, 30}; // but the *saved target* already has an invalid 3.0 m cliff
        // validate (worsening-aware), not validateAbsolute: "preexisting" is only meaningful
        // relative to a prior-vs-candidate comparison, and validateAbsolute always compares a
        // shape to itself (strict mode), so it never sets preexisting at all.
        SurveyConstraintResult res = SurveyConstraintValidator.validate(varea, GRAN, dz, dz, terrain, LIMIT);
        boolean found = false;
        for(SurveyConstraintResult.EdgeFinding f : res.findings) {
            if(varea.contains(f.a) && varea.contains(f.b) && (f.severity == SurveyConstraintResult.Severity.OVER_LIMIT)) {
                assertEquals(Boolean.FALSE, f.actualTerrainOverLimit);
                assertTrue(f.preexisting);
                found = true;
            }
        }
        assertTrue(found);
    }
}
