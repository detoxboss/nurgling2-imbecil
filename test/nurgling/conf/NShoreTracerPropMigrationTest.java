package nurgling.conf;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NConfig's one-time migration (2026-11-05 sync, WorldExplorer/Shore Tracer split) reconstructs
 * NShoreTracerProp from the *raw* HashMap of a legacy `worldexplorerprop` JSON entry - the same
 * shape NWorldExplorerProp used to persist before the split. This locks in that the constructor
 * reads every field the migration depends on, without exercising NConfig's file I/O (which needs
 * resources this test classpath doesn't have - see NConfigSyncKeysTest's own note).
 */
class NShoreTracerPropMigrationTest {

    private static HashMap<String, Object> legacyBlob() {
        HashMap<String, Object> raw = new HashMap<>();
        raw.put("type", "NWorldExplorer");
        raw.put("username", "alice");
        raw.put("chrid", "char-1");
        raw.put("clockwise", true);
        raw.put("deeper", false);
        raw.put("lookaheadTiles", 120);
        raw.put("stuckTimeoutS", 3.5);
        raw.put("backupTiles", 4);
        raw.put("swingTiles", 7);
        raw.put("bandTiles", 9);
        List<Object> visited = new ArrayList<>();
        visited.add(101L);
        visited.add(202L);
        raw.put("visitedGridIds", visited);
        return raw;
    }

    @Test
    void migratesEveryLegacyField() {
        NShoreTracerProp prop = new NShoreTracerProp(legacyBlob());
        assertEquals(true, prop.clockwise);
        assertEquals(false, prop.deeper);
        assertEquals(120, prop.lookaheadTiles);
        assertEquals(3.5, prop.stuckTimeoutS);
        assertEquals(4, prop.backupTiles);
        assertEquals(7, prop.swingTiles);
        assertEquals(9, prop.bandTiles);
        assertEquals(2, prop.visitedGridIds.size());
        assertTrue(prop.visitedGridIds.contains(101L));
        assertTrue(prop.visitedGridIds.contains(202L));
    }

    @Test
    void missingFieldsKeepConstructorDefaults() {
        HashMap<String, Object> raw = new HashMap<>();
        raw.put("chrid", "char-2");
        raw.put("username", "bob");
        NShoreTracerProp prop = new NShoreTracerProp(raw);
        assertEquals(false, prop.clockwise);
        assertEquals(true, prop.deeper);
        assertEquals(80, prop.lookaheadTiles);
        assertEquals(2.0, prop.stuckTimeoutS);
        assertEquals(2, prop.backupTiles);
        assertEquals(5, prop.swingTiles);
        assertEquals(5, prop.bandTiles);
        assertTrue(prop.visitedGridIds.isEmpty());
    }

    @Test
    void toJsonRoundTripsUnderTheNewTypeTag() {
        NShoreTracerProp prop = new NShoreTracerProp(legacyBlob());
        org.json.JSONObject json = prop.toJson();
        assertEquals("NShoreTracer", json.get("type"));
        assertEquals(120, json.get("lookaheadTiles"));
        assertEquals(9, json.get("bandTiles"));

        NShoreTracerProp roundTripped = new NShoreTracerProp(
                (HashMap<String, Object>) (HashMap<?, ?>) json.toMap());
        assertEquals(prop.clockwise, roundTripped.clockwise);
        assertEquals(prop.lookaheadTiles, roundTripped.lookaheadTiles);
        assertEquals(prop.visitedGridIds.size(), roundTripped.visitedGridIds.size());
    }
}
