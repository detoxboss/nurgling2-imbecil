package nurgling.tools;

/** Derived stats shown on the equipment window, as plain arithmetic. */
public final class EquipStats {
    private EquipStats() {}

    /**
     * Subtlety = Intelligence x Stealth. Null unless <em>both</em> attributes are known: a missing one
     * must not be shown as a misleading 0.
     */
    public static Integer subtlety(Integer intelligence, Integer stealth) {
        if(intelligence == null || stealth == null)
            return null;
        return intelligence * stealth;
    }
}
