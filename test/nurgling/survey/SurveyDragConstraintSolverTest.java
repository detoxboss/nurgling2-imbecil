package nurgling.survey;

import haven.Area;
import haven.Coord;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SurveyDragConstraintSolver against the same synthetic 1x1-tile grids as
 * SurveyConstraintValidatorTest, covering the Mover (uniform translation) and CMover
 * (corner-interpolated tilt) drag shapes used by haven.res.ui.surv.LandSurvey.
 */
class SurveyDragConstraintSolverTest {
    // Real client gran (haven.res.ui.surv.LandSurvey#mkwidget: args[2] / 11), not a convenient
    // round number -- the regression this exposed only showed up once the real gran was used
    // (see SurveyConstraintValidatorTest's class doc for why the old gran=10f masked it).
    private static final float GRAN = 10f / 11f;
    private static final double LIMIT = SurveySettings.TERRAIN_MAX_SLOPE_METERS; // 2.0 m

    private static Area oneTile() {
        return Area.sized(Coord.of(0, 0), Coord.of(2, 2));
    }

    private static SurveyTerrainSnapshot flatTerrain(Area probed, double heightMeters) {
        Map<Coord, Double> h = new HashMap<>();
        for(Coord c : probed)
            h.put(c, heightMeters);
        return SurveyTerrainSnapshot.forTesting(h, new HashMap<>(), probed);
    }

    /** Whole-survey Mover: every vertex moves together, coeff = 1 everywhere, gidx covers the
     *  whole varea (0..3 in row-major order matching Area#ridx). */
    private static int[] wholeSurveyGidx() {
        return new int[] {0, 1, 2, 3};
    }

    private static float[] uniformCoeff() {
        return new float[] {1, 1, 1, 1};
    }

    // Translating the whole survey uniformly is only limited by undisturbed ground outside it
    // (flat at 0 m here); requesting +25 units (2.5 m) should clamp to +20 (2.0 m).
    @Test
    void wholeSurveyTranslationClampsToOutsideGround() {
        Area varea = oneTile();
        int[] prior = {0, 0, 0, 0};
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyDragConstraintSolver.ClampResult cr = SurveyDragConstraintSolver.clamp(
            varea, GRAN, prior, wholeSurveyGidx(), new float[] {0, 0, 0, 0}, uniformCoeff(), 25f, terrain, LIMIT);
        assertEquals(20f, cr.diff, 0.001);
        assertTrue(cr.limitingEdge != null);
    }

    // Translating back towards the outside ground's own level is never clamped, even from a
    // starting shape that's already offset.
    @Test
    void translationTowardsOutsideGroundIsUnclamped() {
        Area varea = oneTile();
        int[] prior = {30, 30, 30, 30}; // whole survey already 3.0 m above (flat) outside ground
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyDragConstraintSolver.ClampResult cr = SurveyDragConstraintSolver.clamp(
            varea, GRAN, prior, wholeSurveyGidx(), new float[] {30, 30, 30, 30}, uniformCoeff(), -15f, terrain, LIMIT);
        assertEquals(-15f, cr.diff, 0.001);
        assertEquals(null, cr.limitingEdge);
    }

    // A pre-existing, already-over-limit edge (here: the whole survey sits 3.0 m above flat
    // outside ground) permits further movement that doesn't make it worse, but blocks movement
    // that does -- without distorting the gesture's shape (every moving vertex gets the same
    // clamp amount).
    @Test
    void preexistingViolationBlocksOnlyFurtherWorsening() {
        Area varea = oneTile();
        int[] prior = {30, 30, 30, 30};
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);

        // Moving further up (worsening the 3.0 m boundary gap) should be clamped to 0.
        SurveyDragConstraintSolver.ClampResult worse = SurveyDragConstraintSolver.clamp(
            varea, GRAN, prior, wholeSurveyGidx(), new float[] {30, 30, 30, 30}, uniformCoeff(), 10f, terrain, LIMIT);
        assertEquals(0f, worse.diff, 0.001);

        // Moving down (towards legality) by an amount that doesn't overshoot past the boundary
        // in the *worsening* sense is unclamped.
        SurveyDragConstraintSolver.ClampResult better = SurveyDragConstraintSolver.clamp(
            varea, GRAN, prior, wholeSurveyGidx(), new float[] {30, 30, 30, 30}, uniformCoeff(), -10f, terrain, LIMIT);
        assertEquals(-10f, better.diff, 0.001);
    }

    // Corner-interpolated tilt (CMover): raising only the "ul" corner pulls the diagonally
    // opposite "br" corner's coefficient to 0 and the two adjacent corners to 0.5. The limiting
    // edge here is the interior one between the ul corner and each of its two neighbours.
    @Test
    void cornerTiltClampedByInteriorEdge() {
        Area varea = oneTile();
        int[] prior = {0, 0, 0, 0};
        // ul=(0,0) -> index 0 coeff 1; ur=(1,0) -> index1 coeff 0.5; bl=(0,1) -> index2 coeff 0.5;
        // br=(1,1) -> index3 coeff 0.
        float[] coeff = {1f, 0.5f, 0.5f, 0f};
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyDragConstraintSolver.ClampResult cr = SurveyDragConstraintSolver.clamp(
            varea, GRAN, prior, wholeSurveyGidx(), new float[] {0, 0, 0, 0}, coeff, 100f, terrain, LIMIT);
        // d(t) between index0 and index1: C=0, D=(1-0.5)/10=0.05 -> |0.05*t|<=2.0 -> t<=40.
        // Between index0 and the (flat, 0 m) outside ground straight above it: C=0, D=1/10=0.1 ->
        // t<=20. The tighter of the two (20) wins.
        assertEquals(20f, cr.diff, 0.001);
    }

    // Unverifiable (unloaded) outside terrain never blocks a drag -- it's surfaced via
    // sawUnknownTerrain instead, per spec ("never treat missing terrain data as height zero").
    @Test
    void unknownOutsideTerrainNeverClamps() {
        Area varea = oneTile();
        int[] prior = {0, 0, 0, 0};
        SurveyTerrainSnapshot terrain = SurveyTerrainSnapshot.forTesting(new HashMap<>(), new HashMap<>(), varea.margin(1));
        // No heights known anywhere, including inside the survey, but the solver only needs
        // outside-ground heights for boundary edges, and a whole-survey translation has no
        // interior edges that move relative to each other.
        SurveyDragConstraintSolver.ClampResult cr = SurveyDragConstraintSolver.clamp(
            varea, GRAN, prior, wholeSurveyGidx(), new float[] {0, 0, 0, 0}, uniformCoeff(), 50f, terrain, LIMIT);
        assertEquals(50f, cr.diff, 0.001);
        assertTrue(cr.sawUnknownTerrain);
    }

    // A fractional boundary (here, an outside height of 1.95 m against a 2.0 m limit yields an
    // exact threshold at t=39.5) must round conservatively -- towards zero -- so the final
    // integer dz (haven.res.ui.surv.Data#dupdate rounds wz with Math.round) never ends up over
    // the limit due to rounding the wrong way.
    @Test
    void fractionalBoundaryRoundsConservatively() {
        Area varea = oneTile();
        int[] prior = {0, 0, 0, 0};
        Map<Coord, Double> heights = new HashMap<>();
        for(Coord c : varea.margin(1))
            heights.put(c, 1.95);
        SurveyTerrainSnapshot terrain = SurveyTerrainSnapshot.forTesting(heights, new HashMap<>(), varea.margin(1));
        SurveyDragConstraintSolver.ClampResult cr = SurveyDragConstraintSolver.clamp(
            varea, GRAN, prior, wholeSurveyGidx(), new float[] {0, 0, 0, 0}, uniformCoeff(), 100f, terrain, LIMIT);
        assertEquals(39f, cr.diff, 0.001); // floor(39.5), not round(39.5) -- never rounds outward
        double resultingDiffMeters = Math.abs((0 + cr.diff) * SurveyConstraintValidator.metersPerDzUnit(GRAN) - 1.95);
        assertTrue(resultingDiffMeters <= LIMIT + 1e-9);
    }

    // A requested diff of 0 (no mouse movement yet) never clamps and never reports a limiting
    // edge -- guards against a drag gesture spuriously recording a rejected no-op in undo history.
    @Test
    void zeroDiffIsNeverClamped() {
        Area varea = oneTile();
        int[] prior = {30, 30, 30, 30};
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyDragConstraintSolver.ClampResult cr = SurveyDragConstraintSolver.clamp(
            varea, GRAN, prior, wholeSurveyGidx(), new float[] {30, 30, 30, 30}, uniformCoeff(), 0f, terrain, LIMIT);
        assertEquals(0f, cr.diff, 0.001);
        assertEquals(null, cr.limitingEdge);
    }
}
