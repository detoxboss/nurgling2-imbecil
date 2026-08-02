package nurgling.actions;

import haven.Glob;
import haven.GItem;
import haven.ItemInfo;
import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.NWItem;
import nurgling.iteminfo.NFoodInfo;
import nurgling.iteminfo.NFoodOptimizerSupport;
import nurgling.tasks.FepStateSettled;
import nurgling.tools.NFoodOptimizerLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Eats through a table's food grid in an order that maximizes total attribute gain,
 * exploiting the FEP variety bonus and (optionally) biasing toward up to 3 priority
 * attributes. See {@code docs/feps-system-reference.md} for the underlying mechanics
 * this relies on ({@link NFoodInfo}'s live expected/needed-FEP computation and
 * {@code NCharacterInfo.varity}'s variety-credit tracking).
 * <p>
 * Deliberately self-contained: it only reads existing public APIs (plus
 * {@link NFoodOptimizerSupport}, itself an isolated bridge) and touches no other file's
 * internals, so it can be dropped or removed without any ripple effect.
 */
public class OptimizeTableEating implements Action
{
    public static final List<String> ATTR_CODES =
        Collections.unmodifiableList(Arrays.asList("str", "agi", "int", "con", "prc", "csm", "dex", "wil", "psy"));

    private static final double PURITY_WEIGHT = 3.0;
    private static final double VARIETY_WEIGHT = 1.5;
    private static final double OVERFILL_WEIGHT = 1.0;

    private final NInventory foodInv;
    private final List<String> priority;

    /**
     * @param foodInv  the table's food-grid inventory (not the tableware grid).
     * @param priority 0-3 attribute codes from {@link #ATTR_CODES} to bias toward;
     *                 empty/null means "balanced mode" (favor the currently-lowest
     *                 base attribute).
     */
    public OptimizeTableEating(NInventory foodInv, List<String> priority)
    {
        this.foodInv = foodInv;
        this.priority = (priority == null) ? Collections.emptyList() : priority;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        try (NFoodOptimizerLog log = new NFoodOptimizerLog())
        {
            log.log("Session start. priority=%s", priority);
            log.log("Attributes before: %s", readAttrs(gui));

            Set<WItem> failed = new HashSet<>();
            int eaten = 0, skipped = 0;

            while (true)
            {
                ArrayList<WItem> items = foodInv.getItems();
                items.removeAll(failed);

                List<String> targetCodes = priority.isEmpty()
                    ? Collections.singletonList(lowestAttr(gui))
                    : priority;

                Candidate best = pickBest(gui, items, targetCodes);
                if (best == null)
                {
                    if (!priority.isEmpty())
                        log.log("No remaining table food contributes to priority stats %s. Stopping (%d eaten, %d skipped).", priority, eaten, skipped);
                    else
                        log.log("Table food grid empty or unscoreable. Stopping (%d eaten, %d skipped).", eaten, skipped);
                    break;
                }

                double oldSum = FepStateSettled.currentSum();
                Map<String, Integer> before = readAttrs(gui);

                log.log("Eating '%s' (score=%.3f, expectedFep=%.2f, neededFep=%.2f, newVariety=%s, breakdown=%s)",
                    best.name, best.score, best.expectedFep, best.neededFep, best.newVariety, best.breakdown);

                Results r = new SelectFlowerAction("Eat", (NWItem) best.witem).run(gui);
                if (!r.IsSuccess())
                {
                    log.log("Eat action failed for '%s': %s -- excluding it from further attempts this session.", best.name, r.msg);
                    failed.add(best.witem);
                    skipped++;
                    continue;
                }

                try
                {
                    NUtils.getUI().core.addTask(new FepStateSettled(oldSum));
                }
                catch (InterruptedException e)
                {
                    log.log("Timed out waiting for FEP state to update after eating '%s' (satiation-capped or no effect?). Excluding it from further attempts.", best.name);
                    failed.add(best.witem);
                    skipped++;
                    continue;
                }

                eaten++;
                Map<String, Integer> after = readAttrs(gui);
                logAttrChanges(log, before, after);
            }

            log.log("Attributes after: %s", readAttrs(gui));
            log.log("Session end. eaten=%d skipped=%d", eaten, skipped);
            return eaten > 0 ? Results.SUCCESS() : Results.FAIL();
        }
    }

    private static class Candidate
    {
        final WItem witem;
        final String name;
        final double expectedFep;
        final double neededFep;
        final boolean newVariety;
        final Map<String, Double> breakdown;
        final double score;

        Candidate(WItem witem, String name, double expectedFep, double neededFep,
                  boolean newVariety, Map<String, Double> breakdown, double score)
        {
            this.witem = witem;
            this.name = name;
            this.expectedFep = expectedFep;
            this.neededFep = neededFep;
            this.newVariety = newVariety;
            this.breakdown = breakdown;
            this.score = score;
        }
    }

    private Candidate pickBest(NGameUI gui, List<WItem> items, List<String> targetCodes)
    {
        Candidate best = null;
        for (WItem witem : items)
        {
            GItem gitem = witem.item;
            NFoodInfo info = ItemInfo.find(NFoodInfo.class, gitem.info());
            if (info == null)
                continue; // not food (e.g. an empty plate sitting in the grid)

            String name = ((NGItem) gitem).name();
            if (name == null)
                continue;

            double expectedFep = NFoodOptimizerSupport.expectedFep(info);
            double neededFep = NFoodOptimizerSupport.neededFepForBar(info);
            boolean newVariety = NFoodOptimizerSupport.isNewVarietyForBar(info);
            Map<String, Double> breakdown = NFoodOptimizerSupport.attrFepBreakdown(info);

            double total = breakdown.values().stream().mapToDouble(Double::doubleValue).sum();
            double purity = 0;
            if (total > 0)
            {
                double targeted = 0;
                for (String code : targetCodes)
                    targeted += breakdown.getOrDefault(code, 0.0);
                purity = targeted / total;
            }

            // In priority mode, food that contributes nothing at all to any priority
            // stat is off-target -- exclude it rather than waste it on the wrong stat.
            // In balanced mode, still consider it (just scored low) so the whole table
            // gets used rather than leaving food behind while chasing one stat forever.
            if (!priority.isEmpty() && purity <= 0)
                continue;

            double overfillPenalty = 0;
            if (expectedFep > 0 && expectedFep > neededFep && neededFep > 0)
                overfillPenalty = (expectedFep - neededFep) / expectedFep;

            double score = PURITY_WEIGHT * purity
                + VARIETY_WEIGHT * (newVariety ? 1.0 : 0.0)
                - OVERFILL_WEIGHT * overfillPenalty;

            Candidate cand = new Candidate(witem, name, expectedFep, neededFep, newVariety, breakdown, score);
            if (best == null || cand.score > best.score)
                best = cand;
        }
        return best;
    }

    private static Map<String, Integer> readAttrs(NGameUI gui)
    {
        Map<String, Integer> result = new LinkedHashMap<>();
        Glob glob = (gui.ui != null && gui.ui.sess != null) ? gui.ui.sess.glob : null;
        if (glob == null)
            return result;
        for (String code : ATTR_CODES)
        {
            Glob.CAttr a = glob.getcattr(code);
            if (a != null)
                result.put(code, a.base);
        }
        return result;
    }

    private static String lowestAttr(NGameUI gui)
    {
        Map<String, Integer> attrs = readAttrs(gui);
        String lowest = ATTR_CODES.get(0);
        int lowestVal = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> e : attrs.entrySet())
        {
            if (e.getValue() < lowestVal)
            {
                lowestVal = e.getValue();
                lowest = e.getKey();
            }
        }
        return lowest;
    }

    private static void logAttrChanges(NFoodOptimizerLog log, Map<String, Integer> before, Map<String, Integer> after)
    {
        for (String code : ATTR_CODES)
        {
            Integer b = before.get(code), a = after.get(code);
            if (b != null && a != null && !b.equals(a))
                log.log("LEVEL UP: %s %d -> %d", code, b, a);
        }
    }
}
