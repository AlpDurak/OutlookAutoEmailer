package com.outlookautoemailier.analytics;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents one email campaign batch — a single compose-and-send action
 * targeting multiple recipients.
 *
 * <p>Counters ({@code sentCount}, {@code failedCount}, {@code openCount}) are
 * {@link AtomicInteger} so they can be incremented safely from the
 * {@code EmailDispatcher} worker threads.</p>
 */
public class EmailBatch {

    private final String           id;
    private final String           batchName;
    private final String           subject;
    private final LocalDateTime    sentAt;
    private final int              totalRecipients;

    // Thread-safe mutable counters
    private final AtomicInteger sentCount   = new AtomicInteger();
    private final AtomicInteger failedCount = new AtomicInteger();
    private final AtomicInteger openCount   = new AtomicInteger();

    /** New-batch constructor — all counters start at zero. */
    public EmailBatch(String id, String batchName, String subject,
                      LocalDateTime sentAt, int totalRecipients) {
        this.id              = id;
        this.batchName       = batchName;
        this.subject         = subject;
        this.sentAt          = sentAt;
        this.totalRecipients = totalRecipients;
    }

    /** Full deserialisation constructor (all counters restored from disk). */
    public EmailBatch(String id, String batchName, String subject,
                      LocalDateTime sentAt, int totalRecipients,
                      int sentCount, int failedCount, int openCount) {
        this(id, batchName, subject, sentAt, totalRecipients);
        this.sentCount.set(sentCount);
        this.failedCount.set(failedCount);
        this.openCount.set(openCount);
    }

    // ── Counter mutators ──────────────────────────────────────────────────────

    public void incrementSent()   { sentCount.incrementAndGet(); }
    public void incrementFailed() { failedCount.incrementAndGet(); }
    public void incrementOpens()  { openCount.incrementAndGet(); }

    // ── Derived analytics metrics ─────────────────────────────────────────────

    /**
     * Open rate as a percentage of successfully delivered emails (0–100).
     * Returns 0 if nothing was delivered yet.
     */
    public double openRatePct() {
        int delivered = sentCount.get();
        return delivered == 0 ? 0.0 : (openCount.get() * 100.0) / delivered;
    }

    /**
     * Delivery rate as a percentage of total attempted recipients (0–100).
     * Returns 0 if no recipients.
     */
    public double deliveryRatePct() {
        return totalRecipients == 0 ? 0.0 : (sentCount.get() * 100.0) / totalRecipients;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String         getId()              { return id; }
    public String         getBatchName()       { return batchName; }
    public String         getSubject()         { return subject; }
    public LocalDateTime  getSentAt()          { return sentAt; }
    public int            getTotalRecipients() { return totalRecipients; }
    public int            getSentCount()       { return sentCount.get(); }
    public int            getFailedCount()     { return failedCount.get(); }
    public int            getOpenCount()       { return openCount.get(); }

    @Override
    public String toString() {
        return "EmailBatch{id=" + id + ", name=" + batchName
                + ", sent=" + sentCount + ", failed=" + failedCount
                + ", opens=" + openCount + "}";
    }
}
