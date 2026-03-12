package com.outlookautoemailier.integration;

import com.outlookautoemailier.model.ContactGroup;
import com.outlookautoemailier.model.ContactGroupStore;
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
 * Bidirectional sync between the local {@link ContactGroupStore} and the
 * Supabase {@code contact_groups} table.
 *
 * <p>All methods are fire-and-forget async or return {@link CompletableFuture}.
 * If the Supabase service role key is not configured, all methods no-op.</p>
 *
 * <h3>Supabase table</h3>
 * <pre>
 * CREATE TABLE contact_groups (
 *   id         TEXT PRIMARY KEY,
 *   name       TEXT NOT NULL,
 *   emails     JSONB DEFAULT '[]'::jsonb,
 *   created_at TIMESTAMPTZ DEFAULT now()
 * );
 * </pre>
 */
public final class SupabaseContactGroupSync {

    private static final Logger log = LoggerFactory.getLogger(SupabaseContactGroupSync.class);
    private static final SupabaseContactGroupSync INSTANCE = new SupabaseContactGroupSync();

    private SupabaseContactGroupSync() {}

    public static SupabaseContactGroupSync getInstance() { return INSTANCE; }

    // ── Push operations ──────────────────────────────────────────────────────

    /**
     * Upserts a single contact group to Supabase asynchronously.
     * Safe to call on every local add/update — Supabase merge-duplicates
     * handles the insert-or-update logic.
     */
    public void pushGroupAsync(ContactGroup group) {
        SupabaseClient client = SupabaseClient.getInstance();
        if (!client.isConfigured()) return;

        String emailsJson = buildEmailsJsonArray(group.getContactEmails());
        String body = "{"
                + "\"id\":"         + SupabaseClient.q(group.getId())   + ","
                + "\"name\":"       + SupabaseClient.q(group.getName()) + ","
                + "\"emails\":"     + emailsJson                        + ","
                + "\"created_at\":" + SupabaseClient.q(isoOf(group.getCreatedAt()))
                + "}";
        client.upsertAsync("/rest/v1/contact_groups", body);
    }

    /**
     * Deletes a contact group from Supabase by ID.
     */
    public void deleteGroupAsync(String groupId) {
        if (groupId == null) return;
        SupabaseClient client = SupabaseClient.getInstance();
        if (!client.isConfigured()) return;

        String path = "/rest/v1/contact_groups?id=eq."
                + URLEncoder.encode(groupId, StandardCharsets.UTF_8);
        client.deleteAsync(path);
    }

    // ── Pull operations ──────────────────────────────────────────────────────

    /**
     * Fetches all contact groups from Supabase and merges them into the
     * local {@link ContactGroupStore}. Groups that already exist locally
     * (by ID) are skipped — local state wins.
     *
     * @return a future that completes when the sync is done
     */
    public CompletableFuture<Void> pullAllAsync() {
        SupabaseClient client = SupabaseClient.getInstance();
        if (!client.isConfigured()) return CompletableFuture.completedFuture(null);

        return CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<String> resp = client.get(
                        "/rest/v1/contact_groups?select=*&order=created_at.asc");
                if (resp.statusCode() == 200) {
                    List<ContactGroup> remote = parseGroupsFromJson(resp.body());
                    ContactGroupStore store = ContactGroupStore.getInstance();
                    int merged = 0;
                    for (ContactGroup g : remote) {
                        if (store.findById(g.getId()) == null) {
                            store.addGroup(g);
                            merged++;
                        }
                    }
                    log.info("Pulled {} new contact group(s) from Supabase.", merged);
                } else {
                    log.warn("Supabase pullAllGroups returned {}", resp.statusCode());
                }
            } catch (Exception e) {
                log.warn("Failed to pull contact groups from Supabase: {}", e.getMessage());
            }
        });
    }

    /**
     * Full bidirectional sync: pull remote groups, then push all local groups.
     */
    public CompletableFuture<Void> syncAsync() {
        return pullAllAsync().thenRun(() -> {
            ContactGroupStore store = ContactGroupStore.getInstance();
            for (ContactGroup g : store.getAll()) {
                pushGroupAsync(g);
            }
            log.info("Pushed {} local contact group(s) to Supabase.", store.getAll().size());
        });
    }

    // ── JSON parsing ─────────────────────────────────────────────────────────

    private static List<ContactGroup> parseGroupsFromJson(String json) {
        List<ContactGroup> groups = new ArrayList<>();
        // Parse each object from the JSON array
        Pattern objPat = Pattern.compile("\\{[^}]+}");
        Matcher objMat = objPat.matcher(json);
        while (objMat.find()) {
            String obj = objMat.group();
            try {
                String id = extractField(obj, "id");
                String name = extractField(obj, "name");
                String createdAtStr = extractField(obj, "created_at");
                List<String> emails = extractEmailsArray(obj);

                LocalDateTime createdAt = LocalDateTime.now();
                if (createdAtStr != null && !createdAtStr.isBlank()) {
                    try {
                        String normalized = createdAtStr.replaceAll("\\.[0-9]+", "")
                                .replace("Z", "");
                        if (normalized.length() >= 19) {
                            normalized = normalized.substring(0, 19);
                        }
                        createdAt = LocalDateTime.parse(normalized);
                    } catch (Exception e) {
                        log.debug("Could not parse created_at: {}", createdAtStr);
                    }
                }

                if (id != null && name != null) {
                    groups.add(new ContactGroup(id, name, emails, createdAt));
                }
            } catch (Exception e) {
                log.debug("Skipping malformed contact group: {}", e.getMessage());
            }
        }
        return groups;
    }

    private static String extractField(String json, String field) {
        Pattern pat = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*?)\"");
        Matcher mat = pat.matcher(json);
        return mat.find() ? mat.group(1) : null;
    }

    private static List<String> extractEmailsArray(String json) {
        List<String> emails = new ArrayList<>();
        // Find the emails array value — could be a JSON array
        Pattern pat = Pattern.compile("\"emails\"\\s*:\\s*\\[([^\\]]*)]");
        Matcher mat = pat.matcher(json);
        if (mat.find()) {
            String arr = mat.group(1);
            Pattern emailPat = Pattern.compile("\"([^\"]+)\"");
            Matcher emailMat = emailPat.matcher(arr);
            while (emailMat.find()) {
                emails.add(emailMat.group(1));
            }
        }
        return emails;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String buildEmailsJsonArray(List<String> emails) {
        if (emails == null || emails.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < emails.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(SupabaseClient.q(emails.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String isoOf(LocalDateTime dt) {
        return dt != null ? dt.toString() : "";
    }
}
