package com.outlookautoemailier.smtp;

import com.outlookautoemailier.model.Contact;
import com.outlookautoemailier.model.EmailAccount;
import com.outlookautoemailier.model.EmailJob;
import com.outlookautoemailier.model.EmailTemplate;
import com.outlookautoemailier.security.SpamGuard;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SmtpSender}.
 *
 * <p>Actual SMTP delivery requires a live server and is out of scope for unit
 * tests.  This suite focuses on:
 * <ul>
 *   <li>Constructor null-rejection guards.</li>
 *   <li>Lifecycle enforcement (send before connect must throw).</li>
 *   <li>The static {@link SmtpSender#isPermanentFailure(MessagingException)}
 *       classification method.</li>
 *   <li>The {@link SmtpConfig} factory methods.</li>
 * </ul>
 */
class SmtpSenderTest {

    // -----------------------------------------------------------------------
    //  Shared fixtures (rebuilt fresh before each test via @BeforeEach)
    // -----------------------------------------------------------------------

    private SmtpConfig   config;
    private EmailAccount account;
    private SpamGuard    guard;

    @BeforeEach
    void setUp() {
        config = SmtpConfig.office365();

        account = EmailAccount.builder()
                .emailAddress("sender@example.com")
                .accountType(EmailAccount.AccountType.SENDER)
                .tenantId("test-tenant-id")
                .clientId("test-client-id")
                .accessToken("test-access-token")
                .refreshToken("test-refresh-token")
                .tokenExpiresAt(LocalDateTime.now().plusHours(1))
                .build();

        guard = new SpamGuard(0L, 0L);   // zero delay so tests complete instantly
    }

    // -----------------------------------------------------------------------
    //  Constructor null-rejection
    // -----------------------------------------------------------------------

    @Test
    void testConstructorRejectsNullConfig() {
        assertThrows(NullPointerException.class,
                () -> new SmtpSender(null, account, guard),
                "SmtpSender(null, account, guard) must throw NullPointerException");
    }

    @Test
    void testConstructorRejectsNullAccount() {
        assertThrows(NullPointerException.class,
                () -> new SmtpSender(config, null, guard),
                "SmtpSender(config, null, guard) must throw NullPointerException");
    }

    @Test
    void testConstructorRejectsNullGuard() {
        assertThrows(NullPointerException.class,
                () -> new SmtpSender(config, account, null),
                "SmtpSender(config, account, null) must throw NullPointerException");
    }

    // -----------------------------------------------------------------------
    //  Lifecycle: send() before connect()
    // -----------------------------------------------------------------------

    @Test
    void testSendBeforeConnectThrows() {
        SmtpSender sender = new SmtpSender(config, account, guard);

        Contact contact = Contact.builder()
                .displayName("Test Recipient")
                .addEmailAddress("recipient@example.com")
                .build();

        EmailTemplate template = EmailTemplate.builder()
                .name("test-template")
                .subject("Hello {{firstName}}")
                .body("Hello, this is a test message.")
                .build();

        EmailJob job = EmailJob.builder()
                .contact(contact)
                .template(template)
                .build();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> sender.send(job),
                "Calling send() before connect() must throw IllegalStateException");

        assertTrue(ex.getMessage().contains("connect()"),
                "IllegalStateException message must mention connect()");
    }

    // -----------------------------------------------------------------------
    //  isPermanentFailure() — SMTP reply-code classification
    // -----------------------------------------------------------------------

    @Test
    void testIsPermanentFailure_550() {
        MessagingException ex = new MessagingException("550 user unknown");
        assertTrue(SmtpSender.isPermanentFailure(ex),
                "A 550 response is a permanent failure and must return true");
    }

    @Test
    void testIsPermanentFailure_421() {
        MessagingException ex = new MessagingException("421 service unavailable");
        assertFalse(SmtpSender.isPermanentFailure(ex),
                "A 421 response is a transient (4xx) failure and must return false");
    }

    @Test
    void testIsPermanentFailure_null() {
        assertFalse(SmtpSender.isPermanentFailure(null),
                "isPermanentFailure(null) must return false, not throw");
    }

    @Test
    void testIsPermanentFailure_noSuchUser() {
        MessagingException ex = new MessagingException("User unknown in virtual mailbox table");
        assertTrue(SmtpSender.isPermanentFailure(ex),
                "A message containing 'user unknown' must be treated as permanent");
    }

    // -----------------------------------------------------------------------
    //  SmtpConfig factory methods
    // -----------------------------------------------------------------------

    @Test
    void testOffice365Config() {
        SmtpConfig office365 = SmtpConfig.office365();

        assertEquals("smtp.office365.com", office365.getHost(),
                "office365() host must be smtp.office365.com");
        assertEquals(587, office365.getPort(),
                "office365() port must be 587");
        assertTrue(office365.isUseOAuth2(),
                "office365() must enable OAuth2 (XOAUTH2)");
        assertTrue(office365.isStartTlsEnabled(),
                "office365() must enable STARTTLS");
    }

    @Test
    void testGmailConfig() {
        SmtpConfig gmail = SmtpConfig.gmail();

        assertEquals("smtp.gmail.com", gmail.getHost(),
                "gmail() host must be smtp.gmail.com");
        assertFalse(gmail.isUseOAuth2(),
                "gmail() must disable OAuth2 (uses App Password instead)");
    }
}
