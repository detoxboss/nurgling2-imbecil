package nurgling.widgets;

import haven.Button;
import haven.Coord;
import haven.GameUI;
import haven.Label;
import haven.TextEntry;
import haven.UI;
import haven.Widget;
import haven.Window;
import nurgling.conf.NGroupLabels;
import nurgling.i18n.L10n;

/**
 * Small, Nurgling-owned popup for editing one group's client-side custom label.
 *
 * <p>Deliberately a free-floating {@link Window} added to the root/GameUI, never a child of
 * {@link NExtendedGroupSelector} or anything the server-provided Village permission window owns -
 * so it can never affect either one's size or layout, no matter how long a label gets.
 */
public class NGroupLabelPopup extends Window {
    private final NExtendedGroupSelector owner;
    private final NGroupLabels.Scope scope;
    private final int group;
    private final TextEntry entry;
    private boolean saved = false;

    /**
     * Opens a popup for {@code group}, replacing any popup this selector already had open.
     *
     * <p>Attaches to {@code owner}'s OWN owning session ({@code owner.getparent(GameUI.class)}),
     * never an ambient "current session" accessor - this fork runs multiple sessions in one
     * process, and a popup opened from one session's Kin/Village panel must land in that same
     * session's widget tree, not whichever session happens to be foregrounded elsewhere.
     */
    public static void open(NExtendedGroupSelector owner, NGroupLabels.Scope scope, int group) {
        owner.closeLabelPopup();
        NGroupLabelPopup popup = new NGroupLabelPopup(owner, scope, group);
        owner.setLabelPopup(popup);
        GameUI gui = owner.getparent(GameUI.class);
        Widget host = (gui != null) ? gui : owner.ui.root;
        host.add(popup, owner.ui.mc);
    }

    private NGroupLabelPopup(NExtendedGroupSelector owner, NGroupLabels.Scope scope, int group) {
        super(UI.scale(new Coord(220, 90)), title(scope, group));
        this.owner = owner;
        this.scope = scope;
        this.group = group;

        int margin = UI.scale(10);
        int y = margin;
        add(new Label(L10n.get("group.popup_label")), new Coord(margin, y));
        y += UI.scale(18);

        entry = add(new TextEntry(UI.scale(200), NGroupLabels.get(scope, group)) {
            {dshow = true;}
            public void activate(String text) {
                destroy();
            }
        }, new Coord(margin, y));
        y += entry.sz.y + UI.scale(8);

        add(new Button(UI.scale(80), L10n.get("group.popup_save")) {
            public void click() {
                super.click();
                destroy();
            }
        }, new Coord(margin, y));

        pack();
    }

    private static String title(NGroupLabels.Scope scope, int group) {
        String key = (scope == NGroupLabels.Scope.VILLAGE) ? "group.popup_title_village" : "group.popup_title_kin";
        return(L10n.get(key, group));
    }

    /** Saves whatever is currently in the box (idempotent) and detaches from the owning selector. Runs on every close path - titlebar cross, Escape, Enter, and the Save button alike - so an edit is never silently lost. */
    @Override
    public void destroy() {
        if(!saved) {
            saved = true;
            NGroupLabels.set(scope, group, entry.text());
        }
        owner.clearLabelPopup(this);
        super.destroy();
    }

    /** The titlebar cross / Escape send a {@code close} wdgmsg rather than calling destroy() directly. */
    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if(msg.equals("close")) {
            destroy();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }
}
