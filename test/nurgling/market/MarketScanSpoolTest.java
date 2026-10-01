package nurgling.market;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketScanSpoolTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsPendingStandBeforeAcknowledgement() throws Exception {
        Path file = temporaryDirectory.resolve("scan.json");
        MarketScanSpool spool = MarketScanSpool.fresh(file, "https://h4d.shop", 17, 24, "scan-123");
        JSONObject stand = new JSONObject().put("offers", new org.json.JSONArray());

        spool.setPending(4, stand);
        MarketScanSpool loaded = MarketScanSpool.load(file);

        assertEquals("scan-123", loaded.scanId);
        assertEquals(4, loaded.pendingHouseId);
        assertEquals(0, loaded.pendingStand.getJSONArray("offers").length());
        assertTrue(loaded.uploaded.isEmpty());

        loaded.acknowledgePending();
        MarketScanSpool acknowledged = MarketScanSpool.load(file);
        assertTrue(acknowledged.uploaded.contains(4));
        assertNull(acknowledged.pendingHouseId);
        assertNull(acknowledged.pendingStand);
    }

    @Test
    void onlyResumesTheSameRouteContract() throws Exception {
        Path file = temporaryDirectory.resolve("scan.json");
        MarketScanSpool spool = MarketScanSpool.fresh(file, "https://h4d.shop", 17, 24, "scan-123");
        spool.save();

        MarketScanSpool loaded = MarketScanSpool.load(file);
        assertTrue(loaded.matches("https://h4d.shop", 17, 24));
        assertFalse(loaded.matches("https://example.invalid", 17, 24));
        assertFalse(loaded.matches("https://h4d.shop", 18, 24));
        assertFalse(loaded.matches("https://h4d.shop", 17, 25));
    }

    @Test
    void clearRemovesPrimaryAndBackup() throws Exception {
        Path file = temporaryDirectory.resolve("scan.json");
        MarketScanSpool spool = MarketScanSpool.fresh(file, "https://h4d.shop", 17, 24, "scan-123");
        spool.save();
        spool.save();
        spool.clear();

        assertFalse(java.nio.file.Files.exists(file));
        assertFalse(java.nio.file.Files.exists(file.resolveSibling("scan.json.bak")));
    }
}
