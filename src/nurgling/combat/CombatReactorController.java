package nurgling.combat;

import haven.GameUI;

import java.util.List;
import java.util.Objects;

/**
 * Orchestrates the reactor: prioritizes defense, maintains the current
 * attack recommendation, and handles the manual attack trigger. Owns no
 * widget-message logic itself (that's {@link CombatActionExecutor}) and no
 * opening/attack logic itself (that's {@link CombatDecisionEngine}).
 *
 * Defense re-evaluates fresh every {@link #tick}: since a just-sent move
 * goes on its own real per-action cooldown (reported by the server via
 * "acool"), a tied second candidate (e.g. green/blue) is naturally
 * revalidated against the latest snapshot before it is ever actually sent,
 * rather than being queued blindly.
 */
public final class CombatReactorController {
    private long relationRevision = 0;
    private Long lastRelationGobId = null;

    private CombatSnapshot lastSnapshot;
    private CombatDecisionEngine.AttackRecommendation lastAttack =
        new CombatDecisionEngine.AttackRecommendation(null, "not yet evaluated");
    private String lastRejectionReason = null;
    private CombatMove lastActionAttempted = null;

    private CombatActionExecutor executor;
    private haven.NFightsess executorFsess;

    public void tick(GameUI gui, boolean reactorEnabled) {
        Long curGobId = (gui != null && gui.fv != null && gui.fv.current != null) ? gui.fv.current.gobid : null;
        if(!Objects.equals(curGobId, lastRelationGobId)) {
            relationRevision++;
            lastRelationGobId = curGobId;
            lastRejectionReason = null;
        }

        CombatSnapshot snap = CombatStateAdapter.build(gui, relationRevision, reactorEnabled);
        lastSnapshot = snap;
        lastAttack = CombatDecisionEngine.chooseAttack(snap);

        if(gui == null || gui.fsess == null) {
            executor = null;
            executorFsess = null;
            return;
        }
        // Rebuild whenever the underlying widget identity changes (relogin,
        // reconnect, any widget-tree rebuild) - never keep sending to a
        // stale/torn-down Fightsess just because we built an executor once.
        if(executor == null || executorFsess != gui.fsess) {
            executor = new CombatActionExecutor(gui.fsess);
            executorFsess = gui.fsess;
        }

        if(!reactorEnabled || !snap.combatPresent)
            return;

        List<CombatMove> candidates = CombatDecisionEngine.chooseDefence(snap);
        for(CombatMove candidate : candidates) {
            CombatActionExecutor.Result r = executor.send(candidate, snap);
            if(r.status == CombatActionExecutor.Status.SENT) {
                lastActionAttempted = candidate;
                lastRejectionReason = null;
                break;
            }
            lastRejectionReason = candidate + ": " + r.reason;
        }
    }

    /** Called from the configured manual-attack hotkey. Fires the current recommendation once, or rejects with a reason; it does not queue/retry. */
    public void onManualAttackTrigger(boolean reactorEnabled) {
        if(!reactorEnabled) {
            lastRejectionReason = "reactor disabled";
            return;
        }
        if(lastSnapshot == null || executor == null) {
            lastRejectionReason = "no combat snapshot yet";
            return;
        }
        CombatDecisionEngine.AttackRecommendation rec = CombatDecisionEngine.chooseAttack(lastSnapshot);
        if(rec.isNone()) {
            lastRejectionReason = "no valid attack recommendation: " + rec.reason;
            return;
        }
        if(rec.move == CombatMove.STING) {
            CombatSnapshot.Value ip = lastSnapshot.ip;
            if(ip.state.isUnsafe() || ip.amount < 2) {
                lastRejectionReason = "Sting requires confirmed IP >= 2";
                return;
            }
        }
        CombatActionExecutor.Result r = executor.send(rec.move, lastSnapshot);
        if(r.status == CombatActionExecutor.Status.SENT) {
            lastActionAttempted = rec.move;
            lastRejectionReason = null;
        } else {
            lastRejectionReason = rec.move + ": " + r.reason;
        }
    }

    public CombatSnapshot snapshot() {
        return lastSnapshot;
    }

    public CombatDecisionEngine.AttackRecommendation recommendation() {
        return lastAttack;
    }

    public String lastRejection() {
        return lastRejectionReason;
    }

    public CombatMove lastActionAttempted() {
        return lastActionAttempted;
    }
}
