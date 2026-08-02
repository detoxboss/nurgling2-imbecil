package nurgling.iteminfo;

import java.util.HashMap;
import java.util.Map;

/**
 * Read-only bridge into {@link NFoodInfo}'s package-private live FEP computation, for
 * the table-eating optimizer (see {@code nurgling.actions.OptimizeTableEating}).
 * <p>
 * Deliberately kept as a standalone file rather than adding methods to {@code NFoodInfo}
 * itself: it only reads package-private members that {@code NFoodInfo} already exposes
 * to its own package, so this file can live entirely on its own with zero edits to
 * {@code NFoodInfo.java} -- keeping that upstream-tracked file merge-clean.
 */
public final class NFoodOptimizerSupport
{
    private NFoodOptimizerSupport() {}

    /** Live expected FEP yield of this item right now (subscription/table/realm/satiation-adjusted). */
    public static double expectedFep(NFoodInfo info)
    {
        return info.calcExpectedFep();
    }

    /** Live remaining FEP needed to fill the current bar, variety-adjusted. */
    public static double neededFepForBar(NFoodInfo info)
    {
        return info.calcNeededFep();
    }

    /** Whether eating this item's food name would earn a fresh variety credit this bar. */
    public static boolean isNewVarietyForBar(NFoodInfo info)
    {
        return info.isVarity;
    }

    /**
     * Per-attribute FEP breakdown of this item, keyed by short attribute code (e.g.
     * "str", "str2"). Reconstructed from {@code fepSum} (public) and {@code searchImage}
     * (package-private percentage-per-code map already computed in the constructor)
     * rather than the raw {@code Event[]}, since the latter is an inherited protected
     * field only {@code NFoodInfo} itself (not sibling classes) can read directly.
     */
    public static Map<String, Double> attrFepBreakdown(NFoodInfo info)
    {
        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<String, Double> e : info.searchImage.entrySet())
        {
            if (e.getKey() != null)
                result.put(e.getKey(), e.getValue() / 100.0 * info.fepSum);
        }
        return result;
    }
}
