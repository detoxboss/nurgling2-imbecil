package nurgling.iteminfo;

import haven.ItemInfo;
import haven.resutil.FoodInfo;
import nurgling.NConfig;
import nurgling.iteminfo.food.FoodModifiers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static nurgling.iteminfo.food.FoodModifiers.Account;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The food tooltip's extra rows, rendered headlessly: the modified view, the Shift (unmodified)
 * view, the Deep Worm warning and the "no character data" case.
 */
class FoodDetailsLayoutTest {
    @BeforeAll
    static void defaultConfig() {
        NConfig.getGlobalInstance();
        try {
            Class.forName("haven.Text");
        } catch(ClassNotFoundException | LinkageError e) {
            Assumptions.abort("game resource jars (lib/ext/builtin-res.jar) are not on the test classpath: " + e);
        }
    }

    /** A food of 12 listed FEP, weighted total 15, hunger 4%. */
    private static NFoodInfo food(double weighted) {
        /* NFoodInfo's 5th and 6th arguments are named (sev, cons) but reach FoodInfo as (cons, sev) */
        NFoodInfo f = new NFoodInfo(null, 0.5, 0.04, 0.0, weighted, new FoodInfo.Event[0], new FoodInfo.Effect[0], new int[0]);
        f.fepSum = 12;
        return f;
    }

    private static FoodModifiers mods(boolean deepWorm) {
        return FoodModifiers.of(Account.SUBSCRIBED, 0.1, 0.9, 1.25, 0.8, 0.9, 0.35, deepWorm);
    }

    private static int height(NFoodInfo f, FoodModifiers m, boolean unmodified) {
        ItemInfo.Layout l = new ItemInfo.Layout(null);
        boolean added = FoodDetailsLayout.layout(f, l, m, unmodified, 4, 3, 20, 90, 100);
        return added ? l.cmp.sz.y : 0;
    }

    @Test
    void modifiedViewShowsPredictionsAndBreakdown() {
        assertTrue(height(food(15), mods(false), false) > 0);
    }

    @Test
    void shiftReplacesTheModifiedRowsWithTheShorterUnmodifiedBlock() {
        NFoodInfo f = food(15);
        int modified = height(f, mods(false), false);
        int unmodified = height(f, mods(false), true);
        assertTrue(unmodified > 0);
        assertTrue(modified > unmodified, "modified " + modified + "px vs unmodified " + unmodified + "px");
        /* releasing Shift restores the same modified view */
        assertEquals(modified, height(f, mods(false), false));
    }

    @Test
    void unmodifiedViewDoesNotNeedCharacterData() {
        assertTrue(height(food(15), FoodModifiers.unavailable(), true) > 0);
    }

    @Test
    void missingCharacterDataAddsNoPredictionRows() {
        assertEquals(0, height(food(15), FoodModifiers.unavailable(), false));
    }

    @Test
    void deepWormAddsAWarningRow() {
        NFoodInfo f = food(15);
        assertTrue(height(f, mods(true), false) > height(f, mods(false), false));
    }

    @Test
    void noWeightedTotalMeansNoWeightedRow() {
        assertTrue(height(food(0), mods(false), false) < height(food(15), mods(false), false));
        assertTrue(height(food(0), mods(false), true) < height(food(15), mods(false), true));
    }

    @Test
    void zeroHungerFoodDoesNotBlowUp() {
        NFoodInfo f = new NFoodInfo(null, 0.5, 0.0, 0.0, 15.0, new FoodInfo.Event[0], new FoodInfo.Effect[0], new int[0]);
        f.fepSum = 12;
        assertDoesNotThrow(() -> height(f, mods(false), false));
        assertDoesNotThrow(() -> height(f, mods(false), true));
    }

    @Test
    void optimizerBridgeKeepsReturningZeroWithoutACharacter() {
        /* with no session the live expected FEP is 0, as before; it must not throw */
        assertEquals(0.0, NFoodOptimizerSupport.expectedFep(food(15)));
    }

    @Test
    void shiftIsNeverHeldForRecipes() {
        assertFalse(FoodDetailsLayout.shiftHeld(null));
    }

    @Test
    void incompleteDataExplainsItselfInsteadOfShowingAPrediction() {
        NFoodInfo f = food(15);
        int pending = height(f, FoodModifiers.pending(), false);
        int unsupported = height(f, FoodModifiers.unsupported(), false);
        assertTrue(pending > 0, "a pending prediction says so");
        assertTrue(unsupported > 0, "an unreadable one says so");
        assertTrue(pending < height(f, mods(false), false), "and shows none of the prediction rows");
        /* Shift still shows the listed values whatever the modifiers' state */
        assertTrue(height(f, FoodModifiers.pending(), true) > 0);
    }

    @Test
    void implausibleWeightedTotalIsNotShown() {
        /* a "weighted total" below the plain FEP sum (here a satiation-sized 0.04) is not that number */
        assertEquals(0, food(0.04).weightedTotal());
        assertEquals(0, food(100).weightedTotal(), "more than twice the FEP sum is not a +2 weighting");
        assertEquals(15, food(15).weightedTotal());
        assertEquals(12, food(12).weightedTotal());
        assertEquals(24, food(24).weightedTotal());
        assertTrue(height(food(0.04), mods(false), false) < height(food(15), mods(false), false));
    }

    @Test
    void unreadableHungerLabelAddsANoteButKeepsThePrediction() {
        NFoodInfo f = food(15);
        FoodModifiers unknown = FoodModifiers.of(Account.SUBSCRIBED, 0.1, 0.9, 1.25, 0.8, 0.9, Double.NaN, false);
        int known = height(f, mods(false), false);
        int noHunger = height(f, unknown, false);
        assertDoesNotThrow(() -> height(f, unknown, false));
        assertTrue(noHunger > 0);
        assertTrue(noHunger != known, "the note changes the block");
    }
}