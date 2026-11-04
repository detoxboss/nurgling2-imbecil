package nurgling;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class InventoryListFiltersTest {
    static InventoryListFilters.State q(String minQ, String grouping) {
        return new InventoryListFilters.State(minQ, grouping, "Name", true, false, "quantity");
    }

    @Test
    void lastActivatedInventoryIsTheTemplate() {
        InventoryListFilters f = new InventoryListFilters(() -> true);
        Object a = new Object(), b = new Object();
        f.activated(a, q("1", "Q1"));
        f.activated(b, q("10", "Q10"));
        f.activated(a, q("1", "Q1")); // A clicked again
        assertEquals(q("1", "Q1"), f.template(), "A -> B -> A -> C: C starts from A");
    }

    @Test
    void onlyTheActiveInventoryUpdatesTheTemplate() {
        InventoryListFilters f = new InventoryListFilters(() -> true);
        Object a = new Object(), b = new Object();
        f.activated(a, q("1", "NONE"));
        f.changed(b, q("50", "NONE"));
        assertEquals(q("1", "NONE"), f.template());
        f.changed(a, q("5", "Q5"));
        assertEquals(q("5", "Q5"), f.template());
    }

    @Test
    void templateOutlivesTheInventory() {
        InventoryListFilters f = new InventoryListFilters(() -> true);
        Object a = new Object();
        f.activated(a, q("3", "Q"));
        a = null;
        System.gc();
        assertEquals(q("3", "Q"), f.template(), "closing an inventory does not erase the remembered filters");
    }

    @Test
    void disabledMeansNoTemplateAndNoTracking() {
        AtomicBoolean on = new AtomicBoolean(false);
        InventoryListFilters f = new InventoryListFilters(on::get);
        Object a = new Object();
        f.activated(a, q("7", "Q"));
        assertNull(f.template());
        on.set(true);
        assertNull(f.template(), "nothing was recorded while off");
    }

    @Test
    void sessionsAreIndependent() {
        InventoryListFilters s1 = new InventoryListFilters(() -> true);
        InventoryListFilters s2 = new InventoryListFilters(() -> true);
        s1.activated(new Object(), q("1", "Q1"));
        assertNull(s2.template());
    }

    @Test
    void stateCarriesSorting() {
        InventoryListFilters.State s = new InventoryListFilters.State("", "NONE", "Info", false, true, "name");
        InventoryListFilters f = new InventoryListFilters(() -> true);
        f.activated(new Object(), s);
        InventoryListFilters.State t = f.template();
        assertFalse(t.nameAscending);
        assertTrue(t.quantityAscending);
        assertEquals("name", t.lastSortType);
        assertEquals("Info", t.displayType);
    }

    @Test
    void qualityFieldInput() {
        assertTrue(InventoryListFilters.isQualityChar('7'));
        assertTrue(InventoryListFilters.isQualityChar('.'));
        assertFalse(InventoryListFilters.isQualityChar('q'));
        assertFalse(InventoryListFilters.isQualityChar('!'));
        assertEquals("12.53", InventoryListFilters.sanitizeQuality("q1x2.5.3a"), "only the first '.' is kept");
        assertEquals("", InventoryListFilters.sanitizeQuality(null));
        assertEquals(10.0, InventoryListFilters.parseMinQuality(" 10 "));
        assertNull(InventoryListFilters.parseMinQuality(""));
        assertNull(InventoryListFilters.parseMinQuality("."));
        assertNull(InventoryListFilters.parseMinQuality("abc"));
        assertNull(InventoryListFilters.parseMinQuality("NaN"));
        assertNull(InventoryListFilters.parseMinQuality(null));
    }
}
