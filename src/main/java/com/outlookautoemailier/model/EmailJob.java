package com.outlookautoemailier.model;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a single email dispatch task targeting one {@link Contact} using
 * one {@link EmailTemplate}.
 *
 * <p>Jobs are prioritised by their {@code priority} field (lower value = higher
 * priority) and implement {@link Comparable} so they can be used directly in a
 * {@link java.util.PriorityQueue}.</p>
 *
 * <p>State transitions:
 * <pre>
 *   PENDING ──► SENDING ──► SENT
 *                       └──► FAILED ──► (retry) ──► DEAD_LETTER
 * </pre>
 * </p>
 */
public final class EmailJob implements Comparable<EmailJob> {

    // ------------------------------------------------------------------ //
    //  Status enum                                                         //
    // ------------------------------------------------------------------ //

    public enum JobStatus {
        /** Waiting to be picked up by the dispatcher. */
        PENDING,
        /** Currently being transmitted. */
        SENDING,
        /** Successfully delivered. */
        SENT,
        /** Delivery failed; may be retried. */
        FAILED,
        /** Exhausted all retry attempts; no further action will be taken. */
        DEAD_LETTER,
        /** Waiting for a scheduled date/time before dispatching. */
        SCHEDULED
    }

    // ------------------------------------------------------------------ //
    //  Fields                                                              //
    // ------------------------------------------------------------------ //

    private final UUID          id;
    private final String        batchId;      // groups jobs from the same compose-and-send action
    private final Contact       contact;
    private final EmailTemplate template;
    private final int           priority;
    private final int           maxAttempts;
    private final LocalDateTime createdAt;
    private final LocalDateTime scheduledAt;

    // Mutable state — updated during the job lifecycle
    private volatile int          attemptCount;
    private volatile JobStatus    status;
    private volatile LocalDateTime lastAttemptAt;
    private volatile String       errorMessage;

    private EmailJob(Builder builder) {
        this.id           = builder.id != null ? builder.id : UUID.randomUUID();
        this.batchId      = builder.batchId;
        this.contact      = Objects.requireNonNull(builder.contact,  "contact must not be null");
        this.template     = Objects.requireNonNull(builder.template, "template must not be null");
        this.priority     = builder.priority;
        this.maxAttempts  = builder.maxAttempts;
        this.createdAt    = builder.createdAt != null ? builder.createdAt : LocalDateTime.now();
        this.scheduledAt  = builder.scheduledAt;
        this.status       = builder.status != null ? builder.status
                          : (builder.scheduledAt != null ? JobStatus.SCHEDULED : JobStatus.PENDING);
        this.attemptCount = builder.attemptCount;
        this.lastAttemptAt = builder.lastAttemptAt;
        this.errorMessage = builder.errorMessage;
    }

    // ------------------------------------------------------------------ //
    //  Business logic                                                      //
    // ------------------------------------------------------------------ //

    /**
     * Records one delivery attempt.  Updates {@link #lastAttemptAt} to now and
     * increments {@link #attemptCount}.
     */
    public synchronized void incrementAttempt() {
        this.attemptCount++;
        this.lastAttemptAt = LocalDateTime.now();
    }

    /**
     * Transitions a SCHEDULED job to PENDING once its scheduled time arrives.
     */
    public synchronized void markPending() {
        this.status = JobStatus.PENDING;
    }

    /**
     * Marks the job as successfully sent.
     */
    public synchronized void markSent() {
        this.status = JobStatus.SENT;
    }

    /**
     * Marks the job as failed and records the reason.  If the job has
     * exhausted all attempts it is automatically moved to
     * {@link JobStatus#DEAD_LETTER}.
     *
     * @param reason human-readable description of the failure
     */
    public synchronized void markFailed(String reason) {
        this.errorMessage = reason;
        if (isDead()) {
            this.status = JobStatus.DEAD_LETTER;
        } else {
            this.status = JobStatus.FAILED;
        }
    }

    /**
     * Returns {@code true} when the job has used all of its allowed attempts
     * and should not be retried again.
     */
    public boolean isDead() {
        return attemptCount >= maxAttempts;
    }

    // ------------------------------------------------------------------ //
    //  Comparable                                                          //
    // ------------------------------------------------------------------ //

    /**
     * Natural ordering by priority (ascending) so that a lower numeric value
     * sorts first — i.e., priority 1 is processed before priority 5.
     */
    @Override
    public int compareTo(EmailJob other) {
        return Integer.compare(this.priority, other.priority);
    }

    // ------------------------------------------------------------------ //
    //  Getters                                                             //
    // ------------------------------------------------------------------ //

    public UUID getId() {
        return id;
    }

    public String getBatchId() {
        return batchId;
    }

    public Contact getContact() {
        return contact;
    }

    public EmailTemplate getTemplate() {
        return template;
    }

    public int getPriority() {
        return priority;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public JobStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getScheduledAt() {
        return scheduledAt;
    }

    public LocalDateTime getLastAttemptAt() {
        return lastAttemptAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    // ------------------------------------------------------------------ //
    //  Object overrides                                                    //
    // ------------------------------------------------------------------ //

    @Override
    public String toString() {
        return "EmailJob{"
                + "id=" + id
                + ", contact=" + (contact != null ? contact.getPrimaryEmail() : "null")
                + ", priority=" + priority
                + ", status=" + status
                + ", attemptCount=" + attemptCount
                + ", maxAttempts=" + maxAttempts
                + ", createdAt=" + createdAt
                + ", scheduledAt=" + scheduledAt
                + ", lastAttemptAt=" + lastAttemptAt
                + ", errorMessage='" + errorMessage + '\''
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EmailJob other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    // ------------------------------------------------------------------ //
    //  Builder                                                             //
    // ------------------------------------------------------------------ //

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private UUID          id;
        private String        batchId;
        private Contact       contact;
        private EmailTemplate template;
        private int           priority     = 5;
        private int           attemptCount = 0;
        private int           maxAttempts  = 3;
        private JobStatus     status;
        private LocalDateTime createdAt;
        private LocalDateTime lastAttemptAt;
        private LocalDateTime scheduledAt;
        private String        errorMessage;

        private Builder() {}

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder batchId(String batchId) {
            this.batchId = batchId;
            return this;
        }

        public Builder contact(Contact contact) {
            this.contact = contact;
            return this;
        }

        public Builder template(EmailTemplate template) {
            this.template = template;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder attemptCount(int attemptCount) {
            this.attemptCount = attemptCount;
            return this;
        }

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder status(JobStatus status) {
            this.status = status;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder lastAttemptAt(LocalDateTime lastAttemptAt) {
            this.lastAttemptAt = lastAttemptAt;
            return this;
        }

        public Builder scheduledAt(LocalDateTime scheduledAt) {
            this.scheduledAt = scheduledAt;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public EmailJob build() {
            return new EmailJob(this);
        }
    }
}
