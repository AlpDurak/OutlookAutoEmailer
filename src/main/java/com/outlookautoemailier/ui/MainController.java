package com.outlookautoemailier.ui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.Pane;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

public class MainController implements Initializable {

    // ── Included sub-panes ──────────────────────────────────────────────────
    @FXML private Pane accountSetupPane;
    @FXML private Pane contactListPane;
    @FXML private Pane composePane;
    @FXML private Pane queueDashboardPane;
    @FXML private Pane settingsPane;
    @FXML private Pane templateStudioPane;
    @FXML private Pane analyticsPane;

    // ── Sidebar nav buttons ──────────────────────────────────────────────────
    @FXML private ToggleButton btnAccounts;
    @FXML private ToggleButton btnContacts;
    @FXML private ToggleButton btnCompose;
    @FXML private ToggleButton btnQueue;
    @FXML private ToggleButton btnSettings;
    @FXML private ToggleButton btnStudio;
    @FXML private ToggleButton btnAnalytics;

    // ── Sidebar badges ────────────────────────────────────────────────────
    @FXML private Label queueBadge;
    @FXML private Label contactsBadge;

    /** Ordered list used for bulk visibility toggling. */
    private List<Pane> allPanes;

    // ── Initializable ────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        com.outlookautoemailier.AppContext.get().setMainController(this);
        allPanes = List.of(
                accountSetupPane,
                contactListPane,
                composePane,
                queueDashboardPane,
                settingsPane,
                templateStudioPane,
                analyticsPane
        );

        // Wire nav buttons → pane visibility
        btnAccounts.setOnAction(e -> showPane(accountSetupPane));
        btnContacts.setOnAction(e -> showPane(contactListPane));
        btnCompose.setOnAction(e -> showPane(composePane));
        btnQueue.setOnAction(e -> showPane(queueDashboardPane));
        btnSettings.setOnAction(e -> showPane(settingsPane));
        btnStudio.setOnAction(e -> showPane(templateStudioPane));
        btnAnalytics.setOnAction(e -> showPane(analyticsPane));

        // Prevent the active toggle from being de-selected by a second click
        btnAccounts.getToggleGroup().selectedToggleProperty().addListener(
                (obs, oldToggle, newToggle) -> {
                    if (newToggle == null && oldToggle != null) {
                        oldToggle.setSelected(true);
                    }
                });

        // Default: show the Accounts pane on startup
        btnAccounts.setSelected(true);
        showPane(accountSetupPane);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Hides every managed pane and makes {@code target} visible.
     *
     * @param target the pane to reveal
     */
    private void showPane(Pane target) {
        allPanes.forEach(p -> p.setVisible(false));
        target.setVisible(true);
        target.toFront();
    }

    /**
     * Called by {@link ComposeController} after contacts are forwarded from
     * the Contacts view so the UI switches to the Compose pane automatically.
     */
    public void navigateToCompose() {
        btnCompose.setSelected(true);
        showPane(composePane);
    }

    /**
     * Navigates to the Queue Dashboard pane.
     * Called programmatically after email jobs are submitted to the queue.
     */
    public void navigateToQueue() {
        btnQueue.setSelected(true);
        showPane(queueDashboardPane);
    }

    public void navigateToAnalytics() {
        btnAnalytics.setSelected(true);
        showPane(analyticsPane);
    }

    // ── Badge updates ─────────────────────────────────────────────────────

    /**
     * Updates the queue sidebar badge.
     *
     * @param active number of pending + sending jobs
     * @param failed number of failed + dead-letter jobs
     */
    public void updateQueueBadge(int active, int failed) {
        Runnable update = () -> {
            if (active == 0 && failed == 0) {
                queueBadge.setVisible(false);
                queueBadge.setManaged(false);
                return;
            }
            if (failed > 0) {
                queueBadge.setText(String.valueOf(failed));
                if (!queueBadge.getStyleClass().contains("danger")) {
                    queueBadge.getStyleClass().add("danger");
                }
            } else {
                queueBadge.setText(String.valueOf(active));
                queueBadge.getStyleClass().remove("danger");
            }
            queueBadge.setVisible(true);
            queueBadge.setManaged(true);
        };
        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }

    /**
     * Updates the contacts sidebar badge.
     *
     * @param count number of recipients currently selected
     */
    public void updateContactsBadge(int count) {
        Runnable update = () -> {
            if (count == 0) {
                contactsBadge.setVisible(false);
                contactsBadge.setManaged(false);
            } else {
                contactsBadge.setText(String.valueOf(count));
                contactsBadge.setVisible(true);
                contactsBadge.setManaged(true);
            }
        };
        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }
}
