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
        return rendererReason(haven.iosys.tk.BridgeToolkit.of(gui.ui) != null, haven.PresentLoop.wanted());
    }

    /**
     * Why this render mode cannot give a trustworthy frame, or null when it can. With the smooth
     * renderer the 3D world is drawn by the present thread, not inside the map's UI-thread draw where
     * the {@code delay2} hook runs, so the hook would read back only the interface layer; with the
     * frame bridge the map draws into a separate, margin-padded scene image. Neither is verified to
     * give a real picture, and a wrong picture is worse than a text-only alert, so both are refused.
     */
    static String rendererReason(boolean frameBridge, boolean smoothRenderer) {
        if (frameBridge)
            return "the frame bridge is on (Graphics settings); screenshots are not supported with it yet";
        if (smoothRenderer)
            return "the smooth renderer is on (Graphics settings); screenshots are not supported with it yet";
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
            gui.map.delay2(g -> {
                // Re-checked inside the draw, where the mode actually in use for this frame is known
                // (the smooth renderer can be wanted but have fallen back, or start after the check).
                String mode = rendererReason(haven.iosys.tk.BridgeToolkit.of(gui.ui) != null,
                        haven.PresentLoop.of(gui.ui) != null);
                if (mode != null) {
                    frame.completeExceptionally(new IllegalStateException(mode));
                    return;
                }
                g.getimage(Coord.z, g.sz(), img -> {
                    // A request that outlived its wait must not deliver a stale frame later.
                    if (!abandoned.get())
                        frame.complete(img);
                });
            });
        } catch (RuntimeException e) {
            return Result.failed("could not schedule capture: " + e.getClass().getSimpleName());
        }
        try {
            BufferedImage img = frame.get(timeoutMs, TimeUnit.MILLISECONDS);
            return Result.ok(ScreenshotEncoder.encode(img));
        } catch (TimeoutException e) {
            return Result.failed("no frame was drawn within " + (timeoutMs / 1000) + "s (window minimized, or the scene is still loading)");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IllegalStateException)
                return Result.failed(e.getCause().getMessage());
            return Result.failed("could not encode frame: " + e.getClass().getSimpleName());
        } catch (java.io.IOException e) {
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
