package nurgling.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cluster-level zig-zag route.
 *
 * The order depends only on where the clusters physically are (session-relative positions of the
 * matched stands) and where the walk starts. It ignores cluster numbers and every customer-map
 * coordinate, so renumbering or redrawing the map cannot change the walk, and unbuilt positions
 * simply never appear in the input.
 */
public final class MarketRoutePlanner {
    /** Clusters whose centres differ in y by less than this share a row of the zig-zag (world units). */
    public static final double DEFAULT_ROW_GAP = 8.0;

    private MarketRoutePlanner() {
    }

    public static final class Stop<T> {
        public final int clusterId;
        public final double x;
        public final double y;
        public final T value;

        public Stop(int clusterId, double x, double y, T value) {
            this.clusterId = clusterId;
            this.x = x;
            this.y = y;
            this.value = value;
        }
    }

    private static final class Cluster<T> {
        final List<Stop<T>> stops = new ArrayList<>();
        double cx;
        double cy;
    }

    public static <T> List<T> order(List<Stop<T>> stops, double startX, double startY, double rowGap) {
        Map<Integer, Cluster<T>> byCluster = new LinkedHashMap<>();
        for (Stop<T> stop : stops) {
            byCluster.computeIfAbsent(stop.clusterId, ignored -> new Cluster<>()).stops.add(stop);
        }
        List<Cluster<T>> clusters = new ArrayList<>(byCluster.values());
        for (Cluster<T> cluster : clusters) {
            for (Stop<T> stop : cluster.stops) {
                cluster.cx += stop.x / cluster.stops.size();
                cluster.cy += stop.y / cluster.stops.size();
            }
        }
        // Positions alone (ties broken by value-independent coordinates) give a stable order.
        clusters.sort(Comparator.<Cluster<T>>comparingDouble(c -> c.cy).thenComparingDouble(c -> c.cx));

        List<List<Cluster<T>>> rows = new ArrayList<>();
        for (Cluster<T> cluster : clusters) {
            List<Cluster<T>> row = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (row != null && Math.abs(cluster.cy - row.get(0).cy) <= rowGap) {
                row.add(cluster);
            } else {
                row = new ArrayList<>();
                row.add(cluster);
                rows.add(row);
            }
        }

        List<Cluster<T>> best = null;
        double bestLength = Double.POSITIVE_INFINITY;
        for (int reverseRows = 0; reverseRows < 2; reverseRows++) {
            for (int firstRightToLeft = 0; firstRightToLeft < 2; firstRightToLeft++) {
                List<List<Cluster<T>>> sequence = new ArrayList<>(rows);
                if (reverseRows == 1) {
                    Collections.reverse(sequence);
                }
                List<Cluster<T>> path = new ArrayList<>();
                for (int index = 0; index < sequence.size(); index++) {
                    List<Cluster<T>> row = new ArrayList<>(sequence.get(index));
                    row.sort(Comparator.comparingDouble(c -> c.cx));
                    if ((index + firstRightToLeft) % 2 == 1) {
                        Collections.reverse(row);
                    }
                    path.addAll(row);
                }
                improve(path, startX, startY);
                double length = length(path, startX, startY);
                if (length < bestLength - 1e-9) {
                    bestLength = length;
                    best = path;
                }
            }
        }

        List<T> ordered = new ArrayList<>();
        double x = startX;
        double y = startY;
        if (best == null) {
            return ordered;
        }
        for (Cluster<T> cluster : best) {
            List<Stop<T>> remaining = new ArrayList<>(cluster.stops);
            while (!remaining.isEmpty()) {
                final double fromX = x;
                final double fromY = y;
                Stop<T> next = Collections.min(remaining,
                        Comparator.<Stop<T>>comparingDouble(stop -> Math.hypot(stop.x - fromX, stop.y - fromY))
                                .thenComparingDouble(stop -> stop.y).thenComparingDouble(stop -> stop.x));
                remaining.remove(next);
                ordered.add(next.value);
                x = next.x;
                y = next.y;
            }
        }
        return ordered;
    }

    /** Total walking distance of an order, for tests and diagnostics. */
    public static <T> double walkLength(List<Stop<T>> route, double startX, double startY) {
        double length = 0;
        double x = startX;
        double y = startY;
        for (Stop<T> stop : route) {
            length += Math.hypot(stop.x - x, stop.y - y);
            x = stop.x;
            y = stop.y;
        }
        return length;
    }

    private static <T> double length(List<Cluster<T>> path, double startX, double startY) {
        double length = 0;
        double x = startX;
        double y = startY;
        for (Cluster<T> cluster : path) {
            length += Math.hypot(cluster.cx - x, cluster.cy - y);
            x = cluster.cx;
            y = cluster.cy;
        }
        return length;
    }

    /** Bounded 2-opt over an open path whose start point is fixed. */
    private static <T> void improve(List<Cluster<T>> path, double startX, double startY) {
        boolean improved = true;
        int passes = 0;
        while (improved && passes++ < 50) {
            improved = false;
            for (int i = 0; i < path.size() - 1; i++) {
                for (int j = i + 1; j < path.size(); j++) {
                    double before = length(path, startX, startY);
                    Collections.reverse(path.subList(i, j + 1));
                    if (length(path, startX, startY) < before - 1e-9) {
                        improved = true;
                    } else {
                        Collections.reverse(path.subList(i, j + 1));
                    }
                }
            }
        }
    }
}
