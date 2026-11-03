package nurgling.actions.bots;

import nurgling.NGameUI;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.archery.AimDiagnosticWindow;

/** Bots-menu entry that opens the temporary archery re-aim diagnostic window. It sends nothing itself. */
public class ArcheryAimDiagnosticAction implements Action {
    public static final String BOT_ID = "archery_aim_diagnostic";

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        AimDiagnosticWindow.open(gui);
        return Results.SUCCESS();
    }
}
