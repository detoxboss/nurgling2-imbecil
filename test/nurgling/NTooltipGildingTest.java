package nurgling;

import haven.ItemInfo;
import haven.res.ui.tt.attrmod.AttrMod;
import haven.res.ui.tt.attrmod.Entry;
import haven.res.ui.tt.attrmod.Mod;
import nurgling.iteminfo.bonus.AttrBonus;
import nurgling.iteminfo.bonus.GildingSummary;
import nurgling.iteminfo.bonus.fakes.Fakes;
import nurgling.iteminfo.bonus.fakes.slot.Slotted;
import nurgling.iteminfo.bonus.fakes.slots_alt.ISlots;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gilding tooltip views, rendered headlessly through the real {@link NTooltip#build} (using the
 * alternative-ISlots reflection path, since the real gilding classes need a running game).
 */
class NTooltipGildingTest {
    /** haven.Text takes its fonts from the config in a static initializer, so the defaults must exist first. */
    @BeforeAll
    static void defaultConfig() {
        NConfig.getGlobalInstance();
        try {
            Class.forName("haven.Text");
        } catch(ClassNotFoundException | LinkageError e) {
            Assumptions.abort("game resource jars (lib/ext/builtin-res.jar) are not on the test classpath: " + e);
        }
    }

    private static final Fakes.intattr STR = new Fakes.intattr("Strength");
    private static final Fakes.intattr MELEE = new Fakes.intattr("Melee Combat");
    private static final Fakes.intattr AGI = new Fakes.intattr("Agility");

    private static AttrMod mods(Object... attrAndValue) {
        List<Entry> tab = new ArrayList<>();
        for(int i = 0; i < attrAndValue.length; i += 2)
            tab.add(new Mod((haven.res.ui.tt.attrmod.Attribute)attrAndValue[i], ((Number)attrAndValue[i + 1]).doubleValue()));
        return new AttrMod(null, tab);
    }

    private static ISlots.SItem gilding(String name, AttrMod mod) {
        return new ISlots.SItem(name, Collections.<ItemInfo>singletonList(mod));
    }

    private static ISlots threeGildings() {
        ISlots slots = new ISlots(4, 3);
        slots.s.add(gilding("Silver", mods(STR, 5, MELEE, 3)));
        slots.s.add(gilding("Gold", mods(STR, 4, AGI, 2)));
        slots.s.add(gilding("Amber", mods(MELEE, 1, AGI, 1)));
        return slots;
    }

    private static List<ItemInfo> item(ItemInfo... tips) {
        List<ItemInfo> info = new ArrayList<>();
        info.add(new ItemInfo.Name(null, "Test Helmet"));
        info.addAll(Arrays.asList(tips));
        return info;
    }

    @Test
    void compactSummaryCombinesEveryGildingExactlyOnce() {
        List<AttrBonus> sum = GildingSummary.of(item(threeGildings()));
        assertEquals(3, sum.size());
        assertEquals(9.0, find(sum, "Strength").value);
        assertEquals(4.0, find(sum, "Melee Combat").value);
        assertEquals(3.0, find(sum, "Agility").value);
    }

    @Test
    void summaryEqualsTheSumOfTheBreakdown() {
        ISlots slots = threeGildings();
        double breakdown = 0;
        for(nurgling.iteminfo.bonus.AttrBonusCollector.InstalledGilding g : nurgling.iteminfo.bonus.AttrBonusCollector.installedGildings(item(slots))) {
            for(AttrBonus b : g.bonuses) {
                if(b.name.equals("Strength"))
                    breakdown += b.value;
            }
        }
        assertEquals(breakdown, find(GildingSummary.of(item(slots)), "Strength").value);
    }

    @Test
    void summaryIgnoresTheItemsOwnStatsAndGildingRecipes() {
        List<ItemInfo> info = item(mods(STR, 100), new Slotted(Collections.<ItemInfo>singletonList(mods(AGI, 50))), threeGildings());
        List<AttrBonus> sum = GildingSummary.of(info);
        assertEquals(9.0, find(sum, "Strength").value, "the item's own +100 is not a gilding");
        assertEquals(3.0, find(sum, "Agility").value, "a recipe's +50 is not installed");
    }

    @Test
    void ungildedItemHasNoSummary() {
        assertTrue(GildingSummary.of(item(new ISlots(3, 0))).isEmpty());
        assertTrue(GildingSummary.of(item(mods(STR, 5))).isEmpty());
    }

    @Test
    void shiftExpandsTheTooltipAndReleasingRestoresTheCompactOne() {
        List<ItemInfo> info = item(threeGildings());
        BufferedImage compact = NTooltip.build(info, false);
        BufferedImage expanded = NTooltip.build(info, true);
        BufferedImage compactAgain = NTooltip.build(info, false);
        assertNotNull(compact);
        assertNotNull(expanded);
        assertTrue(expanded.getHeight() > compact.getHeight(),
            "per-gilding breakdown (" + expanded.getHeight() + "px) should be taller than the summary (" + compact.getHeight() + "px)");
        assertEquals(compact.getHeight(), compactAgain.getHeight());
        assertEquals(compact.getWidth(), compactAgain.getWidth());
    }

    @Test
    void ungildedEquipmentRendersTheSameInBothViews() {
        List<ItemInfo> info = item(new ISlots(3, 0));
        BufferedImage compact = NTooltip.build(info, false);
        BufferedImage expanded = NTooltip.build(info, true);
        assertEquals(expanded.getHeight(), compact.getHeight());
        assertEquals(expanded.getWidth(), compact.getWidth());
    }

    @Test
    void gildingsWithNothingToSummariseStillShowTheBreakdown() {
        /* gildings whose tips carry no attribute modifiers: compact must not hide them */
        ISlots slots = new ISlots(2, 2);
        slots.s.add(new ISlots.SItem("Plain", Collections.<ItemInfo>emptyList()));
        slots.s.add(new ISlots.SItem("Plainer", Collections.<ItemInfo>emptyList()));
        List<ItemInfo> info = item(slots);
        assertEquals(NTooltip.build(info, true).getHeight(), NTooltip.build(info, false).getHeight());
    }

    private static AttrBonus find(List<AttrBonus> list, String name) {
        for(AttrBonus b : list) {
            if(b.name.equals(name))
                return b;
        }
        return null;
    }
}
