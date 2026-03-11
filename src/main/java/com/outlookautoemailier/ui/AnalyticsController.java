package com.outlookautoemailier.ui;

import com.outlookautoemailier.AppContext;
import com.outlookautoemailier.analytics.SentEmailRecord;
import com.outlookautoemailier.analytics.SentEmailStore;
import com.outlookautoemailier.analytics.TrackingPixelServer;
import javafx.animation.Animation;
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

public class AnalyticsController implements Initializable {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @FXML private Label totalSentLabel;
    @FXML private Label totalOpensLabel;
    @FXML private Label uniqueOpensLabel;
    @FXML private Label openRateLabel;
    @FXML private Label trackingServerStatusLabel;

    @FXML private TableView<SentEmailRecord>               sentTable;
    @FXML private TableColumn<SentEmailRecord, String>     recipientColumn;
    @FXML private TableColumn<SentEmailRecord, String>     subjectColumn;
    @FXML private TableColumn<SentEmailRecord, String>     sentAtColumn;
    @FXML private TableColumn<SentEmailRecord, String>     openCountColumn;
    @FXML private TableColumn<SentEmailRecord, String>     lastOpenedColumn;

    private final ObservableList<SentEmailRecord> rows = FXCollections.observableArrayList();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        configureColumns();
        sentTable.setItems(rows);
        AppContext.get().setAnalyticsController(this);
        refresh();

        // Auto-refresh every 5 seconds so new sends and opens appear without clicking Refresh
        Timeline autoRefresh = new Timeline(
                new KeyFrame(Duration.seconds(5), e -> refresh()));
        autoRefresh.setCycleCount(Animation.INDEFINITE);
        autoRefresh.play();
    }

    private void configureColumns() {
        recipientColumn.setCellValueFactory(cd ->
            new SimpleStringProperty(cd.getValue().getRecipientEmail()));
        subjectColumn.setCellValueFactory(cd ->
            new SimpleStringProperty(cd.getValue().getSubject()));
        sentAtColumn.setCellValueFactory(cd -> {
            var t = cd.getValue().getSentAt();
            return new SimpleStringProperty(t != null ? t.format(FMT) : "");
        });
        openCountColumn.setCellValueFactory(cd ->
            new SimpleStringProperty(String.valueOf(cd.getValue().getOpenCount())));
        lastOpenedColumn.setCellValueFactory(cd -> {
            var t = cd.getValue().getLastOpenedAt();
            return new SimpleStringProperty(t != null ? t.format(FMT) : "\u2014");
        });
    }

    @FXML
    public void onRefresh() {
        refresh();
    }

    public void refresh() {
        List<SentEmailRecord> all = SentEmailStore.getInstance().getAll();
        rows.setAll(all);

        int totalSent   = all.size();
        int totalOpens  = all.stream().mapToInt(SentEmailRecord::getOpenCount).sum();
        int uniqueOpens = (int) all.stream().filter(r -> r.getOpenCount() > 0).count();
        double rate     = totalSent == 0 ? 0.0 : (double) uniqueOpens / totalSent * 100.0;

        totalSentLabel.setText(String.valueOf(totalSent));
        totalOpensLabel.setText(String.valueOf(totalOpens));
        uniqueOpensLabel.setText(String.valueOf(uniqueOpens));
        openRateLabel.setText(String.format("%.1f%%", rate));

        TrackingPixelServer server = AppContext.get().getTrackingPixelServer();
        if (server != null && server.isRunning()) {
            trackingServerStatusLabel.setText("Running on port " + server.getPort());
        } else {
            trackingServerStatusLabel.setText("Not running (emails sent before this session won't track)");
        }
    }
}
