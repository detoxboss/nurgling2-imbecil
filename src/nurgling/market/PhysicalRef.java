package nurgling.market;

import org.json.JSONObject;

/**
 * A stand's physical position expressed the way Haven makes persistent: the server grid id plus an
 * offset inside that grid. Gob ids and session-relative world coordinates are not stable across
 * client sessions; a grid id is.
 */
public final class PhysicalRef {
    public final String gridId;
    public final double offX;
    public final double offY;

    public PhysicalRef(String gridId, double offX, double offY) {
        this.gridId = gridId;
        this.offX = offX;
        this.offY = offY;
    }

    /** Distance in world units, or infinity when the two positions are in different grids. */
    public double distanceTo(PhysicalRef other) {
        if (!gridId.equals(other.gridId)) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.hypot(offX - other.offX, offY - other.offY);
    }

    public static PhysicalRef fromJson(JSONObject json) {
        return new PhysicalRef(String.valueOf(json.get("gridId")), json.getDouble("offX"), json.getDouble("offY"));
    }

    public JSONObject toJson() {
        return new JSONObject().put("gridId", gridId).put("offX", offX).put("offY", offY);
    }

    @Override
    public String toString() {
        return gridId + "@" + String.format(java.util.Locale.ROOT, "%.1f,%.1f", offX, offY);
    }
}
