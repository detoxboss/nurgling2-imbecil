package nurgling.actions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lease semantics of the per-session Auto-drink / Drinker coordination. */
class AutoDrinkGateTest {

    @Test
    void defersOnlyWhileALeaseIsHeld() {
        AutoDrinkGate gate = new AutoDrinkGate();
        assertFalse(gate.deferring());
        try (AutoDrinkGate.Lease lease = gate.acquire()) {
            assertTrue(gate.deferring());
        }
        assertFalse(gate.deferring());
    }

    @Test
    void anExceptionStillReleasesTheLease() {
        AutoDrinkGate gate = new AutoDrinkGate();
        assertThrows(IllegalStateException.class, () -> {
            try (AutoDrinkGate.Lease lease = gate.acquire()) {
                throw new IllegalStateException("bot failed mid-drink");
            }
        });
        assertFalse(gate.deferring(), "a failed Drinker must not leave Auto-drink suppressed");
    }

    @Test
    void closingTwiceReleasesOnce() {
        AutoDrinkGate gate = new AutoDrinkGate();
        AutoDrinkGate.Lease a = gate.acquire();
        AutoDrinkGate.Lease b = gate.acquire();
        a.close();
        a.close();
        assertTrue(gate.deferring(), "a double close must not release someone else's lease");
        b.close();
        assertFalse(gate.deferring());
    }

    @Test
    void gatesOfDifferentSessionsAreIndependent() {
        AutoDrinkGate one = new AutoDrinkGate();
        AutoDrinkGate two = new AutoDrinkGate();
        try (AutoDrinkGate.Lease lease = one.acquire()) {
            assertTrue(one.deferring());
            assertFalse(two.deferring());
        }
    }

    @Test
    void aMissingGateGivesANoOpLease() {
        try (AutoDrinkGate.Lease lease = AutoDrinkGate.acquire(null)) {
            // Nothing to defer and nothing to throw.
        }
    }

    @Test
    void autoDrinkingFlagIsSeparateFromLeases() {
        AutoDrinkGate gate = new AutoDrinkGate();
        gate.setAutoDrinking(true);
        assertTrue(gate.autoDrinking());
        assertFalse(gate.deferring());
        gate.setAutoDrinking(false);
        assertFalse(gate.autoDrinking());
    }
}
