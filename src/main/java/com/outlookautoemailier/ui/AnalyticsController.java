package com.outlookautoemailier.ui;

import com.outlookautoemailier.AppContext;
import com.outlookautoemailier.analytics.BatchStore;
import com.outlookautoemailier.analytics.ContactReachabilityScorer;
import com.outlookautoemailier.analytics.DeliverabilityHealthScorer;
import com.outlookautoemailier.analytics.EmailBatch;
import com.outlookautoemailier.analytics.LinkClickRecord;
import com.outlookautoemailier.analytics.SendTimeAnalyser;
import com.outlookautoemailier.analytics.SentEmailRecord;
import com.outlookautoemailier.analytics.SentEmailStore;
import com.outlookautoemailier.analytics.SubjectLineAnalyser;
import com.outlookautoemailier.analytics.UnsubscribeAnalyser;
import com.outlookautoemailier.integration.GeminiEmailAgent;
import com.outlookautoemailier.integration.SupabaseAnalyticsSync;
import com.outlookautoemailier.security.UnsubscribeManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class AnalyticsController implements Initializable {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Stat labels ───────────────────────────────────────────────────────────
    @FXML private Label totalBatchesLabel;
    @FXML private Label avgOpenRateLabel;
    @FXML private Label totalSentLabel;
    @FXML private Label totalDeliveredLabel;
    @FXML private Label totalFailedLabel;
    @FXML private Label totalSuppressedLabel;
    @FXML private Label suppressedThisMonthLabel;

    // ── Deliverability health ────────────────────────────────────────────────
    @FXML private Label healthScoreLabel;
    @FXML private Label healthScoreTierLabel;
    @FXML private Label avgCtorLabel;

    // ── Reachability stat labels ─────────────────────────────────────────────
    @FXML private Label reachableCountLabel;
    @FXML private Label atRiskCountLabel;
    @FXML private Label unreachableCountLabel;

    // ── Engagement tier labels ───────────────────────────────────────────────
    @FXML private Label championsCountLabel;
    @FXML private Label activeCountLabel;
    @FXML private Label atRiskEngagementLabel;
    @FXML private Label dormantCountLabel;

    // ── AI Insights ───────────────────────────────────────────────────────────
    @FXML private Button aiInsightsButton;
    @FXML private ProgressIndicator aiSpinner;
    @FXML private TextArea aiInsightsArea;

    // ── Unsub trend chart ─────────────────────────────────────────────────────
    @FXML private WebView unsubChartView;

    private boolean unsubChartReady = false;
    private boolean unsubChartPending = false;

    // ── Unsub rate column ─────────────────────────────────────────────────────
    @FXML private TableColumn<EmailBatch, String> batchUnsubRateColumn;

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
    @FXML private TableColumn<EmailBatch, String> batchCtorColumn;

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
            function updateChart(categories, openData, clickData, ctorData) {
              chart.setOption({
                backgroundColor: '#ffffff',
                tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
                legend: {
                  data: ['Unique Open Rate %', 'Click Rate %', 'CTOR %'],
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
                    name: 'Unique Open Rate %',
                    type: 'bar',
                    data: openData,
                    barMaxWidth: 28,
                    itemStyle: { color: '#2563eb', borderRadius: [4,4,0,0] },
                    emphasis: { itemStyle: { color: '#1d4ed8' } }
                  },
                  {
                    name: 'Click Rate %',
                    type: 'bar',
                    data: clickData,
                    barMaxWidth: 28,
                    itemStyle: { color: '#7c3aed', borderRadius: [4,4,0,0] },
                    emphasis: { itemStyle: { color: '#6d28d9' } }
                  },
                  {
                    name: 'CTOR %',
                    type: 'line',
                    data: ctorData,
                    smooth: true,
                    symbol: 'circle',
                    symbolSize: 6,
                    lineStyle: { color: '#0d9488', width: 2 },
                    itemStyle: { color: '#0d9488' }
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

    private static final String UNSUB_CHART_HTML = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="UTF-8">
            <style>
              * { margin:0; padding:0; box-sizing:border-box; }
              body { background:#ffffff; font-family:"Segoe UI",Arial,sans-serif; }
              #chart { width:100%%; height:220px; }
            </style>
            <script src="https://cdn.jsdelivr.net/npm/echarts@5/dist/echarts.min.js"></script>
            </head>
            <body>
            <div id="chart"></div>
            <script>
            var chart = echarts.init(document.getElementById('chart'));
            function updateUnsubChart(weeks, counts) {
              chart.setOption({
                backgroundColor: '#ffffff',
                tooltip: { trigger: 'axis' },
                grid: { left: 50, right: 16, top: 20, bottom: 40 },
                xAxis: {
                  type: 'category',
                  data: weeks,
                  axisLabel: { color: '#6b7280', rotate: 30, fontSize: 10 },
                  axisLine: { lineStyle: { color: '#dde3ec' } },
                  axisTick: { show: false }
                },
                yAxis: {
                  type: 'value',
                  minInterval: 1,
                  axisLabel: { color: '#6b7280', fontSize: 11 },
                  splitLine: { lineStyle: { color: '#eef2f7' } },
                  axisLine: { show: false }
                },
                series: [{
                  type: 'line',
                  data: counts,
                  smooth: true,
                  symbol: 'circle',
                  symbolSize: 6,
                  lineStyle: { color: '#ef4444', width: 2 },
                  itemStyle: { color: '#ef4444' },
                  areaStyle: {
                    color: {
                      type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
                      colorStops: [
                        { offset: 0, color: 'rgba(239,68,68,0.2)' },
                        { offset: 1, color: 'rgba(239,68,68,0.02)' }
                      ]
                    }
                  }
                }]
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
        initUnsubChart();

        // AI Insights button — disable if Gemini is not configured
        aiSpinner.setVisible(false);
        aiInsightsArea.setVisible(false);
        if (!GeminiEmailAgent.isConfigured()) {
            aiInsightsButton.setDisable(true);
            aiInsightsButton.setText("AI Insights (No API Key)");
        }

        // Fetch campaigns + link-click counts from Supabase; refresh UI when done
        SupabaseAnalyticsSync.syncBatchesFromSupabaseAsync()
                .thenRun(() -> javafx.application.Platform.runLater(this::refresh));
        syncLinkClickCounts();
        SupabaseAnalyticsSync.syncUniqueOpenCountsAsync()
                .thenRun(() -> javafx.application.Platform.runLater(this::refresh));
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
                    SupabaseAnalyticsSync.syncUniqueOpenCountsAsync()
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
                new SimpleStringProperty(String.valueOf(cd.getValue().getUniqueOpenCount())));
        batchOpenRateColumn.setCellValueFactory(cd ->
                new SimpleStringProperty(String.format("%.1f%%", cd.getValue().uniqueOpenRatePct())));

        batchCtorColumn.setCellValueFactory(cd ->
                new SimpleStringProperty(String.format("%.1f%%", cd.getValue().ctorPct())));
        batchCtorColumn.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                setStyle("-fx-text-fill:#0d9488;-fx-font-weight:bold;");
            }
        });

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

        // Unsub rate column — configured from cached rates; warning for >1%
        batchUnsubRateColumn.setCellValueFactory(cd -> {
            double rate = cachedUnsubRates.getOrDefault(cd.getValue().getId(), 0.0);
            return new SimpleStringProperty(String.format("%.1f%%", rate));
        });
        batchUnsubRateColumn.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); getStyleClass().removeAll("unsub-warning-cell"); return; }
                setText(item);
                getStyleClass().removeAll("unsub-warning-cell");
                try {
                    double val = Double.parseDouble(item.replace("%", ""));
                    if (val > 1.0) {
                        getStyleClass().add("unsub-warning-cell");
                    }
                } catch (NumberFormatException ignored) {}
            }
        });
    }

    /** Cached unsub rates per batch, refreshed each cycle. */
    private Map<String, Double> cachedUnsubRates = Map.of();

    // ── FXML handlers ─────────────────────────────────────────────────────────

    @FXML
    public void onRefresh() { refresh(); }

    @FXML
    private void onSyncOpens() {
        SupabaseAnalyticsSync.syncOpensAsync()
                .thenRun(() -> javafx.application.Platform.runLater(this::refresh));
    }

    @FXML
    private void onAiInsights() {
        aiInsightsButton.setDisable(true);
        aiSpinner.setVisible(true);
        aiInsightsArea.setVisible(true);
        aiInsightsArea.setText("Analysing campaign performance...");

        String contextJson = buildPerformanceContextJson();
        GeminiEmailAgent.analyzePerformanceAsync(contextJson)
                .thenAccept(result -> javafx.application.Platform.runLater(() -> {
                    aiSpinner.setVisible(false);
                    aiInsightsArea.setText(result);
                    aiInsightsButton.setDisable(false);
                }))
                .exceptionally(ex -> {
                    javafx.application.Platform.runLater(() -> {
                        aiSpinner.setVisible(false);
                        aiInsightsArea.setText("Analysis failed: " + ex.getMessage());
                        aiInsightsButton.setDisable(false);
                    });
                    return null;
                });
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

    @FXML
    private void onSubjectAnalysis() {
        List<EmailBatch> batches = BatchStore.getInstance().getAll();
        List<SubjectLineAnalyser.FeatureInsight> insights = SubjectLineAnalyser.analyse(batches);

        if (insights.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION,
                    "Not enough data for subject line analysis.\n\n" +
                    "At least 15 campaigns with delivered emails are needed.")
                    .showAndWait();
            return;
        }

        TableView<SubjectLineAnalyser.FeatureInsight> table = new TableView<>();
        table.setPrefHeight(320);

        TableColumn<SubjectLineAnalyser.FeatureInsight, String> colFeature =
                makeCol("Feature", 200, SubjectLineAnalyser.FeatureInsight::getFeatureName);
        TableColumn<SubjectLineAnalyser.FeatureInsight, String> colWith =
                makeCol("With (#)", 65, f -> String.valueOf(f.getWithCount()));
        TableColumn<SubjectLineAnalyser.FeatureInsight, String> colOpenWith =
                makeCol("Avg Open% With", 110, f -> String.format("%.1f%%", f.getAvgOpenRateWith()));
        TableColumn<SubjectLineAnalyser.FeatureInsight, String> colWithout =
                makeCol("Without (#)", 75, f -> String.valueOf(f.getWithoutCount()));
        TableColumn<SubjectLineAnalyser.FeatureInsight, String> colOpenWithout =
                makeCol("Avg Open% Without", 120, f -> String.format("%.1f%%", f.getAvgOpenRateWithout()));
        TableColumn<SubjectLineAnalyser.FeatureInsight, String> colLift =
                makeCol("Lift", 70, f -> String.format("%+.1f%%", f.getLift()));

        colLift.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                try {
                    double val = Double.parseDouble(item.replace("%", "").replace("+", ""));
                    setStyle(val > 0 ? "-fx-text-fill:#16a34a;-fx-font-weight:bold;"
                                     : val < 0 ? "-fx-text-fill:#dc2626;-fx-font-weight:bold;" : "");
                } catch (NumberFormatException ignored) { setStyle(""); }
            }
        });

        table.getColumns().addAll(colFeature, colWith, colOpenWith, colWithout, colOpenWithout, colLift);
        table.setItems(FXCollections.observableArrayList(insights));

        VBox content = new VBox(8,
                new Label("Subject line features correlated with unique open rates:"),
                table);
        content.setPrefWidth(680);

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Subject Line Analysis");
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(700);
        dialog.showAndWait();
    }

    @FXML
    private void onLinkLeaderboard() {
        List<EmailBatch> batches = BatchStore.getInstance().getAll();
        if (batches.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "No campaigns to analyse.")
                    .showAndWait();
            return;
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Link Performance Leaderboard");
        dialog.setHeaderText(null);

        Label loadingLabel = new Label("Loading link click data...");
        VBox content = new VBox(8, loadingLabel);
        content.setPrefWidth(680);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(700);

        javafx.concurrent.Task<List<LinkLeaderboardEntry>> task = new javafx.concurrent.Task<>() {
            @Override protected List<LinkLeaderboardEntry> call() {
                Map<String, LinkLeaderboardEntry> urlMap = new LinkedHashMap<>();
                for (EmailBatch batch : batches) {
                    List<LinkClickRecord> clicks =
                            SupabaseAnalyticsSync.fetchLinkClicksForBatch(batch.getId());
                    for (LinkClickRecord click : clicks) {
                        LinkLeaderboardEntry entry = urlMap.computeIfAbsent(
                                click.getOriginalUrl(), u -> new LinkLeaderboardEntry(u));
                        entry.totalClicks    += click.getClickCount();
                        entry.uniqueClickers += click.getUniqueClickerCount();
                        entry.campaignCount++;
                    }
                }
                List<LinkLeaderboardEntry> result = new ArrayList<>(urlMap.values());
                result.sort((a, b) -> Integer.compare(b.totalClicks, a.totalClicks));
                return result;
            }
        };
        task.setOnSucceeded(e -> {
            List<LinkLeaderboardEntry> entries = task.getValue();
            content.getChildren().clear();
            if (entries.isEmpty()) {
                content.getChildren().add(new Label("No link click data found."));
                return;
            }
            TableView<LinkLeaderboardEntry> table = new TableView<>();
            table.setPrefHeight(360);
            TableColumn<LinkLeaderboardEntry, String> colUrl =
                    makeCol("URL", 320, entry -> {
                        String url = entry.url;
                        return url.length() > 60 ? url.substring(0, 57) + "..." : url;
                    });
            TableColumn<LinkLeaderboardEntry, String> colClicks =
                    makeCol("Total Clicks", 90, entry -> String.valueOf(entry.totalClicks));
            TableColumn<LinkLeaderboardEntry, String> colUnique =
                    makeCol("Unique Clickers", 110, entry -> String.valueOf(entry.uniqueClickers));
            TableColumn<LinkLeaderboardEntry, String> colCampaigns =
                    makeCol("In N Campaigns", 100, entry -> String.valueOf(entry.campaignCount));
            table.getColumns().addAll(colUrl, colClicks, colUnique, colCampaigns);
            table.setItems(FXCollections.observableArrayList(entries));
            content.getChildren().add(new Label("Links ranked by total clicks across all campaigns:"));
            content.getChildren().add(table);
        });
        task.setOnFailed(e -> {
            content.getChildren().clear();
            content.getChildren().add(new Label("Failed to load link click data."));
        });
        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
        dialog.showAndWait();
    }

    /** Lightweight holder for link leaderboard aggregation. */
    private static class LinkLeaderboardEntry {
        final String url;
        int totalClicks;
        int uniqueClickers;
        int campaignCount;
        LinkLeaderboardEntry(String url) { this.url = url; }
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    public void refresh() {
        List<EmailBatch> batches = BatchStore.getInstance().getAll();

        // Compute unsub rates before setting table data
        Map<String, LocalDateTime> unsubData =
                UnsubscribeManager.getInstance().getAllSuppressedWithTimestamps();
        List<SentEmailRecord> allSends = SentEmailStore.getInstance().getAll();
        cachedUnsubRates = UnsubscribeAnalyser.campaignUnsubscribeRates(
                unsubData, allSends, batches);

        batchRows.setAll(batches);

        long totalSent   = batches.stream().mapToInt(EmailBatch::getSentCount).sum();
        long totalFailed = batches.stream().mapToInt(EmailBatch::getFailedCount).sum();
        long totalDelivered = totalSent;
        double avgOpen   = batches.stream()
                .filter(b -> b.getSentCount() > 0)
                .mapToDouble(EmailBatch::uniqueOpenRatePct)
                .average().orElse(0.0);

        totalBatchesLabel.setText(String.valueOf(batches.size()));
        avgOpenRateLabel.setText(String.format("%.1f%%", avgOpen));
        totalSentLabel.setText(String.valueOf(totalSent));
        totalDeliveredLabel.setText(String.valueOf(totalDelivered));
        totalFailedLabel.setText(String.valueOf(totalFailed));

        // Deliverability health score
        int healthScore = DeliverabilityHealthScorer.compute(batches, unsubData.size());
        healthScoreLabel.setText(healthScore + "/100");
        healthScoreLabel.setStyle("-fx-text-fill:" + DeliverabilityHealthScorer.color(healthScore) + ";");
        healthScoreTierLabel.setText(DeliverabilityHealthScorer.label(healthScore));
        healthScoreTierLabel.setStyle("-fx-text-fill:" + DeliverabilityHealthScorer.color(healthScore) + ";");

        // Avg CTOR
        double avgCtor = batches.stream()
                .filter(b -> b.getUniqueOpenCount() > 0)
                .mapToDouble(EmailBatch::ctorPct)
                .average().orElse(0.0);
        avgCtorLabel.setText(String.format("%.1f%%", avgCtor));

        // Unsub stats
        totalSuppressedLabel.setText(String.valueOf(unsubData.size()));
        suppressedThisMonthLabel.setText(
                String.valueOf(UnsubscribeAnalyser.countThisMonth(unsubData)));

        // Reachability scores
        Map<String, Integer> reachScores = ContactReachabilityScorer.computeScores(
                allSends, UnsubscribeManager.getInstance().getAllSuppressed());
        int[] categories = ContactReachabilityScorer.categorize(reachScores);
        reachableCountLabel.setText(String.valueOf(categories[0]));
        atRiskCountLabel.setText(String.valueOf(categories[1]));
        unreachableCountLabel.setText(String.valueOf(categories[2]));

        // Engagement tiers
        computeEngagementTiers(allSends, batches);

        updateChart(batches);
        refreshHourlyChart();
        refreshUnsubChart(unsubData);
    }

    /**
     * Computes engagement tiers by grouping all sent records by recipient email.
     * <ul>
     *   <li><b>Champion</b> -- opened AND was in a batch with link clicks</li>
     *   <li><b>Active</b> -- opened but no click attribution</li>
     *   <li><b>At-Risk</b> -- sent to but never opened (1-2 campaigns)</li>
     *   <li><b>Dormant</b> -- sent to 3+ times and never opened</li>
     * </ul>
     */
    private void computeEngagementTiers(List<SentEmailRecord> allSends, List<EmailBatch> batches) {
        // Group by recipient email
        Map<String, List<SentEmailRecord>> byRecipient = new HashMap<>();
        for (SentEmailRecord r : allSends) {
            if (r.getRecipientEmail() == null) continue;
            byRecipient.computeIfAbsent(r.getRecipientEmail().toLowerCase(Locale.ROOT),
                    k -> new ArrayList<>()).add(r);
        }

        // Collect batch IDs that have link clicks > 0
        Set<String> batchesWithClicks = new HashSet<>();
        for (EmailBatch b : batches) {
            if (b.getLinkClickCount() > 0) {
                batchesWithClicks.add(b.getId());
            }
        }

        int champions = 0, active = 0, atRisk = 0, dormant = 0;

        for (Map.Entry<String, List<SentEmailRecord>> entry : byRecipient.entrySet()) {
            List<SentEmailRecord> records = entry.getValue();
            boolean hasOpened = records.stream().anyMatch(r -> r.getOpenedAt() != null);

            if (hasOpened) {
                // Check if any opened campaign also had clicks
                boolean hasClickBatch = records.stream()
                        .filter(r -> r.getOpenedAt() != null && r.getBatchId() != null)
                        .anyMatch(r -> batchesWithClicks.contains(r.getBatchId()));
                if (hasClickBatch) {
                    champions++;
                } else {
                    active++;
                }
            } else {
                // Never opened -- how many distinct campaigns?
                long distinctCampaigns = records.stream()
                        .map(SentEmailRecord::getBatchId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .count();
                if (distinctCampaigns >= 3) {
                    dormant++;
                } else {
                    atRisk++;
                }
            }
        }

        championsCountLabel.setText(String.valueOf(champions));
        activeCountLabel.setText(String.valueOf(active));
        atRiskEngagementLabel.setText(String.valueOf(atRisk));
        dormantCountLabel.setText(String.valueOf(dormant));
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
        StringBuilder ctors  = new StringBuilder("[");
        for (int i = from; i < batches.size(); i++) {
            EmailBatch b = batches.get(i);
            String label = b.getBatchName().length() > 14
                    ? b.getBatchName().substring(0, 11) + "\u2026"
                    : b.getBatchName();
            if (i > from) {
                cats.append(","); opens.append(","); clicks.append(","); ctors.append(",");
            }
            cats.append("'").append(label.replace("'", "\\'")).append("'");
            opens.append(String.format("%.1f", b.uniqueOpenRatePct()));
            clicks.append(String.format("%.1f", b.linkClickRatePct()));
            ctors.append(String.format("%.1f", b.ctorPct()));
        }
        cats.append("]"); opens.append("]"); clicks.append("]"); ctors.append("]");
        try {
            chartView.getEngine().executeScript(
                    "updateChart(" + cats + "," + opens + "," + clicks + "," + ctors + ")");
        } catch (Exception e) {
            log.debug("Campaign chart script not ready yet, will retry on next refresh cycle");
        }
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

        try {
            hourlyChartView.getEngine().executeScript(
                    "updateHourlyChart(" + hours + "," + counts + "," + rateJs + ")");
        } catch (Exception e) {
            // ECharts CDN script not loaded yet; will retry on next auto-refresh
            log.debug("Hourly chart script not ready yet, will retry on next refresh cycle");
            return;
        }

        // Update advice label
        String advice = SendTimeAnalyser.getRecommendedWindows(rates);
        sendTimeAdvice.setText(advice);
    }

    // ── Unsubscribe trend chart ──────────────────────────────────────────────

    private void initUnsubChart() {
        unsubChartView.getEngine().setJavaScriptEnabled(true);
        unsubChartView.getEngine().loadContent(UNSUB_CHART_HTML);
        unsubChartView.getEngine().getLoadWorker().stateProperty().addListener(
                (obs, oldState, newState) -> {
                    if (newState == Worker.State.SUCCEEDED) {
                        unsubChartReady = true;
                        if (unsubChartPending) {
                            unsubChartPending = false;
                            Map<String, LocalDateTime> data =
                                    UnsubscribeManager.getInstance().getAllSuppressedWithTimestamps();
                            renderUnsubChart(data);
                        }
                    }
                });
    }

    private void refreshUnsubChart(Map<String, LocalDateTime> unsubData) {
        if (!unsubChartReady) {
            unsubChartPending = true;
            return;
        }
        renderUnsubChart(unsubData);
    }

    private void renderUnsubChart(Map<String, LocalDateTime> unsubData) {
        Map<String, Long> weekly = UnsubscribeAnalyser.weeklyUnsubscribeCounts(unsubData);
        // Show at most the last 12 weeks
        java.util.List<Map.Entry<String, Long>> entries = new java.util.ArrayList<>(weekly.entrySet());
        int from = Math.max(0, entries.size() - 12);

        StringBuilder weeks = new StringBuilder("[");
        StringBuilder counts = new StringBuilder("[");
        for (int i = from; i < entries.size(); i++) {
            if (i > from) { weeks.append(","); counts.append(","); }
            weeks.append("'").append(entries.get(i).getKey()).append("'");
            counts.append(entries.get(i).getValue());
        }
        weeks.append("]"); counts.append("]");

        try {
            unsubChartView.getEngine().executeScript(
                    "updateUnsubChart(" + weeks + "," + counts + ")");
        } catch (Exception e) {
            // ECharts CDN script not loaded yet; will retry on next auto-refresh
            log.debug("Unsub chart script not ready yet, will retry on next refresh cycle");
        }
    }

    // ── AI performance context builder ────────────────────────────────────────

    private String buildPerformanceContextJson() {
        try {
            List<EmailBatch> batches = BatchStore.getInstance().getAll();
            List<SentEmailRecord> sends = SentEmailStore.getInstance().getAll();

            ObjectNode root = MAPPER.createObjectNode();

            // Batch summaries
            ArrayNode batchArr = root.putArray("batches");
            for (EmailBatch b : batches) {
                ObjectNode bn = batchArr.addObject();
                bn.put("name", b.getBatchName());
                bn.put("subject", b.getSubject());
                bn.put("sentAt", b.getSentAt() != null ? b.getSentAt().toString() : "");
                bn.put("totalRecipients", b.getTotalRecipients());
                bn.put("delivered", b.getSentCount());
                bn.put("failed", b.getFailedCount());
                bn.put("uniqueOpens", b.getUniqueOpenCount());
                bn.put("openRate", String.format("%.1f%%", b.uniqueOpenRatePct()));
                bn.put("clickRate", String.format("%.1f%%", b.linkClickRatePct()));
                bn.put("ctor", String.format("%.1f%%", b.ctorPct()));
                double unsubRate = cachedUnsubRates.getOrDefault(b.getId(), 0.0);
                bn.put("unsubRate", String.format("%.1f%%", unsubRate));
            }

            // Failure reasons summary
            Map<String, Integer> failureReasons = new java.util.HashMap<>();
            for (SentEmailRecord r : sends) {
                if ("FAILED".equals(r.getStatus()) && r.getFailureReason() != null) {
                    failureReasons.merge(r.getFailureReason(), 1, Integer::sum);
                }
            }
            ObjectNode failures = root.putObject("failureReasons");
            failureReasons.forEach(failures::put);

            // Suppression summary
            Map<String, LocalDateTime> unsubs =
                    UnsubscribeManager.getInstance().getAllSuppressedWithTimestamps();
            root.put("totalSuppressed", unsubs.size());
            root.put("suppressedThisMonth", UnsubscribeAnalyser.countThisMonth(unsubs));

            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            log.error("Failed to build AI context JSON", e);
            return "{\"error\":\"Failed to gather metrics\"}";
        }
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
