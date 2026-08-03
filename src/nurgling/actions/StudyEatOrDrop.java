package nurgling.actions;

import haven.WItem;
import nurgling.NFlowerMenu;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.tasks.NFlowerMenuIsClosed;
import nurgling.tools.NAlias;

import java.util.ArrayList;

/**
 * Per-item-name decision for keeping a gathering bot's inventory from filling up: right-click one
 * instance of the item (the same "iact" interaction FindAndEatItems already uses for "Eat") and,
 * if Study/Eat is enabled and offered, take it once; then, if dropping is enabled, drop every
 * instance of that item name in the inventory - not just this one slot.
 *
 * Eat/Study and Drop are independent, caller-controlled steps (two separate settings-window
 * checkboxes) rather than one combined toggle, so either can be used without the other.
 *
 * Drop mechanics: NUtils.drop() on a stack container drops the WHOLE stack in one "drop" wdgmsg
 * (confirmed by this codebase's own pre-existing NConfig.Key.autoDropper feature, NWItem.java:
 * 155-175, which uses this exact call to mean "drop all of these") - so getItems(alias) is queried
 * ONCE and every distinct match (each already either a loose item or a whole stack container - see
 * docs/inventory-grid-system.md) gets exactly one drop call, not a requery-and-repeat loop.
 *
 * The drop calls are throttled to one per DROP_INTERVAL_MS, matching NWItem's own separate
 * auto-dropper throttle (NWItem.AUTODROP_INTERVAL_MS). This turned out to be the actual cause of
 * "drop all of a name" reliably leaving exactly one unit behind per stack: firing several "drop"
 * wdgmsgs back to back (as an earlier, untimed version of this class did, and as
 * nurgling.actions.bots.Dropper/DropTargets's own drop loops still do) trips the server's flood/
 * spam protection, which silently ignores some of the burst instead of erroring - not a stale
 * WItem reference, which an earlier version of this class blamed and "fixed" by requerying, without
 * actually resolving the drops-go-missing symptom.
 */
public class StudyEatOrDrop implements Action {
    // Matches NWItem.AUTODROP_INTERVAL_MS - the interval the codebase's own pre-existing
    // auto-dropper already established as safe for the server's flood protection.
    private static final long DROP_INTERVAL_MS = 150;
    private static long lastDropMs = 0;

    private final WItem item;
    private final boolean autoEat;
    private final boolean autoDrop;

    public StudyEatOrDrop(WItem item, boolean autoEat, boolean autoDrop) {
        this.item = item;
        this.autoEat = autoEat;
        this.autoDrop = autoDrop;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        if (autoEat && gui.getInventory().getItems().contains(item)) {
            item.item.wdgmsg("iact", item.c, 0);
            NFlowerMenu fm = NUtils.getFlowerMenu();
            if (fm != null && fm.nopts != null && fm.nopts.length > 0) {
                if (fm.hasOpt("Study")) {
                    fm.chooseOpt("Study");
                } else if (fm.hasOpt("Eat")) {
                    fm.chooseOpt("Eat");
                } else {
                    fm.wdgmsg("cl", -1);
                }
                NUtils.getUI().core.addTask(new NFlowerMenuIsClosed());
            }
        }

        if (autoDrop) {
            String name = ((NGItem) item.item).name();
            if (name != null) {
                for (WItem match : gui.getInventory().getItems(new NAlias(name))) {
                    throttledDrop(match);
                }
            }
        }

        return Results.SUCCESS();
    }

    private static synchronized void throttledDrop(WItem item) throws InterruptedException {
        long wait = DROP_INTERVAL_MS - (System.currentTimeMillis() - lastDropMs);
        if (wait > 0)
            Thread.sleep(wait);
        NUtils.drop(item);
        lastDropMs = System.currentTimeMillis();
    }
}
