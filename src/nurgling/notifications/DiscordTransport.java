package nurgling.notifications;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * One HTTP POST to one webhook. An interface so the retry queue can be exercised against a
 * scripted fake; the real implementation holds the webhook URL privately and never prints it.
 */
public interface DiscordTransport {
    final class Response {
        public final int status;
        public final String body;
        /** Server-requested wait before retrying, or -1 when none was given. */
        public final long retryAfterMs;

        public Response(int status, String body, long retryAfterMs) {
            this.status = status;
            this.body = body;
            this.retryAfterMs = retryAfterMs;
        }
    }

    Response send(String contentType, byte[] body) throws IOException;

    /** Generic, URL-free description for logs. */
    String describe();

    final class Https implements DiscordTransport {
        public static final int CONNECT_TIMEOUT_MS = 10_000;
        public static final int READ_TIMEOUT_MS = 20_000;
        private static final int MAX_RESPONSE_BYTES = 16 * 1024;

        private final URL url;

        /** @throws IllegalArgumentException unless the value is an https Discord webhook URL */
        public Https(String webhookUrl) {
            this.url = validate(webhookUrl);
        }

        static URL validate(String webhookUrl) {
            try {
                URI uri = new URI(webhookUrl == null ? "" : webhookUrl.trim());
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                boolean discordHost = host.equals("discord.com") || host.endsWith(".discord.com")
                        || host.equals("discordapp.com") || host.endsWith(".discordapp.com");
                if (!"https".equalsIgnoreCase(uri.getScheme()) || !discordHost
                        || uri.getPath() == null || !uri.getPath().startsWith("/api/webhooks/"))
                    throw new IllegalArgumentException("Not a Discord webhook URL (expected https://discord.com/api/webhooks/...)");
                return uri.toURL();
            } catch (java.net.URISyntaxException | java.net.MalformedURLException e) {
                throw new IllegalArgumentException("Not a Discord webhook URL");
            }
        }

        @Override
        public Response send(String contentType, byte[] body) throws IOException {
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            try {
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", contentType);
                c.setRequestProperty("User-Agent", "Nurgling2-VillageWatch/1");
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body);
                }
                int status = c.getResponseCode();
                InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                String text = in == null ? "" : readCapped(in);
                return new Response(status, text, retryAfter(c, text));
            } catch (IOException e) {
                // JDK exception text can carry the request URL; the URL is the credential.
                throw new IOException(e.getClass().getSimpleName() + " talking to Discord");
            } finally {
                c.disconnect();
            }
        }

        private static String readCapped(InputStream in) throws IOException {
            try (InputStream s = in) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[2048];
                int n;
                while (buf.size() < MAX_RESPONSE_BYTES && (n = s.read(chunk)) > 0)
                    buf.write(chunk, 0, n);
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        }

        private static long retryAfter(HttpURLConnection c, String body) {
            String header = c.getHeaderField("Retry-After");
            try {
                if (header != null)
                    return (long) (Double.parseDouble(header.trim()) * 1000);
                if (body != null && body.startsWith("{"))
                    return (long) (new JSONObject(body).optDouble("retry_after", -1) * 1000);
            } catch (RuntimeException ignored) {
                // fall through to "no hint"
            }
            return -1;
        }

        @Override
        public String describe() {
            return "Discord webhook";
        }

        @Override
        public String toString() {
            return "Discord webhook [url hidden]";
        }
    }
}
