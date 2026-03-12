package com.outlookautoemailier.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Stores and retrieves sent-email records.
 * Thread-safe singleton; records are appended to a JSONL file on disk.
 */
public class SentEmailStore {

    private static final Logger      log      = LoggerFactory.getLogger(SentEmailStore.class);
    private static final ObjectMapper MAPPER   = new ObjectMapper();
    private static final SentEmailStore INSTANCE = new SentEmailStore();

    private final Path storePath;
    private final List<SentEmailRecord> records = Collections.synchronizedList(new ArrayList<>());

    private SentEmailStore() {
        storePath = Path.of(System.getProperty("user.home"),
                ".outlookautoemailier", "analytics", "sent-emails.jsonl");
        try {
            Files.createDirectories(storePath.getParent());
            loadFromDisk();
        } catch (Exception e) {
            log.error("SentEmailStore init failed", e);
        }
    }

    public static SentEmailStore getInstance() { return INSTANCE; }

    /** Adds a new record and appends it to disk immediately. */
    public void add(SentEmailRecord record) {
        records.add(record);
        appendToDisk(record);
    }

    /** Returns an unmodifiable snapshot for the UI, newest first. */
    public List<SentEmailRecord> getAll() {
        List<SentEmailRecord> copy;
        synchronized (records) { copy = new ArrayList<>(records); }
        Collections.reverse(copy);
        return Collections.unmodifiableList(copy);
    }

    /** Returns all records belonging to the given batch, newest first. */
    public List<SentEmailRecord> getByBatchId(String batchId) {
        if (batchId == null) return Collections.emptyList();
        List<SentEmailRecord> copy;
        synchronized (records) { copy = new ArrayList<>(records); }
        List<SentEmailRecord> result = new ArrayList<>();
        for (SentEmailRecord r : copy) {
            if (batchId.equals(r.getBatchId())) result.add(r);
        }
        Collections.reverse(result);
        return Collections.unmodifiableList(result);
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void appendToDisk(SentEmailRecord r) {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(storePath,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            pw.println(toJson(r));
        } catch (Exception e) {
            log.error("Failed to append sent email record", e);
        }
    }

    private void loadFromDisk() throws Exception {
        if (!Files.exists(storePath)) return;
        try (BufferedReader br = Files.newBufferedReader(storePath)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    records.add(fromJson(line));
                } catch (Exception e) {
                    log.warn("Skipping malformed record: {}", line);
                }
            }
        }
        log.info("Loaded {} sent-email records from disk.", records.size());
    }

    private static String toJson(SentEmailRecord r) throws Exception {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("trackingId",     r.getTrackingId());
        n.put("batchId",        r.getBatchId()       != null ? r.getBatchId()       : "");
        n.put("recipientEmail", r.getRecipientEmail());
        n.put("recipientName",  r.getRecipientName());
        n.put("subject",        r.getSubject());
        n.put("sentAt",         r.getSentAt()        != null ? r.getSentAt().toString()    : "");
        n.put("status",         r.getStatus()        != null ? r.getStatus()               : "SENT");
        n.put("failureReason",  r.getFailureReason() != null ? r.getFailureReason()        : "");
        n.put("openedAt",       r.getOpenedAt()      != null ? r.getOpenedAt().toString()  : "");
        return MAPPER.writeValueAsString(n);
    }

    private static SentEmailRecord fromJson(String line) throws Exception {
        JsonNode n           = MAPPER.readTree(line);
        String sentAtStr     = n.path("sentAt").asText("");
        String openedAtStr   = n.path("openedAt").asText("");
        LocalDateTime sentAt   = sentAtStr.isBlank()   ? null : LocalDateTime.parse(sentAtStr);
        LocalDateTime openedAt = openedAtStr.isBlank() ? null : LocalDateTime.parse(openedAtStr);
        String status          = n.path("status").asText("SENT");
        String failureReason   = n.path("failureReason").asText("");
        String batchId         = n.path("batchId").asText("");
        return new SentEmailRecord(
                n.path("trackingId").asText(),
                batchId.isBlank() ? null : batchId,
                n.path("recipientEmail").asText(),
                n.path("recipientName").asText(),
                n.path("subject").asText(),
                sentAt,
                status,
                failureReason.isBlank() ? null : failureReason,
                openedAt
        );
    }
}
