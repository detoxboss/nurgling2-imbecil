package nurgling.market;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects stands seen at different waypoints, de-duplicating by physical position so the same stand
 * seen from two waypoints counts once. All 100+ stands never need to be loaded at the same time.
 */
public final class StandAccumulator<T> {
    private static final double SAME_STAND_UNITS = 1.0;

    private final List<StandMatcher.Detected<T>> stands = new ArrayList<>();

    /** @return true when this stand had not been seen before. */
    public boolean add(StandMatcher.Detected<T> detected) {
        for (int index = 0; index < stands.size(); index++) {
            if (stands.get(index).ref.distanceTo(detected.ref) <= SAME_STAND_UNITS) {
                // Keep the freshest handle (an older Gob object may have been unloaded since).
                stands.set(index, detected);
                return false;
            }
        }
        stands.add(detected);
        return true;
    }

    public List<StandMatcher.Detected<T>> all() {
        return new ArrayList<>(stands);
    }

    public int size() {
        return stands.size();
    }
}
