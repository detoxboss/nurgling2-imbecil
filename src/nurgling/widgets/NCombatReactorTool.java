package nurgling.widgets;

import haven.Button;
import haven.Coord;
import haven.KeyBinding;
import haven.KeyMatch;
import haven.Label;
import haven.UI;
import haven.Widget;
import nurgling.NConfig;

import java.util.Objects;

/**
 * Tiny bots-menu popup for the combat reactor: a rebindable manual-attack
 * key field and a Start/Stop toggle. This is a thin front-end onto the same
 * state the always-present {@link NCombatReactor} overlay widget already
 * reads every tick - {@code NConfig.Key.combatReactorEnabled} and {@link
 * NCombatReactor#kb_attack} - not a second reactor implementation. Toggling
 * this window's button or the Settings > Bots > Combat Reactor checkbox
 * both flip the same flag, so either surface reflects the other next tick.
 *
 * Closing this window (via its close button, Esc, or re-clicking its
 * bots-menu icon) also disables the reactor, per the requested UX.
 */
public class NCombatReactorTool extends haven.Window {

    private final KeyCapture keyCapture;
    private final Button toggle;

    public NCombatReactorTool() {
        super(UI.scale(220, 70), "Combat Reactor");

        Widget prev = add(new Label("Attack key:"), 0, UI.scale(6));
        keyCapture = add(new KeyCapture(UI.scale(80), NCombatReactor.kb_attack), prev.pos("ur").adds(5, -3));

        toggle = add(new Button(UI.scale(100), label()) {
            @Override
            public void click() {
                boolean now = enabled();
                NConfig.set(NConfig.Key.combatReactorEnabled, !now);
                NConfig.needUpdate();
                relabel();
            }
        }, 0, UI.scale(34));

        pack();
    }

    private boolean enabled() {
        Boolean v = (Boolean) NConfig.get(NConfig.Key.combatReactorEnabled);
        return v != null && v;
    }

    private String label() {
        return enabled() ? "Stop" : "Start";
    }

    private void relabel() {
        toggle.change(label());
    }

    /** Disables the reactor; called on close, re-toggle from the bots menu, or teardown. */
    public void stopTool() {
        NConfig.set(NConfig.Key.combatReactorEnabled, false);
        NConfig.needUpdate();
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if((sender == this) && Objects.equals(msg, "close")) {
            stopTool();
            reqdestroy();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }

    @Override
    public void destroy() {
        stopTool();
        super.destroy();
    }

    private static final class KeyCapture extends KeyMatch.Capture {
        private final KeyBinding cmd;

        KeyCapture(int w, KeyBinding cmd) {
            super(w, cmd.key());
            this.cmd = cmd;
        }

        @Override
        public void set(KeyMatch key) {
            super.set(key);
            cmd.set(key);
            NConfig.needUpdate();
        }
    }
}
