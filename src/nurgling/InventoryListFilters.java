package nurgling;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Remember Inventory List Filters (QoL option {@link NConfig.Key#rememberInvListFilters}).
 *
 * Holds, per session ({@link NGameUI#invFilters}), the list-panel filter state of the most
 * recently <em>active</em> inventory: the one whose window last had focus, or whose controls
 * were last changed while it was active. A newly opened inventory starts from this template.
 * Already open inventories are never changed. Nothing here is written to disk, so a restart
 * starts from the defaults again. See docs/keyboard-focus-guard.md.
 */
public final class InventoryListFilters {
    /** Snapshot of one inventory's list-panel filters. Enum values are kept by name. */
    public static final class State {
        public final String minQuality;
        public final String grouping;
        public final String displayType;
        public final boolean nameAscending;
        public final boolean quantityAscending;
        public final String lastSortType;

        public State(String minQuality, String grouping, String displayType,
                     boolean nameAscending, boolean quantityAscending, String lastSortType) {
            this.minQuality = (minQuality == null) ? "" : minQuality;
            this.grouping = grouping;
            this.displayType = displayType;
            this.nameAscending = nameAscending;
            this.quantityAscending = quantityAscending;
            this.lastSortType = lastSortType;
        }

        @Override
        public boolean equals(Object o) {
            if(!(o instanceof State))
                return false;
            State s = (State)o;
            return minQuality.equals(s.minQuality) && Objects.equals(grouping, s.grouping)
                && Objects.equals(displayType, s.displayType) && (nameAscending == s.nameAscending)
                && (quantityAscending == s.quantityAscending) && Objects.equals(lastSortType, s.lastSortType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(minQuality, grouping, displayType, nameAscending, quantityAscending, lastSortType);
        }

        @Override
        public String toString() {
            return "State{q>=" + minQuality + ", " + grouping + ", " + displayType + ", name" + (nameAscending ? "↑" : "↓")
                + ", qty" + (quantityAscending ? "↑" : "↓") + ", last=" + lastSortType + "}";
        }
    }

    private final BooleanSupplier enabled;
    private State template = null;
    /** The active inventory; weak so a closed inventory is not kept alive. */
    private WeakReference<Object> active = new WeakReference<>(null);

    public InventoryListFilters() {
        this(() -> Boolean.TRUE.equals(NConfig.get(NConfig.Key.rememberInvListFilters)));
    }

    InventoryListFilters(BooleanSupplier enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled.getAsBoolean();
    }

    /** The state a newly opened inventory should start from, or null for the defaults. */
    public State template() {
        return enabled() ? template : null;
    }

    public boolean isActive(Object inv) {
        return (inv != null) && (active.get() == inv);
    }

    /** {@code inv} became the active inventory: its filters become the template. */
    public void activated(Object inv, State state) {
        if(!enabled() || (inv == null) || (state == null))
            return;
        if(active.get() != inv)
            active = new WeakReference<>(inv);
        template = state;
    }

    /** One of {@code inv}'s list controls changed; only the active inventory updates the template. */
    public void changed(Object inv, State state) {
        if(!enabled() || (state == null) || !isActive(inv))
            return;
        template = state;
    }

    /* The minimum-quality field: items with quality >= the entered number are listed. */

    /** Characters the minimum-quality field accepts when typed. */
    public static boolean isQualityChar(char c) {
        return ((c >= '0') && (c <= '9')) || (c == '.');
    }

    /** Drop everything but digits and the first '.', e.g. from pasted text. */
    public static String sanitizeQuality(String text) {
        if(text == null)
            return "";
        StringBuilder sb = new StringBuilder(text.length());
        boolean dot = false;
        for(int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if((c >= '0') && (c <= '9')) {
                sb.append(c);
            } else if((c == '.') && !dot) {
                sb.append(c);
                dot = true;
            }
        }
        return sb.toString();
    }

    /** The threshold the field's text means, or null for no filter (empty or not a number). */
    public static Double parseMinQuality(String text) {
        if(text == null)
            return null;
        String t = text.trim();
        if(t.isEmpty())
            return null;
        try {
            double v = Double.parseDouble(t);
            return (Double.isNaN(v) || Double.isInfinite(v)) ? null : v;
        } catch(NumberFormatException e) {
            return null;
        }
    }
}
