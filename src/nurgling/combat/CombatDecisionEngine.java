package nurgling.combat;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure decision logic: no widget messages, no sleeps, no state. Implements
 * the approved defense-tie policy and attack truth table from the port
 * brief (docs/haven-combat-reactor-nurgling-port-brief.md sections 6-7).
 */
public final class CombatDecisionEngine {
    private CombatDecisionEngine() {}

    public static final class AttackRecommendation {
        public final CombatMove move;
        public final String reason;

        AttackRecommendation(CombatMove move, String reason) {
            this.move = move;
            this.reason = reason;
        }

        public boolean isNone() {
            return move == null;
        }
    }

    /**
     * Highest-pressure player opening(s) mapped to their restoring move,
     * red/yellow deduplicated to a single Zig-Zag Ruse candidate, green/blue
     * tie preserved as two separate candidates for the caller to revalidate
     * individually before each is actually sent.
     */
    public static List<CombatMove> chooseDefence(CombatSnapshot s) {
        List<CombatMove> out = new ArrayList<>();
        if(!s.reactorEnabled || !s.combatPresent || s.relationGobId == null)
            return out;

        CombatSnapshot.Value g = s.playerGreen, b = s.playerBlue, y = s.playerYellow, r = s.playerRed;
        if(g.state.isUnsafe() || b.state.isUnsafe() || y.state.isUnsafe() || r.state.isUnsafe())
            return out;

        int gv = g.safeAmount(), bv = b.safeAmount(), yv = y.safeAmount(), rv = r.safeAmount();
        int max = Math.max(Math.max(gv, bv), Math.max(yv, rv));
        if(max <= 0)
            return out;

        boolean needZigzag = (rv == max) || (yv == max);
        if(needZigzag)
            out.add(CombatMove.ZIGZAG_RUSE);
        if(gv == max)
            out.add(CombatMove.QUICK_DODGE);
        if(bv == max)
            out.add(CombatMove.SIDESTEP);
        return out;
    }

    /** Exact truth table from brief section 7.1. */
    public static AttackRecommendation chooseAttack(CombatSnapshot s) {
        if(!s.reactorEnabled)
            return new AttackRecommendation(null, "reactor disabled");
        if(!s.combatPresent || s.relationGobId == null)
            return new AttackRecommendation(null, "no current combat relation");

        CombatSnapshot.Value gV = s.targetGreen, bV = s.targetBlue, yV = s.targetYellow, rV = s.targetRed;
        if(gV.state.isUnsafe() || bV.state.isUnsafe() || yV.state.isUnsafe() || rV.state.isUnsafe())
            return new AttackRecommendation(null, "target openings unavailable");

        int g = gV.safeAmount(), b = bV.safeAmount(), y = yV.safeAmount(), r = rV.safeAmount();
        int fc = r + y;
        int sting = g + b;

        boolean hasTwoIP;
        if(s.ip.state.isUnsafe()) {
            hasTwoIP = false;
        } else {
            hasTwoIP = s.ip.safeAmount() >= 2;
        }

        if(r == 0 && y == 0 && g == 0 && b == 0)
            return new AttackRecommendation(CombatMove.QUICK_BARRAGE, "all openings zero");

        if(!hasTwoIP) {
            if(fc > 0 && fc >= sting)
                return new AttackRecommendation(CombatMove.FULL_CIRCLE, "no 2 IP, FC>=STING");
            return new AttackRecommendation(CombatMove.QUICK_BARRAGE, "no 2 IP, STING>FC");
        }

        if(sting > fc)
            return new AttackRecommendation(CombatMove.STING, "2+ IP, STING>FC");
        if(fc > 0)
            return new AttackRecommendation(CombatMove.FULL_CIRCLE, "2+ IP, FC>0, tie or FC>STING");
        return new AttackRecommendation(CombatMove.QUICK_BARRAGE, "2+ IP, FC==0");
    }
}
