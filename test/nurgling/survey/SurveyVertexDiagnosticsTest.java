package nurgling.survey;

import haven.Area;
import haven.Coord;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SurveyVertexDiagnostics against the same realistic-gran synthetic grids as
 * SurveyConstraintValidatorTest -- confirms the per-vertex report surfaces exactly the numbers
 * the diagonal-corner investigation needs (Model A vs. B's limiting relationship, and the
 * target-based permitted-raise figure each model would allow), without needing a live client.
 */
class SurveyVertexDiagnosticsTest {
    private static final float GRAN = 10f / 11f;
    private static final double LIMIT = SurveySettings.TERRAIN_MAX_SLOPE_METERS; // 2.0 m

    private static Area oneTile() {
        return Area.sized(Coord.of(0, 0), Coord.of(2, 2));
    }

    /** See SurveyConstraintValidatorTest#twoByTwoTiles: a 1x1-tile survey's every vertex is
     *  itself a boundary corner, so a spike placed on one always also produces a same-sized
     *  boundary cardinal edge against flat outside ground, masking the edge-vs-diagonal gap
     *  these tests demonstrate. A 2x2-tile survey's center vertex has none of its 8 neighbors
     *  outside the survey, isolating the comparison to interior relationships only. */
    private static Area twoByTwoTiles() {
        return Area.sized(Coord.of(0, 0), Coord.of(3, 3));
    }

    private static SurveyTerrainSnapshot flatTerrain(Area probed, double heightMeters) {
        Map<Coord, Double> h = new HashMap<>();
        for(Coord c : probed)
            h.put(c, heightMeters);
        return SurveyTerrainSnapshot.forTesting(h, new HashMap<>(), probed);
    }

    // On flat ground with no candidate edit, every direction (cardinal and diagonal) has a known
    // 0 m difference, and both models agree there's no limiting relationship worth flagging
    // (max observed diff is 0, well below the limit) -- a basic sanity check before the
    // more interesting sloped/spiked cases.
    @Test
    void flatGround_allDirectionsKnownAndZero() {
        Area varea = oneTile();
        int[] dz = {0, 0, 0, 0};
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyVertexDiagnostics.Report r = SurveyVertexDiagnostics.compute(varea, GRAN, GRAN * 11, dz, terrain, LIMIT, Coord.of(0, 0));
        assertEquals(0.0, r.actualHeight, 0.001);
        assertEquals(0.0, r.targetHeight, 0.001);
        assertEquals(0.0, r.targetMinusActual(), 0.001);
        for(String dir : new String[] {"N", "S", "E", "W", "NE", "NW", "SE", "SW"}) {
            assertNotNull(r.targetDiffByDir.get(dir), dir + " should be known");
            assertEquals(0.0, r.targetDiffByDir.get(dir), 0.001);
        }
        assertEquals(LIMIT, r.modelAPermittedRaiseMeters, 0.01);
        assertEquals(LIMIT, r.modelBPermittedRaiseMeters, 0.01);
    }

    // The task's exact hypothetical, inspected from the spike's own vertex: a 2x2-tile survey's
    // interior center vertex is a 2.0m spike, its 4 cardinal neighbors are each 0.2m, and its 4
    // diagonal neighbors (the 4 tile corners) are each 0m. Model A's limiting relationship is a
    // cardinal neighbor (1.8m); Model B's is a diagonal (2.0m, the true tightest relationship)
    // -- and only Model B's permitted-raise figure (0.0m: already at the limit) matches what the
    // manual soil-placement test reported ("server refused additional soil"), while Model A's
    // (0.2m) is the "extra room" discrepancy.
    @Test
    void spikeVertex_modelBPermitsLessRaiseThanModelA() {
        Area varea = twoByTwoTiles();
        int[] dz = {0, 2, 0, 2, 20, 2, 0, 2, 0}; // center (1,1) = 20 units = 2.0m
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyVertexDiagnostics.Report r = SurveyVertexDiagnostics.compute(varea, GRAN, GRAN * 11, dz, terrain, LIMIT, Coord.of(1, 1));

        assertEquals(2.0, r.modelBLimitingTarget.diffMeters, 0.01, "a diagonal-to-corner relationship should be Model B's tightest");
        assertTrue(java.util.Arrays.asList("NE", "NW", "SE", "SW").contains(r.modelBLimitingTarget.dir));
        // Model A only ever looks at cardinal neighbors -- all four are the same 1.8m here.
        assertEquals(1.8, r.modelALimitingTarget.diffMeters, 0.01);

        assertEquals(0.2, r.modelAPermittedRaiseMeters, 0.01, "Model A reports 0.2m of headroom that isn't really there");
        assertEquals(0.0, r.modelBPermittedRaiseMeters, 0.01, "Model B correctly reports no headroom left");
    }

    // The NW tile's corner spread (Model B's per-tile figure) must equal the true max-min across
    // all 4 corners, not just the edge-adjacent ones -- here the diagonal from the tile's SE
    // corner (the spike) to its NW corner (0m) is the actual 2.0m spread, while every
    // edge-adjacent pair within that same tile is only 1.8m or 0.2m.
    @Test
    void tileCornerSpread_reflectsAllFourCornersNotJustEdges() {
        Area varea = twoByTwoTiles();
        int[] dz = {0, 2, 0, 2, 20, 2, 0, 2, 0};
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyVertexDiagnostics.Report r = SurveyVertexDiagnostics.compute(varea, GRAN, GRAN * 11, dz, terrain, LIMIT, Coord.of(1, 1));
        // tiles[] order is NW, NE, SW, SE (relative to the inspected vertex (1,1)); the "NW" tile
        // (ul=(0,0)) has the spike as its own SE corner and 0m at its other three corners.
        SurveyVertexDiagnostics.TileInfo nw = r.tiles[0];
        assertEquals(Coord.of(0, 0), nw.ul);
        assertEquals(2.0, nw.targetCornerSpread(), 0.01);
    }

    // A vertex whose diagonal neighbor's *real terrain* height is unknown (unloaded) must never
    // be treated as a known 0m difference -- the actual-terrain side of the report (and Model
    // B's actual-based limiting relationship) falls back to whatever is known instead of
    // silently substituting zero for the missing one. (The target side is unaffected here
    // because the diagonal vertex is inside the survey, so its target comes from dz, not
    // terrain -- see targetHeightAt.)
    @Test
    void unknownDiagonalNeighbor_notTreatedAsZero() {
        Area varea = oneTile();
        int[] dz = {0, 0, 0, 0};
        Map<Coord, Double> heights = new HashMap<>();
        for(Coord c : varea.margin(1))
            heights.put(c, 0.0);
        heights.remove(Coord.of(1, 1)); // the SE diagonal from (0,0): real terrain height unknown
        SurveyTerrainSnapshot terrain = SurveyTerrainSnapshot.forTesting(heights, new HashMap<>(), varea.margin(1));
        SurveyVertexDiagnostics.Report r = SurveyVertexDiagnostics.compute(varea, GRAN, GRAN * 11, dz, terrain, LIMIT, Coord.of(0, 0));
        assertNull(r.actualDiffByDir.get("SE"));
        // Model B's actual-based limiting relationship still resolves from its other known
        // neighbors, never crashing or silently substituting zero for the missing one.
        assertNotNull(r.modelBLimitingActual);
        assertTrue(!r.modelBLimitingActual.dir.equals("SE"));
    }

    // Whole-survey model comparison summary (what the diag panel's first line shows) must agree
    // with SurveyConstraintValidator.compareModels for the same inputs -- this is just a sanity
    // check that the diag panel's data source is the real one, not a separate reimplementation.
    @Test
    void wholeSurveyComparisonMatchesValidatorDirectly() {
        Area varea = twoByTwoTiles();
        int[] dz = {0, 2, 0, 2, 21, 2, 0, 2, 0}; // same as compareModels_spikePastDiagonalLimit_* in the validator test
        SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
        SurveyConstraintValidator.ModelComparison cmp = SurveyConstraintValidator.compareModels(varea, GRAN, dz, terrain, LIMIT);
        assertEquals(0, cmp.modelAViolations);
        assertEquals(4, cmp.modelBViolations); // all 4 of the center's symmetric diagonal-to-corner relationships
        String[] lines = SurveyVertexDiagnostics.formatLines(
            SurveyVertexDiagnostics.compute(varea, GRAN, GRAN * 11, dz, terrain, LIMIT, Coord.of(1, 1)), cmp);
        assertEquals(SurveyVertexDiagnostics.LINE_COUNT, lines.length);
        assertTrue(lines[0].contains("A viol=0"));
        assertTrue(lines[0].contains("B viol=4"));
    }

    // --- Follow-up #4: unit/threshold semantics -- the observed live gran was 1 (mkwidget
    // args[2]==11), not this file's usual GRAN=10f/11f constant. Reproduces the exact in-game
    // screenshot sequence reported: a peak vertex whose N/NE/NW neighbors all tie at the same
    // diff (so Model A and Model B see the identical limiting relationship and headroom --
    // enabling diagonals could not have changed anything here), raised one dz unit at a time. ---

    private static final float GRAN_1 = 1f;

    // Pure conversion arithmetic -- no claim about the server's real limit, just that
    // metersPerDzUnit tracks whatever gran actually is instead of assuming 10/11.
    @Test
    void metersPerDzUnit_gran1EqualsOneEleventhNotOneTenth() {
        assertEquals(1.0 / 11.0, SurveyConstraintValidator.metersPerDzUnit(GRAN_1), 1e-9);
        // 10f/11f loses float precision before this division even happens, so a 1e-9 delta is
        // tighter than the float -> double promotion can actually guarantee here.
        assertEquals(0.1, SurveyConstraintValidator.metersPerDzUnit(10f / 11f), 1e-6);
    }

    // The exact dz=239/219 peak/northern-neighbor fixture from the screenshots, at the interior
    // center of a 2x2-tile survey so NE and NW can be given the same dz as N (tying, not
    // contaminated by survey-boundary edges) while S/E/W/SE/SW stay flat against the peak.
    // Baseline and +1/+2/+3 on the peak alone reproduce, to 2 decimal places, every number in
    // the report: target height 21.73/21.82/21.91/22.00, northern (and tied NE/NW) target diff
    // 1.82/1.91/2.00/2.09, and A==B permitted raise 0.18/0.09/0.00/-0.09. This assumes the
    // current best-guess 2.0 m limit is correct -- it checks that the diagnostic's own
    // arithmetic reproduces observed numbers, not that 2.0 m is the real server threshold.
    @Test
    void gran1_observedPeakSequenceMatchesScreenshots_modelsAgreeNoDiagonalAdvantage() {
        Area varea = twoByTwoTiles();
        Coord peak = Coord.of(1, 1);
        double[] expectedTarget = {21.73, 21.82, 21.91, 22.00};
        double[] expectedDiff = {1.82, 1.91, 2.00, 2.09};
        double[] expectedPermitted = {0.18, 0.09, 0.00, -0.09};
        int[] peakDz = {239, 240, 241, 242};

        for(int i = 0; i < peakDz.length; i++) {
            // row-major over (0,0)..(2,2): N=(1,0)=idx1, NE=(2,0)=idx2, NW=(0,0)=idx0 all tied
            // at dz=219 (the screenshots' northern neighbor); S/E/W/SE/SW held flat against the
            // peak so they can never become the limiting relationship.
            int[] dz = {219, 219, 219, peakDz[i], peakDz[i], peakDz[i], peakDz[i], peakDz[i], peakDz[i]};
            SurveyTerrainSnapshot terrain = flatTerrain(varea.margin(1), 0);
            SurveyVertexDiagnostics.Report r = SurveyVertexDiagnostics.compute(varea, GRAN_1, 11f, dz, terrain, LIMIT, peak);

            assertEquals(expectedTarget[i], r.targetHeight, 0.005, "target height, peak dz=" + peakDz[i]);
            assertEquals(expectedDiff[i], r.targetDiffByDir.get("N"), 0.005, "N diff, peak dz=" + peakDz[i]);
            assertEquals(expectedDiff[i], r.targetDiffByDir.get("NE"), 0.005, "NE should tie N, peak dz=" + peakDz[i]);
            assertEquals(expectedDiff[i], r.targetDiffByDir.get("NW"), 0.005, "NW should tie N, peak dz=" + peakDz[i]);
            assertEquals(expectedPermitted[i], r.modelAPermittedRaiseMeters, 0.005, "Model A permitted raise, peak dz=" + peakDz[i]);
            assertEquals(expectedPermitted[i], r.modelBPermittedRaiseMeters, 0.005, "Model B permitted raise, peak dz=" + peakDz[i]);
            // The reported "Both models have the same limiting relationship" observation:
            // Model B (all 8 directions) finds nothing worse than the N/NE/NW tie Model A
            // (cardinals only) already sees -- diagonals have no room to add anything here.
            assertEquals(r.modelALimitingTarget.diffMeters, r.modelBLimitingTarget.diffMeters, 0.005,
                         "A and B must agree when the diagonal ties rather than exceeds, peak dz=" + peakDz[i]);
            assertEquals("N", r.modelALimitingTarget.dir);
            assertEquals("N", r.modelBLimitingTarget.dir);
        }
    }
}
