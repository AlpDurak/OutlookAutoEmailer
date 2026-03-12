package com.outlookautoemailier.ui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    @FXML private Pane imageLibraryPane;
    @FXML private Pane deadLetterPane;

    // ── Sidebar nav buttons ──────────────────────────────────────────────────
    @FXML private ToggleButton btnAccounts;
    @FXML private ToggleButton btnContacts;
    @FXML private ToggleButton btnCompose;
    @FXML private ToggleButton btnQueue;
    @FXML private ToggleButton btnSettings;
    @FXML private ToggleButton btnStudio;
    @FXML private ToggleButton btnAnalytics;
    @FXML private ToggleButton btnImageLibrary;
    @FXML private ToggleButton btnDeadLetters;

    // ── Sidebar badges ────────────────────────────────────────────────────
    @FXML private Label queueBadge;
    @FXML private Label contactsBadge;

    /** Ordered list used for bulk visibility toggling (holds ScrollPane wrappers). */
    private List<Node> allNodes;

    /** Maps each original pane to its ScrollPane wrapper (or to itself if not wrapped). */
    private final Map<Pane, Node> paneToWrapper = new HashMap<>();

    // ── Initializable ────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        com.outlookautoemailier.AppContext.get().setMainController(this);

        // Wrap content-heavy panes in ScrollPanes for vertical scrolling
        wrapAllPanesInScrollPanes();

        allNodes = List.of(
                paneToWrapper.get(accountSetupPane),
                paneToWrapper.get(contactListPane),
                paneToWrapper.get(composePane),
                paneToWrapper.get(queueDashboardPane),
                paneToWrapper.get(settingsPane),
                paneToWrapper.get(templateStudioPane),
                paneToWrapper.get(imageLibraryPane),
                paneToWrapper.get(analyticsPane),
                paneToWrapper.get(deadLetterPane)
        );

        // Wire nav buttons → pane visibility
        btnAccounts.setOnAction(e -> showPane(accountSetupPane));
        btnContacts.setOnAction(e -> showPane(contactListPane));
        btnCompose.setOnAction(e -> showPane(composePane));
        btnQueue.setOnAction(e -> showPane(queueDashboardPane));
        btnSettings.setOnAction(e -> showPane(settingsPane));
        btnStudio.setOnAction(e -> showPane(templateStudioPane));
        btnImageLibrary.setOnAction(e -> showPane(imageLibraryPane));
        btnAnalytics.setOnAction(e -> showPane(analyticsPane));
        btnDeadLetters.setOnAction(e -> showPane(deadLetterPane));

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

    /**
     * Replaces each content pane in the StackPane with a ScrollPane wrapper.
     * The ScrollPane provides vertical scrolling for content-heavy pages.
     */
    private void wrapAllPanesInScrollPanes() {
        List<Pane> panesToWrap = List.of(
                accountSetupPane, contactListPane, composePane,
                queueDashboardPane, settingsPane, templateStudioPane,
                imageLibraryPane, analyticsPane, deadLetterPane
        );

        for (Pane pane : panesToWrap) {
            if (pane.getParent() instanceof StackPane parent) {
                int index = parent.getChildren().indexOf(pane);
                if (index < 0) continue;

                ScrollPane scroll = new ScrollPane(pane);
                scroll.setFitToWidth(true);
                scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
                scroll.getStyleClass().add("content-scroll");
                scroll.setVisible(pane.isVisible());

                // The inner pane must always be visible — visibility is now
                // controlled by the ScrollPane wrapper, not the pane itself.
                pane.setVisible(true);

                parent.getChildren().set(index, scroll);
                paneToWrapper.put(pane, scroll);
            } else {
                paneToWrapper.put(pane, pane);
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Hides every managed pane/ScrollPane and makes {@code target}'s wrapper visible.
     *
     * @param target the original pane to reveal
     */
    private void showPane(Pane target) {
        allNodes.forEach(n -> n.setVisible(false));
        Node wrapper = paneToWrapper.get(target);
        if (wrapper != null) {
            wrapper.setVisible(true);
            wrapper.toFront();
            // Reset scroll position to top when navigating
            if (wrapper instanceof ScrollPane sp) {
                sp.setVvalue(0);
            }
        }
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

    public void navigateToImageLibrary() {
        btnImageLibrary.setSelected(true);
        showPane(imageLibraryPane);
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

    /**
     * Navigates to the Dead-Letter Explorer pane.
     * Called programmatically from other controllers.
     */
    public void navigateToDeadLetters() {
        btnDeadLetters.setSelected(true);
        showPane(deadLetterPane);
    }
}
