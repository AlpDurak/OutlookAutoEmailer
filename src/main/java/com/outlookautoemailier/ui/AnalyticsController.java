package com.outlookautoemailier.ui;

import com.outlookautoemailier.AppContext;
import com.outlookautoemailier.analytics.BatchStore;
import com.outlookautoemailier.analytics.EmailBatch;
import com.outlookautoemailier.analytics.LinkClickRecord;
import com.outlookautoemailier.analytics.SendTimeAnalyser;
import com.outlookautoemailier.analytics.SentEmailRecord;
import com.outlookautoemailier.analytics.SentEmailStore;
import com.outlookautoemailier.integration.SupabaseAnalyticsSync;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.web.WebView;
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

    // ── Charts ────────────────────────────────────────────────────────────────
    @FXML private WebView chartView;
    @FXML private WebView hourlyChartView;
    @FXML private Label   sendTimeAdvice;

    private boolean chartReady = false;
    private List<EmailBatch> pendingChartData = null;

    private boolean hourlyChartReady = false;
    private boolean hourlyChartPending = false;

    private static final String CHART_HTML = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="UTF-8">
            <style>
              * { margin:0; padding:0; box-sizing:border-box; }
              body { background:#ffffff; font-family:"Segoe UI",Arial,sans-serif; }
              #chart { width:100%; height:230px; }
            </style>
            <script src="https://cdn.jsdelivr.net/npm/echarts@5/dist/echarts.min.js"></script>
            </head>
            <body>
            <div id="chart"></div>
            <script>
            var chart = echarts.init(document.getElementById('chart'));
            function updateChart(categories, openData, clickData) {
              chart.setOption({
                backgroundColor: '#ffffff',
                tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
                legend: {
                  data: ['Open Rate %', 'Click Rate %'],
                  textStyle: { color: '#6b7280', fontSize: 11 },
                  top: 4
                },
                grid: { left: 44, right: 16, top: 36, bottom: 54 },
                xAxis: {
                  type: 'category',
                  data: categories,
                  axisLabel: { color: '#6b7280', rotate: 30, fontSize: 11 },
                  axisLine: { lineStyle: { color: '#dde3ec' } },
                  axisTick: { show: false }
                },
                yAxis: {
                  type: 'value',
                  max: 100,
                  axisLabel: { color: '#6b7280', formatter: '{value}%', fontSize: 11 },
                  splitLine: { lineStyle: { color: '#eef2f7' } },
                  axisLine: { show: false }
                },
                series: [
                  {
                    name: 'Open Rate %',
                    type: 'bar',
                    data: openData,
                    barMaxWidth: 36,
                    itemStyle: { color: '#2563eb', borderRadius: [4,4,0,0] },
                    emphasis: { itemStyle: { color: '#1d4ed8' } }
                  },
                  {
                    name: 'Click Rate %',
                    type: 'bar',
                    data: clickData,
                    barMaxWidth: 36,
                    itemStyle: { color: '#7c3aed', borderRadius: [4,4,0,0] },
                    emphasis: { itemStyle: { color: '#6d28d9' } }
                  }
                ]
              });
            }
            window.addEventListener('resize', function() { chart.resize(); });
            </script>
            </body>
            </html>
            """;

    private static final String HOURLY_CHART_HTML = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="UTF-8">
            <style>
              * { margin:0; padding:0; box-sizing:border-box; }
              body { background:#ffffff; font-family:"Segoe UI",Arial,sans-serif; }
              #chart { width:100%%; height:250px; }
            </style>
            <script src="https://cdn.jsdelivr.net/npm/echarts@5/dist/echarts.min.js"></script>
            </head>
            <body>
            <div id="chart"></div>
            <script>
            var chart = echarts.init(document.getElementById('chart'));
            function updateHourlyChart(hours, counts, rates) {
              chart.setOption({
                backgroundColor: '#ffffff',
                tooltip: {
                  trigger: 'axis',
                  axisPointer: { type: 'cross' },
                  formatter: function(params) {
                    var s = params[0].axisValue + '<br/>';
                    params.forEach(function(p) {
                      s += p.marker + ' ' + p.seriesName + ': ';
                      s += p.seriesName.indexOf('Rate') >= 0
                           ? p.value.toFixed(1) + '%%'
                           : p.value;
                      s += '<br/>';
                    });
                    return s;
                  }
                },
                legend: {
                  data: ['Send Count', 'Success Rate %%'],
                  textStyle: { color: '#6b7280', fontSize: 11 },
                  top: 4
                },
                grid: { left: 50, right: 56, top: 40, bottom: 40 },
                xAxis: {
                  type: 'category',
                  data: hours,
                  axisLabel: { color: '#6b7280', fontSize: 11 },
                  axisLine: { lineStyle: { color: '#dde3ec' } },
                  axisTick: { show: false }
                },
                yAxis: [
                  {
                    type: 'value',
                    name: 'Sends',
                    nameTextStyle: { color: '#6b7280', fontSize: 10 },
                    axisLabel: { color: '#6b7280', fontSize: 11 },
                    splitLine: { lineStyle: { color: '#eef2f7' } },
                    axisLine: { show: false }
                  },
                  {
                    type: 'value',
                    name: 'Success Rate %%',
                    nameTextStyle: { color: '#6b7280', fontSize: 10 },
                    max: 100,
                    axisLabel: { color: '#6b7280', formatter: '{value}%%', fontSize: 11 },
                    splitLine: { show: false },
                    axisLine: { show: false }
                  }
                ],
                series: [
                  {
                    name: 'Send Count',
                    type: 'bar',
                    yAxisIndex: 0,
                    data: counts,
                    barMaxWidth: 24,
                    itemStyle: { color: '#2563eb', borderRadius: [4,4,0,0] },
                    emphasis: { itemStyle: { color: '#1d4ed8' } }
                  },
                  {
                    name: 'Success Rate %%',
                    type: 'line',
                    yAxisIndex: 1,
                    data: rates,
                    smooth: true,
                    symbol: 'circle',
                    symbolSize: 6,
                    lineStyle: { color: '#0d9488', width: 2 },
                    itemStyle: { color: '#0d9488' },
                    areaStyle: { color: 'rgba(13,148,136,0.08)' }
                  }
                ]
              });
            }
            window.addEventListener('resize', function() { chart.resize(); });
            </script>
            </body>
            </html>
            """;

    private final ObservableList<EmailBatch> batchRows = FXCollections.observableArrayList();

    // ── Initializable ─────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        configureColumns();
        batchTable.setItems(batchRows);
        AppContext.get().setAnalyticsController(this);
        initChart();
        initHourlyChart();

        // Fetch campaigns + link-click counts from Supabase; refresh UI when done
        SupabaseAnalyticsSync.syncBatchesFromSupabaseAsync()
                .thenRun(() -> javafx.application.Platform.runLater(this::refresh));
        syncLinkClickCounts();
        refresh();

        // Auto-refresh local data every 5 seconds
        Timeline localRefresh = new Timeline(
                new KeyFrame(Duration.seconds(5), e -> refresh()));
        localRefresh.setCycleCount(Animation.INDEFINITE);
        localRefresh.play();

        // Sync opens + link-click counts from Supabase every 60 seconds
        Timeline openSync = new Timeline(
                new KeyFrame(Duration.seconds(60), e -> {
                    SupabaseAnalyticsSync.syncOpensAsync()
                            .thenRun(() -> javafx.application.Platform.runLater(this::refresh));
                    syncLinkClickCounts();
                }));
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
        SupabaseAnalyticsSync.syncOpensAsync()
                .thenRun(() -> javafx.application.Platform.runLater(this::refresh));
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
        refreshHourlyChart();
    }

    /** Fetches per-batch unique-clicker counts from Supabase and updates BatchStore. */
    private void syncLinkClickCounts() {
        SupabaseAnalyticsSync.syncLinkClickCountsAsync()
                .thenAccept(counts -> {
                    counts.forEach((batchId, count) ->
                            BatchStore.getInstance().setLinkClickCount(batchId, count));
                    javafx.application.Platform.runLater(this::refresh);
                });
    }

    private void initChart() {
        chartView.getEngine().setJavaScriptEnabled(true);
        chartView.getEngine().loadContent(CHART_HTML);
        chartView.getEngine().getLoadWorker().stateProperty().addListener(
                (obs, oldState, newState) -> {
                    if (newState == Worker.State.SUCCEEDED) {
                        chartReady = true;
                        if (pendingChartData != null) {
                            renderChart(pendingChartData);
                            pendingChartData = null;
                        }
                    }
                });
    }

    private void updateChart(List<EmailBatch> batches) {
        if (!chartReady) {
            pendingChartData = batches;
            return;
        }
        renderChart(batches);
    }

    private void renderChart(List<EmailBatch> batches) {
        int from = Math.max(0, batches.size() - 10);
        StringBuilder cats   = new StringBuilder("[");
        StringBuilder opens  = new StringBuilder("[");
        StringBuilder clicks = new StringBuilder("[");
        for (int i = from; i < batches.size(); i++) {
            EmailBatch b = batches.get(i);
            String label = b.getBatchName().length() > 14
                    ? b.getBatchName().substring(0, 11) + "\u2026"
                    : b.getBatchName();
            if (i > from) { cats.append(","); opens.append(","); clicks.append(","); }
            cats.append("'").append(label.replace("'", "\\'")).append("'");
            opens.append(String.format("%.1f", b.openRatePct()));
            clicks.append(String.format("%.1f", b.linkClickRatePct()));
        }
        cats.append("]"); opens.append("]"); clicks.append("]");
        chartView.getEngine().executeScript(
                "updateChart(" + cats + "," + opens + "," + clicks + ")");
    }

    // ── Hourly distribution chart ──────────────────────────────────────────────

    private void initHourlyChart() {
        hourlyChartView.getEngine().setJavaScriptEnabled(true);
        hourlyChartView.getEngine().loadContent(HOURLY_CHART_HTML);
        hourlyChartView.getEngine().getLoadWorker().stateProperty().addListener(
                (obs, oldState, newState) -> {
                    if (newState == Worker.State.SUCCEEDED) {
                        hourlyChartReady = true;
                        if (hourlyChartPending) {
                            hourlyChartPending = false;
                            renderHourlyChart();
                        }
                    }
                });
    }

    private void refreshHourlyChart() {
        if (!hourlyChartReady) {
            hourlyChartPending = true;
            return;
        }
        renderHourlyChart();
    }

    private void renderHourlyChart() {
        List<SentEmailRecord> allRecords = SentEmailStore.getInstance().getAll();

        // Compute send counts per hour
        int[] countsPerHour = new int[24];
        for (SentEmailRecord r : allRecords) {
            if (r.getSentAt() != null) {
                countsPerHour[r.getSentAt().getHour()]++;
            }
        }

        // Compute success rates per hour
        double[] rates = SendTimeAnalyser.computeHourlySuccessRates(allRecords);

        // Build JS arrays
        StringBuilder hours  = new StringBuilder("[");
        StringBuilder counts = new StringBuilder("[");
        StringBuilder rateJs = new StringBuilder("[");
        for (int h = 0; h < 24; h++) {
            if (h > 0) { hours.append(","); counts.append(","); rateJs.append(","); }
            hours.append("'").append(String.format("%02d:00", h)).append("'");
            counts.append(countsPerHour[h]);
            rateJs.append(String.format("%.1f", rates[h] * 100));
        }
        hours.append("]"); counts.append("]"); rateJs.append("]");

        hourlyChartView.getEngine().executeScript(
                "updateHourlyChart(" + hours + "," + counts + "," + rateJs + ")");

        // Update advice label
        String advice = SendTimeAnalyser.getRecommendedWindows(rates);
        sendTimeAdvice.setText(advice);
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
