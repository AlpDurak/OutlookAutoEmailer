package com.outlookautoemailier.integration;

import com.outlookautoemailier.config.DotEnvLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Centralized Supabase REST API client singleton.
 *
 * <p>All Supabase HTTP interactions should go through this class rather than
 * creating ad-hoc {@link HttpClient} instances.  Authentication is handled
 * automatically using the {@code SUPABASE_SERVICE_ROLE_KEY} from the
 * {@code .env} file via {@link DotEnvLoader}.</p>
 *
 * <h3>Required Supabase schema (run once in Supabase SQL editor)</h3>
 * <pre>{@code
 * -- Existing tables (already created):
 * -- email_batches, email_sends, unsubscribes, link_clicks
 *
 * -- New tables for full persistence:
 *
 * CREATE TABLE IF NOT EXISTS contact_groups (
 *   id         TEXT PRIMARY KEY,
 *   name       TEXT NOT NULL,
 *   emails     JSONB DEFAULT '[]'::jsonb,
 *   created_at TIMESTAMPTZ DEFAULT now()
 * );
 *
 * CREATE TABLE IF NOT EXISTS email_templates (
 *   id         TEXT PRIMARY KEY,
 *   name       TEXT NOT NULL,
 *   subject    TEXT,
 *   body       TEXT,
 *   is_html    BOOLEAN DEFAULT false,
 *   created_at TIMESTAMPTZ DEFAULT now(),
 *   updated_at TIMESTAMPTZ DEFAULT now()
 * );
 *
 * -- New columns on existing tables:
 *
 * ALTER TABLE email_sends
 *   ADD COLUMN IF NOT EXISTS contact_group_id TEXT;
 *
 * ALTER TABLE email_batches
 *   ADD COLUMN IF NOT EXISTS template_name TEXT;
 *
 * ALTER TABLE email_batches
 *   ADD COLUMN IF NOT EXISTS link_click_count INT DEFAULT 0;
 * }</pre>
 */
public final class SupabaseClient {

    private static final Logger log = LoggerFactory.getLogger(SupabaseClient.class);

    /** Supabase project URL. */
    static final String SUPABASE_URL = "https://tgbhgwdgqinxwxedhnmc.supabase.co";

    private static volatile SupabaseClient INSTANCE;

    private final HttpClient httpClient;

    private SupabaseClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Returns the singleton instance. */
    public static SupabaseClient getInstance() {
        if (INSTANCE == null) {
            synchronized (SupabaseClient.class) {
                if (INSTANCE == null) {
                    INSTANCE = new SupabaseClient();
                }
            }
        }
        return INSTANCE;
    }

    // ── Configuration helpers ────────────────────────────────────────────────

    /**
     * Returns the service role key from the .env file, or {@code null} if absent.
     * All callers should check for null before proceeding.
     */
    public String serviceRoleKey() {
        String k = DotEnvLoader.get("SUPABASE_SERVICE_ROLE_KEY");
        return (k == null || k.isBlank()) ? null : k;
    }

    /** Returns true when the service role key is configured and Supabase calls can proceed. */
    public boolean isConfigured() {
        return serviceRoleKey() != null;
    }

    // ── Synchronous HTTP methods ─────────────────────────────────────────────

    /**
     * Executes a GET request against the Supabase REST API.
     *
     * @param path REST path including query string (e.g., "/rest/v1/email_batches?select=*")
     * @return the HTTP response; caller must check status code
     * @throws Exception on I/O or interrupt
     */
    public HttpResponse<String> get(String path) throws Exception {
        String key = requireKey();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(SUPABASE_URL + path))
                .header("apikey", key)
                .header("Authorization", "Bearer " + key)
                .GET()
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * Executes a POST request.
     *
     * @param path REST path (e.g., "/rest/v1/contact_groups")
     * @param json JSON body
     * @return the HTTP response
     * @throws Exception on I/O or interrupt
     */
    public HttpResponse<String> post(String path, String json) throws Exception {
        String key = requireKey();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(SUPABASE_URL + path))
                .header("apikey", key)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .header("Prefer", "resolution=ignore-duplicates")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * Executes a PATCH request.
     *
     * @param path REST path with filter (e.g., "/rest/v1/email_batches?id=eq.abc")
     * @param json JSON body with updated fields
     * @return the HTTP response
     * @throws Exception on I/O or interrupt
     */
    public HttpResponse<String> patch(String path, String json) throws Exception {
        String key = requireKey();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(SUPABASE_URL + path))
                .header("apikey", key)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .header("Prefer", "return=minimal")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * Executes a DELETE request.
     *
     * @param path REST path with filter (e.g., "/rest/v1/contact_groups?id=eq.abc")
     * @return the HTTP response
     * @throws Exception on I/O or interrupt
     */
    public HttpResponse<String> delete(String path) throws Exception {
        String key = requireKey();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(SUPABASE_URL + path))
                .header("apikey", key)
                .header("Authorization", "Bearer " + key)
                .DELETE()
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * Executes a POST request that upserts (inserts or updates on conflict).
     *
     * @param path REST path
     * @param json JSON body
     * @return the HTTP response
     * @throws Exception on I/O or interrupt
     */
    public HttpResponse<String> upsert(String path, String json) throws Exception {
        String key = requireKey();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(SUPABASE_URL + path))
                .header("apikey", key)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .header("Prefer", "resolution=merge-duplicates")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ── Async convenience wrappers ───────────────────────────────────────────

    /** Fire-and-forget async POST. Errors are logged, not thrown. */
    public CompletableFuture<Void> postAsync(String path, String json) {
        if (!isConfigured()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<String> resp = post(path, json);
                if (resp.statusCode() >= 400) {
                    log.warn("Supabase POST {} returned {}: {}", path, resp.statusCode(), resp.body());
                }
            } catch (Exception e) {
                log.warn("Supabase async POST {} failed: {}", path, e.getMessage());
            }
        });
    }

    /** Fire-and-forget async UPSERT. Errors are logged, not thrown. */
    public CompletableFuture<Void> upsertAsync(String path, String json) {
        if (!isConfigured()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<String> resp = upsert(path, json);
                if (resp.statusCode() >= 400) {
                    log.warn("Supabase UPSERT {} returned {}: {}", path, resp.statusCode(), resp.body());
                }
            } catch (Exception e) {
                log.warn("Supabase async UPSERT {} failed: {}", path, e.getMessage());
            }
        });
    }

    /** Fire-and-forget async PATCH. Errors are logged, not thrown. */
    public CompletableFuture<Void> patchAsync(String path, String json) {
        if (!isConfigured()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<String> resp = patch(path, json);
                if (resp.statusCode() >= 400) {
                    log.warn("Supabase PATCH {} returned {}: {}", path, resp.statusCode(), resp.body());
                }
            } catch (Exception e) {
                log.warn("Supabase async PATCH {} failed: {}", path, e.getMessage());
            }
        });
    }

    /** Fire-and-forget async DELETE. Errors are logged, not thrown. */
    public CompletableFuture<Void> deleteAsync(String path) {
        if (!isConfigured()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<String> resp = delete(path);
                if (resp.statusCode() >= 400) {
                    log.warn("Supabase DELETE {} returned {}: {}", path, resp.statusCode(), resp.body());
                }
            } catch (Exception e) {
                log.warn("Supabase async DELETE {} failed: {}", path, e.getMessage());
            }
        });
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────

    /** Wraps a string value in JSON quotes with escaping. */
    public static String q(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    /** Returns {@code null} literal for null/blank, otherwise quoted. */
    public static String qOrNull(String s) {
        return (s == null || s.isBlank()) ? "null" : q(s);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private String requireKey() {
        String key = serviceRoleKey();
        if (key == null) {
            throw new IllegalStateException(
                    "SUPABASE_SERVICE_ROLE_KEY is not configured in .env");
        }
        return key;
    }
}
