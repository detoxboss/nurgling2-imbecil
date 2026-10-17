package nurgling.market;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans waypoints so a large NArea can be searched in sections.
 *
 * Gobs only exist client-side while they are loaded, so a big market floor is not guaranteed to be
 * visible from one spot. Each NArea piece (a rectangle in one map grid, in grid-local tiles) is
 * covered by an evenly spaced lattice; no tile of the piece is farther than half the spacing from
 * a waypoint along either axis.
 */
public final class AreaSweepPlanner {
    public static final int DEFAULT_SPACING_TILES = 40;

    private AreaSweepPlanner() {
    }

    /** A rectangle of tiles in one grid. {@code x1}/{@code y1} are exclusive, as in {@code haven.Area}. */
    public static final class Piece {
        public final long gridId;
        public final int x0;
        public final int y0;
        public final int x1;
        public final int y1;

        public Piece(long gridId, int x0, int y0, int x1, int y1) {
            this.gridId = gridId;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }
    }

    public static final class Waypoint {
        public final long gridId;
        public final int tileX;
        public final int tileY;

        Waypoint(long gridId, int tileX, int tileY) {
            this.gridId = gridId;
            this.tileX = tileX;
            this.tileY = tileY;
        }
    }

    public static List<Waypoint> plan(List<Piece> pieces, int spacingTiles) {
        int spacing = Math.max(4, spacingTiles);
        List<Waypoint> waypoints = new ArrayList<>();
        for (Piece piece : pieces) {
            int width = Math.max(1, piece.x1 - piece.x0);
            int height = Math.max(1, piece.y1 - piece.y0);
            int columns = (width + spacing - 1) / spacing;
            int rows = (height + spacing - 1) / spacing;
            for (int row = 0; row < rows; row++) {
                int y = piece.y0 + (int) Math.floor((row + 0.5) * height / rows);
                for (int step = 0; step < columns; step++) {
                    int column = (row % 2 == 0) ? step : columns - 1 - step;
                    int x = piece.x0 + (int) Math.floor((column + 0.5) * width / columns);
                    waypoints.add(new Waypoint(piece.gridId, x, y));
                }
            }
        }
        return waypoints;
    }
}
