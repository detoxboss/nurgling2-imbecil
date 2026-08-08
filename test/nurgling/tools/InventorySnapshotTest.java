package nurgling.tools;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for InventorySnapshot's diff()/classifyCursor()/sameState() logic - the actual
 * ownership decision-making LpAssistantBot's first-product detection, settlement, and cleanup all
 * share. Snapshots are built via InventorySnapshot.of()/entry() rather than capture(), since
 * capture() needs a live haven Widget/GItem/ItemStack tree that this test harness doesn't have -
 * see of()'s own doc. Kept deliberately thin on traversal and focused on the decision logic itself.
 *
 * Round 7: {@code allUnitIds} mirrors capture()'s own convention - a loose item's own wdgid is a
 * unit; a stack CONTAINER's wdgid is never itself a unit, only its children's wdgids are. Ownership
 * is decided against {@code baseline.allUnitIds} (the identity universe), not container shape - see
 * InventorySnapshot's class doc.
 *
 * Round 7b: a stack's physical child set (every child actually in the container) and its matching
 * child set (the subset confirmed to match this snapshot's alias) are tracked separately - see
 * InventorySnapshot.TopLevelEntry's own doc. Most tests below don't care about that distinction
 * (every physical child is also a confirmed match), so the local {@code entry()} helper passes the
 * same set for both; tests that specifically exercise the distinction call
 * {@code InventorySnapshot.entry(wdgid, isStack, physical, matching)} directly with different sets.
 */
class InventorySnapshotTest {

    private static InventorySnapshot snap(Map<Integer, InventorySnapshot.TopLevelEntry> top, Set<Integer> allUnitIds,
                                           InventorySnapshot.CursorState cursorState, Integer cursorWdgid) {
        return InventorySnapshot.of(top, allUnitIds, cursorState, cursorWdgid);
    }

    /** Convenience wrapper for tests where physical and matching children are the same set. */
    private static InventorySnapshot.TopLevelEntry entry(int wdgid, boolean isStack, Set<Integer> childWdgids) {
        return InventorySnapshot.entry(wdgid, isStack, childWdgids, childWdgids);
    }

    @Test
    void unchanged_pre_existing_stack_produces_no_new_output() {
        InventorySnapshot.TopLevelEntry stack = entry(1, true, Set.of(101, 102));
        InventorySnapshot baseline = snap(Map.of(1, stack), Set.of(101, 102), InventorySnapshot.CursorState.EMPTY, null);
        InventorySnapshot settled = snap(Map.of(1, stack), Set.of(101, 102), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.Delta delta = settled.diff(baseline);

        assertTrue(delta.isEmpty());
        assertTrue(delta.ambiguousTopLevel.isEmpty());
    }

    @Test
    void one_new_child_in_pre_existing_stack_produces_exactly_that_owned_child() {
        InventorySnapshot.TopLevelEntry before = entry(1, true, Set.of(101, 102));
        InventorySnapshot.TopLevelEntry after = entry(1, true, Set.of(101, 102, 103));
        InventorySnapshot baseline = snap(Map.of(1, before), Set.of(101, 102), InventorySnapshot.CursorState.EMPTY, null);
        InventorySnapshot settled = snap(Map.of(1, after), Set.of(101, 102, 103), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.Delta delta = settled.diff(baseline);

        assertTrue(delta.newLooseItems.isEmpty());
        assertTrue(delta.newWholeStacks.isEmpty());
        assertEquals(Map.of(1, Set.of(103)), delta.newChildrenInStacks);
    }

    @Test
    void new_separate_stack_identifies_all_of_its_units_as_owned() {
        InventorySnapshot baseline = snap(Map.of(), Set.of(), InventorySnapshot.CursorState.EMPTY, null);
        InventorySnapshot.TopLevelEntry newStack = entry(2, true, Set.of(201, 202, 203));
        InventorySnapshot settled = snap(Map.of(2, newStack), Set.of(201, 202, 203), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.Delta delta = settled.diff(baseline);

        // The whole new container is reported keyed to its own wdgid, with every one of its units
        // (201/202/203) listed - safe to drop as ONE message (see LpAssistantBot.
        // dropHarvestWidgets()), with the child ids preserved for confirmation/recovery.
        assertEquals(Map.of(2, Set.of(201, 202, 203)), delta.newWholeStacks);
        assertTrue(delta.newLooseItems.isEmpty());
        assertTrue(delta.newChildrenInStacks.isEmpty());
    }

    @Test
    void new_loose_item_is_owned() {
        InventorySnapshot baseline = snap(Map.of(), Set.of(), InventorySnapshot.CursorState.EMPTY, null);
        InventorySnapshot.TopLevelEntry loose = entry(5, false, Set.of());
        InventorySnapshot settled = snap(Map.of(5, loose), Set.of(5), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.Delta delta = settled.diff(baseline);

        assertEquals(Set.of(5), delta.newLooseItems);
        assertTrue(delta.newWholeStacks.isEmpty());
    }

    @Test
    void ambiguous_identity_replacement_fails_safe() {
        // Same top-level wdgid, but its loose/stack shape flipped between snapshots (a client-side
        // collapse/re-parenting edge case ON THE SAME CONTAINER IDENTITY) - ownership of its
        // contents can't be safely established either way, so it must be reported as ambiguous,
        // never folded into a "new" map.
        InventorySnapshot.TopLevelEntry wasLoose = entry(9, false, Set.of());
        InventorySnapshot.TopLevelEntry nowStack = entry(9, true, Set.of(901));
        InventorySnapshot baseline = snap(Map.of(9, wasLoose), Set.of(9), InventorySnapshot.CursorState.EMPTY, null);
        InventorySnapshot settled = snap(Map.of(9, nowStack), Set.of(901), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.Delta delta = settled.diff(baseline);

        assertTrue(delta.isEmpty());
        assertEquals(Set.of(9), delta.ambiguousTopLevel);
    }

    @Test
    void stack_collapse_does_not_cause_a_pre_existing_unit_to_become_owned() {
        // A pre-existing stack that LOSES a child (collapse/split) must never report that removal
        // as new ownership of anything - diff() only ever reports additions.
        InventorySnapshot.TopLevelEntry before = entry(1, true, Set.of(101, 102, 103));
        InventorySnapshot.TopLevelEntry after = entry(1, true, Set.of(101));
        InventorySnapshot baseline = snap(Map.of(1, before), Set.of(101, 102, 103), InventorySnapshot.CursorState.EMPTY, null);
        InventorySnapshot settled = snap(Map.of(1, after), Set.of(101), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.Delta delta = settled.diff(baseline);

        assertTrue(delta.isEmpty());
        assertTrue(delta.ambiguousTopLevel.isEmpty());
    }

    // ---- Round 7: identity-universe re-parenting tests ----

    @Test
    void pre_existing_stack_child_becoming_loose_top_level_remains_pre_existing() {
        InventorySnapshot.TopLevelEntry stack = entry(1, true, Set.of(101, 102));
        InventorySnapshot baseline = snap(Map.of(1, stack), Set.of(101, 102), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.TopLevelEntry nowLoose = entry(101, false, Set.of());
        InventorySnapshot settled = snap(Map.of(101, nowLoose), Set.of(101), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.Delta delta = settled.diff(baseline);

        assertTrue(delta.isEmpty(), "unit 101 was a pre-existing stack child and must remain pre-existing as a loose item");
        assertTrue(delta.ambiguousTopLevel.isEmpty());
    }

    @Test
    void pre_existing_loose_becoming_child_of_new_stack_container_remains_pre_existing() {
        InventorySnapshot.TopLevelEntry loose = entry(101, false, Set.of());
        InventorySnapshot baseline = snap(Map.of(101, loose), Set.of(101), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.TopLevelEntry newContainer = entry(5, true, Set.of(101));
        InventorySnapshot settled = snap(Map.of(5, newContainer), Set.of(101), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.Delta delta = settled.diff(baseline);

        assertTrue(delta.isEmpty(), "unit 101 was pre-existing loose and must remain pre-existing as a stack child");
        assertTrue(delta.ambiguousTopLevel.isEmpty());
    }

    @Test
    void new_stack_with_old_child_plus_new_child_owns_only_the_new_child() {
        InventorySnapshot.TopLevelEntry loose = entry(101, false, Set.of());
        InventorySnapshot baseline = snap(Map.of(101, loose), Set.of(101), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.TopLevelEntry mixed = entry(7, true, Set.of(101, 102));
        InventorySnapshot settled = snap(Map.of(7, mixed), Set.of(101, 102), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.Delta delta = settled.diff(baseline);

        assertEquals(Map.of(7, Set.of(102)), delta.newChildrenInStacks);
        assertTrue(delta.newWholeStacks.isEmpty(), "a stack holding any pre-existing child must never be whole-owned");
        assertTrue(delta.newLooseItems.isEmpty());
    }

    @Test
    void recreated_stack_container_with_only_pre_existing_children_owns_nothing() {
        InventorySnapshot baseline = snap(Map.of(), Set.of(101, 102), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.TopLevelEntry recreated = entry(8, true, Set.of(101, 102));
        InventorySnapshot settled = snap(Map.of(8, recreated), Set.of(101, 102), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.Delta delta = settled.diff(baseline);

        assertTrue(delta.isEmpty(), "a recreated container holding only pre-existing children owns nothing");
    }

    @Test
    void wholly_new_stack_with_only_new_children_is_still_safely_recognized() {
        InventorySnapshot baseline = snap(Map.of(), Set.of(), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.TopLevelEntry wholeNew = entry(6, true, Set.of(601, 602));
        InventorySnapshot settled = snap(Map.of(6, wholeNew), Set.of(601, 602), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.Delta delta = settled.diff(baseline);

        assertEquals(Map.of(6, Set.of(601, 602)), delta.newWholeStacks);
        assertTrue(delta.newChildrenInStacks.isEmpty());
        assertTrue(delta.newLooseItems.isEmpty());
    }

    // ---- Cursor classification ----

    @Test
    void cursor_delta_distinguishes_new_expected_output_from_pre_existing_item() {
        InventorySnapshot emptyBaseline = snap(Map.of(), Set.of(), InventorySnapshot.CursorState.EMPTY, null);
        InventorySnapshot newMatchingCursor = snap(Map.of(), Set.of(55), InventorySnapshot.CursorState.MATCHING, 55);
        assertEquals(InventorySnapshot.CursorClassification.NEW_EXPECTED,
                newMatchingCursor.classifyCursor(emptyBaseline));

        InventorySnapshot preExistingBaseline = snap(Map.of(), Set.of(), InventorySnapshot.CursorState.OTHER, 77);
        InventorySnapshot samePreExistingItem = snap(Map.of(), Set.of(), InventorySnapshot.CursorState.OTHER, 77);
        assertEquals(InventorySnapshot.CursorClassification.PRE_EXISTING,
                samePreExistingItem.classifyCursor(preExistingBaseline));

        // Present, but neither the same wdgid as baseline nor a name match for this harvest's
        // product - unknown origin, must fail safe as AMBIGUOUS rather than being assumed ours.
        InventorySnapshot unrelatedNewItem = snap(Map.of(), Set.of(88), InventorySnapshot.CursorState.OTHER, 88);
        assertEquals(InventorySnapshot.CursorClassification.AMBIGUOUS,
                unrelatedNewItem.classifyCursor(emptyBaseline));

        InventorySnapshot empty = snap(Map.of(), Set.of(), InventorySnapshot.CursorState.EMPTY, null);
        assertEquals(InventorySnapshot.CursorClassification.EMPTY, empty.classifyCursor(emptyBaseline));
    }

    @Test
    void cursor_item_already_present_in_baseline_inventory_is_pre_existing_even_with_new_wdgid_slot() {
        // Round 7: a unit that already existed somewhere in the baseline inventory (not
        // necessarily the cursor) and is now found in the cursor must still classify as
        // PRE_EXISTING, not NEW_EXPECTED - matching a name alone is never sufficient.
        InventorySnapshot baseline = snap(Map.of(), Set.of(101), InventorySnapshot.CursorState.EMPTY, null);
        InventorySnapshot movedToCursor = snap(Map.of(), Set.of(101), InventorySnapshot.CursorState.MATCHING, 101);

        assertEquals(InventorySnapshot.CursorClassification.PRE_EXISTING, movedToCursor.classifyCursor(baseline));
    }

    // ---- Round 7b: physical vs. matching children ----

    @Test
    void stack_with_one_old_physical_child_and_one_new_matching_child_owns_only_the_new_one() {
        // Baseline already knows unit 101. The container now PHYSICALLY holds 101 and 102, but
        // only 102 currently resolves as a match for this product's alias (101's name never
        // matched it, matching real gameplay: 101 is some other, unrelated item that happens to
        // share the same ItemStack widget). Must own only 102, and never whole-drop the container -
        // dropping it would also take the unrelated/pre-existing 101 with it.
        InventorySnapshot baseline = snap(Map.of(), Set.of(101), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.TopLevelEntry container =
                InventorySnapshot.entry(7, true, Set.of(101, 102), Set.of(102));
        InventorySnapshot settled = snap(Map.of(7, container), Set.of(101, 102), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.Delta delta = settled.diff(baseline);

        assertEquals(Map.of(7, Set.of(102)), delta.newChildrenInStacks);
        assertTrue(delta.newWholeStacks.isEmpty(), "a container with any unmatched/unproven physical child must never be whole-owned");
    }

    @Test
    void wholly_new_container_with_one_unresolved_physical_child_is_never_whole_owned() {
        // A brand-new container physically holds 201 and 202, but only 201 has a resolved,
        // matching name (202's name is still null/loading, or genuinely a different product) - so
        // it is NOT provably true that every physical child is this harvest's own output. Must not
        // whole-drop; 201 may still be owned individually since it IS proven matching and new.
        InventorySnapshot baseline = snap(Map.of(), Set.of(), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.TopLevelEntry container =
                InventorySnapshot.entry(6, true, Set.of(201, 202), Set.of(201));
        InventorySnapshot settled = snap(Map.of(6, container), Set.of(201, 202), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.Delta delta = settled.diff(baseline);

        assertTrue(delta.newWholeStacks.isEmpty(), "an unresolved/unmatched physical child must block the whole-stack optimization");
        assertEquals(Map.of(6, Set.of(201)), delta.newChildrenInStacks);
    }

    @Test
    void container_with_fully_matching_and_new_physical_children_remains_whole_stack_eligible() {
        // Every physical child is also confirmed-matching and new - the whole-stack optimization
        // must still apply in this (the common) case, unaffected by the physical/matching split.
        InventorySnapshot baseline = snap(Map.of(), Set.of(), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.TopLevelEntry container =
                InventorySnapshot.entry(4, true, Set.of(401, 402), Set.of(401, 402));
        InventorySnapshot settled = snap(Map.of(4, container), Set.of(401, 402), InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.Delta delta = settled.diff(baseline);

        assertEquals(Map.of(4, Set.of(401, 402)), delta.newWholeStacks);
        assertTrue(delta.newChildrenInStacks.isEmpty());
    }

    @Test
    void first_product_detection_does_not_trigger_from_baseline_state_alone() {
        // WaitLpFirstProduct's NEW_ITEM/STACK_GROWTH signals are exactly settled.diff(baseline) -
        // a snapshot diffed against an identical copy of itself (the "nothing happened yet" case
        // every poll starts from) must never report anything new.
        InventorySnapshot.TopLevelEntry preExistingStack = entry(3, true, Set.of(301, 302));
        InventorySnapshot.TopLevelEntry preExistingLoose = entry(4, false, Set.of());
        Map<Integer, InventorySnapshot.TopLevelEntry> top = new HashMap<>();
        top.put(3, preExistingStack);
        top.put(4, preExistingLoose);
        Set<Integer> allIds = Set.of(301, 302, 4);

        InventorySnapshot baseline = snap(top, allIds, InventorySnapshot.CursorState.EMPTY, null);
        InventorySnapshot stillBaseline = snap(top, allIds, InventorySnapshot.CursorState.EMPTY, null);

        InventorySnapshot.Delta delta = stillBaseline.diff(baseline);
        assertTrue(delta.isEmpty());
        assertEquals(InventorySnapshot.CursorClassification.EMPTY, stillBaseline.classifyCursor(baseline));
        assertTrue(stillBaseline.sameState(baseline));
    }
}
