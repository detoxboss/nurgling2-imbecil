package nurgling.watch;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchProfileStoreTest {
    @TempDir
    Path dir;

    private WatchProfileStore store() {
        return new WatchProfileStore(dir.resolve("village_watch.json"));
    }

    @Test
    void a_default_profile_always_exists_and_has_the_documented_defaults() {
        WatchProfile p = store().get(WatchProfile.DEFAULT_NAME);
        assertNotNull(p);
        assertEquals(2, p.scanIntervalSec);
        assertEquals(30, p.absenceGraceSec);
        assertEquals(300, p.cooldownSec);
        assertTrue(p.attachScreenshot);
        for (WatchCategory c : WatchCategory.values()) {
            assertTrue(p.enabled(c));
            assertEquals(WatchTemplate.defaultFor(c), p.category(c).template);
        }
    }

    @Test
    void saved_profiles_survive_a_restart() throws IOException {
        WatchProfile p = new WatchProfile("Southeast Watch");
        p.watcher = "Southeast Wall";
        p.scanIntervalSec = 5;
        p.absenceGraceSec = 60;
        p.cooldownSec = 120;
        p.attachScreenshot = false;
        p.category(WatchCategory.WHITE).enabled = false;
        p.category(WatchCategory.RED).template = "RED at {watcher}\nline two";
        store().save(p);

        WatchProfile back = store().get("Southeast Watch"); // a fresh store object, as after a restart
        assertEquals("Southeast Wall", back.watcher);
        assertEquals(5, back.scanIntervalSec);
        assertEquals(60, back.absenceGraceSec);
        assertEquals(120, back.cooldownSec);
        assertFalse(back.attachScreenshot);
        assertFalse(back.enabled(WatchCategory.WHITE));
        assertTrue(back.enabled(WatchCategory.UNKNOWN));
        assertEquals("RED at {watcher}\nline two", back.category(WatchCategory.RED).template);
    }

    @Test
    void independent_profiles_keep_independent_settings() throws IOException {
        WatchProfile a = new WatchProfile("Southeast Watch");
        a.watcher = "SE";
        WatchProfile b = new WatchProfile("North Watch");
        b.watcher = "N";
        b.category(WatchCategory.UNKNOWN).template = "north unknown";
        store().save(a);
        store().save(b);
        assertEquals("SE", store().get("Southeast Watch").watcher);
        assertEquals("N", store().get("North Watch").watcher);
        assertEquals(WatchTemplate.defaultFor(WatchCategory.UNKNOWN), store().get("Southeast Watch").category(WatchCategory.UNKNOWN).template);
    }

    @Test
    void a_profile_handed_out_is_a_copy_so_editing_it_never_changes_the_stored_one_or_a_running_lookout() throws IOException {
        WatchProfileStore s = store();
        WatchProfile p = new WatchProfile("North Watch");
        p.watcher = "North";
        s.save(p);

        WatchProfile running = s.get("North Watch"); // what a started bot keeps
        WatchProfile editor = s.get("North Watch");
        assertNotSame(running, editor);
        editor.watcher = "Edited";
        editor.category(WatchCategory.RED).template = "edited";
        s.save(editor);

        assertEquals("North", running.watcher);
        assertEquals(WatchTemplate.defaultFor(WatchCategory.RED), running.category(WatchCategory.RED).template);
        assertEquals("Edited", s.get("North Watch").watcher);
    }

    @Test
    void profile_names_are_case_insensitive_and_saving_replaces_rather_than_duplicates() throws IOException {
        WatchProfileStore s = store();
        s.save(new WatchProfile("South Wall"));
        WatchProfile again = new WatchProfile("south wall");
        again.watcher = "second";
        s.save(again);
        assertEquals(2, s.names().size()); // Default + one
        assertEquals("second", s.get("SOUTH WALL").watcher);
    }

    @Test
    void delete_removes_a_profile_and_its_character_assignments_but_never_default() throws IOException {
        WatchProfileStore s = store();
        s.save(new WatchProfile("East Watch"));
        s.assign("Alice", "East Watch");
        assertEquals("East Watch", s.profileFor("Alice"));
        assertTrue(s.delete("East Watch"));
        assertNull(s.get("East Watch"));
        assertEquals(WatchProfile.DEFAULT_NAME, s.profileFor("Alice"));
        assertFalse(s.delete(WatchProfile.DEFAULT_NAME));
        assertNotNull(s.get(WatchProfile.DEFAULT_NAME));
    }

    @Test
    void each_character_remembers_its_own_profile() throws IOException {
        WatchProfileStore s = store();
        s.save(new WatchProfile("East Watch"));
        s.save(new WatchProfile("West Watch"));
        s.assign("Alice", "East Watch");
        s.assign("Bob", "West Watch");
        assertEquals("East Watch", s.profileFor("Alice"));
        assertEquals("West Watch", s.profileFor("Bob"));
        assertEquals(WatchProfile.DEFAULT_NAME, s.profileFor("Carol"));
        s.assign("Carol", "No Such Profile"); // ignored
        assertEquals(WatchProfile.DEFAULT_NAME, s.profileFor("Carol"));
    }

    @Test
    void hand_edited_out_of_range_numbers_are_clamped_and_blank_templates_restored() throws IOException {
        JSONObject p = new JSONObject();
        p.put("scanIntervalSec", 0);
        p.put("absenceGraceSec", 999999);
        p.put("cooldownSec", -4);
        p.put("categories", new JSONObject().put("red", new JSONObject().put("template", "   ")));
        JSONObject root = new JSONObject().put("profiles", new JSONObject().put("Odd", p));
        Files.write(dir.resolve("village_watch.json"), root.toString().getBytes(StandardCharsets.UTF_8));

        WatchProfile odd = store().get("Odd");
        assertEquals(WatchProfile.SCAN_MIN, odd.scanIntervalSec);
        assertEquals(WatchProfile.GRACE_MAX, odd.absenceGraceSec);
        assertEquals(WatchProfile.COOLDOWN_MIN, odd.cooldownSec);
        assertEquals(WatchTemplate.defaultFor(WatchCategory.RED), odd.category(WatchCategory.RED).template);
    }

    @Test
    void a_corrupt_file_reads_as_empty_and_is_replaced_by_the_next_save() throws IOException {
        Files.write(dir.resolve("village_watch.json"), "{ not json".getBytes(StandardCharsets.UTF_8));
        WatchProfileStore s = store();
        assertNotNull(s.get(WatchProfile.DEFAULT_NAME));
        s.save(new WatchProfile("Recovered"));
        assertNotNull(store().get("Recovered"));
    }

    @Test
    void numeric_field_parsing_rejects_junk_and_out_of_range() {
        assertEquals(Integer.valueOf(5), WatchProfile.parseInRange(" 5 ", 1, 60));
        assertNull(WatchProfile.parseInRange("", 1, 60));
        assertNull(WatchProfile.parseInRange("abc", 1, 60));
        assertNull(WatchProfile.parseInRange("61", 1, 60));
        assertNull(WatchProfile.parseInRange("0", 1, 60));
        assertNull(WatchProfile.parseInRange("2.5", 1, 60));
    }

    @Test
    void invalid_profile_names_are_rejected() {
        assertFalse(WatchProfile.validName(""));
        assertFalse(WatchProfile.validName("   "));
        assertFalse(WatchProfile.validName(null));
        assertFalse(WatchProfile.validName(new String(new char[WatchProfile.MAX_NAME_LENGTH + 1]).replace('\0', 'x')));
        assertTrue(WatchProfile.validName("Southeast Watch"));
    }
}
