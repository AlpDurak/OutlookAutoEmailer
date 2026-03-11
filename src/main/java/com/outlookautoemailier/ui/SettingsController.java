package com.outlookautoemailier.ui;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.ResourceBundle;

/**
 * Controller for {@code Settings.fxml}.
 *
 * <p>Persists application settings to
 * {@code <user.home>/.outlookautoemailier/application.properties} and
 * reloads them on startup.
 *
 * <h3>Default values</h3>
 * <table border="1">
 *   <tr><th>Key</th><th>Default</th></tr>
 *   <tr><td>emailsPerHour</td><td>100</td></tr>
 *   <tr><td>minDelay</td><td>3000</td></tr>
 *   <tr><td>maxDelay</td><td>8000</td></tr>
 *   <tr><td>smtpHost</td><td>smtp.office365.com</td></tr>
 *   <tr><td>smtpPort</td><td>587</td></tr>
 *   <tr><td>maxRetry</td><td>3</td></tr>
 * </table>
 */
public class SettingsController implements Initializable {

    // ── FXML controls ────────────────────────────────────────────────────────
    @FXML private Spinner<Integer> emailsPerHourSpinner;
    @FXML private Spinner<Integer> minDelaySpinner;
    @FXML private Spinner<Integer> maxDelaySpinner;
    @FXML private TextField        smtpHostField;
    @FXML private Spinner<Integer> smtpPortSpinner;
    @FXML private Spinner<Integer> maxRetrySpinner;
    @FXML private Label            saveStatusLabel;

    // ── Defaults ─────────────────────────────────────────────────────────────
    private static final int    DEFAULT_EMAILS_PER_HOUR = 100;
    private static final int    DEFAULT_MIN_DELAY       = 3000;
    private static final int    DEFAULT_MAX_DELAY       = 8000;
    private static final String DEFAULT_SMTP_HOST       = "smtp.office365.com";
    private static final int    DEFAULT_SMTP_PORT       = 587;
    private static final int    DEFAULT_MAX_RETRY       = 3;

    /** Path to the persisted properties file. */
    private static final Path PROPS_PATH = Paths.get(
            System.getProperty("user.home"),
            ".outlookautoemailier",
            "application.properties"
    );

    // ── Initializable ────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        configureSpinnerFactories();
        loadFromProperties();
    }

    /** Assigns integer {@link SpinnerValueFactory} instances to all spinners. */
    private void configureSpinnerFactories() {
        emailsPerHourSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 500, DEFAULT_EMAILS_PER_HOUR));
        minDelaySpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(500, 30_000, DEFAULT_MIN_DELAY));
        maxDelaySpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1_000, 60_000, DEFAULT_MAX_DELAY));
        smtpPortSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65_535, DEFAULT_SMTP_PORT));
        maxRetrySpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 10, DEFAULT_MAX_RETRY));

        // Make editable spinners commit on focus loss
        for (Spinner<?> s : new Spinner<?>[] {
                emailsPerHourSpinner, minDelaySpinner, maxDelaySpinner,
                smtpPortSpinner, maxRetrySpinner }) {
            s.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                if (!isNowFocused) {
                    @SuppressWarnings("unchecked")
                    Spinner<Integer> si = (Spinner<Integer>) s;
                    si.increment(0); // forces the editor text to be parsed and committed
                }
            });
        }
    }

    // ── FXML action handlers ─────────────────────────────────────────────────

    /**
     * Writes current control values to {@link #PROPS_PATH}.
     *
     * <p>Creates the parent directory if it does not exist.
     */
    @FXML
    private void onSave() {
        // Validate min/max delay ordering
        int minDelay = spinnerValue(minDelaySpinner, DEFAULT_MIN_DELAY);
        int maxDelay = spinnerValue(maxDelaySpinner, DEFAULT_MAX_DELAY);
        if (minDelay >= maxDelay) {
            saveStatusLabel.setText("Min delay must be less than max delay.");
            saveStatusLabel.getStyleClass().removeAll("status-connected");
            if (!saveStatusLabel.getStyleClass().contains("status-disconnected")) {
                saveStatusLabel.getStyleClass().add("status-disconnected");
            }
            return;
        }

        Properties props = new Properties();
        props.setProperty("rate.limit.emails.per.hour", String.valueOf(spinnerValue(emailsPerHourSpinner, DEFAULT_EMAILS_PER_HOUR)));
        props.setProperty("rate.limit.delay.min.ms",    String.valueOf(minDelay));
        props.setProperty("rate.limit.delay.max.ms",    String.valueOf(maxDelay));
        props.setProperty("smtp.host",                  smtpHostField.getText().trim().isEmpty()
                                            ? DEFAULT_SMTP_HOST
                                            : smtpHostField.getText().trim());
        props.setProperty("smtp.port",                  String.valueOf(spinnerValue(smtpPortSpinner, DEFAULT_SMTP_PORT)));
        props.setProperty("retry.max.attempts",         String.valueOf(spinnerValue(maxRetrySpinner, DEFAULT_MAX_RETRY)));

        try {
            Files.createDirectories(PROPS_PATH.getParent());
            try (OutputStream out = Files.newOutputStream(PROPS_PATH)) {
                props.store(out, "OutlookAutoEmailer settings");
            }
            saveStatusLabel.setText("Settings saved successfully.");
            saveStatusLabel.getStyleClass().removeAll("status-disconnected");
            if (!saveStatusLabel.getStyleClass().contains("status-connected")) {
                saveStatusLabel.getStyleClass().add("status-connected");
            }
            // Reload AppConfig so live components pick up new values
            com.outlookautoemailier.config.AppConfig.reload();
        } catch (IOException ex) {
            saveStatusLabel.setText("Failed to save settings: " + ex.getMessage());
            saveStatusLabel.getStyleClass().removeAll("status-connected");
            if (!saveStatusLabel.getStyleClass().contains("status-disconnected")) {
                saveStatusLabel.getStyleClass().add("status-disconnected");
            }
        }
    }

    /**
     * Resets all controls to their hard-coded default values.
     * Does not write to disk — the user must click "Save Settings".
     */
    @FXML
    private void onReset() {
        emailsPerHourSpinner.getValueFactory().setValue(DEFAULT_EMAILS_PER_HOUR);
        minDelaySpinner.getValueFactory().setValue(DEFAULT_MIN_DELAY);
        maxDelaySpinner.getValueFactory().setValue(DEFAULT_MAX_DELAY);
        smtpHostField.setText(DEFAULT_SMTP_HOST);
        smtpPortSpinner.getValueFactory().setValue(DEFAULT_SMTP_PORT);
        maxRetrySpinner.getValueFactory().setValue(DEFAULT_MAX_RETRY);
        saveStatusLabel.setText("Defaults restored. Click Save to persist.");
        saveStatusLabel.getStyleClass().removeAll("status-connected", "status-disconnected");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Attempts to load settings from {@link #PROPS_PATH}.
     * Silently uses default values if the file does not exist or is malformed.
     */
    private void loadFromProperties() {
        if (!Files.exists(PROPS_PATH)) return;

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(PROPS_PATH)) {
            props.load(in);
        } catch (IOException ex) {
            // File unreadable — defaults remain from configureSpinnerFactories()
            return;
        }

        setSpinner(emailsPerHourSpinner, props, "rate.limit.emails.per.hour", DEFAULT_EMAILS_PER_HOUR);
        setSpinner(minDelaySpinner,      props, "rate.limit.delay.min.ms",    DEFAULT_MIN_DELAY);
        setSpinner(maxDelaySpinner,      props, "rate.limit.delay.max.ms",    DEFAULT_MAX_DELAY);
        setSpinner(smtpPortSpinner,      props, "smtp.port",                  DEFAULT_SMTP_PORT);
        setSpinner(maxRetrySpinner,      props, "retry.max.attempts",         DEFAULT_MAX_RETRY);

        String host = props.getProperty("smtp.host", DEFAULT_SMTP_HOST);
        smtpHostField.setText(host.isBlank() ? DEFAULT_SMTP_HOST : host);
    }

    private static void setSpinner(Spinner<Integer> spinner, Properties props,
                                   String key, int fallback) {
        try {
            int value = Integer.parseInt(props.getProperty(key, String.valueOf(fallback)));
            SpinnerValueFactory.IntegerSpinnerValueFactory factory =
                    (SpinnerValueFactory.IntegerSpinnerValueFactory) spinner.getValueFactory();
            // Clamp to the factory's configured bounds
            value = Math.max(factory.getMin(), Math.min(factory.getMax(), value));
            factory.setValue(value);
        } catch (NumberFormatException ignored) {
            // Leave the spinner at its default
        }
    }

    /**
     * Returns the spinner's committed integer value, falling back to
     * {@code defaultValue} if the editor contains unparseable text.
     */
    private static int spinnerValue(Spinner<Integer> spinner, int defaultValue) {
        try {
            return spinner.getValue() != null ? spinner.getValue() : defaultValue;
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    // ── Public accessors (used by other components) ──────────────────────────

    public int  getEmailsPerHour() { return spinnerValue(emailsPerHourSpinner, DEFAULT_EMAILS_PER_HOUR); }
    public int  getMinDelayMs()    { return spinnerValue(minDelaySpinner,      DEFAULT_MIN_DELAY); }
    public int  getMaxDelayMs()    { return spinnerValue(maxDelaySpinner,      DEFAULT_MAX_DELAY); }
    public String getSmtpHost()    { return smtpHostField.getText().trim(); }
    public int  getSmtpPort()      { return spinnerValue(smtpPortSpinner,      DEFAULT_SMTP_PORT); }
    public int  getMaxRetry()      { return spinnerValue(maxRetrySpinner,      DEFAULT_MAX_RETRY); }
}
