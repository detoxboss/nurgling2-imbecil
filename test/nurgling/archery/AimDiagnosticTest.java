package nurgling.archery;

import haven.Coord;
import haven.Coord2d;
import nurgling.archery.AimDiagnostic.Arrow;
import nurgling.archery.AimDiagnostic.Config;
import nurgling.archery.AimDiagnostic.Method;
import nurgling.archery.AimDiagnostic.Restart;
import nurgling.archery.AimDiagnostic.State;
import nurgling.archery.AimDiagnostic.Trigger;
import nurgling.archery.AimDiagnostic.Verdict;
import nurgling.archery.AimProtocol.Sender;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AimDiagnosticTest {
    private static final String SHOOT_CURSOR = "gfx/hud/curs/test-aim";
    private static final Coord2d B = Coord2d.of(500, 500);

    /** Manual clock and scheduler; sent messages are recorded and echoed back like the real UI does. */
    static final class FakeEnv implements AimDiagnostic.Env {
        long now = 0;
        String cursor = AimDiagnostic.DEFAULT_CURSOR;
        final Map<Long, Coord2d> gobs = new HashMap<>();
        final Set<Long> relations = new HashSet<>();
        final List<Object[]> sent = new ArrayList<>();
        final List<Sender> sentTo = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        String status;
        boolean widgetsPresent = true;
        AimDiagnostic diag;
        private final List<long[]> due = new ArrayList<>();
        private final List<Runnable> tasks = new ArrayList<>();

        public long nowMs() { return now; }

        public AimDiagnostic.Cancel schedule(Runnable r, long delayMs) {
            long[] at = {now + delayMs, 0};
            due.add(at);
            tasks.add(r);
            return () -> at[1] = 1;
        }

        public void runLocked(Runnable r) { r.run(); }

        public AimDiagnostic.Sample sample() { return new AimDiagnostic.Sample(Coord2d.of(0, 0), 0, "idle"); }

        public Coord2d gobPosition(long id) { return gobs.get(id); }

        public boolean hasRelation(long id) { return relations.contains(id); }

        public String cursor() { return cursor; }

        public boolean send(Sender to, String msg, Object[] args) {
            if (!widgetsPresent)
                return false;
            sent.add(args);
            sentTo.add(to);
            diag.onOutgoing(to, msg, args);
            return true;
        }

        public void log(String line) { lines.add(line); }

        public void status(String text) { status = text; }

        /** Advances time, running due timers in order (including ones they schedule). */
        void advance(long ms) {
            long end = now + ms;
            while (true) {
                int best = -1;
                for (int i = 0; i < due.size(); i++)
                    if (due.get(i)[0] <= end && (best < 0 || due.get(i)[0] < due.get(best)[0]))
                        best = i;
                if (best < 0)
                    break;
                long[] at = due.remove(best);
                Runnable r = tasks.remove(best);
                now = Math.max(now, at[0]);
                if (at[1] == 0)
                    r.run();
            }
            now = end;
        }

        boolean logged(String s) {
            for (String l : lines)
                if (l.contains(s))
                    return true;
            return false;
        }
    }

    private static FakeEnv env() {
        FakeEnv e = new FakeEnv();
        e.diag = new AimDiagnostic(e);
        e.diag.setEnabled(true);
        return e;
    }

    private static Config ground(Trigger t, long delay, int pct) {
        return new Config(Method.GROUND, t, delay, pct, true, true, B, -1, -1, "");
    }

    private static void userShot(FakeEnv e) {
        e.diag.onOutgoing(Sender.MENU, "act", new Object[] {"test-act"});
        e.cursor = SHOOT_CURSOR;
        e.diag.onOutgoing(Sender.MAP, "click", new Object[] {Coord.of(5, 5), Coord.of(100, 100), 1, 0});
    }

    @Test
    void disabled_by_default_and_cannot_arm() {
        FakeEnv e = new FakeEnv();
        e.diag = new AimDiagnostic(e);
        assertFalse(e.diag.enabled());
        assertNotNull(e.diag.arm(ground(Trigger.DELAY, 500, 50)));
        assertEquals(State.IDLE, e.diag.state());
    }

    @Test
    void sends_exactly_one_correction_then_disarms() {
        FakeEnv e = env();
        assertNull(e.diag.arm(ground(Trigger.DELAY, 500, 50)));
        userShot(e);
        assertEquals(State.DRAWING, e.diag.state());
        e.diag.onProgress(10.0);
        e.advance(499);
        assertTrue(e.sent.isEmpty());
        e.advance(1);
        assertEquals(1, e.sent.size());
        assertEquals(Sender.MAP, e.sentTo.get(0));
        assertEquals(State.OBSERVING, e.diag.state());
        // More progress and user clicks during observation never trigger a second send.
        e.diag.onProgress(80.0);
        e.diag.onOutgoing(Sender.MAP, "click", new Object[] {Coord.z, Coord.of(1, 1), 1, 0});
        e.advance(AimDiagnostic.OBSERVE_MS + 1000);
        assertEquals(1, e.sent.size());
        assertEquals(State.IDLE, e.diag.state());
        // A later shot is ignored until the user arms again.
        userShot(e);
        e.advance(5000);
        assertEquals(1, e.sent.size());
        assertTrue(e.logged("wire: correction"));
    }

    @Test
    void default_cursor_click_is_not_taken_as_the_shot() {
        FakeEnv e = env();
        e.diag.arm(ground(Trigger.DELAY, 0, 50));
        e.diag.onOutgoing(Sender.MAP, "click", new Object[] {Coord.z, Coord.of(1, 1), 1, 0});
        assertEquals(State.ARMED, e.diag.state());
        e.advance(1000);
        assertTrue(e.sent.isEmpty());
    }

    @Test
    void cursor_filter_must_match() {
        FakeEnv e = env();
        e.diag.arm(new Config(Method.GROUND, Trigger.DELAY, 0, 50, false, true, B, -1, -1, "bow"));
        userShot(e);
        assertEquals(State.ARMED, e.diag.state());
    }

    @Test
    void no_send_when_draw_is_not_confirmed() {
        FakeEnv e = env();
        e.diag.arm(ground(Trigger.DELAY, 500, 50));
        userShot(e);
        e.advance(600);
        assertTrue(e.sent.isEmpty());
        assertEquals(State.IDLE, e.diag.state());
        assertEquals(Verdict.NOT_TESTED, AimDiagnostic.verdict(e.diag.lastResult()));
    }

    @Test
    void progress_end_before_trigger_aborts() {
        FakeEnv e = env();
        e.diag.arm(ground(Trigger.DELAY, 1500, 50));
        userShot(e);
        e.diag.onProgress(20.0);
        e.diag.onProgress(null);
        e.advance(2000);
        assertTrue(e.sent.isEmpty());
        assertEquals(State.IDLE, e.diag.state());
    }

    @Test
    void progress_trigger_fires_once_at_threshold() {
        FakeEnv e = env();
        e.diag.arm(ground(Trigger.PROGRESS, 0, 50));
        userShot(e);
        e.diag.onProgress(30.0);
        e.advance(10);
        assertTrue(e.sent.isEmpty());
        e.diag.onProgress(55.0);
        e.diag.onProgress(60.0);
        e.advance(10);
        assertEquals(1, e.sent.size());
    }

    @Test
    void progress_trigger_times_out_without_sending() {
        FakeEnv e = env();
        e.diag.arm(ground(Trigger.PROGRESS, 0, 90));
        userShot(e);
        e.diag.onProgress(30.0);
        e.advance(AimDiagnostic.DRAW_TIMEOUT_MS + 1);
        assertTrue(e.sent.isEmpty());
        assertEquals(State.IDLE, e.diag.state());
    }

    @Test
    void progress_reset_is_recorded() {
        FakeEnv e = env();
        e.diag.arm(ground(Trigger.DELAY, 200, 50));
        userShot(e);
        e.diag.onProgress(10.0);
        e.advance(200);
        e.diag.onProgress(40.0);
        e.diag.onProgress(5.0);
        assertEquals(1, e.diag.lastResult().progressResets);
    }

    @Test
    void cancel_during_draw_sends_nothing() {
        FakeEnv e = env();
        e.diag.arm(ground(Trigger.DELAY, 500, 50));
        userShot(e);
        e.diag.onProgress(10.0);
        e.diag.cancel("test");
        e.advance(5000);
        assertTrue(e.sent.isEmpty());
        assertEquals(State.IDLE, e.diag.state());
    }

    @Test
    void session_end_cancels_and_disables() {
        FakeEnv e = env();
        e.diag.arm(ground(Trigger.DELAY, 500, 50));
        userShot(e);
        e.diag.onProgress(10.0);
        e.diag.onSessionEnd("gone");
        e.advance(5000);
        assertTrue(e.sent.isEmpty());
        assertFalse(e.diag.enabled());
        assertNotNull(e.diag.arm(ground(Trigger.DELAY, 500, 50)));
    }

    @Test
    void arm_times_out_without_a_shot() {
        FakeEnv e = env();
        e.diag.arm(ground(Trigger.DELAY, 500, 50));
        e.advance(AimDiagnostic.ARM_TIMEOUT_MS + 1);
        assertEquals(State.IDLE, e.diag.state());
    }

    @Test
    void risky_methods_need_acknowledgement_and_a_target() {
        FakeEnv e = env();
        assertNotNull(e.diag.arm(new Config(Method.GROUND, Trigger.DELAY, 500, 50, true, false, B, -1, -1, "")));
        assertNotNull(e.diag.arm(new Config(Method.GROUND, Trigger.DELAY, 500, 50, true, true, null, -1, -1, "")));
        assertNotNull(e.diag.arm(new Config(Method.GOB, Trigger.DELAY, 500, 50, true, true, null, 9, -1, "")));
        assertEquals(State.IDLE, e.diag.state());
    }

    @Test
    void gob_removed_during_draw_aborts() {
        FakeEnv e = env();
        e.gobs.put(9L, Coord2d.of(300, 300));
        assertNull(e.diag.arm(new Config(Method.GOB, Trigger.DELAY, 500, 50, true, true, null, 9, -1, "")));
        userShot(e);
        e.diag.onProgress(10.0);
        e.gobs.remove(9L);
        e.advance(600);
        assertTrue(e.sent.isEmpty());
        assertTrue(e.diag.lastResult().abortReason.contains("gone"));
    }

    @Test
    void gob_correction_uses_the_latest_position() {
        FakeEnv e = env();
        e.gobs.put(9L, Coord2d.of(300, 300));
        e.diag.arm(new Config(Method.GOB, Trigger.DELAY, 500, 50, true, true, null, 9, -1, ""));
        userShot(e);
        e.diag.onProgress(10.0);
        e.gobs.put(9L, Coord2d.of(400, 300));
        e.advance(500);
        assertEquals(1, e.sent.size());
        assertEquals(9, AimProtocol.mapGobId(e.sent.get(0)));
        assertEquals(Coord2d.of(400, 300).floor(haven.OCache.posres), e.sent.get(0)[1]);
    }

    @Test
    void portrait_method_needs_an_existing_relation_and_never_creates_one() {
        FakeEnv e = env();
        assertNotNull(e.diag.arm(new Config(Method.FIGHT_PORTRAIT, Trigger.DELAY, 500, 50, true, false, null, -1, 42, "")));
        e.relations.add(42L);
        assertNull(e.diag.arm(new Config(Method.FIGHT_PORTRAIT, Trigger.DELAY, 500, 50, true, false, null, -1, 42, "")));
        userShot(e);
        e.diag.onProgress(10.0);
        e.relations.clear();
        e.advance(600);
        assertTrue(e.sent.isEmpty());

        e.relations.add(42L);
        e.diag.arm(new Config(Method.FIGHT_PORTRAIT, Trigger.DELAY, 500, 50, true, false, null, -1, 42, ""));
        userShot(e);
        e.diag.onProgress(10.0);
        e.advance(600);
        assertEquals(1, e.sent.size());
        assertEquals(Sender.FIGHTVIEW, e.sentTo.get(0));
        assertEquals(42, e.sent.get(0)[0]);
    }

    @Test
    void missing_sender_widget_aborts() {
        FakeEnv e = env();
        e.widgetsPresent = false;
        e.diag.arm(ground(Trigger.DELAY, 0, 50));
        userShot(e);
        e.diag.onProgress(1.0);
        e.advance(10);
        assertFalse(e.diag.lastResult().correctionSent());
    }

    @Test
    void separate_sessions_do_not_share_state() {
        FakeEnv a = env(), b = env();
        a.diag.arm(ground(Trigger.DELAY, 100, 50));
        userShot(b);
        assertEquals(State.ARMED, a.diag.state());
        assertEquals(State.IDLE, b.diag.state());
    }

    @Test
    void verdict_needs_the_players_observation() {
        FakeEnv e = env();
        e.diag.arm(ground(Trigger.DELAY, 0, 50));
        userShot(e);
        e.diag.onProgress(1.0);
        e.advance(AimDiagnostic.OBSERVE_MS + 100);
        AimDiagnostic.Result r = e.diag.lastResult();
        assertTrue(r.correctionSent());
        assertEquals(Verdict.AWAITING_OBSERVATION, AimDiagnostic.verdict(r));
        e.diag.recordObservation(Arrow.TOWARD_CORRECTION, null);
        assertEquals(Verdict.AWAITING_OBSERVATION, AimDiagnostic.verdict(r));
        e.diag.recordObservation(null, Restart.YES);
        assertEquals(Verdict.NOT_REAIMED, AimDiagnostic.verdict(r));
        e.diag.recordObservation(null, Restart.NO);
        assertEquals(Verdict.REAIM_OBSERVED, AimDiagnostic.verdict(r));
        e.diag.recordObservation(Arrow.NOT_SEEN, null);
        assertEquals(Verdict.INCONCLUSIVE, AimDiagnostic.verdict(r));
    }

    @Test
    void observation_without_a_sent_correction_is_never_success() {
        FakeEnv e = env();
        e.diag.arm(ground(Trigger.DELAY, 500, 50));
        userShot(e);
        e.advance(600);
        e.diag.recordObservation(Arrow.TOWARD_CORRECTION, Restart.NO);
        assertEquals(Verdict.NOT_TESTED, AimDiagnostic.verdict(e.diag.lastResult()));
    }

    @Test
    void delay_longer_than_a_draw_is_refused() {
        FakeEnv e = env();
        assertNotNull(e.diag.arm(ground(Trigger.DELAY, 5001, 50)));
        assertEquals(State.IDLE, e.diag.state());
        assertNull(e.diag.arm(ground(Trigger.DELAY, AimDiagnostic.MAX_DELAY_MS, 50)));
    }

    @Test
    void angle_diff_wraps() {
        assertEquals(0.2, AimDiagnostic.angleDiff(0.1, 2 * Math.PI - 0.1), 1e-9);
        assertEquals(-0.2, AimDiagnostic.angleDiff(2 * Math.PI - 0.1, 0.1), 1e-9);
    }
}
