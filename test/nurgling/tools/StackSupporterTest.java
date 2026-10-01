package nurgling.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link StackSupporter}'s exact-name static fallback (isStackableByName /
 * getFullStackSize) - the logic the shared DB-backed override table (see
 * nurgling/db/migration/MigrationManager.java migration 15, nurgling/db/service/StackSizeService.java)
 * falls back to whenever it has no opinion of its own. Runs with no game session and no database
 * (NCore.databaseManager stays null in this harness), which is exactly the "DB unavailable" path
 * getFullStackSize/isStackable's early stackSizeInfo() check is meant to fall through on - so these
 * tests exercise the real production fallback, not a mock of it.
 *
 * These don't touch NInventory/Window, so isStackable() itself (which needs a container window to
 * resolve context vetoes) isn't tested here - only the exact-name portion both public methods share.
 */
class StackSupporterTest {

    @Test
    void customStackSizeIsHonored() {
        assertTrue(StackSupporter.isStackableByName("Reeds"));
        assertEquals(4, StackSupporter.getFullStackSize("Reeds"));

        assertTrue(StackSupporter.isStackableByName("Branch"));
        assertEquals(5, StackSupporter.getFullStackSize("Branch"));
    }

    @Test
    void categoryLookupIsHonored() {
        // "Onion" is a VSpec category name (putAll(3, ..., "Onion", ...)), not an item name -
        // getFullStackSize resolves real item names through VSpec.getCategory(name), so this only
        // proves the category-size table itself is wired, via a name with no customStackSizes/
        // catExceptions entry of its own.
        assertFalse(StackSupporter.isStackableByName("Onion"));
        assertEquals(1, StackSupporter.getFullStackSize("Onion"));
    }

    @Test
    void unknownNameDefaultsToUnstackable() {
        String name = "Definitely Not A Real Item " + System.nanoTime();
        assertFalse(StackSupporter.isStackableByName(name));
        assertEquals(1, StackSupporter.getFullStackSize(name));
    }

    /**
     * Regression test for the ordering fix documented on isStackableByName(): "Wolf's Claw" is in
     * both catExceptions and customStackSizes (value 4). Before the fix, isStackable checked the
     * exception first (false) while getFullStackSize checked the custom size first (4) - a latent
     * contradiction. Both must now agree: not stackable, size 1.
     */
    @Test
    void wolfsClawExceptionWinsConsistently() {
        assertFalse(StackSupporter.isStackableByName("Wolf's Claw"));
        assertEquals(1, StackSupporter.getFullStackSize("Wolf's Claw"));
    }

    @Test
    void seedCandidateNamesIncludesKnownEntries() {
        java.util.Set<String> names = StackSupporter.seedCandidateNames();
        // customStackSizes and catExceptions entries are always present regardless of whether
        // VSpec's category data loaded in this harness.
        assertTrue(names.contains("Reeds"));
        assertTrue(names.contains("Wolf's Claw"));
    }

    /**
     * Stack-size facts upstream corrected in the 2026-09-30 sync range. These items sit in
     * "Stackable Curiosities" (category size 4) but the server stacks them shallower, so they are
     * customStackSizes entries that must win over the category.
     */
    @Test
    void upstreamShallowCurioCorrections() {
        assertTrue(StackSupporter.isStackableByName("Brain"));
        assertEquals(2, StackSupporter.getFullStackSizeStatic("Brain"));

        assertTrue(StackSupporter.isStackableByName("Small Brain"));
        assertEquals(3, StackSupporter.getFullStackSizeStatic("Small Brain"));

        assertTrue(StackSupporter.isStackableByName("Aurochs Hair"));
        assertEquals(3, StackSupporter.getFullStackSizeStatic("Aurochs Hair"));

        assertTrue(StackSupporter.isStackableByName("Adder's Lying Tongue"));
        assertEquals(3, StackSupporter.getFullStackSizeStatic("Adder's Lying Tongue"));

        // Peapod is in no VSpec category at all - only its customStackSizes entry makes it stack.
        assertTrue(StackSupporter.isStackableByName("Peapod"));
        assertEquals(3, StackSupporter.getFullStackSizeStatic("Peapod"));
    }

    /**
     * Upstream removed "Lynx Claws" from catExceptions and from isStackable's context-veto block in
     * the same range; the merge took both. It is a plain FineBones item again, so it stacks 4 deep
     * like the rest of that category. Regression guard: the veto used to live in two places, and
     * only one of them conflicted during the merge.
     */
    @Test
    void lynxClawsNowStacks() {
        assertTrue(StackSupporter.isStackableByName("Lynx Claws"));
        assertEquals(4, StackSupporter.getFullStackSizeStatic("Lynx Claws"));
    }

    /**
     * Regression test for the " Meat" / "Meat" category-key mismatch fixed in the 2026-09-30 sync.
     * PR #11 renamed VSpec's weird-meat category from " Meat" to "Meat" but left StackSupporter's
     * categorySize key with its leading space; categorySize is an exact Map lookup, so every weird
     * meat silently reported unstackable/1 and never reached the stack_sizes seed set either.
     */
    @Test
    void weirdMeatCategoryContributesStackSizes() {
        for (String meat : new String[] {"Ant Meat", "Cave Louse Meat", "Chasm Conch Meat"}) {
            assertTrue(StackSupporter.isStackableByName(meat), meat + " should be stackable");
            assertEquals(5, StackSupporter.getFullStackSizeStatic(meat), meat + " stack size");
            assertTrue(StackSupporter.seedCandidateNames().contains(meat),
                meat + " should be seeded into stack_sizes");
        }
    }

    /**
     * The static-only view migration 15 seeds and reconciles from must agree with the per-name
     * static methods, and must carry the corrections above.
     */
    @Test
    void staticSeedSnapshotMatchesStaticMethods() {
        java.util.Map<String, StackSupporter.StaticStackFact> snap = StackSupporter.staticSeedSnapshot();

        StackSupporter.StaticStackFact brain = snap.get("Brain");
        assertTrue(brain != null, "Brain missing from the static seed snapshot");
        assertEquals(2, brain.maxStack);
        assertTrue(brain.stackable);

        StackSupporter.StaticStackFact lynx = snap.get("Lynx Claws");
        assertTrue(lynx != null, "Lynx Claws missing from the static seed snapshot");
        assertEquals(4, lynx.maxStack);
        assertTrue(lynx.stackable);

        // An unstackable exception is recorded as (1, false), never as its category size.
        StackSupporter.StaticStackFact wolf = snap.get("Wolf's Claw");
        assertTrue(wolf != null, "Wolf's Claw missing from the static seed snapshot");
        assertEquals(1, wolf.maxStack);
        assertFalse(wolf.stackable);

        // Every entry must be self-consistent with the methods it was generated from.
        for (java.util.Map.Entry<String, StackSupporter.StaticStackFact> e : snap.entrySet()) {
            boolean stackable = StackSupporter.isStackableByName(e.getKey());
            assertEquals(stackable, e.getValue().stackable, e.getKey() + " stackable");
            assertEquals(stackable ? StackSupporter.getFullStackSizeStatic(e.getKey()) : 1,
                e.getValue().maxStack, e.getKey() + " maxStack");
        }
    }
}
