package nurgling.actions.bots;

import nurgling.actions.bots.registry.BotDescriptor;
import nurgling.actions.bots.registry.BotRegistry;
import nurgling.scenarios.BotStep;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Registration and Scenario-step -> bot settings propagation; the live scan loop needs a running client. */
class VillageWatchBotSettingsTest {
    @Test
    void village_watch_is_registered_once_and_usable_from_both_scenarios_and_the_bots_menu() {
        int count = 0;
        for (BotDescriptor d : BotRegistry.all())
            if (VillageWatchBot.BOT_ID.equals(d.id))
                count++;
        assertEquals(1, count);
        BotDescriptor d = BotRegistry.byId("village_watch");
        assertNotNull(d);
        assertEquals(VillageWatchBot.class, d.clazz);
        assertTrue(d.allowedAsStepInScenario);
        assertTrue(d.allowedAsItemInBotMenu);
        assertFalse(d.allowedAsForagerStep);
    }

    @Test
    void the_settings_entry_is_in_the_bots_menu_but_not_a_scenario_step() {
        BotDescriptor d = BotRegistry.byId(VillageWatchSettingsAction.BOT_ID);
        assertNotNull(d);
        assertEquals(VillageWatchSettingsAction.class, d.clazz);
        assertFalse(d.allowedAsStepInScenario);
        assertTrue(d.allowedAsItemInBotMenu);
    }

    @Test
    void a_scenario_step_hands_its_profile_to_the_bot_that_runs_it() {
        BotStep step = new BotStep("village_watch");
        step.setSetting(VillageWatchBot.SETTING_PROFILE, "Southeast Watch");
        VillageWatchBot bot = (VillageWatchBot) BotRegistry.byId(step.getId()).instantiate(step.getSettings());
        assertEquals("Southeast Watch", bot.requestedProfile());
    }

    @Test
    void the_profile_survives_the_scenario_being_saved_and_loaded() {
        BotStep step = new BotStep("village_watch");
        step.setSetting(VillageWatchBot.SETTING_PROFILE, "North Watch");
        BotStep reloaded = new BotStep(new JSONObject(step.toJson().toString()));
        VillageWatchBot bot = (VillageWatchBot) BotRegistry.byId(reloaded.getId()).instantiate(reloaded.getSettings());
        assertEquals("North Watch", bot.requestedProfile());
    }

    @Test
    void two_steps_in_different_scenarios_keep_their_own_profiles() {
        BotStep a = new BotStep("village_watch");
        a.setSetting(VillageWatchBot.SETTING_PROFILE, "Southeast Watch");
        BotStep b = new BotStep("village_watch");
        b.setSetting(VillageWatchBot.SETTING_PROFILE, "Southwest Watch");
        VillageWatchBot botA = (VillageWatchBot) BotRegistry.byId("village_watch").instantiate(a.getSettings());
        VillageWatchBot botB = (VillageWatchBot) BotRegistry.byId("village_watch").instantiate(b.getSettings());
        assertEquals("Southeast Watch", botA.requestedProfile());
        assertEquals("Southwest Watch", botB.requestedProfile());
    }

    @Test
    void started_without_settings_the_bot_defers_to_the_characters_last_profile() {
        assertNull(new VillageWatchBot().requestedProfile());
        assertNull(new VillageWatchBot(null).requestedProfile());
        assertNull(new VillageWatchBot(new HashMap<String, Object>()).requestedProfile());
        Map<String, Object> blank = new HashMap<>();
        blank.put(VillageWatchBot.SETTING_PROFILE, "   ");
        assertNull(new VillageWatchBot(blank).requestedProfile());
        Object viaRegistry = BotRegistry.byId("village_watch").instantiate(null);
        assertTrue(viaRegistry instanceof VillageWatchBot);
        assertNull(((VillageWatchBot) viaRegistry).requestedProfile());
    }
}
