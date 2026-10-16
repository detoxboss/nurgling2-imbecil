package nurgling.market;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketLayoutMatcherTest {
    @Test
    void calibratesAnIrregularLayoutAcrossRotationScaleAndShuffle() throws Exception {
        double[][] map = {{.04, .08}, {.18, .24}, {.51, .17}, {.77, .46}, {.43, .79}, {.96, .91}};
        MarketLayout layout = layout(map, null);
        List<MarketLayoutMatcher.Point<String>> detected = new ArrayList<>();
        for (int index = 0; index < map.length; index++) {
            double worldX = 1_000 + 800 * map[index][1];
            double worldY = -2_000 + 1_100 * (1 - map[index][0]);
            detected.add(new MarketLayoutMatcher.Point<>("stand-" + (index + 1), worldX, worldY));
        }
        Collections.shuffle(detected, new Random(42));

        List<MarketLayoutMatcher.Match<String>> matches = MarketLayoutMatcher.match(layout, detected, 8);

        for (int index = 0; index < matches.size(); index++) {
            assertEquals(index + 1, matches.get(index).layout.houseId);
            assertEquals("stand-" + (index + 1), matches.get(index).value);
        }
        assertEquals(6, MarketLayoutMatcher.calibration(layout, matches).getJSONArray("stands").length());
    }

    @Test
    void calibratedLayoutRejectsAMovedStand() throws Exception {
        double[][] map = {{0, 0}, {1, 1}};
        double[][] world = {{100, 200}, {300, 400}};
        MarketLayout layout = layout(map, world);
        List<MarketLayoutMatcher.Point<String>> detected = List.of(
                new MarketLayoutMatcher.Point<>("one", 100, 200),
                new MarketLayoutMatcher.Point<>("two", 320, 400));

        assertThrows(IOException.class, () -> MarketLayoutMatcher.match(layout, detected, 8));
    }

    private static MarketLayout layout(double[][] map, double[][] world) throws Exception {
        JSONArray stands = new JSONArray();
        for (int index = 0; index < map.length; index++) {
            JSONObject stand = new JSONObject()
                    .put("houseId", index + 1)
                    .put("standKey", String.format("C%02d-S1", index + 1))
                    .put("clusterId", index + 1)
                    .put("stallNumber", 1)
                    .put("x", map[index][0])
                    .put("y", map[index][1]);
            if (world != null) {
                stand.put("worldX", world[index][0]);
                stand.put("worldY", world[index][1]);
            } else {
                stand.put("worldX", JSONObject.NULL);
                stand.put("worldY", JSONObject.NULL);
            }
            stands.put(stand);
        }
        JSONObject json = new JSONObject()
                .put("version", 2)
                .put("clusters", new JSONArray().put(new JSONObject().put("id", 1).put("stands", stands)));
        return MarketLayout.parse(json);
    }
}
