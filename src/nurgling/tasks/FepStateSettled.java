package nurgling.tasks;

import haven.BAttrWnd;
import nurgling.NUtils;

/**
 * Waits for the character's FEP pool total to change after an eat action (either a
 * new event amount was added, or the bar filled and reset to 0). Times out rather than
 * blocking forever, so a food that silently fails to register (e.g. satiation-capped)
 * doesn't hang the caller.
 */
public class FepStateSettled extends NTask
{
    private final double oldSum;

    public FepStateSettled(double oldSum)
    {
        this.oldSum = oldSum;
        infinite = false;
        maxCounter = 60;
    }

    @Override
    public boolean check()
    {
        BAttrWnd.FoodMeter fm = currentMeter();
        if (fm == null)
            return false;
        return Math.abs(sumOf(fm) - oldSum) > 0.005;
    }

    private static BAttrWnd.FoodMeter currentMeter()
    {
        if (NUtils.getGameUI() == null || NUtils.getGameUI().chrwdg == null || NUtils.getGameUI().chrwdg.battr == null)
            return null;
        return NUtils.getGameUI().chrwdg.battr.feps;
    }

    private static double sumOf(BAttrWnd.FoodMeter fm)
    {
        double sum = 0;
        for (BAttrWnd.FoodMeter.El el : fm.els)
            sum += el.a;
        return sum;
    }

    public static double currentSum()
    {
        BAttrWnd.FoodMeter fm = currentMeter();
        return (fm == null) ? 0 : sumOf(fm);
    }
}
