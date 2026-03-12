package com.outlookautoemailier.analytics;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents one email campaign batch — a single compose-and-send action
 * targeting multiple recipients.
 *
 * <p>Counters ({@code sentCount}, {@code failedCount}, {@code openCount},
 * {@code linkClickCount}) are {@link AtomicInteger} so they can be updated
 * safely from worker threads and the Supabase sync thread.</p>
 */
public class EmailBatch {

    private final String           id;
    private final String           batchName;
    private final String           subject;
    private final LocalDateTime    sentAt;
    private final int              totalRecipients;

    // Thread-safe mutable counters
    private final AtomicInteger sentCount      = new AtomicInteger();
    private final AtomicInteger failedCount    = new AtomicInteger();
    private final AtomicInteger openCount      = new AtomicInteger();
    private final AtomicInteger linkClickCount = new AtomicInteger();

    /** New-batch constructor — all counters start at zero. */
    public EmailBatch(String id, String batchName, String subject,
                      LocalDateTime sentAt, int totalRecipients) {
        this.id              = id;
        this.batchName       = batchName;
        this.subject         = subject;
        this.sentAt          = sentAt;
        this.totalRecipients = totalRecipients;
    }

    /** Full deserialisation constructor (all counters restored from disk/Supabase). */
    public EmailBatch(String id, String batchName, String subject,
                      LocalDateTime sentAt, int totalRecipients,
                      int sentCount, int failedCount, int openCount) {
        this(id, batchName, subject, sentAt, totalRecipients);
        this.sentCount.set(sentCount);
        this.failedCount.set(failedCount);
        this.openCount.set(openCount);
    }

    /** Full deserialisation constructor (all counters including linkClickCount). */
    public EmailBatch(String id, String batchName, String subject,
                      LocalDateTime sentAt, int totalRecipients,
                      int sentCount, int failedCount, int openCount, int linkClickCount) {
        this(id, batchName, subject, sentAt, totalRecipients, sentCount, failedCount, openCount);
        this.linkClickCount.set(linkClickCount);
    }

    // ── Counter mutators ──────────────────────────────────────────────────────

    public void incrementSent()   { sentCount.incrementAndGet(); }
    public void incrementFailed() { failedCount.incrementAndGet(); }
    public void incrementOpens()  { openCount.incrementAndGet(); }

    /** Setters used by {@link com.outlookautoemailier.analytics.BatchStore#addOrMerge}
     *  to apply the higher value from Supabase without losing local live increments. */
    public void setSentCount(int n)      { sentCount.set(n); }
    public void setFailedCount(int n)    { failedCount.set(n); }
    public void setOpenCount(int n)      { openCount.set(n); }
    public void setLinkClickCount(int n) { linkClickCount.set(n); }

    // ── Derived analytics metrics ─────────────────────────────────────────────

    /** Open rate as a percentage of successfully delivered emails (0–100). */
    public double openRatePct() {
        int delivered = sentCount.get();
        return delivered == 0 ? 0.0 : (openCount.get() * 100.0) / delivered;
    }

    /** Link click rate as a percentage of delivered emails (0–100). */
    public double linkClickRatePct() {
        int delivered = sentCount.get();
        return delivered == 0 ? 0.0 : (linkClickCount.get() * 100.0) / delivered;
    }

    /** Delivery rate as a percentage of total attempted recipients (0–100). */
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
    public int            getLinkClickCount()  { return linkClickCount.get(); }

    @Override
    public String toString() {
        return "EmailBatch{id=" + id + ", name=" + batchName
                + ", sent=" + sentCount + ", failed=" + failedCount
                + ", opens=" + openCount + ", clicks=" + linkClickCount + "}";
    }
}
