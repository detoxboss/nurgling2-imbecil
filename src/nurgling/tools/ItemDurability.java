package nurgling.tools;

import haven.ItemInfo;

import java.awt.Color;
import java.lang.reflect.Field;
import java.util.List;

/**
 * Remaining durability of an item, read from its {@code Wear} tooltip info (damage {@code d} out of
 * {@code m}), and the colour of the bar that shows it.
 * <p>
 * Only real wear data counts: nothing is inferred from an item's name, quality or resource, and an
 * item without {@code Wear} (or with a nonsensical maximum) has no durability.
 */
public final class ItemDurability {
    public final int damage, max;
    /** Remaining durability, 0 (broken) to 1 (undamaged). */
    public final double remaining;
    public final Color color;

    /** Bar colour stops, spaced evenly from broken to undamaged. Translucent so the item stays visible. */
    private static final Color[] STOPS = {
        new Color(255, 0, 0, 180),
        new Color(255, 153, 0, 180),
        new Color(255, 234, 0, 180),
        new Color(0, 255, 4, 180),
    };

    private static final java.util.concurrent.atomic.AtomicBoolean WARNED = new java.util.concurrent.atomic.AtomicBoolean();

    private ItemDurability(int damage, int max) {
        this.damage = damage;
        this.max = max;
        this.remaining = fraction(damage, max);
        this.color = color(remaining);
    }

    /** Null when the maximum is not positive, since there is no meaningful fraction then. */
    public static ItemDurability of(int damage, int max) {
        return (max > 0) ? new ItemDurability(damage, max) : null;
    }

    public boolean broken() {
        return damage >= max;
    }

    /** {@code (max - damage) / max}, clamped to 0..1. Caller guarantees {@code max > 0}. */
    static double fraction(int damage, int max) {
        double f = (max - (double)damage) / max;
        return Math.max(0.0, Math.min(1.0, f));
    }

    /** Red at broken, through orange and yellow, to green when undamaged. */
    static Color color(double remaining) {
        double r = Math.max(0.0, Math.min(1.0, remaining));
        double pos = r * (STOPS.length - 1);
        int lo = (int)Math.floor(pos);
        if(lo >= STOPS.length - 1)
            return STOPS[STOPS.length - 1];
        double t = pos - lo;
        Color a = STOPS[lo], b = STOPS[lo + 1];
        return new Color(mix(a.getRed(), b.getRed(), t), mix(a.getGreen(), b.getGreen(), t),
                         mix(a.getBlue(), b.getBlue(), t), mix(a.getAlpha(), b.getAlpha(), t));
    }

    private static int mix(int a, int b, double t) {
        return (int)Math.round(a + (b - a) * t);
    }

    /**
     * The durability in an item's info, or null if it has none. {@code Wear} is a server-distributed
     * tooltip class, so it is recognised by name and its {@code d}/{@code m} fields read directly.
     * May throw {@link haven.Loading} if the info is not ready.
     */
    public static ItemDurability of(List<? extends ItemInfo> info) {
        if(info == null)
            return null;
        for(ItemInfo ii : info) {
            Class<?> c = ii.getClass();
            if(!c.getSimpleName().equals("Wear") || !c.getName().contains("wear"))
                continue;
            try {
                Field d = c.getField("d"), m = c.getField("m");
                return of(d.getInt(ii), m.getInt(ii));
            } catch(NoSuchFieldException | IllegalAccessException e) {
                /* a Wear that does not look like the one we know: no durability to show, but say so once */
                if(WARNED.compareAndSet(false, true))
                    System.err.println("[ItemDurability] Unexpected Wear class " + c.getName() + ": " + e);
                return null;
            }
        }
        return null;
    }
}
