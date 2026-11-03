package nurgling.actions.bots.registry;

import nurgling.actions.bots.H4DMarketScanner;
import nurgling.actions.bots.LpAssistantBot;
import nurgling.actions.bots.LpGatherer;
import nurgling.actions.bots.OptimizeTableEatingBot;
import nurgling.actions.bots.VillageWatchBot;
import nurgling.actions.bots.VillageWatchSettingsAction;
import nurgling.actions.bots.blueprint.BlueprintBuilder;
import nurgling.actions.bots.drinker.DrinkerBot;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fork-vs-upstream registry union (docs/fork-customization-ledger.md, "Fork BotRegistry
 * registrations"): every fork bot and every upstream bot registered exactly once. A merge that
 * drops either side's line, or duplicates one, fails here instead of silently in the bot menu.
 */
class SyncRegistryInventoryTest {

    private static void registered(String id, Class<?> clazz) {
        BotDescriptor d = BotRegistry.byId(id);
        assertNotNull(d, "bot not registered: " + id);
        assertEquals(clazz, d.clazz, "wrong class for " + id);
    }

    @Test
    void forkRegistrationsSurvive() {
        registered(H4DMarketScanner.BOT_ID, H4DMarketScanner.class);
        registered(VillageWatchBot.BOT_ID, VillageWatchBot.class);
        registered(VillageWatchSettingsAction.BOT_ID, VillageWatchSettingsAction.class);
        registered("lpassistantbot", LpAssistantBot.class);
        registered("table_eat_optimizer", OptimizeTableEatingBot.class);
        assertNotNull(BotRegistry.byId("combatreactortool"), "combat reactor tool not registered");
    }

    @Test
    void upstreamRegistrationsArrive() {
        registered("lp_gatherer", LpGatherer.class);
        registered("drinker", DrinkerBot.class);
        registered("blueprint_builder", BlueprintBuilder.class);
    }

    @Test
    void noDuplicateIds() {
        Set<String> seen = new HashSet<>();
        int n = 0;
        for (BotDescriptor d : BotRegistry.all()) {
            assertTrue(seen.add(d.id), "duplicate bot id: " + d.id);
            n++;
        }
        // 198 live at the merge base + 6 fork + 3 upstream (2026-10-30 sync). BotRegistry also holds
        // one commented-out bots.add line (testzonepatroller), which a text count would include.
        assertEquals(207, n);
    }
}
