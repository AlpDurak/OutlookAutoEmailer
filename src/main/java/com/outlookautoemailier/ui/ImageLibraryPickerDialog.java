package com.outlookautoemailier.ui;

import com.outlookautoemailier.integration.GoogleDriveService;
import com.outlookautoemailier.model.ImageLibraryItem;
import com.outlookautoemailier.model.ImageLibraryStore;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A modal dialog that lets the user browse the Image Library
 * and select an image for insertion into the Template Studio editor.
 *
 * <p>Built programmatically (no FXML). Shows tag filter chips,
 * a scrollable image grid, and Insert/Cancel buttons.
 */
public class ImageLibraryPickerDialog {

    private static final Logger log = LoggerFactory.getLogger(ImageLibraryPickerDialog.class);

    private static final double DIALOG_WIDTH = 700;
    private static final double DIALOG_HEIGHT = 500;
    private static final double CARD_WIDTH = 150;
    private static final double CARD_IMAGE_WIDTH = 130;
    private static final double CARD_IMAGE_HEIGHT = 90;
    private static final int GRID_HGAP = 12;
    private static final int GRID_VGAP = 12;

    private final Dialog<ImageLibraryItem> dialog;
    private FlowPane imageGrid;
    private FlowPane tagFilterPane;
    private Label emptyLabel;
    private Button insertButton;

    private ImageLibraryItem selectedItem;
    private VBox selectedCard;
    private String activeTagFilter;

    /**
     * Creates the picker dialog owned by the given window.
     */
    public ImageLibraryPickerDialog(Window owner) {
        dialog = new Dialog<>();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(owner);
        dialog.setTitle("Insert Image from Library");
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.setPrefSize(DIALOG_WIDTH, DIALOG_HEIGHT);
        dialogPane.getStylesheets().add(
                getClass().getResource("/css/style.css").toExternalForm());

        // Add hidden CANCEL button type so the dialog can close via the X button
        dialogPane.getButtonTypes().add(ButtonType.CANCEL);
        Node cancelNative = dialogPane.lookupButton(ButtonType.CANCEL);
        if (cancelNative != null) {
            cancelNative.setVisible(false);
            cancelNative.setManaged(false);
        }

        // ── Check preconditions ──────────────────────────────────────────────
        if (!GoogleDriveService.getInstance().isAuthenticated()) {
            dialogPane.setContent(buildMessagePane(
                    "Connect Google Drive in the Image Library tab to use this feature."));
            dialog.setResultConverter(bt -> null);
            return;
        }

        List<ImageLibraryItem> allImages = ImageLibraryStore.getInstance().getAll();
        if (allImages.isEmpty()) {
            dialogPane.setContent(buildMessagePane(
                    "No images in library. Upload images in the Image Library tab first."));
            dialog.setResultConverter(bt -> null);
            return;
        }

        // ── Build UI ─────────────────────────────────────────────────────────

        VBox root = new VBox(12);
        root.setPadding(new Insets(4, 0, 0, 0));

        // Tag filter row
        tagFilterPane = new FlowPane(8, 6);
        tagFilterPane.setAlignment(Pos.CENTER_LEFT);
        tagFilterPane.setPadding(new Insets(0, 0, 4, 0));
        buildTagFilters(allImages);

        // Scrollable image grid
        imageGrid = new FlowPane(GRID_HGAP, GRID_VGAP);
        imageGrid.setPadding(new Insets(8));
        imageGrid.setAlignment(Pos.TOP_LEFT);

        emptyLabel = new Label("No images match this filter.");
        emptyLabel.getStyleClass().add("help-label");
        emptyLabel.setVisible(false);
        emptyLabel.setManaged(false);

        StackPane gridWrapper = new StackPane(imageGrid, emptyLabel);
        gridWrapper.setAlignment(Pos.TOP_CENTER);

        ScrollPane scroll = new ScrollPane(gridWrapper);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("content-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        // Bottom button bar
        HBox buttonBar = new HBox(10);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.setPadding(new Insets(8, 0, 0, 0));

        insertButton = new Button("Insert");
        insertButton.getStyleClass().add("primary-button");
        FontIcon insertIcon = new FontIcon(FontAwesomeSolid.PLUS_CIRCLE);
        insertIcon.setIconSize(14);
        insertButton.setGraphic(insertIcon);
        insertButton.setDisable(true);
        insertButton.setOnAction(e -> {
            dialog.setResult(selectedItem);
            dialog.close();
        });

        Button cancelButton = new Button("Cancel");
        cancelButton.getStyleClass().add("secondary-button");
        FontIcon cancelIcon = new FontIcon(FontAwesomeSolid.TIMES);
        cancelIcon.setIconSize(14);
        cancelButton.setGraphic(cancelIcon);
        cancelButton.setOnAction(e -> {
            dialog.setResult(null);
            dialog.close();
        });

        buttonBar.getChildren().addAll(cancelButton, insertButton);

        root.getChildren().addAll(tagFilterPane, new Separator(), scroll, buttonBar);
        dialogPane.setContent(root);

        // Populate grid
        populateGrid(allImages);

        // Result converter — only return a selection for non-CANCEL button types
        dialog.setResultConverter(bt -> bt == ButtonType.CANCEL ? null : selectedItem);
    }

    /**
     * Shows the dialog and returns the selected ImageLibraryItem, or null if cancelled.
     */
    public Optional<ImageLibraryItem> showAndWait() {
        return dialog.showAndWait();
    }

    // ── Tag filter chips ─────────────────────────────────────────────────────

    private void buildTagFilters(List<ImageLibraryItem> allImages) {
        // Collect all unique tags in order of frequency
        Map<String, Long> tagCounts = allImages.stream()
                .flatMap(img -> img.getTags().stream())
                .collect(Collectors.groupingBy(t -> t, LinkedHashMap::new, Collectors.counting()));

        if (tagCounts.isEmpty()) {
            Label noTags = new Label("No tags defined yet.");
            noTags.getStyleClass().add("help-label");
            tagFilterPane.getChildren().add(noTags);
            return;
        }

        Label filterLabel = new Label("Filter:");
        filterLabel.getStyleClass().add("help-label");
        filterLabel.setPadding(new Insets(2, 0, 0, 0));
        tagFilterPane.getChildren().add(filterLabel);

        Button allBtn = new Button("All");
        allBtn.getStyleClass().addAll("tag-btn", "image-picker-tag-active");
        allBtn.setOnAction(e -> {
            activeTagFilter = null;
            refreshTagStyles();
            populateGrid(ImageLibraryStore.getInstance().getAll());
        });
        tagFilterPane.getChildren().add(allBtn);

        for (String tag : tagCounts.keySet()) {
            Button tagBtn = new Button(tag + " (" + tagCounts.get(tag) + ")");
            tagBtn.getStyleClass().add("tag-btn");
            tagBtn.setUserData(tag);
            tagBtn.setOnAction(e -> {
                if (tag.equals(activeTagFilter)) {
                    // Toggle off
                    activeTagFilter = null;
                    populateGrid(ImageLibraryStore.getInstance().getAll());
                } else {
                    activeTagFilter = tag;
                    populateGrid(ImageLibraryStore.getInstance().findByTag(tag));
                }
                refreshTagStyles();
            });
            tagFilterPane.getChildren().add(tagBtn);
        }
    }

    private void refreshTagStyles() {
        for (Node node : tagFilterPane.getChildren()) {
            if (!(node instanceof Button btn)) continue;
            btn.getStyleClass().remove("image-picker-tag-active");

            Object data = btn.getUserData();
            if (data == null && activeTagFilter == null) {
                // "All" button
                btn.getStyleClass().add("image-picker-tag-active");
            } else if (data != null && data.equals(activeTagFilter)) {
                btn.getStyleClass().add("image-picker-tag-active");
            }
        }
    }

    // ── Image grid ───────────────────────────────────────────────────────────

    private void populateGrid(List<ImageLibraryItem> images) {
        imageGrid.getChildren().clear();
        selectedItem = null;
        selectedCard = null;
        if (insertButton != null) insertButton.setDisable(true);

        if (images.isEmpty()) {
            emptyLabel.setVisible(true);
            emptyLabel.setManaged(true);
            return;
        }
        emptyLabel.setVisible(false);
        emptyLabel.setManaged(false);

        for (ImageLibraryItem img : images) {
            imageGrid.getChildren().add(buildImageCard(img));
        }
    }

    private VBox buildImageCard(ImageLibraryItem item) {
        VBox card = new VBox(4);
        card.getStyleClass().add("image-library-card");
        card.setPrefWidth(CARD_WIDTH);
        card.setPadding(new Insets(8));
        card.setCursor(Cursor.HAND);
        card.setAlignment(Pos.TOP_CENTER);

        // Thumbnail
        ImageView thumb = new ImageView();
        thumb.setFitWidth(CARD_IMAGE_WIDTH);
        thumb.setFitHeight(CARD_IMAGE_HEIGHT);
        thumb.setPreserveRatio(true);
        try {
            String url = item.getThumbnailUrl() != null ? item.getThumbnailUrl() : item.getPublicUrl();
            if (url != null && !url.isBlank()) {
                thumb.setImage(new Image(url, CARD_IMAGE_WIDTH, CARD_IMAGE_HEIGHT, true, true, true));
            }
        } catch (Exception e) {
            log.debug("Could not load thumbnail for {}: {}", item.getFileName(), e.getMessage());
        }

        // File name
        Label nameLabel = new Label(item.getFileName());
        nameLabel.getStyleClass().add("image-card-name");
        nameLabel.setMaxWidth(CARD_IMAGE_WIDTH);
        nameLabel.setWrapText(true);
        nameLabel.setAlignment(Pos.CENTER);

        card.getChildren().addAll(thumb, nameLabel);

        // Tag pills (max 3)
        if (!item.getTags().isEmpty()) {
            FlowPane tagsBox = new FlowPane(4, 2);
            tagsBox.setAlignment(Pos.CENTER);
            tagsBox.setMaxWidth(CARD_IMAGE_WIDTH);
            int count = 0;
            for (String tag : item.getTags()) {
                Label tagLabel = new Label(tag);
                tagLabel.getStyleClass().add("image-tag");
                tagsBox.getChildren().add(tagLabel);
                if (++count >= 3) break;
            }
            card.getChildren().add(tagsBox);
        }

        // Single click = select
        card.setOnMouseClicked(e -> {
            selectCard(card, item);
            // Double-click = confirm
            if (e.getClickCount() == 2) {
                dialog.setResult(item);
                dialog.close();
            }
        });

        return card;
    }

    private void selectCard(VBox card, ImageLibraryItem item) {
        // Deselect previous
        if (selectedCard != null) {
            selectedCard.getStyleClass().remove("image-picker-card-selected");
        }
        // Select new
        selectedCard = card;
        selectedItem = item;
        card.getStyleClass().add("image-picker-card-selected");
        insertButton.setDisable(false);
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private static VBox buildMessagePane(String message) {
        VBox pane = new VBox(12);
        pane.setAlignment(Pos.CENTER);
        pane.setPadding(new Insets(40));
        Label msgLabel = new Label(message);
        msgLabel.getStyleClass().add("help-label");
        msgLabel.setWrapText(true);
        msgLabel.setMaxWidth(400);
        msgLabel.setAlignment(Pos.CENTER);
        pane.getChildren().add(msgLabel);
        return pane;
    }
}
