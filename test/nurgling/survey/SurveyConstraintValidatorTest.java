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
 * are "dz" (haven.res.ui.surv.Data#dz) with gran = 10, i.e. 1 unit = 0.1 m -- matching the manual
 * soil-placement test in the Phase 2 investigation report (0.1 m per soil, blocked at ~20 units
 * = 2.0 m). All terrain snapshots are built with {@link SurveyTerrainSnapshot#forTesting} so
 * nothing here touches MCache or any other game resource.
 */
class SurveyConstraintValidatorTest {
    private static final float GRAN = 10f;
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
}
