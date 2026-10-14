package nurgling.iteminfo.food;

import haven.*;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NUI;

import java.util.List;

/**
 * Gathers the live inputs of {@link FoodModifiers} from one session's UI.
 * <p>
 * The caller passes the {@link NGameUI} that owns the food item, never an ambient "current" one: with
 * several sessions open, a tooltip must only ever see its own character's attributes, satiation,
 * table and wounds.
 */
public final class FoodModifierReader {
    private FoodModifierReader() {}

    public static final String DEEP_WORM_WOUND = "paginae/wound/deepworm";
    private static final String SALT = "salt";

    /**
     * @param satiationPercent the satiation efficiency of this food's type, in percent, as
     *                         {@code NFoodInfo} already determined it (100 = no reduction)
     * @param foodTypes        the food's type indices, as {@code NFoodInfo} resolved satiation from them;
     *                         used to avoid applying salt satiation a second time
     */
    public static FoodModifiers read(NGameUI gui, NGItem item, double satiationPercent, int[] foodTypes) {
        if(gui == null || gui.chrwdg == null || gui.chrwdg.battr == null || gui.chrwdg.battr.glut == null)
            return FoodModifiers.unavailable();
        if(!(gui.ui instanceof NUI) || ((NUI)gui.ui).sessInfo == null)
            return FoodModifiers.unavailable();
        NUI.NSessInfo sess = ((NUI)gui.ui).sessInfo;
        FoodModifiers.Account account = sess.isSubscribed ? FoodModifiers.Account.SUBSCRIBED
            : sess.isVerified ? FoodModifiers.Account.VERIFIED : FoodModifiers.Account.NONE;

        double tableFep, realm;
        try {
            tableFep = gui.getTableMod();
            realm = gui.getRealmMod();
        } catch(RuntimeException e) {
            /* an unexpected label or buff text: better no prediction than a wrong one */
            return FoodModifiers.unavailable();
        }

        return FoodModifiers.of(account, realm, gui.chrwdg.battr.glut.gmod, tableFep, satiationPercent / 100.0,
                                saltSatiation(gui, item, foodTypes), tableHunger(gui), deepWorm(gui));
    }

    /**
     * Salt satiation multiplier, only for salted items and only when the character has such an entry.
     * If the satiation already resolved from the food's own types is the salt entry, it is part of the
     * satiation factor and must not be applied again.
     */
    private static Double saltSatiation(NGameUI gui, NGItem item, int[] foodTypes) {
        if(item == null || !isSalted(item))
            return null;
        try {
            List<BAttrWnd.Constipations.El> els = gui.chrwdg.battr.cons.els;
            if(foodTypes != null) {
                for(int type : foodTypes) {
                    if(type >= 0 && type < els.size() && SALT.equals(els.get(type).t.res.get().basename()))
                        return null;
                }
            }
            for(BAttrWnd.Constipations.El el : els) {
                if(SALT.equals(el.t.res.get().basename()))
                    return el.a;
            }
        } catch(Loading l) {
            /* not resolved yet: reported as not applicable, and the tooltip is rebuilt when it changes */
        }
        return null;
    }

    private static boolean isSalted(NGItem item) {
        List<ItemInfo> info = item.info();
        for(ItemInfo ii : info) {
            if(ii instanceof ItemInfo.AdHoc && "Salted".equals(((ItemInfo.AdHoc)ii).str.text))
                return true;
        }
        return false;
    }

    private static double tableHunger(NGameUI gui) {
        Window table = gui.getWindowWithButton("Table", "Feast!");
        if(table == null)
            return 1.0;
        for(Widget w = table.child; w != null; w = w.next) {
            if(w instanceof Label) {
                String text = ((Label)w).text();
                if(text != null && text.startsWith("Hunger"))
                    return TableLabels.hungerMultiplier(text);
            }
        }
        return 1.0;
    }

    private static boolean deepWorm(NGameUI gui) {
        if(gui.chrwdg.wound == null || gui.chrwdg.wound.wounds == null)
            return false;
        for(WoundWnd.Wound w : gui.chrwdg.wound.wounds.wounds) {
            try {
                if(DEEP_WORM_WOUND.equals(w.res.get().name))
                    return true;
            } catch(Loading l) {
                /* a wound whose resource is still loading cannot be identified yet */
            }
        }
        return false;
    }

    /** Current FEP in the bar, or NaN when the bar is not available. */
    public static double currentFep(NGameUI gui) {
        if(gui == null || gui.chrwdg == null || gui.chrwdg.battr == null || gui.chrwdg.battr.feps == null)
            return Double.NaN;
        double cur = 0;
        for(BAttrWnd.FoodMeter.El el : gui.chrwdg.battr.feps.els)
            cur += el.a;
        return cur;
    }

    /** Cap of the FEP bar, or NaN when the bar is not available. */
    public static double fepCap(NGameUI gui) {
        if(gui == null || gui.chrwdg == null || gui.chrwdg.battr == null || gui.chrwdg.battr.feps == null)
            return Double.NaN;
        return gui.chrwdg.battr.feps.cap;
    }
}
