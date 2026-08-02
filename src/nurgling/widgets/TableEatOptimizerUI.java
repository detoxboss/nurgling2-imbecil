package nurgling.widgets;

import haven.*;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.OptimizeTableEating;
import nurgling.sessions.BotExecutor;
import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Adds an "Optimize Eating" button and a 3-slot priority-attribute picker to eating
 * table windows (see {@code nurgling.actions.OptimizeTableEating} for the algorithm,
 * {@code docs/feps-system-reference.md} for the underlying mechanics).
 * <p>
 * Deliberately standalone: it does not depend on or modify
 * {@link TableInventoryExtension} (a separate, independently-evolving nurgling widget
 * for the same table windows) -- it does its own lightweight table/food-grid/Feast!
 * detection and positions itself below whatever else is currently visible in the
 * window, so it stays visually clear of that extension (or any future one) without
 * needing to know about it. Its only touchpoint elsewhere is a single hook line added
 * to {@code NInventory.added()}, mirroring how every other per-container UI extension
 * in this codebase is wired in.
 */
public class TableEatOptimizerUI
{
    private TableEatOptimizerUI() { throw new UnsupportedOperationException("Utility class"); }

    private static final Set<String> TABLE_RES = new HashSet<>(Arrays.asList(
        "gfx/terobjs/furn/table-stone",
        "gfx/terobjs/furn/table-rustic",
        "gfx/terobjs/furn/table-elegant",
        "gfx/terobjs/furn/cottagetable"
    ));

    private static final int GAP = UI.scale(4);

    public static void installIfTable(NInventory inv)
    {
        if (inv == null || inv.parent == null) return;
        if (!isTableInventory(inv)) return;
        Window wnd = inv.getparent(Window.class);
        if (wnd == null) return;
        if (findController(wnd) != null) return; // idempotent: the table has multiple inventories, each fires added()

        PriorityPicker picker = new PriorityPicker();
        picker.visible = false;
        OptimizeButton button = new OptimizeButton(picker);
        button.visible = false;
        Controller ctrl = new Controller(button, picker);

        wnd.add(picker, Coord.z);
        wnd.add(button, Coord.z);
        wnd.add(ctrl, Coord.z);
    }

    private static boolean isTableInventory(NInventory inv)
    {
        if (inv.parentGob == null) return false;
        Drawable d = inv.parentGob.getattr(Drawable.class);
        if (d == null || d.getres() == null) return false;
        return TABLE_RES.contains(d.getres().name);
    }

    private static Controller findController(Widget wnd)
    {
        for (Widget w = wnd.child; w != null; w = w.next)
            if (w instanceof Controller) return (Controller) w;
        return null;
    }

    private static Inventory findFoodInventory(Window wnd)
    {
        Inventory food = null;
        long area = -1;
        for (Widget w = wnd.child; w != null; w = w.next)
        {
            if (w instanceof Inventory)
            {
                Inventory iv = (Inventory) w;
                long a = (long) iv.sz.x * iv.sz.y;
                if (a > area) { area = a; food = iv; }
            }
        }
        return food;
    }

    private static Button findFeastButton(Window wnd)
    {
        for (Widget w = wnd.child; w != null; w = w.next)
        {
            if (w instanceof Button)
            {
                Button b = (Button) w;
                if (b.text != null && "Feast!".equals(b.text.text)) return b;
            }
        }
        return null;
    }

    /**
     * Invisible per-window controller: shows the button + picker only while the table
     * currently has a live "Feast!" button (same signal {@link TableInventoryExtension}
     * uses for its own enhanced UI), and positions them below the lowest currently
     * visible sibling -- excluding its own widgets, to avoid a runaway feedback loop
     * where each tick pushes itself further down.
     */
    private static class Controller extends Widget
    {
        private final OptimizeButton button;
        private final PriorityPicker picker;

        Controller(OptimizeButton button, PriorityPicker picker)
        {
            super(new Coord(1, 1));
            visible = false;
            this.button = button;
            this.picker = picker;
        }

        @Override
        public void tick(double dt)
        {
            super.tick(dt);
            Window wnd = getparent(Window.class);
            if (wnd == null) return;

            Button feast = findFeastButton(wnd);
            Inventory food = findFoodInventory(wnd);
            boolean show = (feast != null) && (food != null);
            button.visible = show;
            picker.visible = show;
            if (!show) return;

            int bottom = 0;
            for (Widget w = wnd.child; w != null; w = w.next)
            {
                if (w == this || w == button || w == picker || w == wnd.deco || !w.visible) continue;
                bottom = Math.max(bottom, w.c.y + w.sz.y);
            }

            picker.c = new Coord(food.c.x, bottom + GAP);
            button.c = new Coord(food.c.x, picker.c.y + picker.sz.y + GAP);
            wnd.pack();
        }
    }

    /** Row of 9 toggleable attribute icons; up to 3 may be selected as eating priorities. */
    private static class PriorityPicker extends Widget
    {
        static final int ICON = UI.scale(18);
        static final int ICON_GAP = UI.scale(2);
        static final Color SEL_BG = new Color(210, 175, 40);
        static final Color UNSEL_BG = new Color(60, 60, 60);

        final LinkedHashSet<String> selected = new LinkedHashSet<>();
        final Tex[] icons = new Tex[OptimizeTableEating.ATTR_CODES.size()];

        PriorityPicker()
        {
            super(new Coord(OptimizeTableEating.ATTR_CODES.size() * (ICON + ICON_GAP), ICON));
            selected.addAll(loadSelection());
        }

        @Override
        public void tick(double dt)
        {
            super.tick(dt);
            for (int i = 0; i < icons.length; i++)
            {
                if (icons[i] != null) continue;
                try
                {
                    Resource res = Resource.local().load("gfx/hud/chr/" + OptimizeTableEating.ATTR_CODES.get(i)).get();
                    if (res == null) continue;
                    Resource.Image img = res.layer(Resource.imgc);
                    if (img != null)
                        icons[i] = new TexI(PUtils.convolvedown(img.img, new Coord(ICON, ICON), CharWnd.iconfilter));
                }
                catch (Loading ignored)
                {
                    // try again next tick
                }
            }
        }

        @Override
        public void draw(GOut g)
        {
            for (int i = 0; i < icons.length; i++)
            {
                int x = i * (ICON + ICON_GAP);
                boolean sel = selected.contains(OptimizeTableEating.ATTR_CODES.get(i));
                g.chcolor(sel ? SEL_BG : UNSEL_BG);
                g.frect(new Coord(x, 0), new Coord(ICON, ICON));
                g.chcolor();
                if (icons[i] != null)
                    g.image(icons[i], new Coord(x, 0));
            }
        }

        @Override
        public boolean mousedown(MouseDownEvent ev)
        {
            if (ev.b != 1) return super.mousedown(ev);
            int i = ev.c.x / (ICON + ICON_GAP);
            if (i < 0 || i >= OptimizeTableEating.ATTR_CODES.size()) return super.mousedown(ev);

            String code = OptimizeTableEating.ATTR_CODES.get(i);
            if (selected.contains(code))
                selected.remove(code);
            else if (selected.size() < 3)
                selected.add(code);
            saveSelection();
            return true;
        }

        @Override
        public Object tooltip(Coord c, Widget prev)
        {
            int i = c.x / (ICON + ICON_GAP);
            if (i < 0 || i >= OptimizeTableEating.ATTR_CODES.size()) return null;
            return Text.render("Priority: " + OptimizeTableEating.ATTR_CODES.get(i) + " (click to toggle, up to 3)").tex();
        }

        List<String> selected()
        {
            return new ArrayList<>(selected);
        }

        // --- persistence: a tiny standalone file, deliberately not routed through
        // NConfig's central key registry so this feature stays fully self-contained. ---

        private static Path storePath()
        {
            return NUtils.getDataFilePath("food_optimizer_priority.nurgling.json");
        }

        private static List<String> loadSelection()
        {
            List<String> result = new ArrayList<>();
            try
            {
                Path path = storePath();
                if (!Files.exists(path)) return result;
                JSONObject root = new JSONObject(Files.readString(path));
                JSONArray arr = root.optJSONArray("priority");
                if (arr != null)
                    for (int i = 0; i < arr.length(); i++)
                        result.add(arr.getString(i));
            }
            catch (Exception e)
            {
                System.err.println("[TableEatOptimizerUI] failed to load priority selection: " + e.getMessage());
            }
            return result;
        }

        private void saveSelection()
        {
            try
            {
                JSONObject root = new JSONObject();
                root.put("priority", new JSONArray(selected));
                NFileUtils.writeAtomically(storePath().toString(), root.toString());
            }
            catch (Exception e)
            {
                System.err.println("[TableEatOptimizerUI] failed to save priority selection: " + e.getMessage());
            }
        }
    }

    private static class OptimizeButton extends Button
    {
        private final PriorityPicker picker;

        OptimizeButton(PriorityPicker picker)
        {
            super(UI.scale(100), "Optimize Eating");
            this.picker = picker;
        }

        @Override
        public void click()
        {
            Window wnd = getparent(Window.class);
            if (wnd == null) return;
            Inventory food = findFoodInventory(wnd);
            if (!(food instanceof NInventory))
            {
                NUtils.getGameUI().error("Optimize Eating: could not find the table's food grid.");
                return;
            }
            BotExecutor.runAsync("Table Eat Optimizer",
                new OptimizeTableEating((NInventory) food, picker.selected()));
        }
    }
}
