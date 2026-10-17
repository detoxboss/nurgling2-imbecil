package nurgling.tools;

/**
 * Decides when an item tooltip has to be rebuilt. Building one renders text and composes images,
 * so it must not happen every frame.
 * <p>
 * A cached tooltip is dropped when Shift changes (several tips show a different view with Shift
 * held) or when the item's info list is replaced. Otherwise it is rebuilt only when the item asks for
 * it, and at most once per {@link #REBUILD_INTERVAL}. A dropped tooltip is rebuilt immediately,
 * never held back by the interval.
 */
public final class TooltipRebuildGate {
    public static final double REBUILD_INTERVAL = 0.5;

    private boolean lastShift = false;
    private Object lastInfo = null;
    private boolean present = false;
    private double lastBuild = 0;

    /** Call every time the tooltip is requested, with the current Shift state and info list. */
    public void observe(boolean shift, Object info) {
        if(shift != lastShift) {
            present = false;
            lastShift = shift;
        }
        if(info != lastInfo) {
            present = false;
            lastInfo = info;
        }
    }

    /** True when a (re)build should happen now. {@code itemWantsRebuild} is the item's own staleness signal. */
    public boolean shouldRebuild(boolean itemWantsRebuild, double now) {
        if(!present)
            return true;
        return itemWantsRebuild && (now - lastBuild >= REBUILD_INTERVAL);
    }

    /** Record that a fresh tooltip was produced. */
    public void built(double now) {
        present = true;
        lastBuild = now;
    }

    /** Whether a built tooltip is currently valid to show. */
    public boolean present() {
        return present;
    }
}
