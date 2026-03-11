package com.outlookautoemailier.queue;

import com.outlookautoemailier.model.Contact;
import com.outlookautoemailier.model.EmailJob;
import com.outlookautoemailier.model.EmailJob.JobStatus;
import com.outlookautoemailier.model.EmailTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EmailQueue}.
 *
 * <p>Each test gets a fresh queue via {@link #setUp()}.  Helper methods
 * {@link #buildContact(String)} and {@link #buildJob(int)} produce minimal
 * valid objects without importing any external mocking framework.</p>
 */
class EmailQueueTest {

    // ------------------------------------------------------------------
    //  Test fixtures
    // ------------------------------------------------------------------

    private EmailQueue    queue;
    private Contact       contact;
    private EmailTemplate template;

    @BeforeEach
    void setUp() {
        queue = new EmailQueue();

        contact = buildContact("test@example.com");

        template = EmailTemplate.builder()
                .name("Sprint2 Test Template")
                .subject("Hello {{firstName}}")
                .body("Dear {{firstName}}, this is a test email.")
                .build();
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /**
     * Builds a minimal {@link Contact} with the given primary email address.
     *
     * @param email the primary email address
     * @return a valid {@code Contact} instance
     */
    private Contact buildContact(String email) {
        return Contact.builder()
                .id("c-" + email)
                .displayName("Test User")
                .firstName("Test")
                .lastName("User")
                .addEmailAddress(email)
                .build();
    }

    /**
     * Builds an {@link EmailJob} with the given priority against the shared
     * contact and template.
     *
     * @param priority the job priority (lower = processed first)
     * @return a valid {@code EmailJob} in PENDING status
     */
    private EmailJob buildJob(int priority) {
        return EmailJob.builder()
                .contact(contact)
                .template(template)
                .priority(priority)
                .maxAttempts(3)
                .build();
    }

    // ------------------------------------------------------------------
    //  Tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("enqueue() increments pendingCount for each job added")
    void testEnqueueIncrementsPending() {
        queue.enqueue(buildJob(1));
        queue.enqueue(buildJob(2));
        queue.enqueue(buildJob(3));

        assertEquals(3, queue.pendingCount(),
                "pendingCount must equal the number of enqueued jobs.");
        assertEquals(3, queue.totalCount(),
                "totalCount must reflect all registered jobs.");
    }

    @Test
    @DisplayName("take() returns a job and transitions the queue to SENDING state")
    void testTakeSetsStatusToSending() throws InterruptedException {
        EmailJob job = buildJob(5);
        queue.enqueue(job);

        EmailJob taken = queue.take();

        assertNotNull(taken, "take() must return a non-null job.");
        assertSame(job, taken, "take() must return the exact job that was enqueued.");
        assertEquals(0, queue.pendingCount(),
                "pendingCount must be 0 after the only job is taken.");
        assertEquals(1, queue.sendingCount(),
                "sendingCount must be 1 while the job is in-flight.");
        // The job should appear in the SENDING bucket
        List<EmailJob> sending = queue.getJobsByStatus(JobStatus.SENDING);
        assertTrue(sending.contains(taken),
                "getJobsByStatus(SENDING) must include the in-flight job.");
    }

    @Test
    @DisplayName("markJobSent() moves job from SENDING to SENT and updates counters")
    void testMarkJobSentUpdatesCounters() throws InterruptedException {
        EmailJob job = buildJob(5);
        queue.enqueue(job);
        EmailJob taken = queue.take();

        queue.markJobSent(taken);

        assertEquals(0, queue.sendingCount(),
                "sendingCount must be 0 after the job is marked sent.");
        assertEquals(1, queue.sentCount(),
                "sentCount must be 1 after one successful delivery.");
        assertEquals(JobStatus.SENT, taken.getStatus(),
                "The job's own status must be SENT.");
    }

    @Test
    @DisplayName("markJobFailed() re-enqueues the job when retries remain")
    void testMarkJobFailedRequeues() throws InterruptedException {
        EmailJob job = buildJob(5);
        queue.enqueue(job);          // pendingCount = 1
        queue.take();                // sendingCount = 1, pendingCount = 0

        queue.markJobFailed(job, "Connection refused"); // attempt 1, max = 3 → re-enqueue

        assertEquals(0, queue.sendingCount(),
                "sendingCount must return to 0 after failure is recorded.");
        assertEquals(1, queue.pendingCount(),
                "pendingCount must be 1 because the job was re-enqueued for retry.");
        assertEquals(1, queue.failedCount(),
                "failedCount must be 1 to reflect the failed attempt.");
    }

    @Test
    @DisplayName("markJobFailed() moves job to DEAD_LETTER after maxAttempts")
    void testMarkJobFailedDeadLetter() throws InterruptedException {
        // maxAttempts = 3: exhaust all three attempts
        EmailJob job = buildJob(5);
        queue.enqueue(job);

        for (int i = 0; i < 3; i++) {
            EmailJob taken = queue.take();   // sendingCount++, pendingCount--
            queue.markJobFailed(taken, "Simulated failure " + (i + 1));
            // After attempt 3 isDead() returns true → not re-enqueued
        }

        assertEquals(0, queue.pendingCount(),
                "No pending jobs should remain after all retries are exhausted.");
        assertEquals(0, queue.sendingCount(),
                "No in-flight jobs should remain after all retries are exhausted.");
        assertEquals(1, queue.deadLetterCount(),
                "deadLetterCount must be 1 after the job exhausts maxAttempts.");
        assertEquals(JobStatus.DEAD_LETTER, job.getStatus(),
                "The job's status must be DEAD_LETTER.");
    }

    @Test
    @DisplayName("retryAll() re-enqueues FAILED and DEAD_LETTER jobs")
    void testRetryAllRequeues() throws InterruptedException {
        // Drive one job to DEAD_LETTER
        EmailJob job = buildJob(5);
        queue.enqueue(job);

        for (int i = 0; i < 3; i++) {
            EmailJob taken = queue.take();
            queue.markJobFailed(taken, "failure");
        }

        assertEquals(1, queue.deadLetterCount(), "Pre-condition: one dead-letter job.");
        assertEquals(0, queue.pendingCount(),    "Pre-condition: queue is empty.");

        queue.retryAll();

        assertEquals(0, queue.deadLetterCount(),
                "deadLetterCount must be 0 after retryAll() resets the job.");
        assertEquals(1, queue.pendingCount(),
                "pendingCount must be 1 after the dead job is re-enqueued.");
        assertEquals(1, queue.totalCount(),
                "totalCount must stay at 1 (same job, new instance with same UUID).");

        // Verify the re-enqueued job can be picked up and is fresh
        EmailJob retried = queue.take();
        assertNotNull(retried);
        assertEquals(0, retried.getAttemptCount(),
                "The retried job's attemptCount must be reset to 0.");
        assertEquals(JobStatus.PENDING, retried.getStatus(),
                "The retried job's status must be PENDING.");
    }

    @Test
    @DisplayName("cancelPending() empties the live queue and zeroes pendingCount")
    void testCancelPendingClearsQueue() {
        queue.enqueue(buildJob(1));
        queue.enqueue(buildJob(2));
        queue.enqueue(buildJob(3));

        assertEquals(3, queue.pendingCount(), "Pre-condition: three pending jobs.");

        queue.cancelPending();

        assertEquals(0, queue.pendingCount(),
                "pendingCount must be 0 after cancelPending().");
        assertTrue(queue.isEmpty(),
                "The live queue must be empty after cancelPending().");
        // Master list is unchanged — total stays at 3
        assertEquals(3, queue.totalCount(),
                "totalCount (master list) must not change after cancelPending().");
    }

    @Test
    @DisplayName("Priority ordering: take() returns the lowest-priority-number job first")
    void testPriorityOrder() throws InterruptedException {
        EmailJob jobPriority3 = buildJob(3);
        EmailJob jobPriority1 = buildJob(1);
        EmailJob jobPriority2 = buildJob(2);

        // Enqueue deliberately out of order
        queue.enqueue(jobPriority3);
        queue.enqueue(jobPriority1);
        queue.enqueue(jobPriority2);

        // The PriorityBlockingQueue must deliver them in ascending priority order
        EmailJob first  = queue.take();
        EmailJob second = queue.take();
        EmailJob third  = queue.take();

        assertEquals(1, first.getPriority(),
                "First dequeued job must have priority 1 (highest urgency).");
        assertEquals(2, second.getPriority(),
                "Second dequeued job must have priority 2.");
        assertEquals(3, third.getPriority(),
                "Third dequeued job must have priority 3 (lowest urgency).");
    }
}
