package nurgling;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config-key union after the 2026-10-30 upstream sync. (The 75% Auto-drink default itself is not
 * asserted here: constructing NConfig loads font resources the test classpath does not have.)
 */
class NConfigSyncKeysTest {

    @Test
    void keyUnionIsComplete() {
        Set<String> names = new HashSet<>();
        for (NConfig.Key k : NConfig.Key.values())
            names.add(k.name());
        assertEquals(NConfig.Key.values().length, names.size());
        // 296 at the merge base + 12 fork + 7 upstream-only (autoDrinkThreshold was added by both).
        assertEquals(315, names.size());
        for (String fork : new String[] {"gobInstanceConf", "kinGroupLabels", "villageGroupLabels",
                "lpassistantbotprop", "autoDrinkThreshold", "showPlayerCoords", "stackSizeLearning",
                "combatReactorEnabled", "combatReactorDefenseThreshold", "equipBonusesExpanded",
                "showDurabilityBars", "showSpiceMarks"})
            assertTrue(names.contains(fork), "fork key lost: " + fork);
        for (String up : new String[] {"drinkerprop", "gridDiagonals", "gridDiagonalColor",
                "craftUseStorage", "roadTags", "tunnelingBot", "decoupledRender"})
            assertTrue(names.contains(up), "upstream key missing: " + up);
    }
}
