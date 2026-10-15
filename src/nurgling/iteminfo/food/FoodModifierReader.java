package nurgling.iteminfo.food;

import haven.*;
import haven.res.ui.rbuff.RealmBuff;
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
 * <p>
 * Each input is either read, still loading ({@link FoodModifiers#pending()}), or present but not
 * understood ({@link FoodModifiers#unsupported()}). Only a genuinely absent input (no table open, no realm
 * buff, a food that is not salted) counts as neutral.
 */
public final class FoodModifierReader {
    private FoodModifierReader() {}

    public static final String DEEP_WORM_WOUND = "paginae/wound/deepworm";
    private static final String SALT = "salt";

    /** An input exists but is not in a form we can read. */
    private static final class Unsupported extends RuntimeException {
        Unsupported(String why) {
            super(why, null, false, false);
        }
    }

    /**
     * @param foodTypes the food's type indices. {@code BAttrWnd.Constipations} (the game's own satiation
     *                  list) uses them as indices into its entry list, and so does this; they also tell us
     *                  whether the salt entry is already one of the food's own types
     */
    public static FoodModifiers read(NGameUI gui, NGItem item, int[] foodTypes) {
        if(gui == null || gui.chrwdg == null || gui.chrwdg.battr == null || gui.chrwdg.battr.glut == null
           || gui.chrwdg.battr.cons == null)
            return FoodModifiers.unavailable();
        if(!(gui.ui instanceof NUI) || ((NUI)gui.ui).sessInfo == null)
            return FoodModifiers.unavailable();
        NUI.NSessInfo sess = ((NUI)gui.ui).sessInfo;
        FoodModifiers.Account account = sess.isSubscribed ? FoodModifiers.Account.SUBSCRIBED
            : sess.isVerified ? FoodModifiers.Account.VERIFIED : FoodModifiers.Account.NONE;

        try {
            double[] table = table(gui);
            double realm = realm(gui);
            double satiation = satiation(entries(gui.chrwdg.battr.cons.els), foodTypes);
            Double salt = saltSatiation(gui, item, foodTypes);
            return FoodModifiers.of(account, realm, gui.chrwdg.battr.glut.gmod, table[0], satiation, salt, table[1], deepWorm(gui));
        } catch(Loading l) {
            /* a resource or tooltip we need has not arrived; the tooltip is rebuilt once it has */
            return FoodModifiers.pending();
        } catch(Unsupported e) {
            /* better no prediction than a wrong one */
            return FoodModifiers.unsupported();
        }
    }

    private static double[] entries(List<BAttrWnd.Constipations.El> els) {
        double[] ret = new double[els.size()];
        for(int i = 0; i < ret.length; i++)
            ret[i] = els.get(i).a;
        return ret;
    }

    /**
     * The satiation multiplier for a food: the product of the entries its types point to. A type with no
     * entry (the server drops entries that are back to 1.0, and an index past the end can only be such an
     * entry) does not reduce anything.
     *
     * @param entries each satiation entry's multiplier ({@code El.a}; 1 is no effect, above 1 is a bonus)
     */
    static double satiation(double[] entries, int[] types) {
        if(types == null)
            return 1.0;
        double[] hit = new double[types.length];
        int n = 0;
        for(int type : types) {
            if(type >= 0 && type < entries.length)
                hit[n++] = entries[type];
        }
        return FoodModifiers.combineSatiation(java.util.Arrays.copyOf(hit, n));
    }

    /**
     * Salt satiation multiplier, only for salted items and only when the character has such an entry.
     * If the salt entry is already one of the food's own types it is part of the satiation factor and must
     * not be applied again. Not knowing which entry is salt (an entry's resource still loading) throws
     * {@link Loading}; an item that is not salted, or a character without a salt entry, is {@code null}.
     */
    private static Double saltSatiation(NGameUI gui, NGItem item, int[] foodTypes) {
        if(item == null || !isSalted(item))
            return null;
        List<BAttrWnd.Constipations.El> els = gui.chrwdg.battr.cons.els;
        int saltIdx = -1;
        for(int i = 0; i < els.size(); i++) {
            if(SALT.equals(els.get(i).t.res.get().basename())) {
                saltIdx = i;
                break;
            }
        }
        if(saltIdx < 0)
            return null;
        if(foodTypes != null) {
            for(int type : foodTypes) {
                if(type == saltIdx)
                    return null;
            }
        }
        return els.get(saltIdx).a;
    }

    private static boolean isSalted(NGItem item) {
        List<ItemInfo> info = item.info();
        for(ItemInfo ii : info) {
            if(ii instanceof ItemInfo.AdHoc && nurgling.iteminfo.marks.SpiceMarks.Kind.SALT.text.equals(((ItemInfo.AdHoc)ii).str.text))
                return true;
        }
        return false;
    }

    /** {food event multiplier, hunger multiplier} of the open feast table; {1, 1} when none is open. */
    private static double[] table(NGameUI gui) {
        double fep = 1.0, hunger = 1.0;
        Window table = gui.getWindowWithButton("Table", "Feast!");
        if(table != null) {
            for(Widget w = table.child; w != null; w = w.next) {
                if(!(w instanceof Label))
                    continue;
                String text = ((Label)w).text();
                if(TableLabels.isFoodEventBonus(text)) {
                    fep = TableLabels.foodEventMultiplier(text);
                    if(Double.isNaN(fep))
                        throw new Unsupported("table label: " + text);
                } else if(TableLabels.isHungerModifier(text)) {
                    hunger = TableLabels.hungerMultiplier(text);
                    if(Double.isNaN(hunger))
                        throw new Unsupported("table label: " + text);
                }
            }
        }
        return new double[] {fep, hunger};
    }

    /**
     * The food event bonus of the realm buffs, as a fraction. {@code RealmBuff} keeps its tooltip in its
     * own fields (its {@code info()} and {@code rawinfo}), not in the {@code Buff.info} field the base
     * class exposes, so the buff must be asked through {@code info()}; until the server has sent that
     * tooltip the buff's content is not known, which is reported as {@link Loading}.
     */
    public static double realm(NGameUI gui) {
        double bonus = 0;
        for(Widget list = gui.child; list != null; list = list.next) {
            if(!(list instanceof Bufflist))
                continue;
            for(Widget b = list.child; b != null; b = b.next) {
                if(!(b instanceof RealmBuff))
                    continue;
                RealmBuff buff = (RealmBuff)b;
                if(buff.rawinfo == null)
                    throw new Loading("realm buff tooltip");
                for(ItemInfo ii : buff.info()) {
                    if(!(ii instanceof ItemInfo.AdHoc))
                        continue;
                    Double f = TableLabels.realmBonus(((ItemInfo.AdHoc)ii).str.text);
                    if(f == null)
                        continue;
                    if(Double.isNaN(f))
                        throw new Unsupported("realm buff: " + ((ItemInfo.AdHoc)ii).str.text);
                    bonus += f;
                }
            }
        }
        return bonus;
    }

    private static boolean deepWorm(NGameUI gui) {
        if(gui.chrwdg.wound == null || gui.chrwdg.wound.wounds == null)
            return false;
        for(WoundWnd.Wound w : gui.chrwdg.wound.wounds.wounds) {
            if(DEEP_WORM_WOUND.equals(w.res.get().name))
                return true;
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
