package nurgling.market;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Matches loaded game objects to stable Cxx-Sx identities from the server layout. */
public final class MarketLayoutMatcher {
    private static final double MAX_AUTO_RMS = 0.055;
    private static final double MIN_CONFIDENCE_GAP = 0.003;

    private MarketLayoutMatcher() {
    }

    public static final class Point<T> {
        public final T value;
        public final double x;
        public final double y;

        public Point(T value, double x, double y) {
            this.value = value;
            this.x = x;
            this.y = y;
        }
    }

    public static final class Match<T> {
        public final MarketLayout.Stand layout;
        public final T value;
        public final double worldX;
        public final double worldY;

        Match(MarketLayout.Stand layout, Point<T> point) {
            this.layout = layout;
            this.value = point.value;
            this.worldX = point.x;
            this.worldY = point.y;
        }
    }

    public static <T> List<Match<T>> match(MarketLayout layout, List<Point<T>> detected,
                                            double calibratedTolerance) throws IOException {
        if (layout.stands.size() != detected.size()) {
            throw new IOException("Layout expects " + layout.stands.size() + " stands but found " + detected.size());
        }
        return layout.isCalibrated()
                ? matchCalibrated(layout, detected, calibratedTolerance)
                : matchByShape(layout, detected);
    }

    public static <T> JSONObject calibration(MarketLayout layout, List<Match<T>> matches) {
        JSONArray stands = new JSONArray();
        matches.stream().sorted(Comparator.comparingInt(match -> match.layout.houseId)).forEach(match -> {
            JSONObject stand = new JSONObject();
            stand.put("houseId", match.layout.houseId);
            stand.put("standKey", match.layout.standKey);
            stand.put("worldX", match.worldX);
            stand.put("worldY", match.worldY);
            stands.put(stand);
        });
        return new JSONObject().put("layoutVersion", layout.version).put("stands", stands);
    }

    private static <T> List<Match<T>> matchCalibrated(MarketLayout layout, List<Point<T>> detected,
                                                       double tolerance) throws IOException {
        List<Match<T>> matches = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (MarketLayout.Stand stand : layout.stands) {
            int bestIndex = -1;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (int index = 0; index < detected.size(); index++) {
                if (used.contains(index)) continue;
                Point<T> point = detected.get(index);
                double distance = Math.hypot(point.x - stand.worldX, point.y - stand.worldY);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = index;
                }
            }
            if (bestIndex < 0 || bestDistance > tolerance) {
                throw new IOException(stand.standKey + " is missing or moved "
                        + String.format(java.util.Locale.ROOT, "%.2f", bestDistance)
                        + " units (allowed " + tolerance + ")");
            }
            used.add(bestIndex);
            matches.add(new Match<>(stand, detected.get(bestIndex)));
        }
        return matches;
    }

    private static <T> List<Match<T>> matchByShape(MarketLayout layout, List<Point<T>> detected) throws IOException {
        double[][] map = normalizeLayout(layout.stands);
        double[][] world = normalizeDetected(detected);
        Candidate best = null;
        Candidate second = null;
        for (int transform = 0; transform < 8; transform++) {
            double[][] transformed = transform(map, transform);
            double[][] costs = costs(transformed, world);
            int[] assignment = hungarian(costs);
            double rms = rms(costs, assignment);
            Candidate candidate = new Candidate(assignment, rms, transform);
            if (best == null || rms < best.rms) {
                second = best;
                best = candidate;
            } else if (second == null || rms < second.rms) {
                second = candidate;
            }
        }
        if (best == null || best.rms > MAX_AUTO_RMS) {
            throw new IOException("Automatic stand calibration error is too large: "
                    + (best == null ? "unknown" : format(best.rms)));
        }
        if (second != null && second.rms - best.rms < MIN_CONFIDENCE_GAP) {
            throw new IOException("Automatic stand calibration is ambiguous (scores "
                    + format(best.rms) + " and " + format(second.rms) + ")");
        }
        List<Match<T>> matches = new ArrayList<>();
        for (int index = 0; index < layout.stands.size(); index++) {
            matches.add(new Match<>(layout.stands.get(index), detected.get(best.assignment[index])));
        }
        matches.sort(Comparator.comparingInt(match -> match.layout.houseId));
        return matches;
    }

    private static double[][] normalizeLayout(List<MarketLayout.Stand> stands) {
        double[][] points = new double[stands.size()][2];
        for (int index = 0; index < stands.size(); index++) {
            points[index][0] = stands.get(index).mapX;
            points[index][1] = stands.get(index).mapY;
        }
        normalize(points);
        return points;
    }

    private static <T> double[][] normalizeDetected(List<Point<T>> detected) {
        double[][] points = new double[detected.size()][2];
        for (int index = 0; index < detected.size(); index++) {
            points[index][0] = detected.get(index).x;
            points[index][1] = detected.get(index).y;
        }
        normalize(points);
        return points;
    }

    private static void normalize(double[][] points) {
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (double[] point : points) {
            minX = Math.min(minX, point[0]); maxX = Math.max(maxX, point[0]);
            minY = Math.min(minY, point[1]); maxY = Math.max(maxY, point[1]);
        }
        double width = Math.max(0.000001, maxX - minX);
        double height = Math.max(0.000001, maxY - minY);
        for (double[] point : points) {
            point[0] = (point[0] - minX) / width;
            point[1] = (point[1] - minY) / height;
        }
    }

    private static double[][] transform(double[][] source, int transform) {
        double[][] result = new double[source.length][2];
        for (int index = 0; index < source.length; index++) {
            double x = source[index][0], y = source[index][1];
            switch (transform) {
                case 0: result[index] = new double[]{x, y}; break;
                case 1: result[index] = new double[]{1 - x, y}; break;
                case 2: result[index] = new double[]{x, 1 - y}; break;
                case 3: result[index] = new double[]{1 - x, 1 - y}; break;
                case 4: result[index] = new double[]{y, x}; break;
                case 5: result[index] = new double[]{1 - y, x}; break;
                case 6: result[index] = new double[]{y, 1 - x}; break;
                default: result[index] = new double[]{1 - y, 1 - x}; break;
            }
        }
        return result;
    }

    private static double[][] costs(double[][] left, double[][] right) {
        double[][] costs = new double[left.length][right.length];
        for (int row = 0; row < left.length; row++) {
            for (int col = 0; col < right.length; col++) {
                double dx = left[row][0] - right[col][0];
                double dy = left[row][1] - right[col][1];
                costs[row][col] = dx * dx + dy * dy;
            }
        }
        return costs;
    }

    /** Minimum-cost square assignment, returning the selected column for each row. */
    static int[] hungarian(double[][] costs) {
        int size = costs.length;
        double[] u = new double[size + 1];
        double[] v = new double[size + 1];
        int[] p = new int[size + 1];
        int[] way = new int[size + 1];
        for (int row = 1; row <= size; row++) {
            p[0] = row;
            int column0 = 0;
            double[] min = new double[size + 1];
            java.util.Arrays.fill(min, Double.POSITIVE_INFINITY);
            boolean[] used = new boolean[size + 1];
            do {
                used[column0] = true;
                int row0 = p[column0];
                double delta = Double.POSITIVE_INFINITY;
                int column1 = 0;
                for (int column = 1; column <= size; column++) {
                    if (used[column]) continue;
                    double current = costs[row0 - 1][column - 1] - u[row0] - v[column];
                    if (current < min[column]) {
                        min[column] = current;
                        way[column] = column0;
                    }
                    if (min[column] < delta) {
                        delta = min[column];
                        column1 = column;
                    }
                }
                for (int column = 0; column <= size; column++) {
                    if (used[column]) { u[p[column]] += delta; v[column] -= delta; }
                    else min[column] -= delta;
                }
                column0 = column1;
            } while (p[column0] != 0);
            do {
                int column1 = way[column0];
                p[column0] = p[column1];
                column0 = column1;
            } while (column0 != 0);
        }
        int[] assignment = new int[size];
        for (int column = 1; column <= size; column++) assignment[p[column] - 1] = column - 1;
        return assignment;
    }

    private static double rms(double[][] costs, int[] assignment) {
        double total = 0;
        for (int row = 0; row < assignment.length; row++) total += costs[row][assignment[row]];
        return Math.sqrt(total / assignment.length);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    private static final class Candidate {
        final int[] assignment;
        final double rms;
        final int transform;

        Candidate(int[] assignment, double rms, int transform) {
            this.assignment = assignment;
            this.rms = rms;
            this.transform = transform;
        }
    }
}
