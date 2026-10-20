package nurgling.watch;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchTemplateTest {
    private static Map<String, String> values() {
        Map<String, String> v = new HashMap<>();
        v.put("watcher", "Southeast Wall");
        v.put("player", "Mallory");
        v.put("status", "Red kin group");
        v.put("time", "12:00");
        v.put("gob_id", "42");
        v.put("coords", "(1, 2)");
        return v;
    }

    @Test
    void every_placeholder_is_substituted() {
        String out = WatchTemplate.render("{watcher}|{player}|{status}|{time}|{gob_id}|{coords}", values());
        assertEquals("Southeast Wall|Mallory|Red kin group|12:00|42|(1, 2)", out);
    }

    @Test
    void a_repeated_placeholder_is_substituted_each_time() {
        assertEquals("Mallory and Mallory", WatchTemplate.render("{player} and {player}", values()));
    }

    @Test
    void missing_values_render_as_unavailable_never_as_a_guess() {
        Map<String, String> v = values();
        v.remove("coords");
        v.put("player", null);
        assertEquals("at unavailable by unavailable", WatchTemplate.render("at {coords} by {player}", v));
        assertEquals("unavailable", WatchTemplate.render("{coords}", new HashMap<String, String>()));
    }

    @Test
    void unknown_braces_and_plain_text_are_left_alone() {
        assertEquals("{nonsense} {Player} { player } 100%", WatchTemplate.render("{nonsense} {Player} { player } 100%", values()));
    }

    @Test
    void substituted_values_are_not_expanded_again() {
        Map<String, String> v = values();
        v.put("player", "{coords}$1\\");
        assertEquals("who: {coords}$1\\", WatchTemplate.render("who: {player}", v));
    }

    @Test
    void defaults_carry_the_required_fields_for_every_category() {
        for (WatchCategory c : WatchCategory.values()) {
            String t = WatchTemplate.defaultFor(c);
            assertTrue(t.contains("VILLAGE WATCH ALERT"), c.name());
            assertTrue(t.contains("{watcher}") && t.contains("{player}") && t.contains("{status}") && t.contains("{time}"), c.name());
        }
        assertFalse(WatchTemplate.defaultFor(WatchCategory.UNKNOWN).equals(WatchTemplate.defaultFor(WatchCategory.RED)));
    }

    @Test
    void sample_values_cover_every_placeholder_and_never_pose_as_real_data() {
        Map<String, String> v = WatchTemplate.sampleValues("North", WatchCategory.UNKNOWN);
        for (String p : WatchTemplate.PLACEHOLDERS)
            assertTrue(v.containsKey(p), p);
        assertEquals("Unknown", v.get("player"));
    }

    @Test
    void usesPlaceholder_detects_only_exact_tokens() {
        assertTrue(WatchTemplate.usesPlaceholder("at {coords}", "coords"));
        assertFalse(WatchTemplate.usesPlaceholder("at coords", "coords"));
    }
}
