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
    private static final int VERSION = 3;

    private final Path path;
    public String endpoint;
    public int areaId;
    public int layoutVersion;
    public String scanId;
    /** Permanent stand ids already acknowledged by the server. */
    public final Set<Integer> uploaded = new LinkedHashSet<>();
    public Integer pendingStandId;
    public JSONObject pendingStand;

    private MarketScanSpool(Path path) {
        this.path = path;
    }

    public static MarketScanSpool fresh(Path path, String endpoint, int areaId, int layoutVersion, String scanId) {
        MarketScanSpool spool = new MarketScanSpool(path);
        spool.endpoint = endpoint;
        spool.areaId = areaId;
        spool.layoutVersion = layoutVersion;
        spool.scanId = scanId;
        return spool;
    }

    /** Older spool versions (house ids under the previous numbering) are never resumed. */
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
            spool.layoutVersion = json.getInt("layoutVersion");
            spool.scanId = json.getString("scanId");
            JSONArray uploaded = json.optJSONArray("uploadedStandIds");
            if (uploaded != null) {
                for (int index = 0; index < uploaded.length(); index++) {
                    spool.uploaded.add(uploaded.getInt(index));
                }
            }
            if (!json.isNull("pendingStandId") && json.has("pendingStand")) {
                spool.pendingStandId = json.getInt("pendingStandId");
                spool.pendingStand = json.getJSONObject("pendingStand");
            }
            return spool;
        } catch (RuntimeException malformed) {
            System.err.println("[H4DMarketScanner] Ignoring malformed spool: " + malformed.getMessage());
            return null;
        }
    }

    /**
     * Resumable only for the same endpoint, area and layout version. A layout change (stands
     * commissioned, renumbered or remapped) begins a new scan: the server refuses to complete a scan
     * started under an older layout, and identities may have changed.
     */
    public boolean matches(String endpoint, int areaId, int layoutVersion) {
        return this.endpoint.equals(endpoint) && this.areaId == areaId && this.layoutVersion == layoutVersion;
    }

    /** Every recorded stand must still be a commissioned stand of the current layout. */
    public boolean isValidFor(Set<Integer> boundStandIds) {
        if (scanId == null || scanId.isEmpty()) {
            return false;
        }
        for (Integer standId : uploaded) {
            if (standId == null || !boundStandIds.contains(standId)) {
                return false;
            }
        }
        return pendingStandId == null || (boundStandIds.contains(pendingStandId) && pendingStand != null);
    }

    public void setPending(int standId, JSONObject stand) throws IOException {
        pendingStandId = standId;
        pendingStand = stand;
        save();
    }

    public void acknowledgePending() throws IOException {
        if (pendingStandId != null) {
            uploaded.add(pendingStandId);
        }
        pendingStandId = null;
        pendingStand = null;
        save();
    }

    public void save() throws IOException {
        JSONObject json = new JSONObject();
        json.put("version", VERSION);
        json.put("endpoint", endpoint);
        json.put("areaId", areaId);
        json.put("layoutVersion", layoutVersion);
        json.put("scanId", scanId);
        json.put("uploadedStandIds", new JSONArray(uploaded));
        json.put("pendingStandId", pendingStandId == null ? JSONObject.NULL : pendingStandId);
        json.put("pendingStand", pendingStand == null ? JSONObject.NULL : pendingStand);
        NFileUtils.writeAtomically(path.toString(), json.toString(2));
    }

    public void clear() throws IOException {
        Files.deleteIfExists(path);
        Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".bak"));
        Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".tmp"));
    }
}
