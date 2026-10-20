package nurgling.watch;

import org.json.JSONObject;

import java.util.EnumMap;
import java.util.Map;

/**
 * One lookout's configuration: where it watches, how often, which categories alert and what the
 * alerts say. A running bot works on a {@link #copy()} taken at start, so editing a profile never
 * changes a lookout that is already watching.
 */
public final class WatchProfile {
    public static final String DEFAULT_NAME = "Default";
    public static final int MAX_NAME_LENGTH = 40;

    public static final int SCAN_MIN = 1, SCAN_MAX = 60, SCAN_DEFAULT = 2;
    public static final int GRACE_MIN = 5, GRACE_MAX = 3600, GRACE_DEFAULT = 30;
    public static final int COOLDOWN_MIN = 10, COOLDOWN_MAX = 86400, COOLDOWN_DEFAULT = 300;

    public static final class Category {
        public boolean enabled = true;
        public String template;
        /** Who is notified on a new or escalated sighting; never on cooldown repeats. Off by default. */
        public PingTarget ping = PingTarget.NONE;
        /** Discord role/user snowflake, used when {@link #ping} needs one. */
        public String pingId = "";

        Category(WatchCategory c) {
            this.template = WatchTemplate.defaultFor(c);
        }

        Category copy(WatchCategory c) {
            Category n = new Category(c);
            n.enabled = enabled;
            n.template = template;
            n.ping = ping;
            n.pingId = pingId;
            return n;
        }
    }

    /** Profile key; unique within the store. */
    public String name;
    /** Free text shown as {@code {watcher}}, e.g. "Southeast Wall". */
    public String watcher;
    public int scanIntervalSec = SCAN_DEFAULT;
    public int absenceGraceSec = GRACE_DEFAULT;
    public int cooldownSec = COOLDOWN_DEFAULT;
    public boolean attachScreenshot = true;
    private final Map<WatchCategory, Category> categories = new EnumMap<>(WatchCategory.class);

    public WatchProfile(String name) {
        this.name = name;
        this.watcher = name;
        for (WatchCategory c : WatchCategory.values())
            categories.put(c, new Category(c));
    }

    public Category category(WatchCategory c) {
        return categories.get(c);
    }

    public boolean enabled(WatchCategory c) {
        return categories.get(c).enabled;
    }

    public WatchProfile copy() {
        WatchProfile n = new WatchProfile(name);
        n.watcher = watcher;
        n.scanIntervalSec = scanIntervalSec;
        n.absenceGraceSec = absenceGraceSec;
        n.cooldownSec = cooldownSec;
        n.attachScreenshot = attachScreenshot;
        for (WatchCategory c : WatchCategory.values())
            n.categories.put(c, categories.get(c).copy(c));
        return n;
    }

    public static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Force every numeric field into its allowed range; the UI validates, this guards hand-edited files. */
    public WatchProfile normalized() {
        scanIntervalSec = clamp(scanIntervalSec, SCAN_MIN, SCAN_MAX);
        absenceGraceSec = clamp(absenceGraceSec, GRACE_MIN, GRACE_MAX);
        cooldownSec = clamp(cooldownSec, COOLDOWN_MIN, COOLDOWN_MAX);
        if (watcher == null || watcher.trim().isEmpty())
            watcher = name;
        for (WatchCategory c : WatchCategory.values()) {
            Category cat = categories.get(c);
            if (cat.template == null || cat.template.trim().isEmpty())
                cat.template = WatchTemplate.defaultFor(c);
            else if (cat.template.length() > WatchTemplate.MAX_TEMPLATE_LENGTH)
                cat.template = cat.template.substring(0, WatchTemplate.MAX_TEMPLATE_LENGTH);
            if (cat.ping == null || (cat.ping.needsId() && !PingTarget.validId(cat.pingId)))
                cat.ping = PingTarget.NONE; // a half-configured ping must fail quiet, not loud
            cat.pingId = cat.pingId == null ? "" : cat.pingId.trim();
        }
        return this;
    }

    /** Parses a whole-number field; null when the text is not a number or is outside [min, max]. */
    public static Integer parseInRange(String text, int min, int max) {
        if (text == null)
            return null;
        try {
            int v = Integer.parseInt(text.trim());
            return (v < min || v > max) ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean validName(String name) {
        return name != null && !name.trim().isEmpty() && name.trim().length() <= MAX_NAME_LENGTH;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("watcher", watcher);
        o.put("scanIntervalSec", scanIntervalSec);
        o.put("absenceGraceSec", absenceGraceSec);
        o.put("cooldownSec", cooldownSec);
        o.put("attachScreenshot", attachScreenshot);
        JSONObject cats = new JSONObject();
        for (WatchCategory c : WatchCategory.values()) {
            JSONObject co = new JSONObject();
            co.put("enabled", categories.get(c).enabled);
            co.put("template", categories.get(c).template);
            co.put("ping", categories.get(c).ping.key);
            co.put("pingId", categories.get(c).pingId);
            cats.put(c.key, co);
        }
        o.put("categories", cats);
        return o;
    }

    /** Lenient parse: absent or malformed fields fall back to defaults, numbers are clamped. */
    public static WatchProfile fromJson(String name, JSONObject o) {
        WatchProfile p = new WatchProfile(name);
        if (o == null)
            return p;
        p.watcher = o.optString("watcher", name);
        p.scanIntervalSec = o.optInt("scanIntervalSec", SCAN_DEFAULT);
        p.absenceGraceSec = o.optInt("absenceGraceSec", GRACE_DEFAULT);
        p.cooldownSec = o.optInt("cooldownSec", COOLDOWN_DEFAULT);
        p.attachScreenshot = o.optBoolean("attachScreenshot", true);
        JSONObject cats = o.optJSONObject("categories");
        if (cats != null) {
            for (WatchCategory c : WatchCategory.values()) {
                JSONObject co = cats.optJSONObject(c.key);
                if (co == null)
                    continue;
                Category cat = p.categories.get(c);
                cat.enabled = co.optBoolean("enabled", true);
                cat.template = co.optString("template", cat.template);
                cat.ping = PingTarget.fromKey(co.optString("ping", "none"));
                cat.pingId = co.optString("pingId", "");
            }
        }
        return p.normalized();
    }
}
