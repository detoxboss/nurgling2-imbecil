package nurgling.watch;

import nurgling.notifications.DiscordMessage;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;

/**
 * One scan's worth of decisions, with no game or network types in sight: sightings in, messages out
 * through a {@link Sink}. The bot feeds it real gobs and a real webhook; tests feed it fakes.
 *
 * <p>A failed or unavailable screenshot never stops an alert: the message goes out text-only and
 * says why the picture is missing.
 */
public final class WatchEngine {
    public static final long CAPTURE_TIMEOUT_MS = 5000;

    public interface Sink {
        void deliver(DiscordMessage message, ThreatTracker.Alert alert);
    }

    private final WatchProfile profile;
    private final ThreatTracker tracker;
    private final ScreenshotProvider screenshots;
    private final Sink sink;
    private final Clock clock;
    private final String webhookUsername;
    private final String webhookIcon;

    public WatchEngine(WatchProfile profile, ScreenshotProvider screenshots, Sink sink, Clock clock,
                       String webhookUsername, String webhookIcon) {
        this.profile = profile;
        this.tracker = new ThreatTracker(profile);
        this.screenshots = screenshots;
        this.sink = sink;
        this.clock = clock;
        this.webhookUsername = webhookUsername;
        this.webhookIcon = webhookIcon;
    }

    public ThreatTracker tracker() {
        return tracker;
    }

    /** @return how many alerts were handed to the sink */
    public int scan(Collection<ThreatTracker.Sighting> sightings) throws InterruptedException {
        List<ThreatTracker.Alert> alerts = tracker.update(clock.millis(), sightings);
        if (alerts.isEmpty())
            return 0;

        // One picture per scan, shared by every alert raised in it.
        byte[] jpeg = null;
        String missing = null;
        if (profile.attachScreenshot) {
            missing = screenshots.unavailableReason();
            if (missing == null) {
                ScreenshotProvider.Result r = screenshots.capture(CAPTURE_TIMEOUT_MS);
                jpeg = r.jpeg;
                missing = r.failure;
            }
        }
        ZonedDateTime when = ZonedDateTime.now(clock);
        for (ThreatTracker.Alert a : alerts) {
            Boolean inFrame = jpeg == null ? null : screenshots.inFrame(a.sighting.gobId);
            AlertFormatter.Shot shot = new AlertFormatter.Shot(jpeg, jpeg == null ? missing : null, inFrame);
            sink.deliver(AlertFormatter.format(profile, a, when, webhookUsername, webhookIcon, shot), a);
        }
        return alerts.size();
    }
}
