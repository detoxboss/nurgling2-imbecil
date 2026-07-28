package nurgling.combat;

import java.util.EnumMap;
import java.util.Map;

/**
 * One immutable, internally-consistent view of combat state at a point in
 * time. Built by {@link CombatStateAdapter}; consumed by
 * {@link CombatDecisionEngine} and {@link CombatReactorController}. Never
 * mutated after construction, so a controller holding an old snapshot can
 * never be corrupted by a later in-progress read.
 */
public final class CombatSnapshot {

    /** A single numeric observation plus its validity semantics (brief section 5). */
    public static final class Value {
        public final int amount;
        public final CombatOpeningState state;

        public Value(int amount, CombatOpeningState state) {
            this.amount = amount;
            this.state = state;
        }

        public static final Value UNKNOWN = new Value(0, CombatOpeningState.UNKNOWN);
        public static final Value DISPLAY_ABSENT = new Value(0, CombatOpeningState.DISPLAY_ABSENT);

        /** Usable zero-or-real numeric value, or 0 if not safe to use (caller must still check {@link #state}). */
        public int safeAmount() {
            return state.isUnsafe() ? 0 : amount;
        }
    }

    /** Whether/where a configured move currently lives in the live action bar. */
    public static final class ActionState {
        public final int slot;
        public final boolean readyNow;
        public final CombatOpeningState state;

        ActionState(int slot, boolean readyNow, CombatOpeningState state) {
            this.slot = slot;
            this.readyNow = readyNow;
            this.state = state;
        }

        public static final ActionState UNAVAILABLE = new ActionState(-1, false, CombatOpeningState.UNAVAILABLE);
    }

    public final boolean reactorEnabled;
    public final boolean combatPresent;
    public final long relationRevision;
    public final Long relationGobId;

    public final Value playerGreen, playerBlue, playerYellow, playerRed;
    public final Value targetGreen, targetBlue, targetYellow, targetRed;
    public final Value ip;

    public final Map<CombatMove, ActionState> actions;
    public final int heldSlot;
    public final double timestamp;

    public CombatSnapshot(boolean reactorEnabled, boolean combatPresent, long relationRevision, Long relationGobId,
                           Value playerGreen, Value playerBlue, Value playerYellow, Value playerRed,
                           Value targetGreen, Value targetBlue, Value targetYellow, Value targetRed,
                           Value ip, Map<CombatMove, ActionState> actions, int heldSlot, double timestamp) {
        this.reactorEnabled = reactorEnabled;
        this.combatPresent = combatPresent;
        this.relationRevision = relationRevision;
        this.relationGobId = relationGobId;
        this.playerGreen = playerGreen;
        this.playerBlue = playerBlue;
        this.playerYellow = playerYellow;
        this.playerRed = playerRed;
        this.targetGreen = targetGreen;
        this.targetBlue = targetBlue;
        this.targetYellow = targetYellow;
        this.targetRed = targetRed;
        this.ip = ip;
        this.actions = new EnumMap<>(actions);
        this.heldSlot = heldSlot;
        this.timestamp = timestamp;
    }

    public ActionState action(CombatMove move) {
        ActionState st = actions.get(move);
        return st == null ? ActionState.UNAVAILABLE : st;
    }

    /** True if this snapshot and {@code other} describe the same relation and reactor-relevant values. */
    public boolean sameDecisionState(CombatSnapshot other) {
        if(other == null)
            return false;
        if(reactorEnabled != other.reactorEnabled || combatPresent != other.combatPresent)
            return false;
        if(relationRevision != other.relationRevision)
            return false;
        if(!valueEq(playerGreen, other.playerGreen) || !valueEq(playerBlue, other.playerBlue)
            || !valueEq(playerYellow, other.playerYellow) || !valueEq(playerRed, other.playerRed))
            return false;
        if(!valueEq(targetGreen, other.targetGreen) || !valueEq(targetBlue, other.targetBlue)
            || !valueEq(targetYellow, other.targetYellow) || !valueEq(targetRed, other.targetRed))
            return false;
        if(!valueEq(ip, other.ip))
            return false;
        if(heldSlot != other.heldSlot)
            return false;
        for(CombatMove m : CombatMove.values()) {
            ActionState a = action(m), b = other.action(m);
            if(a.slot != b.slot || a.readyNow != b.readyNow || a.state != b.state)
                return false;
        }
        return true;
    }

    private static boolean valueEq(Value a, Value b) {
        return a.amount == b.amount && a.state == b.state;
    }
}
