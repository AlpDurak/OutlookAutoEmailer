package com.outlookautoemailier.ui;

import com.outlookautoemailier.AppContext;
import com.outlookautoemailier.model.EmailJob;
import com.outlookautoemailier.queue.EmailQueue;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for {@code DeadLetterExplorer.fxml}.
 *
 * <p>Displays email jobs that exhausted all retry attempts (DEAD_LETTER status)
 * and provides controls to inspect, filter, retry, export, and delete them.</p>
 */
public class DeadLetterExplorerController implements Initializable {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterExplorerController.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // -- Stat labels --
    @FXML private Label totalDeadLabel;
    @FXML private Label topReasonLabel;

    // -- Filter controls --
    @FXML private TextField searchField;
    @FXML private ComboBox<String> reasonFilter;

    // -- Table --
    @FXML private TableView<DeadLetterRow> deadLetterTable;
    @FXML private TableColumn<DeadLetterRow, String> recipientColumn;
    @FXML private TableColumn<DeadLetterRow, String> domainColumn;
    @FXML private TableColumn<DeadLetterRow, String> subjectColumn;
    @FXML private TableColumn<DeadLetterRow, String> failureReasonColumn;
    @FXML private TableColumn<DeadLetterRow, String> attemptsColumn;
    @FXML private TableColumn<DeadLetterRow, String> timestampColumn;

    // -- Failure breakdown --
    @FXML private VBox failureBreakdownBox;

    // -- Internal state --
    private final ObservableList<DeadLetterRow> tableRows = FXCollections.observableArrayList();
    private List<EmailJob> currentDeadLetterJobs = Collections.emptyList();
    private List<DeadLetterRow> allRows = Collections.emptyList();
    private Timeline pollingTimeline;

    // -- View model --

    /**
     * Flat, string-only row model for the dead-letter table.
     * Retains a reference to the source {@link EmailJob} for retry/delete operations.
     */
    public static final class DeadLetterRow {
        private final EmailJob sourceJob;
        private final String recipient;
        private final String domain;
        private final String subject;
        private final String failureReason;
        private final String failureCategory;
        private final int attempts;
        private final String timestamp;

        public DeadLetterRow(EmailJob job) {
            this.sourceJob = job;
            this.recipient = job.getContact().getPrimaryEmail();
            this.domain = extractDomain(this.recipient);
            this.subject = job.getTemplate() != null ? job.getTemplate().getSubject() : "";
            this.failureReason = job.getErrorMessage() != null ? job.getErrorMessage() : "Unknown";
            this.failureCategory = categorizeFailure(this.failureReason);
            this.attempts = job.getAttemptCount();
            this.timestamp = job.getLastAttemptAt() != null
                    ? job.getLastAttemptAt().format(TIME_FMT)
                    : (job.getCreatedAt() != null ? job.getCreatedAt().format(TIME_FMT) : "--");
        }

        public EmailJob getSourceJob()       { return sourceJob; }
        public String getRecipient()         { return recipient; }
        public String getDomain()            { return domain; }
        public String getSubject()           { return subject; }
        public String getFailureReason()     { return failureReason; }
        public String getFailureCategory()   { return failureCategory; }
        public int getAttempts()             { return attempts; }
        public String getTimestamp()          { return timestamp; }
    }

    // -- Initializable --

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        AppContext.get().setDeadLetterExplorerController(this);

        configureColumns();
        deadLetterTable.setItems(tableRows);
        deadLetterTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // Bind filter listeners
        searchField.textProperty().addListener((obs, oldVal, newVal) -> applyFilters());
        reasonFilter.setOnAction(e -> applyFilters());

        startPolling();
    }

    private void configureColumns() {
        recipientColumn.setCellValueFactory(
                cd -> new SimpleStringProperty(cd.getValue().getRecipient()));
        domainColumn.setCellValueFactory(
                cd -> new SimpleStringProperty(cd.getValue().getDomain()));
        subjectColumn.setCellValueFactory(
                cd -> new SimpleStringProperty(cd.getValue().getSubject()));
        failureReasonColumn.setCellValueFactory(
                cd -> new SimpleStringProperty(cd.getValue().getFailureReason()));
        attemptsColumn.setCellValueFactory(
                cd -> new SimpleStringProperty(String.valueOf(cd.getValue().getAttempts())));
        timestampColumn.setCellValueFactory(
                cd -> new SimpleStringProperty(cd.getValue().getTimestamp()));

        // Red text for failure reason column
        failureReasonColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    getStyleClass().remove("dl-failure-cell");
                } else {
                    setText(item);
                    if (!getStyleClass().contains("dl-failure-cell")) {
                        getStyleClass().add("dl-failure-cell");
                    }
                }
            }
        });
    }

    // -- Polling --

    /**
     * Starts a 2-second polling timeline that refreshes dead-letter data.
     */
    public void startPolling() {
        if (pollingTimeline != null) {
            pollingTimeline.stop();
        }
        pollingTimeline = new Timeline(
                new KeyFrame(Duration.seconds(2), e -> refresh())
        );
        pollingTimeline.setCycleCount(Timeline.INDEFINITE);
        pollingTimeline.play();
    }

    /** Stops the polling timeline. */
    public void stopPolling() {
        if (pollingTimeline != null) {
            pollingTimeline.stop();
        }
    }

    /**
     * Refreshes the dead-letter data from the queue and updates all UI elements.
     */
    public void refresh() {
        EmailQueue q = AppContext.get().getEmailQueue();
        if (q == null) {
            totalDeadLabel.setText("0");
            topReasonLabel.setText("Queue not initialised");
            return;
        }

        currentDeadLetterJobs = q.getDeadLetterJobs();
        allRows = currentDeadLetterJobs.stream()
                .map(DeadLetterRow::new)
                .collect(Collectors.toList());

        // Update stat labels
        totalDeadLabel.setText(String.valueOf(allRows.size()));

        // Compute failure categories and update breakdown + top reason
        Map<String, Long> categoryCounts = allRows.stream()
                .collect(Collectors.groupingBy(DeadLetterRow::getFailureCategory, Collectors.counting()));

        if (categoryCounts.isEmpty()) {
            topReasonLabel.setText("--");
        } else {
            String topCategory = categoryCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("--");
            topReasonLabel.setText(topCategory);
        }

        // Update reason filter combo (preserve selection)
        String currentSelection = reasonFilter.getValue();
        List<String> categories = new ArrayList<>();
        categories.add("All");
        categories.addAll(categoryCounts.keySet().stream().sorted().toList());
        reasonFilter.setItems(FXCollections.observableArrayList(categories));
        if (currentSelection != null && categories.contains(currentSelection)) {
            reasonFilter.setValue(currentSelection);
        }

        // Update failure breakdown box
        updateFailureBreakdown(categoryCounts);

        // Apply current filters
        applyFilters();
    }

    private void updateFailureBreakdown(Map<String, Long> categoryCounts) {
        failureBreakdownBox.getChildren().clear();
        if (categoryCounts.isEmpty()) {
            Label empty = new Label("No dead-letter jobs.");
            empty.getStyleClass().add("help-label");
            failureBreakdownBox.getChildren().add(empty);
            return;
        }

        // Sort by count descending
        categoryCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(entry -> {
                    Label label = new Label(entry.getKey() + ": " + entry.getValue());
                    label.getStyleClass().add("dl-breakdown-label");
                    failureBreakdownBox.getChildren().add(label);
                });
    }

    // -- Filtering --

    private void applyFilters() {
        String searchText = searchField.getText();
        String selectedReason = reasonFilter.getValue();

        List<DeadLetterRow> filtered = allRows.stream()
                .filter(row -> {
                    // Search filter
                    if (searchText != null && !searchText.isBlank()) {
                        String lower = searchText.toLowerCase();
                        return row.getRecipient().toLowerCase().contains(lower)
                                || row.getDomain().toLowerCase().contains(lower);
                    }
                    return true;
                })
                .filter(row -> {
                    // Reason category filter
                    if (selectedReason == null || "All".equals(selectedReason)) {
                        return true;
                    }
                    return row.getFailureCategory().equals(selectedReason);
                })
                .collect(Collectors.toList());

        tableRows.setAll(filtered);
    }

    // -- FXML action handlers --

    @FXML
    private void onClearFilters() {
        searchField.clear();
        reasonFilter.setValue(null);
        applyFilters();
    }

    @FXML
    private void onRetrySelected() {
        List<DeadLetterRow> selected = new ArrayList<>(deadLetterTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            showInfo("No Selection", "Select one or more dead-letter jobs to retry.");
            return;
        }

        EmailQueue q = AppContext.get().getEmailQueue();
        if (q == null) {
            showError("Queue not available.");
            return;
        }

        List<EmailJob> jobsToRetry = selected.stream()
                .map(DeadLetterRow::getSourceJob)
                .collect(Collectors.toList());
        q.retryDeadLetterJobs(jobsToRetry);
        log.info("Retried {} selected dead-letter job(s).", jobsToRetry.size());
        refresh();
    }

    @FXML
    private void onRetryAll() {
        List<DeadLetterRow> visible = new ArrayList<>(tableRows);
        if (visible.isEmpty()) {
            showInfo("Nothing to Retry", "No dead-letter jobs match the current filters.");
            return;
        }

        EmailQueue q = AppContext.get().getEmailQueue();
        if (q == null) {
            showError("Queue not available.");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Retry All Visible");
        confirm.setHeaderText(null);
        confirm.setContentText("Re-enqueue " + visible.size() + " dead-letter job(s) for retry?");
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                List<EmailJob> jobsToRetry = visible.stream()
                        .map(DeadLetterRow::getSourceJob)
                        .collect(Collectors.toList());
                q.retryDeadLetterJobs(jobsToRetry);
                log.info("Retried all {} visible dead-letter job(s).", jobsToRetry.size());
                refresh();
            }
        });
    }

    @FXML
    private void onExportCsv() {
        List<DeadLetterRow> rows = new ArrayList<>(tableRows);
        if (rows.isEmpty()) {
            showInfo("Nothing to Export", "No dead-letter jobs to export.");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Dead-Letter Jobs to CSV");
        fileChooser.setInitialFileName("dead-letter-export.csv");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV Files", "*.csv"));

        File file = fileChooser.showSaveDialog(deadLetterTable.getScene().getWindow());
        if (file == null) {
            return;
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
            pw.println("Recipient,Domain,Subject,Failure Reason,Attempts,Timestamp");
            for (DeadLetterRow row : rows) {
                pw.printf("\"%s\",\"%s\",\"%s\",\"%s\",%d,\"%s\"%n",
                        escapeCsv(row.getRecipient()),
                        escapeCsv(row.getDomain()),
                        escapeCsv(row.getSubject()),
                        escapeCsv(row.getFailureReason()),
                        row.getAttempts(),
                        escapeCsv(row.getTimestamp()));
            }
            log.info("Exported {} dead-letter rows to {}", rows.size(), file.getAbsolutePath());
            showInfo("Export Complete", "Exported " + rows.size() + " row(s) to:\n" + file.getAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to export CSV.", e);
            showError("Failed to export CSV: " + e.getMessage());
        }
    }

    @FXML
    private void onDeleteSelected() {
        List<DeadLetterRow> selected = new ArrayList<>(deadLetterTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            showInfo("No Selection", "Select one or more dead-letter jobs to delete.");
            return;
        }

        EmailQueue q = AppContext.get().getEmailQueue();
        if (q == null) {
            showError("Queue not available.");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Selected");
        confirm.setHeaderText(null);
        confirm.setContentText("Permanently remove " + selected.size() + " dead-letter job(s)?\nThis cannot be undone.");
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                List<EmailJob> jobsToDelete = selected.stream()
                        .map(DeadLetterRow::getSourceJob)
                        .collect(Collectors.toList());
                q.removeDeadLetterJobs(jobsToDelete);
                log.info("Deleted {} dead-letter job(s).", jobsToDelete.size());
                refresh();
            }
        });
    }

    // -- Helpers --

    /**
     * Extracts the domain portion from an email address.
     */
    private static String extractDomain(String email) {
        if (email == null || !email.contains("@")) {
            return "";
        }
        return email.substring(email.indexOf('@') + 1).toLowerCase();
    }

    /**
     * Categorizes a failure reason string into a human-readable category.
     */
    private static String categorizeFailure(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Unknown";
        }
        String lower = reason.toLowerCase();
        if (lower.contains("auth") || lower.contains("oauth") || lower.contains("credential")
                || lower.contains("xoauth2") || lower.contains("login")) {
            return "Authentication";
        }
        if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("connect")) {
            return "Connection/Timeout";
        }
        if (lower.contains("rate") || lower.contains("throttl") || lower.contains("too many")) {
            return "Rate Limit";
        }
        if (lower.contains("bounce") || lower.contains("mailbox") || lower.contains("recipient")
                || lower.contains("user unknown") || lower.contains("550") || lower.contains("553")) {
            return "Invalid Recipient";
        }
        if (lower.contains("spam") || lower.contains("spf") || lower.contains("dkim")
                || lower.contains("reject") || lower.contains("block")) {
            return "Spam/Blocked";
        }
        if (lower.contains("suppression") || lower.contains("unsubscri")) {
            return "Suppressed";
        }
        if (lower.contains("invalid email") || lower.contains("validation")) {
            return "Invalid Address";
        }
        if (lower.contains("interrupt")) {
            return "Interrupted";
        }
        return "Other";
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        return value.replace("\"", "\"\"");
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
