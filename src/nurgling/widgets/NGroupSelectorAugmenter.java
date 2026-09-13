package nurgling.widgets;

import haven.BuddyWnd;
import haven.GameUI;
import haven.MapWnd;
import haven.Polity;
import haven.Widget;
import nurgling.conf.NGroupLabels;

import java.util.WeakHashMap;

/**
 * Detects real, resource-or-first-party-constructed {@link BuddyWnd.GroupSelector} instances once they
 * are genuinely part of a live widget tree ({@link BuddyWnd.GroupSelector#attached()}), classifies the
 * context they were created in, and - for the contexts that want one - attaches a companion control
 * ({@link NGroupSelectorCompanion}) that hides the selector's own colour squares and drives it through
 * its own public {@code select(int)}, exactly the path a click on one of those squares already used.
 *
 * <p>This is the Brodgar-style seam (see {@code docs/fork-customization-ledger.md} for the reference
 * commits this was verified against): the Village and Realm permission windows and the personal-claim
 * ("Stake") permission window are server-distributed resource code with no source in this repository,
 * and each constructs a {@code BuddyWnd.GroupSelector} directly rather than through any {@code @RName}
 * factory - so the only reliable seam into "a group selector just appeared" is a hook on the selector
 * class itself, not on whoever created it.
 *
 * <p>Classification is by structural/resource identity, never by a localized caption: first-party
 * ancestors ({@link BuddyWnd.BuddyInfo}, {@link NKinSettings}) are checked by class directly; resource
 * windows are checked by their runtime class's fully-qualified name, since we hold no compile-time
 * reference to classes we do not vendor.
 */
public class NGroupSelectorAugmenter {
    /** Verified against a vendored copy of this exact resource (see the ledger entry). */
    private static final String CLASS_LANDWINDOW = "haven.res.ui.land.Landwindow";
    /**
     * Inferred from crash-report prose in the reference commits (a real stack trace naming this exact
     * class), not from an independently vendored copy - if this string is wrong, the practical failure
     * mode is simply that Village gets no companion (falls through to "leave alone" below), not a
     * misattributed one; confirm against a live client before relying on it further.
     */
    private static final String CLASS_VILLAGE = "haven.res.ui.vlg.Village";

    private static final WeakHashMap<BuddyWnd.GroupSelector, NGroupSelectorCompanion> companions = new WeakHashMap<>();

    private static final class Target {
        final NGroupLabels.Scope scope;
        final int lo, hi;
        final String owner;
        Target(NGroupLabels.Scope scope, int lo, int hi, String owner) {
            this.scope = scope;
            this.lo = lo;
            this.hi = hi;
            this.owner = owner;
        }
    }

    public static void attached(BuddyWnd.GroupSelector sel) {
        if(companions.containsKey(sel))
            return;
        Target t = classify(sel);
        if(t == null)
            return;
        NGroupSelectorCompanion companion = new NGroupSelectorCompanion(sel, t.scope, t.owner, t.lo, t.hi);
        companions.put(sel, companion);
        sel.hide();
        sel.parent.add(companion, sel.c);
    }

    /** The owning character's stable id, for Kin-scoped labels - never an ambient "current session"
     *  accessor, always resolved from the selector's own owning session. */
    private static String chrid(Widget w) {
        GameUI gui = w.getparent(GameUI.class);
        return((gui != null) ? gui.chrid : "");
    }

    public static void detached(BuddyWnd.GroupSelector sel) {
        NGroupSelectorCompanion companion = companions.remove(sel);
        if(companion != null)
            companion.destroy();
    }

    private static Target classify(BuddyWnd.GroupSelector sel) {
        /* Map-marker colour picker: a plain, unrelated GroupSelector use. Leave it exactly as simple
         * as it has always been. */
        if(sel.getparent(MapWnd.class) != null)
            return null;
        /* Kin per-buddy panel and the Kin notification-settings picker: both first-party, both want
         * the full assignable range under the Kin label namespace. */
        if(sel.getparent(BuddyWnd.BuddyInfo.class) != null)
            return new Target(NGroupLabels.Scope.KIN, 0, BuddyWnd.ncolors - 1, chrid(sel));
        if(sel.getparent(NKinSettings.class) != null)
            return new Target(NGroupLabels.Scope.KIN, 0, BuddyWnd.ncolors - 1, chrid(sel));

        Widget parent = sel.parent;
        if((parent != null) && CLASS_LANDWINDOW.equals(parent.getClass().getName())) {
            /* The personal claim's row picks which of the claim's own eight permission rows is being
             * edited - a client-side array bound in the unmodified ui/land resource itself (measured,
             * not assumed: see the ledger entry), not a server data-model limit. Groups above the
             * eighth are not offered here, and it uses the Kin label namespace (a claim's permissions
             * are granted to the owning character's own Kin groups, not a separate namespace). */
            return new Target(NGroupLabels.Scope.KIN, 0, BuddyWnd.nquick - 1, chrid(sel));
        }

        Polity polity = sel.getparent(Polity.class);
        if(polity != null) {
            if(CLASS_VILLAGE.equals(polity.getClass().getName()))
                return new Target(NGroupLabels.Scope.VILLAGE, 0, BuddyWnd.ncolors - 1, polity.name);
            /* Realm, or any other/future Polity subtype: deliberately left alone rather than guessing
             * it should share the Village label namespace. */
            return null;
        }

        return null;
    }
}
