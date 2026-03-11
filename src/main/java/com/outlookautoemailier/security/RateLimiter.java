package com.outlookautoemailier.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Token-bucket rate limiter that bounds the number of outbound emails to a
 * configurable maximum per hour.
 *
 * <h2>Algorithm</h2>
 * <p>The bucket is initialised with {@code emailsPerHour} tokens.  Each
 * successful call to {@link #tryAcquire()} or {@link #acquire()} consumes one
 * token.  When the wall-clock reaches {@link #getNextRefillTime()} the bucket
 * is atomically refilled to its maximum capacity and the refill time advances
 * by exactly one hour.</p>
 *
 * <h2>Concurrency</h2>
 * <p>The refill check and token decrement are serialised through a
 * {@link ReentrantLock} so that concurrent senders cannot over-consume tokens.
 * The token count is also stored in an {@link AtomicInteger} so that
 * non-locking reads (e.g. {@link #getAvailableTokens()}) always see a
 * consistent value.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   RateLimiter limiter = new RateLimiter(500);   // max 500 emails/hour
 *
 *   // Non-blocking: skip if no tokens remain.
 *   if (!limiter.tryAcquire()) {
 *       log.warn("Rate limit reached: {}", limiter.getStatus());
 *       return;
 *   }
 *   sendEmail(...);
 *
 *   // Blocking: wait until a token is available.
 *   limiter.acquire();
 *   sendEmail(...);
 * }</pre>
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** Duration of one full token-bucket window. */
    private static final Duration WINDOW = Duration.ofHours(1);

    /** Polling interval used by {@link #acquire()} while waiting for tokens. */
    private static final long POLL_INTERVAL_MS = 1_000L;

    // ------------------------------------------------------------------
    //  Instance state
    // ------------------------------------------------------------------

    /** The maximum number of tokens (emails) allowed per hour. */
    private final int maxTokensPerHour;

    /**
     * Current token count.  Decremented by {@link #tryAcquire()} /
     * {@link #acquire()}; reset to {@link #maxTokensPerHour} by
     * {@link #checkAndRefill()}.
     */
    private final AtomicInteger currentTokens;

    /**
     * The wall-clock instant at which the bucket will next be fully refilled.
     * Declared {@code volatile} so that reads outside the lock see the latest
     * value written by {@link #checkAndRefill()}.
     */
    private volatile Instant nextRefillTime;

    /**
     * Serialises the compound check-then-decrement operation so that concurrent
     * threads cannot consume more tokens than the bucket contains.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Tracks whether we have already emitted the "rate limit exhausted" warning
     * for the current window so we don't flood the log every 1 second.
     * Reset to {@code false} when the bucket is refilled.
     */
    private volatile boolean exhaustedWarningLogged = false;

    // ------------------------------------------------------------------
    //  Constructor
    // ------------------------------------------------------------------

    /**
     * Creates a fully-loaded token-bucket rate limiter.
     *
     * @param emailsPerHour the maximum number of emails allowed per hour;
     *                      must be &gt; 0
     * @throws IllegalArgumentException if {@code emailsPerHour} is not positive
     */
    public RateLimiter(int emailsPerHour) {
        if (emailsPerHour <= 0) {
            throw new IllegalArgumentException(
                    "emailsPerHour must be positive, got: " + emailsPerHour);
        }
        this.maxTokensPerHour = emailsPerHour;
        this.currentTokens    = new AtomicInteger(emailsPerHour);
        this.nextRefillTime   = Instant.now().plus(WINDOW);

        log.info("RateLimiter initialised: {} tokens/hour, next refill at {}.",
                 maxTokensPerHour, nextRefillTime);
    }

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    /**
     * Non-blocking token acquisition.
     *
     * <p>Checks whether the bucket should be refilled first, then attempts to
     * atomically decrement the token count.
     *
     * @return {@code true} if a token was consumed; {@code false} if the bucket
     *         is empty and the caller should either wait or skip this send
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            checkAndRefill();

            int tokens = currentTokens.get();
            if (tokens <= 0) {
                log.debug("tryAcquire() denied — bucket empty (next refill: {}).", nextRefillTime);
                return false;
            }
            currentTokens.decrementAndGet();
            log.debug("tryAcquire() granted — {} token(s) remaining.", currentTokens.get());
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocking token acquisition.
     *
     * <p>Polls in {@value #POLL_INTERVAL_MS} ms intervals until a token becomes
     * available (either because other tokens are still in the bucket, or because
     * the bucket has been refilled).
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        log.debug("acquire() called — {} token(s) available.", currentTokens.get());

        while (true) {
            if (tryAcquire()) {
                exhaustedWarningLogged = false; // reset so next exhaustion logs once
                return;
            }
            // Log only the first time per exhaustion window to avoid log flooding.
            if (!exhaustedWarningLogged) {
                log.warn("Rate limit exhausted; waiting for bucket refill at {}. "
                         + "Sleeping {} ms between retries.",
                         nextRefillTime, POLL_INTERVAL_MS);
                exhaustedWarningLogged = true;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
    }

    /**
     * Returns the number of tokens currently available in the bucket.
     *
     * <p>This is a best-effort snapshot; the value may change immediately after
     * this method returns due to concurrent token consumption or a refill.
     *
     * @return a non-negative integer representing the available send budget
     */
    public int getAvailableTokens() {
        return Math.max(0, currentTokens.get());
    }

    /**
     * Returns the instant at which the bucket will next be fully refilled to
     * {@link #maxTokensPerHour} tokens.
     *
     * @return the next refill {@link Instant}; never {@code null}
     */
    public Instant getNextRefillTime() {
        return nextRefillTime;
    }

    /**
     * Returns a human-readable one-line status summary suitable for logging
     * or display in a status bar.
     *
     * <p>Example: {@code "RateLimiter[tokens=423/500, refillAt=2026-03-11T14:00:00Z]"}
     *
     * @return a non-null status string
     */
    public String getStatus() {
        return String.format("RateLimiter[tokens=%d/%d, refillAt=%s]",
                getAvailableTokens(), maxTokensPerHour, nextRefillTime);
    }

    // ------------------------------------------------------------------
    //  Private helpers
    // ------------------------------------------------------------------

    /**
     * Checks whether the refill window has elapsed and, if so, atomically
     * resets the token count to {@link #maxTokensPerHour} and advances
     * {@link #nextRefillTime} by one hour.
     *
     * <p><b>Must be called while holding {@link #lock}.</b>
     */
    private void checkAndRefill() {
        // lock must already be held by the caller.
        if (Instant.now().isAfter(nextRefillTime)) {
            int previousTokens = currentTokens.getAndSet(maxTokensPerHour);
            nextRefillTime = Instant.now().plus(WINDOW);
            exhaustedWarningLogged = false;
            log.info("Token bucket refilled: {} -> {} tokens. Next refill at {}.",
                     previousTokens, maxTokensPerHour, nextRefillTime);
        }
    }
}
