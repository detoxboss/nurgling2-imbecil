package nurgling.watch;

/** Source of the picture attached to an alert. May be unavailable; alerts never wait on it for long. */
public interface ScreenshotProvider {
    final class Result {
        /** JPEG bytes, or null when no picture could be taken. */
        public final byte[] jpeg;
        /** Why there is no picture; null when there is one. */
        public final String failure;

        private Result(byte[] jpeg, String failure) {
            this.jpeg = jpeg;
            this.failure = failure;
        }

        public static Result ok(byte[] jpeg) {
            return new Result(jpeg, null);
        }

        public static Result failed(String reason) {
            return new Result(null, reason);
        }

        public boolean isOk() {
            return jpeg != null;
        }
    }

    /**
     * Capture the lookout's own view. Blocks the calling bot thread for at most {@code timeoutMs}.
     * Must never return another session's picture: when this session is not the one being rendered
     * it reports failure instead.
     */
    Result capture(long timeoutMs) throws InterruptedException;

    /** Whether a capture could succeed right now, with the reason when not; null means yes. */
    String unavailableReason();

    /** Whether the gob is inside the captured view; null when unknown. */
    default Boolean inFrame(long gobId) {
        return null;
    }
}
