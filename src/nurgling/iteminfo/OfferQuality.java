package nurgling.iteminfo;

import haven.ItemInfo;
import haven.res.ui.tt.q.qbuff.QBuff;

import java.util.List;

/**
 * The quality of an item offered at a barter stand, read from its tooltip info. Kept out of the
 * server-distributed {@code Shopbox} class so that only a one-line call remains there, and so the rule
 * can be tested without a game.
 * <p>
 * A container that holds something is described by the quality of its contents (that is what the
 * player cares about), otherwise by the item's own quality.
 */
public final class OfferQuality {
    private OfferQuality() {}

    /** The quality, or null if the info carries none (yet). */
    public static Double of(List<? extends ItemInfo> infos) {
        if(infos == null)
            return null;
        for(ItemInfo ii : infos) {
            if(ii instanceof ItemInfo.Contents) {
                for(ItemInfo sub : ((ItemInfo.Contents)ii).sub) {
                    if(sub instanceof QBuff)
                        return ((QBuff)sub).q;
                }
            }
        }
        for(ItemInfo ii : infos) {
            if(ii instanceof QBuff)
                return ((QBuff)ii).q;
        }
        return null;
    }

    /** The label text for a quality, truncated to a whole number as Hurricane and the stand's own price quality do. */
    public static String label(double quality) {
        return "Quality: " + (int)quality;
    }
}
