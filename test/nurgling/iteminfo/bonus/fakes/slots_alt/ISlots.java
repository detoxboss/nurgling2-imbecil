package nurgling.iteminfo.bonus.fakes.slots_alt;

import haven.GSprite;
import haven.ItemInfo;
import haven.Resource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Stand-in for the alternative {@code ui/tt/slots-alt} gilding list: {@code uses}/{@code used}
 * instead of {@code left}, with the same public {@code s} collection of gildings. The real class
 * cannot be built without a running game; {@code NTooltip} and the bonus collector both read this
 * shape by reflection, which is what these tests exercise.
 */
public class ISlots extends ItemInfo {
    public final Collection<SItem> s = new ArrayList<>();
    public final int uses, used;
    public final double pmin = 0.1, pmax = 0.9;
    public final Resource[] attrs = new Resource[0];

    public ISlots(int uses, int used) {
        super(null);
        this.uses = uses;
        this.used = used;
    }

    public static class SItem {
        public final String name;
        public final Resource res = null;
        public final GSprite spr = null;
        public final List<ItemInfo> info;

        public SItem(String name, List<ItemInfo> info) {
            this.name = name;
            this.info = info;
        }
    }
}
