package com.outlookautoemailier.ui;

import com.outlookautoemailier.AppContext;
import com.outlookautoemailier.integration.GoogleDriveService;
import com.outlookautoemailier.model.ImageLibraryItem;
import com.outlookautoemailier.model.ImageLibraryStore;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for the Image Library screen.
 *
 * <p>Allows the user to connect Google Drive, upload images, tag them,
 * add notes, and manage images that can be used by Gemini AI when
 * generating HTML email templates.
 */
public class ImageLibraryController implements Initializable {

    private static final Logger log = LoggerFactory.getLogger(ImageLibraryController.class);

    // ── FXML fields ──────────────────────────────────────────────────────────

    @FXML private Button    connectDriveBtn;
    @FXML private Label     driveStatusLabel;
    @FXML private Button    uploadBtn;
    @FXML private FlowPane  imageGrid;
    @FXML private VBox      detailPanel;
    @FXML private ImageView detailPreview;
    @FXML private Label     detailFileName;
    @FXML private Label     detailUrl;
    @FXML private TextField tagField;
    @FXML private FlowPane  tagDisplay;
    @FXML private TextArea  notesArea;

    /** The currently selected image item shown in the detail panel. */
    private ImageLibraryItem selectedItem;

    // ── Initializable ────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        AppContext.get().setImageLibraryController(this);

        // Check if Drive is already authenticated from a previous session
        GoogleDriveService drive = GoogleDriveService.getInstance();
        if (drive.tryRestoreSession()) {
            onDriveConnected();
        } else {
            driveStatusLabel.setText("Not connected to Google Drive");
            uploadBtn.setDisable(true);
        }
    }

    // ── Drive connection ─────────────────────────────────────────────────────

    @FXML
    private void onConnectDrive() {
        connectDriveBtn.setDisable(true);
        driveStatusLabel.setText("Opening browser for Google sign-in...");

        CompletableFuture.runAsync(() -> {
            try {
                GoogleDriveService.getInstance().authenticate();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).thenRun(() -> Platform.runLater(this::onDriveConnected))
          .exceptionally(ex -> {
              Platform.runLater(() -> {
                  String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                  driveStatusLabel.setText("Connection failed: " + msg);
                  connectDriveBtn.setDisable(false);
              });
              log.error("Google Drive connection failed", ex);
              return null;
          });
    }

    private void onDriveConnected() {
        driveStatusLabel.setText("Connected to Google Drive");
        driveStatusLabel.getStyleClass().setAll("label", "status-connected");
        connectDriveBtn.setVisible(false);
        connectDriveBtn.setManaged(false);
        uploadBtn.setDisable(false);
        refreshImageGrid();
    }

    // ── Upload ───────────────────────────────────────────────────────────────

    @FXML
    private void onUpload() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Image to Upload");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Images",
                        "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.svg"));
        File file = chooser.showOpenDialog(uploadBtn.getScene().getWindow());
        if (file == null) return;

        driveStatusLabel.setText("Uploading " + file.getName() + "...");
        uploadBtn.setDisable(true);

        CompletableFuture.supplyAsync(() -> {
            try {
                byte[] data = Files.readAllBytes(file.toPath());
                String mimeType = inferMimeType(file.getName());
                GoogleDriveService drive = GoogleDriveService.getInstance();
                String fileId = drive.uploadFile(data, file.getName(), mimeType);
                String publicUrl = GoogleDriveService.getDirectUrl(fileId);

                ImageLibraryItem item = new ImageLibraryItem(file.getName(), fileId, publicUrl);
                ImageLibraryStore.getInstance().addImage(item);
                return item;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).thenAccept(item -> Platform.runLater(() -> {
            driveStatusLabel.setText("Uploaded: " + item.getFileName());
            uploadBtn.setDisable(false);
            refreshImageGrid();
        })).exceptionally(ex -> {
            Platform.runLater(() -> {
                String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                driveStatusLabel.setText("Upload failed: " + msg);
                uploadBtn.setDisable(false);
            });
            log.error("Image upload failed", ex);
            return null;
        });
    }

    // ── Refresh ──────────────────────────────────────────────────────────────

    @FXML
    private void onRefresh() {
        refreshImageGrid();
    }

    private void refreshImageGrid() {
        imageGrid.getChildren().clear();
        List<ImageLibraryItem> images = ImageLibraryStore.getInstance().getAll();

        if (images.isEmpty()) {
            Label emptyLabel = new Label("No images yet. Upload an image to get started.");
            emptyLabel.getStyleClass().add("help-label");
            emptyLabel.setWrapText(true);
            imageGrid.getChildren().add(emptyLabel);
            return;
        }

        for (ImageLibraryItem img : images) {
            imageGrid.getChildren().add(createImageCard(img));
        }
    }

    // ── Image card builder ───────────────────────────────────────────────────

    /**
     * Creates a clickable card for an image in the grid.
     */
    private Node createImageCard(ImageLibraryItem item) {
        VBox card = new VBox(6);
        card.getStyleClass().add("image-library-card");
        card.setPrefWidth(160);
        card.setPadding(new Insets(10));
        card.setCursor(Cursor.HAND);

        // Thumbnail
        ImageView thumb = new ImageView();
        thumb.setFitWidth(140);
        thumb.setFitHeight(100);
        thumb.setPreserveRatio(true);
        try {
            String url = item.getThumbnailUrl() != null ? item.getThumbnailUrl() : item.getPublicUrl();
            if (url != null && !url.isBlank()) {
                thumb.setImage(new Image(url, 140, 100, true, true, true));
            }
        } catch (Exception e) {
            log.debug("Could not load thumbnail for {}: {}", item.getFileName(), e.getMessage());
        }

        Label nameLabel = new Label(item.getFileName());
        nameLabel.getStyleClass().add("image-card-name");
        nameLabel.setMaxWidth(140);
        nameLabel.setWrapText(true);

        card.getChildren().addAll(thumb, nameLabel);

        // Show tag pills if any
        if (!item.getTags().isEmpty()) {
            HBox tagsBox = new HBox(4);
            for (String tag : item.getTags()) {
                Label tagLabel = new Label(tag);
                tagLabel.getStyleClass().add("image-tag");
                tagsBox.getChildren().add(tagLabel);
                if (tagsBox.getChildren().size() >= 3) break; // limit display in card
            }
            card.getChildren().add(tagsBox);
        }

        card.setOnMouseClicked(e -> selectImage(item));
        return card;
    }

    // ── Detail panel ─────────────────────────────────────────────────────────

    private void selectImage(ImageLibraryItem item) {
        selectedItem = item;
        detailPanel.setVisible(true);
        detailPanel.setManaged(true);

        detailFileName.setText(item.getFileName());
        detailUrl.setText(item.getPublicUrl() != null ? item.getPublicUrl() : "No URL");
        notesArea.setText(item.getNotes() != null ? item.getNotes() : "");

        // Load preview image
        try {
            String url = item.getPublicUrl();
            if (url != null && !url.isBlank()) {
                detailPreview.setImage(new Image(url, 200, 160, true, true, true));
            } else {
                detailPreview.setImage(null);
            }
        } catch (Exception e) {
            detailPreview.setImage(null);
        }

        // Populate tags
        refreshTagDisplay();
    }

    private void refreshTagDisplay() {
        tagDisplay.getChildren().clear();
        if (selectedItem == null) return;

        for (String tag : selectedItem.getTags()) {
            HBox pill = new HBox(4);
            pill.getStyleClass().add("image-tag");

            Label tagLabel = new Label(tag);
            Button removeBtn = new Button("x");
            removeBtn.getStyleClass().add("image-tag-remove");
            removeBtn.setOnAction(e -> {
                selectedItem.removeTag(tag);
                ImageLibraryStore.getInstance().updateImage(selectedItem);
                refreshTagDisplay();
                refreshImageGrid();
            });

            pill.getChildren().addAll(tagLabel, removeBtn);
            tagDisplay.getChildren().add(pill);
        }
    }

    @FXML
    private void onAddTag() {
        if (selectedItem == null) return;
        String tag = tagField.getText().trim();
        if (tag.isEmpty()) return;

        selectedItem.addTag(tag);
        ImageLibraryStore.getInstance().updateImage(selectedItem);
        tagField.clear();
        refreshTagDisplay();
        refreshImageGrid();
    }

    @FXML
    private void onSaveDetails() {
        if (selectedItem == null) return;
        selectedItem.setNotes(notesArea.getText().trim());
        ImageLibraryStore.getInstance().updateImage(selectedItem);
        driveStatusLabel.setText("Changes saved for: " + selectedItem.getFileName());
    }

    @FXML
    private void onCopyUrl() {
        if (selectedItem == null || selectedItem.getPublicUrl() == null) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(selectedItem.getPublicUrl());
        Clipboard.getSystemClipboard().setContent(content);
        driveStatusLabel.setText("URL copied to clipboard.");
    }

    @FXML
    private void onDeleteImage() {
        if (selectedItem == null) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Image");
        confirm.setHeaderText(null);
        confirm.setContentText("Delete \"" + selectedItem.getFileName()
                + "\" from your library and Google Drive?");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

        String driveFileId = selectedItem.getDriveFileId();
        String imageId = selectedItem.getId();

        driveStatusLabel.setText("Deleting " + selectedItem.getFileName() + "...");

        CompletableFuture.runAsync(() -> {
            try {
                GoogleDriveService.getInstance().deleteFile(driveFileId);
            } catch (Exception e) {
                log.warn("Drive delete failed (continuing with local removal): {}", e.getMessage());
            }
            ImageLibraryStore.getInstance().removeImage(imageId);
        }).thenRun(() -> Platform.runLater(() -> {
            detailPanel.setVisible(false);
            detailPanel.setManaged(false);
            selectedItem = null;
            driveStatusLabel.setText("Image deleted.");
            refreshImageGrid();
        })).exceptionally(ex -> {
            Platform.runLater(() -> driveStatusLabel.setText("Delete failed: " + ex.getMessage()));
            return null;
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String inferMimeType(String fileName) {
        if (fileName == null) return "image/png";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg"))  return "image/svg+xml";
        return "image/png";
    }
}
