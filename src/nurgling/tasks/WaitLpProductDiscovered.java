package nurgling.tasks;

import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.widgets.NCharacterInfo;

/**
 * Waits until either of two independent signals says LP was gained for this product:
 *
 * 1. The character's total Learning Points (haven.CharWnd.exp, pushed directly by the server the
 *    moment it changes - CharWnd.java:453-454, the same field SAttrWnd/SkillWnd already display
 *    live) rising past a baseline snapshotted right before the harvest action. This is the more
 *    direct signal: server-pushed, no dependency on any client-side item-name resolution. It can't
 *    say *which* product/gob the gain came from, or prove it was specifically a first-time-
 *    discovery bonus rather than some other LP source - acceptable here because the caller only
 *    has one targeted harvest in flight at a time, so a rise during that narrow window is
 *    attributable to it.
 * 2. The character's DiscoveryLedger (NGItem.added()/named()/destroy() -> DiscoveryTracker.arrived/
 *    named/departed -> DiscoveryCorrelator -> DiscoveryLedger.record(), applied asynchronously from
 *    DiscoveryTracker.tick()) - the same record that makes the LP-assistant green marker disappear.
 *    Kept as a second path since it does confirm the specific product, and because comparing which
 *    of the two signals actually fires (see confirmedVia()) is itself useful diagnostic information
 *    for anything not yet root-caused (see this class's git history). Checked via
 *    ledger().isDone(product) only - the per-resource lpExplorer map this used to read
 *    (IsLpExplorerContains(gobResName, product)) was replaced by upstream's Discovery Atlas rewrite
 *    (2026-11-05 sync) with a ledger keyed by item name only; LpExplorer.java itself now reads the
 *    same way (see its allUndiscoveredProducts()/line ~55), so gobResName no longer narrows anything.
 *    Bot-driven right-clicks are still correctly attributed: DiscoveryTracker.sourceGob() reads
 *    NCore.lastGobRightClick(), fed from NMapView.wdgmsg("click") for every sender (manual clicks,
 *    the Q action, and bots' raw wdgmsg clicks alike - see NCore.noteMapClick()'s own doc), unlike
 *    the old haven.MapView.clickedGob this used to depend on, which only a real local mouse click set.
 *
 * Not LpExplorer.allUndiscoveredProducts(gob) - that gates seed/leaf products on the gob's
 * *current* live fruit/leaf visibility as well as discovery state, so picking the one
 * currently-visible berry can empty the list even when the pickup was never actually recorded.
 *
 * Timed by wall clock, not a tick counter: signal 2 is applied asynchronously (DiscoveryTracker's
 * own tick, independent of this task's caller), so a fixed poll-count bound would couple this
 * task's patience to an unrelated tick rate. The exp signal (1) is a direct per-check read with no
 * such dependency either way.
 */
public class WaitLpProductDiscovered extends NTask {
    private static final long DEFAULT_TIMEOUT_MS = 8000;

    private final String gobResName;
    private final String product;
    private final int expBaseline;
    private final long deadline;
    private boolean confirmed = false;
    private String confirmedVia = null;

    public WaitLpProductDiscovered(String gobResName, String product, int expBaseline) {
        this(gobResName, product, expBaseline, DEFAULT_TIMEOUT_MS);
    }

    public WaitLpProductDiscovered(String gobResName, String product, int expBaseline, long timeoutMs) {
        this.gobResName = gobResName;
        this.product = product;
        this.expBaseline = expBaseline;
        this.deadline = System.currentTimeMillis() + timeoutMs;
    }

    @Override
    public boolean check() {
        NGameUI gui = NUtils.getGameUI();
        if (gui != null && gui.chrwdg != null && expBaseline >= 0 && gui.chrwdg.exp > expBaseline) {
            confirmed = true;
            confirmedVia = "exp";
            return true;
        }
        NCharacterInfo info = gui != null ? gui.getCharInfo() : null;
        if (info != null && info.ledger().isDone(product)) {
            confirmed = true;
            confirmedVia = "record";
            return true;
        }
        return System.currentTimeMillis() >= deadline;
    }

    /** True once the product was confirmed discovered; false if the wait timed out. */
    public boolean confirmed() {
        return confirmed;
    }

    /** Which signal confirmed it ("exp" or "record"), or null if not confirmed. Debug/diagnostic only. */
    public String confirmedVia() {
        return confirmedVia;
    }

    /** Current total LP, or -1 if not currently readable. Callers snapshot this before the harvest. */
    public static int currentExp() {
        NGameUI gui = NUtils.getGameUI();
        return (gui != null && gui.chrwdg != null) ? gui.chrwdg.exp : -1;
    }
}
