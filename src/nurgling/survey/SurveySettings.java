package nurgling.survey;

import haven.FColor;
import nurgling.NConfig;

import java.awt.Color;

/**
 * Fork-owned appearance/behaviour model for the Land Survey editor overlay
 * (haven.res.ui.surv.LandSurvey/Display). Backed by NConfig; every getter reads the live
 * config value, so Display's shader uniforms (zero-dependency, evaluated every frame the same
 * way as Glob.u_globtime) pick up changes the instant the settings panel saves, with no client
 * restart and no shader/buffer rebuild.
 */
public class SurveySettings {
    public enum VState { MATCH, FILL, DIG }

    private SurveySettings() {}

    private static Color color(NConfig.Key key, Color def) {
        return NConfig.getColor(key, def);
    }

    private static int pct(NConfig.Key key, int def) {
        Object v = NConfig.get(key);
        return (v instanceof Number) ? ((Number)v).intValue() : def;
    }

    private static boolean flag(NConfig.Key key, boolean def) {
        Object v = NConfig.get(key);
        return (v instanceof Boolean) ? (Boolean)v : def;
    }

    private static float size(NConfig.Key key, float def) {
        Object v = NConfig.get(key);
        return (v instanceof Number) ? ((Number)v).floatValue() : def;
    }

    public static Color vertexColor(VState s) {
        switch(s) {
        case FILL: return color(NConfig.Key.surveyVertexFillColor, new Color(0, 128, 255));
        case DIG:  return color(NConfig.Key.surveyVertexDigColor, new Color(255, 0, 255));
        default:   return color(NConfig.Key.surveyVertexMatchColor, new Color(0, 255, 0));
        }
    }

    public static int vertexOpacity(VState s) {
        switch(s) {
        case FILL: return pct(NConfig.Key.surveyVertexFillOpacity, 100);
        case DIG:  return pct(NConfig.Key.surveyVertexDigOpacity, 100);
        default:   return pct(NConfig.Key.surveyVertexMatchOpacity, 100);
        }
    }

    public static float vertexSize(VState s) {
        switch(s) {
        case FILL: return size(NConfig.Key.surveyVertexFillSize, 7f);
        case DIG:  return size(NConfig.Key.surveyVertexDigSize, 7f);
        default:   return size(NConfig.Key.surveyVertexMatchSize, 7f);
        }
    }

    public static boolean vertexVisible(VState s) {
        switch(s) {
        case FILL: return flag(NConfig.Key.surveyVertexFillVisible, true);
        case DIG:  return flag(NConfig.Key.surveyVertexDigVisible, true);
        default:   return flag(NConfig.Key.surveyVertexMatchVisible, true);
        }
    }

    public static FColor vertexFColor(VState s) {
        if(!vertexVisible(s))
            return new FColor(0, 0, 0, 0);
        Color c = vertexColor(s);
        float a = vertexOpacity(s) / 100f;
        return new FColor(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, a);
    }

    public static Color selectionHighlightColor() {
        return color(NConfig.Key.surveySelHighlightColor, new Color(255, 255, 0));
    }

    public static float selectionHighlightSize() {
        return size(NConfig.Key.surveySelHighlightSize, 11f);
    }

    public static boolean selectionHighlightVisible() {
        return flag(NConfig.Key.surveySelHighlightVisible, true);
    }

    public static FColor selectionHighlightFColor() {
        if(!selectionHighlightVisible())
            return new FColor(0, 0, 0, 0);
        Color c = selectionHighlightColor();
        return new FColor(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, 1f);
    }

    public static Color outlineColor() {
        return color(NConfig.Key.surveyOutlineColor, new Color(0, 0, 255));
    }

    public static int outlineOpacity() {
        return pct(NConfig.Key.surveyOutlineOpacity, 100);
    }

    public static int outlineWidth() {
        return (int)size(NConfig.Key.surveyOutlineWidth, 1f);
    }

    public static boolean outlineVisible() {
        return flag(NConfig.Key.surveyOutlineVisible, true);
    }

    public static Color wireColor() {
        return color(NConfig.Key.surveyWireColor, new Color(255, 255, 255));
    }

    public static int wireOpacity() {
        return pct(NConfig.Key.surveyWireOpacity, 60);
    }

    public static int wireWidth() {
        return (int)size(NConfig.Key.surveyWireWidth, 1f);
    }

    public static boolean wireVisible() {
        return flag(NConfig.Key.surveyWireVisible, false);
    }

    public static boolean fillVisible() {
        return flag(NConfig.Key.surveyFillVisible, true);
    }

    public static int fillOpacity() {
        return pct(NConfig.Key.surveyFillOpacity, 50);
    }

    public static Color fillLowColor() {
        return color(NConfig.Key.surveyFillLowColor, new Color(255, 102, 0));
    }

    public static Color fillHighColor() {
        return color(NConfig.Key.surveyFillHighColor, new Color(255, 0, 110));
    }

    public static boolean heatmapEnabled() {
        return flag(NConfig.Key.surveyHeatmapEnabled, false);
    }

    public static int undoDepth() {
        int v = (int)size(NConfig.Key.surveyUndoDepth, 20f);
        return Math.max(1, Math.min(200, v));
    }

    public static boolean newSurveyUnlocked() {
        return flag(NConfig.Key.surveyNewUnlocked, true);
    }

    /** Historical wiki-sourced reference value only, kept for existing tests that want a fixed
     *  (gran-independent) limit -- <b>no longer the production limit</b>. Follow-up #6: every
     *  production call site (validate/clamp/drag-solver/markers/status text/diagnostics) now
     *  derives its working limit from {@code SurveyConstraintValidator.limitMetersFor(gran)}
     *  (a fixed 20-raw-dz-tick count, converted through the survey's own live {@code gran}),
     *  since the fixed-2.0-m-everywhere model this constant represents was found to disagree
     *  with the real in-game boundary at {@code gran=1} (22 ticks predicted vs. 20 actual). Not
     *  currently user-configurable. */
    public static final double TERRAIN_MAX_SLOPE_METERS = 2.0;

    public static SurveyConstraintValidator.Mode terrainConstraintMode() {
        Object v = NConfig.get(NConfig.Key.surveyTerrainConstraintMode);
        if(v instanceof String) {
            try {
                return SurveyConstraintValidator.Mode.valueOf((String)v);
            } catch(IllegalArgumentException e) {
                // fall through to default
            }
        }
        return SurveyConstraintValidator.Mode.WARN;
    }

    public static void setTerrainConstraintMode(SurveyConstraintValidator.Mode mode) {
        NConfig.set(NConfig.Key.surveyTerrainConstraintMode, mode.name());
    }

    /** Fixed (not user-configurable) colours for the terrain-constraint vertex overlay -- a
     *  separate halo-style render pass, like the selection highlight, so the normal
     *  green/blue/purple status dot underneath is never hidden or replaced. */
    public static final Color TERRAIN_VIOLATION_COLOR = new Color(255, 40, 40);
    /** At-limit (not yet over it) marker -- deliberately a distinct amber/orange, separate from
     *  both red (over-limit violation) and the unknown colour below, per the "AT_LIMIT must be
     *  visually distinguishable, not just a weaker violation colour" requirement. */
    public static final Color TERRAIN_AT_LIMIT_COLOR = new Color(255, 165, 0);
    public static final Color TERRAIN_UNKNOWN_COLOR = new Color(120, 170, 255);
    public static final float TERRAIN_MARKER_SIZE = 13f;

    public static FColor fcolor(Color c, int opacityPct) {
        return new FColor(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, opacityPct / 100f);
    }
}
