package nurgling.market;

import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** Crash-safe progress for a resumable, not-yet-public market scan. */
public final class MarketScanSpool {
    private static final int VERSION = 1;

    private final Path path;
    public String endpoint;
    public int areaId;
    public int standCount;
    public String scanId;
    public final Set<Integer> uploaded = new LinkedHashSet<>();
    public Integer pendingHouseId;
    public JSONObject pendingStand;

    private MarketScanSpool(Path path) {
        this.path = path;
    }

    public static MarketScanSpool fresh(Path path, String endpoint, int areaId, int standCount, String scanId) {
        MarketScanSpool spool = new MarketScanSpool(path);
        spool.endpoint = endpoint;
        spool.areaId = areaId;
        spool.standCount = standCount;
        spool.scanId = scanId;
        return spool;
    }

    public static MarketScanSpool load(Path path) {
        String content = NFileUtils.readWithBackupFallback(path.toString());
        if (content == null) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(content);
            if (json.optInt("version", 0) != VERSION) {
                return null;
            }
            MarketScanSpool spool = new MarketScanSpool(path);
            spool.endpoint = json.getString("endpoint");
            spool.areaId = json.getInt("areaId");
            spool.standCount = json.getInt("standCount");
            spool.scanId = json.getString("scanId");
            JSONArray uploaded = json.optJSONArray("uploadedHouseIds");
            if (uploaded != null) {
                for (int index = 0; index < uploaded.length(); index++) {
                    spool.uploaded.add(uploaded.getInt(index));
                }
            }
            if (!json.isNull("pendingHouseId") && json.has("pendingStand")) {
                spool.pendingHouseId = json.getInt("pendingHouseId");
                spool.pendingStand = json.getJSONObject("pendingStand");
            }
            return spool;
        } catch (RuntimeException malformed) {
            System.err.println("[H4DMarketScanner] Ignoring malformed spool: " + malformed.getMessage());
            return null;
        }
    }

    public boolean matches(String endpoint, int areaId, int standCount) {
        return this.endpoint.equals(endpoint) && this.areaId == areaId && this.standCount == standCount;
    }

    public void setPending(int houseId, JSONObject stand) throws IOException {
        pendingHouseId = houseId;
        pendingStand = stand;
        save();
    }

    public void acknowledgePending() throws IOException {
        if (pendingHouseId != null) {
            uploaded.add(pendingHouseId);
        }
        pendingHouseId = null;
        pendingStand = null;
        save();
    }

    public void save() throws IOException {
        JSONObject json = new JSONObject();
        json.put("version", VERSION);
        json.put("endpoint", endpoint);
        json.put("areaId", areaId);
        json.put("standCount", standCount);
        json.put("scanId", scanId);
        json.put("uploadedHouseIds", new JSONArray(uploaded));
        json.put("pendingHouseId", pendingHouseId == null ? JSONObject.NULL : pendingHouseId);
        json.put("pendingStand", pendingStand == null ? JSONObject.NULL : pendingStand);
        NFileUtils.writeAtomically(path.toString(), json.toString(2));
    }

    public void clear() throws IOException {
        Files.deleteIfExists(path);
        Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".bak"));
        Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".tmp"));
    }
}
