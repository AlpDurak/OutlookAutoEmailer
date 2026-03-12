package com.outlookautoemailier.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.outlookautoemailier.config.DotEnvLoader;
import com.outlookautoemailier.security.UnsubscribeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Bidirectional sync between the local suppression list
 * ({@code ~/.outlookautoemailier/unsubscribed.txt}) and the remote
 * Supabase {@code unsubscribes} table.
 *
 * <p>On each call to {@link #syncAsync()}:
 * <ol>
 *   <li>All emails in the remote table are fetched and added to the local
 *       {@link UnsubscribeManager} (if not already present).</li>
 *   <li>All emails in the local suppression list are upserted into the
 *       remote table (so manual app-side suppressions propagate to the DB).</li>
 * </ol>
 *
 * <p>The sync is performed on a daemon thread so it never blocks the UI.
 * If the {@code SUPABASE_SERVICE_ROLE_KEY} is not set in {@code .env}
 * the method returns immediately without doing anything.
 */
public final class SupabaseUnsubscribeSync {

    private static final Logger log = LoggerFactory.getLogger(SupabaseUnsubscribeSync.class);

    private static final String SUPABASE_URL = "https://tgbhgwdgqinxwxedhnmc.supabase.co";
    private static final String REST_BASE    = SUPABASE_URL + "/rest/v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient   CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private SupabaseUnsubscribeSync() {}

    /**
     * Starts a background sync.  Returns immediately; errors are only logged.
     */
    public static void syncAsync() {
        String key = DotEnvLoader.get("SUPABASE_SERVICE_ROLE_KEY");
        if (key.isBlank()) {
            log.debug("SUPABASE_SERVICE_ROLE_KEY not set — skipping sync.");
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                sync(key);
            } catch (Exception e) {
                log.warn("Supabase unsubscribe sync failed: {}", e.getMessage());
            }
        });
    }

    // ── Private impl ───────────────────────────────────────────────────────────

    private static void sync(String serviceRoleKey) throws Exception {
        UnsubscribeManager mgr = UnsubscribeManager.getInstance();
        mgr.ensureLoaded();

        // ── Step 1: pull remote → local ─────────────────────────────────────
        HttpRequest fetchReq = HttpRequest.newBuilder()
                .uri(URI.create(REST_BASE + "/unsubscribes?select=email"))
                .header("apikey",        serviceRoleKey)
                .header("Authorization", "Bearer " + serviceRoleKey)
                .GET()
                .build();

        HttpResponse<String> fetchRes = CLIENT.send(fetchReq, HttpResponse.BodyHandlers.ofString());

        if (fetchRes.statusCode() == 200) {
            JsonNode arr = MAPPER.readTree(fetchRes.body());
            int added = 0;
            if (arr.isArray()) {
                for (JsonNode row : arr) {
                    String email = row.path("email").asText("").trim().toLowerCase();
                    if (!email.isBlank() && !mgr.isSuppressed(email)) {
                        mgr.addUnsubscribe(email);
                        added++;
                    }
                }
            }
            log.info("Supabase sync: {} new remote unsubscribe(s) added to local list.", added);
        } else {
            log.warn("Supabase fetch failed — HTTP {}: {}", fetchRes.statusCode(), fetchRes.body());
        }

        // ── Step 2: push local → remote ─────────────────────────────────────
        Set<String> localAll = mgr.getAllSuppressed();
        int pushed = 0;
        for (String email : localAll) {
            String json = "{\"email\":\"" + escapeJson(email) + "\",\"source\":\"app_manual\"}";
            HttpRequest pushReq = HttpRequest.newBuilder()
                    .uri(URI.create(REST_BASE + "/unsubscribes"))
                    .header("apikey",        serviceRoleKey)
                    .header("Authorization", "Bearer " + serviceRoleKey)
                    .header("Content-Type",  "application/json")
                    .header("Prefer",        "resolution=ignore-duplicates")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> pushRes = CLIENT.send(pushReq, HttpResponse.BodyHandlers.ofString());
            if (pushRes.statusCode() < 300) pushed++;
        }
        log.info("Supabase sync: {} local unsubscribe(s) pushed to remote.", pushed);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
