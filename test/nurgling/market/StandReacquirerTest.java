package nurgling.market;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static nurgling.market.MarketTestLayouts.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandReacquirerTest {
    private static final class FakeClock implements ShopboxReadiness.Clock {
        long now;
        Runnable onSleep = () -> { };

        @Override public long now() { return now; }

        @Override
        public void sleep(long millis) {
            now += millis;
            onSleep.run();
        }
    }

    /** Stands only exist client-side within a (square, grid-based) load radius of the player; every read yields a fresh handle. */
    private static final class FakeWorld implements StandReacquirer.World<String>, AreaSweep.Source<String> {
        final List<StandMatcher.Detected<String>> stands;
        final double radius;
        double playerX;
        double playerY;
        boolean navigationFails;
        int handles;
        int approaches;
        int hiddenReads;      // how many reads still return nothing after arriving (late loading)

        FakeWorld(List<StandMatcher.Detected<String>> stands, double radius) {
            this.stands = stands;
            this.radius = radius;
        }

        @Override
        public boolean approach(PhysicalRef saved) {
            approaches++;
            if (navigationFails) return false;
            playerX = saved.offX;
            playerY = saved.offY;
            return true;
        }

        @Override
        public boolean visit(AreaSweepPlanner.Waypoint waypoint) {
            playerX = waypoint.tileX * 11.0;
            playerY = waypoint.tileY * 11.0;
            return true;
        }

        @Override
        public List<StandMatcher.Detected<String>> loaded() {
            if (hiddenReads > 0) {
                hiddenReads--;
                return new ArrayList<>();
            }
            return detect();
        }

        @Override
        public List<StandMatcher.Detected<String>> detect() {
            List<StandMatcher.Detected<String>> visible = new ArrayList<>();
            for (StandMatcher.Detected<String> stand : stands) {
                if (Math.max(Math.abs(stand.ref.offX - playerX), Math.abs(stand.ref.offY - playerY)) <= radius) { // square load region
                    visible.add(new StandMatcher.Detected<>(stand.value + "#handle" + (++handles), stand.ref, stand.rcX, stand.rcY));
                }
            }
            return visible;
        }
    }

    private static List<StandMatcher.Detected<String>> grid(int count) {
        List<StandMatcher.Detected<String>> stands = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            stands.add(at("s" + index, "7001", 60 + (index % 10) * 100, 60 + (index / 10) * 100));
        }
        return stands;
    }

    @Test
    void aStandSeenEarlyUnloadsDuringTheSweepAndIsReacquiredForCapture() throws Exception {
        List<StandMatcher.Detected<String>> stands = grid(100);       // 1000x1000 units; load radius ~ 20 tiles
        FakeWorld world = new FakeWorld(stands, 20 * 11);
        List<AreaSweepPlanner.Waypoint> waypoints = AreaSweepPlanner.plan(
                Collections.singletonList(new AreaSweepPlanner.Piece(1, 0, 0, 100, 100)), 40);
        AreaSweep.Outcome<String> sweep = AreaSweep.run(waypoints, world);
        assertEquals(100, sweep.stands.size());

        // The very first stand was found at the first waypoint, but the sweep then walked far away.
        StandMatcher.Detected<String> early = null;
        for (StandMatcher.Detected<String> detected : sweep.stands) {
            if (detected.value.startsWith("s0#")) early = detected;
        }
        assertNotNull(early);
        boolean stillLoaded = false;
        for (StandMatcher.Detected<String> loaded : world.detect()) stillLoaded |= loaded.value.startsWith("s0#");
        assertFalse(stillLoaded, "the early stand must be unloaded after the sweep moved away");

        StandReacquirer.Result<String> result = StandReacquirer.reacquire(
                early.ref, 2.0, world, 8_000, 250, new FakeClock());

        assertTrue(result.found(), result.failure);
        assertEquals(1, world.approaches);                       // navigated toward the saved grid/offset first
        assertNotSame(early.value, result.stand.value);          // a fresh live handle, not the stale sweep handle
        assertTrue(result.stand.value.startsWith("s0#"));
    }

    @Test
    void aStandThatLoadsLateIsWaitedForWithinTheBound() throws Exception {
        FakeWorld world = new FakeWorld(grid(10), 200);
        world.hiddenReads = 3;
        FakeClock clock = new FakeClock();

        StandReacquirer.Result<String> result = StandReacquirer.reacquire(
                world.stands.get(4).ref, 2.0, world, 8_000, 250, clock);

        assertTrue(result.found());
        assertEquals(750, clock.now);
    }

    @Test
    void aStandThatNeverLoadsFailsAfterTheBoundedWait() throws Exception {
        FakeWorld world = new FakeWorld(grid(10), 200);
        world.hiddenReads = Integer.MAX_VALUE;
        FakeClock clock = new FakeClock();

        StandReacquirer.Result<String> result = StandReacquirer.reacquire(
                world.stands.get(4).ref, 2.0, world, 8_000, 250, clock);

        assertFalse(result.found());
        assertTrue(result.failure.contains("no stand loaded"));
        assertTrue(clock.now >= 8_000 && clock.now < 8_300);
    }

    @Test
    void failedNavigationIsReportedWithoutWaitingForObjects() throws Exception {
        FakeWorld world = new FakeWorld(grid(10), 200);
        world.navigationFails = true;
        FakeClock clock = new FakeClock();

        StandReacquirer.Result<String> result = StandReacquirer.reacquire(
                world.stands.get(0).ref, 2.0, world, 8_000, 250, clock);

        assertFalse(result.found());
        assertTrue(result.failure.contains("could not navigate"));
        assertEquals(0, clock.now);
    }

    @Test
    void twoStandsWithinToleranceAreAmbiguousAndNeverPicked() throws Exception {
        List<StandMatcher.Detected<String>> stands = new ArrayList<>();
        stands.add(at("a", "7001", 100, 100));
        stands.add(at("b", "7001", 101, 100));
        FakeWorld world = new FakeWorld(stands, 500);

        StandReacquirer.Result<String> result = StandReacquirer.reacquire(
                stands.get(0).ref, 2.0, world, 8_000, 250, new FakeClock());

        assertFalse(result.found());
        assertTrue(result.failure.contains("ambiguous"));
    }

    @Test
    void aNeighbouringStandOutsideToleranceDoesNotCountAsTheTarget() throws Exception {
        List<StandMatcher.Detected<String>> stands = new ArrayList<>();
        stands.add(at("target", "7001", 100, 100));
        stands.add(at("neighbour", "7001", 112, 100));
        FakeWorld world = new FakeWorld(stands, 500);

        StandReacquirer.Result<String> result = StandReacquirer.reacquire(
                stands.get(0).ref, 2.0, world, 8_000, 250, new FakeClock());

        assertTrue(result.found());
        assertTrue(result.stand.value.startsWith("target#"));
    }

    @Test
    void interruptionIsPropagatedFromTheWaitLoop() {
        FakeWorld world = new FakeWorld(grid(10), 200);
        world.hiddenReads = Integer.MAX_VALUE;
        ShopboxReadiness.Clock interrupting = new ShopboxReadiness.Clock() {
            @Override public long now() { return 0; }
            @Override public void sleep(long millis) throws InterruptedException { throw new InterruptedException("cancelled"); }
        };

        assertThrows(InterruptedException.class, () -> StandReacquirer.reacquire(
                world.stands.get(0).ref, 2.0, world, 8_000, 250, interrupting));
    }
}
