package nurgling.combat;

import haven.NFightsess;

/**
 * Sends exactly one combat move through the normal client action path
 * ({@link NFightsess#requestAction}/{@link NFightsess#releaseAction}, which
 * wrap the same use/rel message sequence normal keyboard combat input uses),
 * after revalidating the move against the freshest available snapshot.
 *
 * Never reimplements the protocol sequencing itself.
 */
public final class CombatActionExecutor {
    public enum Status {SENT, REJECTED}

    public static final class Result {
        public final Status status;
        public final String reason;

        Result(Status status, String reason) {
            this.status = status;
            this.reason = reason;
        }
    }

    private final NFightsess fsess;

    public CombatActionExecutor(NFightsess fsess) {
        this.fsess = fsess;
    }

    /**
     * Revalidates that {@code move} is present in the current deck and off
     * cooldown in {@code snapshot}, then sends it. "Sent" is not proof of
     * execution (range/movement/relation changes can still prevent the
     * intended effect); the client gives no stronger confirmation than this.
     */
    public Result send(CombatMove move, CombatSnapshot snapshot) {
        if(fsess == null)
            return new Result(Status.REJECTED, "no fight session");
        CombatSnapshot.ActionState st = snapshot.action(move);
        if(st.state == CombatOpeningState.UNAVAILABLE || st.slot < 0)
            return new Result(Status.REJECTED, move + " not in current deck");
        if(!st.readyNow)
            return new Result(Status.REJECTED, move + " on cooldown");
        try {
            fsess.requestAction(st.slot);
        } catch(Throwable e) {
            // A combat crash is worse than a missed swing: never let a send
            // failure here (e.g. a widget torn down by a session switch)
            // escape to the caller.
            e.printStackTrace();
            return new Result(Status.REJECTED, "requestAction failed: " + e);
        }
        try {
            fsess.releaseAction();
        } catch(Throwable e) {
            e.printStackTrace();
            // "use" already went out even though release failed; report it
            // sent so the caller doesn't retry the same slot on top of it.
            return new Result(Status.SENT, "release failed: " + e);
        }
        return new Result(Status.SENT, null);
    }
}
