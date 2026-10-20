package nurgling.watch;

import nurgling.notifications.DiscordMessage;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/** Builds the Discord message for one alert from the profile's templates. Pure, so it is unit-tested directly. */
public final class AlertFormatter {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private AlertFormatter() {
    }

    /** What the picture situation was, to say so in the alert rather than silently omit it. */
    public static final class Shot {
        /** JPEG to attach, or null. */
        public final byte[] jpeg;
        /** Why there is none (null when one is attached or screenshots are switched off). */
        public final String missingReason;
        /** False when the threat was outside the camera view; null when unknown. */
        public final Boolean threatInFrame;

        public Shot(byte[] jpeg, String missingReason, Boolean threatInFrame) {
            this.jpeg = jpeg;
            this.missingReason = missingReason;
            this.threatInFrame = threatInFrame;
        }

        public static Shot none() {
            return new Shot(null, null, null);
        }
    }

    public static Map<String, String> values(WatchProfile profile, ThreatTracker.Alert alert, ZonedDateTime when) {
        ThreatTracker.Sighting s = alert.sighting;
        Map<String, String> v = new HashMap<>();
        v.put(WatchTemplate.WATCHER, profile.watcher);
        v.put(WatchTemplate.PLAYER, s.name == null || s.name.isEmpty() ? "Unknown" : s.name);
        v.put(WatchTemplate.STATUS, s.category.statusText());
        v.put(WatchTemplate.TIME, TIME.format(when));
        v.put(WatchTemplate.GOB_ID, Long.toString(s.gobId));
        v.put(WatchTemplate.COORDS, s.coords);
        return v;
    }

    public static DiscordMessage format(WatchProfile profile, ThreatTracker.Alert alert, ZonedDateTime when,
                                        String webhookUsername, String webhookIcon, Shot shot) {
        return format(profile, alert, when, webhookUsername, webhookIcon, shot, true);
    }

    /**
     * @param allowPing false for test messages, which must never notify anyone. Even when true, only a
     *                  new sighting or an escalation pings - a cooldown repeat never does.
     */
    public static DiscordMessage format(WatchProfile profile, ThreatTracker.Alert alert, ZonedDateTime when,
                                        String webhookUsername, String webhookIcon, Shot shot, boolean allowPing) {
        ThreatTracker.Sighting s = alert.sighting;
        WatchProfile.Category cat = profile.category(s.category);
        String template = profile.category(s.category).template;

        DiscordMessage m = new DiscordMessage();
        m.username = webhookUsername;
        m.avatarUrl = webhookIcon;
        m.title = "Village Watch - " + s.category.label + " player"
                + (alert.reason == ThreatTracker.Reason.ESCALATION ? " (escalated)"
                : alert.reason == ThreatTracker.Reason.REPEAT ? " (still present)" : "");
        m.description = WatchTemplate.render(template, values(profile, alert, when));
        m.color = s.category.embedColor;
        m.footer = "Village Watch | " + profile.watcher;
        m.timestamp = when.toInstant().toString();

        // The mention is built only from the configured target and a fixed phrase - never from the
        // player's name, the watcher name or the template, so none of those can ping anyone.
        if (allowPing && alert.reason != ThreatTracker.Reason.REPEAT && cat.ping.usable(cat.pingId)) {
            m.content = cat.ping.mention(cat.pingId) + " Village Watch alert: " + s.category.label.toLowerCase() + " player detected";
            m.allowedMentions = cat.ping.allowedMentions(cat.pingId);
        }

        // Position is shown even when the template does not ask for it, but only when it is known.
        if (s.coords != null && !WatchTemplate.usesPlaceholder(template, WatchTemplate.COORDS))
            m.addField("Position", s.coords, true);

        if (shot.jpeg != null) {
            m.attachment = new DiscordMessage.Attachment(
                    "village-watch-" + s.gobId + "-" + when.toEpochSecond() + ".jpg", "image/jpeg", shot.jpeg);
            if (Boolean.FALSE.equals(shot.threatInFrame))
                m.addField("Screenshot", "The player was outside the camera view (the camera is never moved).", false);
        } else if (shot.missingReason != null) {
            m.addField("Screenshot", "Not attached: " + shot.missingReason, false);
        }
        return m;
    }
}
