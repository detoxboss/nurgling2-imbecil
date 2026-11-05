package nurgling.tasks;

import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.discovery.DiscoveryLedger;
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
 *    attributable to it. Always a genuine new discovery (see {@link #isNewLpDiscovery()}).
 * 2. The character's DiscoveryLedger (NGItem.added()/named()/destroy() -> DiscoveryTracker.arrived/
 *    named/departed -> DiscoveryCorrelator -> DiscoveryLedger.record(), applied asynchronously from
 *    DiscoveryTracker.tick()) - the same record that makes the LP-assistant green marker disappear.
 *    Kept as a second path since it does confirm the specific product, and because comparing which
 *    of the two signals actually fires (see confirmedVia()) is itself useful diagnostic information
 *    for anything not yet root-caused (see this class's git history). Checked via ledger().get(product)
 *    rather than the simpler isDone(product) - the per-resource lpExplorer map this used to read
 *    (IsLpExplorerContains(gobResName, product)) was replaced by upstream's Discovery Atlas rewrite
 *    (2026-11-05 sync) with a ledger keyed by item name only; LpExplorer.java itself now reads the
 *    same way (see its allUndiscoveredProducts()/line ~55), so gobResName no longer narrows anything.
 *    Bot-driven right-clicks are still correctly attributed: DiscoveryTracker.sourceGob() reads
 *    NCore.lastGobRightClick(), fed from NMapView.wdgmsg("click") for every sender (manual clicks,
 *    the Q action, and bots' raw wdgmsg clicks alike - see NCore.noteMapClick()'s own doc), unlike
 *    the old haven.MapView.clickedGob this used to depend on, which only a real local mouse click set.
 *
 *    <b>KNOWN/LEGACY/MANUAL vs. DISCOVERED (2026-11-05, post-sync correction).</b>
 *    {@code DiscoveryLedger.isDone(name)} is true for every "done" state - SEEN is the only non-done
 *    state besides HELD, and KNOWN/LEGACY/MANUAL/DISCOVERED (rank 2 and 3) are all "done" even though
 *    only DISCOVERED (rank 3) means an LP rise was actually paired with this harvest. A product that
 *    was already discovered server-side but not yet recorded locally (or imported from the legacy
 *    lpExplorer map, or marked by hand in the Atlas) resolves via a KNOWN/LEGACY/MANUAL verdict with
 *    zero LP gained - treating that the same as a fresh DISCOVERED would let LP Assistant credit
 *    itself for a discovery that didn't happen this harvest. {@link #isNewLpDiscovery()} is the
 *    narrower check callers that care about *crediting* a new discovery must use; {@link #confirmed()}
 *    keeps its original, broader meaning ("resolved - nothing more to gain here, stop retrying"),
 *    which is correct for either outcome.
 *
 * Not LpExplorer.allUndiscoveredProducts(gob) - that gates seed/leaf products on the gob's
 * *current* live fruit/leaf visibility as well as discovery state, so picking the one
 * currently-visible berry can empty the list even when the pickup was never actually recorded.
 *
 * <b>Signal-2 latency vs. this task's timeout (reviewed, not changed, 2026-11-05).</b>
 * DiscoveryCorrelator.NAME_TIMEOUT_MS holds an entire LP rise unprocessed for up to 30s if any
 * item arrival in its pairing window has an unresolved name - longer than both this task's default
 * 8s timeout and LpAssistantBot's 3s follow-up (DISCOVERY_CONFIRM_TIMEOUT_MS). If the exp signal
 * (1) is unavailable or doesn't fire - expBaseline was unreadable (currentExp() returned -1), or
 * the LP delta genuinely routed through signal 2 only - a slow-to-resolve item name could make this
 * task time out and the caller record a false skip for a product that *was* discovered, just not
 * credited within the window. This is a real, evidence-based latency characteristic of upstream's
 * correlator, not a hypothetical; it is not fixed here because (a) the exp signal is the primary
 * path and covers the overwhelming majority of cases - name resolution for the newly-produced item
 * itself is typically fast relative to these windows, this risk is specifically about *other*
 * arrivals sharing the same rise's pairing window - and (b) widening these timeouts is a
 * timing-policy change with its own tradeoffs (slower failure detection for genuine non-discoveries)
 * that deserves its own review, not a side effect of this fix. Flagged for whoever next
 * investigates a reproducible "discovery not confirmed" false skip.
 *
 * Timed by wall clock, not a tick counter: signal 2 is applied asynchronously (DiscoveryTracker's
 * own tick, independent of this task's caller), so a fixed poll-count bound would couple this
 * task's patience to an unrelated tick rate. The exp signal (1) is a direct per-check read with no
 * such dependency either way.
 */
public class WaitLpProductDiscovered extends NTask {
    private static final long DEFAULT_TIMEOUT_MS = 8000;

    /** Which signal resolved the wait, and whether it represents a genuinely new LP discovery. */
    public enum Outcome {
        /** Timed out; nothing resolved. */
        NONE,
        /** The server-pushed LP total rose past the baseline. Always a new discovery. */
        EXP,
        /** DiscoveryLedger recorded a fresh DISCOVERED verdict (an LP rise paired with this item). */
        DISCOVERED,
        /** DiscoveryLedger resolved the product as already established (KNOWN/LEGACY/MANUAL) with no
         *  new LP gained this harvest - resolved, but not a fresh discovery. */
        KNOWN
    }

    private final String gobResName;
    private final String product;
    private final int expBaseline;
    private final long deadline;
    private boolean confirmed = false;
    private Outcome outcome = Outcome.NONE;

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
            outcome = Outcome.EXP;
            return true;
        }
        NCharacterInfo info = gui != null ? gui.getCharInfo() : null;
        DiscoveryLedger.Entry entry = info != null ? info.ledger().get(product) : null;
        if (entry != null && entry.state.done) {
            confirmed = true;
            outcome = isNewDiscoveryState(entry.state) ? Outcome.DISCOVERED : Outcome.KNOWN;
            return true;
        }
        return System.currentTimeMillis() >= deadline;
    }

    /** True for DiscoveryLedger.State.DISCOVERED specifically - a fresh LP rise paired with this item. */
    static boolean isNewDiscoveryState(DiscoveryLedger.State state) {
        return state == DiscoveryLedger.State.DISCOVERED;
    }

    /** True once the product resolved - either a new discovery or already known; false if it timed out. */
    public boolean confirmed() {
        return confirmed;
    }

    /** True only for a genuinely new LP discovery (EXP or a fresh DISCOVERED ledger verdict) - the
     *  check callers must use before crediting themselves with discovering this product. False for
     *  KNOWN/LEGACY/MANUAL (already established, no new LP this harvest) and for a timeout. */
    public boolean isNewLpDiscovery() {
        return outcome == Outcome.EXP || outcome == Outcome.DISCOVERED;
    }

    /** Which outcome resolved the wait. NONE until resolved or timed out. */
    public Outcome outcome() {
        return outcome;
    }

    /** Which signal confirmed it ("exp", "discovered", "known"), or null if not confirmed. Debug/diagnostic only. */
    public String confirmedVia() {
        switch (outcome) {
            case EXP: return "exp";
            case DISCOVERED: return "discovered";
            case KNOWN: return "known";
            default: return null;
        }
    }

    /** Current total LP, or -1 if not currently readable. Callers snapshot this before the harvest. */
    public static int currentExp() {
        NGameUI gui = NUtils.getGameUI();
        return (gui != null && gui.chrwdg != null) ? gui.chrwdg.exp : -1;
    }
}
