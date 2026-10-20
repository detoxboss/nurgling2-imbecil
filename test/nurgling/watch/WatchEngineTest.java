package nurgling.watch;

import nurgling.notifications.DiscordMessage;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchEngineTest {
    /** A clock the test moves by hand. */
    private static final class Manual extends Clock {
        long ms = 1_700_000_000_000L;

        Manual advance(long sec) {
            ms += sec * 1000;
            return this;
        }

        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return Instant.ofEpochMilli(ms);
        }
    }

    private static final class FakeShots implements ScreenshotProvider {
        String unavailable;
        Result result = Result.ok(new byte[]{1, 2, 3});
        boolean interrupt;
        int captures;
        Boolean inFrame;

        public Result capture(long timeoutMs) throws InterruptedException {
            captures++;
            if (interrupt)
                throw new InterruptedException();
            return result;
        }

        public String unavailableReason() {
            return unavailable;
        }

        public Boolean inFrame(long gobId) {
            return inFrame;
        }
    }

    private final List<DiscordMessage> sent = new ArrayList<>();
    private final Manual clock = new Manual();

    private WatchEngine engine(WatchProfile p, FakeShots shots) {
        return new WatchEngine(p, shots, (m, a) -> sent.add(m), clock, "Nurgling", "");
    }

    private static ThreatTracker.Sighting red(long id) {
        return new ThreatTracker.Sighting(id, WatchCategory.RED, "Mallory", "(10, 20)");
    }

    private static List<ThreatTracker.Sighting> list(ThreatTracker.Sighting... s) {
        return Arrays.asList(s);
    }

    private static JSONObject embed(DiscordMessage m, boolean withAttachment) {
        return m.payload(withAttachment).getJSONArray("embeds").getJSONObject(0);
    }

    @Test
    void a_threat_produces_one_alert_carrying_the_rendered_template_and_a_screenshot() throws Exception {
        WatchProfile p = new WatchProfile("Southeast Watch");
        p.watcher = "Southeast Wall";
        FakeShots shots = new FakeShots();
        assertEquals(1, engine(p, shots).scan(list(red(5))));
        DiscordMessage m = sent.get(0);
        String d = embed(m, true).getString("description");
        assertTrue(d.contains("Location: Southeast Wall"));
        assertTrue(d.contains("Player: Mallory"));
        assertTrue(d.contains("Status: Red kin group"));
        assertTrue(m.hasAttachment());
        assertTrue(embed(m, true).has("image"));
        assertEquals(1, shots.captures);
    }

    @Test
    void unknown_players_are_named_unknown_never_invented() throws Exception {
        WatchProfile p = new WatchProfile("N");
        FakeShots shots = new FakeShots();
        WatchEngine e = engine(p, shots);
        ThreatTracker.Sighting u = new ThreatTracker.Sighting(9, WatchCategory.UNKNOWN, null, null);
        e.scan(list(u));
        assertEquals(0, sent.size()); // still settling
        clock.advance(4);
        e.scan(list(u));
        assertEquals(1, sent.size());
        String d = embed(sent.get(0), true).getString("description");
        assertTrue(d.contains("Player: Unknown"));
        assertTrue(d.contains("Unknown (not memorized)"));
        assertFalse(embed(sent.get(0), true).getJSONArray("fields").toString().contains("Position"));
    }

    @Test
    void the_screenshot_failing_still_sends_the_alert_text_only_and_says_why() throws Exception {
        FakeShots shots = new FakeShots();
        shots.result = ScreenshotProvider.Result.failed("no frame was drawn within 5s");
        assertEquals(1, engine(new WatchProfile("N"), shots).scan(list(red(1))));
        DiscordMessage m = sent.get(0);
        assertFalse(m.hasAttachment());
        assertTrue(embed(m, false).getJSONArray("fields").toString().contains("no frame was drawn"));
    }

    @Test
    void a_background_session_sends_text_alerts_without_ever_asking_for_a_capture() throws Exception {
        FakeShots shots = new FakeShots();
        shots.unavailable = "this session is in the background and is not rendered";
        assertEquals(1, engine(new WatchProfile("N"), shots).scan(list(red(1))));
        assertEquals(0, shots.captures);
        assertFalse(sent.get(0).hasAttachment());
        assertTrue(embed(sent.get(0), false).getJSONArray("fields").toString().contains("not rendered"));
    }

    @Test
    void screenshots_switched_off_means_no_capture_and_no_noise_about_it() throws Exception {
        WatchProfile p = new WatchProfile("N");
        p.attachScreenshot = false;
        FakeShots shots = new FakeShots();
        engine(p, shots).scan(list(red(1)));
        assertEquals(0, shots.captures);
        assertFalse(sent.get(0).hasAttachment());
        assertFalse(embed(sent.get(0), false).getJSONArray("fields").toString().contains("Screenshot"));
    }

    @Test
    void one_capture_is_shared_by_every_alert_raised_in_the_same_scan() throws Exception {
        FakeShots shots = new FakeShots();
        assertEquals(2, engine(new WatchProfile("N"), shots).scan(list(red(1), red(2))));
        assertEquals(1, shots.captures);
        assertTrue(sent.get(0).hasAttachment() && sent.get(1).hasAttachment());
    }

    @Test
    void a_scan_with_nothing_new_does_not_capture_or_send() throws Exception {
        FakeShots shots = new FakeShots();
        WatchEngine e = engine(new WatchProfile("N"), shots);
        e.scan(list(red(1)));
        sent.clear();
        shots.captures = 0;
        clock.advance(2);
        assertEquals(0, e.scan(list(red(1))));
        assertEquals(0, shots.captures);
        assertTrue(sent.isEmpty());
    }

    @Test
    void a_threat_outside_the_camera_view_is_flagged_on_the_alert() throws Exception {
        FakeShots shots = new FakeShots();
        shots.inFrame = Boolean.FALSE;
        engine(new WatchProfile("N"), shots).scan(list(red(1)));
        assertTrue(embed(sent.get(0), true).getJSONArray("fields").toString().contains("outside the camera view"));
    }

    @Test
    void position_is_added_as_a_field_only_when_known_and_not_already_in_the_template() throws Exception {
        WatchProfile p = new WatchProfile("N");
        FakeShots shots = new FakeShots();
        engine(p, shots).scan(list(red(1)));
        assertTrue(embed(sent.get(0), true).getJSONArray("fields").toString().contains("(10, 20)"));

        sent.clear();
        p.category(WatchCategory.RED).template = "at {coords}";
        engine(p, shots).scan(list(red(2)));
        assertEquals("at (10, 20)", embed(sent.get(0), true).getString("description"));
        assertFalse(embed(sent.get(0), true).getJSONArray("fields").toString().contains("Position"));
    }

    @Test
    void each_category_uses_its_own_template() throws Exception {
        WatchProfile p = new WatchProfile("N");
        p.category(WatchCategory.WHITE).template = "WHITE {player}";
        p.category(WatchCategory.RED).template = "RED {player}";
        WatchEngine e = engine(p, new FakeShots());
        e.scan(list(new ThreatTracker.Sighting(1, WatchCategory.WHITE, "Wanda", null), red(2)));
        assertEquals("WHITE Wanda", embed(sent.get(0), true).getString("description"));
        assertEquals("RED Mallory", embed(sent.get(1), true).getString("description"));
    }

    @Test
    void titles_distinguish_escalations_and_repeats() throws Exception {
        WatchProfile p = new WatchProfile("N");
        p.cooldownSec = 10;
        WatchEngine e = engine(p, new FakeShots());
        e.scan(list(new ThreatTracker.Sighting(1, WatchCategory.WHITE, "W", null)));
        clock.advance(2);
        e.scan(list(new ThreatTracker.Sighting(1, WatchCategory.RED, "W", null)));
        assertTrue(embed(sent.get(1), true).getString("title").contains("escalated"));
        clock.advance(11);
        e.scan(list(new ThreatTracker.Sighting(1, WatchCategory.RED, "W", null)));
        assertTrue(embed(sent.get(2), true).getString("title").contains("still present"));
    }

    @Test
    void interruption_during_capture_propagates_so_the_bot_stops_cleanly() {
        FakeShots shots = new FakeShots();
        shots.interrupt = true;
        assertThrows(InterruptedException.class, () -> engine(new WatchProfile("N"), shots).scan(list(red(1))));
        assertTrue(sent.isEmpty());
    }

    @Test
    void two_lookouts_with_different_profiles_do_not_share_state_or_wording() throws Exception {
        WatchProfile southeast = new WatchProfile("Southeast Watch");
        southeast.watcher = "Southeast Wall";
        WatchProfile north = new WatchProfile("North Watch");
        north.watcher = "North Gate";
        List<DiscordMessage> seOut = new ArrayList<>();
        List<DiscordMessage> nOut = new ArrayList<>();
        WatchEngine se = new WatchEngine(southeast, new FakeShots(), (m, a) -> seOut.add(m), clock, "", "");
        WatchEngine n = new WatchEngine(north, new FakeShots(), (m, a) -> nOut.add(m), clock, "", "");
        se.scan(list(red(7)));
        n.scan(list(red(7))); // same gob id, different session
        assertEquals(1, seOut.size());
        assertEquals(1, nOut.size());
        assertTrue(embed(seOut.get(0), true).getString("description").contains("Southeast Wall"));
        assertTrue(embed(nOut.get(0), true).getString("description").contains("North Gate"));
        assertEquals(1, se.tracker().trackedCount());
        assertEquals(1, n.tracker().trackedCount());
    }
}
