package nurgling.watch;

import nurgling.watch.ThreatTracker.Alert;
import nurgling.watch.ThreatTracker.Reason;
import nurgling.watch.ThreatTracker.Sighting;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreatTrackerTest {
    private static final long S = 1000;
    private static final Set<WatchCategory> ALL = EnumSet.allOf(WatchCategory.class);

    /** 30 s grace, 300 s cooldown, 3 s unknown settle - the shipped defaults. */
    private static ThreatTracker tracker() {
        return new ThreatTracker(30 * S, 300 * S, 3 * S, ALL);
    }

    private static Sighting seen(long id, WatchCategory c) {
        return new Sighting(id, c, c == WatchCategory.UNKNOWN ? null : "Name" + id, null);
    }

    private static List<Alert> at(ThreatTracker t, long sec, Sighting... s) {
        return t.update(sec * S, Arrays.asList(s));
    }

    @Test
    void white_and_red_are_reported_on_first_sight() {
        ThreatTracker t = tracker();
        List<Alert> a = at(t, 0, seen(1, WatchCategory.WHITE), seen(2, WatchCategory.RED));
        assertEquals(2, a.size());
        assertEquals(Reason.NEW, a.get(0).reason);
        assertEquals(1, a.get(0).sighting.gobId);
        assertEquals(2, a.get(1).sighting.gobId);
    }

    @Test
    void unknown_player_is_reported_only_after_it_has_stayed_unknown_for_the_settle_time() {
        ThreatTracker t = tracker();
        assertTrue(at(t, 0, seen(1, WatchCategory.UNKNOWN)).isEmpty());
        assertTrue(at(t, 2, seen(1, WatchCategory.UNKNOWN)).isEmpty());
        assertEquals(1, at(t, 3, seen(1, WatchCategory.UNKNOWN)).size());
    }

    @Test
    void unknown_that_turns_out_to_be_memorized_before_settling_is_never_reported_as_unknown() {
        ThreatTracker t = tracker();
        assertTrue(at(t, 0, seen(1, WatchCategory.UNKNOWN)).isEmpty());
        // The buddy record arrives: now white. Reported once, as white.
        List<Alert> a = at(t, 1, seen(1, WatchCategory.WHITE));
        assertEquals(1, a.size());
        assertEquals(WatchCategory.WHITE, a.get(0).sighting.category);
    }

    @Test
    void continuous_presence_does_not_repeat_until_the_cooldown_passes() {
        ThreatTracker t = tracker();
        assertEquals(1, at(t, 0, seen(1, WatchCategory.RED)).size());
        for (long sec = 2; sec < 300; sec += 2)
            assertTrue(at(t, sec, seen(1, WatchCategory.RED)).isEmpty(), "scan at " + sec);
        List<Alert> repeat = at(t, 300, seen(1, WatchCategory.RED));
        assertEquals(1, repeat.size());
        assertEquals(Reason.REPEAT, repeat.get(0).reason);
        assertTrue(at(t, 302, seen(1, WatchCategory.RED)).isEmpty());
    }

    @Test
    void a_short_disappearance_within_grace_is_the_same_sighting() {
        ThreatTracker t = tracker();
        assertEquals(1, at(t, 0, seen(1, WatchCategory.RED)).size());
        assertTrue(at(t, 10).isEmpty());
        assertTrue(at(t, 20, seen(1, WatchCategory.RED)).isEmpty());
        assertEquals(1, t.trackedCount());
    }

    @Test
    void reappearing_after_the_grace_is_a_new_sighting_reported_immediately_despite_the_old_cooldown() {
        ThreatTracker t = tracker();
        assertEquals(1, at(t, 0, seen(1, WatchCategory.RED)).size());
        at(t, 10);
        at(t, 31); // 31 s since last seen: expired
        assertEquals(0, t.trackedCount());
        List<Alert> a = at(t, 40, seen(1, WatchCategory.RED));
        assertEquals(1, a.size());
        assertEquals(Reason.NEW, a.get(0).reason);
    }

    @Test
    void escalation_is_reported_at_once_even_during_the_cooldown() {
        ThreatTracker t = tracker();
        assertEquals(1, at(t, 0, seen(1, WatchCategory.WHITE)).size());
        List<Alert> a = at(t, 2, seen(1, WatchCategory.RED));
        assertEquals(1, a.size());
        assertEquals(Reason.ESCALATION, a.get(0).reason);
        assertEquals(WatchCategory.RED, a.get(0).sighting.category);
    }

    @Test
    void de_escalation_is_quiet_and_a_later_return_to_the_worse_group_escalates_again() {
        ThreatTracker t = tracker();
        assertEquals(1, at(t, 0, seen(1, WatchCategory.RED)).size());
        assertTrue(at(t, 2, seen(1, WatchCategory.WHITE)).isEmpty());
        List<Alert> back = at(t, 4, seen(1, WatchCategory.RED));
        assertEquals(1, back.size());
        assertEquals(Reason.ESCALATION, back.get(0).reason);
    }

    @Test
    void a_disabled_category_never_alerts_but_enabling_applies_per_category() {
        ThreatTracker t = new ThreatTracker(30 * S, 300 * S, 3 * S, EnumSet.of(WatchCategory.RED));
        assertTrue(at(t, 0, seen(1, WatchCategory.WHITE), seen(2, WatchCategory.UNKNOWN)).isEmpty());
        assertTrue(at(t, 10, seen(2, WatchCategory.UNKNOWN)).isEmpty());
        assertEquals(1, at(t, 12, seen(3, WatchCategory.RED)).size());
    }

    @Test
    void a_player_changing_into_an_enabled_category_is_reported_as_new() {
        ThreatTracker t = new ThreatTracker(30 * S, 300 * S, 3 * S, EnumSet.of(WatchCategory.RED));
        assertTrue(at(t, 0, seen(1, WatchCategory.WHITE)).isEmpty());
        List<Alert> a = at(t, 2, seen(1, WatchCategory.RED));
        assertEquals(1, a.size());
        assertEquals(Reason.NEW, a.get(0).reason);
    }

    @Test
    void several_simultaneous_players_alert_independently_and_in_id_order() {
        ThreatTracker t = tracker();
        List<Alert> a = at(t, 0, seen(30, WatchCategory.RED), seen(10, WatchCategory.WHITE), seen(20, WatchCategory.RED));
        assertEquals(Arrays.asList(10L, 20L, 30L), Arrays.asList(a.get(0).sighting.gobId, a.get(1).sighting.gobId, a.get(2).sighting.gobId));
        assertTrue(at(t, 2, seen(30, WatchCategory.RED), seen(10, WatchCategory.WHITE), seen(20, WatchCategory.RED)).isEmpty());
        assertEquals(1, at(t, 4, seen(30, WatchCategory.RED), seen(10, WatchCategory.WHITE), seen(20, WatchCategory.RED), seen(40, WatchCategory.RED)).size());
    }

    @Test
    void trackers_are_isolated_so_the_same_gob_id_in_two_sessions_alerts_in_both() {
        ThreatTracker southeast = tracker();
        ThreatTracker north = tracker();
        assertEquals(1, at(southeast, 0, seen(77, WatchCategory.RED)).size());
        assertEquals(1, at(north, 1, seen(77, WatchCategory.RED)).size());
        assertEquals(1, southeast.trackedCount());
        assertEquals(1, north.trackedCount());
    }

    @Test
    void memory_is_bounded_and_drops_the_least_recently_seen() {
        ThreatTracker t = new ThreatTracker(3600 * S, 300 * S, 3 * S, ALL);
        for (int i = 0; i < ThreatTracker.MAX_TRACKED + 50; i++) {
            List<Sighting> one = new ArrayList<>(Collections.singletonList(seen(i, WatchCategory.WHITE)));
            t.update(i * 10L, one);
        }
        assertEquals(ThreatTracker.MAX_TRACKED, t.trackedCount());
    }

    @Test
    void clear_forgets_everything_so_a_restarted_bot_alerts_again() {
        ThreatTracker t = tracker();
        at(t, 0, seen(1, WatchCategory.RED));
        t.clear();
        assertEquals(1, at(t, 2, seen(1, WatchCategory.RED)).size());
    }

    @Test
    void the_profile_constructor_uses_the_profile_timings_and_switches() {
        WatchProfile p = new WatchProfile("North");
        p.absenceGraceSec = 5;
        p.cooldownSec = 10;
        p.category(WatchCategory.WHITE).enabled = false;
        ThreatTracker t = new ThreatTracker(p);
        assertTrue(at(t, 0, seen(1, WatchCategory.WHITE)).isEmpty());
        assertEquals(1, at(t, 0, seen(2, WatchCategory.RED)).size());
        assertTrue(at(t, 9, seen(2, WatchCategory.RED)).isEmpty());
        assertEquals(1, at(t, 10, seen(2, WatchCategory.RED)).size());
    }
}
