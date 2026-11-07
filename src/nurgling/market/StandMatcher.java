package nurgling.market;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Identifies detected barter stands by their saved physical reference (grid id + offset).
 *
 * Nothing here looks at customer-map coordinates, cluster numbers or counts. A detection is only
 * ever matched when exactly one known stand and exactly one detection are within tolerance of each
 * other; every other outcome is reported (unknown, ambiguous, possibly moved, missing) so the caller
 * can leave known listings intact and a human can decide.
 */
public final class StandMatcher {
    public static final double DEFAULT_TOLERANCE = 2.0;
    public static final double DEFAULT_MOVE_RADIUS = 40.0;

    private StandMatcher() {
    }

    public static final class Detected<T> {
        public final T value;
        public final PhysicalRef ref;
        /** Session-relative world position: only valid for geometry inside one client session. */
        public final double rcX;
        public final double rcY;

        public Detected(T value, PhysicalRef ref, double rcX, double rcY) {
            this.value = value;
            this.ref = ref;
            this.rcX = rcX;
            this.rcY = rcY;
        }
    }

    public static final class Matched<T> {
        public final MarketLayout.Stand stand;
        public final Detected<T> detected;

        Matched(MarketLayout.Stand stand, Detected<T> detected) {
            this.stand = stand;
            this.detected = detected;
        }
    }

    public enum Kind { AMBIGUOUS, MOVED }

    public static final class Doubt<T> {
        public final Kind kind;
        public final Detected<T> detected;
        public final List<MarketLayout.Stand> candidates;

        Doubt(Kind kind, Detected<T> detected, List<MarketLayout.Stand> candidates) {
            this.kind = kind;
            this.detected = detected;
            this.candidates = candidates;
        }
    }

    public static final class Result<T> {
        public final List<Matched<T>> matched = new ArrayList<>();
        public final List<Detected<T>> unknown = new ArrayList<>();
        public final List<Doubt<T>> doubts = new ArrayList<>();
        /** Bound stands with no detection near their saved position: unknown scan state, not "gone". */
        public final List<MarketLayout.Stand> missing = new ArrayList<>();
    }

    public static <T> Result<T> match(MarketLayout layout, List<Detected<T>> detected, double tolerance,
                                       double moveRadius) {
        List<MarketLayout.Stand> bound = layout.boundStands();
        Result<T> result = new Result<>();

        List<List<MarketLayout.Stand>> near = new ArrayList<>();
        int[] detectionsNear = new int[bound.size()];
        for (Detected<T> point : detected) {
            List<MarketLayout.Stand> candidates = new ArrayList<>();
            for (int index = 0; index < bound.size(); index++) {
                if (bound.get(index).physical.distanceTo(point.ref) <= tolerance) {
                    candidates.add(bound.get(index));
                    detectionsNear[index]++;
                }
            }
            near.add(candidates);
        }

        Set<Integer> accountedFor = new HashSet<>();
        for (int index = 0; index < detected.size(); index++) {
            Detected<T> point = detected.get(index);
            List<MarketLayout.Stand> candidates = near.get(index);
            if (candidates.size() == 1 && detectionsNear[bound.indexOf(candidates.get(0))] == 1) {
                result.matched.add(new Matched<>(candidates.get(0), point));
                accountedFor.add(candidates.get(0).standId);
            } else if (!candidates.isEmpty()) {
                result.doubts.add(new Doubt<>(Kind.AMBIGUOUS, point, candidates));
                for (MarketLayout.Stand candidate : candidates) {
                    accountedFor.add(candidate.standId);
                }
            }
        }

        // A detection near nothing known is "unknown", unless a stand that has gone undetected sits
        // close by - then it may be that stand, moved, which only a person may confirm.
        for (int index = 0; index < detected.size(); index++) {
            if (!near.get(index).isEmpty()) {
                continue;
            }
            Detected<T> point = detected.get(index);
            List<MarketLayout.Stand> vacant = new ArrayList<>();
            for (int standIndex = 0; standIndex < bound.size(); standIndex++) {
                MarketLayout.Stand stand = bound.get(standIndex);
                if (detectionsNear[standIndex] == 0 && stand.physical.distanceTo(point.ref) <= moveRadius) {
                    vacant.add(stand);
                }
            }
            if (vacant.isEmpty()) {
                result.unknown.add(point);
            } else {
                vacant.sort(Comparator.comparingDouble(stand -> stand.physical.distanceTo(point.ref)));
                result.doubts.add(new Doubt<>(Kind.MOVED, point, vacant));
            }
        }

        for (int index = 0; index < bound.size(); index++) {
            if (detectionsNear[index] == 0) {
                result.missing.add(bound.get(index));
            }
        }
        return result;
    }
}
