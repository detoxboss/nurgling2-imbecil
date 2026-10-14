package nurgling.iteminfo.food;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the percentages from the table window's text labels ("Hunger modifier: 35%",
 * "Food event bonus: 25.5%"). The server sends those only as label text; there is no structured form
 * to read instead.
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

    /**
     * The table's hunger modifier as a multiplier. NaN or non-positive values (a label that did not
     * parse, or a nonsensical 0%) mean "no information", reported as 1 so nothing is scaled.
     */
    static double hungerMultiplier(String label) {
        double f = fraction(label);
        return (Double.isNaN(f) || f <= 0) ? 1.0 : f;
    }
}
