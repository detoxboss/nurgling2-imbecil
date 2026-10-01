package nurgling.market;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketStandOrderingTest {
    private final List<MarketStandOrdering.Stand<String>> stands = Arrays.asList(
            stand("middle-right", 20, 10.8),
            stand("bottom-left", 0, 20.0),
            stand("top-right", 20, 0.7),
            stand("middle-left", 0, 10.0),
            stand("top-left", 0, 0.0),
            stand("bottom-right", 20, 20.4)
    );

    @Test
    void northWestNumbersRowsLeftToRightDespitePlacementJitter() {
        assertEquals(Arrays.asList("top-left", "top-right", "middle-left", "middle-right", "bottom-left", "bottom-right"),
                names(MarketStandOrdering.order(stands, MarketStandOrdering.FirstCorner.NORTH_WEST, 2.0)));
    }

    @Test
    void southEastReversesBothAxes() {
        assertEquals(Arrays.asList("bottom-right", "bottom-left", "middle-right", "middle-left", "top-right", "top-left"),
                names(MarketStandOrdering.order(stands, MarketStandOrdering.FirstCorner.SOUTH_EAST, 2.0)));
    }

    @Test
    void parsesSavedScenarioValues() {
        assertEquals(MarketStandOrdering.FirstCorner.NORTH_EAST,
                MarketStandOrdering.FirstCorner.parse("north-east"));
        assertEquals(MarketStandOrdering.FirstCorner.SOUTH_WEST,
                MarketStandOrdering.FirstCorner.parse("SOUTH_WEST"));
        assertEquals(MarketStandOrdering.FirstCorner.NORTH_WEST,
                MarketStandOrdering.FirstCorner.parse("unknown"));
    }

    private static MarketStandOrdering.Stand<String> stand(String name, double x, double y) {
        return new MarketStandOrdering.Stand<>(name, x, y);
    }

    private static List<String> names(List<MarketStandOrdering.Stand<String>> ordered) {
        return ordered.stream().map(stand -> stand.value).collect(Collectors.toList());
    }
}
