package nurgling.iteminfo.food;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the percentages from the table window's text labels ("Hunger modifier: 35%",
 * "Food event bonus: 25.5%") and from the realm buff's text ("Food event bonus: +25%"). The server sends
 * those only as text; there is no structured form to read instead.
 * <p>
 * A label that is present but cannot be read is reported as such ({@code NaN}, or a value the caller
 * rejects): the caller must then show no prediction rather than quietly treat the label as neutral.
 */
final class TableLabels {
    private TableLabels() {}

    private static final Pattern PERCENT = Pattern.compile("(-?\\d+(?:[.,]\\d+)?)\\s*%");

    /** The percentage in the label as a fraction (35% gives 0.35), or NaN when the label carries none. */
    static double fraction(String label) {
        if(label == null)
            return Double.NaN;
        Matcher m = PERCENT.matcher(label);
        if(!m.find())
            return Double.NaN;
        return Double.parseDouble(m.group(1).replace(',', '.')) / 100.0;
    }

    /** Whether this is the table's food event bonus label. */
    static boolean isFoodEventBonus(String label) {
        return label != null && label.startsWith("Food event bonus");
    }

    /** Whether this is the table's hunger modifier label. */
    static boolean isHungerModifier(String label) {
        return label != null && label.startsWith("Hunger modifier");
    }

    /**
     * The table's food event bonus as a multiplier (25% gives 1.25), or NaN when the label has no
     * usable percentage. A negative bonus is not something the game shows, so it is rejected too.
     */
    static double foodEventMultiplier(String label) {
        double f = fraction(label);
        return (Double.isNaN(f) || f < 0) ? Double.NaN : (1.0 + f);
    }

    /**
     * The table's hunger modifier as a multiplier (35% gives 0.35), or NaN when the label has no usable
     * percentage. A modifier of zero or less would mean the meal fills no hunger at all, which is more
     * likely a misread label than a real table, so it is rejected rather than applied.
     */
    static double hungerMultiplier(String label) {
        double f = fraction(label);
        return (Double.isNaN(f) || f <= 0) ? Double.NaN : f;
    }

    /**
     * A food event bonus carried by a realm buff line ("Food event bonus: +25%") as a fraction.
     *
     * @return {@code null} if the line is not about the food event bonus at all (nothing to add), the
     *         fraction if it is and can be read, or NaN if it is but carries no usable percentage
     */
    static Double realmBonus(String line) {
        if(line == null || !line.contains("Food event"))
            return null;
        double f = fraction(line);
        return Double.isNaN(f) ? Double.valueOf(Double.NaN) : Double.valueOf(f);
    }
}
