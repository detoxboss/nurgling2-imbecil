package nurgling.iteminfo.bonus.fakes.slots_badslot;

import haven.ItemInfo;

import java.util.ArrayList;
import java.util.Collection;

/** Recognised gilding list whose entries no longer carry an {@code info} list. */
public class ISlots extends ItemInfo {
    public final Collection<SItem> s = new ArrayList<>();

    public ISlots() {
        super(null);
    }

    public static class SItem {
        public final String name;
        public final Object details = null;

        public SItem(String name) {
            this.name = name;
        }
    }
}
