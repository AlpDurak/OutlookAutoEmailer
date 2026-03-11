package com.outlookautoemailier.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates email addresses before they are dispatched by the mailer.
 *
 * <p>Two levels of checking are available:
 * <ol>
 *   <li><b>Format</b> — every address is tested against an RFC 5322 simplified
 *       regular expression that rejects the vast majority of syntactically invalid
 *       addresses with negligible CPU cost.</li>
 *   <li><b>DNS MX lookup</b> — when {@code checkMxRecord} is {@code true} the
 *       domain part of each address is additionally resolved via a JNDI DNS query.
 *       Addresses whose domain has no MX records are rejected, which eliminates
 *       typo domains and deactivated corporate domains before a costly SMTP
 *       connection attempt is made.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   // Fast path — format check only (suitable for interactive input validation).
 *   EmailValidator validator = new EmailValidator();
 *   ValidationResult result = validator.validate("user@example.com");
 *   if (!result.isValid()) {
 *       log.warn("Rejected: {}", result);
 *   }
 *
 *   // Strict path — format + DNS MX check (suitable for bulk pre-flight).
 *   EmailValidator strict = new EmailValidator(true);
 *   List<String> clean = strict.filterValid(recipients);
 * }</pre>
 */
public class EmailValidator {

    private static final Logger log = LoggerFactory.getLogger(EmailValidator.class);

    // ------------------------------------------------------------------
    //  Constants
    // ------------------------------------------------------------------

    /**
     * RFC 5322 simplified regex — catches the vast majority of invalid
     * addresses while keeping the pattern readable and fast to compile.
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+" +
            "@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?" +
            "(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$");

    /** Maximum milliseconds to wait for a DNS MX response before giving up. */
    private static final int DNS_TIMEOUT_MS = 3000;

    // ------------------------------------------------------------------
    //  Instance state
    // ------------------------------------------------------------------

    /**
     * When {@code true}, {@link #validate(String)} performs a DNS MX record
     * lookup in addition to the format check.
     */
    private final boolean checkMxRecord;

    // ------------------------------------------------------------------
    //  Constructors
    // ------------------------------------------------------------------

    /**
     * Creates a format-only validator.
     *
     * <p>Equivalent to {@code new EmailValidator(false)}.  This constructor
     * is suitable for interactive input validation where a sub-millisecond
     * response is required.
     */
    public EmailValidator() {
        this(false);
    }

    /**
     * Creates an email validator with configurable MX-record checking.
     *
     * @param checkMxRecord {@code true} to enable DNS MX lookups in addition
     *                      to the RFC 5322 format check; {@code false} for
     *                      format-only validation
     */
    public EmailValidator(boolean checkMxRecord) {
        this.checkMxRecord = checkMxRecord;
        log.debug("EmailValidator initialised (checkMxRecord={}).", checkMxRecord);
    }

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    /**
     * Validates a single email address.
     *
     * <p>Checks are applied in order:
     * <ol>
     *   <li>Null / blank guard.</li>
     *   <li>RFC 5322 format check via {@link #isFormatValid(String)}.</li>
     *   <li>DNS MX record lookup via {@link #hasMxRecord(String)} (only when
     *       this instance was constructed with {@code checkMxRecord = true}).</li>
     * </ol>
     *
     * @param email the address to validate; may be {@code null}
     * @return a {@link ValidationResult} describing the outcome; never {@code null}
     */
    public ValidationResult validate(String email) {
        // 1. Null / blank guard.
        if (email == null || email.isBlank()) {
            log.debug("Validation failed — null or blank input.");
            return new ValidationResult(email, false, "Empty or null email address");
        }

        // 2. RFC 5322 format check.
        if (!isFormatValid(email)) {
            log.debug("Validation failed — format check rejected: {}", email);
            return new ValidationResult(email, false, "Does not match RFC 5322 format");
        }

        // 3. Optional DNS MX record check.
        if (checkMxRecord) {
            String domain = email.substring(email.indexOf('@') + 1);
            if (!hasMxRecord(domain)) {
                log.debug("Validation failed — no MX record for domain '{}' (email: {}).",
                          domain, email);
                return new ValidationResult(email, false,
                        "No MX record found for domain: " + domain);
            }
        }

        log.debug("Validation passed for: {}", email);
        return new ValidationResult(email, true, null);
    }

    /**
     * Batch validation: validates every address in {@code emails} and returns
     * only those that pass all configured checks.
     *
     * <p>Invalid addresses are logged at DEBUG level and silently dropped from
     * the result list.
     *
     * @param emails the candidate addresses; {@code null} entries are treated as
     *               invalid and excluded from the result
     * @return a new mutable list containing only the valid addresses; never {@code null}
     */
    public List<String> filterValid(List<String> emails) {
        if (emails == null || emails.isEmpty()) {
            log.debug("filterValid() called with null or empty list; returning empty result.");
            return new ArrayList<>();
        }

        List<String> valid = new ArrayList<>(emails.size());
        int rejected = 0;

        for (String email : emails) {
            ValidationResult result = validate(email);
            if (result.isValid()) {
                valid.add(email);
            } else {
                rejected++;
                log.debug("filterValid() rejected {}: {}", email, result.getReason());
            }
        }

        log.debug("filterValid() processed {} address(es): {} valid, {} rejected.",
                  emails.size(), valid.size(), rejected);
        return valid;
    }

    /**
     * Returns {@code true} if {@code email} matches the RFC 5322 simplified
     * pattern defined by {@link #EMAIL_PATTERN}.
     *
     * <p>This method is pure (no I/O, no side-effects) and safe to call from
     * any thread.
     *
     * @param email the address string to test; {@code null} returns {@code false}
     * @return {@code true} if the address is syntactically valid
     */
    public boolean isFormatValid(String email) {
        if (email == null) {
            return false;
        }
        return EMAIL_PATTERN.matcher(email).matches();
    }

    /**
     * Queries the DNS for MX records associated with {@code domain}.
     *
     * <p>Uses JNDI with {@code com.sun.jndi.dns.DnsContextFactory}.  The lookup
     * is bounded by {@value #DNS_TIMEOUT_MS} ms to avoid blocking the calling
     * thread for an extended period on unresponsive nameservers.
     *
     * @param domain the domain portion of an email address (i.e. the part after
     *               {@code @}); must not be {@code null}
     * @return {@code true} if the domain has at least one MX record;
     *         {@code false} if no MX record exists, the lookup times out, or any
     *         {@link NamingException} is thrown
     */
    public boolean hasMxRecord(String domain) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial",       "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial",  String.valueOf(DNS_TIMEOUT_MS));

        try {
            InitialDirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(domain, new String[]{"MX"});
            javax.naming.directory.Attribute mxAttr = attrs.get("MX");
            boolean hasMx = mxAttr != null && mxAttr.size() > 0;
            log.debug("MX lookup for '{}': {} record(s) found.", domain, hasMx ? mxAttr.size() : 0);
            return hasMx;
        } catch (NamingException e) {
            log.debug("MX lookup failed for domain '{}': {}", domain, e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    //  Inner class: ValidationResult
    // ------------------------------------------------------------------

    /**
     * Immutable value object that carries the result of a single address
     * validation.
     *
     * <p>Instances are created exclusively by {@link EmailValidator#validate(String)}.
     */
    public static final class ValidationResult {

        private final String  email;
        private final boolean valid;

        /**
         * Human-readable rejection reason, or {@code null} when the address is
         * valid.
         */
        private final String  reason;

        /**
         * Constructs a new result.
         *
         * @param email  the address that was validated
         * @param valid  {@code true} if the address passed all checks
         * @param reason the rejection reason; pass {@code null} for valid results
         */
        public ValidationResult(String email, boolean valid, String reason) {
            this.email  = email;
            this.valid  = valid;
            this.reason = reason;
        }

        /**
         * Returns {@code true} if the address passed all configured validation
         * checks.
         *
         * @return {@code true} for a valid address
         */
        public boolean isValid() {
            return valid;
        }

        /**
         * Returns the email address that was validated.
         *
         * @return the address string; may be {@code null} if the input was {@code null}
         */
        public String getEmail() {
            return email;
        }

        /**
         * Returns the human-readable reason the address was rejected, or
         * {@code null} if the address is valid.
         *
         * @return rejection reason or {@code null}
         */
        public String getReason() {
            return reason;
        }

        /**
         * Returns a compact string representation.
         *
         * <p>Examples:
         * <ul>
         *   <li>{@code VALID[user@example.com]}</li>
         *   <li>{@code INVALID[bad-email]: Does not match RFC 5322 format}</li>
         * </ul>
         *
         * @return a non-null, human-readable summary
         */
        @Override
        public String toString() {
            return valid
                    ? "VALID[" + email + "]"
                    : "INVALID[" + email + "]: " + reason;
        }
    }
}
