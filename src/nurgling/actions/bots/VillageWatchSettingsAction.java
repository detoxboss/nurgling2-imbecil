package nurgling.actions.bots;

import nurgling.NGameUI;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.watch.ui.VillageWatchWindow;

/** Bots-menu entry that opens the Village Watch settings window (profiles, templates, tests, start/stop). */
public class VillageWatchSettingsAction implements Action {
    public static final String BOT_ID = "village_watch_settings";

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        // Runs on a bot thread; widgets belong to the UI, so open under its lock.
        VillageWatchWindow.open(gui, null, null);
        return Results.SUCCESS();
    }
}
