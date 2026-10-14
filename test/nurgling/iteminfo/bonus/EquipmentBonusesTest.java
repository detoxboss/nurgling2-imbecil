package nurgling.iteminfo.bonus;

import haven.ItemInfo;
import haven.Loading;
import haven.res.ui.tt.attrmod.AttrMod;
import haven.res.ui.tt.attrmod.Entry;
import haven.res.ui.tt.attrmod.Mod;
import nurgling.iteminfo.bonus.fakes.Fakes;
import nurgling.iteminfo.bonus.fakes.slot.Slotted;
import nurgling.iteminfo.bonus.fakes.slots.ISlots;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Equipment-wide bonus totals, run against real {@code AttrMod}/{@code Mod} tips with test
 * attribute classes (see {@link Fakes}); no game session needed.
 */
class EquipmentBonusesTest {
    private static final Fakes.intattr STR = new Fakes.intattr("Strength");
    private static final Fakes.intattr MELEE = new Fakes.intattr("Melee Combat");
    private static final Fakes.pmattr SPEED = new Fakes.pmattr("Speed");

    private static AttrMod mods(Object... attrAndValue) {
        List<Entry> tab = new ArrayList<>();
        for(int i = 0; i < attrAndValue.length; i += 2)
            tab.add(new Mod((haven.res.ui.tt.attrmod.Attribute)attrAndValue[i], ((Number)attrAndValue[i + 1]).doubleValue()));
        return new AttrMod(null, tab);
    }

    private static ISlots gildings(AttrMod... perGilding) {
        ISlots slots = new ISlots();
        int n = 0;
        for(AttrMod m : perGilding)
            slots.s.add(new ISlots.SItem("Gilding " + (n++), Collections.<ItemInfo>singletonList(m)));
        return slots;
    }

    /** A worn item whose data a test can change after the fact. */
    private static class Worn implements EquipmentBonuses.Source {
        final Object id = new Object();
        boolean has = true;
        int seq = 1;
        boolean loading = false;
        List<ItemInfo> info = new ArrayList<>();

        Worn with(ItemInfo... tips) {
            info = new ArrayList<>(Arrays.asList(tips));
            return this;
        }

        public Object id() {return id;}
        public boolean hasInfo() {return has;}
        public int infoSeq() {return seq;}
        public List<? extends ItemInfo> info() {
            if(loading)
                throw new Loading();
            return info;
        }
    }

    private static AttrBonus find(EquipmentBonuses.Result r, String name) {
        for(AttrBonus b : r.totals) {
            if(b.name.equals(name))
                return b;
        }
        return null;
    }

    @Test
    void directBonusesOfOneItem() {
        Worn belt = new Worn().with(mods(STR, 5, MELEE, 2));
        EquipmentBonuses.Result r = EquipmentBonuses.compute(Collections.singletonList(belt));
        assertTrue(r.complete());
        assertEquals(2, r.totals.size());
        assertEquals(5.0, find(r, "Strength").value);
        assertEquals("+5", find(r, "Strength").text());
        assertEquals("+2", find(r, "Melee Combat").text());
    }

    @Test
    void installedGildingsAreAddedToTheItemsOwnBonuses() {
        Worn helm = new Worn().with(mods(STR, 4), gildings(mods(STR, 3, MELEE, 2), mods(STR, 1)));
        EquipmentBonuses.Result r = EquipmentBonuses.compute(Collections.singletonList(helm));
        assertEquals(8.0, find(r, "Strength").value);
        assertEquals(2.0, find(r, "Melee Combat").value);
    }

    @Test
    void sameAttributeAcrossItemsIsSummed() {
        Worn a = new Worn().with(mods(STR, 5));
        Worn b = new Worn().with(mods(STR, 7));
        EquipmentBonuses.Result r = EquipmentBonuses.compute(Arrays.asList(a, b));
        assertEquals(1, r.totals.size());
        assertEquals(12.0, find(r, "Strength").value);
    }

    @Test
    void flatAndPercentageValuesAreNeverCombined() {
        /* The same attribute reported once as a flat number and once as a percentage. */
        AttrBonus flat = new AttrBonus("gfx/hud/chr/melee", "Melee Combat", AttrBonus.Kind.FLAT, 10, null, null);
        AttrBonus pct = new AttrBonus("gfx/hud/chr/melee", "Melee Combat", AttrBonus.Kind.PERMILLE, 100, null, null);
        List<AttrBonus> sum = AttrBonus.sum(Arrays.asList(flat, pct, flat));
        assertEquals(2, sum.size());
        for(AttrBonus b : sum) {
            if(b.kind == AttrBonus.Kind.FLAT)
                assertEquals(20.0, b.value);
            else
                assertEquals(100.0, b.value);
        }
    }

    @Test
    void identityIsTheKeyNotTheLocalizedName() {
        AttrBonus en = new AttrBonus("gfx/hud/chr/str", "Strength", AttrBonus.Kind.FLAT, 3, null, null);
        AttrBonus ru = new AttrBonus("gfx/hud/chr/str", "Сила", AttrBonus.Kind.FLAT, 4, null, null);
        AttrBonus other = new AttrBonus("gfx/hud/chr/agi", "Strength", AttrBonus.Kind.FLAT, 9, null, null);
        List<AttrBonus> sum = AttrBonus.sum(Arrays.asList(en, ru, other));
        assertEquals(2, sum.size());
        assertEquals(7.0, sum.get(0).value);
        assertEquals(9.0, sum.get(1).value);
    }

    @Test
    void negativeBonusesReduceTheTotalAndShowTheirSign() {
        Worn cursed = new Worn().with(mods(STR, -6));
        Worn blessed = new Worn().with(mods(STR, 4, MELEE, -1));
        EquipmentBonuses.Result r = EquipmentBonuses.compute(Arrays.asList(cursed, blessed));
        assertEquals("-2", find(r, "Strength").text());
        assertFalse(find(r, "Strength").beneficial());
        assertEquals("-1", find(r, "Melee Combat").text());
    }

    @Test
    void attributesThatCancelOutAreNotListed() {
        Worn a = new Worn().with(mods(STR, 5));
        Worn b = new Worn().with(mods(STR, -5));
        assertTrue(EquipmentBonuses.compute(Arrays.asList(a, b)).totals.isEmpty());
    }

    @Test
    void multiSlotItemIsCountedOnce() {
        Worn twoHanded = new Worn().with(mods(STR, 10));
        /* the equipment window lists a two-handed weapon under both hand slots */
        Collection<EquipmentBonuses.Source> slots = Arrays.<EquipmentBonuses.Source>asList(twoHanded, twoHanded);
        EquipmentBonuses.Result r = EquipmentBonuses.compute(slots);
        assertEquals(1, r.items);
        assertEquals(10.0, find(r, "Strength").value);
    }

    @Test
    void removalAndReplacementChangeTheTotals() {
        Worn sword = new Worn().with(mods(MELEE, 5));
        Worn axe = new Worn().with(mods(MELEE, 8));
        long before = EquipmentBonuses.signature(Collections.singletonList(sword));
        assertEquals(5.0, find(EquipmentBonuses.compute(Collections.singletonList(sword)), "Melee Combat").value);

        long swapped = EquipmentBonuses.signature(Collections.singletonList(axe));
        assertNotEquals(before, swapped);
        assertEquals(8.0, find(EquipmentBonuses.compute(Collections.singletonList(axe)), "Melee Combat").value);

        EquipmentBonuses.Result none = EquipmentBonuses.compute(Collections.<EquipmentBonuses.Source>emptyList());
        assertTrue(none.complete());
        assertTrue(none.totals.isEmpty());
        assertNotEquals(swapped, EquipmentBonuses.signature(Collections.<EquipmentBonuses.Source>emptyList()));
    }

    @Test
    void signatureIsIndependentOfSlotOrder() {
        Worn a = new Worn(), b = new Worn();
        assertEquals(EquipmentBonuses.signature(Arrays.asList(a, b)), EquipmentBonuses.signature(Arrays.asList(b, a)));
    }

    @Test
    void tooltipDataArrivingLaterUpdatesTheResult() {
        Worn ring = new Worn();
        ring.has = false;
        ring.seq = 0;
        long early = EquipmentBonuses.signature(Collections.singletonList(ring));
        EquipmentBonuses.Result partial = EquipmentBonuses.compute(Collections.singletonList(ring));
        assertFalse(partial.complete(), "an item with no tooltip data yet must not look final");
        assertEquals(1, partial.pending);

        ring.has = true;
        ring.seq = 1;
        ring.with(mods(STR, 2));
        assertNotEquals(early, EquipmentBonuses.signature(Collections.singletonList(ring)));
        EquipmentBonuses.Result done = EquipmentBonuses.compute(Collections.singletonList(ring));
        assertTrue(done.complete());
        assertEquals(2.0, find(done, "Strength").value);
    }

    @Test
    void gildingChangeOnAnExistingItemIsPickedUp() {
        Worn helm = new Worn().with(mods(STR, 4));
        long before = EquipmentBonuses.signature(Collections.singletonList(helm));
        helm.seq++;
        helm.with(mods(STR, 4), gildings(mods(MELEE, 3)));
        assertNotEquals(before, EquipmentBonuses.signature(Collections.singletonList(helm)));
        assertEquals(3.0, find(EquipmentBonuses.compute(Collections.singletonList(helm)), "Melee Combat").value);
    }

    @Test
    void loadingItemIsReportedPendingNotZero() {
        Worn loaded = new Worn().with(mods(STR, 5));
        Worn loading = new Worn().with(mods(STR, 100));
        loading.loading = true;
        EquipmentBonuses.Result r = EquipmentBonuses.compute(Arrays.asList(loaded, loading));
        assertFalse(r.complete());
        assertEquals(1, r.pending);
        assertEquals(5.0, find(r, "Strength").value, "loaded items still count");
    }

    @Test
    void alternativeGildingListIsReadToo() {
        nurgling.iteminfo.bonus.fakes.slots_alt.ISlots alt = new nurgling.iteminfo.bonus.fakes.slots_alt.ISlots(3, 2);
        alt.s.add(new nurgling.iteminfo.bonus.fakes.slots_alt.ISlots.SItem("Alt gilding", Collections.<ItemInfo>singletonList(mods(MELEE, 6))));
        Worn weapon = new Worn().with(alt);
        assertEquals(6.0, find(EquipmentBonuses.compute(Collections.singletonList(weapon)), "Melee Combat").value);
    }

    @Test
    void gildingRecipesAreNotCountedAsWornBonuses() {
        /* a gilding ingredient in a worn slot describes what it WOULD add; it adds nothing itself */
        Worn item = new Worn().with(new Slotted(Collections.<ItemInfo>singletonList(mods(STR, 50))), mods(MELEE, 1));
        EquipmentBonuses.Result r = EquipmentBonuses.compute(Collections.singletonList(item));
        assertNull(find(r, "Strength"));
        assertEquals(1.0, find(r, "Melee Combat").value);
    }

    @Test
    void displayOrderIsStableAndLanguageIndependent() {
        AttrBonus agi = new AttrBonus("gfx/hud/chr/agi", "Zzz", AttrBonus.Kind.FLAT, 1, null, null);
        AttrBonus str = new AttrBonus("gfx/hud/chr/str", "Aaa", AttrBonus.Kind.FLAT, 1, null, null);
        AttrBonus melee = new AttrBonus("gfx/hud/chr/melee", "Mmm", AttrBonus.Kind.FLAT, 1, null, null);
        AttrBonus odd = new AttrBonus("gfx/hud/chr/unknownthing", "Bbb", AttrBonus.Kind.FLAT, 1, null, null);
        List<AttrBonus> list = new ArrayList<>(Arrays.asList(odd, melee, agi, str));
        list.sort(AttrOrder.COMPARATOR);
        assertEquals(Arrays.asList(str, agi, melee, odd), list);
    }

    @Test
    void valuesAreFormattedLikeTheGameDoes() {
        assertEquals("+12", new AttrBonus("k", "n", AttrBonus.Kind.FLAT, 12, null, null).text());
        assertEquals("-3", new AttrBonus("k", "n", AttrBonus.Kind.FLAT, -3, null, null).text());
        assertEquals("+15%", new AttrBonus("k", "n", AttrBonus.Kind.PERMILLE, 150, null, null).text());
        assertEquals("+7.5%", new AttrBonus("k", "n", AttrBonus.Kind.NORMAL, 0.075, null, null).text());
        /* inormattr: a positive raw value is a reduction, which the game colours as a buff */
        AttrBonus inv = new AttrBonus("k", "n", AttrBonus.Kind.INVERSE_NORMAL, 1.0, null, null);
        assertEquals("-50%", inv.text());
        assertTrue(inv.beneficial());
    }

    @Test
    void theGamesOwnFormatterIsUsedWhenAvailable() {
        Worn belt = new Worn().with(mods(SPEED, 150));
        AttrBonus b = EquipmentBonuses.compute(Collections.singletonList(belt)).totals.get(0);
        assertEquals(AttrBonus.Kind.PERMILLE, b.kind);
        assertEquals("+15%", b.text(), "markup from Attribute.format is stripped, its number kept");
    }
}
