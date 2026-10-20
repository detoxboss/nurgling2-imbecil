package nurgling.watch;

import haven.Coord;
import haven.Coord3f;
import haven.Gob;
import nurgling.NGameUI;
import nurgling.NUI;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;

import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures the scene of one specific session using the same render hook the Screenshot tool uses
 * ({@code MapView.delay2} + {@code GOut.getimage}), but without its upload window or any focus change.
 *
 * <p>The hook runs inside that session's own map draw, so a frame can only ever come from the
 * session that owns this provider. The catch is the converse: a session that is not being drawn
 * - every non-active session in a multi-session window - never runs the hook, so it is refused up
 * front rather than waited on. Rendering state (the window's GL environment) belongs to the one
 * on-screen session; see docs/village-watch.md.
 */
public final class MapScreenshotProvider implements ScreenshotProvider {
    private final NGameUI gui;

    public MapScreenshotProvider(NGameUI gui) {
        this.gui = gui;
    }

    @Override
    public String unavailableReason() {
        if (gui == null || gui.map == null || gui.ui == null)
            return "game view not ready";
        SessionManager sm = SessionManager.getInstance();
        SessionContext ctx = sm.findByUI(gui.ui);
        if (ctx != null && ctx.isHeadless())
            return "this session is in the background and is not rendered (use a separate client window for screenshots)";
        NUI active = sm.getActiveUI();
        if (active != null && active != gui.ui)
            return "this session is not the one being rendered";
        return null;
    }

    @Override
    public Result capture(long timeoutMs) throws InterruptedException {
        String why = unavailableReason();
        if (why != null)
            return Result.failed(why);
        CompletableFuture<BufferedImage> frame = new CompletableFuture<>();
        AtomicBoolean abandoned = new AtomicBoolean();
        try {
            gui.map.delay2(g -> g.getimage(Coord.z, g.sz(), img -> {
                // A request that outlived its wait must not deliver a stale frame later.
                if (!abandoned.get())
                    frame.complete(img);
            }));
        } catch (RuntimeException e) {
            return Result.failed("could not schedule capture: " + e.getClass().getSimpleName());
        }
        try {
            BufferedImage img = frame.get(timeoutMs, TimeUnit.MILLISECONDS);
            return Result.ok(ScreenshotEncoder.encode(img));
        } catch (TimeoutException e) {
            return Result.failed("no frame was drawn within " + (timeoutMs / 1000) + "s (window minimized, or the scene is still loading)");
        } catch (ExecutionException | java.io.IOException e) {
            return Result.failed("could not encode frame: " + e.getClass().getSimpleName());
        } finally {
            abandoned.set(true);
        }
    }

    /**
     * Whether the gob is inside the current view; null when that cannot be told. The camera is never
     * moved to bring a threat into frame, so this is reported with the alert instead.
     */
    @Override
    public Boolean inFrame(long gobId) {
        try {
            if (gui == null || gui.map == null || gui.map.glob == null)
                return null;
            Gob gob = gui.map.glob.oc.getgob(gobId);
            if (gob == null || gob.rc == null)
                return null;
            Coord3f sc = gui.map.screenxf(gob.rc);
            if (sc == null)
                return null;
            return sc.x >= 0 && sc.y >= 0 && sc.x < gui.map.sz.x && sc.y < gui.map.sz.y;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
