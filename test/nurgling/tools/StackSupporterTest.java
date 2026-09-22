package nurgling.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link StackSupporter}'s exact-name static fallback (isStackableByName /
 * getFullStackSize) - the logic the shared DB-backed override table (see
 * nurgling/db/migration/MigrationManager.java migration 13, nurgling/db/service/StackSizeService.java)
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
}
