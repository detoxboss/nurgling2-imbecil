package nurgling.widgets.bots;

import haven.*;
import nurgling.NUtils;
import nurgling.conf.NLpAssistantProp;
import nurgling.i18n.L10n;

public class LpAssistant extends Window implements Checkable {

    UsingTools usingBoardTool = null;
    UsingTools usingBlockTool = null;
    UsingTools usingStoneTool = null;
    TextEntry oldtrunkToolInput = null;
    CheckBox autoEatNew = null;
    CheckBox autoDropNew = null;
    CheckBox debug = null;

    public LpAssistant() {
        super(new Coord(220, 360), L10n.get("lpassistantbot.wnd_title"));
        NLpAssistantProp startprop = NLpAssistantProp.get(NUtils.getUI().sessInfo);
        if (startprop == null) startprop = new NLpAssistantProp("", "");
        final NLpAssistantProp finalStartprop = startprop;

        prev = add(new Label(L10n.get("lpassistantbot.settings")));

        // Bough/bark/leaf/seed picking needs no tool - confirmed by the existing CollectBough/
        // CollectBark/CollectLeaf/Forager bots, none of which equip anything. Only sawing a log
        // into boards, chopping it into blocks, or chipping stone does.
        prev = add(new Label(L10n.get("lpassistantbot.board_tool")), prev.pos("bl").add(UI.scale(0, 5)));
        prev = add(usingBoardTool = new UsingTools(UsingTools.Tools.saw), prev.pos("bl").add(UI.scale(0, 2)));
        setSelected(usingBoardTool, UsingTools.Tools.saw, finalStartprop.boardTool);

        prev = add(new Label(L10n.get("lpassistantbot.block_tool")), prev.pos("bl").add(UI.scale(0, 5)));
        prev = add(usingBlockTool = new UsingTools(UsingTools.Tools.axes), prev.pos("bl").add(UI.scale(0, 2)));
        setSelected(usingBlockTool, UsingTools.Tools.axes, finalStartprop.blockTool);

        prev = add(new Label(L10n.get("lpassistantbot.stone_tool")), prev.pos("bl").add(UI.scale(0, 5)));
        prev = add(usingStoneTool = new UsingTools(UsingTools.Tools.pickaxe), prev.pos("bl").add(UI.scale(0, 2)));
        setSelected(usingStoneTool, UsingTools.Tools.pickaxe, finalStartprop.stoneTool);

        // Old-trunk (Block of Mirkwood) harvesting has no existing reference bot to confirm a tool
        // requirement from - left as an optional free-text alias (blank = no tool required).
        prev = add(new Label(L10n.get("lpassistantbot.oldtrunk_tool")), prev.pos("bl").add(UI.scale(0, 5)));
        prev = add(oldtrunkToolInput = new TextEntry(UI.scale(180), finalStartprop.oldtrunkTool == null ? "" : finalStartprop.oldtrunkTool),
                prev.pos("bl").add(UI.scale(0, 2)));

        prev = add(autoEatNew = new CheckBox(L10n.get("lpassistantbot.auto_eat")) {
            {
                a = finalStartprop.autoEatNew;
            }
            @Override
            public void set(boolean a) {
                super.set(a);
            }
        }, prev.pos("bl").add(UI.scale(0, 10)));

        prev = add(autoDropNew = new CheckBox(L10n.get("lpassistantbot.auto_drop")) {
            {
                a = finalStartprop.autoDropNew;
            }
            @Override
            public void set(boolean a) {
                super.set(a);
            }
        }, prev.pos("bl").add(UI.scale(0, 5)));

        prev = add(debug = new CheckBox(L10n.get("lpassistantbot.debug")) {
            {
                a = finalStartprop.debug;
            }
            @Override
            public void set(boolean a) {
                super.set(a);
            }
        }, prev.pos("bl").add(UI.scale(0, 5)));

        prev = add(new Button(UI.scale(150), L10n.get("botwnd.start")) {
            @Override
            public void click() {
                super.click();
                prop = NLpAssistantProp.get(NUtils.getUI().sessInfo);
                if (prop != null) {
                    prop.boardTool = usingBoardTool.s != null ? usingBoardTool.s.name : null;
                    prop.blockTool = usingBlockTool.s != null ? usingBlockTool.s.name : null;
                    prop.stoneTool = usingStoneTool.s != null ? usingStoneTool.s.name : null;
                    prop.oldtrunkTool = emptyToNull(oldtrunkToolInput.text());
                    prop.autoEatNew = autoEatNew.a;
                    prop.autoDropNew = autoDropNew.a;
                    prop.debug = debug.a;
                    NLpAssistantProp.set(prop);
                }
                isReady = true;
            }
        }, prev.pos("bl").add(UI.scale(0, 5)));
        pack();
    }

    private static void setSelected(UsingTools widget, java.util.ArrayList<UsingTools.Tool> tools, String name) {
        if (name == null)
            return;
        for (UsingTools.Tool tl : tools) {
            if (tl.name.equals(name)) {
                widget.s = tl;
                break;
            }
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    @Override
    public boolean check() {
        return isReady;
    }

    boolean isReady = false;

    @Override
    public void wdgmsg(String msg, Object... args) {
        if (msg.equals("close")) {
            isReady = true;
            hide();
        }
        super.wdgmsg(msg, args);
    }

    public NLpAssistantProp prop = null;
}
