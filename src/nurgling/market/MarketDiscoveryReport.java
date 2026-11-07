package nurgling.market;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;

/** Builds the discovery export: every detected stand with its physical reference and classification. */
public final class MarketDiscoveryReport {
    private MarketDiscoveryReport() {
    }

    public static <T> JSONObject build(int layoutVersion, String areaName, StandMatcher.Result<T> result,
                                       int waypoints, int reachedWaypoints) {
        JSONArray stands = new JSONArray();
        for (StandMatcher.Matched<T> matched : result.matched) {
            stands.put(entry(matched.detected, "known", matched.stand.standId, null));
        }
        for (StandMatcher.Detected<T> unknown : result.unknown) {
            stands.put(entry(unknown, "unknown", null, null));
        }
        for (StandMatcher.Doubt<T> doubt : result.doubts) {
            JSONArray candidates = new JSONArray();
            for (MarketLayout.Stand candidate : doubt.candidates) {
                candidates.put(candidate.standId);
            }
            stands.put(entry(doubt.detected, doubt.kind == StandMatcher.Kind.MOVED ? "moved" : "ambiguous",
                    null, candidates));
        }
        JSONArray missing = new JSONArray();
        for (MarketLayout.Stand stand : result.missing) {
            missing.put(stand.standId);
        }
        JSONObject coverage = new JSONObject()
                .put("waypoints", waypoints)
                .put("reached", reachedWaypoints)
                .put("complete", waypoints == reachedWaypoints);
        return new JSONObject()
                .put("scannerId", "nurgling2-h4d-market")
                .put("layoutVersion", layoutVersion)
                .put("observedAt", Instant.now().toString())
                .put("area", areaName)
                .put("coverage", coverage)
                .put("missing", missing)
                .put("stands", stands);
    }

    private static <T> JSONObject entry(StandMatcher.Detected<T> detected, String classification, Integer standId,
                                        JSONArray candidates) {
        JSONObject json = new JSONObject()
                .put("gridId", detected.ref.gridId)
                .put("offX", detected.ref.offX)
                .put("offY", detected.ref.offY)
                .put("sessionX", detected.rcX)
                .put("sessionY", detected.rcY)
                .put("classification", classification);
        if (standId != null) {
            json.put("standId", standId);
        }
        if (candidates != null) {
            json.put("candidates", candidates);
        }
        return json;
    }
}
