package nurgling.actions;

import haven.*;
import nurgling.*;

import nurgling.areas.NContext;
import nurgling.conf.NFishingSettings;
import nurgling.tasks.NTask;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

public class RepairFishingRot implements Action {

    NFishingSettings prop;
    Pair<Coord2d, Coord2d> repArea;
    Pair<Coord2d, Coord2d> baitArea;
    NContext context;


    public RepairFishingRot(NContext context, NFishingSettings prop, Pair<Coord2d, Coord2d> repArea, Pair<Coord2d, Coord2d> bait) {
        this.prop = prop;
        this.repArea = repArea;
        this.baitArea = bait;
        this.context = context;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        WItem rod = NUtils.getEquipment().findItem(prop.tool);
        if(rod == null) {
            return Results.ERROR("No fishing rod");
        }

        if(!((NGItem)rod.item).findContent(prop.fishline)) {
            Results repRes = repItem(gui, rod,  prop.fishline, 1, repArea);
            if (!repRes.IsSuccess()) return repRes;
        }

        if(!((NGItem)rod.item).findContent(prop.hook)) {
            Results repRes = repItem(gui, rod,  prop.hook, 1, repArea);
            if (!repRes.IsSuccess()) return repRes;
        }

        if(!((NGItem)rod.item).findContent(prop.bait)) {
            Results repRes = repItem(gui, rod,  prop.bait, prop.tool.endsWith("Primitive Casting-Rod")?1:5, baitArea);
            if (!repRes.IsSuccess()) return repRes;
        }
        return Results.SUCCESS();
    }

    private Results repItem(NGameUI gui, WItem rod, String item, int count, Pair<Coord2d, Coord2d> area) throws InterruptedException {
        WItem fl = NUtils.getGameUI().getInventory().getItem(item);
        if(fl == null) {
            if(area!=null) {
                takeFromArea(gui, item, count, area);
            }
            fl = NUtils.getGameUI().getInventory().getItem(item);
        }
        if(fl == null) {
            return Results.ERROR("No " + item);
        }
        NUtils.takeItemToHand(fl);
        NWItem itemHand = (NWItem) NUtils.getGameUI().vhand;
        NUtils.itemact(rod);
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                return ((NWItem) NUtils.getGameUI().vhand !=itemHand || ((NGItem)rod.item).findContent(item)) ;
            }
        });
        if((NWItem) NUtils.getGameUI().vhand!=null) {
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    return NUtils.getGameUI().vhand.item.spr!=null;
                }
            });
            NUtils.dropToInv();
        }
        return Results.SUCCESS();
    }

    /**
     * Scans the given rectangle directly for containers/stockpiles holding {@code item},
     * bypassing NContext.getInStorages() (which only resolves items registered via
     * addInItem/inAreas - never true for these ad-hoc "select area with:" prompts).
     */
    private void takeFromArea(NGameUI gui, String item, int count, Pair<Coord2d, Coord2d> area) throws InterruptedException {
        NAlias containerNames = new NAlias(new ArrayList<>(NContext.contcaps.keySet()), new ArrayList<>());
        for (Gob g : Finder.findGobs(area, containerNames)) {
            if (haveEnough(item, count)) return;
            if (g.ngob.isContainerEmpty()) continue;
            Container cont = new Container(g, NContext.contcaps.get(g.ngob.name), null);
            new PathFinder(g).run(gui);
            new OpenTargetContainer(cont).run(gui);
            TakeItemsFromContainer tifc = new TakeItemsFromContainer(cont, new HashSet<>(Arrays.asList(item)), null);
            tifc.minSize = count - NUtils.getGameUI().getInventory().getItems(new NAlias(item)).size();
            tifc.run(gui);
            new CloseTargetContainer(cont).run(gui);
        }
        if (haveEnough(item, count)) return;
        for (Gob g : Finder.findGobs(area, new NAlias("stockpile"))) {
            if (haveEnough(item, count)) return;
            new PathFinder(g).run(gui);
            new OpenTargetContainer("Stockpile", g).run(gui);
            if (NUtils.getGameUI().getStockpile() != null) {
                new TakeItemsFromPile(g, NUtils.getGameUI().getStockpile(), count - NUtils.getGameUI().getInventory().getItems(new NAlias(item)).size()).run(gui);
            }
            new CloseTargetWindow(NUtils.getGameUI().getWindow("Stockpile")).run(gui);
        }
    }

    private boolean haveEnough(String item, int count) throws InterruptedException {
        return NUtils.getGameUI().getInventory().getItems(new NAlias(item)).size() >= count;
    }
}
