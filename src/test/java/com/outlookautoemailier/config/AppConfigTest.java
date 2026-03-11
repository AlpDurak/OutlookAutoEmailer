package com.outlookautoemailier.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AppConfig}.
 *
 * Each test resets the singleton before and after execution so that
 * side-effects do not bleed between cases.
 */
class AppConfigTest {

    @BeforeEach
    void resetBefore() {
        AppConfig.reload();
    }

    @AfterEach
    void resetAfter() {
        AppConfig.reload();
    }

    // -------------------------------------------------------------------------
    // Singleton behaviour
    // -------------------------------------------------------------------------

    @Test
    void testSingletonReturnsSameInstance() {
        AppConfig first  = AppConfig.getInstance();
        AppConfig second = AppConfig.getInstance();
        assertSame(first, second,
                "Two consecutive calls to getInstance() must return the identical object.");
    }

    @Test
    void testReloadCreatesNewInstance() {
        AppConfig before = AppConfig.getInstance();
        AppConfig.reload();
        AppConfig after = AppConfig.getInstance();
        assertNotSame(before, after,
                "After reload(), getInstance() must return a freshly created instance.");
    }

    // -------------------------------------------------------------------------
    // Default values driven by application.properties on the test classpath
    // -------------------------------------------------------------------------

    @Test
    void testDefaultSmtpPort() {
        assertEquals(587, AppConfig.getInstance().getSmtpPort(),
                "Default SMTP port must be 587 (Office 365 STARTTLS).");
    }

    @Test
    void testDefaultRateLimit() {
        assertEquals(100, AppConfig.getInstance().getRateLimitEmailsPerHour(),
                "Default rate limit must be 100 emails per hour.");
    }

    @Test
    void testDelayMinLessThanMax() {
        AppConfig cfg = AppConfig.getInstance();
        assertTrue(cfg.getRateLimitDelayMinMs() < cfg.getRateLimitDelayMaxMs(),
                "Minimum delay must be strictly less than maximum delay.");
    }

    // -------------------------------------------------------------------------
    // summary() masking
    // -------------------------------------------------------------------------

    @Test
    void testSummaryDoesNotExposeFullId() {
        AppConfig cfg = AppConfig.getInstance();
        String summaryText = cfg.summary();

        // The placeholder value in application.properties is "YOUR_CLIENT_ID_HERE".
        // The full string must never appear verbatim in the summary output.
        assertFalse(summaryText.contains("YOUR_CLIENT_ID_HERE"),
                "summary() must not expose the full placeholder client ID.");
    }
}
