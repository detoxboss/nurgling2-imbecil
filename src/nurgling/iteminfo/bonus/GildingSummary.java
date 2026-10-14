package nurgling.iteminfo.bonus;

import haven.ItemInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * The compact gilding view: what all installed gildings of an item add up to.
 * <p>
 * This is derived from the same {@link AttrBonusCollector#installedGildings} data the per-gilding
 * breakdown renders, so the summary and the breakdown cannot drift apart. It deliberately ignores
 * the item's own {@code AttrMod} (not a gilding) and gilding <em>recipes</em> ({@code Slotted},
 * which belong to the ingredient, not to the item wearing gildings).
 */
public final class GildingSummary {
    private GildingSummary() {}

    /** Combined bonuses of every installed gilding, in display order; entries that net to zero are dropped. */
    public static List<AttrBonus> of(List<? extends ItemInfo> info) {
        List<AttrBonus> ret = new ArrayList<>();
        for(AttrBonus b : AttrBonus.sum(AttrBonusCollector.gildings(info))) {
            if(!b.isZero())
                ret.add(b);
        }
        ret.sort(AttrOrder.COMPARATOR);
        return ret;
    }
}
