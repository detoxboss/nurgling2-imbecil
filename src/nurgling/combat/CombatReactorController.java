package nurgling.combat;

import haven.GameUI;
import nurgling.NConfig;

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

        if(snap.sharedCooldownActive()) {
            // The client is still counting down the shared "attack window" from a
            // previous use (Fightview.atkcs/atkct - a real per-use server-supplied
            // duration, distinct from each move's own per-action cooldown). Re-selecting
            // a different action every tick while this window is still open is what
            // caused the reported "wavering" between attacks/defenses several times per
            // cooldown; only (re-)evaluate and send once the window has actually closed.
            lastRejectionReason = "shared cooldown active";
            return;
        }

        List<CombatMove> candidates = CombatDecisionEngine.chooseDefence(snap, defenseThreshold());
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

    /** Minimum opening value (0-100ish) before the reactor bothers clearing it; user-tunable, default 40. */
    private static int defenseThreshold() {
        Object v = NConfig.get(NConfig.Key.combatReactorDefenseThreshold);
        if(v instanceof Number)
            return ((Number) v).intValue();
        return 40;
    }
}
