package nurgling.widgets.nsettings;

import haven.*;
import haven.res.ui.surv.LandSurvey;
import nurgling.NConfig;
import nurgling.survey.SurveyConstraintValidator;
import nurgling.survey.SurveySettings;
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
 *
 * <p>Layout uses a fixed-column, cursor-driven scheme ({@link #x0}/{@link #x1}/{@link #y}):
 * every widget's position is computed from these constants, never from a previous widget's own
 * position, so nested controls (colour swatches, per-state opacity/size rows) can't drift the
 * horizontal position of anything placed after them. Only {@link #y} advances as rows are added.
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

        /** Index into SurveyConstraintValidator.Mode.values(): Off=0, Warn=1, Constrain=2. */
        int terrainMode;
    }

    private final S t = new S();
    /** Values pending Save, pre-populated by load() and overwritten by Reset to Defaults; save()
     *  copies these into NConfig, cancel (i.e. simply not calling save()) just discards them. */
    private final S pending = new S();

    private CheckBox matchVisible, fillVisible, digVisible, selVisible, outlineVisible, wireVisible, surfVisible, heatmap, newUnlocked;
    private RadioGroup terrainMode;
    private NColorWidget matchColor, fillColor, digColor, selColor, outlineColor, wireColor, surfLow, surfHigh;
    private HSlider matchOpacity, fillOpacity, digOpacity, matchSize, fillSize, digSize, selSize,
        outlineOpacity, outlineWidth, wireOpacity, wireWidth, surfOpacity, undoDepth;
    private Label matchOpacityLbl, fillOpacityLbl, digOpacityLbl, matchSizeLbl, fillSizeLbl, digSizeLbl,
        selSizeLbl, outlineOpacityLbl, outlineWidthLbl, wireOpacityLbl, wireWidthLbl, surfOpacityLbl, undoDepthLbl;

    private final Scrollport scrollport;
    private final Widget content;

    /* Fixed layout columns + vertical cursor. x never derives from a previous widget's position,
     * so nothing here can cascade sideways; only y advances, by put()/section(). */
    private final int x0;        // section headings, top-level checkboxes
    private final int x1;        // controls nested under the row above (colour, opacity, size)
    private final int rowGap;
    private final int sectionGap;
    private int y;

    public SurveySettingsPanel() {
        super(""); // scrollport supplies its own heading below; a Panel-level title here would
                   // sit at the same (10,10) corner the scrollport occupies - see World.java.
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

        x0 = UI.scale(5);
        x1 = x0 + UI.scale(20);
        rowGap = UI.scale(5);
        sectionGap = UI.scale(15);
        y = x0;

        section("Survey vertex dots");
        matchVisible = checkRow("Terrain matches target");
        matchColor = colorRow();
        Widget[] s1 = sizeOpacityRow();
        matchOpacityLbl = (Label)s1[0]; matchOpacity = (HSlider)s1[1];
        matchSizeLbl = (Label)s1[2]; matchSize = (HSlider)s1[3];

        fillVisible = checkRow("Fill required (target above terrain)");
        fillColor = colorRow();
        Widget[] s2 = sizeOpacityRow();
        fillOpacityLbl = (Label)s2[0]; fillOpacity = (HSlider)s2[1];
        fillSizeLbl = (Label)s2[2]; fillSize = (HSlider)s2[3];

        digVisible = checkRow("Dig required (target below terrain)");
        digColor = colorRow();
        Widget[] s3 = sizeOpacityRow();
        digOpacityLbl = (Label)s3[0]; digOpacity = (HSlider)s3[1];
        digSizeLbl = (Label)s3[2]; digSize = (HSlider)s3[3];

        section("Selected / hovered point");
        selVisible = checkRow("Highlight visible");
        selColor = colorRow();
        selSizeLbl = labelRow(x1, "Highlight size 11");
        selSize = put(new HSlider(UI.scale(100), 1, 30, 11) {
            public void changed() { selSizeLbl.settext("Highlight size " + val); }
        }, x1);

        section("Sub-region selection outline");
        outlineVisible = checkRow("Outline visible (selection still works when hidden)");
        outlineColor = colorRow();
        outlineOpacityLbl = labelRow(x1, "Opacity 100%");
        outlineOpacity = put(new HSlider(UI.scale(100), 0, 100, 100) {
            public void changed() { outlineOpacityLbl.settext("Opacity " + val + "%"); }
        }, x1);
        outlineWidthLbl = labelRow(x1, "Width 1");
        outlineWidth = put(new HSlider(UI.scale(100), 1, 5, 1) {
            public void changed() { outlineWidthLbl.settext("Width " + val); }
        }, x1);

        section("Cell wireframe (separate from the outline above)");
        wireVisible = checkRow("Wireframe visible");
        wireColor = colorRow();
        wireOpacityLbl = labelRow(x1, "Opacity 60%");
        wireOpacity = put(new HSlider(UI.scale(100), 0, 100, 60) {
            public void changed() { wireOpacityLbl.settext("Opacity " + val + "%"); }
        }, x1);
        wireWidthLbl = labelRow(x1, "Width 1");
        wireWidth = put(new HSlider(UI.scale(100), 1, 5, 1) {
            public void changed() { wireWidthLbl.settext("Width " + val); }
        }, x1);

        section("Survey surface fill");
        surfVisible = put(new CheckBox("Surface fill visible") {
            public void set(boolean val) { a = val; }
        }, x0);
        surfOpacityLbl = labelRow(x0, "Opacity 50%");
        surfOpacity = put(new HSlider(UI.scale(100), 0, 100, 50) {
            public void changed() { surfOpacityLbl.settext("Opacity " + val + "%"); }
        }, x0);
        surfLow = put(new NColorWidget("Gradient: low"), x0);
        surfHigh = put(new NColorWidget("Gradient: high"), x0);
        heatmap = put(new CheckBox("Heatmap mode (dig/fill/match colours instead of the gradient above)") {
            public void set(boolean val) { a = val; }
        }, x0);

        section("Editing behaviour");
        undoDepthLbl = labelRow(x0, "Undo history depth 20");
        undoDepth = put(new HSlider(UI.scale(100), 1, 100, 20) {
            public void changed() { undoDepthLbl.settext("Undo history depth " + val); }
        }, x0);
        newUnlocked = put(new CheckBox("New surveys start unlocked") {
            public void set(boolean val) { a = val; }
        }, x0);

        section("Terrain Constraints (20 raw-tick terraforming limit, scaled by survey gran)");
        terrainMode = new RadioGroup(content) {
            public void changed(int btn, String lbl) { pending.terrainMode = btn; }
        };
        for(String lbl : new String[] {"Off", "Warn", "Constrain"}) {
            RadioGroup.RadioButton rb = terrainMode.add(lbl, new Coord(x0, y));
            y += rb.sz.y + rowGap;
        }

        y += sectionGap - rowGap;
        put(new Button(UI.scale(140), "Reset to defaults", false).action(this::resetDefaults), x0);

        content.pack();
        scrollport.cont.update();
        pack();
    }

    /* --- fixed-column layout helpers: x is always one of x0/x1 above, y only ever advances --- */

    private <T extends Widget> T put(T w, int x) {
        content.add(w, new Coord(x, y));
        y += w.sz.y + rowGap;
        return w;
    }

    private void section(String title) {
        if(y > x0)
            y += sectionGap - rowGap;
        put(new Label("● " + title), x0);
    }

    @SuppressWarnings("unchecked")
    private <T extends Widget> T labelRow(int x, String text) {
        return (T)put(new Label(text), x);
    }

    private CheckBox checkRow(String label) {
        return put(new CheckBox(label) {
            public void set(boolean val) { a = val; }
        }, x0);
    }

    private NColorWidget colorRow() {
        return put(new NColorWidget("Color"), x1);
    }

    private Widget[] sizeOpacityRow() {
        Label opLbl = labelRow(x1, "Opacity 100%");
        HSlider op = put(new HSlider(UI.scale(100), 0, 100, 100) {
            public void changed() { opLbl.settext("Opacity " + val + "%"); }
        }, x1);
        Label szLbl = labelRow(x1, "Point size 7");
        HSlider sz = put(new HSlider(UI.scale(100), 1, 20, 7) {
            public void changed() { szLbl.settext("Point size " + val); }
        }, x1);
        return(new Widget[] {opLbl, op, szLbl, sz});
    }

    /** Updates the panel's pending values and visible controls only; Save commits them to
     *  NConfig, and simply closing/cancelling leaves NConfig untouched (load() below would
     *  restore the previously saved values if the panel were reopened). */
    private void resetDefaults() {
        pending.matchColor = new Color(0, 255, 0);
        pending.fillColor = new Color(0, 128, 255);
        pending.digColor = new Color(255, 0, 255);
        pending.matchOpacity = pending.fillOpacity = pending.digOpacity = 100;
        pending.matchSize = pending.fillSize = pending.digSize = 7;
        pending.matchVisible = pending.fillVisible = pending.digVisible = true;

        pending.selColor = new Color(255, 255, 0);
        pending.selSize = 11;
        pending.selVisible = true;

        pending.outlineColor = new Color(0, 0, 255);
        pending.outlineOpacity = 100;
        pending.outlineWidth = 1;
        pending.outlineVisible = true;

        pending.wireColor = new Color(255, 255, 255);
        pending.wireOpacity = 60;
        pending.wireWidth = 1;
        pending.wireVisible = false;

        pending.surfVisible = true;
        pending.surfOpacity = 50;
        pending.surfLow = new Color(255, 102, 0);
        pending.surfHigh = new Color(255, 0, 110);
        pending.heatmap = false;

        pending.undoDepth = 20;
        pending.newUnlocked = true;

        pending.terrainMode = SurveyConstraintValidator.Mode.WARN.ordinal();

        applyToWidgets(pending);
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

        t.terrainMode = SurveySettings.terrainConstraintMode().ordinal();

        applyToWidgets(t);
    }

    /** Pushes a settings snapshot (the saved values from load(), or the hardcoded defaults from
     *  resetDefaults()) into the pending struct and the visible controls, without touching NConfig. */
    private void applyToWidgets(S src) {
        if(src != pending) {
            pending.matchColor = src.matchColor; pending.fillColor = src.fillColor; pending.digColor = src.digColor;
            pending.matchOpacity = src.matchOpacity; pending.fillOpacity = src.fillOpacity; pending.digOpacity = src.digOpacity;
            pending.matchSize = src.matchSize; pending.fillSize = src.fillSize; pending.digSize = src.digSize;
            pending.matchVisible = src.matchVisible; pending.fillVisible = src.fillVisible; pending.digVisible = src.digVisible;
            pending.selColor = src.selColor; pending.selSize = src.selSize; pending.selVisible = src.selVisible;
            pending.outlineColor = src.outlineColor; pending.outlineOpacity = src.outlineOpacity;
            pending.outlineWidth = src.outlineWidth; pending.outlineVisible = src.outlineVisible;
            pending.wireColor = src.wireColor; pending.wireOpacity = src.wireOpacity;
            pending.wireWidth = src.wireWidth; pending.wireVisible = src.wireVisible;
            pending.surfVisible = src.surfVisible; pending.surfOpacity = src.surfOpacity;
            pending.surfLow = src.surfLow; pending.surfHigh = src.surfHigh; pending.heatmap = src.heatmap;
            pending.undoDepth = src.undoDepth; pending.newUnlocked = src.newUnlocked;
            pending.terrainMode = src.terrainMode;
        }

        matchColor.color = pending.matchColor; fillColor.color = pending.fillColor; digColor.color = pending.digColor;
        matchOpacity.val = pending.matchOpacity; fillOpacity.val = pending.fillOpacity; digOpacity.val = pending.digOpacity;
        matchOpacityLbl.settext("Opacity " + pending.matchOpacity + "%");
        fillOpacityLbl.settext("Opacity " + pending.fillOpacity + "%");
        digOpacityLbl.settext("Opacity " + pending.digOpacity + "%");
        matchSize.val = pending.matchSize; fillSize.val = pending.fillSize; digSize.val = pending.digSize;
        matchSizeLbl.settext("Point size " + pending.matchSize);
        fillSizeLbl.settext("Point size " + pending.fillSize);
        digSizeLbl.settext("Point size " + pending.digSize);
        matchVisible.a = pending.matchVisible; fillVisible.a = pending.fillVisible; digVisible.a = pending.digVisible;

        selColor.color = pending.selColor;
        selSize.val = pending.selSize;
        selSizeLbl.settext("Highlight size " + pending.selSize);
        selVisible.a = pending.selVisible;

        outlineColor.color = pending.outlineColor;
        outlineOpacity.val = pending.outlineOpacity;
        outlineOpacityLbl.settext("Opacity " + pending.outlineOpacity + "%");
        outlineWidth.val = pending.outlineWidth;
        outlineWidthLbl.settext("Width " + pending.outlineWidth);
        outlineVisible.a = pending.outlineVisible;

        wireColor.color = pending.wireColor;
        wireOpacity.val = pending.wireOpacity;
        wireOpacityLbl.settext("Opacity " + pending.wireOpacity + "%");
        wireWidth.val = pending.wireWidth;
        wireWidthLbl.settext("Width " + pending.wireWidth);
        wireVisible.a = pending.wireVisible;

        surfVisible.a = pending.surfVisible;
        surfOpacity.val = pending.surfOpacity;
        surfOpacityLbl.settext("Opacity " + pending.surfOpacity + "%");
        surfLow.color = pending.surfLow;
        surfHigh.color = pending.surfHigh;
        heatmap.a = pending.heatmap;

        undoDepth.val = pending.undoDepth;
        undoDepthLbl.settext("Undo history depth " + pending.undoDepth);
        newUnlocked.a = pending.newUnlocked;

        terrainMode.check(pending.terrainMode);
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

        SurveySettings.setTerrainConstraintMode(SurveyConstraintValidator.Mode.values()[pending.terrainMode]);

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
