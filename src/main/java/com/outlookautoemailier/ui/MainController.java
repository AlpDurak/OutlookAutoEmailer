package com.outlookautoemailier.ui;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
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
}
