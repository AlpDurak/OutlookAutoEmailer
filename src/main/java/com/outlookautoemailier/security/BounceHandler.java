package com.outlookautoemailier.security;

import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses DSN (Delivery Status Notification) bounce messages returned by mail
 * servers and classifies them so the application can suppress invalid recipients.
 *
 * <p>RFC 3464 defines the format for Delivery Status Notifications.  A DSN
 * message is a {@code multipart/report} with {@code report-type=delivery-status}.
 * The second body part is a {@code message/delivery-status} part that carries
 * per-recipient status fields ({@code Final-Recipient}, {@code Status},
 * {@code Diagnostic-Code}, etc.).
 *
 * <h2>Classification rules</h2>
 * <ul>
 *   <li><b>HARD</b> — {@code Status} field starts with {@code 5} (permanent
 *       failure: invalid address, unknown user, domain not found).</li>
 *   <li><b>SOFT</b> — {@code Status} field starts with {@code 4} (transient
 *       failure: mailbox full, server temporarily unavailable — safe to retry).</li>
 *   <li><b>UNKNOWN</b> — a bounce-like message was detected but no SMTP status
 *       code could be extracted.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   BounceHandler handler = new BounceHandler();
 *
 *   // From raw RFC 2822 bytes (e.g. pulled from an IMAP bounce mailbox):
 *   BounceHandler.BounceResult result = handler.analyze(rawBytes);
 *   if (result.isBounce() && result.getType() == BounceHandler.BounceType.HARD) {
 *       suppressionList.add(result.getOriginalRecipient());
 *   }
 * }</pre>
 */
public class BounceHandler {

    private static final Logger log = LoggerFactory.getLogger(BounceHandler.class);

    // ------------------------------------------------------------------
    //  DSN field patterns  (RFC 3464)
    // ------------------------------------------------------------------

    /**
     * Matches the SMTP enhanced status code carried in the {@code Status:} field
     * of a {@code message/delivery-status} part.
     * Example match group 1: {@code 5.1.1}
     */
    private static final Pattern STATUS_CODE_PATTERN =
            Pattern.compile("Status:\\s*(\\d\\.\\d+\\.\\d+)");

    /**
     * Matches the bounced address from the {@code Final-Recipient:} field.
     * RFC 3464 format: {@code Final-Recipient: rfc822; user@domain.tld}
     * Example match group 1: {@code user@domain.tld}
     */
    private static final Pattern FINAL_RECIPIENT_PATTERN =
            Pattern.compile("Final-Recipient:.*?;\\s*(.+@.+)", Pattern.CASE_INSENSITIVE);

    /**
     * Matches the human-readable error text from the {@code Diagnostic-Code:} field.
     * RFC 3464 format: {@code Diagnostic-Code: smtp; 550 5.1.1 User unknown}
     * Example match group 1: {@code 550 5.1.1 User unknown}
     */
    private static final Pattern DIAGNOSTIC_PATTERN =
            Pattern.compile("Diagnostic-Code:.*?;\\s*(.+)", Pattern.CASE_INSENSITIVE);

    // ------------------------------------------------------------------
    //  Bounce-subject keywords
    // ------------------------------------------------------------------

    /** Subject substrings (lower-cased) that identify NDR / bounce messages. */
    private static final String[] BOUNCE_SUBJECT_KEYWORDS = {
            "undeliverable",
            "mail delivery failed",
            "delivery status notification",
            "returned mail",
            "delivery failure",
            "non-delivery report",
            "undelivered mail returned",
            "message not delivered"
    };

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    /**
     * Analyzes a raw RFC 2822 message (bytes) to determine if it is a bounce.
     *
     * <p>The bytes are parsed into a {@link MimeMessage} using a minimal
     * {@link Session} (no transport configured) and then delegated to
     * {@link #analyze(MimeMessage)}.
     *
     * @param rawBytes the raw RFC 2822 message bytes; must not be {@code null}
     * @return a {@link BounceResult} describing the outcome; never {@code null}
     * @throws Exception if Jakarta Mail cannot parse the supplied bytes
     */
    public BounceResult analyze(byte[] rawBytes) throws Exception {
        if (rawBytes == null || rawBytes.length == 0) {
            log.debug("analyze(byte[]) — null or empty input; returning non-bounce result.");
            return BounceResult.notABounce();
        }

        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session, new ByteArrayInputStream(rawBytes));
        log.debug("analyze(byte[]) — parsed {} byte(s) into MimeMessage.", rawBytes.length);
        return analyze(message);
    }

    /**
     * Analyzes a Jakarta Mail {@link MimeMessage} to determine if it is a DSN
     * bounce notification.
     *
     * <p>Analysis steps:
     * <ol>
     *   <li>Inspect the top-level {@code Content-Type}: if it is
     *       {@code multipart/report} with {@code report-type=delivery-status},
     *       parse the {@code message/delivery-status} sub-part for
     *       {@code Final-Recipient}, {@code Status}, and {@code Diagnostic-Code}
     *       fields.</li>
     *   <li>If the Content-Type check is inconclusive, fall back to subject-line
     *       keyword matching via {@link #isBounceLike(String)}.</li>
     *   <li>Determine {@link BounceType} from the leading digit of the status code:
     *       {@code 5} → {@code HARD}, {@code 4} → {@code SOFT}, otherwise
     *       {@code UNKNOWN}.</li>
     * </ol>
     *
     * @param message the message to inspect; must not be {@code null}
     * @return a {@link BounceResult}; never {@code null}
     * @throws Exception if Jakarta Mail throws while reading message content
     */
    public BounceResult analyze(MimeMessage message) throws Exception {
        if (message == null) {
            log.debug("analyze(MimeMessage) — null message; returning non-bounce result.");
            return BounceResult.notABounce();
        }

        String subject = message.getSubject();
        log.debug("Analyzing message — subject: [{}]", subject);

        // ------------------------------------------------------------------
        // Step 1: Check Content-Type for multipart/report; report-type=delivery-status
        // ------------------------------------------------------------------
        String contentType = message.getContentType();
        boolean isMultipartReport = contentType != null
                && contentType.toLowerCase(Locale.ROOT).contains("multipart/report")
                && contentType.toLowerCase(Locale.ROOT).contains("delivery-status");

        String deliveryStatusText = null;

        if (isMultipartReport) {
            log.debug("Message is multipart/report — scanning for delivery-status sub-part.");
            deliveryStatusText = extractDeliveryStatusPart(message);
        }

        // ------------------------------------------------------------------
        // Step 2: Fall back to subject keyword check when no DSN part found
        // ------------------------------------------------------------------
        boolean subjectIndicatesBounce = isBounceLike(subject);

        if (deliveryStatusText == null && !subjectIndicatesBounce) {
            log.debug("Message does not appear to be a bounce (no DSN part, no bounce subject).");
            return BounceResult.notABounce();
        }

        // ------------------------------------------------------------------
        // Step 3: Extract recipient, status code, and diagnostic text
        // ------------------------------------------------------------------
        String recipient    = null;
        String statusCode   = null;
        String diagnostic   = null;

        // Use the delivery-status text when available; otherwise scan the full body.
        String scanTarget = (deliveryStatusText != null)
                ? deliveryStatusText
                : extractText(message);

        if (scanTarget != null) {
            // Final-Recipient
            Matcher recipientMatcher = FINAL_RECIPIENT_PATTERN.matcher(scanTarget);
            if (recipientMatcher.find()) {
                recipient = recipientMatcher.group(1).trim();
                log.debug("Extracted Final-Recipient: {}", recipient);
            }

            // Status code
            Matcher statusMatcher = STATUS_CODE_PATTERN.matcher(scanTarget);
            if (statusMatcher.find()) {
                statusCode = statusMatcher.group(1).trim();
                log.debug("Extracted Status code: {}", statusCode);
            }

            // Diagnostic-Code
            Matcher diagnosticMatcher = DIAGNOSTIC_PATTERN.matcher(scanTarget);
            if (diagnosticMatcher.find()) {
                diagnostic = diagnosticMatcher.group(1).trim();
                log.debug("Extracted Diagnostic-Code: {}", diagnostic);
            }
        }

        // Also try the X-Failed-Recipients header as a fallback for the recipient.
        if (recipient == null) {
            String[] xFailed = message.getHeader("X-Failed-Recipients");
            if (xFailed != null && xFailed.length > 0 && xFailed[0] != null) {
                recipient = xFailed[0].trim();
                log.debug("Extracted recipient from X-Failed-Recipients header: {}", recipient);
            }
        }

        // ------------------------------------------------------------------
        // Step 4: Determine BounceType from the leading digit of the status code
        // ------------------------------------------------------------------
        BounceType bounceType = BounceType.UNKNOWN;
        if (statusCode != null && !statusCode.isEmpty()) {
            char leadingDigit = statusCode.charAt(0);
            if (leadingDigit == '5') {
                bounceType = BounceType.HARD;
            } else if (leadingDigit == '4') {
                bounceType = BounceType.SOFT;
            }
        }

        log.info("Bounce detected — type={}, recipient={}, statusCode={}, diagnostic={}",
                bounceType, recipient, statusCode, diagnostic);

        return new BounceResult(recipient, bounceType, statusCode, diagnostic, true);
    }

    /**
     * Returns {@code true} if the subject line suggests this is a bounce or
     * Non-Delivery Report (NDR).
     *
     * <p>The check is case-insensitive and tests the subject against
     * {@link #BOUNCE_SUBJECT_KEYWORDS}.
     *
     * @param subject the message subject; may be {@code null} (returns {@code false})
     * @return {@code true} if the subject contains a recognised bounce keyword
     */
    public boolean isBounceLike(String subject) {
        if (subject == null || subject.isBlank()) {
            return false;
        }
        String lower = subject.toLowerCase(Locale.ROOT);
        for (String keyword : BOUNCE_SUBJECT_KEYWORDS) {
            if (lower.contains(keyword)) {
                log.debug("isBounceLike() — matched keyword '{}' in subject: [{}]", keyword, subject);
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts the bounced recipient email address from a DSN
     * {@link MimeMessage}.
     *
     * <p>Looks first in the {@code message/delivery-status} sub-part for a
     * {@code Final-Recipient:} field, then falls back to the
     * {@code X-Failed-Recipients} header.
     *
     * @param message the message to inspect; must not be {@code null}
     * @return an {@link Optional} containing the email address, or
     *         {@link Optional#empty()} if none could be found
     * @throws Exception if Jakarta Mail throws while reading message content
     */
    public Optional<String> extractRecipient(MimeMessage message) throws Exception {
        if (message == null) {
            return Optional.empty();
        }

        // Try the delivery-status part first (most authoritative source).
        String dsText = extractDeliveryStatusPart(message);
        if (dsText != null) {
            Matcher m = FINAL_RECIPIENT_PATTERN.matcher(dsText);
            if (m.find()) {
                String addr = m.group(1).trim();
                log.debug("extractRecipient() — found in delivery-status part: {}", addr);
                return Optional.of(addr);
            }
        }

        // Fall back to X-Failed-Recipients header.
        String[] xFailed = message.getHeader("X-Failed-Recipients");
        if (xFailed != null && xFailed.length > 0 && xFailed[0] != null) {
            String addr = xFailed[0].trim();
            log.debug("extractRecipient() — found in X-Failed-Recipients header: {}", addr);
            return Optional.of(addr);
        }

        log.debug("extractRecipient() — no recipient found.");
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    //  Private helpers
    // ------------------------------------------------------------------

    /**
     * Walks the MIME structure of {@code message} looking for a
     * {@code message/delivery-status} sub-part and returns its raw text content.
     *
     * <p>Returns {@code null} if no such part is found.
     *
     * @param message the message to inspect
     * @return the raw text of the delivery-status part, or {@code null}
     * @throws Exception if Jakarta Mail throws during traversal
     */
    private String extractDeliveryStatusPart(MimeMessage message) throws Exception {
        Object content = message.getContent();
        if (!(content instanceof Multipart multipart)) {
            return null;
        }

        for (int i = 0; i < multipart.getCount(); i++) {
            Part part = multipart.getBodyPart(i);
            String partType = part.getContentType();
            if (partType != null
                    && partType.toLowerCase(Locale.ROOT).startsWith("message/delivery-status")) {
                // RFC 3464 §2.1 — delivery-status parts are plain text.
                return extractText(part);
            }
        }
        return null;
    }

    /**
     * Extracts all plain-text content from a {@link Part} recursively.
     *
     * <p>For {@code multipart/*} parts the method descends into each sub-part and
     * concatenates the results.  For leaf parts the raw string content is returned
     * directly (Jakarta Mail returns a {@link String} for text/* parts).
     *
     * @param part the MIME part to extract text from
     * @return the concatenated text content, or an empty string if none was found
     * @throws Exception if Jakarta Mail throws while reading the part
     */
    private String extractText(Part part) throws Exception {
        if (part == null) {
            return "";
        }

        Object content = part.getContent();

        if (content instanceof String text) {
            return text;
        }

        if (content instanceof Multipart multipart) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                sb.append(extractText(multipart.getBodyPart(i)));
                sb.append('\n');
            }
            return sb.toString();
        }

        // For other content types (e.g. message/rfc822 wrapper) return empty.
        return "";
    }

    // ------------------------------------------------------------------
    //  Inner enum: BounceType
    // ------------------------------------------------------------------

    /**
     * Classifies the nature of a mail delivery failure.
     */
    public enum BounceType {

        /**
         * Permanent failure (5xx SMTP status codes): the address is invalid, the
         * domain does not exist, or the user is unknown.  The recipient should be
         * added to a suppression list immediately.
         */
        HARD,

        /**
         * Transient failure (4xx SMTP status codes): the remote mailbox is
         * temporarily full, the server is busy, or a greylisting policy is in
         * effect.  A retry after a cooling-off period is appropriate.
         */
        SOFT,

        /**
         * The message was identified as a bounce notification (e.g. by subject
         * keyword) but no SMTP status code could be extracted to determine
         * permanence.  Treat conservatively as a hard bounce or investigate
         * manually.
         */
        UNKNOWN
    }

    // ------------------------------------------------------------------
    //  Inner class: BounceResult
    // ------------------------------------------------------------------

    /**
     * Immutable value object that carries the outcome of a single bounce analysis.
     *
     * <p>When {@link #isBounce()} is {@code false} all other fields are
     * {@code null} / default, and the message should be treated as ordinary mail.
     */
    public static final class BounceResult {

        /** The email address that caused the delivery failure, or {@code null} if not found. */
        private final String originalRecipient;

        /** Classification of the failure type. */
        private final BounceType type;

        /**
         * SMTP enhanced status code as defined by RFC 3463 (e.g. {@code "5.1.1"}),
         * or {@code null} if not present in the DSN.
         */
        private final String statusCode;

        /**
         * Raw diagnostic text from the {@code Diagnostic-Code:} field of the DSN,
         * or {@code null} if absent.  Useful for logging and operator inspection.
         */
        private final String diagnosticMessage;

        /**
         * {@code false} when the analyzed message is not a bounce at all (i.e.
         * it is ordinary inbound mail that happened to be processed by this handler).
         */
        private final boolean isBounce;

        /**
         * Full constructor used by {@link BounceHandler#analyze(MimeMessage)}.
         *
         * @param originalRecipient the bounced address; may be {@code null}
         * @param type              the bounce classification; must not be {@code null}
         * @param statusCode        the enhanced SMTP status code; may be {@code null}
         * @param diagnosticMessage the raw diagnostic text; may be {@code null}
         * @param isBounce          {@code true} if this represents an actual bounce
         */
        public BounceResult(String originalRecipient,
                            BounceType type,
                            String statusCode,
                            String diagnosticMessage,
                            boolean isBounce) {
            this.originalRecipient = originalRecipient;
            this.type              = type;
            this.statusCode        = statusCode;
            this.diagnosticMessage = diagnosticMessage;
            this.isBounce          = isBounce;
        }

        /**
         * Factory method that creates a sentinel result representing a message
         * that is not a bounce at all.
         *
         * @return a {@link BounceResult} with {@code isBounce = false}
         */
        static BounceResult notABounce() {
            return new BounceResult(null, BounceType.UNKNOWN, null, null, false);
        }

        // ---- Getters --------------------------------------------------------

        /**
         * Returns the email address that triggered the delivery failure.
         *
         * @return the bounced address, or {@code null} if it could not be extracted
         */
        public String getOriginalRecipient() {
            return originalRecipient;
        }

        /**
         * Returns the bounce classification.
         *
         * @return {@link BounceType#HARD}, {@link BounceType#SOFT}, or
         *         {@link BounceType#UNKNOWN}; never {@code null} when
         *         {@link #isBounce()} is {@code true}
         */
        public BounceType getType() {
            return type;
        }

        /**
         * Returns the SMTP enhanced status code (RFC 3463) extracted from the DSN.
         *
         * <p>Example values: {@code "5.1.1"} (user unknown),
         * {@code "4.2.2"} (mailbox full).
         *
         * @return the status code string, or {@code null} if absent
         */
        public String getStatusCode() {
            return statusCode;
        }

        /**
         * Returns the raw diagnostic text from the {@code Diagnostic-Code:}
         * field of the delivery-status part.
         *
         * @return diagnostic text, or {@code null} if absent
         */
        public String getDiagnosticMessage() {
            return diagnosticMessage;
        }

        /**
         * Returns {@code true} if the analyzed message is a bounce / NDR.
         *
         * <p>When this returns {@code false} the message should be treated as
         * regular inbound mail; all other fields of this result are meaningless.
         *
         * @return {@code true} for a bounce, {@code false} for regular mail
         */
        public boolean isBounce() {
            return isBounce;
        }

        /**
         * Returns a compact, human-readable summary of this result.
         *
         * <p>Examples:
         * <ul>
         *   <li>{@code BOUNCE[HARD | 5.1.1 | user@example.com] "550 User unknown"}</li>
         *   <li>{@code NOT_A_BOUNCE}</li>
         * </ul>
         *
         * @return a non-null string
         */
        @Override
        public String toString() {
            if (!isBounce) {
                return "NOT_A_BOUNCE";
            }
            return String.format("BOUNCE[%s | %s | %s] \"%s\"",
                    type,
                    statusCode  != null ? statusCode  : "no-status",
                    originalRecipient != null ? originalRecipient : "unknown-recipient",
                    diagnosticMessage != null ? diagnosticMessage : "");
        }
    }
}
