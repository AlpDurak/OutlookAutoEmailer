package com.outlookautoemailier;

import com.outlookautoemailier.api.ContactFetcher;
import com.outlookautoemailier.api.GraphApiClient;
import com.outlookautoemailier.queue.EmailDispatcher;
import com.outlookautoemailier.queue.EmailQueue;
import com.outlookautoemailier.security.OAuth2Helper;
import com.outlookautoemailier.security.RateLimiter;
import com.outlookautoemailier.security.SpamGuard;
import com.outlookautoemailier.smtp.SmtpConfig;
import com.outlookautoemailier.smtp.SmtpSender;
import com.outlookautoemailier.ui.AccountSetupController;
import com.outlookautoemailier.ui.AnalyticsController;
import com.outlookautoemailier.ui.ComposeController;
import com.outlookautoemailier.ui.ContactListController;
import com.outlookautoemailier.ui.MainController;
import com.outlookautoemailier.ui.QueueDashboardController;
import com.outlookautoemailier.ui.TemplateStudioController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application-wide singleton that holds shared backend state and UI controller
 * references.
 *
 * <p>Obtain the single instance via {@link #get()}.  The instance is created
 * lazily on the first call using double-checked locking so it is safe for use
 * from any thread.
 *
 * <p>UI controllers register themselves at the end of their
 * {@code initialize()} methods so that cross-controller navigation and data
 * exchange can be wired without hard coupling between FXML controllers.
 */
public class AppContext {

    private static final Logger log = LoggerFactory.getLogger(AppContext.class);

    // ── Singleton ────────────────────────────────────────────────────────────

    private static volatile AppContext INSTANCE;

    /** Private constructor — use {@link #get()}. */
    private AppContext() {}

    /**
     * Returns the application-wide {@code AppContext} instance, creating it on
     * the first call (double-checked locking).
     *
     * @return the singleton instance; never {@code null}
     */
    public static AppContext get() {
        if (INSTANCE == null) {
            synchronized (AppContext.class) {
                if (INSTANCE == null) {
                    INSTANCE = new AppContext();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Destroys the current singleton instance.
     * Intended for unit tests that require a clean slate between test cases.
     */
    public static void reset() {
        synchronized (AppContext.class) {
            INSTANCE = null;
        }
    }

    // ── Backend fields ────────────────────────────────────────────────────────

    private EmailQueue            emailQueue;
    private EmailDispatcher       emailDispatcher;
    private GraphApiClient        graphApiClient;
    private ContactFetcher        contactFetcher;
    private OAuth2Helper          oauth2Helper;
    private SmtpSender            smtpSender;
    /** Set by AccountSetupController before initBackend() to select the sender's SMTP provider. */
    private SmtpConfig            senderSmtpConfig;

    // ── UI controller fields ──────────────────────────────────────────────────

    private MainController            mainController;
    private AccountSetupController    accountSetupController;
    private ContactListController     contactListController;
    private ComposeController         composeController;
    private QueueDashboardController  queueDashboardController;
    private TemplateStudioController  templateStudioController;
    private AnalyticsController       analyticsController;

    // ── Status helpers ────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when the minimum backend components required to send
     * emails are all initialised.
     *
     * @return {@code true} if emailQueue, emailDispatcher, and smtpSender are non-null
     */
    public boolean isBackendReady() {
        return emailQueue != null && emailDispatcher != null && smtpSender != null;
    }

    /**
     * Returns {@code true} when both the Graph API client and the sending
     * backend are ready.
     *
     * @return {@code true} if graphApiClient is non-null and {@link #isBackendReady()} is true
     */
    public boolean isFullyConnected() {
        return graphApiClient != null && isBackendReady();
    }

    // ── Backend factory ───────────────────────────────────────────────────────

    /**
     * Initialises just the Graph API client (contact reading) for the source account.
     * Called as soon as the source account is authenticated — does not require the
     * sender account. If contactListController is already registered, triggers an
     * automatic contact refresh.
     *
     * @param sourceAccount the account used to read contacts via Microsoft Graph
     */
    public void initGraphApi(com.outlookautoemailier.model.EmailAccount sourceAccount) {
        graphApiClient = new GraphApiClient(sourceAccount);
        graphApiClient.connect();
        contactFetcher = new ContactFetcher();
        log.info("GraphApiClient initialized for source account: {}", sourceAccount.getEmailAddress());
        if (contactListController != null) {
            javafx.application.Platform.runLater(() -> contactListController.triggerRefresh());
        }
    }

    /**
     * Initialises the backend with default components.
     * Called after both accounts are successfully authenticated.
     *
     * @param sourceAccount  the account used to read contacts via Microsoft Graph
     * @param senderAccount  the account used to send emails via SMTP
     */
    public void initBackend(
            com.outlookautoemailier.model.EmailAccount sourceAccount,
            com.outlookautoemailier.model.EmailAccount senderAccount) {

        // Create EmailQueue
        emailQueue = new EmailQueue();

        // Create SpamGuard with defaults (minDelay=3000 ms, maxDelay=8000 ms)
        SpamGuard spamGuard = new SpamGuard(3_000L, 8_000L);

        // Create RateLimiter with 100 emails/hour
        RateLimiter rateLimiter = new RateLimiter(100);

        // Create SmtpSender — use caller-specified config or default to Office 365
        SmtpConfig smtpConfig = (senderSmtpConfig != null) ? senderSmtpConfig : SmtpConfig.office365();
        smtpSender = new SmtpSender(smtpConfig, senderAccount, spamGuard);
        smtpSender.connect();

        // Create EmailDispatcher with 2 worker threads and start it
        emailDispatcher = new EmailDispatcher(emailQueue, smtpSender, rateLimiter, spamGuard, 2);
        emailDispatcher.start();

        if (graphApiClient == null) {
            graphApiClient = new GraphApiClient(sourceAccount);
            graphApiClient.connect();
            contactFetcher = new ContactFetcher();
        }

        // Sync unsubscribes with Supabase in the background
        com.outlookautoemailier.integration.SupabaseUnsubscribeSync.syncAsync();

        log.info("AppContext backend initialised.");
    }

    // ── Getters and setters — backend ─────────────────────────────────────────

    public EmailQueue getEmailQueue() {
        return emailQueue;
    }

    public void setEmailQueue(EmailQueue emailQueue) {
        this.emailQueue = emailQueue;
    }

    public EmailDispatcher getEmailDispatcher() {
        return emailDispatcher;
    }

    public void setEmailDispatcher(EmailDispatcher emailDispatcher) {
        this.emailDispatcher = emailDispatcher;
    }

    public GraphApiClient getGraphApiClient() {
        return graphApiClient;
    }

    public void setGraphApiClient(GraphApiClient graphApiClient) {
        this.graphApiClient = graphApiClient;
    }

    public ContactFetcher getContactFetcher() {
        return contactFetcher;
    }

    public void setContactFetcher(ContactFetcher contactFetcher) {
        this.contactFetcher = contactFetcher;
    }

    public OAuth2Helper getOauth2Helper() {
        return oauth2Helper;
    }

    public void setOauth2Helper(OAuth2Helper oauth2Helper) {
        this.oauth2Helper = oauth2Helper;
    }

    public SmtpSender getSmtpSender() {
        return smtpSender;
    }

    public void setSmtpSender(SmtpSender smtpSender) {
        this.smtpSender = smtpSender;
    }

    // ── Getters and setters — UI controllers ──────────────────────────────────

    public MainController getMainController() {
        return mainController;
    }

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    public AccountSetupController getAccountSetupController() {
        return accountSetupController;
    }

    public void setAccountSetupController(AccountSetupController accountSetupController) {
        this.accountSetupController = accountSetupController;
    }

    public ContactListController getContactListController() {
        return contactListController;
    }

    public void setContactListController(ContactListController contactListController) {
        this.contactListController = contactListController;
    }

    public ComposeController getComposeController() {
        return composeController;
    }

    public void setComposeController(ComposeController composeController) {
        this.composeController = composeController;
    }

    public QueueDashboardController getQueueDashboardController() {
        return queueDashboardController;
    }

    public void setQueueDashboardController(QueueDashboardController queueDashboardController) {
        this.queueDashboardController = queueDashboardController;
    }

    public TemplateStudioController getTemplateStudioController() {
        return templateStudioController;
    }

    public void setTemplateStudioController(TemplateStudioController templateStudioController) {
        this.templateStudioController = templateStudioController;
    }

    public AnalyticsController getAnalyticsController() {
        return analyticsController;
    }

    public void setAnalyticsController(AnalyticsController analyticsController) {
        this.analyticsController = analyticsController;
    }

    public SmtpConfig getSenderSmtpConfig() { return senderSmtpConfig; }
    public void setSenderSmtpConfig(SmtpConfig cfg) { this.senderSmtpConfig = cfg; }

}
