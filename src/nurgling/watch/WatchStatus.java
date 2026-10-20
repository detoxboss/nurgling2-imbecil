package nurgling.watch;

import nurgling.NGameUI;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * What one session's lookout is doing, for the settings window to display. Keyed by the owning
 * {@link NGameUI} and held weakly, so an entry disappears with its session and nothing is shared
 * between sessions. Holds display state only - no threat tracking lives here.
 */
public final class WatchStatus {
    public enum State {STOPPED, STARTING, WATCHING, ERROR}

    private static final Map<NGameUI, WatchStatus> BY_SESSION = new WeakHashMap<>();

    public volatile State state = State.STOPPED;
    public volatile String profileName = "";
    public volatile String detail = "";
    public volatile int playersInView;
    public volatile int alertsSent;
    public volatile String lastDelivery = "";
    public volatile long lastScanMs;
    /** The thread running the lookout, so the settings window can stop it however it was started. */
    public volatile Thread thread;

    public static WatchStatus of(NGameUI gui) {
        synchronized (BY_SESSION) {
            return BY_SESSION.computeIfAbsent(gui, g -> new WatchStatus());
        }
    }

    public boolean running() {
        return state == State.STARTING || state == State.WATCHING;
    }

    public String text() {
        switch (state) {
            case STARTING:
                return "Starting (" + profileName + ")";
            case WATCHING:
                return "Watching as '" + profileName + "' - " + playersInView + " player(s) in view, "
                        + alertsSent + " alert(s) sent" + (lastDelivery.isEmpty() ? "" : "; last: " + lastDelivery);
            case ERROR:
                return "Stopped: " + detail;
            default:
                return "Not running" + (detail.isEmpty() ? "" : " (" + detail + ")");
        }
    }
}
