package nurgling.combat;

/**
 * The six moves the reactor knows about, identified by their canonical
 * server resource path (see {@code nurgling.conf.NCooldown}) rather than by
 * keyboard binding or action-slot index, since either can change.
 */
public enum CombatMove {
    QUICK_BARRAGE("paginae/atk/barrage"),
    FULL_CIRCLE("paginae/atk/fullcircle"),
    STING("paginae/atk/sting"),
    QUICK_DODGE("paginae/atk/qdodge"),
    SIDESTEP("paginae/atk/sidestep"),
    ZIGZAG_RUSE("paginae/atk/zigzag");

    public final String resourceName;

    CombatMove(String resourceName) {
        this.resourceName = resourceName;
    }

    public static CombatMove byResourceName(String name) {
        if(name == null)
            return null;
        for(CombatMove m : values()) {
            if(m.resourceName.equals(name))
                return m;
        }
        return null;
    }
}
