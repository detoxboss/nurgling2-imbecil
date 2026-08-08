package nurgling.tasks;

import nurgling.NInventory;
import nurgling.tools.InventorySnapshot;
import nurgling.tools.NAlias;

/**
 * Fires the instant the FIRST unit of one specific harvest product appears, by any of four
 * independent, non-exclusive signals - whichever is observed first wins:
 *
 * 1. NEW_ITEM - a genuinely new top-level item/stack matching the product (InventorySnapshot.Delta.
 *    newLooseItems/newWholeStacks against the pre-harvest baseline) - a unit wdgid that was not
 *    present ANYWHERE in the baseline's identity universe, so a pre-existing matching stack alone
 *    can never trigger this, even if it's been re-parented since (see InventorySnapshot's own doc).
 * 2. STACK_GROWTH - a new child merged into a stack, identified by child wdgid absent from the
 *    baseline's identity universe (InventorySnapshot.Delta.newChildrenInStacks) - typically only
 *    relevant for stackable categories like SEED.
 * 3. CURSOR_ITEM - the cursor now classifies as InventorySnapshot.CursorClassification.NEW_EXPECTED
 *    (a matching item that provably wasn't there at baseline) - never a pre-existing/unrelated
 *    cursor item.
 * 4. DISCOVERY - the same exp/discovery-record signal WaitLpProductDiscovered itself checks for,
 *    reused here via composition rather than duplicated (so both classes stay in sync if that
 *    logic ever changes) - LP can in principle register before the item visually settles.
 *
 * LP Assistant exists to confirm a resource *can* produce one specific undiscovered item, not to
 * harvest it to exhaustion - Board/Block especially repeat server-side and share one depleting
 * "log HP" pool with each other, so waiting for a full natural stop (the old WaitCollectState/
 * WaitFirstProgressCycle approach) risked using up a log's second, still-undiscovered product
 * before ever reaching it (confirmed live 2026-08). The caller is expected to interrupt extraction
 * immediately once this fires - this task only detects the first appearance, it does not itself
 * stop anything.
 *
 * Uses the same InventorySnapshot topology as LpAssistantBot's baseline/settlement/ownership/drop
 * stages (see that class's doc) rather than its own ad-hoc widget walk, so "what counts as new"
 * can never quietly disagree between detection and cleanup.
 */
public class WaitLpFirstProduct extends NTask {
    public enum Signal { NONE, NEW_ITEM, STACK_GROWTH, CURSOR_ITEM, DISCOVERY }

    private final NInventory inventory;
    private final NAlias alias;
    private final InventorySnapshot baseline;
    private final WaitLpProductDiscovered discoveryCheck;
    private final long deadline;

    private Signal signal = Signal.NONE;

    public WaitLpFirstProduct(NInventory inventory, NAlias alias, InventorySnapshot baseline,
                               String gobResName, String product, int expBaseline, long timeoutMs) {
        this.inventory = inventory;
        this.alias = alias;
        this.baseline = baseline;
        this.discoveryCheck = new WaitLpProductDiscovered(gobResName, product, expBaseline, timeoutMs);
        this.deadline = System.currentTimeMillis() + timeoutMs;
    }

    @Override
    public boolean check() {
        InventorySnapshot cur = InventorySnapshot.capture(inventory, alias);
        InventorySnapshot.Delta delta = cur.diff(baseline);
        if (!delta.newLooseItems.isEmpty() || !delta.newWholeStacks.isEmpty()) {
            signal = Signal.NEW_ITEM;
            return true;
        }
        if (!delta.newChildrenInStacks.isEmpty()) {
            signal = Signal.STACK_GROWTH;
            return true;
        }
        if (cur.classifyCursor(baseline) == InventorySnapshot.CursorClassification.NEW_EXPECTED) {
            signal = Signal.CURSOR_ITEM;
            return true;
        }

        // check() on a plain, un-queued NTask instance is just a method call - safe to invoke
        // directly every poll rather than duplicating its exp/record logic here.
        if (discoveryCheck.check() && discoveryCheck.confirmed()) {
            signal = Signal.DISCOVERY;
            return true;
        }

        return System.currentTimeMillis() >= deadline;
    }

    /** Which signal fired first, or NONE if this timed out without any. */
    public Signal signal() {
        return signal;
    }
}
