package nurgling.iteminfo.food;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The multipliers that turn a food's listed FEP and hunger into what eating it will actually give
 * this character right now, kept as plain numbers so the arithmetic can be tested without a game.
 * <p>
 * <b>FEP side.</b> {@code multiplier = (account + realm) * foodEfficacy * tableBonus * satiation * salt}.
 * That is exactly the formula {@code NFoodInfo.calcExpectedFep()} has always used
 * ({@code (account*F*g*t + F*g*t*realm) * satiation}), except that {@code satiation} is now the
 * <em>product</em> over all of the food's types (see {@link #combineSatiation}) and salt satiation is the
 * one extra, 1 unless the item is salted and the character has a salt satiation entry. The account bonus
 * and the realm bonus are <em>added</em>, not multiplied, which is why they are one factor here.
 * <p>
 * <b>Hunger side.</b> Only the table's own "Hunger modifier" is applied. Whether satiation also scales
 * the hunger a food fills has not been verified in-game, so it is deliberately not applied; see
 * {@link #SATIATION_SCALES_HUNGER}.
 * <p>
 * <b>Never invented.</b> The result has a {@link Status}: only {@code AVAILABLE} carries numbers. When
 * an input is still loading ({@code PENDING}) or cannot be understood ({@code UNSUPPORTED}) callers must
 * not show a prediction as if it were complete.
 */
public final class FoodModifiers {
    /**
     * Hurricane scales displayed hunger by satiation as well, and the Ring of Brodgar wiki's "Food
     * Satiations" page gives {@code HungerGained = FoodHungerValue * SatiationModifier}. The fork's own
     * FEP formula has never done so and nothing in the client source decides it, so this stays off until
     * it is confirmed against the live game (compare a food's listed hunger with the hunger bar's change).
     */
    public static final boolean SATIATION_SCALES_HUNGER = false;

    public static final double SUBSCRIBED_BONUS = 1.5;
    public static final double VERIFIED_BONUS = 1.2;

    public enum Account {NONE, VERIFIED, SUBSCRIBED}

    /** Whether the numbers can be shown. */
    public enum Status {
        /** All inputs were read; the numbers are complete. */
        AVAILABLE,
        /** An input has not loaded yet (a resource, a buff's tooltip); try again shortly. */
        PENDING,
        /** An input is present but not in a form this client understands; do not guess. */
        UNSUPPORTED,
        /** No character data at all (no session, character sheet not built). */
        UNAVAILABLE
    }

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

    private final Status status;
    private final Account account;
    private final double realm;
    private final double foodEfficacy;
    private final double tableFep;
    private final double satiation;
    private final Double salt;
    private final double tableHunger;
    private final boolean deepWorm;

    private FoodModifiers(Status status, Account account, double realm, double foodEfficacy, double tableFep,
                          double satiation, Double salt, double tableHunger, boolean deepWorm) {
        this.status = status;
        this.account = account;
        this.realm = realm;
        this.foodEfficacy = foodEfficacy;
        this.tableFep = tableFep;
        this.satiation = satiation;
        this.salt = salt;
        this.tableHunger = tableHunger;
        this.deepWorm = deepWorm;
    }

    private static FoodModifiers without(Status status) {
        return new FoodModifiers(status, Account.NONE, 0, 1, 1, 1, null, 1, false);
    }

    private static final FoodModifiers UNAVAILABLE = without(Status.UNAVAILABLE);
    private static final FoodModifiers PENDING = without(Status.PENDING);
    private static final FoodModifiers UNSUPPORTED = without(Status.UNSUPPORTED);

    /** Character data is missing (not logged in far enough, or the item belongs to no session). */
    public static FoodModifiers unavailable() {
        return UNAVAILABLE;
    }

    /** Some input has not loaded yet. */
    public static FoodModifiers pending() {
        return PENDING;
    }

    /** Some input exists but is not understood. */
    public static FoodModifiers unsupported() {
        return UNSUPPORTED;
    }

    /**
     * @param realm        realm food-event bonus as a fraction (0.25 for +25%), 0 for none
     * @param foodEfficacy the hunger bar's food efficacy multiplier
     * @param tableFep     table food-event multiplier, 1 when not at a table
     * @param satiation    the food's satiation efficiency as a multiplier (1 = none applied); for a food
     *                     with several types, the result of {@link #combineSatiation}
     * @param salt         salt satiation multiplier when the item is salted and the character has one, else null
     * @param tableHunger  the table's hunger modifier as a multiplier, 1 when not at a table, NaN if its label was unreadable
     */
    public static FoodModifiers of(Account account, double realm, double foodEfficacy, double tableFep,
                                   double satiation, Double salt, double tableHunger, boolean deepWorm) {
        return new FoodModifiers(Status.AVAILABLE, account, realm, foodEfficacy, tableFep, satiation, salt, tableHunger, deepWorm);
    }

    /**
     * The satiation multiplier of a food that belongs to several types: every type's multiplier applies,
     * so they multiply. A food with no matching satiation entry is unaffected (1).
     * <p>
     * <b>Status of this rule.</b> That <em>every</em> type of the food counts is proven by the client's own
     * satiation list, which highlights all of a hovered food's types ({@code BAttrWnd.Constipations.draw}).
     * That they combine <em>multiplicatively</em> is what Hurricane does, and is the only rule that does not
     * depend on the order of the type indices; it has not been compared against the server's own numbers.
     * Single-type foods, the common case, are exact either way.
     */
    public static double combineSatiation(double... perType) {
        double ret = 1.0;
        for(double m : perType)
            ret *= m;
        return ret;
    }

    public Status status() {
        return status;
    }

    public boolean available() {
        return status == Status.AVAILABLE;
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

    /**
     * Multiplier from the listed hunger to the hunger the meal actually fills; NaN when the table's
     * hunger label could not be read (see {@link #hungerKnown()}).
     */
    public double hungerMultiplier() {
        return tableHunger;
    }

    /**
     * False when a table is open but its hunger label could not be read. The FEP prediction does not
     * depend on that label and stays valid; only hunger-based figures are unknown.
     */
    public boolean hungerKnown() {
        return !Double.isNaN(tableHunger);
    }

    public double predictedFep(double listedFep) {
        return listedFep * fepMultiplier();
    }

    public double predictedHunger(double listedHunger) {
        return listedHunger * hungerMultiplier();
    }

    /**
     * Identity of everything that is displayed about these modifiers: the status, every factor and the
     * Deep Worm flag. Unlike comparing the predicted FEP, it changes when two factors move in opposite
     * directions and cancel out numerically, so a tooltip that shows the breakdown can tell it is stale.
     */
    public int signature() {
        return Objects.hash(status, account, realm, foodEfficacy, tableFep, satiation, salt, tableHunger, deepWorm);
    }

    /** FEP per unit of hunger; NaN when the hunger is zero or unknown. */
    public static double perHunger(double fep, double hunger) {
        return (hunger > 0 && !Double.isNaN(fep)) ? (fep / hunger) : Double.NaN;
    }

    /**
     * Where the FEP bar would stand after eating, in percent of {@code target}; NaN when the target is
     * unknown. {@code target} should be the amount at which the bar actually completes, which with the
     * variety bonus is below the bar's cap.
     */
    public static double barFillPercent(double currentFep, double predictedFep, double target) {
        return (target > 0) ? ((currentFep + predictedFep) / target * 100.0) : Double.NaN;
    }

    /** The factors that actually change something, FEP-side ones first. Empty when every factor is neutral. */
    public List<Factor> factors() {
        if(!available())
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
        if(tableHunger != 1.0 && !Double.isNaN(tableHunger))
            ret.add(new Factor(Kind.TABLE_HUNGER, tableHunger, false));
        return ret;
    }
}
