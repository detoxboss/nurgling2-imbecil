package nurgling.market;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Deterministic row-major numbering for market stands. */
public final class MarketStandOrdering {
    private MarketStandOrdering() {
    }

    public enum FirstCorner {
        NORTH_WEST("north-west"),
        NORTH_EAST("north-east"),
        SOUTH_WEST("south-west"),
        SOUTH_EAST("south-east");

        public final String setting;

        FirstCorner(String setting) {
            this.setting = setting;
        }

        public static FirstCorner parse(Object value) {
            String text = value == null ? "" : value.toString().trim().toLowerCase(Locale.ROOT);
            for (FirstCorner corner : values()) {
                if (corner.setting.equals(text) || corner.name().toLowerCase(Locale.ROOT).equals(text.replace('-', '_'))) {
                    return corner;
                }
            }
            return NORTH_WEST;
        }
    }

    public static final class Stand<T> {
        public final T value;
        public final double x;
        public final double y;

        public Stand(T value, double x, double y) {
            this.value = value;
            this.x = x;
            this.y = y;
        }
    }

    private static final class Row<T> {
        final List<Stand<T>> stands = new ArrayList<>();
        double meanY;

        void add(Stand<T> stand) {
            meanY = ((meanY * stands.size()) + stand.y) / (stands.size() + 1);
            stands.add(stand);
        }
    }

    /**
     * Groups nearly-aligned stands into rows before sorting them. This prevents small placement
     * jitter from interleaving two physical rows when the market is numbered left-to-right.
     */
    public static <T> List<Stand<T>> order(List<Stand<T>> input, FirstCorner firstCorner, double rowTolerance) {
        if (rowTolerance < 0) {
            throw new IllegalArgumentException("rowTolerance must not be negative");
        }
        List<Stand<T>> byY = new ArrayList<>(input);
        byY.sort(Comparator.comparingDouble((Stand<T> stand) -> stand.y)
                .thenComparingDouble(stand -> stand.x));

        List<Row<T>> rows = new ArrayList<>();
        for (Stand<T> stand : byY) {
            Row<T> row = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (row == null || Math.abs(stand.y - row.meanY) > rowTolerance) {
                row = new Row<>();
                rows.add(row);
            }
            row.add(stand);
        }

        boolean southFirst = firstCorner == FirstCorner.SOUTH_WEST || firstCorner == FirstCorner.SOUTH_EAST;
        boolean eastFirst = firstCorner == FirstCorner.NORTH_EAST || firstCorner == FirstCorner.SOUTH_EAST;
        if (southFirst) {
            java.util.Collections.reverse(rows);
        }

        List<Stand<T>> result = new ArrayList<>(input.size());
        for (Row<T> row : rows) {
            row.stands.sort(Comparator.comparingDouble((Stand<T> stand) -> stand.x));
            if (eastFirst) {
                java.util.Collections.reverse(row.stands);
            }
            result.addAll(row.stands);
        }
        return result;
    }
}
