package nurgling.tasks;

import haven.GItem;
import haven.WItem;
import haven.Widget;
import nurgling.NGItem;
import nurgling.NISBox;
import nurgling.NInventory;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.util.ArrayList;

public class WaitNoItems extends NTask
{
    NAlias name = null;
    Widget inventory;

    GItem target = null;
    // -1 = wait forever (original behavior, unchanged for existing callers). A positive value
    // makes check() give up and report a timeout instead of blocking indefinitely once the
    // deadline passes - same wall-clock-deadline-inside-check() pattern as
    // WaitLpProductDiscovered, not NTask's counter/criticalExit mechanism, so a timeout here is
    // reported via timedOut() for the caller to react to, never a synthetic InterruptedException.
    private final long deadline;
    private boolean timedOut = false;

    public WaitNoItems(NInventory inventory, NAlias name)
    {
        this.name = name;
        this.inventory = inventory;
        this.deadline = -1;
    }

    public WaitNoItems(NInventory inventory, NAlias name, long timeoutMs)
    {
        this.name = name;
        this.inventory = inventory;
        this.deadline = System.currentTimeMillis() + timeoutMs;
    }


    @Override
    public boolean check()
    {

        if (target != null)
            if (((NGItem) target).name() != null)
                name = new NAlias(((NGItem) target).name());
            else
                return checkDeadline();

        if(inventory instanceof NInventory)
        {
            result.clear();

            for (Widget widget = inventory.child; widget != null; widget = widget.next)
            {
                if (widget instanceof WItem)
                {
                    WItem item = (WItem) widget;
                    String item_name;
                    if ((item_name = ((NGItem) item.item).name()) == null)
                    {
                        return checkDeadline();
                    }
                    else
                    {
                        if (name == null || NParser.checkName(item_name, name))
                        {
                            result.add(item);
                        }
                    }
                }
            }
            if (result.isEmpty())
                return true;
            return checkDeadline();
        }
        return checkDeadline();
    }

    private boolean checkDeadline()
    {
        if (deadline >= 0 && System.currentTimeMillis() >= deadline)
        {
            timedOut = true;
            return true;
        }
        return false;
    }

    /** True if this wait gave up on its deadline instead of ever seeing the items disappear. */
    public boolean timedOut()
    {
        return timedOut;
    }

    private ArrayList<WItem> result = new ArrayList<>();

}
