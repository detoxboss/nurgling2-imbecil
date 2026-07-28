package nurgling.widgets;

import haven.Coord;
import haven.GOut;
import haven.GameUI;
import haven.KeyBinding;
import haven.KeyMatch;
import haven.Text;
import haven.UI;
import haven.Widget;
import nurgling.NConfig;
import nurgling.combat.CombatDecisionEngine;
import nurgling.combat.CombatMove;
import nurgling.combat.CombatReactorController;
import nurgling.combat.CombatSnapshot;

import java.awt.Color;
import java.awt.event.KeyEvent;

/**
 * Nurgling-native combat reactor: reads Fightview/Fightsess state directly
 * (no screen pixels), automatically fires the correct defensive restoration
 * against the player's most pressing opening, and fires the currently
 * recommended attack (Quick Barrage / Full Circle / Sting) when the
 * configured manual key is pressed. See
 * docs/haven-combat-reactor-nurgling-port-brief.md for the full spec this
 * implements.
 *
 * All actual state reading/decision/sending is delegated to
 * {@code nurgling.combat}; this widget only owns the tick cadence, the
 * manual-attack hotkey, the on-screen diagnostics readout, and the safety
 * trip switch below.
 *
 * Safety invariant: a bug in this feature must never be able to crash the
 * client mid-combat (H&H is permadeath - a crash here can mean losing the
 * character). Every entry point is wrapped so any exception is swallowed
 * and logged rather than propagated, and repeated failures trip a latch
 * that stops the reactor from sending anything further (falling back to
 * fully manual play, which is always still available) until the user
 * explicitly re-enables it.
 */
public class NCombatReactor extends Widget {
    public static final KeyBinding kb_attack =
        KeyBinding.get("combat-reactor-attack", KeyMatch.forcode(KeyEvent.VK_E, 0));

    private static final double TICK_INTERVAL = 0.1;
    private static final int TRIP_THRESHOLD = 3;
    private static final Text.Foundry font = new Text.Foundry(Text.sans, 12);
    private static final Text.Foundry warnFont = new Text.Foundry(Text.sans, 14);

    private final CombatReactorController controller = new CombatReactorController();
    private double sinceTick = 0;
    private int consecutiveFailures = 0;
    private boolean tripped = false;
    private String tripReason = null;
    private boolean wasEnabled = false;

    public NCombatReactor() {
        super(UI.scale(280, 150));
    }

    private boolean configEnabled() {
        Boolean e = (Boolean) NConfig.get(NConfig.Key.combatReactorEnabled);
        return e != null && e;
    }

    /** Whether the reactor is actually allowed to act right now: the user has it on AND it hasn't tripped its safety latch. */
    private boolean effectiveEnabled() {
        return configEnabled() && !tripped;
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);

        boolean nowEnabled = configEnabled();
        if(nowEnabled && !wasEnabled) {
            // User just (re-)enabled it: give it a clean slate.
            tripped = false;
            tripReason = null;
            consecutiveFailures = 0;
        }
        wasEnabled = nowEnabled;

        sinceTick += dt;
        if(sinceTick < TICK_INTERVAL)
            return;
        sinceTick = 0;

        try {
            // Resolve our OWN owning GameUI by walking our actual widget
            // ancestry, not NUtils.getGameUI() (which resolves whichever
            // session is globally focused/thread-local). With two sessions
            // open, each has its own NCombatReactor instance; using the
            // global lookup here made a session's reactor act on whatever
            // session happened to be focused at that instant, and go null
            // mid-switch - causing sent actions to target a torn-down
            // widget tree.
            GameUI gui = getparent(GameUI.class);
            controller.tick(gui, effectiveEnabled());
            consecutiveFailures = 0;
        } catch(Throwable e) {
            handleFailure("tick", e);
        }
    }

    /** Called from NGameUI.globtype; returns true if the key was the reactor's manual-attack trigger. */
    public boolean handleGlobalKey(Widget.GlobKeyEvent ev) {
        if(kb_attack.key().match(ev.awt)) {
            try {
                controller.onManualAttackTrigger(effectiveEnabled());
            } catch(Throwable e) {
                handleFailure("manual attack trigger", e);
            }
            return true;
        }
        return false;
    }

    private void handleFailure(String where, Throwable e) {
        e.printStackTrace();
        consecutiveFailures++;
        if(consecutiveFailures >= TRIP_THRESHOLD && !tripped) {
            tripped = true;
            tripReason = where + ": " + e;
        }
    }

    @Override
    public void draw(GOut g) {
        try {
            drawUnsafe(g);
        } catch(Throwable e) {
            // Never let a diagnostics-rendering bug crash the render loop.
            e.printStackTrace();
        }
    }

    private void drawUnsafe(GOut g) {
        int y = 0;
        if(tripped) {
            g.chcolor(Color.RED);
            y = line(g, y, warnFont, "REACTOR STOPPED - internal error, DEFEND MANUALLY");
            g.chcolor();
            y = line(g, y, font, "(" + tripReason + ")");
            y = line(g, y, font, "Toggle the setting off/on to retry.");
        }

        if(!configEnabled())
            return;
        CombatSnapshot snap = controller.snapshot();
        if(snap == null || !snap.combatPresent)
            return;

        y = line(g, y, font, "Reactor: " + (tripped ? "STOPPED" : "ON") + " (" + kb_attack.key().name() + " to attack)");
        CombatDecisionEngine.AttackRecommendation rec = controller.recommendation();
        y = line(g, y, font, "Attack: " + (rec.isNone() ? "none (" + rec.reason + ")" : rec.move + " - " + rec.reason));
        y = line(g, y, font, String.format("Player G/B/Y/R: %d/%d/%d/%d",
            snap.playerGreen.safeAmount(), snap.playerBlue.safeAmount(),
            snap.playerYellow.safeAmount(), snap.playerRed.safeAmount()));
        y = line(g, y, font, String.format("Target G/B/Y/R: %d/%d/%d/%d",
            snap.targetGreen.safeAmount(), snap.targetBlue.safeAmount(),
            snap.targetYellow.safeAmount(), snap.targetRed.safeAmount()));
        y = line(g, y, font, "IP: " + (snap.ip.state.isUnsafe() ? "unknown" : String.valueOf(snap.ip.amount)));
        CombatMove last = controller.lastActionAttempted();
        if(last != null)
            y = line(g, y, font, "Last sent: " + last);
        String rej = controller.lastRejection();
        if(rej != null)
            line(g, y, font, "Last rejection: " + rej);
    }

    private int line(GOut g, int y, Text.Foundry fnd, String text) {
        g.image(fnd.render(text).tex(), new Coord(0, y));
        return y + UI.scale(16);
    }
}
