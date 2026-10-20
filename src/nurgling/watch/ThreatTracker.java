package nurgling.watch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a stream of per-scan sightings into the alerts worth sending: report a new threat at once,
 * stay quiet while it stays put, repeat after the cooldown, report an escalation at once, and
 * forget a threat that has been gone longer than the absence grace.
 *
 * <p>One tracker belongs to one running lookout. Gob ids are only meaningful inside the session
 * that produced them, so trackers are never shared or looked up globally. Time is passed in, so
 * the logic needs no clock to test.
 */
public final class ThreatTracker {
    /** Upper bound on remembered sightings; the least recently seen is dropped first. */
    public static final int MAX_TRACKED = 256;
    /**
     * How long a player with no memorized identity must be continuously classified unknown before
     * it is reported. Rides out a Buddy attribute that is a moment behind the gob it belongs to.
     */
    public static final long UNKNOWN_SETTLE_MS = 3000;

    public enum Reason {NEW, ESCALATION, REPEAT}

    /** One player seen in one scan. */
    public static final class Sighting {
        public final long gobId;
        public final WatchCategory category;
        /** Memorized name; null for an unknown player (never invented). */
        public final String name;
        /** Display text for the position; null when unavailable. */
        public final String coords;

        public Sighting(long gobId, WatchCategory category, String name, String coords) {
            this.gobId = gobId;
            this.category = category;
            this.name = name;
            this.coords = coords;
        }
    }

    public static final class Alert {
        public final Sighting sighting;
        public final Reason reason;
        public final long atMs;

        Alert(Sighting sighting, Reason reason, long atMs) {
            this.sighting = sighting;
            this.reason = reason;
            this.atMs = atMs;
        }
    }

    /** An alert not produced by tracking, for the settings window's test buttons. */
    public static Alert sampleAlert(Sighting sighting) {
        return new Alert(sighting, Reason.NEW, System.currentTimeMillis());
    }

    private static final class Entry {
        WatchCategory category;
        long categorySince;
        long lastSeen;
        WatchCategory lastAlertCategory;
        long lastAlertAt;
    }

    private final long graceMs;
    private final long cooldownMs;
    private final long settleMs;
    private final Set<WatchCategory> enabled;
    private final Map<Long, Entry> entries = new HashMap<>();

    public ThreatTracker(WatchProfile profile) {
        this(profile.absenceGraceSec * 1000L, profile.cooldownSec * 1000L, UNKNOWN_SETTLE_MS, enabledOf(profile));
    }

    public ThreatTracker(long graceMs, long cooldownMs, long settleMs, Set<WatchCategory> enabled) {
        this.graceMs = graceMs;
        this.cooldownMs = cooldownMs;
        this.settleMs = settleMs;
        this.enabled = EnumSet.noneOf(WatchCategory.class);
        this.enabled.addAll(enabled);
    }

    private static Set<WatchCategory> enabledOf(WatchProfile p) {
        Set<WatchCategory> s = EnumSet.noneOf(WatchCategory.class);
        for (WatchCategory c : WatchCategory.values()) {
            if (p.enabled(c))
                s.add(c);
        }
        return s;
    }

    /**
     * Fold one scan into the tracker. {@code sightings} is everything classifiable this scan; gobs
     * absent from it are not forgotten until the absence grace has passed.
     */
    public List<Alert> update(long nowMs, Collection<Sighting> sightings) {
        List<Alert> alerts = new ArrayList<>();
        List<Sighting> ordered = new ArrayList<>(sightings);
        ordered.sort((a, b) -> Long.compare(a.gobId, b.gobId));
        for (Sighting s : ordered) {
            Entry e = entries.get(s.gobId);
            boolean alertable = enabled.contains(s.category);
            if (e == null) {
                if (!alertable)
                    continue;
                e = new Entry();
                e.category = s.category;
                e.categorySince = nowMs;
                entries.put(s.gobId, e);
            } else if (e.category != s.category) {
                e.category = s.category;
                e.categorySince = nowMs;
            }
            e.lastSeen = nowMs;
            if (!alertable)
                continue;
            Reason reason = reasonToAlert(e, nowMs);
            if (reason != null) {
                e.lastAlertCategory = s.category;
                e.lastAlertAt = nowMs;
                alerts.add(new Alert(s, reason, nowMs));
            } else if (e.lastAlertCategory != null && e.lastAlertCategory != s.category) {
                // Moved to a milder category: no alert, but remember it so a later return to the
                // worse one counts as an escalation again.
                e.lastAlertCategory = s.category;
            }
        }
        expire(nowMs);
        return alerts;
    }

    private Reason reasonToAlert(Entry e, long nowMs) {
        if (e.category == WatchCategory.UNKNOWN && nowMs - e.categorySince < settleMs)
            return null;
        if (e.lastAlertCategory == null)
            return Reason.NEW;
        if (e.category.severity > e.lastAlertCategory.severity)
            return Reason.ESCALATION;
        if (nowMs - e.lastAlertAt >= cooldownMs)
            return Reason.REPEAT;
        return null;
    }

    private void expire(long nowMs) {
        for (Iterator<Entry> i = entries.values().iterator(); i.hasNext(); ) {
            if (nowMs - i.next().lastSeen > graceMs)
                i.remove();
        }
        while (entries.size() > MAX_TRACKED) {
            Long oldest = null;
            long oldestSeen = Long.MAX_VALUE;
            for (Map.Entry<Long, Entry> me : entries.entrySet()) {
                if (me.getValue().lastSeen < oldestSeen) {
                    oldestSeen = me.getValue().lastSeen;
                    oldest = me.getKey();
                }
            }
            entries.remove(oldest);
        }
    }

    public int trackedCount() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }
}
