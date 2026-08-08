package nurgling.tasks;

import nurgling.NInventory;
import nurgling.tools.InventorySnapshot;
import nurgling.tools.NAlias;

/**
 * Bounded, state-driven replacement for a fixed post-interrupt sleep: polls the same
 * InventorySnapshot topology LpAssistantBot uses everywhere else, resets a short quiet-period
 * deadline every time the matching product/cursor state actually changes, and finishes once state
 * has held steady for one full quiet period - or once the hard wall-clock deadline is reached,
 * whichever comes first.
 *
 * Purpose: absorb server deltas that were already in flight the instant extraction was interrupted
 * (a unit committed server-side just before the interrupt landed can still arrive a little late)
 * without waiting long enough to risk folding in a second, genuinely new production cycle - the
 * quiet period is intentionally much shorter than the time a fresh harvest click takes to produce
 * its own first unit (see LpAssistantBot.FIRST_PRODUCT_TIMEOUT_MS).
 *
 * check() only ever calls InventorySnapshot.capture() (instant, non-blocking) - it never queues a
 * nested NCore task from inside its own predicate.
 */
public class WaitLpSettlement extends NTask {
    public enum EndReason { QUIET, DEADLINE }

    private final NInventory inventory;
    private final NAlias alias;
    private final long quietMs;
    private final long deadline;

    private InventorySnapshot last;
    private long quietDeadline;
    private EndReason endReason = EndReason.DEADLINE;

    public WaitLpSettlement(NInventory inventory, NAlias alias, long quietMs, long maxWaitMs) {
        this.inventory = inventory;
        this.alias = alias;
        this.quietMs = quietMs;
        long now = System.currentTimeMillis();
        this.deadline = now + maxWaitMs;
        this.last = InventorySnapshot.capture(inventory, alias);
        this.quietDeadline = now + quietMs;
    }

    @Override
    public boolean check() {
        InventorySnapshot cur = InventorySnapshot.capture(inventory, alias);
        long now = System.currentTimeMillis();
        if (!cur.sameState(last)) {
            last = cur;
            quietDeadline = now + quietMs;
        }
        if (now >= deadline) {
            endReason = EndReason.DEADLINE;
            return true;
        }
        if (now >= quietDeadline) {
            endReason = EndReason.QUIET;
            return true;
        }
        return false;
    }

    /** The last-observed (settled, or deadline-cut-off) snapshot. */
    public InventorySnapshot result() {
        return last;
    }

    /** Whether this ended because state went quiet, or because the hard deadline was hit. */
    public EndReason endReason() {
        return endReason;
    }
}
