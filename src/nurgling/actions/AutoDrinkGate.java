package nurgling.actions;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-session coordination between Auto-drink and an action that drinks on its own (the Drinker
 * bot). One instance lives on each session's {@code NCore}; nothing here is static, so two sessions
 * never block each other.
 *
 * <p>Both sides drink through the same channels: the {@code gfx/borka/drinkan} pose and the session's
 * last-error slot. Drinker counts any drinking pose as a finished sip and treats any game error as a
 * refused sip, and Auto-drink clears the error slot before it presses Drink - so running both at once
 * makes each misread the other. While a lease is held, Auto-drink defers; it never defers merely
 * because some other bot is running (the fork's non-blocking Auto-drink behavior stays intact).
 *
 * <p>A lease is released exactly once, from try-with-resources, so an exception or an interrupted
 * bot never leaves Auto-drink suppressed.
 */
public final class AutoDrinkGate {
    private final AtomicInteger leases = new AtomicInteger();
    private volatile boolean autoDrinking = false;

    /** Starts deferring Auto-drink until the returned lease is closed. */
    public Lease acquire() {
        leases.incrementAndGet();
        return new Lease(this);
    }

    /** As {@link #acquire()}, or a no-op lease when there is no gate (a session without a core). */
    public static Lease acquire(AutoDrinkGate gate) {
        return (gate != null) ? gate.acquire() : new Lease(null);
    }

    /** Whether Auto-drink must hold off right now. */
    public boolean deferring() {
        return leases.get() > 0;
    }

    /** Whether an Auto-drink Drink is in flight (pressed, pose not yet over). */
    public boolean autoDrinking() {
        return autoDrinking;
    }

    /** Set only by AutoDrink around a Drink it pressed (always cleared in a finally). */
    public void setAutoDrinking(boolean drinking) {
        autoDrinking = drinking;
    }

    public static final class Lease implements AutoCloseable {
        private final AutoDrinkGate gate;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Lease(AutoDrinkGate gate) {
            this.gate = gate;
        }

        @Override
        public void close() {
            if (gate != null && closed.compareAndSet(false, true))
                gate.leases.decrementAndGet();
        }
    }
}
