package nurgling.iteminfo.bonus.fakes.slot;

import haven.ItemInfo;

import java.util.List;

/** Stand-in for a gilding <em>recipe</em> ({@code ui/tt/slot}): what an ingredient would add, not what is worn. */
public class Slotted extends ItemInfo {
    public final List<ItemInfo> sub;

    public Slotted(List<ItemInfo> sub) {
        super(null);
        this.sub = sub;
    }
}
