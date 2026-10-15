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

    /** What the armor total needs to know about one occupied equipment slot. */
    public interface Armored {
        /** Identity of the item in the slot; the same item in several slots has the same identity. */
        Object id();

        /** False for a broken item, which gives no armor. */
        boolean intact();

        int hard();

        int soft();
    }

    /**
     * {hard, soft} armor of everything worn. The armor figures belong to the <em>item</em>, and an item
     * that occupies several slots (the equipory shows it once per slot) is still one item, so it is
     * counted once. Counting per slot would inflate the total shown next to the sidebar, which already
     * counts each item once.
     */
    public static int[] armor(Iterable<? extends Armored> slots) {
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        int hard = 0, soft = 0;
        for(Armored a : slots) {
            if(a == null || seen.put(a.id(), Boolean.TRUE) != null)
                continue;
            if(a.intact()) {
                hard += a.hard();
                soft += a.soft();
            }
        }
        return new int[] {hard, soft};
    }
}