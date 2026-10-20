package nurgling.market;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Server-owned stand identity (schema 2).
 *
 * Carries permanent identity, cluster membership and the private physical reference only. Customer
 * map presentation (marker positions, artwork) is deliberately absent: it can change freely without
 * ever affecting which physical stand is which.
 */
public final class MarketLayout {
    public static final class Stand {
        public final int standId;
        public final String standKey;
        public final int clusterId;
        public final int stallNumber;
        /** Null until the stand has been commissioned (bound to a physical position). */
        public final PhysicalRef physical;

        Stand(int standId, String standKey, int clusterId, int stallNumber, PhysicalRef physical) {
            this.standId = standId;
            this.standKey = standKey;
            this.clusterId = clusterId;
            this.stallNumber = stallNumber;
            this.physical = physical;
        }

        public boolean isBound() {
            return physical != null;
        }
    }

    public final int version;
    public final List<Stand> stands;

    private MarketLayout(int version, List<Stand> stands) {
        this.version = version;
        this.stands = stands;
    }

    public static MarketLayout parse(JSONObject json) throws IOException {
        if (json.optInt("schema", 1) != 2) {
            throw new IOException("The H4D Market server still serves the legacy layout; upgrade the server to "
                    + "v0.4.0 or newer before running this scanner");
        }
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
                int standId = source.getInt("standId");
                String standKey = source.getString("standKey");
                if (!ids.add(standId) || !keys.add(standKey)) {
                    throw new IOException("Market layout contains duplicate stand identities");
                }
                PhysicalRef physical = source.isNull("physical") || !source.has("physical")
                        ? null : PhysicalRef.fromJson(source.getJSONObject("physical"));
                stands.add(new Stand(standId, standKey, source.optInt("clusterId", clusterId),
                        source.getInt("stallNumber"), physical));
            }
        }
        return new MarketLayout(version, stands);
    }

    /** Stands that have a physical position and therefore can be scanned. */
    public List<Stand> boundStands() {
        List<Stand> bound = new ArrayList<>();
        for (Stand stand : stands) {
            if (stand.isBound()) {
                bound.add(stand);
            }
        }
        return bound;
    }

    public Set<Integer> boundIds() {
        Set<Integer> ids = new HashSet<>();
        for (Stand stand : stands) {
            if (stand.isBound()) {
                ids.add(stand.standId);
            }
        }
        return ids;
    }

    public int unboundCount() {
        return stands.size() - boundStands().size();
    }
}
