package com.outlookautoemailier.queue;

import com.outlookautoemailier.model.EmailJob;
import com.outlookautoemailier.model.EmailJob.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Thread-safe priority queue for {@link EmailJob} objects.
 *
 * <p>Jobs with a lower {@code priority} number are processed first because
 * {@link EmailJob} implements {@link Comparable} in ascending order.
 * A {@link PriorityBlockingQueue} is used as the backing store so worker
 * threads can block on {@link #take()} without busy-waiting.</p>
 *
 * <h3>Status-tracking design note</h3>
 * <p>{@link EmailJob} exposes only three mutable state transitions:
 * {@code markSent()}, {@code markFailed()}, and {@code incrementAttempt()}.
 * There is no public {@code setStatus(SENDING)} method, and Sprint 1 files
 * must not be modified.  The SENDING state is therefore tracked entirely
 * inside this class via {@link #inFlightIds}: any job whose UUID appears in
 * that set is considered logically SENDING for dashboard purposes, regardless
 * of the {@code EmailJob.status} field value (which remains PENDING until a
 * terminal transition).</p>
 *
 * <h3>Counter semantics</h3>
 * <ul>
 *   <li>{@code pendingCount}    — jobs sitting in the {@link PriorityBlockingQueue}.</li>
 *   <li>{@code sendingCount}    — jobs removed from the queue but not yet finished.</li>
 *   <li>{@code sentCount}       — jobs for which {@link #markJobSent} was called.</li>
 *   <li>{@code failedCount}     — jobs that failed but will be retried (re-enqueued).</li>
 *   <li>{@code deadLetterCount} — jobs that exhausted all retries.</li>
 * </ul>
 *
 * <h3>Synchronization</h3>
 * <p>{@link PriorityBlockingQueue} and {@link ConcurrentHashMap} handle their
 * own atomicity.  The {@code allJobsLock} monitor guards the multi-step
 * read-modify-write inside {@link #retryAll()} and
 * {@link #getAllJobsSnapshot()}.</p>
 */
public class EmailQueue {

    private static final Logger log = LoggerFactory.getLogger(EmailQueue.class);

    // ------------------------------------------------------------------
    //  Core data structures
    // ------------------------------------------------------------------

    /** Live queue — only PENDING jobs waiting for a worker thread. */
    private final PriorityBlockingQueue<EmailJob> queue;

    /**
     * Master list of every job ever submitted to this queue instance.
     * The dashboard reads this list frequently; writes are rare (enqueue /
     * retryAll).  {@link CopyOnWriteArrayList} avoids iterator-vs-write
     * races without heavy locking.
     */
    private final List<EmailJob> allJobs;

    /**
     * Guards multi-step compound operations on {@code allJobs} (retryAll,
     * getAllJobsSnapshot) that must read and write atomically.
     */
    private final Object allJobsLock = new Object();

    /**
     * IDs of jobs that have been dequeued but not yet reached a terminal
     * state (SENT / FAILED / DEAD_LETTER).  These are logically "SENDING".
     */
    private final Set<UUID> inFlightIds =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // ------------------------------------------------------------------
    //  Per-status counters
    // ------------------------------------------------------------------

    private final AtomicInteger pendingCount    = new AtomicInteger(0);
    private final AtomicInteger sendingCount    = new AtomicInteger(0);
    private final AtomicInteger sentCount       = new AtomicInteger(0);
    private final AtomicInteger failedCount     = new AtomicInteger(0);
    private final AtomicInteger deadLetterCount = new AtomicInteger(0);

    // ------------------------------------------------------------------
    //  Constructor
    // ------------------------------------------------------------------

    public EmailQueue() {
        this.queue   = new PriorityBlockingQueue<>(100);
        this.allJobs = new CopyOnWriteArrayList<>();
    }

    // ------------------------------------------------------------------
    //  Enqueue operations
    // ------------------------------------------------------------------

    /**
     * Adds a single job to the live queue and registers it in the master
     * list.  The job must be freshly constructed (PENDING status); the
     * {@code pendingCount} counter is incremented.
     *
     * @param job the job to enqueue; must not be null
     */
    public void enqueue(EmailJob job) {
        Objects.requireNonNull(job, "job must not be null");
        synchronized (allJobsLock) {
            allJobs.add(job);
        }
        queue.offer(job);
        pendingCount.incrementAndGet();
        log.debug("Enqueued job {} for contact={} priority={}",
                job.getId(), job.getContact().getPrimaryEmail(), job.getPriority());
    }

    /**
     * Convenience method — enqueues every job in the supplied list.
     *
     * @param jobs the jobs to enqueue; must not be null
     */
    public void enqueueAll(List<EmailJob> jobs) {
        Objects.requireNonNull(jobs, "jobs list must not be null");
        for (EmailJob job : jobs) {
            enqueue(job);
        }
        log.info("Batch enqueue complete: {} job(s) added. Pending total: {}",
                jobs.size(), pendingCount.get());
    }

    // ------------------------------------------------------------------
    //  Consumer operations
    // ------------------------------------------------------------------

    /**
     * Retrieves and removes the highest-priority job, <em>blocking</em>
     * until one becomes available.
     *
     * <p>The job is registered as in-flight (logically SENDING) and
     * counters are adjusted: {@code pendingCount} decremented,
     * {@code sendingCount} incremented.</p>
     *
     * @return the next job to process; never null
     * @throws InterruptedException if the calling thread is interrupted
     */
    public EmailJob take() throws InterruptedException {
        EmailJob job = queue.take();
        inFlightIds.add(job.getId());
        pendingCount.decrementAndGet();
        sendingCount.incrementAndGet();
        log.debug("Worker took job {} for contact={}",
                job.getId(), job.getContact().getPrimaryEmail());
        return job;
    }

    /**
     * Non-blocking poll.  Returns {@code null} if the queue is currently
     * empty.  When a job is returned it is registered as in-flight and
     * counters are adjusted identically to {@link #take()}.
     *
     * @return the next pending job, or {@code null} if the queue is empty
     */
    public EmailJob poll() {
        EmailJob job = queue.poll();
        if (job != null) {
            inFlightIds.add(job.getId());
            pendingCount.decrementAndGet();
            sendingCount.incrementAndGet();
            log.debug("Worker polled job {} for contact={}",
                    job.getId(), job.getContact().getPrimaryEmail());
        }
        return job;
    }

    // ------------------------------------------------------------------
    //  Terminal state transitions
    // ------------------------------------------------------------------

    /**
     * Records a successful delivery.
     *
     * <p>Removes the job from the in-flight set, calls
     * {@link EmailJob#markSent()}, and adjusts counters:
     * {@code sendingCount} decremented, {@code sentCount} incremented.</p>
     *
     * @param job the job that was delivered successfully
     */
    public void markJobSent(EmailJob job) {
        inFlightIds.remove(job.getId());
        job.markSent();
        sendingCount.decrementAndGet();
        sentCount.incrementAndGet();
        log.info("SENT job {} to {}",
                job.getId(), job.getContact().getPrimaryEmail());
    }

    /**
     * Records a failed delivery attempt.
     *
     * <p>Steps:
     * <ol>
     *   <li>Removes the job from the in-flight set.</li>
     *   <li>Calls {@link EmailJob#incrementAttempt()} then
     *       {@link EmailJob#markFailed(String)}.</li>
     *   <li>Decrements {@code sendingCount}.</li>
     *   <li>If {@link EmailJob#isDead()}: increments {@code deadLetterCount}
     *       and logs a WARN — the job is <em>not</em> re-enqueued.</li>
     *   <li>Otherwise: increments {@code pendingCount} (re-enqueue)
     *       and also increments {@code failedCount} to track total failures
     *       (the job is back in the queue as a pending retry).</li>
     * </ol>
     * </p>
     *
     * @param job    the job whose attempt failed
     * @param reason human-readable description of the failure
     */
    public void markJobFailed(EmailJob job, String reason) {
        inFlightIds.remove(job.getId());
        job.incrementAttempt();
        job.markFailed(reason);
        sendingCount.decrementAndGet();

        if (job.isDead()) {
            deadLetterCount.incrementAndGet();
            log.warn("DEAD_LETTER job {} after {}/{} attempt(s). Reason: {}",
                    job.getId(), job.getAttemptCount(), job.getMaxAttempts(), reason);
        } else {
            // Re-enqueue for retry — job status is now FAILED but it goes
            // back into the live queue; pendingCount reflects the live queue size.
            failedCount.incrementAndGet();
            queue.offer(job);
            pendingCount.incrementAndGet();
            log.info("FAILED job {} re-enqueued for retry (attempt {}/{}). Reason: {}",
                    job.getId(), job.getAttemptCount(), job.getMaxAttempts(), reason);
        }
    }

    // ------------------------------------------------------------------
    //  Bulk operations
    // ------------------------------------------------------------------

    /**
     * Re-enqueues all FAILED and DEAD_LETTER jobs for a fresh retry run.
     *
     * <p>Because {@link EmailJob} does not expose a public
     * {@code resetAttempts()} method, each candidate job is replaced in the
     * master list with a freshly built copy that shares the same UUID, contact,
     * template, and priority but starts with {@code attemptCount=0} and
     * {@code status=PENDING}.  Preserving the UUID allows the dashboard to
     * correlate retried jobs with their history.</p>
     */
    public void retryAll() {
        synchronized (allJobsLock) {
            List<EmailJob> retryable = allJobs.stream()
                    .filter(j -> j.getStatus() == JobStatus.FAILED
                              || j.getStatus() == JobStatus.DEAD_LETTER)
                    .collect(Collectors.toList());

            if (retryable.isEmpty()) {
                log.info("retryAll(): no FAILED or DEAD_LETTER jobs found.");
                return;
            }

            // Drain the entire live queue once to remove any stale FAILED entries
            // that were re-enqueued by markJobFailed().  We will repopulate below.
            List<EmailJob> liveSnapshot = new ArrayList<>();
            queue.drainTo(liveSnapshot);
            // Adjust pendingCount for jobs we just drained
            pendingCount.addAndGet(-liveSnapshot.size());

            for (EmailJob old : retryable) {
                allJobs.remove(old);

                // Roll back the counter that was incremented when the old status was set
                if (old.getStatus() == JobStatus.DEAD_LETTER) {
                    deadLetterCount.decrementAndGet();
                } else {
                    // FAILED jobs had failedCount++ and pendingCount++ (re-enqueue path)
                    failedCount.decrementAndGet();
                }

                // Build a fresh copy with reset attempts
                EmailJob fresh = EmailJob.builder()
                        .id(old.getId())
                        .contact(old.getContact())
                        .template(old.getTemplate())
                        .priority(old.getPriority())
                        .maxAttempts(old.getMaxAttempts())
                        .createdAt(old.getCreatedAt())
                        .build(); // attemptCount=0, status=PENDING

                allJobs.add(fresh);
                queue.offer(fresh);
                pendingCount.incrementAndGet();
            }

            // Re-enqueue any live PENDING jobs that were drained but are NOT being retried
            for (EmailJob live : liveSnapshot) {
                boolean isBeingReplaced = retryable.stream()
                        .anyMatch(r -> r.getId().equals(live.getId()));
                if (!isBeingReplaced) {
                    queue.offer(live);
                    pendingCount.incrementAndGet();
                }
            }

            log.info("retryAll(): re-enqueued {} job(s) for retry.", retryable.size());
        }
    }

    /**
     * Returns a previously polled job back to the live queue without
     * re-registering it in allJobs. Properly reverses the counter
     * changes made by poll().
     *
     * @param job the job to requeue; must not be null
     */
    public void requeue(EmailJob job) {
        Objects.requireNonNull(job, "job must not be null");
        inFlightIds.remove(job.getId());
        sendingCount.decrementAndGet();
        queue.offer(job);
        pendingCount.incrementAndGet();
        log.debug("Requeued job {} (scheduled for later)", job.getId());
    }

    /**
     * Drains all PENDING jobs from the live queue without processing them.
     * In-flight jobs (currently being sent) are unaffected.
     * The drained jobs remain in the master list with their existing status.
     */
    public void cancelPending() {
        List<EmailJob> drained = new ArrayList<>();
        queue.drainTo(drained);
        int count = drained.size();
        if (count > 0) {
            pendingCount.addAndGet(-count);
        }
        log.info("cancelPending(): removed {} pending job(s) from the queue.", count);
    }

    // ------------------------------------------------------------------
    //  Query / reporting
    // ------------------------------------------------------------------

    /** Returns true when the live pending queue is empty. */
    public boolean isEmpty() { return queue.isEmpty(); }

    /** Number of jobs waiting in the live queue. */
    public int pendingCount()    { return pendingCount.get(); }

    /** Number of jobs currently being sent by worker threads. */
    public int sendingCount()    { return sendingCount.get(); }

    /** Cumulative number of jobs successfully delivered. */
    public int sentCount()       { return sentCount.get(); }

    /** Number of jobs that failed at least once and are queued for retry. */
    public int failedCount()     { return failedCount.get(); }

    /** Number of jobs that exhausted all retries. */
    public int deadLetterCount() { return deadLetterCount.get(); }

    /** Total number of distinct jobs ever submitted (includes all statuses). */
    public int totalCount()      { return allJobs.size(); }

    /**
     * Returns an immutable snapshot of all jobs for dashboard display.
     *
     * @return unmodifiable copy of the master job list
     */
    public List<EmailJob> getAllJobsSnapshot() {
        synchronized (allJobsLock) {
            return Collections.unmodifiableList(new ArrayList<>(allJobs));
        }
    }

    /**
     * Returns all jobs matching the supplied status filter.
     *
     * <p>For {@link JobStatus#SENDING}: returns jobs currently in-flight
     * (tracked via {@link #inFlightIds}) regardless of the job's own
     * {@code status} field.</p>
     *
     * @param status the target status
     * @return mutable list of matching jobs; never null
     */
    public List<EmailJob> getJobsByStatus(JobStatus status) {
        if (status == JobStatus.SENDING) {
            return allJobs.stream()
                    .filter(j -> inFlightIds.contains(j.getId()))
                    .collect(Collectors.toList());
        }
        return allJobs.stream()
                .filter(j -> j.getStatus() == status
                          && !inFlightIds.contains(j.getId()))
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    //  Dead-letter operations
    // ------------------------------------------------------------------

    /**
     * Returns a snapshot of all jobs currently in the DEAD_LETTER state.
     *
     * @return unmodifiable list of dead-letter jobs; never null
     */
    public List<EmailJob> getDeadLetterJobs() {
        return getJobsByStatus(JobStatus.DEAD_LETTER);
    }

    /**
     * Removes the specified dead-letter jobs from the master list and adjusts
     * the {@code deadLetterCount} counter accordingly.  Only jobs whose current
     * status is {@link JobStatus#DEAD_LETTER} are removed; others are silently
     * skipped.
     *
     * @param jobs the dead-letter jobs to remove; must not be null
     */
    public void removeDeadLetterJobs(List<EmailJob> jobs) {
        Objects.requireNonNull(jobs, "jobs must not be null");
        synchronized (allJobsLock) {
            for (EmailJob job : jobs) {
                if (job.getStatus() == JobStatus.DEAD_LETTER && allJobs.remove(job)) {
                    deadLetterCount.decrementAndGet();
                    log.info("Removed DEAD_LETTER job {} from master list.", job.getId());
                }
            }
        }
    }

    /**
     * Re-enqueues the specified dead-letter jobs for a fresh retry run.
     * Each job is replaced with a fresh copy that has {@code attemptCount=0}
     * and {@code status=PENDING}, preserving the original UUID.
     *
     * @param jobs the dead-letter jobs to retry; must not be null
     */
    public void retryDeadLetterJobs(List<EmailJob> jobs) {
        Objects.requireNonNull(jobs, "jobs must not be null");
        synchronized (allJobsLock) {
            for (EmailJob old : jobs) {
                if (old.getStatus() != JobStatus.DEAD_LETTER) {
                    continue;
                }
                allJobs.remove(old);
                deadLetterCount.decrementAndGet();

                EmailJob fresh = EmailJob.builder()
                        .id(old.getId())
                        .batchId(old.getBatchId())
                        .contact(old.getContact())
                        .template(old.getTemplate())
                        .priority(old.getPriority())
                        .maxAttempts(old.getMaxAttempts())
                        .createdAt(old.getCreatedAt())
                        .build();

                allJobs.add(fresh);
                queue.offer(fresh);
                pendingCount.incrementAndGet();
                log.info("Retried DEAD_LETTER job {} — re-enqueued as PENDING.", fresh.getId());
            }
        }
    }
}
