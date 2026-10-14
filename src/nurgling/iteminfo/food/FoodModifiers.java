package nurgling.iteminfo.food;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The multipliers that turn a food's listed FEP and hunger into what eating it will actually give
 * this character right now, kept as plain numbers so the arithmetic can be tested without a game.
 * <p>
 * <b>FEP side.</b> {@code multiplier = (account + realm) * foodEfficacy * tableBonus * satiation * salt}.
 * That is exactly the formula {@code NFoodInfo.calcExpectedFep()} has always used
 * ({@code (account*F*g*t + F*g*t*realm) * satiation}); salt satiation is the only extra, and is 1
 * unless the item is salted and the character has a salt satiation entry. The account bonus and the
 * realm bonus are <em>added</em>, not multiplied, which is why they are one factor here.
 * <p>
 * <b>Hunger side.</b> Only the table's own "Hunger modifier" is applied. Whether satiation also scales
 * the hunger a food fills has not been verified in-game, so it is deliberately not applied; see
 * {@link #SATIATION_SCALES_HUNGER}.
 * <p>
 * Nothing here invents a value: when the character's data is not available the result is
 * {@link #unavailable()} and callers must not show predictions.
 */
public final class FoodModifiers {
    /**
     * Another client (Hurricane) scales displayed hunger by satiation as well. The source available
     * here shows no such rule, and the fork's own FEP formula has never done so, so this stays off
     * until it is confirmed against the live game.
     */
    public static final boolean SATIATION_SCALES_HUNGER = false;

    public static final double SUBSCRIBED_BONUS = 1.5;
    public static final double VERIFIED_BONUS = 1.2;

    public enum Account {NONE, VERIFIED, SUBSCRIBED}

    public enum Kind {
        ACCOUNT, REALM, FOOD_EFFICACY, TABLE_FEP, SATIATION, SALT, TABLE_HUNGER
    }

    /** One contributing multiplier. {@code additive} is true for the realm bonus, which adds to the account bonus. */
    public static final class Factor {
        public final Kind kind;
        public final double value;
        public final boolean additive;

        Factor(Kind kind, double value, boolean additive) {
            this.kind = kind;
            this.value = value;
            this.additive = additive;
        }

        /** True for the one factor that scales hunger rather than FEP. */
        public boolean hunger() {
            return kind == Kind.TABLE_HUNGER;
        }
    }

    private final boolean available;
    private final Account account;
    private final double realm;
    private final double foodEfficacy;
    private final double tableFep;
    private final double satiation;
    private final Double salt;
    private final double tableHunger;
    private final boolean deepWorm;

    private FoodModifiers(boolean available, Account account, double realm, double foodEfficacy, double tableFep,
                          double satiation, Double salt, double tableHunger, boolean deepWorm) {
        this.available = available;
        this.account = account;
        this.realm = realm;
        this.foodEfficacy = foodEfficacy;
        this.tableFep = tableFep;
        this.satiation = satiation;
        this.salt = salt;
        this.tableHunger = tableHunger;
        this.deepWorm = deepWorm;
    }

    private static final FoodModifiers UNAVAILABLE = new FoodModifiers(false, Account.NONE, 0, 1, 1, 1, null, 1, false);

    /** Character data is missing (not logged in far enough, or the item belongs to no session). */
    public static FoodModifiers unavailable() {
        return UNAVAILABLE;
    }

    /**
     * @param realm        realm food-event bonus as a fraction (0.25 for +25%), 0 for none
     * @param foodEfficacy the hunger bar's food efficacy multiplier
     * @param tableFep     table food-event multiplier, 1 when not at a table
     * @param satiation    the food's satiation efficiency as a multiplier (1 = none applied)
     * @param salt         salt satiation multiplier when the item is salted and the character has one, else null
     * @param tableHunger  the table's hunger modifier as a multiplier, 1 when not at a table
     */
    public static FoodModifiers of(Account account, double realm, double foodEfficacy, double tableFep,
                                   double satiation, Double salt, double tableHunger, boolean deepWorm) {
        return new FoodModifiers(true, account, realm, foodEfficacy, tableFep, satiation, salt, tableHunger, deepWorm);
    }

    public boolean available() {
        return available;
    }

    public boolean deepWorm() {
        return deepWorm;
    }

    public double accountBonus() {
        switch(account) {
        case SUBSCRIBED: return SUBSCRIBED_BONUS;
        case VERIFIED: return VERIFIED_BONUS;
        default: return 1.0;
        }
    }

    /** Multiplier from the listed FEP to the FEP this character will actually gain. */
    public double fepMultiplier() {
        return (accountBonus() + realm) * foodEfficacy * tableFep * satiation * ((salt == null) ? 1.0 : salt);
    }

    /** Multiplier from the listed hunger to the hunger the meal actually fills. */
    public double hungerMultiplier() {
        return tableHunger;
    }

    public double predictedFep(double listedFep) {
        return listedFep * fepMultiplier();
    }

    public double predictedHunger(double listedHunger) {
        return listedHunger * hungerMultiplier();
    }

    /** FEP per unit of hunger; NaN when the hunger is zero or unknown. */
    public static double perHunger(double fep, double hunger) {
        return (hunger > 0 && !Double.isNaN(fep)) ? (fep / hunger) : Double.NaN;
    }

    /** Where the FEP bar would stand after eating, in percent of its cap; NaN when the cap is unknown. */
    public static double barFillPercent(double currentFep, double predictedFep, double cap) {
        return (cap > 0) ? ((currentFep + predictedFep) / cap * 100.0) : Double.NaN;
    }

    /** The factors that actually change something, FEP-side ones first. Empty when every factor is neutral. */
    public List<Factor> factors() {
        if(!available)
            return Collections.emptyList();
        List<Factor> ret = new ArrayList<>();
        if(account != Account.NONE)
            ret.add(new Factor(Kind.ACCOUNT, accountBonus(), false));
        if(realm != 0)
            ret.add(new Factor(Kind.REALM, realm, true));
        if(foodEfficacy != 1.0)
            ret.add(new Factor(Kind.FOOD_EFFICACY, foodEfficacy, false));
        if(tableFep != 1.0)
            ret.add(new Factor(Kind.TABLE_FEP, tableFep, false));
        if(satiation != 1.0)
            ret.add(new Factor(Kind.SATIATION, satiation, false));
        if(salt != null && salt != 1.0)
            ret.add(new Factor(Kind.SALT, salt, false));
        if(tableHunger != 1.0)
            ret.add(new Factor(Kind.TABLE_HUNGER, tableHunger, false));
        return ret;
    }
}
