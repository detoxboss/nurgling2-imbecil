package nurgling.archery;

import haven.Coord2d;

import java.util.ArrayList;
import java.util.List;

/**
 * One session's archery re-aim experiment: watches for the next manually started shot, sends exactly
 * one preconfigured correction message during the draw, records what the client could observe around
 * it, and disarms. It never activates Shoot, never clicks to start a shot and never retries.
 *
 * All game access goes through {@link Env}, so the state machine runs without a client in tests. The
 * methods are synchronized; callers that also need the UI lock must take it first (see
 * {@link Env#runLocked}), never while holding this object's lock.
 *
 * Nothing recorded here proves the arrow changed course: that is only known from the player's own
 * observation, recorded with {@link #recordObservation} and reported separately (see {@link #verdict}).
 */
public final class AimDiagnostic {
    public enum Method {
        /** Map ground click toward a fixed point B. */
        GROUND("A: ground click at point B"),
        /** Map click naming a gob (same shape as a minimap icon click). */
        GOB("B: gob click on selected gob"),
        /** Fightview portrait click on an existing combat relation. */
        FIGHT_PORTRAIT("D: combat portrait click");

        public final String label;

        Method(String label) {
            this.label = label;
        }
    }

    public enum Trigger { DELAY, PROGRESS }

    public enum State { IDLE, ARMED, DRAWING, OBSERVING }

    public enum Arrow { NOT_RECORDED, TOWARD_ORIGINAL, TOWARD_CORRECTION, ELSEWHERE, NOT_SEEN }

    public enum Restart { NOT_RECORDED, NO, YES, UNSURE }

    public enum Verdict {
        /** No test has produced a result to judge. */
        NONE,
        /** No correction went out, so nothing about re-aiming was tested. */
        NOT_TESTED,
        /** A correction went out but the player has not recorded what the arrow did. */
        AWAITING_OBSERVATION,
        /** Player saw the arrow fly toward the correction and the draw did not restart. */
        REAIM_OBSERVED,
        /** Player saw the arrow fly elsewhere, or the draw restarted. */
        NOT_REAIMED,
        /** The observation does not settle it (not seen, unsure about a restart). */
        INCONCLUSIVE
    }

    public static final String DEFAULT_CURSOR = "gfx/hud/curs/arw";
    /** How long an armed test waits for the shot click before giving up. */
    public static final long ARM_TIMEOUT_MS = 60_000;
    /** How long after the shot click a progress-triggered or draw-confirmed test may wait. */
    public static final long DRAW_TIMEOUT_MS = 6_000;
    /** Longest correction delay accepted; a measured draw lasts about 0.9 s from the click. */
    public static final long MAX_DELAY_MS = 2_000;
    /** Measured: the first "prog" arrives 157-223 ms after the shot click. */
    public static final long PROG_START_MS = 250;
    /** How long to keep observing after the correction went out. */
    public static final long OBSERVE_MS = 3_000;
    /** Spacing of the bounded orientation/pose samples. */
    public static final long SAMPLE_MS = 100;
    /** Facing changes smaller than this (radians, about 1 degree) are not logged. */
    static final double ANGLE_EPS = Math.toRadians(1);

    /** Frozen settings of one armed test. */
    public static final class Config {
        public final Method method;
        public final Trigger trigger;
        public final long delayMs;
        public final int progressPct;
        public final boolean requireDraw;
        public final boolean riskAcknowledged;
        public final Coord2d point;
        public final long gobId;
        public final long relationGobId;
        public final String cursorFilter;

        public Config(Method method, Trigger trigger, long delayMs, int progressPct, boolean requireDraw,
                      boolean riskAcknowledged, Coord2d point, long gobId, long relationGobId, String cursorFilter) {
            this.method = method;
            this.trigger = trigger;
            this.delayMs = delayMs;
            this.progressPct = progressPct;
            this.requireDraw = requireDraw;
            this.riskAcknowledged = riskAcknowledged;
            this.point = point;
            this.gobId = gobId;
            this.relationGobId = relationGobId;
            this.cursorFilter = (cursorFilter == null) ? "" : cursorFilter.trim();
        }

        public String describe() {
            String trig = (trigger == Trigger.DELAY) ? ("delay " + delayMs + " ms") : ("progress >= " + progressPct + "%");
            String tgt;
            switch (method) {
                case GROUND: tgt = "point " + point; break;
                case GOB: tgt = "gob " + gobId; break;
                default: tgt = "relation gob " + relationGobId; break;
            }
            return method.label + ", " + tgt + ", trigger " + trig + (requireDraw ? ", draw required" : ", draw not required")
                + ", cursor filter " + (cursorFilter.isEmpty() ? "(any non-default)" : "\"" + cursorFilter + "\"");
        }
    }

    /** Player state at one instant; any field may be unknown. */
    public static final class Sample {
        public final Coord2d rc;
        public final double angle;
        public final String pose;

        public Sample(Coord2d rc, double angle, String pose) {
            this.rc = rc;
            this.angle = angle;
            this.pose = pose;
        }

        public String toString() {
            return String.format("rc=%s a=%.1fdeg pose=%s", rc, Math.toDegrees(angle), pose);
        }
    }

    public interface Cancel {
        void cancel();
    }

    /** Everything the diagnostic needs from its owning session. */
    public interface Env {
        /** Monotonic milliseconds. */
        long nowMs();

        Cancel schedule(Runnable r, long delayMs);

        /** Runs {@code r} holding the owning UI's lock. */
        void runLocked(Runnable r);

        /** The player's state, or null when there is no player gob. */
        Sample sample();

        /** Current position of gob {@code id}, or null if it is gone. */
        Coord2d gobPosition(long id);

        /** Whether the Fightview currently lists a relation with gob {@code id}. */
        boolean hasRelation(long id);

        /** Resource name of the current cursor. */
        String cursor();

        /** Sends from the given widget; false if that widget is missing. */
        boolean send(AimProtocol.Sender to, String msg, Object[] args);

        void log(String line);

        void status(String text);
    }

    /** What happened in the last test. Automatic fields are filled by the diagnostic only. */
    public static final class Result {
        public final Config config;
        public String shotAction;
        public String shotCursor;
        public String shotClick;
        public boolean drawConfirmed;
        public double lastProgress = -1;
        public int progressResets;
        public boolean progressEnded;
        public String correction;
        public String abortReason;
        public Sample before, after;
        public final List<String> poses = new ArrayList<>();
        public Arrow arrow = Arrow.NOT_RECORDED;
        public Restart restart = Restart.NOT_RECORDED;

        Result(Config config) {
            this.config = config;
        }

        public boolean correctionSent() {
            return correction != null;
        }
    }

    private final Env env;
    private boolean enabled = false;
    private State state = State.IDLE;
    private Config cfg;
    private Result result;
    /** Bumped by every arm/cancel/finish, so timers of an earlier test fall through. */
    private int gen = 0;
    private long t0;
    private long shotAt;
    private boolean sending = false;
    private Cancel armTimeout, trigger, sampler;
    private Sample lastSample;
    private String lastAct;
    private long lastActAt;

    public AimDiagnostic(Env env) {
        this.env = env;
    }

    Env env() {
        return env;
    }

    public synchronized boolean enabled() {
        return enabled;
    }

    public synchronized void setEnabled(boolean on) {
        if (!on && state != State.IDLE)
            cancel("diagnostic disabled");
        if (enabled != on)
            env.log("diagnostic " + (on ? "enabled" : "disabled"));
        enabled = on;
        if (!on)
            env.status("Disabled.");
    }

    public synchronized State state() {
        return state;
    }

    public synchronized Result lastResult() {
        return result;
    }

    /** Arms one test. Returns null on success, or why it could not arm. */
    public synchronized String arm(Config c) {
        if (!enabled)
            return "Enable diagnostic mode first.";
        if (state != State.IDLE)
            return "A test is already " + state.name().toLowerCase() + "; cancel it first.";
        String err = validate(c);
        if (err != null)
            return err;
        gen++;
        cfg = c;
        result = new Result(c);
        lastAct = null;
        t0 = env.nowMs();
        state = State.ARMED;
        env.log("=== ARMED: " + c.describe());
        env.log("cursor at arm: " + env.cursor() + "; player " + env.sample());
        env.status("Armed (" + (c.trigger == Trigger.DELAY ? "delay " + c.delayMs + " ms" : "progress " + c.progressPct + "%")
            + "). Activate Shoot and click the original spot.");
        int g = gen;
        armTimeout = env.schedule(() -> timeout(g, "no shot click within " + ARM_TIMEOUT_MS / 1000 + " s"), ARM_TIMEOUT_MS);
        return null;
    }

    /** Why {@code c} cannot run right now, or null. Checked at arm time and again at send time. */
    String validate(Config c) {
        if (c == null || c.method == null || c.trigger == null)
            return "Incomplete settings.";
        if (c.trigger == Trigger.DELAY && (c.delayMs < 0 || c.delayMs > MAX_DELAY_MS))
            return "Delay " + c.delayMs + " ms is outside 0.." + MAX_DELAY_MS + " ms (a draw lasts about 900 ms). Clear the field and retype it.";
        if (c.trigger == Trigger.DELAY && c.requireDraw && c.delayMs < PROG_START_MS)
            return "Delay " + c.delayMs + " ms is before the progress ring appears (~160-220 ms); untick \"Require draw progress\" to test that early.";
        if (c.trigger == Trigger.PROGRESS && (c.progressPct < 1 || c.progressPct > 99))
            return "Progress threshold must be 1..99 %.";
        switch (c.method) {
            case GROUND:
                if (c.point == null)
                    return "Method A needs point B; set it first.";
                if (!c.riskAcknowledged)
                    return "Method A can be read as a walk order; tick the risk box to arm it.";
                return null;
            case GOB:
                if (c.gobId <= 0)
                    return "Method B needs a selected gob.";
                if (env.gobPosition(c.gobId) == null)
                    return "Selected gob " + c.gobId + " is gone.";
                if (!c.riskAcknowledged)
                    return "Method B can be read as walking to or using the gob; tick the risk box to arm it.";
                return null;
            case FIGHT_PORTRAIT:
                if (c.relationGobId <= 0)
                    return "Method D needs an existing combat relation; none is selected.";
                if (!env.hasRelation(c.relationGobId))
                    return "No combat relation with gob " + c.relationGobId + " (the diagnostic never starts one).";
                return null;
            default:
                return "Unknown method.";
        }
    }

    public synchronized void cancel(String reason) {
        if (state == State.IDLE)
            return;
        if (result != null && result.abortReason == null && !result.correctionSent())
            result.abortReason = "cancelled: " + reason;
        env.log("CANCELLED (" + state + "): " + reason);
        finish("Cancelled: " + reason);
    }

    /* ------------------------------------------------------------------ events */

    /** A message the session sent. Runs on the sender's thread. */
    public synchronized void onOutgoing(AimProtocol.Sender from, String msg, Object[] args) {
        if (state == State.IDLE)
            return;
        AimProtocol.Kind k = AimProtocol.classify(from, msg, args);
        if (k == AimProtocol.Kind.OTHER)
            return;
        String line = k + " " + msg + " " + AimProtocol.fmt(args);
        if (sending) {
            env.log("wire: correction " + line);
            return;
        }
        switch (k) {
            case MENU_ACT:
            case BELT_USE:
                lastAct = line;
                lastActAt = env.nowMs();
                env.log((state == State.ARMED ? "action: " : "action DURING TEST (user): ") + line);
                return;
            case MAP_GROUND_CLICK:
            case MAP_GOB_CLICK:
                if (state == State.ARMED)
                    maybeShot(line, args);
                else
                    env.log("extra map click DURING TEST (user): " + line + "; cursor " + env.cursor());
                return;
            case FIGHT_PORTRAIT_CLICK:
                env.log("portrait click (user): " + line);
                return;
            default:
        }
    }

    private void maybeShot(String line, Object[] args) {
        String curs = env.cursor();
        if (AimProtocol.mapButton(args) != 1) {
            env.log("ignored (not a left click): " + line);
            return;
        }
        if (curs == null || curs.equals(DEFAULT_CURSOR)) {
            env.log("ignored (default cursor, an ordinary walk/use click): " + line);
            return;
        }
        if (!cfg.cursorFilter.isEmpty() && !curs.contains(cfg.cursorFilter)) {
            env.log("ignored (cursor " + curs + " does not match filter \"" + cfg.cursorFilter + "\"): " + line);
            return;
        }
        shotAt = env.nowMs();
        result.shotClick = line;
        result.shotCursor = curs;
        result.shotAction = (lastAct != null) ? lastAct + " (" + (shotAt - lastActAt) + " ms before click)" : "(not seen since arming)";
        result.before = env.sample();
        lastSample = result.before;
        if (result.before != null && result.before.pose != null)
            result.poses.add(result.before.pose);
        state = State.DRAWING;
        stop(armTimeout);
        env.log("SHOT CLICK at +" + (shotAt - t0) + " ms: " + line);
        env.log("  cursor " + curs + (cfg.cursorFilter.isEmpty() ? " (Shoot not positively identified: no cursor filter set)" : " (matches filter)"));
        env.log("  last action " + result.shotAction);
        env.log("  player " + result.before);
        int g = gen;
        sampler = env.schedule(() -> sampleTick(g), SAMPLE_MS);
        if (cfg.trigger == Trigger.DELAY) {
            trigger = env.schedule(() -> env.runLocked(() -> fire(g, "delay " + cfg.delayMs + " ms")), cfg.delayMs);
        } else {
            trigger = env.schedule(() -> timeout(g, "progress never reached " + cfg.progressPct + "% within " + DRAW_TIMEOUT_MS + " ms"), DRAW_TIMEOUT_MS);
        }
        env.status("Shot seen; correction pending (" + (cfg.trigger == Trigger.DELAY ? cfg.delayMs + " ms" : cfg.progressPct + "%") + ").");
    }

    /** The server's "prog" message to GameUI: {@code pct} in 0..100, or null when the indicator ends. */
    public synchronized void onProgress(Double pct) {
        if (state != State.DRAWING && state != State.OBSERVING)
            return;
        long t = env.nowMs() - shotAt;
        if (pct == null) {
            result.progressEnded = true;
            env.log("prog END at +" + t + " ms (indicator removed; release, interruption and completion all look alike here)");
            if (state == State.DRAWING) {
                result.abortReason = "progress ended before the correction was sent";
                env.log("ABORT: " + result.abortReason);
                finish("Draw ended before the correction; nothing sent.");
            }
            return;
        }
        if (!result.drawConfirmed) {
            result.drawConfirmed = true;
            env.log("prog START at +" + t + " ms: " + pct + "%");
        } else if (pct < result.lastProgress) {
            result.progressResets++;
            env.log("prog RESET at +" + t + " ms: " + result.lastProgress + "% -> " + pct + "% (possible draw restart)");
        } else {
            env.log("prog +" + t + " ms: " + pct + "%");
        }
        result.lastProgress = pct;
        if (state == State.DRAWING && cfg.trigger == Trigger.PROGRESS && pct >= cfg.progressPct) {
            int g = gen;
            stop(trigger);
            trigger = env.schedule(() -> env.runLocked(() -> fire(g, "progress " + pct + "%")), 0);
        }
    }

    /** The server changed the cursor. */
    public synchronized void onCursor(String res) {
        if (state != State.IDLE)
            env.log("cursor -> " + res + (state == State.DRAWING || state == State.OBSERVING ? " at +" + (env.nowMs() - shotAt) + " ms" : ""));
    }

    /** Any other server message worth seeing during a test, e.g. the Fightview's current-target change. */
    public synchronized void onIncoming(String what) {
        if (state != State.IDLE)
            env.log("incoming " + what + (state == State.ARMED ? "" : " at +" + (env.nowMs() - shotAt) + " ms"));
    }

    /** The owning session is going away. */
    public synchronized void onSessionEnd(String why) {
        cancel(why);
        enabled = false;
    }

    /* ------------------------------------------------------------------ timers */

    synchronized void fire(int g, String why) {
        if (g != gen || state != State.DRAWING)
            return;
        if (cfg.requireDraw && !result.drawConfirmed) {
            result.abortReason = "no progress indicator after the shot click, draw not confirmed";
            env.log("ABORT at " + why + ": " + result.abortReason);
            finish("No draw seen; nothing sent.");
            return;
        }
        String err = validate(cfg);
        if (err != null) {
            result.abortReason = "target no longer valid: " + err;
            env.log("ABORT at " + why + ": " + result.abortReason);
            finish("Target invalid; nothing sent.");
            return;
        }
        AimProtocol.Sender to;
        Object[] args;
        switch (cfg.method) {
            case GROUND:
                to = AimProtocol.Sender.MAP;
                args = AimProtocol.groundClick(cfg.point, 1);
                break;
            case GOB:
                to = AimProtocol.Sender.MAP;
                args = AimProtocol.gobClick(cfg.gobId, env.gobPosition(cfg.gobId), 1);
                break;
            default:
                to = AimProtocol.Sender.FIGHTVIEW;
                args = AimProtocol.portraitClick(cfg.relationGobId, 1);
                break;
        }
        Sample pre = env.sample();
        long t = env.nowMs() - shotAt;
        boolean ok;
        sending = true;
        try {
            ok = env.send(to, "click", args);
        } finally {
            sending = false;
        }
        if (!ok) {
            result.abortReason = "sender widget " + to + " missing";
            env.log("ABORT at " + why + ": " + result.abortReason);
            finish("Could not send; nothing sent.");
            return;
        }
        result.correction = to + " \"click\" " + AimProtocol.fmt(args) + " at +" + t + " ms (" + why + ")";
        env.log("CORRECTION SENT (once): " + result.correction);
        env.log("  player at send " + pre + "; cursor " + env.cursor());
        state = State.OBSERVING;
        int gg = gen;
        trigger = env.schedule(() -> endObservation(gg), OBSERVE_MS);
        env.status("Correction sent. Watch the arrow, then record what it did.");
    }

    private synchronized void timeout(int g, String why) {
        if (g != gen || state == State.IDLE)
            return;
        if (result.abortReason == null && !result.correctionSent())
            result.abortReason = "timeout: " + why;
        env.log("TIMEOUT (" + state + "): " + why);
        finish("Timed out: " + why);
    }

    private synchronized void endObservation(int g) {
        if (g != gen || state != State.OBSERVING)
            return;
        finish("Done. Record the arrow direction and whether the draw restarted.");
    }

    private synchronized void sampleTick(int g) {
        if (g != gen || (state != State.DRAWING && state != State.OBSERVING))
            return;
        Sample s = env.sample();
        if (s != null) {
            long t = env.nowMs() - shotAt;
            if (lastSample == null || Math.abs(angleDiff(s.angle, lastSample.angle)) > ANGLE_EPS)
                env.log(String.format("facing +%d ms: %.1f deg", t, Math.toDegrees(s.angle)));
            if (s.pose != null && (lastSample == null || !s.pose.equals(lastSample.pose))) {
                env.log("pose +" + t + " ms: " + s.pose);
                result.poses.add(s.pose);
            }
            lastSample = s;
        }
        sampler = env.schedule(() -> sampleTick(g), SAMPLE_MS);
    }

    static double angleDiff(double a, double b) {
        double d = (a - b) % (2 * Math.PI);
        if (d > Math.PI)
            d -= 2 * Math.PI;
        else if (d < -Math.PI)
            d += 2 * Math.PI;
        return d;
    }

    private void finish(String status) {
        if (result != null && state != State.IDLE) {
            result.after = env.sample();
            env.log("player at end " + result.after);
            env.log("=== END: " + summary(result));
        }
        gen++;
        stop(armTimeout);
        stop(trigger);
        stop(sampler);
        armTimeout = trigger = sampler = null;
        state = State.IDLE;
        env.status(status);
    }

    private static void stop(Cancel c) {
        if (c != null)
            c.cancel();
    }

    /* ------------------------------------------------------------------ results */

    /** The player's own observation of the last test's arrow. Returns false if there is nothing to annotate. */
    public synchronized boolean recordObservation(Arrow arrow, Restart restart) {
        if (result == null || state != State.IDLE)
            return false;
        if (arrow != null)
            result.arrow = arrow;
        if (restart != null)
            result.restart = restart;
        env.log("MANUAL OBSERVATION: arrow " + result.arrow + ", draw restarted " + result.restart + " -> verdict " + verdict(result));
        return true;
    }

    public static Verdict verdict(Result r) {
        if (r == null)
            return Verdict.NONE;
        if (!r.correctionSent())
            return Verdict.NOT_TESTED;
        if (r.arrow == Arrow.NOT_RECORDED || r.restart == Restart.NOT_RECORDED)
            return Verdict.AWAITING_OBSERVATION;
        if (r.arrow == Arrow.TOWARD_CORRECTION && r.restart == Restart.NO)
            return Verdict.REAIM_OBSERVED;
        if (r.restart == Restart.YES || r.arrow == Arrow.TOWARD_ORIGINAL || r.arrow == Arrow.ELSEWHERE)
            return Verdict.NOT_REAIMED;
        return Verdict.INCONCLUSIVE;
    }

    public static String summary(Result r) {
        if (r == null)
            return "no test yet";
        StringBuilder sb = new StringBuilder();
        sb.append("AUTOMATIC: ");
        sb.append(r.shotClick == null ? "no shot click" : "shot click seen");
        sb.append(r.drawConfirmed ? ", progress seen" : ", no progress seen");
        if (r.progressResets > 0)
            sb.append(", progress reset x").append(r.progressResets);
        if (r.progressEnded)
            sb.append(", progress ended");
        sb.append(r.correctionSent() ? ", correction sent" : ", correction NOT sent");
        if (r.abortReason != null)
            sb.append(" (").append(r.abortReason).append(')');
        if (r.before != null && r.after != null)
            sb.append(String.format(", facing %.1f -> %.1f deg", Math.toDegrees(r.before.angle), Math.toDegrees(r.after.angle)));
        sb.append(" | MANUAL: arrow ").append(r.arrow).append(", restart ").append(r.restart);
        sb.append(" | VERDICT: ").append(verdict(r));
        return sb.toString();
    }
}
