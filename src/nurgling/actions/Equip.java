package nurgling.actions;

import haven.Coord;
import haven.Inventory;
import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.tasks.WaitItemInHand;
import nurgling.tasks.WaitItemInEquip;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.widgets.NEquipory;

import java.util.HashSet;

public class Equip implements Action {

    NAlias target_name;
    NAlias exception = null;

    public Equip(NAlias target_name) {
        this.target_name = target_name;
    }

    public Equip(NAlias target_name, NAlias exception) {
        this.target_name = target_name;
        this.exception = exception;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        if(target_name.keys.contains("Traveller's Sack")) {
            target_name.keys.add("Traveler's Sack");
            target_name.buildCaches(); // Rebuild caches after modifying keys
        } else if (target_name.keys.contains("Traveler's Sack")) {
            target_name.keys.add("Traveller's Sack");
            target_name.buildCaches(); // Rebuild caches after modifying keys
        }
        WItem lhand = NUtils.getEquipment().findItem (NEquipory.Slots.HAND_LEFT.idx);

        WItem rhand = NUtils.getEquipment().findItem (NEquipory.Slots.HAND_RIGHT.idx);
        if((lhand!=null && NParser.checkName(((NGItem)lhand.item).name(), target_name) || (rhand!=null && NParser.checkName(((NGItem)rhand.item).name(),target_name))))
        {
            return Results.SUCCESS();
        }
        WItem wbelt = NUtils.getEquipment().findItem (NEquipory.Slots.BELT.idx);
        NInventory belt = (wbelt != null && wbelt.item.contents instanceof NInventory) ? (NInventory) wbelt.item.contents : null;
        WItem witem = belt != null ? belt.getItem(target_name) : null;
        // Tool wasn't on the belt - fall back to the main inventory instead of failing outright.
        // A player's axe/saw can live in either place (belt has limited slots), and the old code
        // only ever looked at the belt, silently failing every Equip call for a tool that was
        // sitting in plain inventory (confirmed root cause of LP Assistant never swapping to the
        // axe/saw for board/block/stone actions when the tool wasn't belted).
        NInventory container = belt;
        if (witem == null) {
            NInventory inv = gui.getInventory();
            witem = inv.getItem(target_name);
            container = inv;
        }
        if (container != null) {
            if (witem != null) {
                    // A bucket carrying liquid can't be put into a container inventory slot at all
                    // - the server rejects it ("The bucket must be carried when not empty.") and
                    // silently ignores the drop request instead of erroring. Confirmed live 2026-08:
                    // the swap logic below used to try exactly that (take the occupying hand's item,
                    // then drop it into the belt/inventory slot the target tool currently sits in)
                    // whenever both hands were occupied, which for a non-empty bucket just hangs the
                    // caller's DropOn wait until it times out. A non-empty bucket must stay in hand,
                    // so it's excluded from the swap candidates the same way the "exception" alias
                    // already is, and equipping fails cleanly instead of hanging when there's no
                    // other hand available to free.
                    boolean lhandBucket = isNonEmptyBucket(lhand);
                    boolean rhandBucket = isNonEmptyBucket(rhand);

                    if (isTwoHanded(witem) && (lhandBucket || rhandBucket)) {
                        return Results.ERROR("Cannot equip " + witemName(witem)
                                + ": it needs both hands, but a non-empty bucket must stay in hand");
                    }

                    if (isTwoHanded(witem) && ((lhand != null && rhand != null && lhand != rhand && !isTwoHanded(lhand)))) {
                        NUtils.takeItemToHand(rhand);
                        if (container.getFreeSpace() == 0) {
                            WItem item = NUtils.getGameUI().vhand;
                            Coord pos = NUtils.getGameUI().getInventory().getFreeCoord(item);
                            gui.getInventory().dropOn(pos, ((NGItem) item.item).name());
                        } else if (container == belt) {
                            NUtils.transferToBelt();
                        } else {
                            gui.getInventory().dropOn(container.getFreeCoord(NUtils.getGameUI().vhand));
                        }

                        NUtils.takeItemToHand(lhand);
                        container.dropOn(witem.c.div(Inventory.sqsz));
                        NUtils.getUI().core.addTask(new WaitItemInHand(witem));
                        NUtils.getEquipment().wdgmsg("drop", -1);
                    } else {
                        if ((rhand == null && lhand == null) || (!isTwoHanded(witem) && (rhand == null || lhand == null))) {
                            NUtils.takeItemToHand(witem);
                            NUtils.getEquipment().wdgmsg("drop", -1);
                        } else {
                            boolean lhandKeep = lhandBucket
                                    || (lhand != null && NParser.checkName(((NGItem) lhand.item).name(), exception));

                            if (lhand != null && !lhandKeep) {
                                NUtils.takeItemToHand(lhand);
                                container.dropOn(witem.c.div(Inventory.sqsz));
                                NUtils.getUI().core.addTask(new WaitItemInHand(witem));
                                NUtils.getEquipment().wdgmsg("drop", -1);

                            }
                            else if (rhand != null && !rhandBucket)
                            {
                                NUtils.takeItemToHand(rhand);
                                container.dropOn(witem.c.div(Inventory.sqsz));
                                NUtils.getUI().core.addTask(new WaitItemInHand(witem));
                                NUtils.getEquipment().wdgmsg("drop", -1);
                            }
                            else
                            {
                                return Results.ERROR("Cannot equip " + witemName(witem)
                                        + ": both hands hold protected items (non-empty bucket)");
                            }
                        }
                    }
                    NUtils.getUI().core.addTask(new WaitItemInEquip(witem,new NEquipory.Slots[]{NEquipory.Slots.HAND_LEFT, NEquipory.Slots.HAND_RIGHT}));
            }
            else {
                    return Results.ERROR("No target item");
            }

        }

        return Results.SUCCESS();
    }

    /** True if this hand holds a Bucket that currently has liquid in it - see run()'s own doc. */
    private boolean isNonEmptyBucket(WItem item) {
        if (item == null || !(item.item instanceof NGItem))
            return false;
        NGItem ngItem = (NGItem) item.item;
        String name = ngItem.name();
        return name != null && NParser.checkName(name, "Bucket") && !ngItem.content().isEmpty();
    }

    private String witemName(WItem item) {
        String name = ((NGItem) item.item).name();
        return name != null ? name : "tool";
    }

    boolean isTwoHanded(WItem item)
    {
        HashSet<String> items = new HashSet<>();
        items.add("Scythe");
        items.add("Pickaxe");
        items.add("Glass Blowing Rod");
        items.add("Boar Spear");
        items.add("Metal Shovel");
        items.add("Tinker's Shovel");
        items.add("Wooden Shovel");
        items.add("Dowsing Rod");
        items.add("Battle Axe of the Twelfth Bay");
        items.add("Cutblade");
        return items.contains(((NGItem)item.item).name());
    }
}
