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
 * Client-side-only Kin/Village group control: a single dropdown listing all {@code 0..BuddyWnd.ncolors-1}
 * groups (colour swatch + number + optional custom label), plus a small button opening the
 * label-editor popup - sized to fit in the same footprint the plain, unmodified
 * {@link BuddyWnd.GroupSelector} already occupies (see {@link BuddyWnd.GroupSelector#basesz}), so it
 * drops into the Kin panel and the server-provided Village permission window without growing either
 * one's row.
 *
 * <p>An earlier version of this class kept the 8 quick-colour squares visible next to the dropdown.
 * That made the control roughly 350px wide against a ~160px budget (BuddyWnd itself is only
 * {@code UI.scale(263)} wide, and the Village window is no wider), so most of it was clipped off both
 * panels in practice. The quick squares are dropped from the visible layout entirely; the dropdown
 * alone covers the same groups they did (0..7) plus everything above.
 *
 * <p>This is the Brodgar-style seam: rather than reshaping {@code BuddyWnd.GroupSelector} itself
 * (which broke the server-provided Village permission window's layout when it was tried), this class
 * keeps a plain, unmodified instance of it purely as an internal dispatch delegate - never added to
 * this widget's own child/render tree, so it has no visual footprint at all - and drives it through
 * its own public {@code select}/{@code update} methods, exactly the path a normal mouse click on one
 * of its colour squares would have used. Whatever the owning UI (Kin panel, or the Village resource
 * via the {@code grp} widget factory) does in its own {@code changed(int)} override keeps working
 * unchanged; this class never sends a wdgmsg itself.
 */
public class NExtendedGroupSelector extends Widget {
    private static final int gap = BuddyWnd.margin1;
    private static final int rowh = BuddyWnd.GroupSelector.basesz.y;
    private static final int editw = rowh;
    private static final int dropw = BuddyWnd.GroupSelector.basesz.x - gap - editw;

    /** Never added as a child - see the class comment. Exists purely so a dropdown pick can be
     *  dispatched through the exact same select()/update()/changed() path a real colour-square
     *  click on an ordinary GroupSelector would use, without this class inventing its own. */
    private final BuddyWnd.GroupSelector delegate;
    private final GroupDropbox dropdown;
    private final EditButton edit;
    private final NGroupLabels.Scope scope;
    private NGroupLabelPopup labelPopup;

    public NExtendedGroupSelector(int group, NGroupLabels.Scope scope) {
        super(new Coord(dropw + gap + editw, rowh));
        this.scope = scope;
        delegate = new BuddyWnd.GroupSelector(group) {
            protected void changed(int group) {
                dropdownSync(group);
                NExtendedGroupSelector.this.changed(group);
            }
        };
        dropdown = add(new GroupDropbox(group), 0, 0);
        edit = add(new EditButton(), dropdown.sz.x + gap, 0);
    }

    /** Overridden by the owning UI (Kin panel / Village {@code grp} factory), exactly like {@link BuddyWnd.GroupSelector#changed}. */
    protected void changed(int group) {
    }

    /** Reflects an externally-driven group change (e.g. the server updating a Kin buddy's group) onto both the delegate and the dropdown, without re-notifying the owner. */
    public void update(int group) {
        delegate.update(group);
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
            super(dropw, 10, rowh);
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

        /** The critical dispatch: drive the hidden, unmodified GroupSelector delegate through its
         *  own public select() - the exact path a normal colour-square click would take - rather
         *  than sending any protocol message ourselves. */
        public void change(Integer item) {
            super.change(item);
            delegate.select(item);
        }
    }

    /** Fixed-size (matches the control's own row height) button opening the label-editor popup for whichever group is currently selected. */
    private class EditButton extends Widget {
        EditButton() {
            super(new Coord(editw, rowh));
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
