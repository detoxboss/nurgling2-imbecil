package nurgling.watch;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Alert message templates: defaults, placeholder substitution and the sample values used by the
 * settings preview. Substitution is single-pass, so a player name containing "{coords}" is shown
 * literally rather than expanded.
 */
public final class WatchTemplate {
    public static final String WATCHER = "watcher";
    public static final String PLAYER = "player";
    public static final String STATUS = "status";
    public static final String TIME = "time";
    public static final String GOB_ID = "gob_id";
    public static final String COORDS = "coords";

    /** Placeholder names a template may use, in the order the settings UI lists them. */
    public static final String[] PLACEHOLDERS = {WATCHER, PLAYER, STATUS, TIME, GOB_ID, COORDS};

    /** Shown where a value is genuinely not available - never a guessed name or position. */
    public static final String UNAVAILABLE = "unavailable";

    public static final int MAX_TEMPLATE_LENGTH = 1500;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)\\}");

    private WatchTemplate() {
    }

    public static String defaultFor(WatchCategory category) {
        String head = "**VILLAGE WATCH ALERT**\n\nLocation: {watcher}\nPlayer: {player}\nStatus: {status}\nTime: {time}\n\n";
        switch (category) {
            case UNKNOWN:
                return head + "A potentially hostile player has been detected near {watcher}.";
            case WHITE:
                return head + "A white-group player is near {watcher}.";
            default:
                return head + "A RED-group player is near {watcher}. Treat as hostile.";
        }
    }

    /**
     * Fill in a template. A placeholder with no entry (or a null entry) in {@code values} renders as
     * {@link #UNAVAILABLE}; text that merely looks like a placeholder but is not one is left alone.
     */
    public static String render(String template, Map<String, String> values) {
        if (template == null)
            return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            if (!isPlaceholder(key)) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }
            String value = values == null ? null : values.get(key);
            m.appendReplacement(out, Matcher.quoteReplacement(value == null || value.isEmpty() ? UNAVAILABLE : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    public static boolean isPlaceholder(String key) {
        for (String p : PLACEHOLDERS) {
            if (p.equals(key))
                return true;
        }
        return false;
    }

    public static boolean usesPlaceholder(String template, String key) {
        return template != null && template.contains("{" + key + "}");
    }

    /** Illustrative values for the settings preview; clearly sample data, not a real sighting. */
    public static Map<String, String> sampleValues(String watcher, WatchCategory category) {
        Map<String, String> v = new HashMap<>();
        v.put(WATCHER, watcher);
        v.put(PLAYER, category == WatchCategory.UNKNOWN ? "Unknown" : "Sample Player");
        v.put(STATUS, category.statusText());
        v.put(TIME, "2026-01-01 12:00:00");
        v.put(GOB_ID, "123456");
        v.put(COORDS, "(1000, 2000)");
        return v;
    }
}
