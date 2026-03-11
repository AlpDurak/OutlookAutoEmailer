package com.outlookautoemailier.ui;

import com.outlookautoemailier.AppContext;
import com.outlookautoemailier.config.AppConfig;
import com.outlookautoemailier.security.OAuth2Helper;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.time.ZoneId;
import java.util.ResourceBundle;

/**
 * Controller for {@code AccountSetup.fxml}.
 *
 * <p>Manages two Microsoft account connections:
 * <ul>
 *   <li><b>Source</b> – used to read Outlook contacts via the Microsoft Graph API.</li>
 *   <li><b>Sender</b> – used to send emails (SMTP / Graph Mail.Send scope).</li>
 * </ul>
 */
public class AccountSetupController implements Initializable {

    private static final Logger log = LoggerFactory.getLogger(AccountSetupController.class);

    // ── Source account controls ──────────────────────────────────────────────
    @FXML private Label  sourceStatusLabel;
    @FXML private Label  sourceEmailLabel;
    @FXML private Button btnConnectSource;

    // ── Sender account controls ──────────────────────────────────────────────
    @FXML private Label  senderStatusLabel;
    @FXML private Label  senderEmailLabel;
    @FXML private Button btnConnectSender;

    /** Stored after successful SOURCE auth — passed to AppContext.initBackend(). */
    private com.outlookautoemailier.model.EmailAccount sourceAccount;
    /** Stored after successful SENDER auth — passed to AppContext.initBackend(). */
    private com.outlookautoemailier.model.EmailAccount senderAccount;

    // ── Initializable ────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        AppContext.get().setAccountSetupController(this);
    }

    // ── FXML action handlers ─────────────────────────────────────────────────

    @FXML
    private void onConnectSource() {
        sourceStatusLabel.setText("Connecting\u2026");
        sourceStatusLabel.getStyleClass().removeAll("status-connected", "status-disconnected");
        btnConnectSource.setDisable(true);

        Task<com.outlookautoemailier.model.EmailAccount> task = new Task<>() {
            @Override
            protected com.outlookautoemailier.model.EmailAccount call() throws Exception {
                AppConfig cfg = AppConfig.getInstance();
                OAuth2Helper oauth = new OAuth2Helper(
                        new com.outlookautoemailier.security.CredentialStore()
                );
                com.microsoft.aad.msal4j.IAuthenticationResult result =
                        oauth.authenticate(com.outlookautoemailier.model.EmailAccount.AccountType.SOURCE,
                                OAuth2Helper.GRAPH_SCOPES);

                return com.outlookautoemailier.model.EmailAccount.builder()
                        .accountType(com.outlookautoemailier.model.EmailAccount.AccountType.SOURCE)
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
        senderStatusLabel.setText("Connecting\u2026");
        senderStatusLabel.getStyleClass().removeAll("status-connected", "status-disconnected");
        btnConnectSender.setDisable(true);

        Task<com.outlookautoemailier.model.EmailAccount> task = new Task<>() {
            @Override
            protected com.outlookautoemailier.model.EmailAccount call() throws Exception {
                AppConfig cfg = AppConfig.getInstance();
                OAuth2Helper oauth = new OAuth2Helper(
                        new com.outlookautoemailier.security.CredentialStore()
                );
                com.microsoft.aad.msal4j.IAuthenticationResult result =
                        oauth.authenticate(com.outlookautoemailier.model.EmailAccount.AccountType.SENDER,
                                OAuth2Helper.SMTP_SCOPES);

                return com.outlookautoemailier.model.EmailAccount.builder()
                        .accountType(com.outlookautoemailier.model.EmailAccount.AccountType.SENDER)
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
            markSenderConnected(senderAccount.getEmailAddress());
            maybeInitBackend();
        });
        task.setOnFailed(e -> {
            log.error("Sender account authentication failed", task.getException());
            markSenderFailed();
        });

        new Thread(task, "oauth-sender").start();
    }

    // ── Public state helpers (called by OAuth callbacks) ─────────────────────

    /**
     * Updates source account UI to reflect a successful connection.
     *
     * @param email the authenticated account email address
     */
    public void markSourceConnected(String email) {
        sourceStatusLabel.setText("Connected");
        sourceStatusLabel.getStyleClass().removeAll("status-disconnected");
        if (!sourceStatusLabel.getStyleClass().contains("status-connected")) {
            sourceStatusLabel.getStyleClass().add("status-connected");
        }
        sourceEmailLabel.setText(email);
        btnConnectSource.setText("Reconnect Source Account");
        btnConnectSource.setDisable(false);
    }

    /**
     * Resets source account UI after an authentication failure.
     */
    public void markSourceFailed() {
        sourceStatusLabel.setText("Connection failed");
        sourceStatusLabel.getStyleClass().removeAll("status-connected");
        if (!sourceStatusLabel.getStyleClass().contains("status-disconnected")) {
            sourceStatusLabel.getStyleClass().add("status-disconnected");
        }
        btnConnectSource.setDisable(false);
    }

    /**
     * Updates sender account UI to reflect a successful connection.
     *
     * @param email the authenticated account email address
     */
    public void markSenderConnected(String email) {
        senderStatusLabel.setText("Connected");
        senderStatusLabel.getStyleClass().removeAll("status-disconnected");
        if (!senderStatusLabel.getStyleClass().contains("status-connected")) {
            senderStatusLabel.getStyleClass().add("status-connected");
        }
        senderEmailLabel.setText(email);
        btnConnectSender.setText("Reconnect Sender Account");
        btnConnectSender.setDisable(false);
    }

    /**
     * Resets sender account UI after an authentication failure.
     */
    public void markSenderFailed() {
        senderStatusLabel.setText("Connection failed");
        senderStatusLabel.getStyleClass().removeAll("status-connected");
        if (!senderStatusLabel.getStyleClass().contains("status-disconnected")) {
            senderStatusLabel.getStyleClass().add("status-disconnected");
        }
        btnConnectSender.setDisable(false);
    }

    // ── State queries (used by other controllers for guard checks) ───────────

    /** @return {@code true} if the source account has been successfully connected. */
    public boolean isSourceConnected() {
        return "Connected".equals(sourceStatusLabel.getText());
    }

    /** @return {@code true} if the sender account has been successfully connected. */
    public boolean isSenderConnected() {
        return "Connected".equals(senderStatusLabel.getText());
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Called after each successful auth. Initialises the backend as soon as both
     * accounts are available. Safe to call multiple times — AppContext.initBackend()
     * is idempotent if both accounts are the same object references.
     */
    private void maybeInitBackend() {
        if (sourceAccount != null && senderAccount != null) {
            try {
                AppContext.get().initBackend(sourceAccount, senderAccount);
                log.info("Backend initialised successfully with source={} sender={}",
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
