package com.outlookautoemailier.smtp;

import com.outlookautoemailier.model.EmailAccount;
import com.outlookautoemailier.model.EmailJob;
import com.outlookautoemailier.security.SpamGuard;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jakarta Mail-based SMTP sender that delivers a single
 * {@link EmailJob} per invocation of {@link #send(EmailJob)}.
 *
 * <h3>Authentication modes</h3>
 * <ul>
 *   <li><b>OAuth2 (XOAUTH2)</b> — used when
 *       {@link SmtpConfig#isUseOAuth2()} is {@code true}.  The session is
 *       configured with the XOAUTH2 SASL mechanism and an
 *       {@link Authenticator} that returns
 *       {@code new PasswordAuthentication(emailAddress, accessToken)}.
 *       The plain and login mechanisms are disabled so the server uses
 *       XOAUTH2 exclusively.</li>
 *   <li><b>Password</b> — used for non-OAuth2 providers (e.g. Gmail with
 *       App Password).  The standard AUTH LOGIN / AUTH PLAIN mechanisms
 *       are available.</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 * <pre>
 *   constructor → connect() → send() [repeated] → disconnect()
 * </pre>
 * {@link #connect()} must be called once before any call to
 * {@link #send(EmailJob)}.  {@link #disconnect()} releases resources when
 * the dispatcher shuts down.
 *
 * <h3>Spam compliance</h3>
 * <p>Before each message is handed to the transport,
 * {@link SpamGuard#applyHeaders(MimeMessage, String)} is called to stamp
 * the message with the RFC 2076 / RFC 2369 / RFC 3834 bulk-mail headers
 * required by major receiving providers.</p>
 */
public class SmtpSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpSender.class);

    // ------------------------------------------------------------------
    //  Fields
    // ------------------------------------------------------------------

    /** SMTP connection parameters. */
    private final SmtpConfig   config;

    /** The account used to authenticate with the SMTP server. */
    private final EmailAccount senderAccount;

    /** Stamps outbound messages with anti-spam compliance headers. */
    private final SpamGuard    spamGuard;

    /**
     * Jakarta Mail session, initialised by {@link #connect()}.
     * Declared {@code volatile} so that a thread calling {@link #send(EmailJob)}
     * always sees the session written by the thread that called {@link #connect()}.
     */
    private volatile Session session;

    // ------------------------------------------------------------------
    //  Constructor
    // ------------------------------------------------------------------

    /**
     * Creates a new {@code SmtpSender}.
     *
     * @param config        SMTP connection parameters; must not be null
     * @param senderAccount the sending account (access token used for OAuth2); must not be null
     * @param spamGuard     the spam-guard layer used to apply compliance headers; must not be null
     */
    public SmtpSender(SmtpConfig config, EmailAccount senderAccount, SpamGuard spamGuard) {
        this.config        = Objects.requireNonNull(config,        "config");
        this.senderAccount = Objects.requireNonNull(senderAccount, "senderAccount");
        this.spamGuard     = Objects.requireNonNull(spamGuard,     "spamGuard");
    }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    /**
     * Initialises the Jakarta Mail {@link Session} with the SMTP properties
     * derived from {@link #config}.
     *
     * <p>If {@link SmtpConfig#isUseOAuth2()} is {@code true}, the XOAUTH2
     * SASL mechanism is configured and an {@link Authenticator} is provided
     * that returns a {@link PasswordAuthentication} whose "password" is the
     * OAuth2 access token.  The plain and login mechanisms are explicitly
     * disabled so that the server does not fall back to them.</p>
     *
     * <p>If OAuth2 is disabled, the standard authenticator path is used and
     * the session is configured for AUTH LOGIN / AUTH PLAIN only.</p>
     *
     * <p>This method is idempotent: calling it more than once replaces the
     * existing session (useful after token refresh).</p>
     */
    public void connect() {
        Properties props = buildSmtpProperties();

        if (config.isUseOAuth2()) {
            // XOAUTH2: disable PLAIN/LOGIN and declare the SASL mechanism
            props.put("mail.smtp.auth.mechanisms",    "XOAUTH2");
            props.put("mail.smtp.auth.login.disable", "true");
            props.put("mail.smtp.auth.plain.disable", "true");
            props.put("mail.smtp.sasl.enable",        "true");
            props.put("mail.smtp.sasl.mechanisms",    "XOAUTH2");

            final String email       = senderAccount.getEmailAddress();
            final String accessToken = senderAccount.getAccessToken();

            session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(email, accessToken);
                }
            });

            log.info("SMTP session initialised with XOAUTH2 for {}@{}:{}",
                    email, config.getHost(), config.getPort());
        } else {
            // Password-based auth (e.g. Gmail App Password)
            final String email    = senderAccount.getEmailAddress();
            final String password = senderAccount.getAccessToken(); // re-use accessToken field

            session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(email, password);
                }
            });

            log.info("SMTP session initialised with password auth for {}@{}:{}",
                    email, config.getHost(), config.getPort());
        }
    }

    /**
     * Sends the email described by the given {@link EmailJob}.
     *
     * <p>Steps:
     * <ol>
     *   <li>Resolve the personalised subject and body from the job's template
     *       and contact using {@link com.outlookautoemailier.model.EmailTemplate#resolveSubject(com.outlookautoemailier.model.Contact)}
     *       and {@link com.outlookautoemailier.model.EmailTemplate#resolveBody(com.outlookautoemailier.model.Contact)}.</li>
     *   <li>Build a {@link MimeMessage} with From, To, Subject, sent date,
     *       and a plain-text body part wrapped in a {@link MimeMultipart}.</li>
     *   <li>Call {@link SpamGuard#applyHeaders(MimeMessage, String)} to stamp
     *       the required bulk-mail compliance headers.</li>
     *   <li>Deliver via {@link Transport#send(Message)}.</li>
     * </ol>
     *
     * @param job the job to deliver; must not be null
     * @throws MessagingException if Jakarta Mail reports a transport error
     * @throws IllegalStateException if {@link #connect()} has not been called
     */
    public void send(EmailJob job) throws MessagingException {
        Objects.requireNonNull(job, "job must not be null");
        if (session == null) {
            throw new IllegalStateException(
                    "SmtpSender.connect() must be called before send()");
        }

        String recipientEmail = job.getContact().getPrimaryEmail();
        if (recipientEmail == null || recipientEmail.isBlank()) {
            throw new MessagingException(
                    "Contact has no primary email address: " + job.getContact());
        }

        // 1. Resolve personalised content
        String resolvedSubject = job.getTemplate().resolveSubject(job.getContact());
        String resolvedBody    = job.getTemplate().resolveBody(job.getContact());

        // Create a SentEmailRecord for analytics (includes batchId for grouping)
        com.outlookautoemailier.analytics.SentEmailRecord trackingRecord =
                new com.outlookautoemailier.analytics.SentEmailRecord(
                        job.getBatchId(),
                        recipientEmail,
                        job.getContact().getDisplayName(),
                        resolvedSubject,
                        java.time.LocalDateTime.now()
                );

        if (job.getTemplate().isHtml()) {
            // Upload inline Base64 images to imgbb (cached per unique image)
            String imgbbKey = com.outlookautoemailier.config.AppConfig.getInstance().getImgbbApiKey();
            if (!imgbbKey.isBlank()) {
                resolvedBody = replaceInlineImagesWithHosted(resolvedBody, imgbbKey);
            }
            // Append branded footer before normalizing
            resolvedBody = resolvedBody + "\n" + EmailFooter.generate(
                senderAccount.getEmailAddress(), recipientEmail);
            // Inject click-tracking redirect links (skips unsubscribe / mailto / tel)
            resolvedBody = LinkTracker.injectTrackingLinks(
                    resolvedBody, trackingRecord.getTrackingId(), job.getBatchId());
            // Embed 1×1 tracking pixel so open events can be recorded in Supabase
            String anonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
                    + ".eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InRnYmhnd2RncWlueHd4ZWRobm1jIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzMyOTI2NjEsImV4cCI6MjA4ODg2ODY2MX0"
                    + ".wwlqMBYKU53gv2mG15MM70allTNevZut8Mkg5mOu4Mw";
            resolvedBody = resolvedBody
                    + "<img src=\"https://tgbhgwdgqinxwxedhnmc.supabase.co/functions/v1/track-open"
                    + "?apikey=" + anonKey
                    + "&id=" + trackingRecord.getTrackingId()
                    + "\" width=\"1\" height=\"1\" border=\"0\" alt=\"\">";
            // Normalise HTML for cross-client compatibility (Outlook, Gmail, Apple Mail)
            resolvedBody = HtmlEmailNormalizer.normalize(resolvedBody);
        }

        // 2. Construct the MIME message
        MimeMessage message = new MimeMessage(session);

        message.setFrom(new InternetAddress(senderAccount.getEmailAddress()));
        message.setRecipient(Message.RecipientType.TO,
                new InternetAddress(recipientEmail));
        message.setSubject(resolvedSubject, "UTF-8");
        message.setSentDate(new Date());

        // 3. Build multipart body — plain text or HTML
        MimeMultipart multipart = new MimeMultipart("alternative");

        if (job.getTemplate().isHtml()) {
            // Plain-text fallback (strip tags for clients that prefer plain text)
            MimeBodyPart plainPart = new MimeBodyPart();
            plainPart.setText(resolvedBody.replaceAll("<[^>]+>", ""), "UTF-8", "plain");
            multipart.addBodyPart(plainPart);
            // HTML version — added last so email clients prefer it
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(resolvedBody, "text/html; charset=UTF-8");
            multipart.addBodyPart(htmlPart);
        } else {
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(resolvedBody, "UTF-8", "plain");
            multipart.addBodyPart(textPart);
        }

        message.setContent(multipart);

        // 4. Apply spam-guard compliance headers
        String senderDomain = extractDomain(senderAccount.getEmailAddress());
        try {
            spamGuard.applyHeaders(message, senderDomain);
        } catch (Exception e) {
            // Log the issue but do not fail the send; the headers are advisory.
            log.warn("SpamGuard.applyHeaders() failed for job {}: {}", job.getId(), e.getMessage());
        }

        // 5. Deliver
        try {
            Transport.send(message);
            log.info("Delivered job {} to {} (subject: '{}')",
                    job.getId(), recipientEmail, resolvedSubject);
            com.outlookautoemailier.analytics.SentEmailStore.getInstance().add(trackingRecord);
            // Update batch counters locally and patch Supabase
            if (job.getBatchId() != null) {
                com.outlookautoemailier.analytics.BatchStore.getInstance().incrementSent(job.getBatchId());
                com.outlookautoemailier.analytics.BatchStore.getInstance().getById(job.getBatchId())
                        .ifPresent(b -> com.outlookautoemailier.integration.SupabaseAnalyticsSync
                                .patchBatchCountsAsync(job.getBatchId(), b.getSentCount(), b.getFailedCount()));
            }
            com.outlookautoemailier.integration.SupabaseAnalyticsSync.pushSendAsync(trackingRecord);
        } catch (MessagingException ex) {
            // Record the failure in analytics
            String truncated = ex.getMessage() != null
                    ? ex.getMessage().substring(0, Math.min(ex.getMessage().length(), 200))
                    : "Unknown error";
            com.outlookautoemailier.analytics.SentEmailRecord failRecord =
                new com.outlookautoemailier.analytics.SentEmailRecord(
                    java.util.UUID.randomUUID().toString(),
                    job.getBatchId(),
                    recipientEmail,
                    job.getContact().getDisplayName(),
                    resolvedSubject,
                    java.time.LocalDateTime.now(),
                    "FAILED",
                    truncated,
                    null
                );
            com.outlookautoemailier.analytics.SentEmailStore.getInstance().add(failRecord);
            // Update batch failure counter locally and patch Supabase
            if (job.getBatchId() != null) {
                com.outlookautoemailier.analytics.BatchStore.getInstance().incrementFailed(job.getBatchId());
                com.outlookautoemailier.analytics.BatchStore.getInstance().getById(job.getBatchId())
                        .ifPresent(b -> com.outlookautoemailier.integration.SupabaseAnalyticsSync
                                .patchBatchCountsAsync(job.getBatchId(), b.getSentCount(), b.getFailedCount()));
            }
            com.outlookautoemailier.integration.SupabaseAnalyticsSync.pushSendAsync(failRecord);

            if (isPermanentFailure(ex)) {
                log.warn("Permanent SMTP failure for job {} to {}: {}",
                        job.getId(), recipientEmail, ex.getMessage());
                // Auto-suppress confirmed dead addresses to avoid future wasted attempts.
                if (ex.getMessage() != null && (
                        ex.getMessage().contains("User unknown") ||
                        ex.getMessage().contains("No such user") ||
                        ex.getMessage().contains("does not exist"))) {
                    com.outlookautoemailier.security.UnsubscribeManager.getInstance()
                            .addUnsubscribe(recipientEmail);
                    log.info("Auto-suppressed permanently failed address: {}", recipientEmail);
                }
            }
            throw ex; // always rethrow so the dispatcher can handle retry/dead-letter
        }
    }

    /**
     * Releases any open transport resources held by the session.
     *
     * <p>Jakarta Mail's static {@link Transport#send(Message)} API manages
     * connections internally, so there is typically nothing to explicitly
     * close.  This method nulls out the session reference so that subsequent
     * calls to {@link #send(EmailJob)} fail fast until {@link #connect()} is
     * called again.</p>
     */
    public void disconnect() {
        session = null;
        log.info("SmtpSender disconnected.");
    }

    // ------------------------------------------------------------------
    //  Private helpers
    // ------------------------------------------------------------------

    /**
     * Builds the {@link Properties} map that controls the Jakarta Mail
     * {@link Session} behaviour.
     *
     * @return a fully populated {@code Properties} instance
     */
    private Properties buildSmtpProperties() {
        Properties props = new Properties();

        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.host",          config.getHost());
        props.put("mail.smtp.port",          String.valueOf(config.getPort()));

        // Authentication is required in all modes
        props.put("mail.smtp.auth", "true");

        // STARTTLS
        if (config.isStartTlsEnabled()) {
            props.put("mail.smtp.starttls.enable",   "true");
            props.put("mail.smtp.starttls.required", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "false");
        }

        // Timeouts
        props.put("mail.smtp.connectiontimeout",
                String.valueOf(config.getConnectionTimeoutMs()));
        props.put("mail.smtp.timeout",
                String.valueOf(config.getReadTimeoutMs()));

        // Use SSLSocketFactory for SASL/XOAUTH2 compatibility
        if (config.isUseOAuth2()) {
            props.put("mail.smtp.ssl.trust", config.getHost());
        }

        return props;
    }

    /**
     * Returns {@code true} when the exception represents a permanent (5xx) SMTP
     * failure that should not be retried.
     *
     * <p>Permanent failures are identified by:
     * <ul>
     *   <li>The exception message starting with a {@code 5xx} SMTP reply code.</li>
     *   <li>The exception message containing well-known permanent-failure phrases
     *       such as {@code "user unknown"}, {@code "no such user"}, or
     *       {@code "address rejected"}.</li>
     * </ul>
     *
     * <p>A {@code null} argument is treated as non-permanent (returns {@code false}).
     *
     * @param ex the exception to classify; may be null
     * @return {@code true} if the failure is permanent and should not be retried
     */
    public static boolean isPermanentFailure(MessagingException ex) {
        if (ex == null) {
            return false;
        }
        String msg = ex.getMessage();
        if (msg == null) {
            return false;
        }
        // 5xx reply codes are permanent failures; 4xx are transient.
        if (msg.matches("^5\\d{2}.*")) {
            return true;
        }
        // Well-known permanent-failure phrases (case-insensitive).
        String lower = msg.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("user unknown")
                || lower.contains("no such user")
                || lower.contains("address rejected")
                || lower.contains("invalid address")
                || lower.contains("mailbox not found");
    }

    /**
     * Scans {@code html} for inline data-URI images, uploads each one to imgbb,
     * and replaces the {@code src} attribute with the returned hosted URL.
     * If an individual upload fails the original {@code src} is preserved.
     *
     * @param html   the HTML body to process
     * @param apiKey imgbb API key
     * @return the HTML with data URIs replaced by hosted URLs
     */
    private static String replaceInlineImagesWithHosted(String html, String apiKey) {
        Pattern pat = Pattern.compile("src=\"data:image/[^;]+;base64,([^\"]+)\"");
        Matcher mat = pat.matcher(html);
        StringBuffer sb = new StringBuffer();
        ImageCache cache = ImageCache.getInstance();
        while (mat.find()) {
            String base64Data = mat.group(1);
            try {
                String url = cache.get(base64Data);
                if (url == null) {
                    url = ImageHostingService.uploadBase64(base64Data, apiKey);
                    cache.put(base64Data, url);
                    log.info("Uploaded image to imgbb: {}", url);
                } else {
                    log.debug("Using cached imgbb URL for image");
                }
                mat.appendReplacement(sb, Matcher.quoteReplacement("src=\"" + url + "\""));
            } catch (Exception e) {
                log.warn("Failed to upload inline image to imgbb: {}", e.getMessage());
                mat.appendReplacement(sb, Matcher.quoteReplacement(mat.group(0)));
            }
        }
        mat.appendTail(sb);
        return sb.toString();
    }

    /**
     * Extracts the domain portion of an email address (everything after '@').
     *
     * @param email the email address; must contain '@'
     * @return the domain string, or {@code "unknown"} if the address is malformed
     */
    private String extractDomain(String email) {
        if (email == null || !email.contains("@")) {
            log.warn("Cannot extract domain from email address: '{}'", email);
            return "unknown";
        }
        return email.substring(email.indexOf('@') + 1);
    }
}
