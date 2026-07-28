package nurgling.combat;

import haven.Buff;
import haven.Bufflist;
import haven.Fightsess;
import haven.Fightview;
import haven.GameUI;
import haven.Indir;
import haven.NFightsess;
import haven.Resource;
import haven.Session;
import haven.Utils;

import java.util.EnumMap;
import java.util.Map;

/**
 * Reads live {@link Fightview}/{@link Fightsess} state (already tracked by
 * the client from server messages) and produces one immutable
 * {@link CombatSnapshot}. Read-only: never sends anything, never mutates the
 * widgets it reads from.
 */
public final class CombatStateAdapter {
    private CombatStateAdapter() {}

    private static final String GREEN = "paginae/atk/offbalance";
    private static final String BLUE = "paginae/atk/dizzy";
    private static final String YELLOW = "paginae/atk/reeling";
    private static final String RED = "paginae/atk/cornered";

    public static CombatSnapshot build(GameUI gui, long relationRevision, boolean reactorEnabled) {
        double now = Utils.rtime();
        Fightview fv = (gui == null) ? null : gui.fv;
        NFightsess fsess = (gui == null) ? null : gui.fsess;

        boolean combatPresent = (fv != null) && (fsess != null) && !fv.lsrel.isEmpty() && (fv.current != null);
        Fightview.Relation rel = (fv != null) ? fv.current : null;
        Long relationGobId = (rel != null) ? rel.gobid : null;

        CombatSnapshot.Value playerGreen, playerBlue, playerYellow, playerRed;
        if(fv == null) {
            playerGreen = playerBlue = playerYellow = playerRed = CombatSnapshot.Value.DISPLAY_ABSENT;
        } else {
            playerGreen = readOpening(fv.buffs, GREEN);
            playerBlue = readOpening(fv.buffs, BLUE);
            playerYellow = readOpening(fv.buffs, YELLOW);
            playerRed = readOpening(fv.buffs, RED);
        }

        CombatSnapshot.Value targetGreen, targetBlue, targetYellow, targetRed, ip;
        if(rel == null) {
            targetGreen = targetBlue = targetYellow = targetRed = CombatSnapshot.Value.DISPLAY_ABSENT;
            ip = CombatSnapshot.Value.DISPLAY_ABSENT;
        } else {
            targetGreen = readOpening(rel.buffs, GREEN);
            targetBlue = readOpening(rel.buffs, BLUE);
            targetYellow = readOpening(rel.buffs, YELLOW);
            targetRed = readOpening(rel.buffs, RED);
            ip = new CombatSnapshot.Value(rel.ip, CombatOpeningState.CONFIRMED_ZERO);
        }

        Map<CombatMove, CombatSnapshot.ActionState> actions = readActions(fsess, now);
        int heldSlot = (fsess != null) ? fsess.use : -1;

        return new CombatSnapshot(reactorEnabled, combatPresent, relationRevision, relationGobId,
            playerGreen, playerBlue, playerYellow, playerRed,
            targetGreen, targetBlue, targetYellow, targetRed,
            ip, actions, heldSlot, now);
    }

    /**
     * Zero-value opening buffs are removed by the protocol rather than sent
     * as a zero, so "not found in the list" is also a safe zero
     * ({@link CombatOpeningState#VALID_NOT_FOUND}), distinct from a positive
     * lookup failure ({@link CombatOpeningState#SEARCH_ERROR}).
     */
    private static CombatSnapshot.Value readOpening(Bufflist list, String resourceName) {
        if(list == null)
            return CombatSnapshot.Value.DISPLAY_ABSENT;
        for(Buff buff : list.children(Buff.class)) {
            String resnm = resolveResName(buff.res);
            if(resnm == null || !resnm.equals(resourceName))
                continue;
            int v = buff.ameter();
            if(v < 0)
                return new CombatSnapshot.Value(0, CombatOpeningState.SEARCH_ERROR);
            return new CombatSnapshot.Value(v, CombatOpeningState.CONFIRMED_ZERO);
        }
        return new CombatSnapshot.Value(0, CombatOpeningState.VALID_NOT_FOUND);
    }

    private static Map<CombatMove, CombatSnapshot.ActionState> readActions(NFightsess fsess, double now) {
        Map<CombatMove, CombatSnapshot.ActionState> out = new EnumMap<>(CombatMove.class);
        for(CombatMove m : CombatMove.values())
            out.put(m, CombatSnapshot.ActionState.UNAVAILABLE);
        if(fsess == null || fsess.actions == null)
            return out;
        for(int i = 0; i < fsess.actions.length; i++) {
            Fightsess.Action act = fsess.actions[i];
            if(act == null)
                continue;
            String resnm = resolveResName(act.res);
            CombatMove m = CombatMove.byResourceName(resnm);
            if(m == null)
                continue;
            boolean ready = now >= act.ct;
            out.put(m, new CombatSnapshot.ActionState(i, ready, CombatOpeningState.CONFIRMED_ZERO));
        }
        return out;
    }

    private static String resolveResName(Indir<Resource> ref) {
        if(ref instanceof Session.CachedRes.Ref)
            return ((Session.CachedRes.Ref) ref).resnm();
        return null;
    }
}
