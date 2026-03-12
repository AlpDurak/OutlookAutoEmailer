package com.outlookautoemailier.queue;

import com.outlookautoemailier.model.EmailJob;
import com.outlookautoemailier.model.EmailJob.JobStatus;
import com.outlookautoemailier.security.RateLimiter;
import com.outlookautoemailier.security.SpamGuard;
import com.outlookautoemailier.smtp.SmtpSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-pool dispatcher that consumes jobs from an {@link EmailQueue} and
 * delivers them via {@link SmtpSender}.
 *
 * <h3>Lifecycle</h3>
 * <pre>
 *   [created] ──► start() ──► [running] ──► pause() ──► [paused]
 *                                  │                         │
 *                                  │         resume() ◄──────┘
 *                                  │
 *                                  └──► shutdown() ──► [terminated]
 * </pre>
 *
 * <h3>Worker loop</h3>
 * <p>Each worker thread repeats the following cycle while the dispatcher is
 * running:
 * <ol>
 *   <li>If paused, sleep 500 ms and loop.</li>
 *   <li>Call {@link RateLimiter#acquire()} — blocks until a send token is available.</li>
 *   <li>Poll the queue (non-blocking).  If empty, sleep 200 ms and loop.</li>
 *   <li>Call {@link SpamGuard#applyDelay()} for inter-message jitter.</li>
 *   <li>Attempt delivery via {@link SmtpSender#send(EmailJob)}.</li>
 *   <li>On success: call {@link EmailQueue#markJobSent(EmailJob)}.</li>
 *   <li>On failure: call {@link EmailQueue#markJobFailed(EmailJob, String)}.</li>
 * </ol>
 * </p>
 *
 * <h3>Pause / resume</h3>
 * <p>Pausing does <em>not</em> interrupt in-flight sends.  Worker threads
 * finish their current job before checking the pause flag, so the dispatcher
 * drains gracefully rather than abandoning mid-send jobs.</p>
 */
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    /** Default number of concurrent worker threads. */
    public static final int DEFAULT_THREAD_COUNT = 2;

    /** How long to sleep (ms) while the dispatcher is paused. */
    private static final long PAUSE_SLEEP_MS = 500L;

    /** How long to sleep (ms) when the queue is empty. */
    private static final long IDLE_SLEEP_MS = 200L;

    // ------------------------------------------------------------------
    //  Collaborators
    // ------------------------------------------------------------------

    private final EmailQueue  emailQueue;
    private final SmtpSender  smtpSender;
    private final RateLimiter rateLimiter;
    private final SpamGuard   spamGuard;
    private final int         threadCount;

    // ------------------------------------------------------------------
    //  Concurrency state
    // ------------------------------------------------------------------

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused  = new AtomicBoolean(false);

    /** Fixed-size pool; each thread runs one worker loop. */
    private ExecutorService workerPool;

    /** Holds the Future handles of all running workers for orderly shutdown. */
    private final List<Future<?>> workerFutures = new ArrayList<>();

    // ------------------------------------------------------------------
    //  Constructors
    // ------------------------------------------------------------------

    /**
     * Creates a dispatcher with a custom thread count.
     *
     * @param emailQueue  the queue to consume; must not be null
     * @param smtpSender  the transport layer; must not be null
     * @param rateLimiter the rate limiter; must not be null
     * @param spamGuard   the spam-guard layer; must not be null
     * @param threadCount number of concurrent worker threads (must be &ge; 1)
     */
    public EmailDispatcher(EmailQueue  emailQueue,
                           SmtpSender  smtpSender,
                           RateLimiter rateLimiter,
                           SpamGuard   spamGuard,
                           int         threadCount) {
        if (threadCount < 1) {
            throw new IllegalArgumentException("threadCount must be >= 1, got: " + threadCount);
        }
        this.emailQueue   = java.util.Objects.requireNonNull(emailQueue,  "emailQueue");
        this.smtpSender   = java.util.Objects.requireNonNull(smtpSender,  "smtpSender");
        this.rateLimiter  = java.util.Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.spamGuard    = java.util.Objects.requireNonNull(spamGuard,   "spamGuard");
        this.threadCount  = threadCount;
    }

    /**
     * Creates a dispatcher with the default thread count ({@value #DEFAULT_THREAD_COUNT}).
     *
     * @param emailQueue  the queue to consume
     * @param smtpSender  the transport layer
     * @param rateLimiter the rate limiter
     * @param spamGuard   the spam-guard layer
     */
    public EmailDispatcher(EmailQueue  emailQueue,
                           SmtpSender  smtpSender,
                           RateLimiter rateLimiter,
                           SpamGuard   spamGuard) {
        this(emailQueue, smtpSender, rateLimiter, spamGuard, DEFAULT_THREAD_COUNT);
    }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    /**
     * Starts the dispatcher.  Spawns {@code threadCount} worker threads that
     * immediately begin consuming jobs.  Calling {@code start()} on an already
     * running dispatcher is a no-op (idempotent).
     */
    public synchronized void start() {
        if (running.get()) {
            log.warn("Dispatcher is already running; ignoring start() call.");
            return;
        }

        workerPool = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "email-dispatcher-worker-" + System.nanoTime());
            t.setDaemon(true); // don't block JVM shutdown
            return t;
        });

        running.set(true);
        paused.set(false);
        workerFutures.clear();

        for (int i = 0; i < threadCount; i++) {
            Future<?> future = workerPool.submit(this::workerLoop);
            workerFutures.add(future);
        }

        log.info("EmailDispatcher started with {} worker thread(s).", threadCount);
    }

    /**
     * Pauses processing.  Worker threads will finish their current job (if any)
     * and then sleep until {@link #resume()} is called.  Calling {@code pause()}
     * on a stopped or already-paused dispatcher is a no-op.
     */
    public void pause() {
        if (!running.get()) {
            log.warn("Dispatcher is not running; ignoring pause() call.");
            return;
        }
        paused.set(true);
        log.info("EmailDispatcher paused.");
    }

    /**
     * Resumes a paused dispatcher.  Worker threads will start consuming the
     * queue again on their next loop iteration.  Calling {@code resume()} on a
     * non-paused dispatcher is a no-op.
     */
    public void resume() {
        if (!running.get()) {
            log.warn("Dispatcher is not running; ignoring resume() call.");
            return;
        }
        paused.set(false);
        log.info("EmailDispatcher resumed.");
    }

    /**
     * Gracefully shuts down the dispatcher.
     *
     * <p>Sets the running flag to false (workers will exit their loops after
     * finishing their current job), then waits up to 30 seconds for the pool
     * to terminate.  If the pool does not terminate in time it is forcibly
     * shut down.</p>
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public synchronized void shutdown() throws InterruptedException {
        if (!running.get()) {
            log.warn("Dispatcher is already stopped; ignoring shutdown() call.");
            return;
        }

        log.info("EmailDispatcher shutting down — waiting for in-flight jobs...");
        running.set(false);
        paused.set(false); // unblock any paused workers so they can exit

        workerPool.shutdown();
        boolean terminated = workerPool.awaitTermination(30, TimeUnit.SECONDS);

        if (!terminated) {
            log.warn("Worker pool did not terminate cleanly within 30 s; forcing shutdown.");
            workerPool.shutdownNow();
        } else {
            log.info("EmailDispatcher shut down cleanly.");
        }
    }

    // ------------------------------------------------------------------
    //  Status accessors
    // ------------------------------------------------------------------

    /**
     * Returns {@code true} if the dispatcher has been started and not yet shut down.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns {@code true} if the dispatcher is running but currently paused.
     *
     * @return true if paused
     */
    public boolean isPaused() {
        return paused.get();
    }

    // ------------------------------------------------------------------
    //  Worker loop
    // ------------------------------------------------------------------

    /**
     * The main body executed by each worker thread.
     *
     * <p>The loop runs until {@link #running} is set to {@code false} or the
     * thread is interrupted.  All checked exceptions that arise during a send
     * attempt are caught and reported to the queue rather than propagating,
     * which keeps the worker alive for subsequent jobs.</p>
     */
    private void workerLoop() {
        log.debug("Worker thread [{}] started.", Thread.currentThread().getName());

        while (running.get() && !Thread.currentThread().isInterrupted()) {

            // 1. Honour pause state — sleep briefly and re-check flags.
            if (paused.get()) {
                sleepQuietly(PAUSE_SLEEP_MS);
                continue;
            }

            // 2. Non-blocking poll from the queue first.
            //    Rate-limit tokens must NOT be consumed when the queue is empty;
            //    doing so would drain the hour budget on idle workers and trigger
            //    the "Rate limit exhausted" warning flood.
            EmailJob job = emailQueue.poll();

            if (job == null) {
                sleepQuietly(IDLE_SLEEP_MS);
                continue;
            }

            // 2b. Check if the job is scheduled for a future time.
            if (job.getScheduledAt() != null
                    && job.getScheduledAt().isAfter(java.time.LocalDateTime.now())) {
                // Not yet time — re-enqueue and sleep briefly to avoid busy-spinning.
                emailQueue.requeue(job);
                sleepQuietly(2_000L);
                continue;
            }

            // 2c. If the job was SCHEDULED but its time has arrived, transition to PENDING.
            if (job.getStatus() == JobStatus.SCHEDULED) {
                job.markPending();
            }

            // 3. Acquire a rate-limit token — only when we actually have a job.
            try {
                rateLimiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emailQueue.markJobFailed(job, "Worker interrupted before rate-limit token acquired");
                log.info("Worker [{}] interrupted during rate-limit acquire; exiting.",
                        Thread.currentThread().getName());
                break;
            } catch (Exception e) {
                log.error("RateLimiter.acquire() threw an unexpected exception; pausing 1 s.", e);
                emailQueue.markJobFailed(job, "Rate limiter error: " + e.getMessage());
                sleepQuietly(1_000L);
                continue;
            }

            // Pre-send validation: suppression list + email format check
            String recipientEmail = job.getContact().getPrimaryEmail();

            // Check unsubscribe suppression
            if (com.outlookautoemailier.security.UnsubscribeManager.getInstance().isSuppressed(recipientEmail)) {
                log.info("Skipping suppressed recipient: {}", recipientEmail);
                emailQueue.markJobFailed(job, "Recipient is on the unsubscribe suppression list.");
                continue;
            }

            // Validate email format
            com.outlookautoemailier.security.EmailValidator.ValidationResult validationResult =
                    new com.outlookautoemailier.security.EmailValidator().validate(recipientEmail);
            if (!validationResult.isValid()) {
                log.warn("Skipping invalid email address {}: {}", recipientEmail, validationResult.getReason());
                emailQueue.markJobFailed(job, "Invalid email address: " + validationResult.getReason());
                continue;
            }

            // 4. Apply spam-guard inter-message jitter delay.
            try {
                spamGuard.applyDelay();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Re-enqueue the job so it isn't lost before exiting.
                emailQueue.markJobFailed(job, "Worker interrupted before send");
                log.info("Worker [{}] interrupted during spam-guard delay; exiting.",
                        Thread.currentThread().getName());
                break;
            } catch (Exception e) {
                log.warn("SpamGuard.applyDelay() threw; continuing without delay. Error: {}",
                        e.getMessage());
            }

            // 5. Attempt delivery.
            try {
                smtpSender.send(job);
                emailQueue.markJobSent(job);
                log.info("Worker [{}] delivered job {} to {}.",
                        Thread.currentThread().getName(),
                        job.getId(),
                        job.getContact().getPrimaryEmail());
            } catch (Exception e) {
                String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
                emailQueue.markJobFailed(job, reason);
                log.warn("Worker [{}] failed to deliver job {} to {}. Reason: {}",
                        Thread.currentThread().getName(),
                        job.getId(),
                        job.getContact().getPrimaryEmail(),
                        reason);
            }
        }

        log.debug("Worker thread [{}] exiting.", Thread.currentThread().getName());
    }

    // ------------------------------------------------------------------
    //  Private helpers
    // ------------------------------------------------------------------

    /**
     * Sleeps for the given duration, swallowing {@link InterruptedException}
     * by re-setting the interrupt flag so the outer loop can detect it.
     *
     * @param ms milliseconds to sleep
     */
    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
