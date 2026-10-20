package nurgling.watch;

import nurgling.watch.WatchClassifier.Observation;
import nurgling.watch.WatchClassifier.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WatchClassifierTest {
    private static Observation memorized(Integer group) {
        Observation o = new Observation();
        o.hasBuddyAttr = true;
        o.group = group;
        return o;
    }

    @Test
    void player_without_buddy_attribute_is_unknown() {
        assertEquals(Verdict.UNKNOWN, WatchClassifier.classify(new Observation()));
    }

    @Test
    void group_zero_is_white_and_is_not_unknown() {
        assertEquals(Verdict.WHITE, WatchClassifier.classify(memorized(0)));
    }

    @Test
    void group_two_is_red() {
        assertEquals(Verdict.RED, WatchClassifier.classify(memorized(2)));
    }

    @Test
    void other_kin_groups_never_alert() {
        for (int g : new int[]{1, 3, 4, 5, 9}) {
            Verdict v = WatchClassifier.classify(memorized(g));
            assertEquals(Verdict.OTHER_GROUP, v);
            assertNull(v.category());
        }
    }

    @Test
    void buddy_attribute_with_record_not_loaded_is_pending_not_unknown() {
        assertEquals(Verdict.PENDING, WatchClassifier.classify(memorized(null)));
    }

    @Test
    void buddy_list_not_loaded_yet_is_pending_even_with_a_group() {
        Observation o = memorized(0);
        o.buddyListLoaded = false;
        assertEquals(Verdict.PENDING, WatchClassifier.classify(o));
    }

    @Test
    void model_still_loading_is_pending() {
        Observation o = new Observation();
        o.modelLoaded = false;
        assertEquals(Verdict.PENDING, WatchClassifier.classify(o));
        o = memorized(2);
        o.modelLoaded = false;
        assertEquals(Verdict.PENDING, WatchClassifier.classify(o));
    }

    @Test
    void unmemorized_player_waits_for_the_map_to_finish_loading() {
        Observation o = new Observation();
        o.mapLoaded = false;
        assertEquals(Verdict.PENDING, WatchClassifier.classify(o));
    }

    @Test
    void memorized_player_does_not_need_the_full_map() {
        Observation o = memorized(2);
        o.mapLoaded = false;
        assertEquals(Verdict.RED, WatchClassifier.classify(o));
    }

    @Test
    void self_party_dead_and_mannequins_are_ignored_whatever_their_data() {
        Observation o = new Observation();
        o.live = false;
        assertEquals(Verdict.IGNORE, WatchClassifier.classify(o));
        o = memorized(2);
        o.live = false;
        assertEquals(Verdict.IGNORE, WatchClassifier.classify(o));
    }

    @Test
    void verdicts_map_to_categories() {
        assertEquals(WatchCategory.UNKNOWN, Verdict.UNKNOWN.category());
        assertEquals(WatchCategory.WHITE, Verdict.WHITE.category());
        assertEquals(WatchCategory.RED, Verdict.RED.category());
        assertNull(Verdict.PENDING.category());
        assertNull(Verdict.IGNORE.category());
    }
}
