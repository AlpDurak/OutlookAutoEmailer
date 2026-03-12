package com.outlookautoemailier.ui;

import com.outlookautoemailier.AppContext;
import com.outlookautoemailier.analytics.BatchStore;
import com.outlookautoemailier.analytics.EmailBatch;
import com.outlookautoemailier.analytics.LinkClickRecord;
import com.outlookautoemailier.analytics.SentEmailRecord;
import com.outlookautoemailier.analytics.SentEmailStore;
import com.outlookautoemailier.integration.SupabaseAnalyticsSync;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.net.URL;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ResourceBundle;

public class AnalyticsController implements Initializable {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ── Stat labels ───────────────────────────────────────────────────────────
    @FXML private Label totalBatchesLabel;
    @FXML private Label avgOpenRateLabel;
    @FXML private Label totalSentLabel;
    @FXML private Label totalFailedLabel;

    // ── Batch table ───────────────────────────────────────────────────────────
    @FXML private TableView<EmailBatch>           batchTable;
    @FXML private TableColumn<EmailBatch, String> batchNameColumn;
    @FXML private TableColumn<EmailBatch, String> batchSubjectColumn;
    @FXML private TableColumn<EmailBatch, String> batchSentAtColumn;
    @FXML private TableColumn<EmailBatch, String> batchTotalColumn;
    @FXML private TableColumn<EmailBatch, String> batchSentColumn;
    @FXML private TableColumn<EmailBatch, String> batchFailedColumn;
    @FXML private TableColumn<EmailBatch, String> batchOpensColumn;
    @FXML private TableColumn<EmailBatch, String> batchOpenRateColumn;

    // ── Chart ─────────────────────────────────────────────────────────────────
    @FXML private BarChart<String, Number> openRateChart;

    private final ObservableList<EmailBatch> batchRows = FXCollections.observableArrayList();

    // ── Initializable ─────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        configureColumns();
        batchTable.setItems(batchRows);
        AppContext.get().setAnalyticsController(this);
        SupabaseAnalyticsSync.syncBatchesFromSupabaseAsync();
        refresh();

        // Auto-refresh local data every 5 seconds
        Timeline localRefresh = new Timeline(
                new KeyFrame(Duration.seconds(5), e -> refresh()));
        localRefresh.setCycleCount(Animation.INDEFINITE);
        localRefresh.play();

        // Sync open events from Supabase every 60 seconds
        Timeline openSync = new Timeline(
                new KeyFrame(Duration.seconds(60), e -> SupabaseAnalyticsSync.syncOpensAsync()));
        openSync.setCycleCount(Animation.INDEFINITE);
        openSync.play();
    }

    // ── Column wiring ─────────────────────────────────────────────────────────

    private void configureColumns() {
        batchNameColumn.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getBatchName()));
        batchSubjectColumn.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getSubject()));
        batchSentAtColumn.setCellValueFactory(cd -> {
            var t = cd.getValue().getSentAt();
            return new SimpleStringProperty(t != null ? t.format(FMT) : "");
        });
        batchTotalColumn.setCellValueFactory(cd ->
                new SimpleStringProperty(String.valueOf(cd.getValue().getTotalRecipients())));
        batchSentColumn.setCellValueFactory(cd ->
                new SimpleStringProperty(String.valueOf(cd.getValue().getSentCount())));
        batchFailedColumn.setCellValueFactory(cd ->
                new SimpleStringProperty(String.valueOf(cd.getValue().getFailedCount())));
        batchOpensColumn.setCellValueFactory(cd ->
                new SimpleStringProperty(String.valueOf(cd.getValue().getOpenCount())));
        batchOpenRateColumn.setCellValueFactory(cd ->
                new SimpleStringProperty(String.format("%.1f%%", cd.getValue().openRatePct())));

        batchFailedColumn.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                setStyle(!"0".equals(item)
                        ? "-fx-text-fill:#ef4444;-fx-font-weight:bold;" : "");
            }
        });

        batchOpenRateColumn.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                setStyle("-fx-text-fill:#16a34a;-fx-font-weight:bold;");
            }
        });
    }

    // ── FXML handlers ─────────────────────────────────────────────────────────

    @FXML
    public void onRefresh() { refresh(); }

    @FXML
    private void onSyncOpens() {
        SupabaseAnalyticsSync.syncOpensAsync();
        refresh();
    }

    @FXML
    private void onViewDetails() {
        EmailBatch selected = batchTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.INFORMATION, "Select a campaign row first.")
                    .showAndWait();
            return;
        }
        showBatchDetails(selected);
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    public void refresh() {
        List<EmailBatch> batches = BatchStore.getInstance().getAll();
        batchRows.setAll(batches);

        long totalSent   = batches.stream().mapToInt(EmailBatch::getSentCount).sum();
        long totalFailed = batches.stream().mapToInt(EmailBatch::getFailedCount).sum();
        double avgOpen   = batches.stream()
                .filter(b -> b.getSentCount() > 0)
                .mapToDouble(EmailBatch::openRatePct)
                .average().orElse(0.0);

        totalBatchesLabel.setText(String.valueOf(batches.size()));
        avgOpenRateLabel.setText(String.format("%.1f%%", avgOpen));
        totalSentLabel.setText(String.valueOf(totalSent));
        totalFailedLabel.setText(String.valueOf(totalFailed));

        updateChart(batches);
    }

    private void updateChart(List<EmailBatch> batches) {
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        // Show the 10 most recent batches in chronological order
        int from = Math.max(0, batches.size() - 10);
        for (int i = from; i < batches.size(); i++) {
            EmailBatch b = batches.get(i);
            String label = b.getBatchName().length() > 14
                    ? b.getBatchName().substring(0, 11) + "\u2026"
                    : b.getBatchName();
            series.getData().add(new XYChart.Data<>(label, b.openRatePct()));
        }
        openRateChart.getData().setAll(series);
    }

    // ── Drill-down dialog ─────────────────────────────────────────────────────

    private void showBatchDetails(EmailBatch batch) {
        List<SentEmailRecord> sends = SentEmailStore.getInstance().getByBatchId(batch.getId());

        TableView<SentEmailRecord> table = new TableView<>();
        table.setPrefHeight(360);

        TableColumn<SentEmailRecord, String> colRecipient =
                makeCol("Recipient",  200, r -> r.getRecipientEmail());
        TableColumn<SentEmailRecord, String> colStatus =
                makeCol("Status",      75, r -> r.getStatus());
        TableColumn<SentEmailRecord, String> colSentAt =
                makeCol("Sent At",    130, r -> r.getSentAt() != null ? r.getSentAt().format(FMT) : "");
        TableColumn<SentEmailRecord, String> colOpenedAt =
                makeCol("Opened At",  130, r -> r.getOpenedAt() != null ? r.getOpenedAt().format(FMT) : "\u2014");
        TableColumn<SentEmailRecord, String> colDelay =
                makeCol("Open Delay",  90, r -> r.openDelayMinutes() >= 0
                        ? r.openDelayMinutes() + " min" : "\u2014");

        colStatus.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                setStyle("FAILED".equals(item)
                        ? "-fx-text-fill:#ef4444;-fx-font-weight:bold;"
                        : "-fx-text-fill:#16a34a;");
            }
        });

        table.getColumns().addAll(colRecipient, colStatus, colSentAt, colOpenedAt, colDelay);
        table.setItems(FXCollections.observableArrayList(sends));
        table.setPlaceholder(new Label("No send records found for this campaign."));

        long opened   = sends.stream().filter(r -> r.getOpenedAt() != null).count();
        double avgDelay = sends.stream()
                .filter(r -> r.openDelayMinutes() >= 0)
                .mapToLong(SentEmailRecord::openDelayMinutes)
                .average().orElse(-1);
        String summary = String.format(
                "Campaign: %s  ·  Total: %d  ·  Delivered: %d  ·  Failed: %d  ·  Opened: %d  ·  Avg delay: %s",
                batch.getBatchName(), batch.getTotalRecipients(),
                batch.getSentCount(), batch.getFailedCount(), opened,
                avgDelay >= 0 ? String.format("%.0f min", avgDelay) : "\u2014");

        // ── Link click analytics (loaded async so dialog opens immediately) ──────
        javafx.scene.control.Separator sep = new javafx.scene.control.Separator();

        Label clicksTitle = new Label("Link Click Analytics");
        clicksTitle.setStyle("-fx-font-weight:bold;");

        Label loadingLabel = new Label("Loading\u2026");
        loadingLabel.setStyle("-fx-text-fill:#64748b;");

        TableView<LinkClickRecord> clickTable = new TableView<>();
        clickTable.setPrefHeight(180);
        clickTable.setVisible(false);
        TableColumn<LinkClickRecord, String> colUrl =
                makeCol("URL", 340, r -> r.getOriginalUrl());
        TableColumn<LinkClickRecord, String> colClicks =
                makeCol("Clicks", 60, r -> String.valueOf(r.getClickCount()));
        TableColumn<LinkClickRecord, String> colUnique =
                makeCol("Unique Clickers", 110, r -> String.valueOf(r.getUniqueClickerCount()));
        TableColumn<LinkClickRecord, String> colRate =
                makeCol("Click Rate", 80,
                        r -> String.format("%.1f%%", r.clickRatePct(batch.getTotalRecipients())));
        clickTable.getColumns().addAll(colUrl, colClicks, colUnique, colRate);
        clickTable.setPlaceholder(new Label("No clicks recorded for this campaign."));

        javafx.concurrent.Task<List<LinkClickRecord>> clickFetch =
                new javafx.concurrent.Task<>() {
            @Override protected List<LinkClickRecord> call() {
                return SupabaseAnalyticsSync.fetchLinkClicksForBatch(batch.getId());
            }
        };
        clickFetch.setOnSucceeded(e -> {
            loadingLabel.setVisible(false);
            clickTable.setItems(FXCollections.observableArrayList(clickFetch.getValue()));
            clickTable.setVisible(true);
        });
        clickFetch.setOnFailed(e -> loadingLabel.setText("Could not load click data."));
        Thread t = new Thread(clickFetch);
        t.setDaemon(true);
        t.start();

        VBox content = new VBox(8, new Label(summary), table, sep,
                clicksTitle, loadingLabel, clickTable);
        content.setPrefWidth(760);

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Campaign Details — " + batch.getBatchName());
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(780);
        dialog.showAndWait();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static <T> TableColumn<T, String> makeCol(
            String title, double width,
            java.util.function.Function<T, String> fn) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new SimpleStringProperty(fn.apply(cd.getValue())));
        return c;
    }
}
