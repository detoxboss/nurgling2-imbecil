package nurgling.iteminfo.food;

import org.junit.jupiter.api.Test;

import java.util.List;

import static nurgling.iteminfo.food.FoodModifiers.Account;
import static nurgling.iteminfo.food.FoodModifiers.Kind;
import static org.junit.jupiter.api.Assertions.*;

/** The food prediction arithmetic, with no game running. */
class FoodModifiersTest {
    private static final double EPS = 1e-9;

    /**
     * The formula {@code NFoodInfo.calcExpectedFep()} used before it delegated to {@link FoodModifiers},
     * copied verbatim in shape: {@code (account*F*g*t + F*g*t*realm) * satiation%/100}.
     */
    private static double legacyExpected(double fepSum, double account, double gmod, double table, double realm, double efficiencyPercent) {
        return (account * fepSum * gmod * table + fepSum * gmod * table * realm) * efficiencyPercent / 100;
    }

    private static FoodModifiers mods(Account a, double realm, double gmod, double table, double satiation, Double salt, double tableHunger) {
        return FoodModifiers.of(a, realm, gmod, table, satiation, salt, tableHunger, false);
    }

    @Test
    void predictionMatchesTheExistingExpectedFepFormula() {
        double[] feps = {0, 3.5, 42.25, 180};
        double[] gmods = {0.5, 0.93, 1.0, 1.21};
        double[] tables = {1.0, 1.25, 1.6};
        double[] realms = {0, 0.1, 0.35};
        double[] sats = {100, 87.5, 40};
        Account[] accounts = {Account.NONE, Account.VERIFIED, Account.SUBSCRIBED};
        double[] coef = {1.0, 1.2, 1.5};
        for(double f : feps)
            for(double g : gmods)
                for(double t : tables)
                    for(double r : realms)
                        for(double s : sats)
                            for(int a = 0; a < accounts.length; a++) {
                                FoodModifiers m = mods(accounts[a], r, g, t, s / 100.0, null, 1.0);
                                assertEquals(legacyExpected(f, coef[a], g, t, r, s), m.predictedFep(f), EPS,
                                    "fep=" + f + " gmod=" + g + " table=" + t + " realm=" + r + " sat=" + s + " acct=" + accounts[a]);
                            }
    }

    @Test
    void realmBonusAddsToTheAccountBonusInsteadOfMultiplyingIt() {
        FoodModifiers m = mods(Account.SUBSCRIBED, 0.25, 1, 1, 1, null, 1);
        assertEquals(1.75, m.fepMultiplier(), EPS);
        assertNotEquals(1.5 * 1.25, m.fepMultiplier(), EPS);
    }

    @Test
    void baseVersusModifiedValues() {
        FoodModifiers m = mods(Account.VERIFIED, 0, 0.9, 1.25, 0.8, null, 1);
        double listed = 100;
        assertEquals(100, listed, "the listed value is what Shift shows");
        assertEquals(100 * 1.2 * 0.9 * 1.25 * 0.8, m.predictedFep(listed), EPS);
    }

    @Test
    void weightedTotalScalesWithTheSameFepMultiplier() {
        FoodModifiers m = mods(Account.SUBSCRIBED, 0, 1, 1, 0.5, null, 1);
        double sev = 12;
        assertEquals(sev * 1.5 * 0.5, m.predictedFep(sev), EPS);
        /* the weighted total is a different number from the plain sum and must not be confused with it */
        assertNotEquals(m.predictedFep(8), m.predictedFep(sev));
    }

    @Test
    void hungerOnlyFollowsTheTableModifier() {
        FoodModifiers m = mods(Account.SUBSCRIBED, 0.2, 0.7, 1.4, 0.6, 0.9, 0.35);
        assertEquals(0.35, m.hungerMultiplier(), EPS);
        assertEquals(10 * 0.35, m.predictedHunger(10), EPS);
        assertFalse(FoodModifiers.SATIATION_SCALES_HUNGER);
        FoodModifiers none = mods(Account.SUBSCRIBED, 0.2, 0.7, 1.4, 0.6, 0.9, 1.0);
        assertEquals(10, none.predictedHunger(10), EPS, "satiation, salt and gmod must not scale hunger");
    }

    @Test
    void saltSatiationOnlyAppliesWhenPresent() {
        FoodModifiers plain = mods(Account.NONE, 0, 1, 1, 0.8, null, 1);
        FoodModifiers salted = mods(Account.NONE, 0, 1, 1, 0.8, 0.5, 1);
        assertEquals(0.8, plain.fepMultiplier(), EPS);
        assertEquals(0.4, salted.fepMultiplier(), EPS);
        assertTrue(plain.factors().stream().noneMatch(f -> f.kind == Kind.SALT));
        assertTrue(salted.factors().stream().anyMatch(f -> f.kind == Kind.SALT));
    }

    @Test
    void factorsListOnlyWhatChangesSomethingAndKeepHungerSeparate() {
        FoodModifiers m = mods(Account.VERIFIED, 0.1, 0.9, 1.0, 0.75, null, 0.5);
        List<FoodModifiers.Factor> fs = m.factors();
        assertEquals(5, fs.size(), "account, realm, efficacy, satiation, table hunger");
        assertEquals(Kind.ACCOUNT, fs.get(0).kind);
        assertTrue(fs.get(1).additive);
        assertEquals(Kind.TABLE_HUNGER, fs.get(4).kind);
        assertTrue(fs.get(4).hunger());
        for(int i = 0; i < 4; i++)
            assertFalse(fs.get(i).hunger());
        assertTrue(mods(Account.NONE, 0, 1, 1, 1, null, 1).factors().isEmpty());
    }

    @Test
    void missingCharacterDataGivesNoPrediction() {
        FoodModifiers m = FoodModifiers.unavailable();
        assertFalse(m.available());
        assertTrue(m.factors().isEmpty());
        assertFalse(m.deepWorm());
    }

    @Test
    void deepWormIsCarriedThrough() {
        assertTrue(FoodModifiers.of(Account.NONE, 0, 1, 1, 1, null, 1, true).deepWorm());
        assertFalse(mods(Account.NONE, 0, 1, 1, 1, null, 1).deepWorm());
    }

    @Test
    void divisionsByZeroGiveNaNNotInfinity() {
        assertTrue(Double.isNaN(FoodModifiers.perHunger(5, 0)));
        assertTrue(Double.isNaN(FoodModifiers.perHunger(5, -1)));
        assertTrue(Double.isNaN(FoodModifiers.perHunger(Double.NaN, 3)));
        assertEquals(2.5, FoodModifiers.perHunger(5, 2), EPS);
        assertTrue(Double.isNaN(FoodModifiers.barFillPercent(10, 5, 0)));
        assertTrue(Double.isNaN(FoodModifiers.barFillPercent(10, 5, -3)));
    }

    @Test
    void fepBarPrediction() {
        assertEquals(75.0, FoodModifiers.barFillPercent(50, 25, 100), EPS);
        assertEquals(120.0, FoodModifiers.barFillPercent(90, 30, 100), EPS, "can overshoot the bar");
        assertEquals(25.0, FoodModifiers.barFillPercent(0, 25, 100), EPS);
    }

    @Test
    void tableLabelsAreParsedFromTheirText() {
        assertEquals(0.35, TableLabels.fraction("Hunger modifier: 35%"), EPS);
        assertEquals(0.255, TableLabels.fraction("Food event bonus: 25.5%"), EPS);
        assertEquals(0.255, TableLabels.fraction("Food event bonus: 25,5%"), EPS);
        assertEquals(1.2, TableLabels.fraction("Food event bonus: 120 %"), EPS);
        assertTrue(Double.isNaN(TableLabels.fraction("Feast!")));
        assertTrue(Double.isNaN(TableLabels.fraction(null)));
    }

    @Test
    void tableLabelsGiveMultipliersAndRejectWhatTheyCannotRead() {
        assertEquals(0.35, TableLabels.hungerMultiplier("Hunger modifier: 35%"), EPS);
        assertEquals(1.25, TableLabels.foodEventMultiplier("Food event bonus: 25%"), EPS);
        /* unreadable labels are NaN so the caller shows no prediction; they are never quietly neutral */
        assertTrue(Double.isNaN(TableLabels.hungerMultiplier("Hunger modifier: 0%")));
        assertTrue(Double.isNaN(TableLabels.hungerMultiplier("Hunger modifier: ??")));
        assertTrue(Double.isNaN(TableLabels.hungerMultiplier(null)));
        assertTrue(Double.isNaN(TableLabels.foodEventMultiplier("Food event bonus: n/a")));
        assertTrue(Double.isNaN(TableLabels.foodEventMultiplier("Food event bonus: -5%")));
    }

    @Test
    void tableLabelsAreRecognisedByTheirStart() {
        assertTrue(TableLabels.isFoodEventBonus("Food event bonus: 25%"));
        assertFalse(TableLabels.isFoodEventBonus("Hunger modifier: 25%"));
        assertTrue(TableLabels.isHungerModifier("Hunger modifier: 35%"));
        assertFalse(TableLabels.isHungerModifier(null));
    }

    @Test
    void realmLinesAreNothingUnlessTheyAreAboutFoodEvents() {
        assertNull(TableLabels.realmBonus("Strength +5"));
        assertNull(TableLabels.realmBonus(null));
        assertEquals(0.25, TableLabels.realmBonus("Food event bonus: +25%"), EPS);
        assertTrue(Double.isNaN(TableLabels.realmBonus("Food event bonus: lots")));
    }

    @Test
    void everyTypeOfAMultiTypeFoodCounts() {
        double[] entries = {0.9, 0.5, 1.2, 0.8};
        /* a food of types 1 and 3 is reduced by both, whichever order the types come in */
        assertEquals(0.5 * 0.8, FoodModifierReader.satiation(entries, new int[] {1, 3}), EPS);
        assertEquals(0.5 * 0.8, FoodModifierReader.satiation(entries, new int[] {3, 1}), EPS);
        /* a single type is exactly its entry, as it always was */
        assertEquals(0.5, FoodModifierReader.satiation(entries, new int[] {1}), EPS);
        /* bonuses (above 1) multiply in too */
        assertEquals(0.9 * 1.2, FoodModifierReader.satiation(entries, new int[] {0, 2}), EPS);
    }

    @Test
    void typesWithoutAnEntryDoNotReduceAnything() {
        double[] entries = {0.5};
        assertEquals(1.0, FoodModifierReader.satiation(entries, new int[] {}), EPS);
        assertEquals(1.0, FoodModifierReader.satiation(entries, null), EPS);
        assertEquals(1.0, FoodModifierReader.satiation(new double[0], new int[] {0, 1}), EPS);
        assertEquals(0.5, FoodModifierReader.satiation(entries, new int[] {0, 7, -1}), EPS, "out-of-range types are skipped");
    }

    @Test
    void multiTypeSatiationFeedsThePredictionAsOneFactor() {
        double sat = FoodModifiers.combineSatiation(0.5, 0.8);
        FoodModifiers m = mods(Account.NONE, 0, 1, 1, sat, null, 1);
        assertEquals(100 * 0.4, m.predictedFep(100), EPS);
        assertEquals(1, m.factors().stream().filter(f -> f.kind == Kind.SATIATION).count());
    }

    @Test
    void incompleteDataIsNeverAvailable() {
        assertEquals(FoodModifiers.Status.PENDING, FoodModifiers.pending().status());
        assertEquals(FoodModifiers.Status.UNSUPPORTED, FoodModifiers.unsupported().status());
        assertEquals(FoodModifiers.Status.UNAVAILABLE, FoodModifiers.unavailable().status());
        assertFalse(FoodModifiers.pending().available());
        assertFalse(FoodModifiers.unsupported().available());
        assertTrue(FoodModifiers.pending().factors().isEmpty());
        assertTrue(mods(Account.NONE, 0, 1, 1, 1, null, 1).available());
    }

    @Test
    void signatureChangesWhenAnyDisplayedFactorChangesEvenIfThePredictionDoesNot() {
        /* satiation x0.5 and table x2.0 cancel out: the predicted FEP is the same, the breakdown is not */
        FoodModifiers a = mods(Account.NONE, 0, 1, 2.0, 0.5, null, 1);
        FoodModifiers b = mods(Account.NONE, 0, 1, 1.0, 1.0, null, 1);
        assertEquals(a.predictedFep(10), b.predictedFep(10), EPS);
        assertNotEquals(a.signature(), b.signature());
        assertEquals(a.signature(), mods(Account.NONE, 0, 1, 2.0, 0.5, null, 1).signature());
        /* hunger-only, salt and the Deep Worm flag are part of what is shown too */
        assertNotEquals(b.signature(), mods(Account.NONE, 0, 1, 1, 1, null, 0.5).signature());
        assertNotEquals(b.signature(), mods(Account.NONE, 0, 1, 1, 1, 1.0, 1).signature());
        assertNotEquals(b.signature(), FoodModifiers.of(Account.NONE, 0, 1, 1, 1, null, 1, true).signature());
        assertNotEquals(b.signature(), FoodModifiers.pending().signature());
        assertNotEquals(FoodModifiers.pending().signature(), FoodModifiers.unsupported().signature());
    }

    @Test
    void barFillIsMeasuredAgainstTheGivenTarget() {
        /* with the variety bonus the bar completes below its cap (cap 100, target 90) */
        assertEquals(100.0, FoodModifiers.barFillPercent(60, 30, 90), EPS);
        assertEquals(90.0, FoodModifiers.barFillPercent(60, 30, 100), EPS);
    }

    @Test
    void anUnreadableHungerLabelOnlyMakesHungerUnknownNotTheFepPrediction() {
        /* the food optimizer relies on the FEP prediction, which does not depend on the hunger label */
        FoodModifiers known = mods(Account.VERIFIED, 0, 0.9, 1.25, 0.8, null, 0.5);
        FoodModifiers unknown = mods(Account.VERIFIED, 0, 0.9, 1.25, 0.8, null, Double.NaN);
        assertTrue(unknown.available());
        assertFalse(unknown.hungerKnown());
        assertTrue(known.hungerKnown());
        assertEquals(known.predictedFep(10), unknown.predictedFep(10), EPS);
        assertTrue(Double.isNaN(unknown.predictedHunger(10)));
        assertTrue(unknown.factors().stream().noneMatch(f -> f.kind == Kind.TABLE_HUNGER));
        assertNotEquals(known.signature(), unknown.signature());
        assertTrue(Double.isNaN(FoodModifiers.perHunger(5, unknown.predictedHunger(10))));
    }
}