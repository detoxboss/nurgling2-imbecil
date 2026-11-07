package nurgling.market;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds schema-2 layouts (as the server serves them) for tests. */
final class MarketTestLayouts {
    private MarketTestLayouts() {
    }

    /** Cluster id -> stand count for the 31 current clusters (108 stands). */
    static Map<Integer, Integer> currentClusterSizes() {
        Map<Integer, Integer> sizes = new LinkedHashMap<>();
        sizes.put(1, 3); sizes.put(2, 2); sizes.put(3, 3); sizes.put(4, 10);
        for (int id : new int[]{5, 6, 7, 8, 9, 10, 15, 16, 17, 18, 19, 20, 24, 25, 30, 31, 35, 36}) sizes.put(id, 4);
        for (int id : new int[]{21, 22, 23, 26, 32, 33, 34, 43, 44}) sizes.put(id, 2);
        return sizes;
    }

    /** Where every stand is: 25 units apart along x, clusters 400 units apart along y (fake grid). */
    static MarketLayout layout(int version, Map<Integer, Integer> sizes, boolean bound) throws IOException {
        return layout(version, sizes, bound, -1);
    }

    /** mapSeed >= 0 adds arbitrary customer-map presentation fields, which the client must ignore. */
    static MarketLayout layout(int version, Map<Integer, Integer> sizes, boolean bound, int mapSeed) throws IOException {
        java.util.Random random = new java.util.Random(mapSeed);
        JSONArray clusters = new JSONArray();
        int standId = 1000;
        int row = 0;
        for (Map.Entry<Integer, Integer> cluster : sizes.entrySet()) {
            JSONArray stands = new JSONArray();
            for (int stall = 1; stall <= cluster.getValue(); stall++) {
                standId++;
                JSONObject stand = new JSONObject()
                        .put("standId", standId)
                        .put("standKey", String.format("C%02d-S%d", cluster.getKey(), stall))
                        .put("clusterId", cluster.getKey())
                        .put("stallNumber", stall);
                if (mapSeed >= 0) {
                    stand.put("mapX", random.nextDouble() * 1000).put("mapY", random.nextDouble() * 900)
                            .put("x", random.nextDouble()).put("y", random.nextDouble());
                }
                if (bound) {
                    stand.put("physical", new JSONObject().put("gridId", "7001")
                            .put("offX", stall * 25.0).put("offY", row * 400.0));
                } else {
                    stand.put("physical", JSONObject.NULL);
                }
                stands.put(stand);
            }
            clusters.put(new JSONObject().put("id", cluster.getKey()).put("stands", stands));
            row++;
        }
        return MarketLayout.parse(new JSONObject().put("schema", 2).put("version", version).put("clusters", clusters));
    }

    static List<StandMatcher.Detected<String>> detectedAtSavedPositions(MarketLayout layout) {
        List<StandMatcher.Detected<String>> detected = new ArrayList<>();
        for (MarketLayout.Stand stand : layout.boundStands()) {
            detected.add(new StandMatcher.Detected<>(stand.standKey, stand.physical,
                    stand.physical.offX + 5000, stand.physical.offY + 9000));
        }
        return detected;
    }

    static StandMatcher.Detected<String> at(String label, String grid, double x, double y) {
        return new StandMatcher.Detected<>(label, new PhysicalRef(grid, x, y), x, y);
    }
}
