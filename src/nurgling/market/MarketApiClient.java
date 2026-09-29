package nurgling.market;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Small dependency-free client for the H4D Market ingestion API. */
public final class MarketApiClient {
    public static final class ApiException extends IOException {
        public final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final String endpoint;
    private final String token;
    private final int attempts;

    public MarketApiClient(String endpoint, String token, int attempts) throws IOException {
        String normalized = endpoint == null ? "" : endpoint.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        URL parsed = new URL(normalized);
        if (!"http".equals(parsed.getProtocol()) && !"https".equals(parsed.getProtocol())) {
            throw new IOException("Market endpoint must use http or https");
        }
        this.endpoint = normalized;
        this.token = token;
        this.attempts = Math.max(1, attempts);
    }

    public String begin(JSONObject payload) throws IOException, InterruptedException {
        // A lost response to a non-idempotent begin may leave an invisible orphan scan. Do not
        // automatically issue a second begin; the next scheduled run can safely begin afresh.
        JSONObject response = requestJson("POST", "/api/v1/scans/begin", payload, 1);
        String scanId = response.optString("scanId", "");
        if (scanId.isEmpty()) {
            throw new IOException("Market begin response did not contain scanId");
        }
        return scanId;
    }

    public void putStand(String scanId, int houseId, JSONObject payload) throws IOException, InterruptedException {
        requestJson("PUT", "/api/v1/scans/" + path(scanId) + "/stands/" + houseId, payload, attempts);
    }

    public void complete(String scanId) throws IOException, InterruptedException {
        requestJson("POST", "/api/v1/scans/" + path(scanId) + "/complete", new JSONObject(), attempts);
    }

    public void ensureIcon(String resource, byte[] png) throws IOException, InterruptedException {
        int status = status("GET", "/icons/" + resource + ".png", attempts);
        if (status >= 200 && status < 300) {
            return;
        }
        if (status != 404) {
            throw new ApiException(status, "Icon lookup failed with HTTP " + status);
        }
        JSONObject payload = new JSONObject();
        payload.put("resource", resource);
        payload.put("pngBase64", Base64.getEncoder().encodeToString(png));
        requestJson("POST", "/api/v1/icons", payload, attempts);
    }

    private JSONObject requestJson(String method, String path, JSONObject payload, int maxAttempts)
            throws IOException, InterruptedException {
        byte[] body = payload == null ? null : payload.toString().getBytes(StandardCharsets.UTF_8);
        IOException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = open(method, path);
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(body);
                    }
                }
                int status = connection.getResponseCode();
                String response = read(connection, status);
                if (status >= 200 && status < 300) {
                    return response.trim().isEmpty() ? new JSONObject() : new JSONObject(response);
                }
                ApiException failure = new ApiException(status, "Market API returned HTTP " + status + bodyHint(response));
                if (!retryable(status) || attempt == maxAttempts) {
                    throw failure;
                }
                last = failure;
            } catch (IOException failure) {
                last = failure;
                if (failure instanceof ApiException && !retryable(((ApiException) failure).status)) {
                    throw failure;
                }
                if (attempt == maxAttempts) {
                    throw failure;
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            backoff(attempt);
        }
        throw last == null ? new IOException("Market request failed") : last;
    }

    private int status(String method, String path, int maxAttempts) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = open(method, path);
                int status = connection.getResponseCode();
                close(connection, status);
                if (!retryable(status) || attempt == maxAttempts) {
                    return status;
                }
                last = new ApiException(status, "Market API returned HTTP " + status);
            } catch (IOException failure) {
                last = failure;
                if (attempt == maxAttempts) {
                    throw failure;
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            backoff(attempt);
        }
        throw last == null ? new IOException("Market request failed") : last;
    }

    private HttpURLConnection open(String method, String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("User-Agent", "Nurgling2-H4D-Market-Scanner/1");
        return connection;
    }

    private static String read(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void close(HttpURLConnection connection, int status) {
        InputStream stream = null;
        try {
            stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream != null) {
                byte[] buffer = new byte[1024];
                while (stream.read(buffer) >= 0) {
                    // Drain so keep-alive connections can be reused.
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static boolean retryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private static void backoff(int attempt) throws InterruptedException {
        Thread.sleep(Math.min(4_000L, 500L << Math.min(3, attempt - 1)));
    }

    private static String path(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private static String bodyHint(String response) {
        String compact = response == null ? "" : response.replaceAll("\\s+", " ").trim();
        if (compact.isEmpty()) {
            return "";
        }
        return ": " + compact.substring(0, Math.min(300, compact.length()));
    }
}
