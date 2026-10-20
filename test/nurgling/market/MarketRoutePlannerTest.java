package nurgling.market;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketRoutePlannerTest {
    /** A market-like floor: 4 rows of clusters, each cluster of 2..4 stands; entrance bottom-right. */
    private static List<MarketRoutePlanner.Stop<String>> market(int clusterIdOffset, boolean scramble) {
        List<MarketRoutePlanner.Stop<String>> stops = new ArrayList<>();
        int cluster = 0;
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 7; column++) {
                cluster++;
                double cx = column * 90.0;
                double cy = row * 80.0;
                int stands = 2 + (cluster % 3);
                for (int stand = 0; stand < stands; stand++) {
                    stops.add(new MarketRoutePlanner.Stop<>(
                            scramble ? (cluster * 37) % 101 + clusterIdOffset : cluster + clusterIdOffset,
                            cx + (stand % 2) * 12, cy + (stand / 2) * 12, "c" + cluster + "s" + stand));
                }
            }
        }
        return stops;
    }

    private static List<MarketRoutePlanner.Stop<String>> reorder(List<MarketRoutePlanner.Stop<String>> stops, List<String> order) {
        List<MarketRoutePlanner.Stop<String>> result = new ArrayList<>();
        for (String value : order) {
            for (MarketRoutePlanner.Stop<String> stop : stops) {
                if (stop.value.equals(value)) result.add(stop);
            }
        }
        return result;
    }

    @Test
    void visitsEveryStandExactlyOnce() {
        List<MarketRoutePlanner.Stop<String>> stops = market(0, false);
        List<String> order = MarketRoutePlanner.order(stops, 600, 320, MarketRoutePlanner.DEFAULT_ROW_GAP);

        assertEquals(stops.size(), order.size());
        assertEquals(stops.size(), new HashSet<>(order).size());
    }

    @Test
    void routeIsIndependentOfClusterNumbersAndInputOrder() {
        List<MarketRoutePlanner.Stop<String>> plain = market(0, false);
        List<MarketRoutePlanner.Stop<String>> renumbered = market(500, true);
        List<MarketRoutePlanner.Stop<String>> shuffled = new ArrayList<>(plain);
        Collections.shuffle(shuffled, new Random(11));

        List<String> expected = MarketRoutePlanner.order(plain, 600, 320, 8);

        assertEquals(expected, MarketRoutePlanner.order(renumbered, 600, 320, 8));
        assertEquals(expected, MarketRoutePlanner.order(shuffled, 600, 320, 8));
    }

    @Test
    void zigZagIsMuchShorterThanWalkingInIdOrderOrRandomOrder() {
        List<MarketRoutePlanner.Stop<String>> stops = market(0, false);
        double smart = MarketRoutePlanner.walkLength(
                reorder(stops, MarketRoutePlanner.order(stops, 600, 320, 8)), 600, 320);

        List<MarketRoutePlanner.Stop<String>> random = new ArrayList<>(stops);
        Collections.shuffle(random, new Random(3));
        double naive = MarketRoutePlanner.walkLength(random, 600, 320);

        assertTrue(smart < naive * 0.35, "zig-zag " + smart + " vs random " + naive);
        // Never worse than ~2x the ideal boustrophedon length through the rows.
        assertTrue(smart < 3000, "unexpectedly long route: " + smart);
    }

    @Test
    void startsAtTheClusterNearestTheStartPoint() {
        List<MarketRoutePlanner.Stop<String>> stops = market(0, false);
        List<String> order = MarketRoutePlanner.order(stops, 560, 330, 8); // bottom-right corner
        MarketRoutePlanner.Stop<String> first = reorder(stops, order.subList(0, 1)).get(0);

        assertTrue(Math.hypot(first.x - 560, first.y - 330) < 150, "first stop was " + first.value);
    }

    @Test
    void emptyAndSingleStandRoutesAreHandled() {
        assertTrue(MarketRoutePlanner.order(new ArrayList<MarketRoutePlanner.Stop<String>>(), 0, 0, 8).isEmpty());
        List<MarketRoutePlanner.Stop<String>> one = new ArrayList<>();
        one.add(new MarketRoutePlanner.Stop<>(9, 1, 2, "only"));
        assertEquals(Collections.singletonList("only"), MarketRoutePlanner.order(one, 0, 0, 8));
    }

    @Test
    void unbuiltPositionsSimplyNeverAppear() {
        List<MarketRoutePlanner.Stop<String>> stops = market(0, false);
        stops.removeIf(stop -> stop.value.startsWith("c1s") || stop.value.startsWith("c2s"));
        List<String> order = MarketRoutePlanner.order(stops, 0, 0, 8);
        Set<String> visited = new HashSet<>(order);
        assertEquals(stops.size(), visited.size());
        assertTrue(order.stream().noneMatch(v -> v.startsWith("c1s")));
    }
}
