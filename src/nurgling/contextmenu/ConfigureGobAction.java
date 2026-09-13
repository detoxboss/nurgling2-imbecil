package nurgling.contextmenu;

import haven.Gob;
import nurgling.actions.Action;
import nurgling.widgets.GobConfigWindow;

/**
 * Opens the settings window for whatever object was clicked, defaulting to an override for just
 * that object (see {@link GobConfigWindow}). Offered on every gob that has a resolved resource
 * name, since the settings behind it are generic display options rather than anything tied to a
 * particular kind of object.
 */
public class ConfigureGobAction implements GobContextAction {

    @Override
    public boolean appliesTo(Gob gob) {
        return gob != null && gob.ngob != null && gob.ngob.name != null;
    }

    @Override
    public String label() {
        return nurgling.i18n.L10n.get("context.configure");
    }

    @Override
    public Action create(Gob gob) {
        // Never called - this is a UI-only entry.
        return null;
    }

    @Override
    public boolean isUiAction() {
        return true;
    }

    @Override
    public void performUi(Gob gob) {
        GobConfigWindow.open(gob.ngob.name, gob.ngob.hash, gob.glob);
    }
}
