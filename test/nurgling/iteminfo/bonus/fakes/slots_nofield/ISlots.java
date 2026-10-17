package nurgling.iteminfo.bonus.fakes.slots_nofield;

import haven.ItemInfo;

/**
 * A gilding list the reader recognises by name (an {@code ISlots} in a "slots" package) but whose
 * structure has changed: there is no {@code s} collection any more.
 */
public class ISlots extends ItemInfo {
    public final int left = 1;

    public ISlots() {
        super(null);
    }
}
