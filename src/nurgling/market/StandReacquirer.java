package nurgling.market;

import java.util.ArrayList;
import java.util.List;

/**
 * Re-finds a barter stand's live object just before it is captured.
 *
 * Gob handles collected during the area sweep may have been unloaded by later sweep movement, so the
 * saved physical reference (grid id + offset) is the only thing trusted: navigate toward it, wait for
 * the section and its objects to load, then require exactly one stand within tolerance.
 */
public final class StandReacquirer {
    private StandReacquirer() {
    }

    public interface World<T> {
        /** Navigates toward the saved position (ChunkNav when far or unloaded); true when it arrived near it. */
        boolean approach(PhysicalRef saved) throws InterruptedException;

        /** Stands that are loaded right now. */
        List<StandMatcher.Detected<T>> loaded() throws InterruptedException;
    }

    public static final class Result<T> {
        public final StandMatcher.Detected<T> stand;
        public final String failure;

        private Result(StandMatcher.Detected<T> stand, String failure) {
            this.stand = stand;
            this.failure = failure;
        }

        public boolean found() {
            return stand != null;
        }
    }

    public static <T> Result<T> reacquire(PhysicalRef saved, double tolerance, World<T> world, long loadTimeoutMs,
                                          long pollMs, ShopboxReadiness.Clock clock) throws InterruptedException {
        if (!world.approach(saved)) {
            return new Result<>(null, "could not navigate near the saved stand position " + saved);
        }
        long deadline = clock.now() + loadTimeoutMs;
        while (true) {
            List<StandMatcher.Detected<T>> within = new ArrayList<>();
            for (StandMatcher.Detected<T> candidate : world.loaded()) {
                if (candidate.ref.distanceTo(saved) <= tolerance) {
                    within.add(candidate);
                }
            }
            if (within.size() == 1) {
                return new Result<>(within.get(0), null);
            }
            if (within.size() > 1) {
                return new Result<>(null, within.size() + " stands are within tolerance of " + saved + " (ambiguous)");
            }
            if (clock.now() >= deadline) {
                return new Result<>(null, "no stand loaded at " + saved + " after navigating there");
            }
            clock.sleep(pollMs);
        }
    }
}
