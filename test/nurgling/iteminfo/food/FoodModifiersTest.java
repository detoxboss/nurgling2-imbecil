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
    void unusableHungerModifierLabelsScaleNothing() {
        assertEquals(0.35, TableLabels.hungerMultiplier("Hunger modifier: 35%"), EPS);
        assertEquals(1.0, TableLabels.hungerMultiplier("Hunger modifier: 0%"), EPS);
        assertEquals(1.0, TableLabels.hungerMultiplier("Hunger modifier: ??"), EPS);
        assertEquals(1.0, TableLabels.hungerMultiplier(null), EPS);
    }
}
