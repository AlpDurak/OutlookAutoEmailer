package com.outlookautoemailier.security;

import com.outlookautoemailier.model.EmailJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only security audit trail that writes one JSON Lines (JSON-L) record
 * per email event to a persistent file on disk.
 *
 * <p>Storage location:
 * {@code $HOME/.outlookautoemailier/audit.jsonl}
 *
 * <p>Each line is a self-contained JSON object (no outer array).  The file is
 * opened in append mode so that records survive application restarts and are
 * never overwritten.
 *
 * <p>Thread-safety: the singleton is initialised with double-checked locking
 * on a {@code volatile} field.  All writes are serialised through a
 * {@link ReentrantLock} so that concurrent sends do not interleave partial
 * lines.
 *
 * <p>Fault tolerance: an {@link IOException} during a write is logged at
 * ERROR level but is never propagated to callers.  The audit log must never
 * crash the send pipeline.
 */
public final class SecurityAuditLog {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditLog.class);

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // -----------------------------------------------------------------------
    //  Singleton
    // -----------------------------------------------------------------------

    private static volatile SecurityAuditLog INSTANCE;

    /**
     * Returns the shared {@code SecurityAuditLog} instance, creating it on the
     * first call (double-checked locking).
     */
    public static SecurityAuditLog getInstance() {
        if (INSTANCE == null) {
            synchronized (SecurityAuditLog.class) {
                if (INSTANCE == null) {
                    INSTANCE = new SecurityAuditLog();
                }
            }
        }
        return INSTANCE;
    }

    // -----------------------------------------------------------------------
    //  Fields
    // -----------------------------------------------------------------------

    private final Path auditFile;
    private final ReentrantLock writeLock = new ReentrantLock();

    // -----------------------------------------------------------------------
    //  Constructor (private — use getInstance())
    // -----------------------------------------------------------------------

    private SecurityAuditLog() {
        Path dir = Paths.get(System.getProperty("user.home"), ".outlookautoemailier");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("Failed to create audit log directory '{}': {}", dir, e.getMessage());
        }
        this.auditFile = dir.resolve("audit.jsonl");
        log.info("SecurityAuditLog initialised. Audit file: {}", auditFile);
    }

    // -----------------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------------

    /**
     * Records a successful email send event.
     *
     * @param job the job that was successfully delivered
     */
    public void logSent(EmailJob job) {
        if (job == null) {
            log.warn("logSent() called with null job — skipping audit entry.");
            return;
        }
        String line = String.format(
                "{\"ts\":\"%s\",\"event\":\"SENT\",\"jobId\":\"%s\",\"recipient\":\"%s\","
                + "\"subject\":\"%s\",\"attempt\":%d}",
                ts(),
                escape(job.getId().toString()),
                escape(job.getContact().getPrimaryEmail()),
                escape(job.getTemplate().getSubject()),
                job.getAttemptCount()
        );
        write(line);
    }

    /**
     * Records a failed email send event.
     *
     * @param job    the job whose delivery failed
     * @param reason human-readable description of the failure
     */
    public void logFailed(EmailJob job, String reason) {
        if (job == null) {
            log.warn("logFailed() called with null job — skipping audit entry.");
            return;
        }
        String line = String.format(
                "{\"ts\":\"%s\",\"event\":\"FAILED\",\"jobId\":\"%s\",\"recipient\":\"%s\","
                + "\"reason\":\"%s\",\"attempt\":%d}",
                ts(),
                escape(job.getId().toString()),
                escape(job.getContact().getPrimaryEmail()),
                escape(reason != null ? reason : ""),
                job.getAttemptCount()
        );
        write(line);
    }

    /**
     * Records a skipped send (suppressed or invalid address).
     *
     * @param recipientEmail the address that was skipped
     * @param reason         why the address was skipped
     */
    public void logSkipped(String recipientEmail, String reason) {
        String line = String.format(
                "{\"ts\":\"%s\",\"event\":\"SKIPPED\",\"recipient\":\"%s\",\"reason\":\"%s\"}",
                ts(),
                escape(recipientEmail != null ? recipientEmail : ""),
                escape(reason         != null ? reason         : "")
        );
        write(line);
    }

    /**
     * Records backend startup, shutdown, or other lifecycle events.
     *
     * @param event  short event identifier, e.g. {@code "BACKEND_START"}
     * @param detail supplementary detail string, e.g. the sender address
     */
    public void logEvent(String event, String detail) {
        String line = String.format(
                "{\"ts\":\"%s\",\"event\":\"%s\",\"detail\":\"%s\"}",
                ts(),
                escape(event  != null ? event  : ""),
                escape(detail != null ? detail : "")
        );
        write(line);
    }

    // -----------------------------------------------------------------------
    //  Private helpers
    // -----------------------------------------------------------------------

    /**
     * Writes a single JSON-L line to the audit file.  Thread-safe via
     * {@link #writeLock}.  IOExceptions are swallowed and logged at ERROR
     * level so that audit failures never crash the send pipeline.
     *
     * @param jsonLine the complete JSON object string (no trailing newline needed)
     */
    private void write(String jsonLine) {
        writeLock.lock();
        try {
            Files.write(
                    auditFile,
                    List.of(jsonLine + "\n"),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.CREATE
            );
        } catch (IOException e) {
            log.error("Failed to write audit record to '{}': {}", auditFile, e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Returns the current timestamp formatted as ISO-8601 local date-time.
     */
    private static String ts() {
        return LocalDateTime.now().format(TS_FMT);
    }

    /**
     * Minimal JSON string escaping: replaces {@code "} with {@code \"} and
     * {@code \n} with the two-character literal {@code \n} so that values
     * are safe to embed inside a JSON string literal.
     *
     * @param value the raw string value; must not be null
     * @return the escaped string
     */
    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
