package nurgling.market;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketScanSpoolTest {
    @TempDir
    Path temporaryDirectory;

    private static Set<Integer> ids(int... values) {
        Set<Integer> set = new HashSet<>();
        for (int value : values) set.add(value);
        return set;
    }

    @Test
    void persistsPendingStandBeforeAcknowledgement() throws Exception {
        Path file = temporaryDirectory.resolve("scan.json");
        MarketScanSpool spool = MarketScanSpool.fresh(file, "https://h4d.shop", 17, 6, "scan-123");
        JSONObject stand = new JSONObject().put("offers", new org.json.JSONArray());

        spool.setPending(1004, stand);
        MarketScanSpool loaded = MarketScanSpool.load(file);

        assertEquals("scan-123", loaded.scanId);
        assertEquals(1004, loaded.pendingStandId);
        assertEquals(0, loaded.pendingStand.getJSONArray("offers").length());
        assertTrue(loaded.uploaded.isEmpty());

        loaded.acknowledgePending();
        MarketScanSpool acknowledged = MarketScanSpool.load(file);
        assertTrue(acknowledged.uploaded.contains(1004));
        assertNull(acknowledged.pendingStandId);
        assertNull(acknowledged.pendingStand);
    }

    @Test
    void resumesOnlyTheSameEndpointAreaAndLayoutVersion() throws Exception {
        Path file = temporaryDirectory.resolve("scan.json");
        MarketScanSpool.fresh(file, "https://h4d.shop", 17, 6, "scan-123").save();

        MarketScanSpool loaded = MarketScanSpool.load(file);
        assertTrue(loaded.matches("https://h4d.shop", 17, 6));
        assertFalse(loaded.matches("https://example.invalid", 17, 6));
        assertFalse(loaded.matches("https://h4d.shop", 18, 6));
        // Any layout change (stands commissioned/renumbered/remapped) starts a new scan.
        assertFalse(loaded.matches("https://h4d.shop", 17, 7));
    }

    @Test
    void anAdditionalUnregisteredStandDoesNotInvalidateAnInProgressScan() throws Exception {
        // The old spool compared a total stand count, so discovering one more stand killed the resume.
        Path file = temporaryDirectory.resolve("scan.json");
        MarketScanSpool spool = MarketScanSpool.fresh(file, "https://h4d.shop", 17, 6, "scan-123");
        spool.setPending(1001, new JSONObject().put("offers", new org.json.JSONArray()));
        spool.acknowledgePending();

        MarketScanSpool loaded = MarketScanSpool.load(file);
        assertTrue(loaded.matches("https://h4d.shop", 17, 6));
        assertTrue(loaded.isValidFor(ids(1001, 1002, 1003)));
    }

    @Test
    void refusesToResumeWhenARecordedStandIsNoLongerCommissioned() throws Exception {
        Path file = temporaryDirectory.resolve("scan.json");
        MarketScanSpool spool = MarketScanSpool.fresh(file, "https://h4d.shop", 17, 6, "scan-123");
        spool.setPending(1001, new JSONObject().put("offers", new org.json.JSONArray()));
        spool.acknowledgePending();
        spool.setPending(1002, new JSONObject().put("offers", new org.json.JSONArray()));

        MarketScanSpool loaded = MarketScanSpool.load(file);
        assertTrue(loaded.isValidFor(ids(1001, 1002)));
        assertFalse(loaded.isValidFor(ids(1002)));       // acknowledged stand was retired
        assertFalse(loaded.isValidFor(ids(1001)));       // pending stand was retired
    }

    @Test
    void spoolsFromTheHouseIdEraAreNeverResumed() throws Exception {
        Path file = temporaryDirectory.resolve("scan.json");
        Files.write(file, ("{\"version\":2,\"endpoint\":\"https://h4d.shop\",\"areaId\":17,\"standCount\":89,"
                + "\"layoutVersion\":4,\"scanId\":\"old\",\"uploadedHouseIds\":[26,27],\"pendingHouseId\":null,"
                + "\"pendingStand\":null}").getBytes("UTF-8"));

        assertNull(MarketScanSpool.load(file));
    }

    @Test
    void clearRemovesPrimaryAndBackup() throws Exception {
        Path file = temporaryDirectory.resolve("scan.json");
        MarketScanSpool spool = MarketScanSpool.fresh(file, "https://h4d.shop", 17, 6, "scan-123");
        spool.save();
        spool.save();
        spool.clear();

        assertFalse(Files.exists(file));
        assertFalse(Files.exists(file.resolveSibling("scan.json.bak")));
    }
}
