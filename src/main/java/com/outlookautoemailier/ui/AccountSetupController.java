package com.outlookautoemailier.ui;

import com.outlookautoemailier.AppContext;
import com.outlookautoemailier.config.AppConfig;
import com.outlookautoemailier.model.EmailAccount;
import com.outlookautoemailier.security.CredentialStore;
import com.outlookautoemailier.security.GoogleOAuth2Helper;
import com.outlookautoemailier.security.OAuth2Helper;
import com.outlookautoemailier.smtp.SmtpConfig;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Controller for {@code AccountSetup.fxml}.
 *
 * <p>Manages two account connections:
 * <ul>
 *   <li><b>Source</b> – Microsoft 365 account for reading Outlook contacts via Graph API.</li>
 *   <li><b>Sender</b> – Microsoft 365 or Gmail account for sending emails via SMTP XOAUTH2.</li>
 * </ul>
 * Both accounts attempt a silent token restore on startup so the user doesn't have to
 * log in again every session.
 */
public class AccountSetupController implements Initializable {

    private static final Logger log = LoggerFactory.getLogger(AccountSetupController.class);

    private static final String PROVIDER_M365  = "Microsoft 365";
    private static final String PROVIDER_GMAIL = "Gmail";

    // ── Source account controls ──────────────────────────────────────────────
    @FXML private Label  sourceStatusLabel;
    @FXML private Label  sourceEmailLabel;
    @FXML private Button btnConnectSource;

    // ── Sender account controls ──────────────────────────────────────────────
    @FXML private ChoiceBox<String> senderProviderChoice;
    @FXML private Label             gmailHintLabel;
    @FXML private Label             senderStatusLabel;
    @FXML private Label             senderEmailLabel;
    @FXML private Button            btnConnectSender;

    /** Stored after successful SOURCE auth. */
    private EmailAccount sourceAccount;
    /** Stored after successful SENDER auth. */
    private EmailAccount senderAccount;

    // ── Initializable ────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        AppContext.get().setAccountSetupController(this);

        // Populate provider choice
        senderProviderChoice.setItems(FXCollections.observableArrayList(PROVIDER_M365, PROVIDER_GMAIL));
        senderProviderChoice.setValue(PROVIDER_M365);
        senderProviderChoice.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> {
            boolean gmail = PROVIDER_GMAIL.equals(val);
            gmailHintLabel.setVisible(gmail);
            gmailHintLabel.setManaged(gmail);
        });

        // Attempt silent restore of previously authenticated sessions
        new Thread(this::tryAutoRestore, "auto-restore").start();
    }

    // ── Auto-restore ─────────────────────────────────────────────────────────

    private void tryAutoRestore() {
        tryRestoreSource();
        tryRestoreSender();
    }

    private void tryRestoreSource() {
        try {
            OAuth2Helper oauth = new OAuth2Helper(new CredentialStore());
            var result = oauth.tryRestoreSession(EmailAccount.AccountType.SOURCE, OAuth2Helper.GRAPH_SCOPES);
            if (result != null) {
                AppConfig cfg = AppConfig.getInstance();
                EmailAccount account = EmailAccount.builder()
                        .accountType(EmailAccount.AccountType.SOURCE)
                        .accessToken(result.accessToken())
                        .tokenExpiresAt(result.expiresOnDate().toInstant()
                                .atZone(ZoneId.systemDefault()).toLocalDateTime())
                        .emailAddress(result.account().username())
                        .tenantId(cfg.getAzureTenantId())
                        .clientId(cfg.getAzureClientId())
                        .build();
                sourceAccount = account;
                Platform.runLater(() -> {
                    markSourceConnected(account.getEmailAddress());
                    AppContext.get().initGraphApi(account);
                    maybeInitBackend();
                });
                log.info("Source account restored silently: {}", account.getEmailAddress());
            }
        } catch (Exception e) {
            log.debug("Silent source restore failed (will require manual login): {}", e.getMessage());
        }
    }

    private void tryRestoreSender() {
        // Try Microsoft 365 first
        try {
            OAuth2Helper oauth = new OAuth2Helper(new CredentialStore());
            var result = oauth.tryRestoreSession(EmailAccount.AccountType.SENDER, OAuth2Helper.SMTP_SCOPES);
            if (result != null) {
                AppConfig cfg = AppConfig.getInstance();
                EmailAccount account = EmailAccount.builder()
                        .accountType(EmailAccount.AccountType.SENDER)
                        .accessToken(result.accessToken())
                        .tokenExpiresAt(result.expiresOnDate().toInstant()
                                .atZone(ZoneId.systemDefault()).toLocalDateTime())
                        .emailAddress(result.account().username())
                        .tenantId(cfg.getAzureTenantId())
                        .clientId(cfg.getAzureClientId())
                        .build();
                senderAccount = account;
                AppContext.get().setSenderSmtpConfig(SmtpConfig.office365());
                Platform.runLater(() -> {
                    senderProviderChoice.setValue(PROVIDER_M365);
                    markSenderConnected(account.getEmailAddress());
                    maybeInitBackend();
                });
                log.info("Sender account (M365) restored silently: {}", account.getEmailAddress());
                return;
            }
        } catch (Exception e) {
            log.debug("Silent M365 sender restore failed: {}", e.getMessage());
        }

        // Try Gmail refresh token
        try {
            AppConfig cfg = AppConfig.getInstance();
            String clientId     = cfg.getGoogleClientId();
            String clientSecret = cfg.getGoogleClientSecret();
            if (clientId.isBlank() || clientSecret.isBlank()) return;

            GoogleOAuth2Helper googleOAuth = new GoogleOAuth2Helper(new CredentialStore(), clientId, clientSecret);
            GoogleOAuth2Helper.TokenResult tokenResult = googleOAuth.refreshAccessToken();
            if (tokenResult != null) {
                EmailAccount account = buildGmailAccount(tokenResult);
                senderAccount = account;
                AppContext.get().setSenderSmtpConfig(SmtpConfig.gmailOAuth2());
                Platform.runLater(() -> {
                    senderProviderChoice.setValue(PROVIDER_GMAIL);
                    markSenderConnected(account.getEmailAddress());
                    maybeInitBackend();
                });
                log.info("Gmail sender account restored silently: {}", account.getEmailAddress());
            }
        } catch (Exception e) {
            log.debug("Silent Gmail sender restore failed: {}", e.getMessage());
        }
    }

    // ── FXML action handlers ─────────────────────────────────────────────────

    @FXML
    private void onConnectSource() {
        sourceStatusLabel.setText("Connecting\u2026");
        sourceStatusLabel.getStyleClass().removeAll("status-connected", "status-disconnected");
        btnConnectSource.setDisable(true);

        Task<EmailAccount> task = new Task<>() {
            @Override
            protected EmailAccount call() throws Exception {
                AppConfig cfg = AppConfig.getInstance();
                OAuth2Helper oauth = new OAuth2Helper(new CredentialStore());
                var result = oauth.authenticate(EmailAccount.AccountType.SOURCE, OAuth2Helper.GRAPH_SCOPES);
                return EmailAccount.builder()
                        .accountType(EmailAccount.AccountType.SOURCE)
                        .accessToken(result.accessToken())
                        .tokenExpiresAt(result.expiresOnDate().toInstant()
                                .atZone(ZoneId.systemDefault()).toLocalDateTime())
                        .emailAddress(result.account().username())
                        .tenantId(cfg.getAzureTenantId())
                        .clientId(cfg.getAzureClientId())
                        .build();
            }
        };

        task.setOnSucceeded(e -> {
            sourceAccount = task.getValue();
            markSourceConnected(sourceAccount.getEmailAddress());
            AppContext.get().initGraphApi(sourceAccount);
            maybeInitBackend();
        });
        task.setOnFailed(e -> {
            log.error("Source account authentication failed", task.getException());
            markSourceFailed();
        });
        new Thread(task, "oauth-source").start();
    }

    @FXML
    private void onConnectSender() {
        String provider = senderProviderChoice.getValue();
        senderStatusLabel.setText("Connecting\u2026");
        senderStatusLabel.getStyleClass().removeAll("status-connected", "status-disconnected");
        btnConnectSender.setDisable(true);

        if (PROVIDER_GMAIL.equals(provider)) {
            connectGmailSender();
        } else {
            connectM365Sender();
        }
    }

    private void connectM365Sender() {
        Task<EmailAccount> task = new Task<>() {
            @Override
            protected EmailAccount call() throws Exception {
                AppConfig cfg = AppConfig.getInstance();
                OAuth2Helper oauth = new OAuth2Helper(new CredentialStore());
                var result = oauth.authenticate(EmailAccount.AccountType.SENDER, OAuth2Helper.SMTP_SCOPES);
                return EmailAccount.builder()
                        .accountType(EmailAccount.AccountType.SENDER)
                        .accessToken(result.accessToken())
                        .tokenExpiresAt(result.expiresOnDate().toInstant()
                                .atZone(ZoneId.systemDefault()).toLocalDateTime())
                        .emailAddress(result.account().username())
                        .tenantId(cfg.getAzureTenantId())
                        .clientId(cfg.getAzureClientId())
                        .build();
            }
        };

        task.setOnSucceeded(e -> {
            senderAccount = task.getValue();
            AppContext.get().setSenderSmtpConfig(SmtpConfig.office365());
            markSenderConnected(senderAccount.getEmailAddress());
            maybeInitBackend();
        });
        task.setOnFailed(e -> {
            log.error("Sender account (M365) authentication failed", task.getException());
            markSenderFailed();
        });
        new Thread(task, "oauth-sender").start();
    }

    private void connectGmailSender() {
        Task<EmailAccount> task = new Task<>() {
            @Override
            protected EmailAccount call() throws Exception {
                AppConfig cfg = AppConfig.getInstance();
                String clientId     = cfg.getGoogleClientId();
                String clientSecret = cfg.getGoogleClientSecret();
                if (clientId.isBlank() || clientSecret.isBlank()) {
                    throw new IllegalStateException(
                        "Google Client ID and Secret not set. Add them in Settings > Google Client ID/Secret.");
                }
                GoogleOAuth2Helper googleOAuth =
                        new GoogleOAuth2Helper(new CredentialStore(), clientId, clientSecret);
                GoogleOAuth2Helper.TokenResult result = googleOAuth.authenticate();
                return buildGmailAccount(result);
            }
        };

        task.setOnSucceeded(e -> {
            senderAccount = task.getValue();
            AppContext.get().setSenderSmtpConfig(SmtpConfig.gmailOAuth2());
            markSenderConnected(senderAccount.getEmailAddress());
            maybeInitBackend();
        });
        task.setOnFailed(e -> {
            log.error("Gmail sender authentication failed", task.getException());
            markSenderFailed();
        });
        new Thread(task, "oauth-gmail-sender").start();
    }

    // ── Public state helpers ──────────────────────────────────────────────────

    public void markSourceConnected(String email) {
        sourceStatusLabel.setText("Connected");
        sourceStatusLabel.getStyleClass().removeAll("status-disconnected");
        if (!sourceStatusLabel.getStyleClass().contains("status-connected"))
            sourceStatusLabel.getStyleClass().add("status-connected");
        sourceEmailLabel.setText(email);
        btnConnectSource.setText("Reconnect Source Account");
        btnConnectSource.setDisable(false);
    }

    public void markSourceFailed() {
        sourceStatusLabel.setText("Connection failed");
        sourceStatusLabel.getStyleClass().removeAll("status-connected");
        if (!sourceStatusLabel.getStyleClass().contains("status-disconnected"))
            sourceStatusLabel.getStyleClass().add("status-disconnected");
        btnConnectSource.setDisable(false);
    }

    public void markSenderConnected(String email) {
        senderStatusLabel.setText("Connected");
        senderStatusLabel.getStyleClass().removeAll("status-disconnected");
        if (!senderStatusLabel.getStyleClass().contains("status-connected"))
            senderStatusLabel.getStyleClass().add("status-connected");
        senderEmailLabel.setText(email);
        btnConnectSender.setText("Reconnect Sender Account");
        btnConnectSender.setDisable(false);
    }

    public void markSenderFailed() {
        senderStatusLabel.setText("Connection failed");
        senderStatusLabel.getStyleClass().removeAll("status-connected");
        if (!senderStatusLabel.getStyleClass().contains("status-disconnected"))
            senderStatusLabel.getStyleClass().add("status-disconnected");
        btnConnectSender.setDisable(false);
    }

    public boolean isSourceConnected() {
        return "Connected".equals(sourceStatusLabel.getText());
    }

    public boolean isSenderConnected() {
        return "Connected".equals(senderStatusLabel.getText());
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private static EmailAccount buildGmailAccount(GoogleOAuth2Helper.TokenResult t) {
        LocalDateTime expiresAt = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(t.expiresAtEpochSeconds()), ZoneId.systemDefault());
        return EmailAccount.builder()
                .accountType(EmailAccount.AccountType.SENDER)
                .accessToken(t.accessToken())
                .tokenExpiresAt(expiresAt)
                .emailAddress(t.email() != null ? t.email() : "gmail-user")
                .tenantId("")
                .clientId(AppConfig.getInstance().getGoogleClientId())
                .build();
    }

    private void maybeInitBackend() {
        if (sourceAccount != null && senderAccount != null) {
            try {
                AppContext.get().initBackend(sourceAccount, senderAccount);
                log.info("Backend initialised: source={} sender={}",
                        sourceAccount.getEmailAddress(), senderAccount.getEmailAddress());
            } catch (Exception ex) {
                log.error("Backend initialisation failed", ex);
                Platform.runLater(() -> {
                    sourceStatusLabel.setText("Backend init failed \u2014 see logs");
                    sourceStatusLabel.getStyleClass().add("status-disconnected");
                });
            }
        }
    }
}
