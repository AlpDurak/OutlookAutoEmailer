package com.outlookautoemailier.integration;

import com.outlookautoemailier.model.EmailTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bidirectional sync between local email templates (stored as JSON files
 * under {@code ~/.outlookautoemailier/templates/}) and the Supabase
 * {@code email_templates} table.
 *
 * <h3>Supabase table</h3>
 * <pre>
 * CREATE TABLE email_templates (
 *   id         TEXT PRIMARY KEY,
 *   name       TEXT NOT NULL,
 *   subject    TEXT,
 *   body       TEXT,
 *   is_html    BOOLEAN DEFAULT false,
 *   created_at TIMESTAMPTZ DEFAULT now(),
 *   updated_at TIMESTAMPTZ DEFAULT now()
 * );
 * </pre>
 */
public final class SupabaseTemplateSync {

    private static final Logger log = LoggerFactory.getLogger(SupabaseTemplateSync.class);
    private static final SupabaseTemplateSync INSTANCE = new SupabaseTemplateSync();

    private SupabaseTemplateSync() {}

    public static SupabaseTemplateSync getInstance() { return INSTANCE; }

    // ── Push operations ──────────────────────────────────────────────────────

    /**
     * Upserts a template to Supabase. The template name is used as the
     * primary key (id) since templates are identified by name in the local store.
     */
    public void pushTemplateAsync(EmailTemplate template) {
        SupabaseClient client = SupabaseClient.getInstance();
        if (!client.isConfigured()) return;

        String body = "{"
                + "\"id\":"         + SupabaseClient.q(template.getName()) + ","
                + "\"name\":"       + SupabaseClient.q(template.getName()) + ","
                + "\"subject\":"    + SupabaseClient.q(template.getSubject()) + ","
                + "\"body\":"       + SupabaseClient.q(template.getBody()) + ","
                + "\"is_html\":"    + template.isHtml() + ","
                + "\"created_at\":" + SupabaseClient.q(isoOf(template.getCreatedAt())) + ","
                + "\"updated_at\":" + SupabaseClient.q(isoOf(template.getUpdatedAt()))
                + "}";
        client.upsertAsync("/rest/v1/email_templates", body);
    }

    /**
     * Deletes a template from Supabase by name.
     */
    public void deleteTemplateAsync(String templateName) {
        if (templateName == null) return;
        SupabaseClient client = SupabaseClient.getInstance();
        if (!client.isConfigured()) return;

        String path = "/rest/v1/email_templates?id=eq."
                + URLEncoder.encode(templateName, StandardCharsets.UTF_8);
        client.deleteAsync(path);
    }

    // ── Pull operations ──────────────────────────────────────────────────────

    /**
     * Fetches all templates from Supabase and returns them as a list.
     * Does NOT merge into local storage — the caller decides what to do.
     *
     * @return future containing the list of remote templates
     */
    public CompletableFuture<List<EmailTemplate>> pullAllAsync() {
        SupabaseClient client = SupabaseClient.getInstance();
        if (!client.isConfigured()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<String> resp = client.get(
                        "/rest/v1/email_templates?select=*&order=updated_at.desc");
                if (resp.statusCode() == 200) {
                    List<EmailTemplate> templates = parseTemplatesFromJson(resp.body());
                    log.info("Pulled {} template(s) from Supabase.", templates.size());
                    return templates;
                }
                log.warn("Supabase pullAllTemplates returned {}", resp.statusCode());
            } catch (Exception e) {
                log.warn("Failed to pull templates from Supabase: {}", e.getMessage());
            }
            return new ArrayList<>();
        });
    }

    // ── JSON parsing ─────────────────────────────────────────────────────────

    private static List<EmailTemplate> parseTemplatesFromJson(String json) {
        List<EmailTemplate> templates = new ArrayList<>();
        // Split into individual JSON objects — templates can have large bodies
        // so we use a more careful approach than simple regex on the whole string
        int depth = 0;
        int start = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String obj = json.substring(start, i + 1);
                    try {
                        EmailTemplate t = parseOneTemplate(obj);
                        if (t != null) templates.add(t);
                    } catch (Exception e) {
                        log.debug("Skipping malformed template: {}", e.getMessage());
                    }
                    start = -1;
                }
            }
        }
        return templates;
    }

    private static EmailTemplate parseOneTemplate(String obj) {
        String name    = extractField(obj, "name");
        String subject = extractField(obj, "subject");
        String body    = extractField(obj, "body");
        String isHtmlStr = extractRawField(obj, "is_html");
        String createdAtStr = extractField(obj, "created_at");
        String updatedAtStr = extractField(obj, "updated_at");

        if (name == null || subject == null || body == null) return null;

        // Unescape JSON string escapes
        body = body.replace("\\n", "\n").replace("\\r", "\r")
                   .replace("\\\"", "\"").replace("\\\\", "\\");
        subject = subject.replace("\\n", "\n").replace("\\r", "\r")
                         .replace("\\\"", "\"").replace("\\\\", "\\");

        boolean isHtml = "true".equalsIgnoreCase(isHtmlStr);
        LocalDateTime createdAt = parseTimestamp(createdAtStr);
        LocalDateTime updatedAt = parseTimestamp(updatedAtStr);

        return EmailTemplate.builder()
                .name(name)
                .subject(subject)
                .body(body)
                .html(isHtml)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }

    private static String extractField(String json, String field) {
        // Match "field":"value" — handles escaped quotes inside values
        Pattern pat = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher mat = pat.matcher(json);
        return mat.find() ? mat.group(1) : null;
    }

    private static String extractRawField(String json, String field) {
        // Match "field":value where value is not quoted (boolean, number, null)
        Pattern pat = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*([^,}\\s]+)");
        Matcher mat = pat.matcher(json);
        return mat.find() ? mat.group(1).trim() : null;
    }

    private static LocalDateTime parseTimestamp(String ts) {
        if (ts == null || ts.isBlank()) return LocalDateTime.now();
        try {
            String normalized = ts.replaceAll("\\.[0-9]+", "")
                    .replace("Z", "").replace("+00:00", "");
            if (normalized.length() >= 19) normalized = normalized.substring(0, 19);
            return LocalDateTime.parse(normalized);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private static String isoOf(LocalDateTime dt) {
        return dt != null ? dt.toString() : "";
    }
}
