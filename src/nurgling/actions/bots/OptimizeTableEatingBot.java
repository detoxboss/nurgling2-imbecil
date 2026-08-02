package nurgling.actions.bots;

import haven.Inventory;
import haven.Widget;
import haven.Window;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.actions.Action;
import nurgling.actions.OptimizeTableEating;
import nurgling.actions.Results;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bot-menu / scenario / headless entry point for {@link OptimizeTableEating}. Requires
 * a table window (caption "Table", with a live "Feast!" button) to already be open --
 * this bot does not path to or open one itself, it only locates and acts on whichever
 * one is currently open, same as the table-widget button does.
 */
public class OptimizeTableEatingBot implements Action
{
    private final List<String> priority;

    public OptimizeTableEatingBot()
    {
        this.priority = new ArrayList<>();
    }

    public OptimizeTableEatingBot(Map<String, Object> settings)
    {
        this.priority = parsePriority(settings);
    }

    @SuppressWarnings("unchecked")
    private static List<String> parsePriority(Map<String, Object> settings)
    {
        List<String> result = new ArrayList<>();
        if (settings == null) return result;
        Object raw = settings.get("priority");
        if (raw instanceof List)
        {
            for (Object o : (List<Object>) raw)
                if (o != null) result.add(o.toString());
        }
        else if (raw instanceof String && !((String) raw).isBlank())
        {
            for (String s : ((String) raw).split(","))
                result.add(s.trim());
        }
        return result;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        Window wnd = gui.getWindowWithButton("Table", "Feast!");
        if (wnd == null)
            return Results.ERROR("No open table window with an active Feast! button found.");

        Inventory food = findFoodInventory(wnd);
        if (!(food instanceof NInventory))
            return Results.ERROR("Could not find the table's food grid.");

        return new OptimizeTableEating((NInventory) food, priority).run(gui);
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
}
