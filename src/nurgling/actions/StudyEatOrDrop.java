package nurgling.actions;

import haven.WItem;
import nurgling.NFlowerMenu;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.tasks.NFlowerMenuIsClosed;
import nurgling.tasks.WaitNoItems;
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
 * Drop mechanics: NUtils.drop() on a stack container's own top-level WItem drops the WHOLE stack in
 * one "drop" wdgmsg (confirmed by this codebase's own pre-existing NConfig.Key.autoDropper feature,
 * NWItem.java:155-175, which uses this exact call to mean "drop all of these"). NInventory.
 * getItems(alias) does NOT return that top-level container, though: for a stack, it recurses into
 * the container's contents and returns the matching LEAF CHILDREN inside it instead (see
 * GetItems.checkContainer() - it only ever adds an item once item.item.contents is null, and
 * recurses rather than adding when contents is a stack). NUtils.drop() on one of those child WItems
 * sends "drop" to that specific child's own wdgid, which drops only that one unit - so for a
 * matching stack, this class's drop loop below is (and needs to be) already effectively a per-child
 * drop, not a single whole-container drop; it only reduces to "one call per match" when every match
 * happens to be a loose item. See docs/inventory-grid-system.md for the full top-level-vs-leaf-child
 * distinction and nurgling.tools.InventorySnapshot for LP Assistant's own topology-aware model of it.
 *
 * The drop calls are throttled to one per DROP_INTERVAL_MS, matching NWItem's own separate
 * auto-dropper throttle (NWItem.AUTODROP_INTERVAL_MS). This turned out to be the actual cause of
 * "drop all of a name" reliably leaving exactly one unit behind per stack: firing several "drop"
 * wdgmsgs back to back (as an earlier, untimed version of this class did, and as
 * nurgling.actions.bots.Dropper/DropTargets's own drop loops still do) trips the server's flood/
 * spam protection, which silently ignores some of the burst instead of erroring - not a stale
 * WItem reference, which an earlier version of this class blamed and "fixed" by requerying, without
 * actually resolving the drops-go-missing symptom.
 *
 * Every "drop" wdgmsg is fire-and-forget at the protocol level - the server removes the item from
 * the inventory asynchronously, on its own schedule. Confirmed live 2026-08: a caller that assumes
 * the drop already happened the instant this method returns (as LpAssistantBot's inventory-space
 * check used to) can see the item still sitting in the grid, either because a fresh free-space
 * check ran before the delta arrived, or because the drop was silently dropped by the server for
 * some other reason (e.g. the "bucket must be carried when not empty" class of rejection). run()
 * therefore re-sends drops for whatever's still actually present and waits (bounded per attempt -
 * see DROP_CONFIRM_TIMEOUT_MS/MAX_DROP_ATTEMPTS) up to a few times, and reports whether the name
 * was fully cleared via the returned Results/dropConfirmed() so the caller can tell "cleared" from
 * "still pending" instead of assuming. Every attempt re-queries getItems(alias) fresh rather than
 * reusing a WItem from an earlier attempt, so a retry can never send a drop against a stale
 * reference (e.g. a stack that's since collapsed to nothing, or a slot the server already cleared).
 */
public class StudyEatOrDrop implements Action {
    // Matches NWItem.AUTODROP_INTERVAL_MS - the interval the codebase's own pre-existing
    // auto-dropper already established as safe for the server's flood protection.
    private static final long DROP_INTERVAL_MS = 150;
    // How long to wait, per attempt, for the server to actually remove every dropped unit of this
    // name before re-checking and (if anything is still there) retrying - see class doc.
    private static final long DROP_CONFIRM_TIMEOUT_MS = 3000;
    // Bounded retry count for the drop-and-confirm loop - each attempt re-queries live state fresh
    // (never a stale WItem reference), so a retry only ever re-sends drops for items genuinely
    // still present. Worst case DROP_CONFIRM_TIMEOUT_MS * MAX_DROP_ATTEMPTS before giving up.
    private static final int MAX_DROP_ATTEMPTS = 3;
    private static long lastDropMs = 0;

    private final WItem item;
    private final boolean autoEat;
    private final boolean autoDrop;
    private boolean dropConfirmed = true;

    public StudyEatOrDrop(WItem item, boolean autoEat, boolean autoDrop) {
        this.item = item;
        this.autoEat = autoEat;
        this.autoDrop = autoDrop;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        dropConfirmed = true;
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
                NAlias alias = new NAlias(name);
                dropConfirmed = false;
                for (int attempt = 1; attempt <= MAX_DROP_ATTEMPTS; attempt++) {
                    // Fresh re-query every attempt - never reuses a WItem from an earlier pass, so
                    // a retry can't send a duplicate drop against a stale/already-gone reference.
                    ArrayList<WItem> matches = gui.getInventory().getItems(alias);
                    if (matches.isEmpty()) {
                        dropConfirmed = true;
                        break;
                    }
                    for (WItem match : matches) {
                        throttledDrop(match);
                    }
                    WaitNoItems settled = new WaitNoItems(gui.getInventory(), alias, DROP_CONFIRM_TIMEOUT_MS);
                    NUtils.getUI().core.addTask(settled);
                    if (!settled.timedOut()) {
                        dropConfirmed = true;
                        break;
                    }
                }
            }
        }

        return dropConfirmed ? Results.SUCCESS() : Results.ERROR(null);
    }

    /** True unless a drop was requested this run() and never confirmed within the timeout. */
    public boolean dropConfirmed() {
        return dropConfirmed;
    }

    private static synchronized void throttledDrop(WItem item) throws InterruptedException {
        long wait = DROP_INTERVAL_MS - (System.currentTimeMillis() - lastDropMs);
        if (wait > 0)
            Thread.sleep(wait);
        NUtils.drop(item);
        lastDropMs = System.currentTimeMillis();
    }
}
