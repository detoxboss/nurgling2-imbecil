package nurgling.iteminfo;

import haven.*;
import nurgling.NGItem;
import nurgling.i18n.L10n;
import nurgling.iteminfo.food.FoodModifiers;
import nurgling.styles.TooltipStyle;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The extra rows of the food tooltip: weighted totals, predicted FEP per hunger, the FEP bar after
 * eating, and the breakdown of modifiers behind the prediction. Holding Shift swaps all of it for the
 * unmodified numbers.
 * <p>
 * Kept out of {@link NFoodInfo} (which stays close to the upstream file) and driven entirely by a
 * {@link FoodModifiers}, so the numbers shown here are the ones the tests check.
 */
final class FoodDetailsLayout {
    private FoodDetailsLayout() {}

    private static final Color UNMODIFIED = new Color(218, 163, 0);
    private static final Color FILL_SHORT = new Color(252, 186, 3);

    /** Whether the item's own UI has Shift held; false for recipes and other ownerless info. */
    static boolean shiftHeld(ItemInfo.Owner owner) {
        return (owner instanceof NGItem) && ((NGItem)owner).ui != null && ((NGItem)owner).ui.modshift;
    }

    /** Appends text-only rows below what the layout already holds. */
    private static final class Rows {
        final ItemInfo.Layout l;
        final int firstGap, lineGap;
        boolean any = false;

        Rows(ItemInfo.Layout l, int firstGap, int lineGap) {
            this.l = l;
            this.firstGap = firstGap;
            this.lineGap = lineGap;
        }

        void add(BufferedImage row) {
            l.cmp.add(TooltipStyle.cropTopOnly(row), Coord.of(0, l.cmp.sz.y + (any ? lineGap : firstGap)));
            any = true;
        }

        void add(BufferedImage... parts) {
            add(ItemInfo.catimgsh(0, parts));
        }
    }

    /**
     * @param firstGap gap above the first row (the section gap, already reduced by the font descent and
     *                 by whatever the previous row left below its text)
     * @param lineGap  gap between rows of this block, likewise reduced
     * @param current  current FEP in the bar, NaN if unknown
     * @param target   FEP at which the bar completes after eating this food (the cap less the variety
     *                 bonus it would add), NaN if unknown
     * @param cap      FEP bar cap, NaN if unknown; only used to say whether the target is below it
     * @return whether any row was added
     */
    static boolean layout(NFoodInfo food, ItemInfo.Layout l, FoodModifiers mods, boolean unmodified,
                          int firstGap, int lineGap, double current, double target, double cap) {
        Rows rows = new Rows(l, firstGap, lineGap);
        final double weighted = food.weightedTotal();
        if(unmodified) {
            rows.add(NFoodInfo.labelColored(L10n.get("food.details.unmodified"), UNMODIFIED));
            if(weighted > 0)
                rows.add(weightedRow(weighted, food.glut * 100.0));
            return rows.any;
        }
        if(!mods.available()) {
            /* never leave a gap that reads as "no modifiers": say why there is no prediction */
            if(mods.status() == FoodModifiers.Status.PENDING)
                rows.add(NFoodInfo.labelColored(L10n.get("food.details.pending"), UNMODIFIED));
            else if(mods.status() == FoodModifiers.Status.UNSUPPORTED)
                rows.add(NFoodInfo.labelColored(L10n.get("food.details.unsupported"), TooltipStyle.COLOR_NEGATIVE_STAT));
            return rows.any;
        }

        if(mods.deepWorm())
            rows.add(NFoodInfo.labelColored(L10n.get("food.details.deepworm"), TooltipStyle.COLOR_NEGATIVE_STAT));

        double hungerPct = mods.predictedHunger(food.glut * 100.0);
        if(weighted > 0)
            rows.add(weightedRow(mods.predictedFep(weighted), hungerPct));

        double predicted = mods.predictedFep(food.fepSum);
        rows.add(NFoodInfo.label(L10n.get("food.details.predicted_per_hunger") + " "),
                 NFoodInfo.value(fmt(FoodModifiers.perHunger(predicted, hungerPct)), TooltipStyle.COLOR_FOOD_FEP_HUNGER));

        /* measured against where the bar really completes, not its cap: the variety bonus lowers that
           point, so 100% here is exactly when the "Expected FEP" row shows a non-negative balance */
        double fill = FoodModifiers.barFillPercent(current, predicted, target);
        if(!Double.isNaN(fill)) {
            boolean adjusted = !Double.isNaN(cap) && target < cap - 0.005;
            BufferedImage head = NFoodInfo.label(L10n.get("food.details.bar_after") + " ");
            BufferedImage val = NFoodInfo.value(Utils.odformat2(fill, 2) + "%", (fill >= 100) ? TooltipStyle.COLOR_FOOD_FEP_SUM : FILL_SHORT);
            if(adjusted)
                rows.add(head, val, NFoodInfo.labelColored(" " + L10n.get("food.details.bar_variety"), TooltipStyle.COLOR_PERCENTAGE));
            else
                rows.add(head, val);
        }

        List<FoodModifiers.Factor> factors = mods.factors();
        if(!factors.isEmpty()) {
            rows.add(NFoodInfo.label(L10n.get("food.details.modifiers") + " "),
                     NFoodInfo.value("×" + Utils.odformat2(mods.fepMultiplier(), 2), TooltipStyle.COLOR_FOOD_FEP_SUM),
                     NFoodInfo.labelColored(" " + L10n.get("food.details.fep"), TooltipStyle.COLOR_PERCENTAGE));
            for(FoodModifiers.Factor f : factors)
                rows.add(NFoodInfo.labelColored(factorText(f), TooltipStyle.COLOR_PERCENTAGE));
        }
        rows.add(NFoodInfo.labelColored(L10n.get("food.details.hint_shift"), TooltipStyle.COLOR_RESOURCE_PATH));
        return rows.any;
    }

    private static BufferedImage weightedRow(double weightedFep, double hungerPct) {
        return ItemInfo.catimgsh(0,
            NFoodInfo.label(L10n.get("food.details.weighted_total") + " "),
            NFoodInfo.value(fmt(weightedFep), TooltipStyle.COLOR_FOOD_FEP_SUM),
            NFoodInfo.label("  " + L10n.get("food.details.per_hunger") + " "),
            NFoodInfo.value(fmt(FoodModifiers.perHunger(weightedFep, hungerPct)), TooltipStyle.COLOR_FOOD_FEP_HUNGER));
    }

    static String factorText(FoodModifiers.Factor f) {
        String name;
        switch(f.kind) {
        case ACCOUNT: name = L10n.get("food.details.factor.account"); break;
        case REALM: name = L10n.get("food.details.factor.realm"); break;
        case FOOD_EFFICACY: name = L10n.get("food.details.factor.efficacy"); break;
        case TABLE_FEP: name = L10n.get("food.details.factor.table_fep"); break;
        case SATIATION: name = L10n.get("food.details.factor.satiation"); break;
        case SALT: name = L10n.get("food.details.factor.salt"); break;
        default: name = L10n.get("food.details.factor.table_hunger"); break;
        }
        return "  " + (f.additive ? ("+" + Utils.odformat2(f.value, 2)) : ("×" + Utils.odformat2(f.value, 2))) + "  " + name;
    }

    static String fmt(double v) {
        return (Double.isNaN(v) || Double.isInfinite(v)) ? "-" : Utils.odformat2(v, 2);
    }
}
