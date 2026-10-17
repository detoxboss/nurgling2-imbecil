package nurgling.iteminfo.bonus.fakes.slots;

import haven.ItemInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Stand-in for {@code haven.res.ui.tt.slots.ISlots}: same simple name, a package containing
 * "slots", and the same public {@code s} collection of gildings that each expose {@code name} and
 * {@code info}. The real class cannot be built without a running game.
 */
public class ISlots extends ItemInfo {
    public final Collection<SItem> s = new ArrayList<>();

    public ISlots() {
        super(null);
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
