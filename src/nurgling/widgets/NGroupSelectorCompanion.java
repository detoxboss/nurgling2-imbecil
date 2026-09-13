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
 * Client-side-only companion for one real, already-existing {@link BuddyWnd.GroupSelector}: a compact
 * dropdown listing a configured range of groups (colour swatch + number + optional custom label), plus
 * a small button opening the label-editor popup.
 *
 * <p>Never constructs or replaces the real selector - {@link NGroupSelectorAugmenter} hides an existing
 * instance and adds this as a sibling at that instance's own position, sized to fit within
 * {@link BuddyWnd.GroupSelector#basesz} so the resource window (or first-party panel) that laid the
 * real selector out never notices the substitution. Choosing a group here drives the real selector
 * through its own public {@code select(int)} - the same path a colour-square click would have taken -
 * so whatever protocol message its own {@code changed(int)} override sends (which this class never
 * needs to know) fires exactly as it always did. Because resource code may change the real selector's
 * group without going through {@code select()} (e.g. a raw field write, or the server re-driving it),
 * this class mirrors {@code real.group} into its own displayed value every tick rather than relying
 * solely on {@code changed()}/{@code update()} overrides to notice.
 */
public class NGroupSelectorCompanion extends Widget {
    private static final int gap = BuddyWnd.margin1;
    private static final int rowh = BuddyWnd.GroupSelector.basesz.y;
    private static final int editw = rowh;
    private static final int dropw = BuddyWnd.GroupSelector.basesz.x - gap - editw;

    private final BuddyWnd.GroupSelector real;
    private final NGroupLabels.Scope scope;
    /** Kin: the owning character's {@code chrid}. Village: the polity's own {@code name} (the most
     *  stable identity available - see {@link NGroupLabels}'s own doc for the tradeoff). */
    private final String owner;
    private final int lo, hi;
    private final GroupDropbox dropdown;
    private final EditButton edit;
    private NGroupLabelPopup labelPopup;

    NGroupSelectorCompanion(BuddyWnd.GroupSelector real, NGroupLabels.Scope scope, String owner, int lo, int hi) {
        super(new Coord(dropw + gap + editw, rowh));
        this.real = real;
        this.scope = scope;
        this.owner = owner;
        this.lo = lo;
        this.hi = hi;
        dropdown = add(new GroupDropbox(real.group), 0, 0);
        edit = add(new EditButton(), dropdown.sz.x + gap, 0);
    }

    public void tick(double dt) {
        super.tick(dt);
        if(dropdown.sel != real.group)
            dropdown.sel = real.group;
    }

    void setLabelPopup(NGroupLabelPopup popup) {
        labelPopup = popup;
    }

    void clearLabelPopup(NGroupLabelPopup popup) {
        if(labelPopup == popup)
            labelPopup = null;
    }

    /** Closes any label-editor popup this companion currently has open, saving its pending edit first. */
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
        String label = NGroupLabels.get(scope, owner, group);
        return(label.isEmpty() ? Integer.toString(group) : (group + " - " + label));
    }

    private class GroupDropbox extends Dropbox<Integer> {
        GroupDropbox(int group) {
            super(dropw, 10, rowh);
            sel = group;
        }

        protected Integer listitem(int i) {
            return(lo + i);
        }

        protected int listitems() {
            return(hi - lo + 1);
        }

        protected void drawitem(GOut g, Integer item, int i) {
            int sw = itemh - UI.scale(4);
            g.chcolor(BuddyWnd.gcolor(item));
            g.frect(new Coord(UI.scale(2), (itemh - sw) / 2), new Coord(sw, sw));
            g.chcolor(Color.WHITE);
            g.text(labelText(item), new Coord(sw + UI.scale(6), (itemh - Text.std.m.getHeight()) / 2));
            g.chcolor();
        }

        /** The critical dispatch: drive the REAL, resource-or-first-party-owned GroupSelector through
         *  its own public select() - the exact path a normal colour-square click on it would take -
         *  so whatever protocol message its own changed()/select() override sends fires unchanged. This
         *  class never sends a wdgmsg and never needs to know what message that is. */
        public void change(Integer item) {
            super.change(item);
            real.select(item);
        }
    }

    /** Fixed-size (matches the companion's own row height) button opening the label-editor popup for whichever group is currently selected. */
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
            NGroupLabelPopup.open(NGroupSelectorCompanion.this, scope, owner, dropdown.sel);
            return(true);
        }
    }

    private static final String EDIT_GLYPH = "...";
}
