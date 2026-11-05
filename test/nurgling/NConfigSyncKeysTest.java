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
        // 296 at the merge base + 12 fork + 7 upstream-only (autoDrinkThreshold was added by both),
        // + 2 fork keys since (keyboardFocusGuard, rememberInvListFilters), + 5 upstream keys from the
        // 2026-11-05 sync (mapClaimsShown, showThingwallLinks, botFavourites, disableEasterEggs,
        // easterEggSeen) + 1 fork key from the same sync (shoretracerprop, WorldExplorer/Shore
        // Tracer split - see docs/fork-customization-ledger.md).
        assertEquals(323, names.size());
        for (String fork : new String[] {"gobInstanceConf", "kinGroupLabels", "villageGroupLabels",
                "lpassistantbotprop", "autoDrinkThreshold", "showPlayerCoords", "stackSizeLearning",
                "combatReactorEnabled", "combatReactorDefenseThreshold", "equipBonusesExpanded",
                "showDurabilityBars", "showSpiceMarks", "keyboardFocusGuard", "rememberInvListFilters",
                "shoretracerprop"})
            assertTrue(names.contains(fork), "fork key lost: " + fork);
        for (String up : new String[] {"drinkerprop", "gridDiagonals", "gridDiagonalColor",
                "craftUseStorage", "roadTags", "tunnelingBot", "decoupledRender",
                "mapClaimsShown", "showThingwallLinks", "botFavourites"})
            assertTrue(names.contains(up), "upstream key missing: " + up);
    }
}
