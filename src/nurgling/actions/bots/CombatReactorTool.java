package nurgling.actions.bots;

import haven.Coord;
import nurgling.NGameUI;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.widgets.NCombatReactorTool;

/**
 * Bots-menu front-end for the combat reactor: opens/closes the tiny
 * Start/Stop + key-capture popup ({@link NCombatReactorTool}). The reactor's
 * actual logic already runs continuously in the always-present
 * {@code nurgling.widgets.NCombatReactor} overlay widget/{@code
 * nurgling.combat.CombatReactorController} - this popup only edits the same
 * config/keybinding those already read, so there is nothing else to start
 * here.
 */
public class CombatReactorTool implements Action {

    private static NCombatReactorTool currentTool = null;

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        if(currentTool != null && currentTool.parent != null) {
            currentTool.stopTool();
            currentTool.reqdestroy();
            currentTool = null;
            return Results.SUCCESS();
        }

        currentTool = new NCombatReactorTool();
        Coord center = new Coord(gui.sz.x / 2 - currentTool.sz.x / 2, gui.sz.y / 2 - currentTool.sz.y / 2 - 200);
        gui.add(currentTool, center);

        return Results.SUCCESS();
    }
}
