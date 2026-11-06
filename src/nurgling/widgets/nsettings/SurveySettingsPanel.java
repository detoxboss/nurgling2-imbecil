package nurgling.widgets.nsettings;

import haven.*;
import haven.res.ui.surv.LandSurvey;
import nurgling.NConfig;
import nurgling.widgets.NColorWidget;

import java.awt.Color;

/**
 * Nurgling Settings -> Game environment -> Survey Settings.
 *
 * <p>Fork-owned appearance/behaviour controls for the Land Survey editor
 * (haven.res.ui.surv.LandSurvey/Display), backed by nurgling.survey.SurveySettings / NConfig.
 * Colour, opacity, size and most visibility changes apply to any open survey the instant Save is
 * pressed, with no client restart (Display reads these live); outline/wireframe visibility and
 * width additionally need LandSurvey.refreshAllStyles(), called from save() below, to rebuild
 * those two render passes.
 */
public class SurveySettingsPanel extends Panel {
    private static class S {
        Color matchColor, fillColor, digColor;
        int matchOpacity, fillOpacity, digOpacity;
        int matchSize, fillSize, digSize;
        boolean matchVisible, fillVisible, digVisible;

        Color selColor;
        int selSize;
        boolean selVisible;

        Color outlineColor;
        int outlineOpacity, outlineWidth;
        boolean outlineVisible;

        Color wireColor;
        int wireOpacity, wireWidth;
        boolean wireVisible;

        boolean surfVisible;
        int surfOpacity;
        Color surfLow, surfHigh;
        boolean heatmap;

        int undoDepth;
        boolean newUnlocked;
    }

    private final S t = new S();

    private CheckBox matchVisible, fillVisible, digVisible, selVisible, outlineVisible, wireVisible, surfVisible, heatmap, newUnlocked;
    private NColorWidget matchColor, fillColor, digColor, selColor, outlineColor, wireColor, surfLow, surfHigh;
    private HSlider matchOpacity, fillOpacity, digOpacity, matchSize, fillSize, digSize, selSize,
        outlineOpacity, outlineWidth, wireOpacity, wireWidth, surfOpacity, undoDepth;
    private Label matchOpacityLbl, fillOpacityLbl, digOpacityLbl, matchSizeLbl, fillSizeLbl, digSizeLbl,
        selSizeLbl, outlineOpacityLbl, outlineWidthLbl, wireOpacityLbl, wireWidthLbl, surfOpacityLbl, undoDepthLbl;

    private final Scrollport scrollport;
    private final Widget content;

    public SurveySettingsPanel() {
        super("Survey Settings");
        int margin = UI.scale(10);
        int scrollWidth = UI.scale(560), scrollHeight = UI.scale(550);
        scrollport = add(new Scrollport(new Coord(scrollWidth, scrollHeight)), new Coord(margin, margin));
        content = new Widget(new Coord(scrollWidth - UI.scale(20), UI.scale(50))) {
            @Override
            public void pack() {
                resize(contentsz());
            }
        };
        scrollport.cont.add(content, Coord.z);

        int cm = UI.scale(5);
        Widget prev = content.add(new Label("● Survey vertex dots"), new Coord(cm, cm));

        prev = row(prev, "Terrain matches target", true);
        matchVisible = (CheckBox)lastCheck;
        matchColor = (NColorWidget)lastColor;
        Widget[] s1 = sizeOpacityRow(prev);
        matchOpacityLbl = (Label)s1[0]; matchOpacity = (HSlider)s1[1];
        matchSizeLbl = (Label)s1[2]; matchSize = (HSlider)s1[3];
        prev = s1[3];

        prev = row(prev, "Fill required (target above terrain)", true);
        fillVisible = (CheckBox)lastCheck;
        fillColor = (NColorWidget)lastColor;
        Widget[] s2 = sizeOpacityRow(prev);
        fillOpacityLbl = (Label)s2[0]; fillOpacity = (HSlider)s2[1];
        fillSizeLbl = (Label)s2[2]; fillSize = (HSlider)s2[3];
        prev = s2[3];

        prev = row(prev, "Dig required (target below terrain)", true);
        digVisible = (CheckBox)lastCheck;
        digColor = (NColorWidget)lastColor;
        Widget[] s3 = sizeOpacityRow(prev);
        digOpacityLbl = (Label)s3[0]; digOpacity = (HSlider)s3[1];
        digSizeLbl = (Label)s3[2]; digSize = (HSlider)s3[3];
        prev = s3[3];

        prev = content.add(new Label("● Selected / hovered point"), prev.pos("bl").adds(0, 15));
        prev = row(prev, "Highlight visible", true);
        selVisible = (CheckBox)lastCheck;
        selColor = (NColorWidget)lastColor;
        prev = selSizeLbl = content.add(new Label("Highlight size 11"), prev.pos("bl").adds(0, 5));
        prev = selSize = content.add(new HSlider(UI.scale(100), 1, 30, 11) {
            public void changed() { selSizeLbl.settext("Highlight size " + val); }
        }, prev.pos("bl").adds(0, 5));

        prev = content.add(new Label("● Sub-region selection outline"), prev.pos("bl").adds(0, 15));
        prev = row(prev, "Outline visible (selection still works when hidden)", true);
        outlineVisible = (CheckBox)lastCheck;
        outlineColor = (NColorWidget)lastColor;
        prev = outlineOpacityLbl = content.add(new Label("Opacity 100%"), prev.pos("bl").adds(0, 5));
        prev = outlineOpacity = content.add(new HSlider(UI.scale(100), 0, 100, 100) {
            public void changed() { outlineOpacityLbl.settext("Opacity " + val + "%"); }
        }, prev.pos("bl").adds(0, 5));
        prev = outlineWidthLbl = content.add(new Label("Width 1"), prev.pos("bl").adds(0, 5));
        prev = outlineWidth = content.add(new HSlider(UI.scale(100), 1, 5, 1) {
            public void changed() { outlineWidthLbl.settext("Width " + val); }
        }, prev.pos("bl").adds(0, 5));

        prev = content.add(new Label("● Cell wireframe (separate from the outline above)"), prev.pos("bl").adds(0, 15));
        prev = row(prev, "Wireframe visible", true);
        wireVisible = (CheckBox)lastCheck;
        wireColor = (NColorWidget)lastColor;
        prev = wireOpacityLbl = content.add(new Label("Opacity 60%"), prev.pos("bl").adds(0, 5));
        prev = wireOpacity = content.add(new HSlider(UI.scale(100), 0, 100, 60) {
            public void changed() { wireOpacityLbl.settext("Opacity " + val + "%"); }
        }, prev.pos("bl").adds(0, 5));
        prev = wireWidthLbl = content.add(new Label("Width 1"), prev.pos("bl").adds(0, 5));
        prev = wireWidth = content.add(new HSlider(UI.scale(100), 1, 5, 1) {
            public void changed() { wireWidthLbl.settext("Width " + val); }
        }, prev.pos("bl").adds(0, 5));

        prev = content.add(new Label("● Survey surface fill"), prev.pos("bl").adds(0, 15));
        prev = surfVisible = content.add(new CheckBox("Surface fill visible") {
            public void set(boolean val) { t.surfVisible = val; a = val; }
        }, prev.pos("bl").adds(0, 5));
        prev = surfOpacityLbl = content.add(new Label("Opacity 50%"), prev.pos("bl").adds(0, 5));
        prev = surfOpacity = content.add(new HSlider(UI.scale(100), 0, 100, 50) {
            public void changed() { surfOpacityLbl.settext("Opacity " + val + "%"); }
        }, prev.pos("bl").adds(0, 5));
        prev = surfLow = content.add(new NColorWidget("Gradient: low"), prev.pos("bl").adds(0, 5));
        prev = surfHigh = content.add(new NColorWidget("Gradient: high"), prev.pos("bl").adds(0, 5));
        prev = heatmap = content.add(new CheckBox("Heatmap mode (dig/fill/match colours instead of the gradient above)") {
            public void set(boolean val) { t.heatmap = val; a = val; }
        }, prev.pos("bl").adds(0, 5));

        prev = content.add(new Label("● Editing behaviour"), prev.pos("bl").adds(0, 15));
        prev = undoDepthLbl = content.add(new Label("Undo history depth 20"), prev.pos("bl").adds(0, 5));
        prev = undoDepth = content.add(new HSlider(UI.scale(100), 1, 100, 20) {
            public void changed() { undoDepthLbl.settext("Undo history depth " + val); }
        }, prev.pos("bl").adds(0, 5));
        prev = newUnlocked = content.add(new CheckBox("New surveys start unlocked") {
            public void set(boolean val) { t.newUnlocked = val; a = val; }
        }, prev.pos("bl").adds(0, 5));

        prev = content.add(new Button(UI.scale(140), "Reset to defaults", false).action(this::resetDefaults),
                            prev.pos("bl").adds(0, 15));

        content.pack();
        scrollport.cont.update();
        pack();
    }

    /* row()/sizeOpacityRow() only exist to keep the repeated three-state vertex-dot block (and
     * the colour+visibility pairs elsewhere) from being typed out three times with copy/paste
     * drift between them; they are plain layout helpers, not a general widget. */
    private CheckBox lastCheck;
    private NColorWidget lastColor;

    private Widget row(Widget prev, String label, boolean withColor) {
        Widget r = content.add(new CheckBox(label) {
            public void set(boolean val) { a = val; }
        }, prev.pos("bl").adds(0, 10));
        lastCheck = (CheckBox)r;
        if(withColor) {
            r = content.add(new NColorWidget("Color"), r.pos("bl").adds(20, 5));
            lastColor = (NColorWidget)r;
        }
        return(r);
    }

    private Widget[] sizeOpacityRow(Widget prev) {
        Label opLbl = content.add(new Label("Opacity 100%"), prev.pos("bl").adds(0, 5));
        HSlider op = content.add(new HSlider(UI.scale(100), 0, 100, 100) {
            public void changed() { opLbl.settext("Opacity " + val + "%"); }
        }, opLbl.pos("bl").adds(0, 5));
        Label szLbl = content.add(new Label("Point size 7"), op.pos("bl").adds(0, 5));
        HSlider sz = content.add(new HSlider(UI.scale(100), 1, 20, 7) {
            public void changed() { szLbl.settext("Point size " + val); }
        }, szLbl.pos("bl").adds(0, 5));
        return(new Widget[] {opLbl, op, szLbl, sz});
    }

    private void resetDefaults() {
        for(NConfig.Key k : new NConfig.Key[] {
            NConfig.Key.surveyVertexMatchColor, NConfig.Key.surveyVertexFillColor, NConfig.Key.surveyVertexDigColor,
            NConfig.Key.surveyVertexMatchOpacity, NConfig.Key.surveyVertexFillOpacity, NConfig.Key.surveyVertexDigOpacity,
            NConfig.Key.surveyVertexMatchSize, NConfig.Key.surveyVertexFillSize, NConfig.Key.surveyVertexDigSize,
            NConfig.Key.surveyVertexMatchVisible, NConfig.Key.surveyVertexFillVisible, NConfig.Key.surveyVertexDigVisible,
            NConfig.Key.surveySelHighlightColor, NConfig.Key.surveySelHighlightSize, NConfig.Key.surveySelHighlightVisible,
            NConfig.Key.surveyOutlineColor, NConfig.Key.surveyOutlineOpacity, NConfig.Key.surveyOutlineWidth, NConfig.Key.surveyOutlineVisible,
            NConfig.Key.surveyWireColor, NConfig.Key.surveyWireOpacity, NConfig.Key.surveyWireWidth, NConfig.Key.surveyWireVisible,
            NConfig.Key.surveyFillVisible, NConfig.Key.surveyFillOpacity, NConfig.Key.surveyFillLowColor, NConfig.Key.surveyFillHighColor,
            NConfig.Key.surveyHeatmapEnabled, NConfig.Key.surveyUndoDepth, NConfig.Key.surveyNewUnlocked}) {
            NConfig.set(k, null);
        }
        load();
    }

    @Override
    public void load() {
        t.matchColor = NConfig.getColor(NConfig.Key.surveyVertexMatchColor, new Color(0, 255, 0));
        t.fillColor = NConfig.getColor(NConfig.Key.surveyVertexFillColor, new Color(0, 128, 255));
        t.digColor = NConfig.getColor(NConfig.Key.surveyVertexDigColor, new Color(255, 0, 255));
        t.matchOpacity = intOf(NConfig.Key.surveyVertexMatchOpacity, 100);
        t.fillOpacity = intOf(NConfig.Key.surveyVertexFillOpacity, 100);
        t.digOpacity = intOf(NConfig.Key.surveyVertexDigOpacity, 100);
        t.matchSize = intOf(NConfig.Key.surveyVertexMatchSize, 7);
        t.fillSize = intOf(NConfig.Key.surveyVertexFillSize, 7);
        t.digSize = intOf(NConfig.Key.surveyVertexDigSize, 7);
        t.matchVisible = boolOf(NConfig.Key.surveyVertexMatchVisible, true);
        t.fillVisible = boolOf(NConfig.Key.surveyVertexFillVisible, true);
        t.digVisible = boolOf(NConfig.Key.surveyVertexDigVisible, true);

        t.selColor = NConfig.getColor(NConfig.Key.surveySelHighlightColor, new Color(255, 255, 0));
        t.selSize = intOf(NConfig.Key.surveySelHighlightSize, 11);
        t.selVisible = boolOf(NConfig.Key.surveySelHighlightVisible, true);

        t.outlineColor = NConfig.getColor(NConfig.Key.surveyOutlineColor, new Color(0, 0, 255));
        t.outlineOpacity = intOf(NConfig.Key.surveyOutlineOpacity, 100);
        t.outlineWidth = intOf(NConfig.Key.surveyOutlineWidth, 1);
        t.outlineVisible = boolOf(NConfig.Key.surveyOutlineVisible, true);

        t.wireColor = NConfig.getColor(NConfig.Key.surveyWireColor, new Color(255, 255, 255));
        t.wireOpacity = intOf(NConfig.Key.surveyWireOpacity, 60);
        t.wireWidth = intOf(NConfig.Key.surveyWireWidth, 1);
        t.wireVisible = boolOf(NConfig.Key.surveyWireVisible, false);

        t.surfVisible = boolOf(NConfig.Key.surveyFillVisible, true);
        t.surfOpacity = intOf(NConfig.Key.surveyFillOpacity, 50);
        t.surfLow = NConfig.getColor(NConfig.Key.surveyFillLowColor, new Color(255, 102, 0));
        t.surfHigh = NConfig.getColor(NConfig.Key.surveyFillHighColor, new Color(255, 0, 110));
        t.heatmap = boolOf(NConfig.Key.surveyHeatmapEnabled, false);

        t.undoDepth = intOf(NConfig.Key.surveyUndoDepth, 20);
        t.newUnlocked = boolOf(NConfig.Key.surveyNewUnlocked, true);

        matchColor.color = t.matchColor; fillColor.color = t.fillColor; digColor.color = t.digColor;
        matchOpacity.val = t.matchOpacity; fillOpacity.val = t.fillOpacity; digOpacity.val = t.digOpacity;
        matchOpacityLbl.settext("Opacity " + t.matchOpacity + "%");
        fillOpacityLbl.settext("Opacity " + t.fillOpacity + "%");
        digOpacityLbl.settext("Opacity " + t.digOpacity + "%");
        matchSize.val = t.matchSize; fillSize.val = t.fillSize; digSize.val = t.digSize;
        matchSizeLbl.settext("Point size " + t.matchSize);
        fillSizeLbl.settext("Point size " + t.fillSize);
        digSizeLbl.settext("Point size " + t.digSize);
        matchVisible.a = t.matchVisible; fillVisible.a = t.fillVisible; digVisible.a = t.digVisible;

        selColor.color = t.selColor;
        selSize.val = t.selSize;
        selSizeLbl.settext("Highlight size " + t.selSize);
        selVisible.a = t.selVisible;

        outlineColor.color = t.outlineColor;
        outlineOpacity.val = t.outlineOpacity;
        outlineOpacityLbl.settext("Opacity " + t.outlineOpacity + "%");
        outlineWidth.val = t.outlineWidth;
        outlineWidthLbl.settext("Width " + t.outlineWidth);
        outlineVisible.a = t.outlineVisible;

        wireColor.color = t.wireColor;
        wireOpacity.val = t.wireOpacity;
        wireOpacityLbl.settext("Opacity " + t.wireOpacity + "%");
        wireWidth.val = t.wireWidth;
        wireWidthLbl.settext("Width " + t.wireWidth);
        wireVisible.a = t.wireVisible;

        surfVisible.a = t.surfVisible;
        surfOpacity.val = t.surfOpacity;
        surfOpacityLbl.settext("Opacity " + t.surfOpacity + "%");
        surfLow.color = t.surfLow;
        surfHigh.color = t.surfHigh;
        heatmap.a = t.heatmap;

        undoDepth.val = t.undoDepth;
        undoDepthLbl.settext("Undo history depth " + t.undoDepth);
        newUnlocked.a = t.newUnlocked;
    }

    @Override
    public void save() {
        NConfig.setColor(NConfig.Key.surveyVertexMatchColor, matchColor.color);
        NConfig.setColor(NConfig.Key.surveyVertexFillColor, fillColor.color);
        NConfig.setColor(NConfig.Key.surveyVertexDigColor, digColor.color);
        NConfig.set(NConfig.Key.surveyVertexMatchOpacity, matchOpacity.val);
        NConfig.set(NConfig.Key.surveyVertexFillOpacity, fillOpacity.val);
        NConfig.set(NConfig.Key.surveyVertexDigOpacity, digOpacity.val);
        NConfig.set(NConfig.Key.surveyVertexMatchSize, matchSize.val);
        NConfig.set(NConfig.Key.surveyVertexFillSize, fillSize.val);
        NConfig.set(NConfig.Key.surveyVertexDigSize, digSize.val);
        NConfig.set(NConfig.Key.surveyVertexMatchVisible, matchVisible.a);
        NConfig.set(NConfig.Key.surveyVertexFillVisible, fillVisible.a);
        NConfig.set(NConfig.Key.surveyVertexDigVisible, digVisible.a);

        NConfig.setColor(NConfig.Key.surveySelHighlightColor, selColor.color);
        NConfig.set(NConfig.Key.surveySelHighlightSize, selSize.val);
        NConfig.set(NConfig.Key.surveySelHighlightVisible, selVisible.a);

        NConfig.setColor(NConfig.Key.surveyOutlineColor, outlineColor.color);
        NConfig.set(NConfig.Key.surveyOutlineOpacity, outlineOpacity.val);
        NConfig.set(NConfig.Key.surveyOutlineWidth, outlineWidth.val);
        NConfig.set(NConfig.Key.surveyOutlineVisible, outlineVisible.a);

        NConfig.setColor(NConfig.Key.surveyWireColor, wireColor.color);
        NConfig.set(NConfig.Key.surveyWireOpacity, wireOpacity.val);
        NConfig.set(NConfig.Key.surveyWireWidth, wireWidth.val);
        NConfig.set(NConfig.Key.surveyWireVisible, wireVisible.a);

        NConfig.set(NConfig.Key.surveyFillVisible, surfVisible.a);
        NConfig.set(NConfig.Key.surveyFillOpacity, surfOpacity.val);
        NConfig.setColor(NConfig.Key.surveyFillLowColor, surfLow.color);
        NConfig.setColor(NConfig.Key.surveyFillHighColor, surfHigh.color);
        NConfig.set(NConfig.Key.surveyHeatmapEnabled, heatmap.a);

        NConfig.set(NConfig.Key.surveyUndoDepth, undoDepth.val);
        NConfig.set(NConfig.Key.surveyNewUnlocked, newUnlocked.a);

        NConfig.needUpdate();
        LandSurvey.refreshAllStyles();
    }

    private static int intOf(NConfig.Key key, int def) {
        Object v = NConfig.get(key);
        return (v instanceof Number) ? ((Number)v).intValue() : def;
    }

    private static boolean boolOf(NConfig.Key key, boolean def) {
        Object v = NConfig.get(key);
        return (v instanceof Boolean) ? (Boolean)v : def;
    }
}
