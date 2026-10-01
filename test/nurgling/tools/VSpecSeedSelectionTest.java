package nurgling.tools;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link VSpec#getSeedForTree(String)}, the method BlueprintTreePlanter uses as the exact
 * item name to find in inventory and place in a Treeplanter's Pot.
 *
 * <p>Both positional implementations this method has carried were wrong for a long tail of species:
 * {@code products.get(size - 1)} returned the bough for Beech/Poplar, the leaves for Teabush, and
 * the "Yesteryear's ..." variant for every seasonal-pair species; {@code products.get(0)} (upstream,
 * 2026-09-30 sync range) fixed those but returned a bough or leaf for fourteen others. These tests
 * pin the semantic selection that replaced them.
 */
class VSpecSeedSelectionTest {

    /** A tree whose product list starts with the seed - the easy case both implementations got right. */
    @Test
    void simpleSeedFirstTree() {
        assertEquals("Oak Acorn", VSpec.getSeedForTree("gfx/terobjs/trees/oak"));
        assertEquals("Hazelnut", VSpec.getSeedForTree("gfx/terobjs/trees/hazel"));
        assertEquals("Pine Cone", VSpec.getSeedForTree("gfx/terobjs/trees/pine"));
    }

    /** The species upstream's get(0) got wrong: a bough or leaf is listed before the seed. */
    @Test
    void boughOrLeafFirstTrees() {
        assertEquals("Alder Catkin", VSpec.getSeedForTree("gfx/terobjs/trees/alder"));
        assertEquals("Gray Alder Cones", VSpec.getSeedForTree("gfx/terobjs/trees/grayalder"));
        assertEquals("Elm Seeds", VSpec.getSeedForTree("gfx/terobjs/trees/elm"));
        assertEquals("Fir Cone", VSpec.getSeedForTree("gfx/terobjs/trees/fir"));
        assertEquals("Spruce Cone", VSpec.getSeedForTree("gfx/terobjs/trees/spruce"));
        assertEquals("Yew Cones", VSpec.getSeedForTree("gfx/terobjs/trees/yew"));
        assertEquals("Linden Fruits", VSpec.getSeedForTree("gfx/terobjs/trees/linden"));
        assertEquals("Sweetgum Seedpod", VSpec.getSeedForTree("gfx/terobjs/trees/sweetgum"));
        assertEquals("Maple Samara", VSpec.getSeedForTree("gfx/terobjs/trees/maple"));
        assertEquals("Laurel Seeds", VSpec.getSeedForTree("gfx/terobjs/trees/laurel"));
        assertEquals("Conker", VSpec.getSeedForTree("gfx/terobjs/trees/conkertree"));
        // Olive's bough-equivalent is the only one not named "<Species> Bough".
        assertEquals("Olive", VSpec.getSeedForTree("gfx/terobjs/trees/olivetree"));
    }

    /** The species the original get(size - 1) got wrong: the bough or leaves come last. */
    @Test
    void boughOrLeafLastTrees() {
        assertEquals("Beech Nuts", VSpec.getSeedForTree("gfx/terobjs/trees/beech"));
        assertEquals("Poplar Catkin", VSpec.getSeedForTree("gfx/terobjs/trees/poplar"));
        assertEquals("Teabush Seedpod", VSpec.getSeedForTree("gfx/terobjs/bushes/teabush"));
    }

    /** Seasonal pairs: the plantable item is the normal variant, never "Yesteryear's ...". */
    @Test
    void seasonalVariantsNeverWin() {
        assertEquals("Red Apple", VSpec.getSeedForTree("gfx/terobjs/trees/appletree"));
        assertEquals("Cherries", VSpec.getSeedForTree("gfx/terobjs/trees/cherry"));
        assertEquals("Crabapple", VSpec.getSeedForTree("gfx/terobjs/trees/crabappletree"));
        assertEquals("Pear", VSpec.getSeedForTree("gfx/terobjs/trees/peartree"));
        assertEquals("Raspberry", VSpec.getSeedForTree("gfx/terobjs/bushes/raspberrybush"));
    }

    /** A leaf first AND a seasonal variant last, in the same list. */
    @Test
    void leafFirstAndSeasonalLast() {
        assertEquals("Fig", VSpec.getSeedForTree("gfx/terobjs/trees/figtree"));
        assertEquals("Mulberry", VSpec.getSeedForTree("gfx/terobjs/trees/mulberry"));
    }

    /** Bushes BlueprintTreePlanter also plants. */
    @Test
    void bushes() {
        assertEquals("Blackberry", VSpec.getSeedForTree("gfx/terobjs/bushes/blackberrybush"));
        assertEquals("Hollyberries", VSpec.getSeedForTree("gfx/terobjs/bushes/holly"));
        // "Crampbark Berries" contains "bark" - the bark filter must be a suffix match, not a
        // substring one, or this bush would lose its only product.
        assertEquals("Crampbark Berries", VSpec.getSeedForTree("gfx/terobjs/bushes/crampbark"));
    }

    @Test
    void unknownTreePathReturnsNull() {
        assertEquals(null, VSpec.getSeedForTree("gfx/terobjs/trees/definitely-not-a-tree"));
    }

    /**
     * Whole-dataset invariant, scoped to {@code VSpec.object}'s genuine plantable population:
     * {@code gfx/terobjs/trees/*} and {@code gfx/terobjs/bushes/*} entries, excluding felled-log
     * mappings.
     *
     * <p>{@code VSpec.object} also carries, and this test explicitly excludes:
     * <ul>
     *   <li>{@code gfx/terobjs/bumlings/*} - rock/ore product mappings, a single mineral name each,
     *       never a seed, and not something {@link VSpec#getSeedForTree} is ever called on by
     *       BlueprintTreePlanter. Excluded by the path-prefix check alone (not under
     *       {@code trees/} or {@code bushes/}).</li>
     *   <li>{@code gfx/terobjs/trees/<species>log} - the felled-log counterpart every tree species
     *       has alongside its standing-tree entry (e.g. {@code acacialog} next to {@code acacia}),
     *       each mapping to a single "<Species> Log" product. These sit under {@code trees/} and
     *       would otherwise be swept in by the prefix check alone - they are excluded explicitly
     *       by the trailing {@code "log"} suffix. Not a seed-selection case: nothing calls
     *       {@code getSeedForTree} with a log path, and a log's own product (itself) would
     *       trivially satisfy the bough/leaf/bark/Yesteryear predicate below anyway, which is
     *       exactly why an earlier version of this test's scope comment overclaimed "zero log
     *       entries" without this exclusion actually being coded - it was passing by coincidence,
     *       not by construction.</li>
     * </ul>
     * No bush entry currently ends in {@code "log"}, but the suffix check applies to both prefixes
     * uniformly in case that ever changes.
     *
     * <p>The count is derived from the live merged source, not hard-coded, so a future VSpec
     * addition or removal changes what this test checks rather than silently going stale.
     */
    @Test
    void everyPlantableTreeOrBushResolvesToAPlausibleSeed() {
        int checked = 0;
        for (Map.Entry<String, ArrayList<String>> e : VSpec.object.entrySet()) {
            String path = e.getKey();
            boolean isTreeOrBush = path.startsWith("gfx/terobjs/trees/") || path.startsWith("gfx/terobjs/bushes/");
            if (!isTreeOrBush || path.endsWith("log")) {
                continue;
            }
            ArrayList<String> products = e.getValue();
            if (products == null || products.isEmpty()) {
                continue;
            }
            String seed = VSpec.getSeedForTree(path);
            assertNotNull(seed, path + " resolved to no seed");
            assertTrue(products.contains(seed),
                path + " resolved to \"" + seed + "\", which is not one of its products");
            assertFalse(seed.startsWith(HarvestState.YESTERYEAR_PREFIX),
                path + " resolved to the seasonal variant \"" + seed + "\"");
            assertFalse(seed.contains("Bough") || seed.equals("Olive Branch")
                    || seed.contains("Leaf") || seed.contains("Leaves") || seed.endsWith(" Bark"),
                path + " resolved to the non-seed product \"" + seed + "\"");
            checked++;
        }
        // Pinned to the exact authoritative count at the merged source (80 non-log trees + 29
        // bushes = 109), not a loose floor - so a future addition/removal to either population is
        // caught here instead of silently passing with a different count.
        assertEquals(109, checked, "expected exactly 109 plantable tree/bush entries");
    }
}
