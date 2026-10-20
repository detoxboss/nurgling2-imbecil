package nurgling.actions.bots;

import nurgling.NGameUI;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.notifications.DiscordWebhookQueue;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.watch.GobScanner;
import nurgling.watch.MapScreenshotProvider;
import nurgling.watch.WatchEngine;
import nurgling.watch.WatchProfile;
import nurgling.watch.WatchProfileStore;
import nurgling.watch.WatchStatus;
import nurgling.watch.WatchWebhook;

import java.time.Clock;
import java.util.Map;

/**
 * Village Watch: a passive lookout. Stays where it is, reads the player gobs its own session has
 * loaded, and posts a Discord alert (with a screenshot when this session is the rendered one) when
 * an unknown, white or red player turns up.
 *
 * <p>It never moves, clicks, attacks, opens anything, logs out or hearths. It runs until stopped, so
 * in a Scenario it should be the last step.
 *
 * <p>Which profile it uses: the {@value #SETTING_PROFILE} setting of its Scenario step, or - when
 * started from the Bots menu, which passes none - the profile this character last used. The profile
 * is copied at start; editing it afterwards does not change a lookout that is already running.
 */
public class VillageWatchBot implements Action {
    public static final String BOT_ID = "village_watch";
    public static final String SETTING_PROFILE = "profile";

    private final String requestedProfile;

    public VillageWatchBot() {
        this(null);
    }

    public VillageWatchBot(Map<String, Object> settings) {
        Object p = settings == null ? null : settings.get(SETTING_PROFILE);
        this.requestedProfile = (p instanceof String && !((String) p).trim().isEmpty()) ? ((String) p).trim() : null;
    }

    /** The profile name this instance was configured with, or null to use the character's last-used profile. */
    public String requestedProfile() {
        return requestedProfile;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        WatchStatus status = WatchStatus.of(gui);
        synchronized (status) {
            if (status.running()) {
                gui.msg("Village Watch is already running in this session");
                return Results.FAIL();
            }
            status.state = WatchStatus.State.STARTING;
        }
        WatchProfileStore store = WatchProfileStore.shared();
        String profileName = requestedProfile != null ? requestedProfile : store.profileFor(gui.chrid);
        status.profileName = profileName;
        status.alertsSent = 0;
        status.lastDelivery = "";

        WatchProfile profile = store.get(profileName);
        if (profile == null)
            return fail(gui, status, "profile '" + profileName + "' does not exist");
        final WatchWebhook webhook;
        try {
            webhook = WatchWebhook.resolve();
        } catch (IllegalStateException e) {
            return fail(gui, status, e.getMessage());
        }

        final WatchStatus st = status;
        WatchEngine engine = new WatchEngine(profile, new MapScreenshotProvider(gui),
                (message, alert) -> DiscordWebhookQueue.shared().submit(webhook.transport, message, result -> {
                    st.lastDelivery = result.summary();
                    if (result.success)
                        st.alertsSent++;
                    else
                        gui.msg("Village Watch: alert not delivered (" + result.error + ")");
                }),
                Clock.systemDefaultZone(), webhook.username, webhook.icon);

        gui.msg("Village Watch started: " + profile.watcher + " (profile " + profile.name + ")");
        long intervalMs = profile.scanIntervalSec * 1000L;
        status.detail = "";
        status.thread = Thread.currentThread();
        status.state = WatchStatus.State.WATCHING;
        boolean sessionClosed = false;
        try {
            while (true) {
                if (!sessionAlive(gui)) {
                    sessionClosed = true;
                    return Results.SUCCESS();
                }
                try {
                    GobScanner.Scan scan = GobScanner.scan(gui);
                    status.playersInView = scan.playersInView;
                    engine.scan(scan.sightings);
                    status.lastScanMs = System.currentTimeMillis();
                } catch (RuntimeException e) {
                    // One bad scan (a gob mid-removal, a resource still loading) must not end the watch.
                    status.lastDelivery = "scan error: " + e.getClass().getSimpleName();
                }
                Thread.sleep(intervalMs);
            }
        } finally {
            status.thread = null;
            status.state = WatchStatus.State.STOPPED;
            status.detail = sessionClosed ? "session closed" : "stopped";
            status.playersInView = 0;
        }
    }

    private static Results fail(NGameUI gui, WatchStatus status, String reason) {
        status.state = WatchStatus.State.ERROR;
        status.detail = reason;
        gui.error("Village Watch: " + reason);
        return Results.FAIL();
    }

    /** False once this bot's session has been disconnected or closed. */
    private static boolean sessionAlive(NGameUI gui) {
        if (gui.ui == null)
            return false;
        SessionContext ctx = SessionManager.getInstance().findByUI(gui.ui);
        return ctx == null || ctx.isConnected();
    }
}
