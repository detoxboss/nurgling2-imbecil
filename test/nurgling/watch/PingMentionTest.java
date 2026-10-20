package nurgling.watch;

import nurgling.notifications.DiscordMessage;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PingMentionTest {
    private static final String ROLE = "123456789012345678";
    private static final String USER = "987654321098765432";
    private static final ZonedDateTime WHEN = ZonedDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);

    private static WatchProfile profile(WatchCategory c, PingTarget t, String id) {
        WatchProfile p = new WatchProfile("North");
        p.category(c).ping = t;
        p.category(c).pingId = id;
        return p;
    }

    private static DiscordMessage msg(WatchProfile p, WatchCategory c, String playerName, ThreatTracker.Reason reason, boolean allowPing) {
        ThreatTracker.Sighting s = new ThreatTracker.Sighting(5, c, playerName, null);
        ThreatTracker.Alert a = reason == ThreatTracker.Reason.NEW ? ThreatTracker.sampleAlert(s) : alertWith(s, reason);
        return AlertFormatter.format(p, a, WHEN, "", "", AlertFormatter.Shot.none(), allowPing);
    }

    /** Drives the tracker into the wanted reason, since Alert's constructor is private. */
    private static ThreatTracker.Alert alertWith(ThreatTracker.Sighting s, ThreatTracker.Reason reason) {
        ThreatTracker t = new ThreatTracker(30_000, 10_000, 0, java.util.EnumSet.allOf(WatchCategory.class));
        if (reason == ThreatTracker.Reason.REPEAT) {
            t.update(0, java.util.Collections.singletonList(s));
            return t.update(10_000, java.util.Collections.singletonList(s)).get(0);
        }
        ThreatTracker.Sighting milder = new ThreatTracker.Sighting(s.gobId, WatchCategory.WHITE, s.name, null);
        t.update(0, java.util.Collections.singletonList(milder));
        return t.update(1000, java.util.Collections.singletonList(s)).get(0);
    }

    private static JSONObject payload(DiscordMessage m) {
        return m.payload(false);
    }

    @Test
    void default_profiles_do_not_ping_and_keep_mentions_fully_disabled() {
        for (WatchCategory c : WatchCategory.values()) {
            assertEquals(PingTarget.NONE, new WatchProfile("N").category(c).ping);
            JSONObject json = payload(msg(new WatchProfile("N"), c, "Bob", ThreatTracker.Reason.NEW, true));
            assertFalse(json.has("content"));
            assertEquals(0, json.getJSONObject("allowed_mentions").getJSONArray("parse").length());
            assertFalse(json.getJSONObject("allowed_mentions").has("roles") || json.getJSONObject("allowed_mentions").has("users"));
        }
    }

    @Test
    void here_and_everyone_put_the_mention_in_top_level_content_and_permit_only_everyone() {
        for (PingTarget t : new PingTarget[]{PingTarget.HERE, PingTarget.EVERYONE}) {
            JSONObject json = payload(msg(profile(WatchCategory.RED, t, ""), WatchCategory.RED, "Mallory", ThreatTracker.Reason.NEW, true));
            assertTrue(json.getString("content").startsWith(t.label + " "), json.getString("content"));
            assertEquals("everyone", json.getJSONObject("allowed_mentions").getJSONArray("parse").getString(0));
            assertEquals(1, json.getJSONObject("allowed_mentions").getJSONArray("parse").length());
            assertFalse(json.getJSONObject("allowed_mentions").has("roles"));
        }
    }

    @Test
    void a_role_is_mentioned_by_id_and_only_that_role_is_permitted() {
        JSONObject json = payload(msg(profile(WatchCategory.RED, PingTarget.ROLE, ROLE), WatchCategory.RED, "Mallory", ThreatTracker.Reason.NEW, true));
        assertTrue(json.getString("content").startsWith("<@&" + ROLE + "> "));
        JSONObject allowed = json.getJSONObject("allowed_mentions");
        assertEquals(0, allowed.getJSONArray("parse").length());
        assertEquals(ROLE, allowed.getJSONArray("roles").getString(0));
        assertFalse(allowed.has("users"));
    }

    @Test
    void a_user_is_mentioned_by_id_and_only_that_user_is_permitted() {
        JSONObject json = payload(msg(profile(WatchCategory.UNKNOWN, PingTarget.USER, USER), WatchCategory.UNKNOWN, null, ThreatTracker.Reason.NEW, true));
        assertTrue(json.getString("content").startsWith("<@" + USER + "> "));
        JSONObject allowed = json.getJSONObject("allowed_mentions");
        assertEquals(0, allowed.getJSONArray("parse").length());
        assertEquals(USER, allowed.getJSONArray("users").getString(0));
        assertFalse(allowed.has("roles"));
    }

    @Test
    void the_embed_template_and_attachment_are_kept_alongside_the_ping() {
        WatchProfile p = profile(WatchCategory.RED, PingTarget.HERE, "");
        p.category(WatchCategory.RED).template = "RED {player}";
        ThreatTracker.Alert a = ThreatTracker.sampleAlert(new ThreatTracker.Sighting(5, WatchCategory.RED, "Mallory", null));
        DiscordMessage m = AlertFormatter.format(p, a, WHEN, "", "",
                new AlertFormatter.Shot(new byte[]{1, 2}, null, null), true);
        JSONObject json = m.payload(true);
        assertEquals("RED Mallory", json.getJSONArray("embeds").getJSONObject(0).getString("description"));
        assertEquals("attachment://" + m.attachment.filename, json.getJSONArray("embeds").getJSONObject(0).getJSONObject("image").getString("url"));
        assertTrue(json.getString("content").startsWith("@here"));
        assertTrue(new String(m.body(true, "B"), StandardCharsets.ISO_8859_1).contains("name=\"payload_json\""));
    }

    @Test
    void player_names_and_templates_cannot_inject_a_mention() {
        WatchProfile p = new WatchProfile("N");
        p.watcher = "@everyone <@&" + ROLE + ">";
        p.category(WatchCategory.RED).template = "@everyone {player} <@" + USER + ">";
        JSONObject json = payload(msg(p, WatchCategory.RED, "@everyone @here <@&" + ROLE + ">", ThreatTracker.Reason.NEW, true));
        assertFalse(json.has("content"));
        assertEquals(0, json.getJSONObject("allowed_mentions").getJSONArray("parse").length());

        // And with a ping configured, the content holds only the fixed phrase, none of the injected text.
        WatchProfile pinging = profile(WatchCategory.RED, PingTarget.USER, USER);
        pinging.watcher = "@everyone";
        JSONObject json2 = payload(msg(pinging, WatchCategory.RED, "@everyone", ThreatTracker.Reason.NEW, true));
        String content = json2.getString("content");
        assertFalse(content.contains("@everyone") || content.contains("@here"));
        assertEquals(0, json2.getJSONObject("allowed_mentions").getJSONArray("parse").length());
    }

    @Test
    void only_new_sightings_and_escalations_ping_never_cooldown_repeats() {
        WatchProfile p = profile(WatchCategory.RED, PingTarget.EVERYONE, "");
        assertTrue(payload(msg(p, WatchCategory.RED, "M", ThreatTracker.Reason.NEW, true)).has("content"));
        assertTrue(payload(msg(p, WatchCategory.RED, "M", ThreatTracker.Reason.ESCALATION, true)).has("content"));
        JSONObject repeat = payload(msg(p, WatchCategory.RED, "M", ThreatTracker.Reason.REPEAT, true));
        assertFalse(repeat.has("content"));
        assertEquals(0, repeat.getJSONObject("allowed_mentions").getJSONArray("parse").length());
    }

    @Test
    void each_category_pings_its_own_target() {
        WatchProfile p = new WatchProfile("N");
        p.category(WatchCategory.RED).ping = PingTarget.EVERYONE;
        p.category(WatchCategory.WHITE).ping = PingTarget.ROLE;
        p.category(WatchCategory.WHITE).pingId = ROLE;
        assertFalse(payload(msg(p, WatchCategory.UNKNOWN, null, ThreatTracker.Reason.NEW, true)).has("content"));
        assertTrue(payload(msg(p, WatchCategory.WHITE, "W", ThreatTracker.Reason.NEW, true)).getString("content").startsWith("<@&" + ROLE));
        assertTrue(payload(msg(p, WatchCategory.RED, "R", ThreatTracker.Reason.NEW, true)).getString("content").startsWith("@everyone"));
    }

    @Test
    void test_messages_never_ping_even_when_a_target_is_configured() {
        WatchProfile p = profile(WatchCategory.UNKNOWN, PingTarget.EVERYONE, "");
        JSONObject json = payload(msg(p, WatchCategory.UNKNOWN, null, ThreatTracker.Reason.NEW, false));
        assertFalse(json.has("content"));
        assertEquals(0, json.getJSONObject("allowed_mentions").getJSONArray("parse").length());
    }

    @Test
    void a_role_or_user_target_with_a_bad_id_fails_quiet() {
        for (String bad : new String[]{"", "abc", "12345", "1234567890123456789012", "<@&123456789012345678>", null}) {
            WatchProfile p = profile(WatchCategory.RED, PingTarget.ROLE, bad);
            JSONObject json = payload(msg(p, WatchCategory.RED, "M", ThreatTracker.Reason.NEW, true));
            assertFalse(json.has("content"), String.valueOf(bad));
            assertEquals(0, json.getJSONObject("allowed_mentions").getJSONArray("parse").length());
            assertNull(PingTarget.ROLE.mention(bad));
        }
        assertTrue(PingTarget.validId(ROLE) && PingTarget.validId(" " + USER + " "));
    }

    @Test
    void ping_settings_persist_and_old_profiles_load_as_no_ping(@TempDir Path dir) throws IOException {
        WatchProfileStore store = new WatchProfileStore(dir.resolve("vw.json"));
        WatchProfile p = new WatchProfile("North Watch");
        p.category(WatchCategory.RED).ping = PingTarget.ROLE;
        p.category(WatchCategory.RED).pingId = ROLE;
        p.category(WatchCategory.UNKNOWN).ping = PingTarget.HERE;
        store.save(p);
        WatchProfile back = new WatchProfileStore(dir.resolve("vw.json")).get("North Watch");
        assertEquals(PingTarget.ROLE, back.category(WatchCategory.RED).ping);
        assertEquals(ROLE, back.category(WatchCategory.RED).pingId);
        assertEquals(PingTarget.HERE, back.category(WatchCategory.UNKNOWN).ping);
        assertEquals(PingTarget.NONE, back.category(WatchCategory.WHITE).ping);

        // A profile file written before this feature existed has no ping keys at all.
        JSONObject cat = new JSONObject().put("enabled", true).put("template", "old");
        JSONObject legacy = new JSONObject().put("profiles", new JSONObject().put("Old",
                new JSONObject().put("categories", new JSONObject().put("red", cat))));
        Files.write(dir.resolve("legacy.json"), legacy.toString().getBytes(StandardCharsets.UTF_8));
        WatchProfile old = new WatchProfileStore(dir.resolve("legacy.json")).get("Old");
        for (WatchCategory c : WatchCategory.values())
            assertEquals(PingTarget.NONE, old.category(c).ping);
    }

    @Test
    void a_hand_edited_role_ping_without_a_valid_id_loads_as_no_ping(@TempDir Path dir) throws IOException {
        JSONObject cat = new JSONObject().put("ping", "role").put("pingId", "nope");
        JSONObject file = new JSONObject().put("profiles", new JSONObject().put("Odd",
                new JSONObject().put("categories", new JSONObject().put("red", cat))));
        Files.write(dir.resolve("odd.json"), file.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(PingTarget.NONE, new WatchProfileStore(dir.resolve("odd.json")).get("Odd").category(WatchCategory.RED).ping);
    }

    @Test
    void copies_of_a_profile_keep_their_own_ping_settings() {
        WatchProfile a = profile(WatchCategory.RED, PingTarget.EVERYONE, "");
        WatchProfile b = a.copy();
        b.category(WatchCategory.RED).ping = PingTarget.NONE;
        assertEquals(PingTarget.EVERYONE, a.category(WatchCategory.RED).ping);
    }
}
