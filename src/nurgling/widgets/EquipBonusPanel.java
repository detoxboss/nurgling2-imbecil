package nurgling.widgets;

import haven.*;
import nurgling.i18n.L10n;
import nurgling.iteminfo.bonus.AttrBonus;
import nurgling.iteminfo.bonus.EquipmentBonuses;
import nurgling.styles.TooltipStyle;
import nurgling.styles.UITheme;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * The "Equipment Bonuses" sidebar of the equipment window: a scrollable list of the attribute
 * bonuses currently contributed by worn items. It only displays an
 * {@link EquipmentBonuses.Result}; {@link NEquipory} decides when to recompute it.
 */
public class EquipBonusPanel extends Widget {
    public static final int WIDTH = 190;

    private static final int PAD = 6;
    private static final int ROW_H = 18;
    private static final int ICON = 14;

    private static Text.Foundry titleFnd, nameFnd, valueFnd, hintFnd;

    private static Text.Foundry titleFnd() {
        if(titleFnd == null)
            titleFnd = TooltipStyle.createFoundry(true, 12, Color.WHITE);
        return(titleFnd);
    }

    private static Text.Foundry nameFnd() {
        if(nameFnd == null)
            nameFnd = TooltipStyle.createFoundry(false, 11, Color.WHITE);
        return(nameFnd);
    }

    private static Text.Foundry valueFnd() {
        if(valueFnd == null)
            valueFnd = TooltipStyle.createFoundry(true, 11, Color.WHITE);
        return(valueFnd);
    }

    private static Text.Foundry hintFnd() {
        if(hintFnd == null)
            hintFnd = TooltipStyle.createFoundry(false, 10, Color.WHITE);
        return(hintFnd);
    }

    private static class Row {
        final Tex icon;
        final Tex name, value;

        Row(Tex icon, Tex name, Tex value) {
            this.icon = icon;
            this.name = name;
            this.value = value;
        }

        void dispose() {
            if(icon != null) icon.dispose();
            name.dispose();
            value.dispose();
        }
    }

    private final Scrollbar bar;
    private final Tex title;
    private final int listTop;
    private List<Row> rows = new ArrayList<>();
    private Tex status = null;
    private int viewH;

    public EquipBonusPanel(Coord sz) {
        super(sz);
        title = titleFnd().render(L10n.get("equipment.bonuses.title"), UITheme.TEXT).tex();
        listTop = UI.scale(PAD) + title.sz().y + UI.scale(6);
        viewH = Math.max(UI.scale(ROW_H), sz.y - listTop - UI.scale(PAD));
        bar = adda(new Scrollbar(viewH, 0, 0), sz.x - UI.scale(2), listTop, 1, 0);
        bar.visible = false;
    }

    /** Replace the shown totals. Cheap enough to call on every change of the equipment. */
    public void setResult(EquipmentBonuses.Result res) {
        List<Row> old = rows;
        List<Row> nrows = new ArrayList<>();
        int iconPx = UI.scale(ICON);
        for(AttrBonus b : res.totals) {
            Tex icon = null;
            if(b.icon != null)
                icon = new TexI(PUtils.convolvedown(b.icon, Coord.of(iconPx), CharWnd.iconfilter));
            Color vc = b.beneficial() ? TooltipStyle.COLOR_STUDY_TIME : TooltipStyle.COLOR_NEGATIVE_STAT;
            nrows.add(new Row(icon, nameFnd().render(b.name, UITheme.TEXT).tex(), valueFnd().render(b.text(), vc).tex()));
        }
        String msg = null;
        if(res.pending > 0)
            msg = L10n.get("equipment.bonuses.loading");
        else if(res.unsupported > 0)
            msg = L10n.get("equipment.bonuses.unsupported");
        else if(res.totals.isEmpty())
            msg = L10n.get("equipment.bonuses.empty");
        Tex ostatus = status;
        status = (msg == null) ? null : hintFnd().render(msg, UITheme.MUTED).tex();
        rows = nrows;
        for(Row r : old)
            r.dispose();
        if(ostatus != null)
            ostatus.dispose();

        int content = rows.size() * UI.scale(ROW_H);
        bar.visible = content > viewH;
        bar.max = Math.max(0, content - viewH);
        bar.ch(0);
    }

    private int rowWidth() {
        return(sz.x - UI.scale(PAD) * 2 - (bar.visible ? bar.sz.x + UI.scale(2) : 0));
    }

    @Override
    public void draw(GOut g) {
        if(UITheme.on()) {
            UITheme.panel(g, Coord.z, sz, UITheme.PANEL, UITheme.LINE);
        } else {
            UITheme.panel(g, Coord.z, sz, new Color(0, 0, 0, 120), new Color(255, 255, 255, 70));
        }
        g.image(title, new Coord(UI.scale(PAD), UI.scale(PAD)));
        int rh = UI.scale(ROW_H);
        int w = rowWidth();
        GOut lg = g.reclip(new Coord(UI.scale(PAD), listTop), new Coord(w, viewH));
        int y = -bar.val;
        for(Row r : rows) {
            if(y + rh > 0 && y < viewH) {
                int x = 0;
                if(r.icon != null)
                    lg.image(r.icon, new Coord(x, y + (rh - r.icon.sz().y) / 2));
                x += UI.scale(ICON) + UI.scale(4);
                lg.image(r.name, new Coord(x, y + (rh - r.name.sz().y) / 2));
                lg.image(r.value, new Coord(w - r.value.sz().x, y + (rh - r.value.sz().y) / 2));
            }
            y += rh;
        }
        if(status != null) {
            int sy = rows.isEmpty() ? 0 : Math.min(y, viewH - status.sz().y);
            lg.image(status, new Coord(0, Math.max(0, sy)));
        }
        super.draw(g);
    }

    @Override
    public boolean mousewheel(MouseWheelEvent ev) {
        if(bar.visible) {
            bar.ch(ev.a * UI.scale(ROW_H));
            return(true);
        }
        return(super.mousewheel(ev));
    }

    /** Small tab on the edge of the equipment window that opens and closes the sidebar. */
    public static class Toggle extends Widget {
        public static final int WIDTH = 14;
        public static final int HEIGHT = 40;

        private final java.util.function.BooleanSupplier expanded;
        private final Runnable action;
        private boolean hover = false, pressed = false;
        private UI.Grab<?> grab = null;

        public Toggle(java.util.function.BooleanSupplier expanded, Runnable action) {
            super(UI.scale(new Coord(WIDTH, HEIGHT)));
            this.expanded = expanded;
            this.action = action;
        }

        @Override
        public void draw(GOut g) {
            Color fill = (pressed && hover) ? UITheme.SELECTED : hover ? UITheme.HOVER : UITheme.PANEL;
            if(UITheme.on())
                UITheme.panel(g, Coord.z, sz, fill, UITheme.LINE);
            else
                UITheme.panel(g, Coord.z, sz, hover ? new Color(60, 60, 60, 200) : new Color(0, 0, 0, 150), new Color(255, 255, 255, 90));
            /* a filled triangle, one column at a time: points right when collapsed, left when open */
            int s = Math.max(1, UI.scale(1));
            int cols = 4;
            boolean right = !expanded.getAsBoolean();
            g.chcolor(UITheme.TEXT);
            int cx = (sz.x - cols * s) / 2;
            for(int i = 0; i < cols; i++) {
                int h = (cols * 2 - 1 - i * 2) * s;
                int col = right ? i : (cols - 1 - i);
                g.frect(new Coord(cx + col * s, (sz.y - h) / 2), new Coord(s, h));
            }
            g.chcolor();
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            if(ev.b != 1)
                return(false);
            pressed = true;
            grab = ui.grabmouse(this);
            return(true);
        }

        @Override
        public boolean mouseup(MouseUpEvent ev) {
            if(grab == null || ev.b != 1)
                return(false);
            grab.remove();
            grab = null;
            boolean fire = pressed && hover;
            pressed = false;
            if(fire)
                action.run();
            return(true);
        }

        @Override
        public void mousemove(MouseMoveEvent ev) {
            hover = ev.c.isect(Coord.z, sz);
        }

        @Override
        public Object tooltip(Coord c, Widget prev) {
            return(L10n.get(expanded.getAsBoolean() ? "equipment.bonuses.collapse" : "equipment.bonuses.expand"));
        }
    }
}
