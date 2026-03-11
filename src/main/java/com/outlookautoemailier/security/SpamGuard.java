package com.outlookautoemailier.security;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Delivery safety layer that reduces the risk of outbound mass-email being
 * classified as spam by ISPs and receiving MTAs.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li><b>Pacing</b> — {@link #applyDelay()} inserts a human-like randomised
 *       inter-message pause using a {@link SecureRandom} source so that the
 *       send cadence does not form a detectable fixed-interval pattern.</li>
 *   <li><b>Header compliance</b> — {@link #applyHeaders} stamps each message
 *       with the RFC 2076 / RFC 3834 / RFC 2369 headers that cooperative
 *       bulk-mail senders are expected to include.</li>
 *   <li><b>Header validation</b> — {@link #validateHeaders} asserts that
 *       required headers are present before a message is sent.</li>
 *   <li><b>Content heuristics</b> — {@link #checkContent} scans subject and
 *       body for patterns commonly flagged by spam filters and returns
 *       human-readable advisory warnings.</li>
 * </ol>
 *
 * <p>Typical usage:
 * <pre>{@code
 *   SpamGuard guard = new SpamGuard(3_000L, 8_000L);
 *   guard.applyHeaders(message, "example.com");
 *   guard.validateHeaders(message);
 *   List<String> warnings = guard.checkContent(subject, body);
 *   warnings.forEach(w -> log.warn("SpamGuard: {}", w));
 *   guard.applyDelay();
 *   transport.sendMessage(message, ...);
 * }</pre>
 */
public class SpamGuard {

    private static final Logger log = LoggerFactory.getLogger(SpamGuard.class);

    // ------------------------------------------------------------------
    //  Spam heuristic constants
    // ------------------------------------------------------------------

    /** Maximum recommended RFC 5322 subject line length before a warning is issued. */
    private static final int MAX_SUBJECT_LENGTH = 78;

    /** Number of exclamation marks in the subject that triggers a warning. */
    private static final int MAX_EXCLAMATION_MARKS = 2;

    /** Spam-indicative keywords checked against the uppercased subject and body. */
    private static final String[] SPAM_KEYWORDS = {
            "FREE", "WINNER", "CLICK HERE", "ACT NOW",
            "URGENT", "GUARANTEED", "NO RISK"
    };

    /** Minimum fraction of uppercase characters in subject to trigger an all-caps warning. */
    private static final double ALL_CAPS_THRESHOLD = 0.80;

    // ------------------------------------------------------------------
    //  Instance state
    // ------------------------------------------------------------------

    /** Lower bound (inclusive) of the randomised inter-message delay in milliseconds. */
    private final long minDelayMs;

    /** Upper bound (inclusive) of the randomised inter-message delay in milliseconds. */
    private final long maxDelayMs;

    /**
     * Cryptographically strong RNG used for delay sampling so that the resulting
     * send pattern does not repeat or become predictable.
     */
    private final SecureRandom random = new SecureRandom();

    // ------------------------------------------------------------------
    //  Constructors
    // ------------------------------------------------------------------

    /**
     * Creates a {@code SpamGuard} with the supplied delay bounds.
     *
     * @param minDelayMs minimum inter-message pause in milliseconds (must be &ge; 0)
     * @param maxDelayMs maximum inter-message pause in milliseconds (must be &ge; minDelayMs)
     * @throws IllegalArgumentException if the delay bounds are invalid
     */
    public SpamGuard(long minDelayMs, long maxDelayMs) {
        if (minDelayMs < 0) {
            throw new IllegalArgumentException("minDelayMs must not be negative: " + minDelayMs);
        }
        if (maxDelayMs < minDelayMs) {
            throw new IllegalArgumentException(
                    "maxDelayMs (" + maxDelayMs + ") must be >= minDelayMs (" + minDelayMs + ")");
        }
        this.minDelayMs = minDelayMs;
        this.maxDelayMs = maxDelayMs;
        log.debug("SpamGuard initialised with delay range [{}, {}] ms.", minDelayMs, maxDelayMs);
    }

    /**
     * Creates a {@code SpamGuard} with the default delay range of 3 000–8 000 ms.
     */
    public SpamGuard() {
        this(3_000L, 8_000L);
    }

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    /**
     * Sleeps for a random duration uniformly distributed in
     * [{@code minDelayMs}, {@code maxDelayMs}].
     *
     * <p>Call this method immediately before each {@code Transport.sendMessage()}
     * invocation.  Using {@link SecureRandom} as the source ensures the send
     * intervals are statistically indistinguishable from human-initiated sending.
     *
     * @throws InterruptedException if the current thread is interrupted while sleeping
     */
    public void applyDelay() throws InterruptedException {
        long range = maxDelayMs - minDelayMs;
        // nextLong(bound) requires bound > 0; guard the edge case of equal bounds.
        long delay = (range == 0)
                ? minDelayMs
                : minDelayMs + (random.nextLong() & Long.MAX_VALUE) % (range + 1);

        log.debug("Applying inter-message delay: {} ms.", delay);
        Thread.sleep(delay);
    }

    /**
     * Validates that the mandatory anti-spam headers are present in {@code message}.
     *
     * <p>Required headers: {@code Message-ID}, {@code List-Unsubscribe},
     * {@code Precedence}.
     *
     * @param message the MIME message to inspect
     * @throws IllegalArgumentException if one or more required headers are absent
     * @throws Exception                if the Jakarta Mail API reports an error
     */
    public void validateHeaders(MimeMessage message) throws Exception {
        List<String> missing = new ArrayList<>();

        if (isHeaderAbsent(message, "Message-ID")) {
            missing.add("Message-ID");
        }
        if (isHeaderAbsent(message, "List-Unsubscribe")) {
            missing.add("List-Unsubscribe");
        }
        if (isHeaderAbsent(message, "Precedence")) {
            missing.add("Precedence");
        }

        if (!missing.isEmpty()) {
            String msg = "Required anti-spam headers are missing: " + missing;
            log.warn("Header validation failed — {}", msg);
            throw new IllegalArgumentException(msg);
        }

        log.debug("Header validation passed.");
    }

    /**
     * Generates a globally unique, RFC 5322 compliant Message-ID.
     *
     * <p>Format: {@code <&lt;epochMillis&gt;.&lt;64-bit hex random&gt;@&lt;domain&gt;>}
     *
     * @param domain the sending domain (e.g. {@code "example.com"}); must not be null or blank
     * @return a Message-ID string including the angle-bracket delimiters
     * @throws IllegalArgumentException if {@code domain} is null or blank
     */
    public String generateMessageId(String domain) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain must not be null or blank");
        }
        long timestamp  = Instant.now().toEpochMilli();
        long randomPart = random.nextLong();

        String id = String.format("<%d.%016x@%s>",
                timestamp,
                randomPart,
                domain.toLowerCase(Locale.ROOT).trim());

        log.debug("Generated Message-ID: {}", id);
        return id;
    }

    /**
     * Stamps the supplied {@link MimeMessage} with the standard bulk-mail headers
     * required for responsible mass delivery:
     *
     * <table border="1">
     *   <tr><th>Header</th><th>Value</th></tr>
     *   <tr><td>Message-ID</td><td>generated if absent</td></tr>
     *   <tr><td>Precedence</td><td>bulk</td></tr>
     *   <tr><td>X-Mailer</td><td>OutlookAutoEmailer/1.0</td></tr>
     *   <tr><td>List-Unsubscribe</td><td>placeholder; replace with real URL</td></tr>
     *   <tr><td>Auto-Submitted</td><td>auto-generated</td></tr>
     * </table>
     *
     * @param message      the message to modify in place
     * @param senderDomain the sender's domain, used when generating the Message-ID
     * @throws Exception   if Jakarta Mail throws while reading or setting headers
     */
    public void applyHeaders(MimeMessage message, String senderDomain) throws Exception {
        // Message-ID: generate only if not already set.
        if (isHeaderAbsent(message, "Message-ID")) {
            String messageId = generateMessageId(senderDomain);
            message.setHeader("Message-ID", messageId);
            log.debug("Applied Message-ID: {}", messageId);
        } else {
            log.debug("Message-ID already present; skipping generation.");
        }

        // Precedence: bulk  — signals to MTAs and auto-responders that this is
        // automated bulk mail and suppresses out-of-office replies.
        message.setHeader("Precedence", "bulk");

        // X-Mailer: identifies the sending application.
        message.setHeader("X-Mailer", "OutlookAutoEmailer/1.0");

        // List-Unsubscribe: required by Gmail, Yahoo, and most major providers
        // for bulk senders.  This is a placeholder; replace with the actual
        // unsubscribe URL or mailto before production use.
        message.setHeader("List-Unsubscribe",
                "<https://example.com/unsubscribe>, <mailto:unsubscribe@example.com>");

        // Auto-Submitted: RFC 3834 header that suppresses automated responses
        // (e.g. vacation auto-replies) from recipients.
        message.setHeader("Auto-Submitted", "auto-generated");

        log.debug("Anti-spam headers applied to message.");
    }

    /**
     * Performs heuristic content safety analysis on the email subject and body.
     *
     * <p>Returns an empty list when no issues are detected.  Each element of the
     * returned list is a human-readable advisory string that should be presented
     * to the operator before sending (or logged as a warning).
     *
     * <p>Checks performed:
     * <ol>
     *   <li>Subject is predominantly uppercase (>80% alpha characters are upper).</li>
     *   <li>Subject contains more than {@value #MAX_EXCLAMATION_MARKS} exclamation marks.</li>
     *   <li>Subject or body contains a known spam-trigger keyword from
     *       {@link #SPAM_KEYWORDS}.</li>
     *   <li>Subject line exceeds {@value #MAX_SUBJECT_LENGTH} characters
     *       (RFC 5322 recommendation).</li>
     *   <li>Body appears to be HTML-only (contains {@code <html} tag) but lacks
     *       any plain-text alternative content.</li>
     * </ol>
     *
     * @param subject the email subject line; may be null (treated as empty)
     * @param body    the email body text or HTML; may be null (treated as empty)
     * @return a mutable list of warning strings; empty if the content passes all checks
     */
    public List<String> checkContent(String subject, String body) {
        List<String> warnings = new ArrayList<>();

        String safeSubject = (subject != null) ? subject : "";
        String safeBody    = (body    != null) ? body    : "";
        String upperSubject = safeSubject.toUpperCase(Locale.ROOT);
        String upperBody    = safeBody.toUpperCase(Locale.ROOT);

        // --- 1. All-caps subject detection ------------------------------------
        long alphaCount = safeSubject.chars()
                .filter(Character::isLetter)
                .count();
        if (alphaCount > 3) {
            long upperCount = safeSubject.chars()
                    .filter(c -> Character.isLetter(c) && Character.isUpperCase(c))
                    .count();
            double upperRatio = (double) upperCount / alphaCount;
            if (upperRatio >= ALL_CAPS_THRESHOLD) {
                String msg = String.format(
                        "Subject appears to be predominantly uppercase (%.0f%% uppercase letters).",
                        upperRatio * 100);
                log.debug("SpamGuard content warning: {}", msg);
                warnings.add(msg);
            }
        }

        // --- 2. Excessive exclamation marks in subject -----------------------
        long exclamationCount = safeSubject.chars()
                .filter(c -> c == '!')
                .count();
        if (exclamationCount > MAX_EXCLAMATION_MARKS) {
            String msg = String.format(
                    "Subject contains %d exclamation mark(s) (limit: %d); "
                    + "this can trigger spam filters.",
                    exclamationCount, MAX_EXCLAMATION_MARKS);
            log.debug("SpamGuard content warning: {}", msg);
            warnings.add(msg);
        }

        // --- 3. Spam keyword detection in subject and body -------------------
        for (String keyword : SPAM_KEYWORDS) {
            boolean inSubject = upperSubject.contains(keyword);
            boolean inBody    = upperBody.contains(keyword);
            if (inSubject || inBody) {
                String location = inSubject && inBody ? "subject and body"
                        : inSubject ? "subject" : "body";
                String msg = String.format(
                        "Spam-trigger keyword \"%s\" found in %s.", keyword, location);
                log.debug("SpamGuard content warning: {}", msg);
                warnings.add(msg);
            }
        }

        // --- 4. Subject length > RFC recommendation --------------------------
        if (safeSubject.length() > MAX_SUBJECT_LENGTH) {
            String msg = String.format(
                    "Subject line length (%d chars) exceeds the RFC 5322 recommended "
                    + "maximum of %d characters.", safeSubject.length(), MAX_SUBJECT_LENGTH);
            log.debug("SpamGuard content warning: {}", msg);
            warnings.add(msg);
        }

        // --- 5. HTML-only body without plain-text alternative ----------------
        String lowerBody = safeBody.toLowerCase(Locale.ROOT);
        boolean isHtml       = lowerBody.contains("<html");
        boolean hasPlainText = !lowerBody.contains("<html")
                || !safeBody.trim().startsWith("<");  // Rough heuristic: body starts with raw text.

        // More precise check: if the body contains HTML tags but no visible plain text
        // (i.e. the entire content appears to be wrapped in HTML without a plain fallback).
        if (isHtml) {
            // Strip all HTML tags and check if any non-whitespace text remains.
            String strippedText = safeBody.replaceAll("<[^>]*>", "").trim();
            if (strippedText.isEmpty()) {
                String msg = "Email body appears to be HTML-only with no plain-text alternative. "
                        + "This may reduce deliverability; consider adding a plain-text part.";
                log.debug("SpamGuard content warning: {}", msg);
                warnings.add(msg);
            }
        }

        if (warnings.isEmpty()) {
            log.debug("SpamGuard content check passed with no warnings.");
        } else {
            log.warn("SpamGuard content check produced {} warning(s) for subject: [{}]",
                     warnings.size(), safeSubject);
        }

        return warnings;
    }

    // ------------------------------------------------------------------
    //  Private helpers
    // ------------------------------------------------------------------

    /**
     * Returns {@code true} if the specified header is absent or has no non-blank value.
     *
     * @param message    the MIME message to inspect
     * @param headerName the header name to check
     * @return {@code true} if the header is missing or blank
     * @throws Exception if Jakarta Mail throws while reading the header
     */
    private boolean isHeaderAbsent(MimeMessage message, String headerName) throws Exception {
        String[] values = message.getHeader(headerName);
        if (values == null || values.length == 0) {
            return true;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
