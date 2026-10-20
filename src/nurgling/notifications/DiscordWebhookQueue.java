package nurgling.notifications;

import org.json.JSONObject;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Non-blocking, bounded delivery of webhook messages. Callers hand a message over and carry on; one
 * background thread sends them in order, honouring Discord's rate limits and retrying transient
 * failures with backoff. A result is reported only once Discord has answered (success) or the
 * message has definitively failed.
 *
 * <p>Bounds: at most {@link #CAPACITY} messages wait at once, and queued screenshots are capped by
 * bytes ({@link #MAX_QUEUED_ATTACHMENT_BYTES}); a message that would exceed the byte budget is still
 * queued, but text-only.
 */
public final class DiscordWebhookQueue {
    public static final int CAPACITY = 16;
    public static final int MAX_ATTEMPTS = 4;
    public static final long MAX_RETRY_WAIT_MS = 30_000;
    public static final long FIRST_BACKOFF_MS = 1_000;
    /** Largest single file sent; Discord's webhook limit is larger, this keeps requests small. */
    public static final int MAX_ATTACHMENT_BYTES = 7 * 1024 * 1024;
    public static final long MAX_QUEUED_ATTACHMENT_BYTES = 16L * 1024 * 1024;

    public static final class Result {
        public final boolean success;
        public final int attempts;
        /** Last HTTP status seen, or 0 when none was received. */
        public final int status;
        /** Failure reason (never contains the webhook URL); null on success. */
        public final String error;
        public final boolean attachmentSent;
        /** True when a screenshot was dropped (size/queue budget, or Discord rejected it as too large). */
        public final boolean attachmentDropped;

        Result(boolean success, int attempts, int status, String error, boolean attachmentSent, boolean attachmentDropped) {
            this.success = success;
            this.attempts = attempts;
            this.status = status;
            this.error = error;
            this.attachmentSent = attachmentSent;
            this.attachmentDropped = attachmentDropped;
        }

        public String summary() {
            if (success)
                return attachmentSent ? "delivered with screenshot" : (attachmentDropped ? "delivered (screenshot dropped)" : "delivered");
            return "failed: " + error;
        }
    }

    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private static final class Job {
        final DiscordTransport transport;
        final DiscordMessage message;
        final boolean attachmentAllowed;
        final long attachmentBytes;
        final Consumer<Result> callback;

        Job(DiscordTransport transport, DiscordMessage message, boolean attachmentAllowed, long attachmentBytes, Consumer<Result> callback) {
            this.transport = transport;
            this.message = message;
            this.attachmentAllowed = attachmentAllowed;
            this.attachmentBytes = attachmentBytes;
            this.callback = callback;
        }
    }

    private static DiscordWebhookQueue shared;

    public static synchronized DiscordWebhookQueue shared() {
        if (shared == null)
            shared = new DiscordWebhookQueue(Thread::sleep);
        return shared;
    }

    private final BlockingQueue<Job> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong queuedBytes = new AtomicLong();
    private final SecureRandom random = new SecureRandom();
    private final Sleeper sleeper;
    private final Thread worker;

    public DiscordWebhookQueue(Sleeper sleeper) {
        this.sleeper = sleeper;
        this.worker = new Thread(this::loop, "DiscordWebhookQueue");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * Queue a message. Returns false (after reporting a failed {@link Result}) when the queue is full.
     * {@code callback} runs on the delivery thread and may be null.
     */
    public boolean submit(DiscordTransport transport, DiscordMessage message, Consumer<Result> callback) {
        long bytes = message.hasAttachment() ? message.attachment.data.length : 0;
        long reserved = 0;
        if (bytes > 0 && bytes <= MAX_ATTACHMENT_BYTES) {
            if (queuedBytes.addAndGet(bytes) <= MAX_QUEUED_ATTACHMENT_BYTES)
                reserved = bytes;
            else
                queuedBytes.addAndGet(-bytes);
        }
        if (!queue.offer(new Job(transport, message, reserved > 0, reserved, callback))) {
            queuedBytes.addAndGet(-reserved);
            report(callback, new Result(false, 0, 0, "delivery queue full", false, message.hasAttachment()));
            return false;
        }
        return true;
    }

    public int pending() {
        return queue.size();
    }

    /** Stop the worker; used by tests, the shared queue lives for the whole process. */
    public void shutdown() {
        worker.interrupt();
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            Job job;
            try {
                job = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            Result result;
            try {
                result = deliver(job);
            } finally {
                queuedBytes.addAndGet(-job.attachmentBytes);
            }
            report(job.callback, result);
        }
    }

    private Result deliver(Job job) {
        boolean withAttachment = job.attachmentAllowed;
        boolean dropped = job.message.hasAttachment() && !withAttachment;
        long backoff = FIRST_BACKOFF_MS;
        int status = 0;
        String error = "unknown error";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String boundary = "----nurgling" + Long.toHexString(random.nextLong());
            DiscordTransport.Response r;
            try {
                r = job.transport.send(job.message.contentType(withAttachment, boundary), job.message.body(withAttachment, boundary));
            } catch (IOException e) {
                error = e.getMessage();
                if (attempt == MAX_ATTEMPTS || !pause(backoff))
                    return new Result(false, attempt, status, error, false, dropped);
                backoff *= 2;
                continue;
            }
            status = r.status;
            if (r.status >= 200 && r.status < 300)
                return new Result(true, attempt, status, null, withAttachment, dropped);
            error = "HTTP " + r.status + describe(r.body);
            if (r.status == 413 && withAttachment) {
                // Discord says the file is too big: the alert matters more than its picture.
                withAttachment = false;
                dropped = true;
                continue;
            }
            boolean retryable = r.status == 429 || r.status >= 500;
            if (!retryable || attempt == MAX_ATTEMPTS)
                return new Result(false, attempt, status, error, false, dropped);
            long wait = r.status == 429 && r.retryAfterMs > 0 ? Math.min(r.retryAfterMs, MAX_RETRY_WAIT_MS) : backoff;
            if (!pause(wait))
                return new Result(false, attempt, status, "interrupted", false, dropped);
            if (r.status != 429)
                backoff *= 2;
        }
        return new Result(false, MAX_ATTEMPTS, status, error, false, dropped);
    }

    private boolean pause(long ms) {
        try {
            sleeper.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Discord's own error text is useful ("Invalid Webhook Token"); keep it short. */
    private static String describe(String body) {
        if (body == null || !body.startsWith("{"))
            return "";
        try {
            String m = new JSONObject(body).optString("message", "");
            return m.isEmpty() ? "" : ": " + (m.length() > 120 ? m.substring(0, 120) : m);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static void report(Consumer<Result> callback, Result result) {
        if (callback == null)
            return;
        try {
            callback.accept(result);
        } catch (RuntimeException e) {
            System.err.println("[DiscordWebhookQueue] result callback failed: " + e.getClass().getSimpleName());
        }
    }
}
