package com.outlookautoemailier.analytics;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds the tracking data for one outbound email.
 * Thread-safe open count via AtomicInteger.
 */
public class SentEmailRecord {

    private final String        trackingId;     // UUID, used in pixel URL
    private final String        recipientEmail;
    private final String        recipientName;
    private final String        subject;
    private final LocalDateTime sentAt;
    private final AtomicInteger openCount = new AtomicInteger(0);
    private volatile LocalDateTime lastOpenedAt;

    public SentEmailRecord(String recipientEmail, String recipientName,
                           String subject, LocalDateTime sentAt) {
        this.trackingId    = UUID.randomUUID().toString();
        this.recipientEmail = recipientEmail;
        this.recipientName  = recipientName;
        this.subject        = subject;
        this.sentAt         = sentAt;
    }

    // Constructor for deserialisation (all fields known)
    public SentEmailRecord(String trackingId, String recipientEmail, String recipientName,
                           String subject, LocalDateTime sentAt, int openCount,
                           LocalDateTime lastOpenedAt) {
        this.trackingId     = trackingId;
        this.recipientEmail = recipientEmail;
        this.recipientName  = recipientName;
        this.subject        = subject;
        this.sentAt         = sentAt;
        this.openCount.set(openCount);
        this.lastOpenedAt   = lastOpenedAt;
    }

    public void recordOpen() {
        openCount.incrementAndGet();
        lastOpenedAt = LocalDateTime.now();
    }

    public String        getTrackingId()    { return trackingId; }
    public String        getRecipientEmail(){ return recipientEmail; }
    public String        getRecipientName() { return recipientName; }
    public String        getSubject()       { return subject; }
    public LocalDateTime getSentAt()        { return sentAt; }
    public int           getOpenCount()     { return openCount.get(); }
    public LocalDateTime getLastOpenedAt()  { return lastOpenedAt; }
}
