package nurgling.market;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks the planned waypoints, collecting stands as sections load. Stands are de-duplicated by
 * physical position, and an unreachable waypoint is recorded rather than aborting the sweep, so a
 * partly blocked market still yields everything that could be seen.
 */
public final class AreaSweep {
    private AreaSweep() {
    }

    public interface Source<T> {
        /** Moves toward the waypoint; false when it cannot be reached (after the source's own bounded retries). */
        boolean visit(AreaSweepPlanner.Waypoint waypoint) throws InterruptedException;

        /** Stands inside the area that are loaded right now. */
        List<StandMatcher.Detected<T>> detect() throws InterruptedException;
    }

    public static final class Outcome<T> {
        public final List<StandMatcher.Detected<T>> stands;
        public final int waypoints;
        public final int reached;
        public final List<AreaSweepPlanner.Waypoint> unreached;

        Outcome(List<StandMatcher.Detected<T>> stands, int waypoints, int reached,
                List<AreaSweepPlanner.Waypoint> unreached) {
            this.stands = stands;
            this.waypoints = waypoints;
            this.reached = reached;
            this.unreached = unreached;
        }

        public boolean complete() {
            return unreached.isEmpty();
        }
    }

    public static <T> Outcome<T> run(List<AreaSweepPlanner.Waypoint> waypoints, Source<T> source)
            throws InterruptedException {
        StandAccumulator<T> accumulator = new StandAccumulator<>();
        List<AreaSweepPlanner.Waypoint> unreached = new ArrayList<>();
        int reached = 0;
        // Whatever is loaded before moving counts too.
        for (StandMatcher.Detected<T> stand : source.detect()) {
            accumulator.add(stand);
        }
        for (AreaSweepPlanner.Waypoint waypoint : waypoints) {
            if (!source.visit(waypoint)) {
                unreached.add(waypoint);
                continue;
            }
            reached++;
            for (StandMatcher.Detected<T> stand : source.detect()) {
                accumulator.add(stand);
            }
        }
        return new Outcome<>(accumulator.all(), waypoints.size(), reached, unreached);
    }
}
