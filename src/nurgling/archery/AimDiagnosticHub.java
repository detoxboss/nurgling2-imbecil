package nurgling.archery;

import haven.Composite;
import haven.Coord2d;
import haven.Drawable;
import haven.Fightview;
import haven.GameUI;
import haven.Gob;
import haven.MapView;
import haven.MenuGrid;
import haven.UI;
import haven.Utils;
import haven.Widget;
import nurgling.NUtils;
import nurgling.plugins.UiEvents;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Routes {@link UiEvents} traffic to the {@link AimDiagnostic} of the session it came from. The hub is
 * registered with UiEvents only while at least one session has the diagnostic window open, so a client
 * that never opens it pays nothing. Events from a UI without a diagnostic are dropped, which is what
 * keeps one session's test from seeing another session's clicks.
 */
public final class AimDiagnosticHub implements UiEvents.Listener {
    private static final AimDiagnosticHub INSTANCE = new AimDiagnosticHub();
    private static ScheduledExecutorService exec = null;

    private final Map<UI, Entry> sessions = new IdentityHashMap<>();

    private AimDiagnosticHub() {}

    private static final class Entry {
        final GameUI gui;
        final AimDiagnostic diag;

        Entry(GameUI gui, AimDiagnostic diag) {
            this.gui = gui;
            this.diag = diag;
        }
    }

    /** Creates the diagnostic for {@code gui}'s session, or returns the existing one. */
    public static AimDiagnostic attach(GameUI gui, Consumer<String> view, Consumer<String> status) {
        synchronized (INSTANCE) {
            Entry e = INSTANCE.sessions.get(gui.ui);
            if (e != null && e.gui == gui)
                return e.diag;
            AimDiagnostic d = new AimDiagnostic(new SessionEnv(gui, view, status));
            INSTANCE.sessions.put(gui.ui, new Entry(gui, d));
            if (INSTANCE.sessions.size() == 1)
                UiEvents.addListener(INSTANCE);
            return d;
        }
    }

    /** Ends and forgets the diagnostic of {@code gui}'s session. */
    public static void detach(GameUI gui, String why) {
        Entry e;
        synchronized (INSTANCE) {
            e = INSTANCE.sessions.get(gui.ui);
            if (e == null || e.gui != gui)
                return;
            INSTANCE.sessions.remove(gui.ui);
            if (INSTANCE.sessions.isEmpty())
                UiEvents.removeListener(INSTANCE);
        }
        e.diag.onSessionEnd(why);
    }

    private AimDiagnostic diag(UI ui) {
        synchronized (this) {
            Entry e = sessions.get(ui);
            return (e == null) ? null : e.diag;
        }
    }

    static AimProtocol.Sender senderOf(Widget w) {
        if (w instanceof MapView)
            return AimProtocol.Sender.MAP;
        if (w instanceof Fightview)
            return AimProtocol.Sender.FIGHTVIEW;
        if (w instanceof MenuGrid)
            return AimProtocol.Sender.MENU;
        if (w instanceof GameUI)
            return AimProtocol.Sender.GAMEUI;
        return AimProtocol.Sender.OTHER;
    }

    @Override
    public void onOutgoing(UI ui, int id, Widget sender, String msg, Object[] args) {
        AimDiagnostic d = diag(ui);
        if (d != null)
            d.onOutgoing(senderOf(sender), msg, args);
    }

    @Override
    public void onUiMsg(UI ui, int id, Widget wdg, String msg, Object[] args) {
        AimDiagnostic d = diag(ui);
        if (d == null)
            return;
        if (wdg instanceof GameUI && "prog".equals(msg))
            d.onProgress((args != null && args.length > 0) ? Utils.dv(args[0]) : null);
        else if ("curs".equals(msg))
            d.onCursor(wdg.cursorRes);
        else if (wdg instanceof Fightview && "cur".equals(msg))
            d.onIncoming("Fightview \"cur\" " + AimProtocol.fmt(args) + " (server changed the current combat target)");
    }

    @Override
    public void onDestroyWidget(UI ui, int id, Widget wdg) {
        if (wdg instanceof GameUI)
            detach((GameUI) wdg, "game UI destroyed (logout, character change or disconnect)");
    }

    private static synchronized ScheduledExecutorService exec() {
        if (exec == null) {
            ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "Archery aim diagnostic");
                t.setDaemon(true);
                return t;
            });
            ex.setRemoveOnCancelPolicy(true);
            exec = ex;
        }
        return exec;
    }

    /** {@link AimDiagnostic.Env} bound to one GameUI; never resolves "the active session". */
    private static final class SessionEnv implements AimDiagnostic.Env {
        private final GameUI gui;
        private final Consumer<String> view, status;
        private final String label;
        private final Path file;

        SessionEnv(GameUI gui, Consumer<String> view, Consumer<String> status) {
            this.gui = gui;
            this.view = view;
            this.status = status;
            this.label = "session@" + Integer.toHexString(System.identityHashCode(gui.ui));
            this.file = NUtils.getDataFilePath("archery-aim-diag-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".log");
        }

        public long nowMs() {
            return System.nanoTime() / 1_000_000L;
        }

        public AimDiagnostic.Cancel schedule(Runnable r, long delayMs) {
            ScheduledFuture<?> f = exec().schedule(() -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    log("internal error: " + e);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
            return () -> f.cancel(false);
        }

        public void runLocked(Runnable r) {
            synchronized (gui.ui) {
                r.run();
            }
        }

        public AimDiagnostic.Sample sample() {
            try {
                MapView map = gui.map;
                Gob pl = (map == null) ? null : map.player();
                if (pl == null)
                    return null;
                String pose = null;
                Drawable dr = pl.getattr(Drawable.class);
                if (dr instanceof Composite)
                    pose = ((Composite) dr).current_pose;
                return new AimDiagnostic.Sample(pl.rc, pl.a, pose);
            } catch (RuntimeException e) {
                return null;
            }
        }

        public Coord2d gobPosition(long id) {
            try {
                MapView map = gui.map;
                Gob g = (map == null) ? null : map.glob.oc.getgob(id);
                return (g == null || g.removed) ? null : g.rc;
            } catch (RuntimeException e) {
                return null;
            }
        }

        public boolean hasRelation(long id) {
            try {
                Fightview fv = gui.fv;
                if (fv == null)
                    return false;
                for (Fightview.Relation rel : fv.lsrel)
                    if (rel.gobid == id)
                        return true;
            } catch (RuntimeException e) {
                /* The relation list belongs to the UI thread; a concurrent change reads as "not there". */
            }
            return false;
        }

        public String cursor() {
            return (gui.ui == null || gui.ui.root == null) ? null : gui.ui.root.cursorRes;
        }

        public boolean send(AimProtocol.Sender to, String msg, Object[] args) {
            Widget w = (to == AimProtocol.Sender.MAP) ? gui.map : (to == AimProtocol.Sender.FIGHTVIEW) ? gui.fv : null;
            if (w == null || w.ui == null)
                return false;
            w.wdgmsg(msg, args);
            return true;
        }

        public void log(String line) {
            String stamped = String.format("%s t=%d %s %s", new SimpleDateFormat("HH:mm:ss.SSS").format(new Date()), nowMs(), label, line);
            view.accept(stamped);
            try (PrintWriter out = new PrintWriter(new FileWriter(file.toFile(), true))) {
                out.println(stamped);
            } catch (IOException | RuntimeException e) {
                view.accept("(log file write failed: " + e.getMessage() + ")");
            }
        }

        public void status(String text) {
            status.accept(text);
        }

        String path() {
            return file.toString();
        }
    }

    /** Where {@code gui}'s diagnostic writes its log, or null. */
    public static String logPath(GameUI gui) {
        synchronized (INSTANCE) {
            Entry e = INSTANCE.sessions.get(gui.ui);
            return (e == null) ? null : ((SessionEnv) e.diag.env()).path();
        }
    }
}
