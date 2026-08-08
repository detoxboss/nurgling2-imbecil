package nurgling.tasks;

import haven.*;
import nurgling.*;
import nurgling.tools.*;

public class DropOn extends NTask
{
    public DropOn(NInventory inventory, Coord coord, NAlias name)
    {
        this.coord = coord;
        this.inventory = inventory;
        this.name = name;
        if(name.keys.contains("Traveller's Sack")) {
            name.keys.add("Traveler's Sack");
            name.buildCaches(); // Rebuild caches after modifying keys
        } else if (name.keys.contains("Traveler's Sack")) {
            name.keys.add("Traveller's Sack");
            name.buildCaches(); // Rebuild caches after modifying keys
        }

        infinite = false;
    }

    public DropOn(NInventory inventory, Coord coord, String name)
    {
        this(inventory, coord, new NAlias(name));
    }

    Coord coord;
    NInventory inventory;

    NAlias name;

    @Override
    public boolean check()
    {
        // The counter/maxCounter timeout is already handled once per poll by NTask.baseCheck()
        // (which calls this check() only when its own counter hasn't yet tripped) - re-checking it
        // here as well double-counts against the same shared `counter` field, so a bounded DropOn
        // was actually timing out (and setting criticalExit, which NCore.addTask() turns into an
        // InterruptedException up the caller - see LpAssistantBot's own containment doc for why
        // that mattered) after ~maxCounter/2 polls instead of the intended maxCounter.
        return !inventory.isSlotFree(coord) && inventory.isItemInSlot(coord, name);
    }
}
