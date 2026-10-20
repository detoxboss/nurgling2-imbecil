package nurgling.market;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static nurgling.market.MarketTestLayouts.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaSweepTest {
    /** A fake world where a stand is only visible while the player stands within {@code loadRadius} tiles. */
    private static final class FakeWorld implements AreaSweep.Source<String> {
        final List<StandMatcher.Detected<String>> all;
        final double loadRadiusUnits;
        final Set<Long> blockedGrids = new HashSet<>();
        long gridId;
        double playerX;
        double playerY;
        final List<AreaSweepPlanner.Waypoint> visited = new ArrayList<>();

        FakeWorld(List<StandMatcher.Detected<String>> all, double loadRadiusUnits) {
            this.all = all;
            this.loadRadiusUnits = loadRadiusUnits;
        }

        @Override
        public boolean visit(AreaSweepPlanner.Waypoint waypoint) {
            if (blockedGrids.contains(waypoint.gridId)) return false;
            visited.add(waypoint);
            playerX = waypoint.tileX * 11.0;
            playerY = waypoint.tileY * 11.0;
            return true;
        }

        @Override
        public List<StandMatcher.Detected<String>> detect() {
            List<StandMatcher.Detected<String>> loaded = new ArrayList<>();
            for (StandMatcher.Detected<String> stand : all) {
                if (Math.hypot(stand.ref.offX - playerX, stand.ref.offY - playerY) <= loadRadiusUnits) {
                    loaded.add(stand);
                }
            }
            return loaded;
        }
    }

    private static List<StandMatcher.Detected<String>> standsOn(long grid, int count, int seed) {
        List<StandMatcher.Detected<String>> stands = new ArrayList<>();
        java.util.Random random = new java.util.Random(seed);
        for (int index = 0; index < count; index++) {
            stands.add(at("g" + grid + "-" + index, Long.toString(grid), 40 + random.nextInt(1000), 40 + random.nextInt(1000)));
        }
        return stands;
    }

    @Test
    void everyTileOfAnAreaPieceIsNearAWaypoint() {
        AreaSweepPlanner.Piece piece = new AreaSweepPlanner.Piece(1, 0, 0, 100, 100);
        List<AreaSweepPlanner.Waypoint> waypoints = AreaSweepPlanner.plan(Collections.singletonList(piece), 40);

        assertEquals(9, waypoints.size());
        for (int x = 0; x < 100; x++) {
            for (int y = 0; y < 100; y++) {
                boolean covered = false;
                for (AreaSweepPlanner.Waypoint waypoint : waypoints) {
                    if (Math.abs(waypoint.tileX - x) <= 20 && Math.abs(waypoint.tileY - y) <= 20) {
                        covered = true;
                        break;
                    }
                }
                assertTrue(covered, "tile " + x + "," + y + " is more than half the spacing from any waypoint");
            }
        }
    }

    @Test
    void smallAndOddPiecesGetAtLeastOneWaypointInsideThem() {
        AreaSweepPlanner.Piece tiny = new AreaSweepPlanner.Piece(1, 10, 20, 12, 21);
        List<AreaSweepPlanner.Waypoint> waypoints = AreaSweepPlanner.plan(Collections.singletonList(tiny), 40);
        assertEquals(1, waypoints.size());
        assertTrue(waypoints.get(0).tileX >= 10 && waypoints.get(0).tileX < 12);
        assertTrue(waypoints.get(0).tileY >= 20 && waypoints.get(0).tileY < 21);
    }

    @Test
    void largeAreaIsDiscoveredEvenThoughNoSingleSpotSeesAllStands() throws Exception {
        // 3 grids of stands, each 100x100 tiles (1100x1100 units); only ~25 tiles are loaded around the player.
        List<StandMatcher.Detected<String>> world = new ArrayList<>();
        for (long grid = 1; grid <= 3; grid++) world.addAll(standsOn(grid, 36, (int) grid));
        List<AreaSweepPlanner.Piece> pieces = new ArrayList<>();
        for (long grid = 1; grid <= 3; grid++) pieces.add(new AreaSweepPlanner.Piece(grid, 0, 0, 100, 100));

        // Without sweeping, a single viewpoint finds only a fraction of them.
        FakeWorld stationary = new FakeWorld(world, 25 * 11);
        assertTrue(stationary.detect().size() < world.size());

        AreaSweep.Outcome<String> outcome = sweepPerGrid(world, pieces, 25 * 11);

        assertEquals(world.size(), outcome.stands.size());
        assertTrue(outcome.complete());
        assertEquals(outcome.waypoints, outcome.reached);
    }

    /** Each grid has its own local frame, so sweep each separately and merge through the accumulator. */
    private static AreaSweep.Outcome<String> sweepPerGrid(List<StandMatcher.Detected<String>> world,
                                                           List<AreaSweepPlanner.Piece> pieces, double radius)
            throws Exception {
        StandAccumulator<String> merged = new StandAccumulator<>();
        int waypoints = 0;
        int reached = 0;
        for (AreaSweepPlanner.Piece piece : pieces) {
            List<StandMatcher.Detected<String>> grid = new ArrayList<>();
            for (StandMatcher.Detected<String> stand : world) {
                if (stand.ref.gridId.equals(Long.toString(piece.gridId))) grid.add(stand);
            }
            FakeWorld source = new FakeWorld(grid, radius);
            AreaSweep.Outcome<String> outcome = AreaSweep.run(
                    AreaSweepPlanner.plan(Collections.singletonList(piece), AreaSweepPlanner.DEFAULT_SPACING_TILES), source);
            waypoints += outcome.waypoints;
            reached += outcome.reached;
            for (StandMatcher.Detected<String> stand : outcome.stands) merged.add(stand);
        }
        return new AreaSweep.Outcome<>(merged.all(), waypoints, reached, new ArrayList<AreaSweepPlanner.Waypoint>());
    }

    @Test
    void standsSeenFromTwoWaypointsAreCountedOnce() throws Exception {
        List<StandMatcher.Detected<String>> world = standsOn(1, 30, 5);
        FakeWorld source = new FakeWorld(world, 100 * 11); // sees everything from anywhere
        AreaSweep.Outcome<String> outcome = AreaSweep.run(
                AreaSweepPlanner.plan(Collections.singletonList(new AreaSweepPlanner.Piece(1, 0, 0, 100, 100)), 40), source);

        assertEquals(30, outcome.stands.size());
        assertTrue(outcome.waypoints > 1);
    }

    @Test
    void anUnreachableSectionIsReportedAndTheRestOfTheSweepContinues() throws Exception {
        List<StandMatcher.Detected<String>> world = new ArrayList<>(standsOn(1, 20, 1));
        world.addAll(standsOn(2, 20, 2));
        FakeWorld source = new FakeWorld(world, 100 * 11);
        source.blockedGrids.add(2L);
        List<AreaSweepPlanner.Piece> pieces = new ArrayList<>();
        pieces.add(new AreaSweepPlanner.Piece(1, 0, 0, 100, 100));
        pieces.add(new AreaSweepPlanner.Piece(2, 0, 0, 100, 100));

        AreaSweep.Outcome<String> outcome = AreaSweep.run(AreaSweepPlanner.plan(pieces, 40), source);

        assertFalse(outcome.complete());
        assertEquals(9, outcome.unreached.size());
        assertTrue(outcome.stands.size() >= 20);
    }

    @Test
    void discoveryReportClassifiesEveryDetectionAndNamesMissingStands() throws Exception {
        MarketLayout layout = MarketTestLayouts.layout(6, MarketTestLayouts.currentClusterSizes(), true);
        List<StandMatcher.Detected<String>> detected = MarketTestLayouts.detectedAtSavedPositions(layout);
        detected.remove(0);
        detected.add(at("new", "7001", 9000, 9000));
        StandMatcher.Result<String> result = StandMatcher.match(layout, detected, 2.0, 40.0);

        org.json.JSONObject report = MarketDiscoveryReport.build(6, "H4D Market", result, 9, 8);

        assertEquals(108, report.getJSONArray("stands").length());
        assertEquals(1, report.getJSONArray("missing").length());
        assertEquals(false, report.getJSONObject("coverage").getBoolean("complete"));
        int unknown = 0;
        for (int index = 0; index < report.getJSONArray("stands").length(); index++) {
            if ("unknown".equals(report.getJSONArray("stands").getJSONObject(index).getString("classification"))) unknown++;
        }
        assertEquals(1, unknown);
    }
}
