package nurgling.tasks;

import nurgling.NGameUI;
import nurgling.NUtils;

/**
 * Waits for haven.GameUI.prog (the hourglass/percentage widget shown during a repeating timed
 * action - GameUI.java:85,992-1028, server-pushed via the "prog" widget message) to complete its
 * first cycle, or disappear entirely (action ended, e.g. tapped out or was too quick to ever show
 * progress). This is the same signal Forging/LightFire/Craft/TunnelingBot already poll
 * (`gui.prog != null && gui.prog.prog > 0`-style checks) to know when a timed action finishes -
 * a genuine server-driven event, not a guessed duration. Used here to interrupt a repeating
 * gather action right after its first unit instead of a fixed delay.
 */
public class WaitFirstProgressCycle extends NTask {
    private static final double COMPLETE_THRESHOLD = 0.95;

    private boolean sawProgress = false;
    private int ticks = 0;
    private final int maxTicks;

    public WaitFirstProgressCycle() {
        this(300);
    }

    public WaitFirstProgressCycle(int maxTicks) {
        this.maxTicks = maxTicks;
    }

    @Override
    public boolean check() {
        NGameUI gui = NUtils.getGameUI();
        if (gui != null) {
            if (gui.prog != null) {
                sawProgress = true;
                if (gui.prog.prog >= COMPLETE_THRESHOLD)
                    return true;
            } else if (sawProgress) {
                // Was showing progress, now gone - the whole repeating job ended (tapped out, or
                // interrupted elsewhere) before we caught a 95%+ reading on this cycle.
                return true;
            }
        }
        return ++ticks >= maxTicks;
    }
}
