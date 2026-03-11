package com.outlookautoemailier.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Encapsulates retry delay calculation using exponential back-off.
 *
 * <p>Used by the dispatcher to determine how long to wait before
 * re-attempting a failed {@link com.outlookautoemailier.model.EmailJob}.
 * The delay after the <em>n</em>-th failure is calculated as:
 *
 * <pre>
 *   delay(n) = min( baseDelayMs &times; multiplier^(n-1), maxDelayMs )
 * </pre>
 *
 * For example, with the default policy (base=5 000 ms, multiplier=2.0,
 * max=60 000 ms):
 * <table border="1">
 *   <tr><th>Attempt</th><th>Delay</th></tr>
 *   <tr><td>1</td><td>5 000 ms</td></tr>
 *   <tr><td>2</td><td>10 000 ms</td></tr>
 *   <tr><td>3</td><td>20 000 ms</td></tr>
 *   <tr><td>4+</td><td>60 000 ms (capped)</td></tr>
 * </table>
 *
 * <p>{@code RetryPolicy} is immutable and therefore safe to share across
 * threads without additional synchronisation.</p>
 */
public class RetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);

    // ------------------------------------------------------------------
    //  Fields
    // ------------------------------------------------------------------

    /** Maximum number of delivery attempts before a job is moved to dead-letter. */
    private final int    maxAttempts;

    /** Base delay applied after the first failure (milliseconds). */
    private final long   baseDelayMs;

    /** Exponential multiplier applied on each successive failure. */
    private final double multiplier;

    /** Ceiling on the calculated delay regardless of the back-off formula. */
    private final long   maxDelayMs;

    // ------------------------------------------------------------------
    //  Constructor
    // ------------------------------------------------------------------

    /**
     * Creates a {@code RetryPolicy} with fully customised parameters.
     *
     * @param maxAttempts maximum number of attempts before dead-letter (must be &ge; 1)
     * @param baseDelayMs delay after first failure in milliseconds (must be &ge; 0)
     * @param multiplier  exponential factor; e.g. 2.0 doubles the delay each time
     *                    (must be &ge; 1.0)
     * @param maxDelayMs  cap on delay regardless of back-off calculation (must be &ge; baseDelayMs)
     * @throws IllegalArgumentException if any parameter violates its constraint
     */
    public RetryPolicy(int maxAttempts, long baseDelayMs, double multiplier, long maxDelayMs) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got: " + maxAttempts);
        }
        if (baseDelayMs < 0) {
            throw new IllegalArgumentException("baseDelayMs must be >= 0, got: " + baseDelayMs);
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1.0, got: " + multiplier);
        }
        if (maxDelayMs < baseDelayMs) {
            throw new IllegalArgumentException(
                    "maxDelayMs (" + maxDelayMs + ") must be >= baseDelayMs (" + baseDelayMs + ")");
        }

        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.multiplier  = multiplier;
        this.maxDelayMs  = maxDelayMs;
    }

    // ------------------------------------------------------------------
    //  Factory methods
    // ------------------------------------------------------------------

    /**
     * Returns a policy using the application defaults:
     * <ul>
     *   <li>3 attempts</li>
     *   <li>5 000 ms base delay</li>
     *   <li>2.0&times; multiplier</li>
     *   <li>60 000 ms max delay</li>
     * </ul>
     *
     * @return the default {@code RetryPolicy} instance
     */
    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(3, 5_000L, 2.0, 60_000L);
    }

    // ------------------------------------------------------------------
    //  Core logic
    // ------------------------------------------------------------------

    /**
     * Calculates the back-off delay for the given 1-indexed attempt number.
     *
     * <p>Formula: {@code min(baseDelayMs * multiplier^(attemptNumber-1), maxDelayMs)}</p>
     *
     * <p>Attempt number 1 always returns exactly {@code baseDelayMs}
     * (the exponent is zero, so multiplier has no effect on the first retry).</p>
     *
     * @param attemptNumber the 1-indexed attempt number (1 = first failure)
     * @return the calculated delay as a {@link Duration}; never null
     */
    public Duration delayFor(int attemptNumber) {
        if (attemptNumber <= 1) {
            log.debug("RetryPolicy: attempt {}, delay = {} ms (base)", attemptNumber, baseDelayMs);
            return Duration.ofMillis(baseDelayMs);
        }
        double rawDelay = baseDelayMs * Math.pow(multiplier, attemptNumber - 1);
        long   delay    = Math.min((long) rawDelay, maxDelayMs);
        log.debug("RetryPolicy: attempt {}, raw delay = {} ms, capped = {} ms",
                attemptNumber, (long) rawDelay, delay);
        return Duration.ofMillis(delay);
    }

    /**
     * Returns {@code true} if the job should be retried given its current
     * attempt count.
     *
     * <p>A job is retryable when its total attempt count is strictly less
     * than {@link #maxAttempts}.  This mirrors the logic in
     * {@link com.outlookautoemailier.model.EmailJob#isDead()}, which treats
     * {@code attemptCount >= maxAttempts} as exhausted.</p>
     *
     * @param attemptCount the number of attempts already made
     * @return true if another attempt should be scheduled
     */
    public boolean shouldRetry(int attemptCount) {
        return attemptCount < maxAttempts;
    }

    // ------------------------------------------------------------------
    //  Getters
    // ------------------------------------------------------------------

    /** Maximum allowed attempts before a job is dead-lettered. */
    public int    getMaxAttempts() { return maxAttempts; }

    /** Base delay in milliseconds applied after the first failure. */
    public long   getBaseDelayMs() { return baseDelayMs; }

    /** Exponential multiplier applied on each successive failure. */
    public double getMultiplier()  { return multiplier; }

    /** Ceiling on any single delay regardless of the back-off formula. */
    public long   getMaxDelayMs()  { return maxDelayMs; }

    // ------------------------------------------------------------------
    //  Object overrides
    // ------------------------------------------------------------------

    @Override
    public String toString() {
        return String.format(
                "RetryPolicy{maxAttempts=%d, baseDelay=%d ms, multiplier=%.1f, maxDelay=%d ms}",
                maxAttempts, baseDelayMs, multiplier, maxDelayMs);
    }
}
