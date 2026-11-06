package haven.res.ui.surv;

/**
 * Thrown by {@link Data#encode()} when the peak-to-trough span of target heights no longer fits
 * the wire format's unsigned-byte relative encoding (0-255 units above the lowest point). Fork
 * addition: the original encoder silently wrapped past 255, corrupting the submitted heights.
 */
public class HeightRangeExceededException extends Exception {
    public final int lo, hi;

    public HeightRangeExceededException(int lo, int hi) {
        super(String.format("Survey target height span (%d) exceeds the 255-unit encodable range [lo=%d, hi=%d]",
                             hi - lo, lo, hi));
        this.lo = lo;
        this.hi = hi;
    }
}
