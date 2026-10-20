package nurgling.watch;

/**
 * The player categories Village Watch can alert on. Kin group numbers follow the {@code BuddyWnd}
 * palette (group 0 is white, group 2 is red); an unknown player is one with no memorized buddy
 * identity at all, which is a different thing from group 0.
 */
public enum WatchCategory {
    UNKNOWN("unknown", "Unknown", 2, 0xE67E22),
    WHITE("white", "White", 1, 0xE0E0E0),
    RED("red", "Red", 3, 0xE74C3C);

    /** Stable key used in the profile file and in the settings UI. */
    public final String key;
    public final String label;
    /** Higher is worse; an increase between alerts is an escalation that bypasses the repeat cooldown. */
    public final int severity;
    public final int embedColor;

    WatchCategory(String key, String label, int severity, int embedColor) {
        this.key = key;
        this.label = label;
        this.severity = severity;
        this.embedColor = embedColor;
    }

    /** Value substituted for {@code {status}} in message templates. */
    public String statusText() {
        switch (this) {
            case UNKNOWN:
                return "Unknown (not memorized)";
            case WHITE:
                return "White kin group";
            default:
                return "Red kin group";
        }
    }
}
