package nurgling.tools;

import haven.ItemInfo;
import haven.res.ui.tt.wear.Wear;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class ItemDurabilityTest {
    @Test
    void remainingFractionFollowsDamageOverMax() {
        assertEquals(1.0, ItemDurability.of(0, 100).remaining, 1e-12);
        assertEquals(0.75, ItemDurability.of(25, 100).remaining, 1e-12);
        assertEquals(0.0, ItemDurability.of(100, 100).remaining, 1e-12);
    }

    @Test
    void brokenAndOverDamagedItemsClampToEmpty() {
        assertTrue(ItemDurability.of(100, 100).broken());
        assertTrue(ItemDurability.of(130, 100).broken());
        assertEquals(0.0, ItemDurability.of(130, 100).remaining, 1e-12);
        assertFalse(ItemDurability.of(99, 100).broken());
    }

    @Test
    void negativeDamageCannotOverfillTheBar() {
        assertEquals(1.0, ItemDurability.of(-5, 100).remaining, 1e-12);
    }

    @Test
    void invalidMaximumMeansNoDurabilityInsteadOfADivisionByZero() {
        assertNull(ItemDurability.of(0, 0));
        assertNull(ItemDurability.of(5, 0));
        assertNull(ItemDurability.of(5, -10));
    }

    @Test
    void colourRunsFromRedThroughOrangeAndYellowToGreen() {
        Color broken = ItemDurability.of(100, 100).color;
        Color low = ItemDurability.of(67, 100).color;
        Color mid = ItemDurability.of(34, 100).color;
        Color full = ItemDurability.of(0, 100).color;
        assertEquals(new Color(255, 0, 0, 180), broken);
        assertEquals(new Color(0, 255, 4, 180), full);
        /* red channel stays high until yellow, green channel only rises from there */
        assertTrue(low.getRed() == 255 && low.getGreen() > 0 && low.getGreen() < 153 + 80, "low: " + low);
        assertTrue(mid.getRed() == 255 && mid.getGreen() > 153, "mid: " + mid);
        assertTrue(low.getGreen() < mid.getGreen());
    }

    @Test
    void colourIsContinuousAndGreenerAsDurabilityRises() {
        int prevGreen = -1;
        for(int remaining = 0; remaining <= 100; remaining++) {
            Color c = ItemDurability.of(100 - remaining, 100).color;
            assertEquals(180, c.getAlpha());
            if(remaining <= 66)
                assertTrue(c.getGreen() >= prevGreen, "green must not fall as durability rises, at " + remaining + "%");
            prevGreen = c.getGreen();
        }
    }

    @Test
    void readsTheRealWearTooltipClass() {
        ItemDurability d = ItemDurability.of(Collections.<ItemInfo>singletonList(new Wear(null, 30, 120)));
        assertNotNull(d);
        assertEquals(30, d.damage);
        assertEquals(120, d.max);
        assertEquals(0.75, d.remaining, 1e-12);
    }

    @Test
    void itemsWithoutWearHaveNoBar() {
        assertNull(ItemDurability.of(Collections.<ItemInfo>emptyList()));
        assertNull(ItemDurability.of((java.util.List<ItemInfo>)null));
    }

    @Test
    void aWearWithAnInvalidMaximumHasNoBar() {
        assertNull(ItemDurability.of(Arrays.<ItemInfo>asList(new Wear(null, 0, 0))));
    }
}
