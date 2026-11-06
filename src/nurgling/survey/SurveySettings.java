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

    public static FColor fcolor(Color c, int opacityPct) {
        return new FColor(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, opacityPct / 100f);
    }
}
