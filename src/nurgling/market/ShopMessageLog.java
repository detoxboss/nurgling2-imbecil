package nurgling.market;

import haven.UI;
import haven.Widget;
import nurgling.plugins.UiEvents;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Records which server messages each widget has received, through Nurgling's existing
 * {@link UiEvents} seam (no change to the server-distributed Shopbox class). Register it before the
 * Barter Stand window is opened so the first messages are not missed.
 */
public final class ShopMessageLog implements UiEvents.Listener {
    private static final class Entry {
        final Set<String> heard = new HashSet<>();
        long lastAt;
        long firstAt;
        final java.util.List<String> trace = new java.util.ArrayList<>();
    }

    private final Map<Object, Entry> entries = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void onUiMsg(UI ui, int id, Widget wdg, String msg, Object[] args) {
        record(wdg, msg, System.currentTimeMillis());
    }

    void record(Object widget, String message, long at) {
        if (widget == null) {
            return;
        }
        synchronized (entries) {
            Entry entry = entries.computeIfAbsent(widget, ignored -> new Entry());
            if (entry.trace.isEmpty()) {
                entry.firstAt = at;
            }
            entry.heard.add(message);
            if (entry.trace.size() < 40) {
                entry.trace.add(message + "@+" + (at - entry.firstAt) + "ms");
            }
            entry.lastAt = at;
        }
    }

    public boolean heard(Object widget, String message) {
        synchronized (entries) {
            Entry entry = entries.get(widget);
            return entry != null && entry.heard.contains(message);
        }
    }

    /** Diagnostic: the ordered server messages this widget received, e.g. "res@+0ms tt@+12ms price@+40ms" (empty if none). */
    public String trace(Object widget) {
        synchronized (entries) {
            Entry entry = entries.get(widget);
            return entry == null || entry.trace.isEmpty() ? "(no messages)" : String.join(" ", entry.trace);
        }
    }

    public long lastMessageAt(Object widget) {
        synchronized (entries) {
            Entry entry = entries.get(widget);
            return entry == null ? 0 : entry.lastAt;
        }
    }
}
