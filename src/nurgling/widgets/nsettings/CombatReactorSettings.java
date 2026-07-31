package nurgling.widgets.nsettings;

import haven.CheckBox;
import haven.Coord;
import haven.Label;
import haven.TextEntry;
import haven.UI;
import nurgling.NConfig;
import nurgling.widgets.NCombatReactor;

/**
 * Settings panel for the Nurgling-native combat reactor (defense automation
 * plus manual attack-trigger recommender for the Quick Barrage / Full
 * Circle / Sting deck). See
 * docs/haven-combat-reactor-nurgling-port-brief.md.
 */
public class CombatReactorSettings extends Panel {

    private CheckBox masterEnable;
    private TextEntry thresholdEntry;

    public CombatReactorSettings() {
        super("Combat Reactor");

        int margin = UI.scale(10);
        int y = UI.scale(40);
        int lineHeight = UI.scale(28);
        int entryWidth = UI.scale(50);

        add(new Label("Automates defense and recommends an attack for the Quick Barrage / Full Circle / Sting deck."),
            new Coord(margin, y));
        y += lineHeight;
        add(new Label("Never picks targets, starts/ends combat, or fires an attack without the manual trigger below."),
            new Coord(margin, y));
        y += lineHeight;

        masterEnable = add(new CheckBox("Enable combat reactor") {
            public void set(boolean val) {
                a = val;
            }
        }, new Coord(margin, y));
        y += lineHeight;

        add(new Label("Manual attack trigger: " + NCombatReactor.kb_attack.key().name()), new Coord(margin, y));
        y += lineHeight;

        add(new Label("Defense threshold (minimum opening before clearing it):"), new Coord(margin, y));
        thresholdEntry = add(new TextEntry(entryWidth, ""), new Coord(margin + UI.scale(280), y));
        y += lineHeight;

        add(new Label("Diagnostics (openings/IP/recommendation) are shown near the fight HUD while enabled and in combat."),
            new Coord(margin, y));
    }

    @Override
    public void load() {
        Boolean enabled = (Boolean) NConfig.get(NConfig.Key.combatReactorEnabled);
        masterEnable.a = enabled != null && enabled;
        thresholdEntry.settext(String.valueOf(getConfigInt(NConfig.Key.combatReactorDefenseThreshold, 40)));
    }

    @Override
    public void save() {
        NConfig.set(NConfig.Key.combatReactorEnabled, masterEnable.a);
        NConfig.set(NConfig.Key.combatReactorDefenseThreshold, parseIntSafe(thresholdEntry.text(), 40));
        NConfig.needUpdate();
    }

    private int getConfigInt(NConfig.Key key, int defaultValue) {
        Object val = NConfig.get(key);
        if(val instanceof Number)
            return ((Number) val).intValue();
        return defaultValue;
    }

    private int parseIntSafe(String text, int defaultValue) {
        try {
            return Integer.parseInt(text.trim());
        } catch(NumberFormatException e) {
            return defaultValue;
        }
    }
}
