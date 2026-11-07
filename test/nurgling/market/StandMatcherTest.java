package nurgling.market;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static nurgling.market.MarketTestLayouts.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandMatcherTest {
    private static StandMatcher.Result<String> match(MarketLayout layout, List<StandMatcher.Detected<String>> detected)
            throws IOException {
        return StandMatcher.match(layout, detected, StandMatcher.DEFAULT_TOLERANCE, StandMatcher.DEFAULT_MOVE_RADIUS);
    }

    @Test
    void the108CurrentStandsAcrossThe31ClustersAllMatchTheirSavedPositions() throws Exception {
        MarketLayout layout = MarketTestLayouts.layout(6, MarketTestLayouts.currentClusterSizes(), true);
        assertEquals(31, MarketTestLayouts.currentClusterSizes().size());
        assertEquals(108, layout.stands.size());

        StandMatcher.Result<String> result = match(layout, MarketTestLayouts.detectedAtSavedPositions(layout));

        assertEquals(108, result.matched.size());
        assertTrue(result.unknown.isEmpty() && result.doubts.isEmpty() && result.missing.isEmpty());
        for (StandMatcher.Matched<String> matched : result.matched) {
            assertEquals(matched.stand.standKey, matched.detected.value);
        }
    }

    @Test
    void clustersMayHaveAnySizeAndShape() throws Exception {
        Map<Integer, Integer> sizes = new LinkedHashMap<>();
        for (int size : new int[]{1, 2, 3, 4, 10, 7}) sizes.put(sizes.size() + 1, size);
        MarketLayout layout = MarketTestLayouts.layout(2, sizes, true);

        StandMatcher.Result<String> result = match(layout, MarketTestLayouts.detectedAtSavedPositions(layout));

        assertEquals(27, result.matched.size());
    }

    @Test
    void extraUnregisteredStandsDoNotFailTheRunAndDoNotDisturbKnownOnes() throws Exception {
        MarketLayout layout = MarketTestLayouts.layout(6, MarketTestLayouts.currentClusterSizes(), true);
        List<StandMatcher.Detected<String>> detected = MarketTestLayouts.detectedAtSavedPositions(layout);
        // 12 brand-new stands in an empty expansion area far from every known stand
        for (int index = 0; index < 12; index++) {
            detected.add(at("new" + index, "7001", 5000 + index * 25.0, 5000));
        }

        StandMatcher.Result<String> result = match(layout, detected);

        assertEquals(108, result.matched.size());
        assertEquals(12, result.unknown.size());
        assertTrue(result.doubts.isEmpty() && result.missing.isEmpty());
    }

    @Test
    void knownIdentitiesSurviveWhenNewStandsAppearBetweenThem() throws Exception {
        MarketLayout layout = MarketTestLayouts.layout(6, MarketTestLayouts.currentClusterSizes(), true);
        MarketLayout.Stand anchor = layout.stands.get(40);
        List<StandMatcher.Detected<String>> detected = MarketTestLayouts.detectedAtSavedPositions(layout);
        Collections.shuffle(detected, new java.util.Random(7));
        // a new stand 13 units from a known one (adjacent clusters are close together)
        detected.add(at("neighbour", anchor.physical.gridId, anchor.physical.offX + 13, anchor.physical.offY));

        StandMatcher.Result<String> result = match(layout, detected);

        Map<Integer, String> byStand = new HashMap<>();
        for (StandMatcher.Matched<String> matched : result.matched) byStand.put(matched.stand.standId, matched.detected.value);
        assertEquals(108, byStand.size());
        assertEquals(anchor.standKey, byStand.get(anchor.standId));
        assertEquals(1, result.unknown.size() + result.doubts.size());
    }

    @Test
    void unloadedStandsAreReportedMissingNotRemoved() throws Exception {
        MarketLayout layout = MarketTestLayouts.layout(6, MarketTestLayouts.currentClusterSizes(), true);
        List<StandMatcher.Detected<String>> detected = MarketTestLayouts.detectedAtSavedPositions(layout);
        detected.subList(0, 9).clear(); // a whole chunk is not loaded

        StandMatcher.Result<String> result = match(layout, detected);

        assertEquals(99, result.matched.size());
        assertEquals(9, result.missing.size());
        assertTrue(result.unknown.isEmpty());
    }

    @Test
    void aStandNearAnUndetectedOneIsOfferedAsAPossibleMoveNeverAutoMatched() throws Exception {
        MarketLayout layout = MarketTestLayouts.layout(6, MarketTestLayouts.currentClusterSizes(), true);
        MarketLayout.Stand moved = layout.stands.get(10);
        List<StandMatcher.Detected<String>> detected = MarketTestLayouts.detectedAtSavedPositions(layout);
        detected.remove(10);
        detected.add(at("moved", moved.physical.gridId, moved.physical.offX + 15, moved.physical.offY + 3));

        StandMatcher.Result<String> result = match(layout, detected);

        assertEquals(107, result.matched.size());
        assertEquals(1, result.doubts.size());
        assertEquals(StandMatcher.Kind.MOVED, result.doubts.get(0).kind);
        assertEquals(moved.standId, result.doubts.get(0).candidates.get(0).standId);
        assertEquals(1, result.missing.size());
        assertTrue(result.unknown.isEmpty());
    }

    @Test
    void twoDetectionsOnOneSavedPositionAreAmbiguousAndNotGuessed() throws Exception {
        MarketLayout layout = MarketTestLayouts.layout(6, MarketTestLayouts.currentClusterSizes(), true);
        MarketLayout.Stand stand = layout.stands.get(3);
        List<StandMatcher.Detected<String>> detected = MarketTestLayouts.detectedAtSavedPositions(layout);
        detected.add(at("twin", stand.physical.gridId, stand.physical.offX + 1, stand.physical.offY));

        StandMatcher.Result<String> result = match(layout, detected);

        assertEquals(107, result.matched.size());
        assertEquals(2, result.doubts.size());
        for (StandMatcher.Doubt<String> doubt : result.doubts) {
            assertEquals(StandMatcher.Kind.AMBIGUOUS, doubt.kind);
        }
        assertTrue(result.missing.isEmpty()); // the stand is not "gone": it is in doubt
    }

    @Test
    void unboundStandsAreNotMatchedAndEverythingDetectedIsUnknown() throws Exception {
        MarketLayout layout = MarketTestLayouts.layout(5, MarketTestLayouts.currentClusterSizes(), false);
        List<StandMatcher.Detected<String>> detected = new ArrayList<>();
        for (int index = 0; index < 108; index++) detected.add(at("s" + index, "7001", index * 25.0, 0));

        StandMatcher.Result<String> result = match(layout, detected);

        assertTrue(result.matched.isEmpty());
        assertEquals(108, result.unknown.size());
        assertEquals(108, layout.unboundCount());
    }

    @Test
    void identityDoesNotDependOnCustomerMapPresentation() throws Exception {
        // Two layouts with identical physical references but completely different (random) customer-map
        // coordinates must produce identical identities for every detected stand.
        MarketLayout first = MarketTestLayouts.layout(6, MarketTestLayouts.currentClusterSizes(), true, 1);
        MarketLayout second = MarketTestLayouts.layout(6, MarketTestLayouts.currentClusterSizes(), true, 99);
        List<StandMatcher.Detected<String>> detected = MarketTestLayouts.detectedAtSavedPositions(first);

        Map<String, Integer> before = new HashMap<>();
        for (StandMatcher.Matched<String> matched : match(first, detected).matched) {
            before.put(matched.detected.value, matched.stand.standId);
        }
        Map<String, Integer> after = new HashMap<>();
        for (StandMatcher.Matched<String> matched : match(second, detected).matched) {
            after.put(matched.detected.value, matched.stand.standId);
        }

        assertEquals(108, before.size());
        assertEquals(before, after);
    }

    @Test
    void differentGridsNeverMatchEachOtherEvenAtTheSameOffset() throws Exception {
        MarketLayout layout = MarketTestLayouts.layout(6, MarketTestLayouts.currentClusterSizes(), true);
        MarketLayout.Stand stand = layout.stands.get(0);
        List<StandMatcher.Detected<String>> detected = new ArrayList<>();
        detected.add(at("elsewhere", "9999", stand.physical.offX, stand.physical.offY));

        StandMatcher.Result<String> result = match(layout, detected);

        assertTrue(result.matched.isEmpty());
        assertEquals(1, result.unknown.size());
    }

    @Test
    void legacyServerLayoutIsRejectedWithAnActionableMessage() {
        JSONObject legacy = new JSONObject().put("version", 4).put("clusters", new org.json.JSONArray());
        IOException failure = assertThrows(IOException.class, () -> MarketLayout.parse(legacy));
        assertTrue(failure.getMessage().contains("upgrade the server"));
    }
}
