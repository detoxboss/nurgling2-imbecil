package nurgling.watch.ui;

import haven.Button;
import haven.Coord;
import haven.GOut;
import haven.Label;
import haven.UI;
import haven.Widget;
import nurgling.NUtils;
import nurgling.actions.bots.VillageWatchBot;
import nurgling.scenarios.BotStep;
import nurgling.watch.WatchProfile;
import nurgling.watch.WatchProfileStore;
import nurgling.widgets.NDropbox;

import java.util.List;

/**
 * The Village Watch controls inside a Scenario step's settings panel: which profile the step runs,
 * and a button to edit profiles. The full editor lives in {@link VillageWatchWindow}, so the panel
 * stays small and every lookout's settings are edited in one place.
 *
 * <p>The step stores only the profile <em>name</em>; the bot copies the profile when it starts.
 */
public final class VillageWatchStepPanel {
    private VillageWatchStepPanel() {
    }

    /**
     * Adds the controls to {@code panel} starting at {@code y}.
     *
     * @param refresh rebuilds the panel (profile list or summary changed)
     * @return the y below the last control
     */
    public static int populate(Widget panel, BotStep step, int y, Runnable refresh) {
        List<String> names = WatchProfileStore.shared().names();
        Object current = step.getSetting(VillageWatchBot.SETTING_PROFILE);
        String selected = (current instanceof String && names.contains(current)) ? (String) current : WatchProfile.DEFAULT_NAME;
        step.setSetting(VillageWatchBot.SETTING_PROFILE, selected);

        panel.add(new Label("Watcher profile:"), new Coord(UI.scale(8), y));
        y += UI.scale(24);
        NDropbox<String> box = new NDropbox<String>(UI.scale(160), Math.min(names.size(), 10), UI.scale(22)) {
            @Override
            protected String listitem(int i) {
                return names.get(i);
            }

            @Override
            protected int listitems() {
                return names.size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }

            @Override
            public void change(String item) {
                super.change(item);
                if (item != null)
                    step.setSetting(VillageWatchBot.SETTING_PROFILE, item);
            }
        };
        box.change(selected);
        panel.add(box, new Coord(UI.scale(8), y));
        y += UI.scale(34);

        panel.add(new Button(UI.scale(160), "Edit Watcher Settings", () -> {
            String name = (String) step.getSetting(VillageWatchBot.SETTING_PROFILE);
            nurgling.NGameUI gui = (panel.ui instanceof nurgling.NUI) ? ((nurgling.NUI) panel.ui).gui : NUtils.getGameUI();
            VillageWatchWindow.open(gui, name, saved -> {
                step.setSetting(VillageWatchBot.SETTING_PROFILE, saved);
                refresh.run();
            });
        }), new Coord(UI.scale(8), y));
        y += UI.scale(34);

        WatchProfile p = WatchProfileStore.shared().get(selected);
        if (p != null) {
            panel.add(new Label("Watcher: " + p.watcher), new Coord(UI.scale(8), y));
            y += UI.scale(18);
            panel.add(new Label("Alerts: " + summary(p)), new Coord(UI.scale(8), y));
            y += UI.scale(18);
            panel.add(new Label("Scan " + p.scanIntervalSec + "s, repeat " + p.cooldownSec + "s"), new Coord(UI.scale(8), y));
            y += UI.scale(24);
        }
        panel.add(new Label("Runs until stopped:"), new Coord(UI.scale(8), y));
        y += UI.scale(18);
        panel.add(new Label("make this the last step."), new Coord(UI.scale(8), y));
        return y + UI.scale(24);
    }

    private static String summary(WatchProfile p) {
        StringBuilder sb = new StringBuilder();
        for (nurgling.watch.WatchCategory c : nurgling.watch.WatchCategory.values()) {
            if (p.enabled(c))
                sb.append(sb.length() == 0 ? "" : "/").append(c.label);
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }
}
