package nurgling.widgets.bots;

import haven.*;
import nurgling.NUtils;
import nurgling.conf.NShoreTracerProp;
import nurgling.i18n.L10n;

/**
 * Shore Tracer's start window, renamed from the pre-split WorldExplorerWnd
 * (2026-11-05) when upstream's WorldExplorer rewrite took over that name.
 */
public class ShoreTracerWnd extends Window implements Checkable {

    public String tool = null;

    UsingTools usingTools = null;
    CheckBox clockwise;
    CheckBox unclockwise;
    CheckBox deep;
    CheckBox shallow;
    TextEntry lookaheadEntry;
    TextEntry stuckTimeoutEntry;
    TextEntry backupTilesEntry;
    TextEntry swingTilesEntry;
    TextEntry bandTilesEntry;

    public ShoreTracerWnd() {
        super(new Coord(200,200), L10n.get("shoretracer.wnd_title"));
        NShoreTracerProp startprop = NShoreTracerProp.get(NUtils.getUI().sessInfo);
        if (startprop == null) startprop = new NShoreTracerProp("", "");
        final NShoreTracerProp finalStartprop = startprop;
        prev = add(new Label(L10n.get("shoretracer.settings")));

        prev = add(clockwise = new CheckBox(L10n.get("shoretracer.clockwise")){
            {
                a = finalStartprop.clockwise;
            }
            @Override
            public void set(boolean a) {
                super.set(a);
                unclockwise.a = !a;
            }

        }, prev.pos("bl").add(UI.scale(0,5)));


        prev = add(unclockwise = new CheckBox(L10n.get("shoretracer.counterclockwise")){
            {
                a = !finalStartprop.clockwise;
            }
            @Override
            public void set(boolean a) {
                super.set(a);
                clockwise.a = !a;
            }

        }, prev.pos("bl").add(UI.scale(0,5)));

        prev = add(deep = new CheckBox(L10n.get("shoretracer.deep_deeper"))
        {
            {
                a = finalStartprop.deeper;
            }
            @Override
            public void set(boolean a) {
                super.set(a);
                shallow.a = !a;
            }

        }, prev.pos("bl").add(UI.scale(0,5)));

        prev = add(shallow = new CheckBox(L10n.get("shoretracer.deep_shallow"))
        {
            {
                a = !finalStartprop.deeper;
            }
            @Override
            public void set(boolean a) {
                super.set(a);
                deep.a = !a;
            }

        }, prev.pos("bl").add(UI.scale(0,5)));

        prev = add(new Label(L10n.get("shoretracer.lookahead")), prev.pos("bl").add(UI.scale(0,5)));
        prev = add(lookaheadEntry = new TextEntry(UI.scale(80), String.valueOf(finalStartprop.lookaheadTiles)), prev.pos("bl").add(UI.scale(0,2)));

        prev = add(new Label(L10n.get("shoretracer.stuck_timeout")), prev.pos("bl").add(UI.scale(0,5)));
        prev = add(stuckTimeoutEntry = new TextEntry(UI.scale(80), String.valueOf(finalStartprop.stuckTimeoutS)), prev.pos("bl").add(UI.scale(0,2)));

        prev = add(new Label(L10n.get("shoretracer.backup_tiles")), prev.pos("bl").add(UI.scale(0,5)));
        prev = add(backupTilesEntry = new TextEntry(UI.scale(80), String.valueOf(finalStartprop.backupTiles)), prev.pos("bl").add(UI.scale(0,2)));

        prev = add(new Label(L10n.get("shoretracer.swing_tiles")), prev.pos("bl").add(UI.scale(0,5)));
        prev = add(swingTilesEntry = new TextEntry(UI.scale(80), String.valueOf(finalStartprop.swingTiles)), prev.pos("bl").add(UI.scale(0,2)));

        prev = add(new Label(L10n.get("shoretracer.band_tiles")), prev.pos("bl").add(UI.scale(0,5)));
        prev = add(bandTilesEntry = new TextEntry(UI.scale(80), String.valueOf(finalStartprop.bandTiles)), prev.pos("bl").add(UI.scale(0,2)));

        prev = add(new Button(UI.scale(150), L10n.get("botwnd.start")){
            @Override
            public void click() {
                super.click();
                prop = NShoreTracerProp.get(NUtils.getUI().sessInfo);
                if (prop != null) {
                    prop.deeper = deep.a;
                    prop.clockwise = clockwise.a;
                    try {
                        prop.lookaheadTiles = Integer.parseInt(lookaheadEntry.text().trim());
                    } catch (NumberFormatException ignored) {}
                    try {
                        prop.stuckTimeoutS = Double.parseDouble(stuckTimeoutEntry.text().trim());
                    } catch (NumberFormatException ignored) {}
                    try {
                        prop.backupTiles = Integer.parseInt(backupTilesEntry.text().trim());
                    } catch (NumberFormatException ignored) {}
                    try {
                        prop.swingTiles = Integer.parseInt(swingTilesEntry.text().trim());
                    } catch (NumberFormatException ignored) {}
                    try {
                        prop.bandTiles = Integer.parseInt(bandTilesEntry.text().trim());
                    } catch (NumberFormatException ignored) {}
                    NShoreTracerProp.set(prop);
                }
                isReady = true;
            }
        }, prev.pos("bl").add(UI.scale(0,5)));
        pack();
    }

    @Override
    public boolean check() {
        return isReady;
    }

    boolean isReady = false;

    @Override
    public void wdgmsg(String msg, Object... args) {
        if(msg.equals("close")) {
            isReady = true;
            hide();
        }
        super.wdgmsg(msg, args);
    }
    public NShoreTracerProp prop = null;
}
