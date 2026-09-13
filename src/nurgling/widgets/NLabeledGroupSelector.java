package nurgling.widgets;

import haven.BuddyWnd;
import haven.Label;
import haven.TextEntry;
import nurgling.conf.NGroupLabels;
import nurgling.i18n.L10n;

/**
 * Extends the shared {@link BuddyWnd.GroupSelector} with a compact client-side label editor.
 *
 * Used only where a person is actually assigning or inspecting a Kin or Village permission group:
 * the {@code grp} resource-widget factory (Village's top-level and per-member selectors) and the
 * Kin per-buddy panel. Unrelated pickers such as {@code MapWnd}'s map-marker colour selector and
 * {@code NKinSettings}'s notification-group picker deliberately keep using the plain
 * {@link BuddyWnd.GroupSelector} and never gain this editor.
 *
 * Growing this widget via {@link #pack()} also grows the {@code Coord} it reports through
 * {@code sz}, so any container that positions its own children relative to this selector's actual
 * size (rather than a hardcoded offset) reflows correctly on its own.
 */
public class NLabeledGroupSelector extends BuddyWnd.GroupSelector {
    private final NGroupLabels.Scope scope;
    private final Label caption;
    private final TextEntry editor;

    public NLabeledGroupSelector(int group, NGroupLabels.Scope scope) {
        super(group);
        this.scope = scope;
        caption = add(new Label(captiontext(group)), 0, sz.y + BuddyWnd.margin1);
        editor = add(new TextEntry(sz.x, NGroupLabels.get(scope, group)) {
            {dshow = true;}
            public void activate(String text) {
                save(text);
            }
            public void lostfocus() {
                super.lostfocus();
                save(text());
            }
        }, 0, caption.c.y + caption.sz.y + BuddyWnd.margin1);
        pack();
    }

    private void save(String text) {
        NGroupLabels.set(scope, this.group, text);
    }

    private String captiontext(int group) {
        return L10n.get("group.label_prompt", group);
    }

    @Override
    public void update(int group) {
        if(group == this.group)
            return;
        /* Commit whatever's in the box for the group being left before switching tiles,
           so a pending, not-yet-confirmed edit is never silently lost. */
        save(editor.text());
        super.update(group);
        caption.settext(captiontext(group));
        editor.settext(NGroupLabels.get(scope, group));
    }

    @Override
    protected String grouptip(int group) {
        String label = NGroupLabels.get(scope, group);
        return label.isEmpty() ? L10n.get("group.tooltip", group) : L10n.get("group.tooltip_labeled", group, label);
    }
}
