package nurgling.watch;

/**
 * Decides what a player gob is, from facts the caller has already read off the game state. Pure and
 * stateless so the awkward cases (buddy record not loaded yet, unknown vs. white) are unit-testable;
 * time-based confirmation lives in {@link ThreatTracker}.
 *
 * <p>The distinction that matters: a gob with <b>no</b> {@code Buddy} attribute is a player the
 * character has not memorized (unknown). A gob <b>with</b> the attribute is memorized, and its kin
 * group comes from the buddy list record - which may simply not have arrived yet. Treating that
 * loading gap as "unknown" would raise false alarms on every login, so it is {@link Verdict#PENDING}.
 * ({@code NAlarmWdg} instead folds unknown into group 0; Village Watch deliberately does not.)
 */
public final class WatchClassifier {
    /** Kin group numbers, from the {@code BuddyWnd} palette. */
    public static final int GROUP_WHITE = 0;
    public static final int GROUP_RED = 2;

    public enum Verdict {
        /** Not a live player to watch for: the lookout itself, a party member, a corpse or mannequin. */
        IGNORE,
        /** Cannot be classified yet (model, buddy list or map still loading); never alerts. */
        PENDING,
        UNKNOWN,
        WHITE,
        RED,
        /** A memorized player in a kin group Village Watch does not alert on. */
        OTHER_GROUP;

        /** The alert category, or null for verdicts that never alert. */
        public WatchCategory category() {
            switch (this) {
                case UNKNOWN:
                    return WatchCategory.UNKNOWN;
                case WHITE:
                    return WatchCategory.WHITE;
                case RED:
                    return WatchCategory.RED;
                default:
                    return null;
            }
        }
    }

    /** What the caller read off one player gob. */
    public static final class Observation {
        public boolean live = true;
        public boolean modelLoaded = true;
        public boolean mapLoaded = true;
        public boolean hasBuddyAttr = false;
        public boolean buddyListLoaded = true;
        /** Kin group from the buddy list record; null when the attribute's record was not found. */
        public Integer group = null;
    }

    private WatchClassifier() {
    }

    public static Verdict classify(Observation o) {
        if (!o.live)
            return Verdict.IGNORE;
        if (!o.modelLoaded)
            return Verdict.PENDING;
        if (o.hasBuddyAttr) {
            if (!o.buddyListLoaded || o.group == null)
                return Verdict.PENDING;
            if (o.group == GROUP_WHITE)
                return Verdict.WHITE;
            if (o.group == GROUP_RED)
                return Verdict.RED;
            return Verdict.OTHER_GROUP;
        }
        return o.mapLoaded ? Verdict.UNKNOWN : Verdict.PENDING;
    }
}
