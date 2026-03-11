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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores and retrieves sent-email tracking records.
 * Thread-safe singleton.
 */
public class SentEmailStore {

    private static final Logger log = LoggerFactory.getLogger(SentEmailStore.class);
    private static final SentEmailStore INSTANCE = new SentEmailStore();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path storePath;
    // trackingId → record (in-memory index for fast lookup by tracking pixel server)
    private final Map<String, SentEmailRecord> byTrackingId = new ConcurrentHashMap<>();
    // ordered list for the UI table
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
        byTrackingId.put(record.getTrackingId(), record);
        appendToDisk(record);
    }

    /** Looks up a record by tracking ID (called by TrackingPixelServer on opens). */
    public SentEmailRecord findByTrackingId(String id) {
        return byTrackingId.get(id);
    }

    /** Returns an unmodifiable snapshot for the UI. Newest first. */
    public List<SentEmailRecord> getAll() {
        List<SentEmailRecord> copy;
        synchronized (records) { copy = new ArrayList<>(records); }
        Collections.reverse(copy);
        return Collections.unmodifiableList(copy);
    }

    /** Re-writes the whole file after an open is recorded (to update open counts). */
    public void flush() {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(storePath,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            synchronized (records) {
                for (SentEmailRecord r : records) {
                    pw.println(toJson(r));
                }
            }
        } catch (Exception e) {
            log.error("SentEmailStore flush failed", e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

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
                    SentEmailRecord r = fromJson(line);
                    records.add(r);
                    byTrackingId.put(r.getTrackingId(), r);
                } catch (Exception e) {
                    log.warn("Skipping malformed record: {}", line);
                }
            }
        }
        log.info("Loaded {} sent-email records from disk.", records.size());
    }

    private static String toJson(SentEmailRecord r) throws Exception {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("trackingId",    r.getTrackingId());
        n.put("recipientEmail",r.getRecipientEmail());
        n.put("recipientName", r.getRecipientName());
        n.put("subject",       r.getSubject());
        n.put("sentAt",        r.getSentAt() != null ? r.getSentAt().toString() : "");
        n.put("openCount",     r.getOpenCount());
        n.put("lastOpenedAt",  r.getLastOpenedAt() != null ? r.getLastOpenedAt().toString() : "");
        return MAPPER.writeValueAsString(n);
    }

    private static SentEmailRecord fromJson(String line) throws Exception {
        JsonNode n = MAPPER.readTree(line);
        String lastOpenStr = n.path("lastOpenedAt").asText("");
        LocalDateTime lastOpened = lastOpenStr.isBlank() ? null : LocalDateTime.parse(lastOpenStr);
        String sentAtStr = n.path("sentAt").asText("");
        LocalDateTime sentAt = sentAtStr.isBlank() ? null : LocalDateTime.parse(sentAtStr);
        return new SentEmailRecord(
                n.path("trackingId").asText(),
                n.path("recipientEmail").asText(),
                n.path("recipientName").asText(),
                n.path("subject").asText(),
                sentAt,
                n.path("openCount").asInt(0),
                lastOpened
        );
    }
}
