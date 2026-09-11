package nurgling.actions;

import haven.Gob;
import haven.MenuGrid;
import nurgling.*;
import nurgling.tasks.*;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.util.concurrent.atomic.AtomicBoolean;

public class AutoDrink implements Action
{

    public final AtomicBoolean stop = new AtomicBoolean(false);

    // Stamina fraction (0.0-1.0) considered "full" -- avoids floating-point edge cases
    // around the meter's true 1.0 ceiling while still meaning "no further drinking needed".
    private static final double FULL_STAMINA = 0.99;

    // Latched once stamina drops to/below the configured threshold; stays set until stamina
    // recovers to FULL_STAMINA so a temporary rise above threshold mid-cycle doesn't abort it.
    private volatile boolean active = false;

    // Suppresses repeated no-water notifications within a single active cycle.
    private boolean noWaterNotified = false;

    public AutoDrink()
    {
        stop.set(false);
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        while(!stop.get())
        {
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    if(stop.get())
                        return true;
                    double stamina = NUtils.getStamina();
                    if(stamina < 0)
                        return false;
                    if(stamina >= FULL_STAMINA) {
                        active = false;
                        return false;
                    }
                    if(!active && stamina <= getThresholdFraction())
                        active = true;
                    return active;
                }
            });
            if(stop.get()) {
                return Results.SUCCESS();
            }

            if(hasDrinkableWater(gui)) {
                NUtils.getUI().dropLastError();
                if (gui.menu == null) {
                    // Menu not ready yet -- back off instead of spinning.
                    NUtils.addTask(cooldown());
                    continue;
                }
                MenuGrid.Pagina drinkPag = null;
                for (MenuGrid.Pagina pag : gui.menu.paginae) {
                    if (pag.button() != null && pag.button().name().equals("Drink")) {
                        drinkPag = pag;
                        break;
                    }
                }
                if (drinkPag == null) {
                    // No "Drink" button registered yet -- back off instead of spinning.
                    NUtils.addTask(cooldown());
                    continue;
                }
                drinkPag.button().use(new MenuGrid.Interaction(1, 0));
                Gob player = NUtils.player();
                if (player == null) {
                    NUtils.addTask(cooldown());
                    continue;
                }
                WaitPoseOrMsg wops = new WaitPoseOrMsg(player, "gfx/borka/drinkan", new NAlias("You have nothing on your hotbelt to drink."));
                NUtils.getUI().core.addTask(wops);
                if (wops.isError()) {
                    // Drink command failed despite DrinkMeter reporting water (stale read,
                    // container just emptied, etc). Back off instead of hammering the button.
                    reportAutoDrinkIssue(gui, "Auto-drink: drink attempt failed, backing off.");
                    NUtils.addTask(cooldown());
                    continue;
                }
                // Drink actually began -- clear the notification latch so a later, genuine
                // failure/no-water episode can notify again.
                noWaterNotified = false;
                NUtils.addTask(new NTask() {
                    @Override
                    public boolean check() {
                        Gob p = NUtils.player();
                        return p == null || !NParser.checkName(p.pose(), "gfx/borka/drinkan");
                    }
                });
            }
            else
            {
                reportAutoDrinkIssue(gui, "Auto-drink: no water available.");
                NUtils.addTask(cooldown());
            }
        }
        return Results.SUCCESS();
    }

    private void reportAutoDrinkIssue(NGameUI gui, String message) {
        if (!noWaterNotified && gui != null) {
            gui.error(message);
            noWaterNotified = true;
        }
    }

    private static NTask cooldown() {
        return new NTask() {
            int count = 0;
            @Override
            public boolean check() {
                return count++ > 60;
            }
        };
    }

    private static int getThresholdPercent() {
        Object v = NConfig.get(NConfig.Key.autoDrinkThreshold);
        int pct = (v instanceof Number) ? ((Number) v).intValue() : 75;
        if (pct < 1) pct = 1;
        if (pct > 100) pct = 100;
        return pct;
    }

    private static double getThresholdFraction() {
        return getThresholdPercent() / 100.0;
    }

    boolean hasDrinkableWater(NGameUI gui) {
        return gui != null && gui.drinkMeter != null && gui.drinkMeter.getWater() > 0;
    }
}
