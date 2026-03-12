package com.outlookautoemailier.analytics;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Holds the send metadata for one outbound email (analytics).
 *
 * <p>{@code openedAt} is mutable: it is set to {@code null} when the record
 * is first created and later updated by
 * {@link com.outlookautoemailier.integration.SupabaseAnalyticsSync} when the
 * tracking-pixel open event is pulled from Supabase.</p>
 */
public class SentEmailRecord {

    private final String        trackingId;
    private final String        batchId;          // null for legacy records
    private final String        recipientEmail;
    private final String        recipientName;
    private final String        subject;
    private final LocalDateTime sentAt;
    private final String        status;           // "SENT" | "FAILED"
    private final String        failureReason;    // null when status = SENT
    private volatile LocalDateTime openedAt;      // null until pixel fires

    // ── Constructors ──────────────────────────────────────────────────────────

    /** New SENT record with batch tracking (primary constructor). */
    public SentEmailRecord(String batchId, String recipientEmail, String recipientName,
                           String subject, LocalDateTime sentAt) {
        this.trackingId     = UUID.randomUUID().toString();
        this.batchId        = batchId;
        this.recipientEmail = recipientEmail;
        this.recipientName  = recipientName;
        this.subject        = subject;
        this.sentAt         = sentAt;
        this.status         = "SENT";
        this.failureReason  = null;
        this.openedAt       = null;
    }

    /** Legacy 4-arg constructor (no batchId) — kept for backward compatibility. */
    public SentEmailRecord(String recipientEmail, String recipientName,
                           String subject, LocalDateTime sentAt) {
        this(null, recipientEmail, recipientName, subject, sentAt);
    }

    /** Full deserialization constructor (all fields known). */
    public SentEmailRecord(String trackingId, String batchId, String recipientEmail,
                           String recipientName, String subject, LocalDateTime sentAt,
                           String status, String failureReason, LocalDateTime openedAt) {
        this.trackingId     = trackingId;
        this.batchId        = batchId;
        this.recipientEmail = recipientEmail;
        this.recipientName  = recipientName;
        this.subject        = subject;
        this.sentAt         = sentAt;
        this.status         = status;
        this.failureReason  = failureReason;
        this.openedAt       = openedAt;
    }

    /** Legacy 7-arg constructor (no batchId/openedAt). */
    public SentEmailRecord(String trackingId, String recipientEmail, String recipientName,
                           String subject, LocalDateTime sentAt,
                           String status, String failureReason) {
        this(trackingId, null, recipientEmail, recipientName, subject, sentAt, status, failureReason, null);
    }

    // ── Mutator ───────────────────────────────────────────────────────────────

    /** Called by SupabaseAnalyticsSync when an open event is pulled from Supabase. */
    public void setOpenedAt(LocalDateTime openedAt) {
        this.openedAt = openedAt;
    }

    // ── Derived metrics ───────────────────────────────────────────────────────

    /**
     * Time in minutes from {@code sentAt} to {@code openedAt}.
     * Returns {@code -1} if the email has not been opened yet.
     */
    public long openDelayMinutes() {
        if (openedAt == null || sentAt == null) return -1L;
        return java.time.Duration.between(sentAt, openedAt).toMinutes();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String        getTrackingId()    { return trackingId; }
    public String        getBatchId()       { return batchId; }
    public String        getRecipientEmail(){ return recipientEmail; }
    public String        getRecipientName() { return recipientName; }
    public String        getSubject()       { return subject; }
    public LocalDateTime getSentAt()        { return sentAt; }
    public String        getStatus()        { return status; }
    public String        getFailureReason() { return failureReason; }
    public LocalDateTime getOpenedAt()      { return openedAt; }
}
