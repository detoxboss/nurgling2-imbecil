package nurgling.widgets;

import haven.BuddyWnd;
import haven.Coord;
import haven.Dropbox;
import haven.GOut;
import haven.Text;
import haven.UI;
import haven.Widget;
import nurgling.conf.NGroupLabels;

import java.awt.Color;

/**
 * Client-side-only extension of the shared, unmodified {@link BuddyWnd.GroupSelector}: the same
 * compact one-row quick-colour squares (groups {@code 0..BuddyWnd.nquick-1}), plus a numeric
 * dropdown giving access to the full {@code 0..BuddyWnd.ncolors-1} range, plus a small "Edit" button
 * for a client-side custom label - all without growing past the wrapped selector's own height.
 *
 * <p>This is the Brodgar-style seam: rather than reshaping {@code BuddyWnd.GroupSelector} itself
 * (which broke the server-provided Village permission window's layout when it was tried), this class
 * wraps an ordinary instance of it and drives it through its own public {@code select}/{@code update}
 * methods - exactly the path a normal mouse click on one of its colour squares already uses. Whatever
 * the owning UI (Kin panel, or the Village resource via the {@code grp} widget factory) does in its
 * own {@code changed(int)} override keeps working unchanged; this class never sends a wdgmsg itself.
 */
public class NExtendedGroupSelector extends Widget {
    private static final int gap = BuddyWnd.margin1;
    private static final int dropw = UI.scale(160);

    public final BuddyWnd.GroupSelector quick;
    private final GroupDropbox dropdown;
    private final EditButton edit;
    private final NGroupLabels.Scope scope;
    private NGroupLabelPopup labelPopup;

    public NExtendedGroupSelector(int group, NGroupLabels.Scope scope) {
        super(Coord.z);
        this.scope = scope;
        quick = add(new BuddyWnd.GroupSelector(group) {
            protected void changed(int group) {
                dropdownSync(group);
                NExtendedGroupSelector.this.changed(group);
            }
        }, Coord.z);
        dropdown = add(new GroupDropbox(group), quick.sz.x + gap, 0);
        edit = add(new EditButton(), dropdown.c.x + dropdown.sz.x + gap, 0);
        resize(new Coord(edit.c.x + edit.sz.x, quick.sz.y));
    }

    /** Overridden by the owning UI (Kin panel / Village {@code grp} factory), exactly like {@link BuddyWnd.GroupSelector#changed}. */
    protected void changed(int group) {
    }

    /** Reflects an externally-driven group change (e.g. the server updating a Kin buddy's group) onto both the quick squares and the dropdown, without re-notifying the owner. */
    public void update(int group) {
        quick.update(group);
        dropdownSync(group);
    }

    private void dropdownSync(int group) {
        dropdown.sel = group;
    }

    void setLabelPopup(NGroupLabelPopup popup) {
        labelPopup = popup;
    }

    void clearLabelPopup(NGroupLabelPopup popup) {
        if(labelPopup == popup)
            labelPopup = null;
    }

    /** Closes any label-editor popup this selector currently has open, saving its pending edit first. */
    void closeLabelPopup() {
        NGroupLabelPopup p = labelPopup;
        labelPopup = null;
        if(p != null)
            p.destroy();
    }

    @Override
    public void destroy() {
        closeLabelPopup();
        super.destroy();
    }

    private String labelText(int group) {
        String label = NGroupLabels.get(scope, group);
        return(label.isEmpty() ? Integer.toString(group) : (group + " - " + label));
    }

    private class GroupDropbox extends Dropbox<Integer> {
        GroupDropbox(int group) {
            super(dropw, 10, quick.sz.y);
            sel = group;
        }

        protected Integer listitem(int i) {
            return(i);
        }

        protected int listitems() {
            return(BuddyWnd.ncolors);
        }

        protected void drawitem(GOut g, Integer item, int i) {
            int sw = itemh - UI.scale(4);
            g.chcolor(BuddyWnd.gcolor(item));
            g.frect(new Coord(UI.scale(2), (itemh - sw) / 2), new Coord(sw, sw));
            g.chcolor(Color.WHITE);
            g.text(labelText(item), new Coord(sw + UI.scale(6), (itemh - Text.std.m.getHeight()) / 2));
            g.chcolor();
        }

        /** The critical dispatch: drive the wrapped, unmodified GroupSelector through its own public
         *  select() - the exact path a normal colour-square click already takes - rather than sending
         *  any protocol message ourselves. */
        public void change(Integer item) {
            super.change(item);
            quick.select(item);
        }
    }

    /** Fixed-size (matches the selector's own row height) button opening the label-editor popup for whichever group is currently selected. */
    private class EditButton extends Widget {
        EditButton() {
            super(new Coord(quick.sz.y, quick.sz.y));
        }

        public void draw(GOut g) {
            g.chcolor(new Color(60, 60, 60));
            g.frect(Coord.z, sz);
            g.chcolor(Color.WHITE);
            g.atext(EDIT_GLYPH, sz.div(2), 0.5, 0.5);
            g.chcolor();
        }

        public boolean mousedown(MouseDownEvent ev) {
            NGroupLabelPopup.open(NExtendedGroupSelector.this, scope, dropdown.sel);
            return(true);
        }
    }

    private static final String EDIT_GLYPH = "...";
}
