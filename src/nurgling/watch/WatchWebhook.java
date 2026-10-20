package nurgling.watch;

import nurgling.conf.NDiscordNotification;
import nurgling.notifications.DiscordTransport;

/** The "general" Discord webhook from Settings, resolved once per lookout start. */
public final class WatchWebhook {
    public final DiscordTransport transport;
    public final String username;
    public final String icon;

    private WatchWebhook(DiscordTransport transport, String username, String icon) {
        this.transport = transport;
        this.username = username;
        this.icon = icon;
    }

    /**
     * @throws IllegalStateException with a user-facing message (never containing the URL) when no usable webhook is configured
     */
    public static WatchWebhook resolve() {
        NDiscordNotification settings = NDiscordNotification.get("general");
        if (settings == null || settings.webhookUrl == null || settings.webhookUrl.trim().isEmpty())
            throw new IllegalStateException("No Discord webhook configured (Settings > Discord)");
        try {
            return new WatchWebhook(new DiscordTransport.Https(settings.webhookUrl), settings.webhookUsername, settings.webhookIcon);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }
}
