package com.outlookautoemailier.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe singleton that persists {@link EmailBatch} objects to
 * {@code ~/.outlookautoemailier/analytics/batches.json}.
 */
public class BatchStore {

    private static final Logger      log      = LoggerFactory.getLogger(BatchStore.class);
    private static final ObjectMapper MAPPER  = new ObjectMapper();
    private static final BatchStore  INSTANCE = new BatchStore();

    private final Path storePath;
    private final List<EmailBatch> batches = new CopyOnWriteArrayList<>();

    private BatchStore() {
        storePath = Path.of(System.getProperty("user.home"),
                ".outlookautoemailier", "analytics", "batches.json");
        try {
            Files.createDirectories(storePath.getParent());
            loadFromDisk();
        } catch (Exception e) {
            log.error("BatchStore init failed", e);
        }
    }

    public static BatchStore getInstance() { return INSTANCE; }

    // ── Mutations ─────────────────────────────────────────────────────────────

    /** Adds a new batch and immediately persists to disk. */
    public synchronized void addBatch(EmailBatch batch) {
        batches.add(batch);
        saveToDisk();
    }

    /**
     * Adds the batch if its id is not yet known.
     * If it already exists, takes the maximum of local vs incoming counters so that
     * Supabase data (which has the authoritative counts) can flow in without
     * losing live in-flight increments.
     */
    public synchronized void addOrMerge(EmailBatch incoming) {
        Optional<EmailBatch> existing = getById(incoming.getId());
        if (existing.isPresent()) {
            EmailBatch b = existing.get();
            boolean changed = false;
            if (incoming.getSentCount() > b.getSentCount()) {
                b.setSentCount(incoming.getSentCount()); changed = true;
            }
            if (incoming.getFailedCount() > b.getFailedCount()) {
                b.setFailedCount(incoming.getFailedCount()); changed = true;
            }
            if (incoming.getOpenCount() > b.getOpenCount()) {
                b.setOpenCount(incoming.getOpenCount()); changed = true;
            }
            if (changed) saveToDisk();
            return;
        }
        batches.add(incoming);
        saveToDisk();
    }

    /**
     * Increments the sent counter for the batch identified by {@code batchId},
     * then saves to disk. Safe to call from worker threads.
     */
    public void incrementSent(String batchId) {
        getById(batchId).ifPresent(b -> { b.incrementSent();   saveToDisk(); });
    }

    /** Increments the failed counter and saves. */
    public void incrementFailed(String batchId) {
        getById(batchId).ifPresent(b -> { b.incrementFailed(); saveToDisk(); });
    }

    /** Increments the open counter and saves. */
    public void incrementOpens(String batchId) {
        getById(batchId).ifPresent(b -> { b.incrementOpens();  saveToDisk(); });
    }

    /** Sets the link-click count for a batch (applied from Supabase sync). */
    public void setLinkClickCount(String batchId, int count) {
        getById(batchId).ifPresent(b -> { b.setLinkClickCount(count); saveToDisk(); });
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** Returns an unmodifiable snapshot, newest-first. */
    public List<EmailBatch> getAll() {
        List<EmailBatch> copy = new ArrayList<>(batches);
        Collections.reverse(copy);
        return Collections.unmodifiableList(copy);
    }

    public Optional<EmailBatch> getById(String id) {
        if (id == null) return Optional.empty();
        return batches.stream().filter(b -> id.equals(b.getId())).findFirst();
    }

    // ── Disk I/O ──────────────────────────────────────────────────────────────

    private synchronized void saveToDisk() {
        try {
            ArrayNode arr = MAPPER.createArrayNode();
            for (EmailBatch b : batches) {
                ObjectNode n = MAPPER.createObjectNode();
                n.put("id",              b.getId());
                n.put("batchName",       b.getBatchName());
                n.put("subject",         b.getSubject());
                n.put("sentAt",          b.getSentAt() != null ? b.getSentAt().toString() : "");
                n.put("totalRecipients", b.getTotalRecipients());
                n.put("sentCount",       b.getSentCount());
                n.put("failedCount",     b.getFailedCount());
                n.put("openCount",       b.getOpenCount());
                n.put("linkClickCount",  b.getLinkClickCount());
                arr.add(n);
            }
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(storePath.toFile(), arr);
        } catch (Exception e) {
            log.error("BatchStore saveToDisk failed", e);
        }
    }

    private void loadFromDisk() throws Exception {
        if (!Files.exists(storePath)) return;
        JsonNode root = MAPPER.readTree(storePath.toFile());
        if (!root.isArray()) return;
        for (JsonNode n : root) {
            try {
                String raw    = n.path("sentAt").asText("");
                LocalDateTime sentAt = raw.isBlank() ? LocalDateTime.now()
                        : LocalDateTime.parse(raw);
                batches.add(new EmailBatch(
                        n.path("id").asText(),
                        n.path("batchName").asText("Unknown"),
                        n.path("subject").asText(""),
                        sentAt,
                        n.path("totalRecipients").asInt(0),
                        n.path("sentCount").asInt(0),
                        n.path("failedCount").asInt(0),
                        n.path("openCount").asInt(0),
                        n.path("linkClickCount").asInt(0)
                ));
            } catch (Exception e) {
                log.warn("Skipping malformed batch entry: {}", e.getMessage());
            }
        }
        log.info("Loaded {} batches from disk.", batches.size());
    }
}
