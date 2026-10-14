package nurgling.iteminfo.bonus.fakes.slots_alt;

import haven.ItemInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Stand-in for the alternative {@code ui/tt/slots-alt} gilding list ({@code uses}/{@code used} instead of {@code left}). */
public class ISlots extends ItemInfo {
    public final Collection<SItem> s = new ArrayList<>();
    public final int uses, used;

    public ISlots(int uses, int used) {
        super(null);
        this.uses = uses;
        this.used = used;
    }

    public static class SItem {
        public final String name;
        public final List<ItemInfo> info;

        public SItem(String name, List<ItemInfo> info) {
            this.name = name;
            this.info = info;
        }
    }
}
