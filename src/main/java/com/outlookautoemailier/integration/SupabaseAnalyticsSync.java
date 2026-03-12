package com.outlookautoemailier.integration;

import com.outlookautoemailier.analytics.BatchStore;
import com.outlookautoemailier.analytics.EmailBatch;
import com.outlookautoemailier.analytics.SentEmailRecord;
import com.outlookautoemailier.analytics.SentEmailStore;
import com.outlookautoemailier.config.DotEnvLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.outlookautoemailier.analytics.LinkClickRecord;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pushes email batch and send records to Supabase and pulls open events back.
 *
 * <p>All methods are fire-and-forget: they run on a daemon thread via
 * {@link CompletableFuture#runAsync} and silently no-op when the
 * {@code SUPABASE_SERVICE_ROLE_KEY} environment variable is absent.</p>
 *
 * <h3>Required Supabase tables</h3>
 * <pre>
 * CREATE TABLE email_batches (
 *   id TEXT PRIMARY KEY,
 *   batch_name TEXT, subject TEXT, sent_at TIMESTAMPTZ,
 *   total_recipients INT DEFAULT 0, sent_count INT DEFAULT 0,
 *   failed_count INT DEFAULT 0, open_count INT DEFAULT 0,
 *   created_at TIMESTAMPTZ DEFAULT now()
 * );
 * CREATE TABLE email_sends (
 *   tracking_id TEXT PRIMARY KEY,
 *   batch_id TEXT REFERENCES email_batches(id),
 *   recipient_email TEXT, recipient_name TEXT, subject TEXT,
 *   sent_at TIMESTAMPTZ, status TEXT DEFAULT 'SENT',
 *   failure_reason TEXT, opened_at TIMESTAMPTZ,
 *   created_at TIMESTAMPTZ DEFAULT now()
 * );
 * </pre>
 */
public final class SupabaseAnalyticsSync {

    private static final Logger log = LoggerFactory.getLogger(SupabaseAnalyticsSync.class);
    private static final String SUPABASE_URL = "https://tgbhgwdgqinxwxedhnmc.supabase.co";

    private SupabaseAnalyticsSync() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /** Pushes a new batch record to Supabase asynchronously. */
    public static void pushBatchAsync(EmailBatch batch) {
        String key = serviceRoleKey();
        if (key == null) return;
        CompletableFuture.runAsync(() -> {
            try {
                String body = "{"
                        + "\"id\":"               + q(batch.getId())             + ","
                        + "\"batch_name\":"       + q(batch.getBatchName())      + ","
                        + "\"subject\":"          + q(batch.getSubject())        + ","
                        + "\"sent_at\":"          + q(isoOf(batch.getSentAt()))  + ","
                        + "\"total_recipients\":" + batch.getTotalRecipients()   + ","
                        + "\"sent_count\":"       + batch.getSentCount()         + ","
                        + "\"failed_count\":"     + batch.getFailedCount()       + ","
                        + "\"open_count\":"       + batch.getOpenCount()
                        + "}";
                post("/rest/v1/email_batches", body, key);
            } catch (Exception e) {
                log.warn("Failed to push batch to Supabase: {}", e.getMessage());
            }
        });
    }

    /** Pushes a single send record to Supabase asynchronously. */
    public static void pushSendAsync(SentEmailRecord r) {
        String key = serviceRoleKey();
        if (key == null) return;
        CompletableFuture.runAsync(() -> {
            try {
                String body = "{"
                        + "\"tracking_id\":"    + q(r.getTrackingId())           + ","
                        + "\"batch_id\":"       + qOrNull(r.getBatchId())        + ","
                        + "\"recipient_email\":" + q(r.getRecipientEmail())      + ","
                        + "\"recipient_name\":" + q(r.getRecipientName())        + ","
                        + "\"subject\":"        + q(r.getSubject())              + ","
                        + "\"sent_at\":"        + q(isoOf(r.getSentAt()))        + ","
                        + "\"status\":"         + q(r.getStatus())               + ","
                        + "\"failure_reason\":" + qOrNull(r.getFailureReason())
                        + "}";
                post("/rest/v1/email_sends", body, key);
            } catch (Exception e) {
                log.warn("Failed to push send record to Supabase: {}", e.getMessage());
            }
        });
    }

    /**
     * Fetches all rows from {@code email_batches} and merges them into the local
     * {@link BatchStore}.  Batches already present locally are not overwritten.
     * Called on Analytics page open so data survives app restarts.
     */
    public static CompletableFuture<Void> syncBatchesFromSupabaseAsync() {
        String key = serviceRoleKey();
        if (key == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(SUPABASE_URL
                                + "/rest/v1/email_batches"
                                + "?select=*&order=sent_at.asc"))
                        .header("apikey",        key)
                        .header("Authorization", "Bearer " + key)
                        .GET().build();
                HttpResponse<String> resp =
                        client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() == 200) {
                    mergeBatchesFromJson(resp.body());
                } else {
                    log.warn("Supabase syncBatches returned {}", resp.statusCode());
                }
            } catch (Exception e) {
                log.warn("Failed to sync batches from Supabase: {}", e.getMessage());
            }
        });
    }

    /**
     * Pulls rows from {@code email_sends} where {@code opened_at IS NOT NULL}
     * and updates local {@link SentEmailRecord} and {@link BatchStore} counters.
     * Called periodically by {@link com.outlookautoemailier.ui.AnalyticsController}.
     */
    public static CompletableFuture<Void> syncOpensAsync() {
        String key = serviceRoleKey();
        if (key == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(SUPABASE_URL
                                + "/rest/v1/email_sends"
                                + "?select=tracking_id,batch_id,opened_at"
                                + "&opened_at=not.is.null"))
                        .header("apikey",        key)
                        .header("Authorization", "Bearer " + key)
                        .GET().build();
                HttpResponse<String> resp =
                        client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() == 200) {
                    applyOpenEvents(resp.body());
                } else {
                    log.warn("Supabase syncOpens returned {}", resp.statusCode());
                }
            } catch (Exception e) {
                log.warn("Failed to sync opens from Supabase: {}", e.getMessage());
            }
        });
    }

    /**
     * PATCHes the {@code sent_count} and {@code failed_count} columns of
     * {@code email_batches} for the given batch. Called fire-and-forget after
     * every successful delivery and every failure in {@code SmtpSender} so that
     * Supabase always holds accurate delivery stats.
     */
    public static void patchBatchCountsAsync(String batchId, int sentCount, int failedCount) {
        String key = serviceRoleKey();
        if (key == null || batchId == null) return;
        CompletableFuture.runAsync(() -> {
            try {
                String path = "/rest/v1/email_batches?id=eq."
                        + URLEncoder.encode(batchId, StandardCharsets.UTF_8);
                String body = "{\"sent_count\":" + sentCount
                        + ",\"failed_count\":" + failedCount + "}";
                patch(path, body, key);
            } catch (Exception e) {
                log.warn("Failed to patch batch counts for {}: {}", batchId, e.getMessage());
            }
        });
    }

    /**
     * Fetches all rows from {@code link_clicks}, aggregates unique clickers per
     * batch, and returns a map of batchId → uniqueClickerCount.
     * Used by {@link com.outlookautoemailier.ui.AnalyticsController} to populate
     * the link-click-rate series in the open rate chart.
     */
    public static CompletableFuture<Map<String, Integer>> syncLinkClickCountsAsync() {
        String key = serviceRoleKey();
        if (key == null) return CompletableFuture.completedFuture(Collections.emptyMap());
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(SUPABASE_URL
                                + "/rest/v1/link_clicks"
                                + "?select=batch_id,tracking_id"
                                + "&limit=100000"))
                        .header("apikey",        key)
                        .header("Authorization", "Bearer " + key)
                        .GET().build();
                HttpResponse<String> resp =
                        client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() == 200) {
                    return aggregateLinkClickCounts(resp.body());
                }
                log.warn("syncLinkClickCounts returned {}", resp.statusCode());
            } catch (Exception e) {
                log.warn("Failed to sync link click counts: {}", e.getMessage());
            }
            return Collections.emptyMap();
        });
    }

    /**
     * Synchronously fetches click events for a batch from {@code link_clicks}
     * and returns them aggregated by URL.
     * Returns an empty list if the key is absent or an error occurs.
     */
    public static List<LinkClickRecord> fetchLinkClicksForBatch(String batchId) {
        String key = serviceRoleKey();
        if (key == null || batchId == null) return Collections.emptyList();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(SUPABASE_URL
                            + "/rest/v1/link_clicks"
                            + "?batch_id=eq." + URLEncoder.encode(batchId, StandardCharsets.UTF_8)
                            + "&select=original_url,tracking_id"))
                    .header("apikey",        key)
                    .header("Authorization", "Bearer " + key)
                    .GET().build();
            HttpResponse<String> resp =
                    client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                return aggregateClicksFromJson(batchId, resp.body());
            }
        } catch (Exception e) {
            log.warn("Failed to fetch link clicks: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static List<LinkClickRecord> aggregateClicksFromJson(String batchId, String json) {
        // Each row: {"original_url":"...","tracking_id":"..."}
        Pattern pat = Pattern.compile(
                "\"original_url\"\\s*:\\s*\"([^\"]+)\"[^}]*\"tracking_id\"\\s*:\\s*\"([^\"]+)\"");
        Matcher mat = pat.matcher(json);
        // url -> { total clicks, unique tracking_ids }
        Map<String, int[]>    counts  = new HashMap<>();
        Map<String, java.util.Set<String>> uniques = new HashMap<>();
        while (mat.find()) {
            String url  = mat.group(1);
            String tid  = mat.group(2);
            counts.computeIfAbsent(url, k -> new int[1])[0]++;
            uniques.computeIfAbsent(url, k -> new java.util.HashSet<>()).add(tid);
        }
        List<LinkClickRecord> result = new ArrayList<>();
        for (String url : counts.keySet()) {
            result.add(new LinkClickRecord(batchId, url,
                    counts.get(url)[0], uniques.get(url).size()));
        }
        result.sort((a, b) -> Integer.compare(b.getClickCount(), a.getClickCount()));
        return result;
    }

    private static void mergeBatchesFromJson(String json) {
        // Parse JSON array from Supabase REST response
        Pattern pat = Pattern.compile(
                "\"id\"\\s*:\\s*\"([^\"]+)\"[^}]*"
                + "\"batch_name\"\\s*:\\s*\"([^\"]*?)\"[^}]*"
                + "\"subject\"\\s*:\\s*\"([^\"]*?)\"[^}]*"
                + "\"sent_at\"\\s*:\\s*\"([^\"]*?)\"[^}]*"
                + "\"total_recipients\"\\s*:\\s*(\\d+)[^}]*"
                + "\"sent_count\"\\s*:\\s*(\\d+)[^}]*"
                + "\"failed_count\"\\s*:\\s*(\\d+)[^}]*"
                + "\"open_count\"\\s*:\\s*(\\d+)");
        Matcher mat = pat.matcher(json);
        BatchStore store = BatchStore.getInstance();
        int merged = 0;
        while (mat.find()) {
            try {
                String rawSentAt = mat.group(4);
                String normalized = rawSentAt.replaceAll("\\.[0-9]+", "")
                        .replace("Z", "").substring(0, 19);
                LocalDateTime sentAt = LocalDateTime.parse(normalized);
                EmailBatch batch = new EmailBatch(
                        mat.group(1), mat.group(2), mat.group(3), sentAt,
                        Integer.parseInt(mat.group(5)),
                        Integer.parseInt(mat.group(6)),
                        Integer.parseInt(mat.group(7)),
                        Integer.parseInt(mat.group(8)));
                store.addOrMerge(batch);
                merged++;
            } catch (Exception e) {
                log.debug("Could not parse batch from Supabase: {}", e.getMessage());
            }
        }
        log.info("Merged {} batches from Supabase.", merged);
    }

    private static void applyOpenEvents(String json) {
        // Parse JSON array with regex — avoids Jackson dependency on records we don't fully control
        Pattern pat = Pattern.compile(
                "\"tracking_id\"\\s*:\\s*\"([^\"]+)\"[^}]*"
                + "\"batch_id\"\\s*:\\s*([^,}]+)[^}]*"
                + "\"opened_at\"\\s*:\\s*\"([^\"]+)\"");
        Matcher mat = pat.matcher(json);
        SentEmailStore store      = SentEmailStore.getInstance();
        BatchStore     batchStore = BatchStore.getInstance();

        while (mat.find()) {
            String trackingId  = mat.group(1);
            String rawBatchId  = mat.group(2).trim().replace("\"", "");
            String openedAtStr = mat.group(3);

            try {
                // Normalise ISO timestamp from Postgres (strip microseconds + Z)
                String normalized = openedAtStr.replaceAll("\\.[0-9]+", "")
                        .replace("Z", "").substring(0, 19);
                LocalDateTime openedAt = LocalDateTime.parse(normalized);

                store.getAll().stream()
                        .filter(r -> trackingId.equals(r.getTrackingId()))
                        .filter(r -> r.getOpenedAt() == null)
                        .findFirst()
                        .ifPresent(r -> {
                            r.setOpenedAt(openedAt);
                            if (!rawBatchId.isBlank() && !"null".equals(rawBatchId)) {
                                batchStore.incrementOpens(rawBatchId);
                            }
                        });
            } catch (Exception e) {
                log.debug("Could not parse open event: {}", e.getMessage());
            }
        }
    }

    private static Map<String, Integer> aggregateLinkClickCounts(String json) {
        // Each row: {"batch_id":"...","tracking_id":"..."}
        Pattern pat = Pattern.compile(
                "\"batch_id\"\\s*:\\s*\"([^\"]+)\"[^}]*\"tracking_id\"\\s*:\\s*\"([^\"]+)\"");
        Matcher mat = pat.matcher(json);
        Map<String, Set<String>> batchToClickers = new HashMap<>();
        while (mat.find()) {
            batchToClickers
                    .computeIfAbsent(mat.group(1), k -> new HashSet<>())
                    .add(mat.group(2));
        }
        Map<String, Integer> result = new HashMap<>();
        batchToClickers.forEach((batchId, clickers) -> result.put(batchId, clickers.size()));
        return result;
    }

    private static void patch(String path, String body, String key) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(SUPABASE_URL + path))
                .header("apikey",        key)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type",  "application/json")
                .header("Prefer",        "return=minimal")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp =
                client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 400) {
            log.warn("Supabase PATCH {} returned {}: {}", path, resp.statusCode(), resp.body());
        }
    }

    private static void post(String path, String body, String key) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(SUPABASE_URL + path))
                .header("apikey",        key)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type",  "application/json")
                .header("Prefer",        "resolution=ignore-duplicates")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp =
                client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 400) {
            log.warn("Supabase POST {} returned {}: {}", path, resp.statusCode(), resp.body());
        }
    }

    private static String serviceRoleKey() {
        String k = DotEnvLoader.get("SUPABASE_SERVICE_ROLE_KEY");
        return (k == null || k.isBlank()) ? null : k;
    }

    /** Wraps a string value in JSON quotes with escaping. */
    private static String q(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    /** Returns {@code null} literal for null/blank, otherwise quoted. */
    private static String qOrNull(String s) {
        return (s == null || s.isBlank()) ? "null" : q(s);
    }

    private static String isoOf(LocalDateTime dt) {
        return dt != null ? dt.toString() : "";
    }
}
