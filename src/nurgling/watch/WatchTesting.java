package nurgling.watch;

import nurgling.NGameUI;
import nurgling.notifications.DiscordMessage;
import nurgling.notifications.DiscordWebhookQueue;

import java.time.Clock;
import java.time.ZonedDateTime;

/**
 * The settings window's "Test Discord" and "Test screenshot" buttons. Both send a clearly marked
 * test message built from the profile being edited, and report the real delivery outcome.
 */
public final class WatchTesting {
    private WatchTesting() {
    }

    public static void testDiscord(NGameUI gui, WatchProfile profile) {
        run(gui, "Village Watch: Discord test", profile, false);
    }

    public static void testScreenshot(NGameUI gui, WatchProfile profile) {
        run(gui, "Village Watch: screenshot test", profile, true);
    }

    private static void run(NGameUI gui, String what, WatchProfile profile, boolean screenshot) {
        final WatchWebhook webhook;
        try {
            webhook = WatchWebhook.resolve();
        } catch (IllegalStateException e) {
            gui.error(e.getMessage());
            return;
        }
        final ScreenshotProvider provider = new MapScreenshotProvider(gui);
        // A capture waits for the render thread to draw a frame, so it must never run on the UI thread.
        Thread t = new Thread(() -> {
            try {
                byte[] jpeg = null;
                String missing = null;
                if (screenshot) {
                    missing = provider.unavailableReason();
                    if (missing == null) {
                        ScreenshotProvider.Result r = provider.capture(WatchEngine.CAPTURE_TIMEOUT_MS);
                        jpeg = r.jpeg;
                        missing = r.failure;
                    }
                    gui.msg(jpeg != null ? what + ": captured " + (jpeg.length / 1024) + " KB" : what + ": " + missing);
                }
                ThreatTracker.Alert alert = sampleAlert();
                ZonedDateTime when = ZonedDateTime.now(Clock.systemDefaultZone());
                DiscordMessage m = AlertFormatter.format(profile, alert, when, webhook.username, webhook.icon,
                        new AlertFormatter.Shot(jpeg, jpeg == null ? missing : null, null));
                m.title = "TEST - " + m.title;
                m.addField("Test message", "No player was detected. Sent from the Village Watch settings window.", false);
                DiscordWebhookQueue.shared().submit(webhook.transport, m,
                        r -> gui.msg(what + ": " + r.summary()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "VillageWatch-test");
        t.setDaemon(true);
        t.start();
    }

    static ThreatTracker.Alert sampleAlert() {
        return ThreatTracker.sampleAlert(new ThreatTracker.Sighting(0, WatchCategory.UNKNOWN, null, null));
    }
}
