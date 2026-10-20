package nurgling.notifications;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordWebhookTest {
    private static final String SECRET_URL = "https://discord.com/api/webhooks/123/SECRET-TOKEN";
    private final List<Long> sleeps = Collections.synchronizedList(new ArrayList<Long>());
    private DiscordWebhookQueue queue;

    @AfterEach
    void stop() {
        if (queue != null)
            queue.shutdown();
    }

    /** Replies from a script, one entry per attempt; an IOException entry is thrown. */
    private static final class Scripted implements DiscordTransport {
        final Queue<Object> script = new LinkedList<>();
        final List<String> contentTypes = Collections.synchronizedList(new ArrayList<String>());
        final List<byte[]> bodies = Collections.synchronizedList(new ArrayList<byte[]>());

        Scripted(Object... replies) {
            script.addAll(Arrays.asList(replies));
        }

        @Override
        public Response send(String contentType, byte[] body) throws IOException {
            contentTypes.add(contentType);
            bodies.add(body);
            Object next = script.poll();
            if (next instanceof IOException)
                throw (IOException) next;
            return (Response) next;
        }

        @Override
        public String describe() {
            return "scripted";
        }
    }

    private static DiscordTransport.Response http(int status) {
        return new DiscordTransport.Response(status, "", -1);
    }

    private DiscordWebhookQueue newQueue() {
        queue = new DiscordWebhookQueue(sleeps::add);
        return queue;
    }

    private static DiscordMessage message(byte[] image) {
        DiscordMessage m = new DiscordMessage();
        m.title = "Village Watch - Red player";
        m.description = "hello";
        if (image != null)
            m.attachment = new DiscordMessage.Attachment("shot.jpg", "image/jpeg", image);
        return m;
    }

    private static DiscordWebhookQueue.Result deliver(DiscordWebhookQueue q, DiscordTransport t, DiscordMessage m) throws InterruptedException {
        AtomicReference<DiscordWebhookQueue.Result> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        q.submit(t, m, r -> {
            got.set(r);
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "no result reported");
        return got.get();
    }

    /* ------------------------------------------------------- payload / multipart */

    @Test
    void text_only_message_is_plain_json_with_an_embed_and_no_pings() {
        DiscordMessage m = message(null);
        m.addField("Position", "(1, 2)", true);
        JSONObject json = m.payload(false);
        JSONObject embed = json.getJSONArray("embeds").getJSONObject(0);
        assertEquals("Village Watch - Red player", embed.getString("title"));
        assertEquals("hello", embed.getString("description"));
        assertEquals("Position", embed.getJSONArray("fields").getJSONObject(0).getString("name"));
        assertFalse(embed.has("image"));
        assertFalse(json.has("attachments"));
        assertEquals(0, json.getJSONObject("allowed_mentions").getJSONArray("parse").length());
        assertEquals("application/json", m.contentType(false, "b"));
        assertEquals(json.toString(), new String(m.body(false, "b"), StandardCharsets.UTF_8));
    }

    @Test
    void attachment_message_is_multipart_with_payload_json_and_files0_and_the_embed_references_the_file() {
        byte[] image = {1, 2, 3, (byte) 0xFF, 0, 9};
        DiscordMessage m = message(image);
        JSONObject json = m.payload(true);
        assertEquals("attachment://shot.jpg", json.getJSONArray("embeds").getJSONObject(0).getJSONObject("image").getString("url"));
        assertEquals("shot.jpg", json.getJSONArray("attachments").getJSONObject(0).getString("filename"));

        assertEquals("multipart/form-data; boundary=XYZ", m.contentType(true, "XYZ"));
        byte[] body = m.body(true, "XYZ");
        String text = new String(body, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("--XYZ\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n{"));
        assertTrue(text.contains("\r\n--XYZ\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"shot.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"));
        assertTrue(text.endsWith("\r\n--XYZ--\r\n"));
        // The exact image bytes sit between the files[0] headers and the closing boundary.
        int start = text.indexOf("filename=\"shot.jpg\"");
        int dataStart = text.indexOf("\r\n\r\n", start) + 4;
        assertEquals(Arrays.toString(image), Arrays.toString(Arrays.copyOfRange(body, dataStart, dataStart + image.length)));
    }

    @Test
    void asking_for_text_only_drops_the_attachment_and_the_image_reference() {
        DiscordMessage m = message(new byte[]{1});
        JSONObject json = m.payload(false);
        assertFalse(json.getJSONArray("embeds").getJSONObject(0).has("image"));
        assertEquals("application/json", m.contentType(false, "b"));
    }

    @Test
    void filenames_cannot_break_the_multipart_headers() {
        DiscordMessage.Attachment a = new DiscordMessage.Attachment("a\"b\r\nX: y.jpg", "image/jpeg", new byte[0]);
        assertFalse(a.filename.contains("\"") || a.filename.contains("\r") || a.filename.contains("\n"));
    }

    @Test
    void overlong_text_is_cut_to_discords_limits() {
        DiscordMessage m = new DiscordMessage();
        m.title = new String(new char[500]).replace('\0', 't');
        m.description = new String(new char[9000]).replace('\0', 'd');
        JSONObject embed = m.payload(false).getJSONArray("embeds").getJSONObject(0);
        assertTrue(embed.getString("title").length() <= DiscordMessage.MAX_TITLE);
        assertTrue(embed.getString("description").length() <= DiscordMessage.MAX_DESCRIPTION);
    }

    /* ----------------------------------------------------------------- delivery */

    @Test
    void success_is_reported_only_after_discord_answers_2xx() throws Exception {
        Scripted t = new Scripted(http(204));
        DiscordWebhookQueue.Result r = deliver(newQueue(), t, message(new byte[]{1, 2, 3}));
        assertTrue(r.success);
        assertTrue(r.attachmentSent);
        assertEquals(1, r.attempts);
        assertTrue(t.contentTypes.get(0).startsWith("multipart/form-data"));
    }

    @Test
    void a_rate_limit_waits_for_retry_after_then_retries() throws Exception {
        Scripted t = new Scripted(new DiscordTransport.Response(429, "{\"retry_after\":2.5}", 2500), http(204));
        DiscordWebhookQueue.Result r = deliver(newQueue(), t, message(null));
        assertTrue(r.success);
        assertEquals(2, r.attempts);
        assertEquals(Collections.singletonList(2500L), sleeps);
    }

    @Test
    void an_enormous_retry_after_is_capped() throws Exception {
        Scripted t = new Scripted(new DiscordTransport.Response(429, "", 600_000), http(200));
        deliver(newQueue(), t, message(null));
        assertEquals(Collections.singletonList(DiscordWebhookQueue.MAX_RETRY_WAIT_MS), sleeps);
    }

    @Test
    void server_errors_retry_with_doubling_backoff_and_then_give_up_without_reporting_success() throws Exception {
        Scripted t = new Scripted(http(500), http(502), http(503), http(500));
        DiscordWebhookQueue.Result r = deliver(newQueue(), t, message(null));
        assertFalse(r.success);
        assertEquals(DiscordWebhookQueue.MAX_ATTEMPTS, r.attempts);
        assertEquals(Arrays.asList(1000L, 2000L, 4000L), sleeps);
        assertTrue(r.error.contains("500"));
    }

    @Test
    void network_failures_are_retried_and_can_recover() throws Exception {
        Scripted t = new Scripted(new IOException("SocketTimeoutException talking to Discord"), http(204));
        DiscordWebhookQueue.Result r = deliver(newQueue(), t, message(null));
        assertTrue(r.success);
        assertEquals(2, r.attempts);
    }

    @Test
    void client_errors_fail_immediately_with_discords_reason_and_no_retry() throws Exception {
        Scripted t = new Scripted(new DiscordTransport.Response(401, "{\"message\":\"Invalid Webhook Token\",\"code\":50027}", -1));
        DiscordWebhookQueue.Result r = deliver(newQueue(), t, message(null));
        assertFalse(r.success);
        assertEquals(1, r.attempts);
        assertEquals(401, r.status);
        assertTrue(r.error.contains("Invalid Webhook Token"));
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void a_screenshot_discord_rejects_as_too_large_falls_back_to_a_text_only_alert() throws Exception {
        Scripted t = new Scripted(http(413), http(204));
        DiscordWebhookQueue.Result r = deliver(newQueue(), t, message(new byte[]{1, 2, 3}));
        assertTrue(r.success);
        assertFalse(r.attachmentSent);
        assertTrue(r.attachmentDropped);
        assertTrue(t.contentTypes.get(0).startsWith("multipart/form-data"));
        assertEquals("application/json", t.contentTypes.get(1));
    }

    @Test
    void an_oversized_screenshot_is_never_sent_but_the_alert_still_is() throws Exception {
        Scripted t = new Scripted(http(204));
        byte[] huge = new byte[DiscordWebhookQueue.MAX_ATTACHMENT_BYTES + 1];
        DiscordWebhookQueue.Result r = deliver(newQueue(), t, message(huge));
        assertTrue(r.success);
        assertFalse(r.attachmentSent);
        assertTrue(r.attachmentDropped);
        assertEquals("application/json", t.contentTypes.get(0));
    }

    @Test
    void a_full_queue_rejects_new_messages_with_a_reported_failure_instead_of_growing() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        DiscordTransport blocking = new DiscordTransport() {
            public Response send(String contentType, byte[] body) throws IOException {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IOException("interrupted");
                }
                return http(204);
            }

            public String describe() {
                return "blocking";
            }
        };
        DiscordWebhookQueue q = newQueue();
        List<DiscordWebhookQueue.Result> rejected = Collections.synchronizedList(new ArrayList<DiscordWebhookQueue.Result>());
        int accepted = 0;
        for (int i = 0; i < DiscordWebhookQueue.CAPACITY + 8; i++) {
            if (q.submit(blocking, message(null), r -> {
                if (!r.success && "delivery queue full".equals(r.error))
                    rejected.add(r);
            }))
                accepted++;
        }
        release.countDown();
        assertTrue(accepted <= DiscordWebhookQueue.CAPACITY + 1, "accepted " + accepted); // + the one in flight
        assertTrue(rejected.size() >= 7, "rejected " + rejected.size());
    }

    @Test
    void queued_screenshot_bytes_are_bounded_later_messages_go_text_only() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Scripted first = new Scripted(http(204));
        DiscordTransport gate = new DiscordTransport() {
            public Response send(String contentType, byte[] body) throws IOException {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IOException("interrupted");
                }
                return first.send(contentType, body);
            }

            public String describe() {
                return "gate";
            }
        };
        DiscordWebhookQueue q = newQueue();
        byte[] big = new byte[6 * 1024 * 1024];
        List<DiscordWebhookQueue.Result> results = Collections.synchronizedList(new ArrayList<DiscordWebhookQueue.Result>());
        CountDownLatch all = new CountDownLatch(4);
        Scripted ok = new Scripted(http(204), http(204), http(204));
        q.submit(gate, message(big), r -> { results.add(r); all.countDown(); });
        for (int i = 0; i < 3; i++)
            q.submit(ok, message(big), r -> { results.add(r); all.countDown(); });
        release.countDown();
        assertTrue(all.await(10, TimeUnit.SECONDS));
        long dropped = 0;
        for (DiscordWebhookQueue.Result r : results)
            if (r.attachmentDropped)
                dropped++;
        assertTrue(dropped >= 1, "at least one screenshot should have been dropped for the byte budget");
    }

    /* ---------------------------------------------------------------- the URL */

    @Test
    void only_https_discord_webhook_urls_are_accepted() {
        new DiscordTransport.Https(SECRET_URL);
        new DiscordTransport.Https("https://ptb.discord.com/api/webhooks/1/x");
        new DiscordTransport.Https("https://discordapp.com/api/webhooks/1/x");
        for (String bad : new String[]{"", null, "http://discord.com/api/webhooks/1/x", "https://evil.example/api/webhooks/1/x",
                "https://discord.com.evil.example/api/webhooks/1/x", "https://discord.com/other/1/x", "ftp://discord.com/api/webhooks/1/x", "not a url"})
            assertThrows(IllegalArgumentException.class, () -> new DiscordTransport.Https(bad), String.valueOf(bad));
    }

    @Test
    void the_webhook_url_never_appears_in_descriptions_or_errors() {
        DiscordTransport.Https t = new DiscordTransport.Https(SECRET_URL);
        assertFalse(t.toString().contains("SECRET-TOKEN"));
        assertFalse(t.describe().contains("SECRET-TOKEN"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new DiscordTransport.Https("https://evil.example/SECRET-TOKEN"));
        assertFalse(String.valueOf(e.getMessage()).contains("SECRET-TOKEN"));
    }
}
