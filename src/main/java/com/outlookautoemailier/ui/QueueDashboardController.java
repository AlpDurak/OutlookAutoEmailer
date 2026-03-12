package com.outlookautoemailier.ui;

import com.outlookautoemailier.AppContext;
import com.outlookautoemailier.queue.EmailQueue;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.util.Duration;

import java.net.URL;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

/**
 * Controller for {@code QueueDashboard.fxml}.
 *
 * <p>Displays live email-queue statistics and provides controls to pause,
 * resume, cancel, and retry failed jobs.
 *
 * <p>Sprint 2: {@link #refreshStats()} queries {@link EmailQueue} for real
 * counts and job rows; the pause/resume/cancel/retry handlers are wired to
 * {@link com.outlookautoemailier.queue.EmailDispatcher} and
 * {@link EmailQueue} via {@link AppContext}.
 */
public class QueueDashboardController implements Initializable {

    // ── Stat labels ──────────────────────────────────────────────────────────
    @FXML private Label pendingCount;
    @FXML private Label sendingCount;
    @FXML private Label sentCount;
    @FXML private Label scheduledCount;
    @FXML private Label failedCount;

    // ── Progress indicators ──────────────────────────────────────────────────
    @FXML private ProgressBar overallProgress;
    @FXML private Label       progressLabel;

    // ── Job table and columns ────────────────────────────────────────────────
    @FXML private TableView<JobRow>               jobTable;
    @FXML private TableColumn<JobRow, String>     jobIdColumn;
    @FXML private TableColumn<JobRow, String>     contactNameColumn;
    @FXML private TableColumn<JobRow, String>     statusColumn;
    @FXML private TableColumn<JobRow, String>     attemptsColumn;
    @FXML private TableColumn<JobRow, String>     scheduledAtColumn;
    @FXML private TableColumn<JobRow, String>     lastUpdatedColumn;

    // ── Control buttons ──────────────────────────────────────────────────────
    @FXML private Button btnPause;

    // ── Internal state ───────────────────────────────────────────────────────

    private final ObservableList<JobRow> jobRows = FXCollections.observableArrayList();
    private Timeline pollingTimeline;
    private boolean paused = false;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("MMM d HH:mm");

    // ── Lightweight display model ────────────────────────────────────────────

    /**
     * Flat, string-only view model used by the {@link TableView}.
     */
    public static final class JobRow {
        private final String jobId;
        private final String contactName;
        private final String status;
        private final String attempts;
        private final String scheduledAt;
        private final String lastUpdated;

        public JobRow(String jobId, String contactName, String status,
                      int attempts, String scheduledAt, String lastUpdated) {
            this.jobId       = jobId;
            this.contactName = contactName;
            this.status      = status;
            this.attempts    = String.valueOf(attempts);
            this.scheduledAt = scheduledAt;
            this.lastUpdated = lastUpdated;
        }

        public String getJobId()       { return jobId; }
        public String getContactName() { return contactName; }
        public String getStatus()      { return status; }
        public String getAttempts()    { return attempts; }
        public String getScheduledAt() { return scheduledAt; }
        public String getLastUpdated() { return lastUpdated; }
    }

    // ── Initializable ────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        configureColumns();
        jobTable.setItems(jobRows);
        startPolling();
        AppContext.get().setQueueDashboardController(this);
    }

    private void configureColumns() {
        jobIdColumn.setCellValueFactory(
                cd -> new SimpleStringProperty(cd.getValue().getJobId()));
        contactNameColumn.setCellValueFactory(
                cd -> new SimpleStringProperty(cd.getValue().getContactName()));
        statusColumn.setCellValueFactory(
                cd -> new SimpleStringProperty(cd.getValue().getStatus()));
        attemptsColumn.setCellValueFactory(
                cd -> new SimpleStringProperty(cd.getValue().getAttempts()));
        scheduledAtColumn.setCellValueFactory(
                cd -> new SimpleStringProperty(cd.getValue().getScheduledAt()));
        lastUpdatedColumn.setCellValueFactory(
                cd -> new SimpleStringProperty(cd.getValue().getLastUpdated()));

        // Purple text for SCHEDULED status cells
        statusColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    getStyleClass().removeAll("schedule-badge");
                } else {
                    setText(item);
                    if ("SCHEDULED".equals(item)) {
                        if (!getStyleClass().contains("schedule-badge")) {
                            getStyleClass().add("schedule-badge");
                        }
                    } else {
                        getStyleClass().removeAll("schedule-badge");
                    }
                }
            }
        });
    }

    // ── Polling ──────────────────────────────────────────────────────────────

    /**
     * Starts a 1-second {@link Timeline} that calls {@link #refreshStats()}
     * on every tick.  Safe to call multiple times — a running timeline is
     * stopped first.
     */
    public void startPolling() {
        if (pollingTimeline != null) {
            pollingTimeline.stop();
        }
        pollingTimeline = new Timeline(
                new KeyFrame(Duration.seconds(1), e -> refreshStats())
        );
        pollingTimeline.setCycleCount(Timeline.INDEFINITE);
        pollingTimeline.play();
    }

    /** Stops the polling timeline (e.g. when the pane is hidden). */
    public void stopPolling() {
        if (pollingTimeline != null) {
            pollingTimeline.stop();
        }
    }

    /**
     * Queries the {@link EmailQueue} for per-status counts and updates the
     * dashboard widgets.  Falls back gracefully when the queue is not yet
     * initialised.
     */
    private void refreshStats() {
        AppContext ctx = AppContext.get();
        EmailQueue q = ctx.getEmailQueue();
        if (q == null) {
            progressLabel.setText("Queue not initialised.");
            return;
        }

        int pending  = q.pendingCount();
        int sending  = q.sendingCount();
        int sent     = q.sentCount();
        int failed   = q.failedCount() + q.deadLetterCount();
        int total    = q.totalCount();

        // Count scheduled jobs from the snapshot
        long scheduled = q.getAllJobsSnapshot().stream()
                .filter(j -> j.getStatus() == com.outlookautoemailier.model.EmailJob.JobStatus.SCHEDULED)
                .count();

        pendingCount.setText(String.valueOf(pending));
        sendingCount.setText(String.valueOf(sending));
        sentCount.setText(String.valueOf(sent));
        if (scheduledCount != null) {
            scheduledCount.setText(String.valueOf(scheduled));
        }
        failedCount.setText(String.valueOf(failed));

        double progress = (total == 0) ? 0.0 : (double)(sent + failed) / total;
        overallProgress.setProgress(progress);
        progressLabel.setText(sent + " / " + total + " processed"
                + (sending > 0 ? "  ·  " + sending + " sending" : ""));

        // Update sidebar badge
        MainController mainCtrl = ctx.getMainController();
        if (mainCtrl != null) {
            mainCtrl.updateQueueBadge(pending + sending, failed);
        }

        // Refresh job table
        List<JobRow> rows = q.getAllJobsSnapshot().stream()
                .map(job -> new JobRow(
                        job.getId().toString().substring(0, 8),
                        job.getContact().getDisplayName(),
                        job.getStatus().name(),
                        job.getAttemptCount(),
                        job.getScheduledAt() != null
                                ? job.getScheduledAt().format(DATETIME_FMT)
                                : "—",
                        job.getLastAttemptAt() != null
                                ? job.getLastAttemptAt().format(TIME_FMT)
                                : "—"
                ))
                .collect(Collectors.toList());
        jobRows.setAll(rows);
    }

    // ── FXML action handlers ─────────────────────────────────────────────────

    /**
     * Pauses the email-sending worker.
     */
    @FXML
    private void onPause() {
        paused = true;
        btnPause.setDisable(true);
        progressLabel.setText("Paused");
        AppContext ctx = AppContext.get();
        if (ctx.getEmailDispatcher() != null) ctx.getEmailDispatcher().pause();
    }

    /**
     * Resumes the email-sending worker after a pause.
     */
    @FXML
    private void onResume() {
        paused = false;
        btnPause.setDisable(false);
        progressLabel.setText("Running…");
        AppContext ctx = AppContext.get();
        if (ctx.getEmailDispatcher() != null) ctx.getEmailDispatcher().resume();
    }

    /**
     * Cancels all pending jobs after user confirmation.
     */
    @FXML
    private void onCancelAll() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Cancel All Jobs");
        confirm.setHeaderText(null);
        confirm.setContentText(
                "Are you sure you want to cancel ALL pending and sending jobs?\n"
                + "This cannot be undone.");
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                jobRows.clear();
                progressLabel.setText("Cancelled");
                overallProgress.setProgress(0);
                AppContext ctx = AppContext.get();
                if (ctx.getEmailQueue() != null) ctx.getEmailQueue().cancelPending();
            }
        });
    }

    /**
     * Re-queues all failed and dead-letter jobs so they are attempted again.
     */
    @FXML
    private void onRetryFailed() {
        AppContext ctx = AppContext.get();
        if (ctx.getEmailQueue() != null) {
            ctx.getEmailQueue().retryAll();
            progressLabel.setText("Failed jobs re-queued.");
        } else {
            progressLabel.setText("Queue not available.");
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Replaces the job-table rows with a fresh snapshot.
     * Safe to call from any thread.
     *
     * @param rows updated job rows from the queue
     */
    public void updateJobTable(List<JobRow> rows) {
        Runnable update = () -> jobRows.setAll(rows);
        if (javafx.application.Platform.isFxApplicationThread()) {
            update.run();
        } else {
            javafx.application.Platform.runLater(update);
        }
    }
}
