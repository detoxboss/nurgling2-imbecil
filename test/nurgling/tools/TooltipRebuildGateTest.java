package nurgling.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The tooltip cache policy used by {@code NWItem}: when a built tooltip is kept, dropped or rebuilt. */
class TooltipRebuildGateTest {
    private final Object info = new Object();

    private TooltipRebuildGate built(double at) {
        TooltipRebuildGate g = new TooltipRebuildGate();
        g.observe(false, info);
        assertTrue(g.shouldRebuild(false, at), "nothing cached yet");
        g.built(at);
        return g;
    }

    @Test
    void cachedTooltipIsKeptWhileNothingChanges() {
        TooltipRebuildGate g = built(10);
        g.observe(false, info);
        assertTrue(g.present());
        assertFalse(g.shouldRebuild(false, 11));
    }

    @Test
    void pressingShiftDropsTheCacheAndRebuildsAtOnce() {
        TooltipRebuildGate g = built(10);
        g.observe(true, info);
        assertFalse(g.present());
        assertTrue(g.shouldRebuild(false, 10.01), "a Shift change must not wait for the rebuild interval");
    }

    @Test
    void releasingShiftAlsoRebuildsAtOnce() {
        TooltipRebuildGate g = new TooltipRebuildGate();
        g.observe(true, info);
        g.built(10);
        g.observe(false, info);
        assertTrue(g.shouldRebuild(false, 10.01));
    }

    @Test
    void rapidShiftTogglesNeverLeaveAStaleTooltip() {
        TooltipRebuildGate g = new TooltipRebuildGate();
        boolean shift = false;
        double t = 0;
        for(int i = 0; i < 20; i++) {
            shift = !shift;
            t += 0.01;
            g.observe(shift, info);
            assertTrue(g.shouldRebuild(false, t), "toggle " + i + " must rebuild");
            g.built(t);
            g.observe(shift, info);
            assertFalse(g.shouldRebuild(false, t), "no rebuild without a change");
        }
    }

    @Test
    void replacedInfoListDropsTheCache() {
        TooltipRebuildGate g = built(10);
        g.observe(false, new Object());
        assertFalse(g.present());
        assertTrue(g.shouldRebuild(false, 10.01));
    }

    @Test
    void itemRequestedRebuildsAreThrottled() {
        TooltipRebuildGate g = built(10);
        assertFalse(g.shouldRebuild(true, 10.2), "inside the interval");
        assertTrue(g.shouldRebuild(true, 10.6), "after the interval");
        assertFalse(g.shouldRebuild(false, 99), "item does not want one");
    }

    @Test
    void failedBuildIsRetriedNextFrame() {
        TooltipRebuildGate g = new TooltipRebuildGate();
        g.observe(false, info);
        assertTrue(g.shouldRebuild(false, 1));
        /* build produced nothing: built() is not called */
        assertTrue(g.shouldRebuild(false, 1.001));
    }
}
