package nurgling.iteminfo.bonus;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Stable display order for attribute rows: the nine base attributes in the order of the character
 * sheet, then the abilities in the order of the abilities tab, then everything else by name.
 * Keyed on the attribute resource's last path component, so it does not depend on the game language.
 */
public final class AttrOrder {
    private AttrOrder() {}

    private static final List<String> ORDER = Arrays.asList(
        "str", "agi", "int", "con", "prc", "csm", "dex", "wil", "psy",
        "unarmed", "melee", "ranged", "explore", "stealth", "sewing", "smithing",
        "masonry", "carpentry", "cooking", "farming", "survive", "lore");

    static int rank(String key) {
        int slash = key.lastIndexOf('/');
        String base = (slash >= 0) ? key.substring(slash + 1) : key;
        int i = ORDER.indexOf(base);
        return (i >= 0) ? i : ORDER.size();
    }

    public static final Comparator<AttrBonus> COMPARATOR = Comparator
        .comparingInt((AttrBonus b) -> rank(b.key))
        .thenComparing((AttrBonus b) -> b.name)
        .thenComparingInt((AttrBonus b) -> b.kind.ordinal());
}
