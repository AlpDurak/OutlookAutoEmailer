package com.outlookautoemailier.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RetryPolicy}.
 *
 * <p>Tests cover delay calculation (base case, exponential growth, and cap),
 * the {@link RetryPolicy#shouldRetry(int)} guard, and the
 * {@link RetryPolicy#defaultPolicy()} factory values.</p>
 */
class RetryPolicyTest {

    // ------------------------------------------------------------------
    //  Shared policy used by most tests
    // ------------------------------------------------------------------

    /**
     * Policy under test with well-known parameters that make arithmetic easy
     * to verify:
     * <ul>
     *   <li>maxAttempts = 3</li>
     *   <li>baseDelayMs = 1 000 ms</li>
     *   <li>multiplier  = 2.0</li>
     *   <li>maxDelayMs  = 10 000 ms</li>
     * </ul>
     */
    private RetryPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new RetryPolicy(3, 1_000L, 2.0, 10_000L);
    }

    // ------------------------------------------------------------------
    //  delayFor() tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("delayFor(1) returns the base delay exactly")
    void testDelayFirstAttempt() {
        Duration delay = policy.delayFor(1);

        assertEquals(Duration.ofMillis(1_000L), delay,
                "delayFor(1) must equal baseDelayMs (2^0 == 1 so multiplier has no effect).");
    }

    @Test
    @DisplayName("delayFor(2) returns baseDelay * multiplier^1")
    void testDelayExponentialGrowth() {
        // Expected: 1_000 * 2.0^1 = 2_000 ms
        Duration delay = policy.delayFor(2);

        assertEquals(Duration.ofMillis(2_000L), delay,
                "delayFor(2) must equal baseDelayMs * multiplier = 2 000 ms.");
    }

    @Test
    @DisplayName("delayFor(3) returns baseDelay * multiplier^2")
    void testDelayThirdAttempt() {
        // Expected: 1_000 * 2.0^2 = 4_000 ms
        Duration delay = policy.delayFor(3);

        assertEquals(Duration.ofMillis(4_000L), delay,
                "delayFor(3) must equal baseDelayMs * multiplier^2 = 4 000 ms.");
    }

    @Test
    @DisplayName("delayFor(100) is capped at maxDelayMs")
    void testDelayMaxCap() {
        // 1_000 * 2^99 is astronomically large; must be capped at 10_000 ms
        Duration delay = policy.delayFor(100);

        assertEquals(Duration.ofMillis(10_000L), delay,
                "delayFor with a very large attempt number must not exceed maxDelayMs.");
    }

    @Test
    @DisplayName("delayFor(attemptNumber) never exceeds maxDelayMs for any input")
    void testDelayNeverExceedsMax() {
        for (int i = 1; i <= 50; i++) {
            Duration delay = policy.delayFor(i);
            assertTrue(delay.toMillis() <= policy.getMaxDelayMs(),
                    "delayFor(" + i + ") = " + delay.toMillis()
                    + " ms must not exceed maxDelayMs = " + policy.getMaxDelayMs() + " ms.");
        }
    }

    // ------------------------------------------------------------------
    //  shouldRetry() tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("shouldRetry() returns true when attemptCount is below maxAttempts")
    void testShouldRetryBelowMax() {
        // maxAttempts = 3; attemptCount = 2 → should retry
        assertTrue(policy.shouldRetry(2),
                "shouldRetry(2) must be true when maxAttempts is 3.");
    }

    @Test
    @DisplayName("shouldRetry() returns true for attemptCount = 0")
    void testShouldRetryAtZero() {
        assertTrue(policy.shouldRetry(0),
                "shouldRetry(0) must be true — no attempts have been made yet.");
    }

    @Test
    @DisplayName("shouldRetry() returns false when attemptCount equals maxAttempts")
    void testShouldNotRetryAtMax() {
        // maxAttempts = 3; attemptCount = 3 → exhausted
        assertFalse(policy.shouldRetry(3),
                "shouldRetry(3) must be false when maxAttempts is 3.");
    }

    @Test
    @DisplayName("shouldRetry() returns false when attemptCount exceeds maxAttempts")
    void testShouldNotRetryAboveMax() {
        assertFalse(policy.shouldRetry(10),
                "shouldRetry(10) must be false when maxAttempts is 3.");
    }

    // ------------------------------------------------------------------
    //  defaultPolicy() factory tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("defaultPolicy() returns a policy with the documented defaults")
    void testDefaultPolicyValues() {
        RetryPolicy defaults = RetryPolicy.defaultPolicy();

        assertAll("Default RetryPolicy values",
                () -> assertEquals(3,        defaults.getMaxAttempts(),
                        "maxAttempts must be 3"),
                () -> assertEquals(5_000L,   defaults.getBaseDelayMs(),
                        "baseDelayMs must be 5 000 ms"),
                () -> assertEquals(2.0,      defaults.getMultiplier(),  0.001,
                        "multiplier must be 2.0"),
                () -> assertEquals(60_000L,  defaults.getMaxDelayMs(),
                        "maxDelayMs must be 60 000 ms")
        );
    }

    @Test
    @DisplayName("defaultPolicy() delay progression matches documented examples")
    void testDefaultPolicyDelayProgression() {
        RetryPolicy defaults = RetryPolicy.defaultPolicy();

        // Attempt 1 : 5_000 ms
        assertEquals(Duration.ofMillis(5_000L), defaults.delayFor(1),
                "First retry delay must be 5 000 ms.");
        // Attempt 2 : 5_000 * 2 = 10_000 ms
        assertEquals(Duration.ofMillis(10_000L), defaults.delayFor(2),
                "Second retry delay must be 10 000 ms.");
        // Attempt 3 : 5_000 * 4 = 20_000 ms
        assertEquals(Duration.ofMillis(20_000L), defaults.delayFor(3),
                "Third retry delay must be 20 000 ms.");
        // Attempt 4 : 5_000 * 8 = 40_000 ms (still under 60 s cap)
        assertEquals(Duration.ofMillis(40_000L), defaults.delayFor(4),
                "Fourth retry delay must be 40 000 ms.");
        // Attempt 5 : 5_000 * 16 = 80_000 → capped at 60_000 ms
        assertEquals(Duration.ofMillis(60_000L), defaults.delayFor(5),
                "Fifth retry delay must be capped at 60 000 ms.");
    }

    // ------------------------------------------------------------------
    //  Constructor validation tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Constructor rejects maxAttempts < 1")
    void testConstructorRejectsInvalidMaxAttempts() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(0, 1_000L, 2.0, 10_000L),
                "maxAttempts = 0 must throw IllegalArgumentException.");
    }

    @Test
    @DisplayName("Constructor rejects negative baseDelayMs")
    void testConstructorRejectsNegativeBaseDelay() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, -1L, 2.0, 10_000L),
                "Negative baseDelayMs must throw IllegalArgumentException.");
    }

    @Test
    @DisplayName("Constructor rejects multiplier < 1.0")
    void testConstructorRejectsLowMultiplier() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, 1_000L, 0.5, 10_000L),
                "multiplier < 1.0 must throw IllegalArgumentException.");
    }

    @Test
    @DisplayName("Constructor rejects maxDelayMs < baseDelayMs")
    void testConstructorRejectsMaxBelowBase() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, 5_000L, 2.0, 1_000L),
                "maxDelayMs < baseDelayMs must throw IllegalArgumentException.");
    }
}
