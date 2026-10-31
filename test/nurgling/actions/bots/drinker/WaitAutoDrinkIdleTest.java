package nurgling.actions.bots.drinker;

import nurgling.actions.AutoDrinkGate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Drinker's wait for an in-flight Auto-drink must always end. */
class WaitAutoDrinkIdleTest {

    @Test
    void endsAtOnceWhenAutoDrinkIsIdle() {
        AutoDrinkGate gate = new AutoDrinkGate();
        assertTrue(new DrinkerBot.WaitAutoDrinkIdle(gate).check());
    }

    @Test
    void endsWhenTheAutoDrinkFinishes() {
        AutoDrinkGate gate = new AutoDrinkGate();
        gate.setAutoDrinking(true);
        DrinkerBot.WaitAutoDrinkIdle wait = new DrinkerBot.WaitAutoDrinkIdle(gate);
        assertFalse(wait.check());
        gate.setAutoDrinking(false);
        assertTrue(wait.check());
    }

    @Test
    void aStuckAutoDrinkOnlyDelaysTheDrinker() {
        AutoDrinkGate gate = new AutoDrinkGate();
        gate.setAutoDrinking(true);
        DrinkerBot.WaitAutoDrinkIdle wait = new DrinkerBot.WaitAutoDrinkIdle(gate);
        int ticks = 0;
        while (!wait.check()) {
            ticks++;
            assertTrue(ticks < 10_000, "the wait never gave up");
        }
        assertTrue(ticks > 0);
    }
}
