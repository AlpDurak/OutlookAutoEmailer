package com.outlookautoemailier.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EmailJob} covering status transitions, attempt
 * tracking, dead-letter detection, and priority ordering.
 */
class EmailJobTest {

    private Contact       sampleContact;
    private EmailTemplate sampleTemplate;
    private EmailJob      job;

    // ------------------------------------------------------------------ //
    //  Test fixture                                                        //
    // ------------------------------------------------------------------ //

    @BeforeEach
    void setUp() {
        sampleContact = Contact.builder()
                .id("contact-001")
                .displayName("Jane Doe")
                .firstName("Jane")
                .lastName("Doe")
                .emailAddresses(List.of("jane.doe@example.com"))
                .company("Acme Corp")
                .jobTitle("Engineer")
                .build();

        sampleTemplate = EmailTemplate.builder()
                .name("Welcome Email")
                .subject("Hello {{firstName}}!")
                .body("Dear {{firstName}} {{lastName}}, welcome to {{company}}.")
                .build();

        job = EmailJob.builder()
                .contact(sampleContact)
                .template(sampleTemplate)
                .priority(3)
                .maxAttempts(3)
                .build();
    }

    // ------------------------------------------------------------------ //
    //  Tests                                                               //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("New job should have PENDING status by default")
    void testJobDefaultStatus() {
        assertEquals(EmailJob.JobStatus.PENDING, job.getStatus(),
                "A freshly created job must start in PENDING state.");
    }

    @Test
    @DisplayName("incrementAttempt() should increase attemptCount from 0 to 1")
    void testIncrementAttempt() {
        assertEquals(0, job.getAttemptCount(), "Initial attempt count should be 0.");
        job.incrementAttempt();
        assertEquals(1, job.getAttemptCount(), "Attempt count should be 1 after one increment.");
    }

    @Test
    @DisplayName("markSent() should set status to SENT")
    void testMarkSent() {
        job.markSent();
        assertEquals(EmailJob.JobStatus.SENT, job.getStatus(),
                "Status must be SENT after calling markSent().");
    }

    @Test
    @DisplayName("markFailed() should set status to FAILED and record error message")
    void testMarkFailed() {
        job.incrementAttempt(); // 1 out of 3 — not dead yet
        job.markFailed("Connection timeout");

        assertEquals(EmailJob.JobStatus.FAILED, job.getStatus(),
                "Status must be FAILED when there are remaining attempts.");
        assertEquals("Connection timeout", job.getErrorMessage(),
                "Error message must match the reason passed to markFailed().");
    }

    @Test
    @DisplayName("isDead() should return true after maxAttempts failures")
    void testIsDeadAfterMaxAttempts() {
        // Simulate 3 attempts (maxAttempts = 3)
        job.incrementAttempt();
        job.markFailed("Attempt 1 failed");

        job.incrementAttempt();
        job.markFailed("Attempt 2 failed");

        job.incrementAttempt();
        job.markFailed("Attempt 3 failed");

        assertTrue(job.isDead(),
                "Job must be dead after reaching maxAttempts.");
        assertEquals(EmailJob.JobStatus.DEAD_LETTER, job.getStatus(),
                "Status must be DEAD_LETTER once all attempts are exhausted.");
    }

    @Test
    @DisplayName("Job with priority 1 should compare less than job with priority 5")
    void testPriorityComparison() {
        EmailJob highPriorityJob = EmailJob.builder()
                .contact(sampleContact)
                .template(sampleTemplate)
                .priority(1)
                .build();

        EmailJob lowPriorityJob = EmailJob.builder()
                .contact(sampleContact)
                .template(sampleTemplate)
                .priority(5)
                .build();

        assertTrue(highPriorityJob.compareTo(lowPriorityJob) < 0,
                "Job with priority 1 must sort before (be less than) job with priority 5.");
        assertTrue(lowPriorityJob.compareTo(highPriorityJob) > 0,
                "Job with priority 5 must sort after (be greater than) job with priority 1.");
        assertEquals(0, highPriorityJob.compareTo(
                        EmailJob.builder().contact(sampleContact).template(sampleTemplate).priority(1).build()),
                "Jobs with equal priority must compare as equal.");
    }
}
