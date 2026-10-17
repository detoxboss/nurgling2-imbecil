package nurgling.market;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Server-owned identity and map position for every physical H4D barter stand. */
public final class MarketLayout {
    public static final class Stand {
        public final int houseId;
        public final String standKey;
        public final int clusterId;
        public final int stallNumber;
        public final int routeOrder;
        public final double mapX;
        public final double mapY;
        public final Double worldX;
        public final Double worldY;

        Stand(int houseId, String standKey, int clusterId, int stallNumber, int routeOrder,
              double mapX, double mapY, Double worldX, Double worldY) {
            this.houseId = houseId;
            this.standKey = standKey;
            this.clusterId = clusterId;
            this.stallNumber = stallNumber;
            this.routeOrder = routeOrder;
            this.mapX = mapX;
            this.mapY = mapY;
            this.worldX = worldX;
            this.worldY = worldY;
        }
    }

    public final int version;
    public final List<Stand> stands;

    private MarketLayout(int version, List<Stand> stands) {
        this.version = version;
        this.stands = stands;
    }

    public static MarketLayout parse(JSONObject json) throws IOException {
        int version = json.optInt("version", 0);
        JSONArray clusters = json.optJSONArray("clusters");
        if (version < 1 || clusters == null) {
            throw new IOException("Market layout response is missing version or clusters");
        }
        List<Stand> stands = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (int clusterIndex = 0; clusterIndex < clusters.length(); clusterIndex++) {
            JSONObject cluster = clusters.getJSONObject(clusterIndex);
            int clusterId = cluster.getInt("id");
            JSONArray clusterStands = cluster.getJSONArray("stands");
            for (int standIndex = 0; standIndex < clusterStands.length(); standIndex++) {
                JSONObject source = clusterStands.getJSONObject(standIndex);
                int houseId = source.getInt("houseId");
                String standKey = source.getString("standKey");
                if (!ids.add(houseId) || !keys.add(standKey)) {
                    throw new IOException("Market layout contains duplicate stand identities");
                }
                stands.add(new Stand(
                        houseId,
                        standKey,
                        source.optInt("clusterId", clusterId),
                        source.getInt("stallNumber"),
                        source.optInt("routeOrder", houseId),
                        source.getDouble("x"),
                        source.getDouble("y"),
                        optionalDouble(source, "worldX"),
                        optionalDouble(source, "worldY")
                ));
            }
        }
        stands.sort(Comparator.comparingInt(stand -> stand.routeOrder));
        if (stands.isEmpty()) {
            throw new IOException("Market layout contains no stands");
        }
        return new MarketLayout(version, stands);
    }

    public boolean isCalibrated() {
        for (Stand stand : stands) {
            if (stand.worldX == null || stand.worldY == null) {
                return false;
            }
        }
        return true;
    }

    public int calibratedCount() {
        int count = 0;
        for (Stand stand : stands) {
            if (stand.worldX != null && stand.worldY != null) count++;
        }
        return count;
    }

    private static Double optionalDouble(JSONObject json, String name) {
        return !json.has(name) || json.isNull(name) ? null : json.getDouble(name);
    }
}
