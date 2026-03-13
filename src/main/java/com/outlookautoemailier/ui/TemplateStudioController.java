package com.outlookautoemailier.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.outlookautoemailier.AppContext;
import com.outlookautoemailier.integration.GeminiEmailAgent;
import com.outlookautoemailier.model.ImageLibraryItem;
import com.outlookautoemailier.model.ImageLibraryStore;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;

public class TemplateStudioController implements Initializable {

    private static final Logger log = LoggerFactory.getLogger(TemplateStudioController.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @FXML private TextField   templateNameField;
    @FXML private WebView     editorView;
    @FXML private ComboBox<String> fontFamilyBox;
    @FXML private TextField   fontSizeField;
    @FXML private ColorPicker textColorPicker;

    // ── AI panel ──────────────────────────────────────────────────────────────
    @FXML private VBox      aiPanel;
    @FXML private TextArea  aiPromptArea;
    @FXML private Label     aiStatusLabel;
    @FXML private Button    aiToggleBtn;
    @FXML private Button    aiGenerateBtn;

    // ── Template library panel ────────────────────────────────────────────────
    @FXML private VBox      templateLibraryPanel;
    @FXML private VBox      templateCardList;
    @FXML private Button    btnToggleLibrary;

    private static final String EDITOR_HTML = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="UTF-8">
            <style>
              * { box-sizing: border-box; }
              body { margin: 0; font-family: Arial, sans-serif; background: #fafafa; }
              #editor {
                min-height: 380px;
                padding: 16px;
                background: #fff;
                outline: none;
                font-size: 14px;
                line-height: 1.6;
                border: 1px solid #ddd;
                position: relative;
              }
              #editor h1 { font-size: 28px; margin: 8px 0; }
              #editor h2 { font-size: 22px; margin: 6px 0; }
              #editor h3 { font-size: 18px; margin: 4px 0; }
              #editor hr { border: none; border-top: 2px solid #e0e0e0; margin: 12px 0; }
              img.resizable { cursor: default; }
              img.resizable.selected { outline: 2px solid #0078d4; }
              #ctxMenu {
                display: none;
                position: fixed;
                background: #fff;
                border: 1px solid #ccc;
                border-radius: 4px;
                box-shadow: 2px 4px 10px rgba(0,0,0,.18);
                z-index: 9999;
                min-width: 190px;
              }
              #ctxMenu div {
                padding: 8px 16px;
                cursor: pointer;
                font-size: 13px;
                font-family: Arial, sans-serif;
              }
              #ctxMenu div:hover { background: #f0f0f0; }
              #ctxMenu .ctx-sep { height: 1px; background: #e0e0e0; margin: 4px 0; padding: 0; cursor: default; }
              #ctxMenu .ctx-sep:hover { background: #e0e0e0; }
              .resize-handle {
                width: 10px; height: 10px;
                background: #0078d4;
                position: fixed;
                cursor: se-resize;
                border-radius: 2px;
                z-index: 9998;
              }
            </style>
            </head>
            <body>
            <div id="editor" contenteditable="true">
              <p>Start writing your email here\u2026</p>
            </div>

            <div id="ctxMenu">
              <div id="ctxStyle">\u270f\ufe0f Add / Edit Inline Style</div>
              <div id="ctxLink">\ud83d\udd17 Wrap in Link</div>
              <div class="ctx-sep"></div>
              <div id="ctxRemove">\ud83d\uddd1\ufe0f Remove Element</div>
            </div>

            <script>
            // ── Context-menu ───────────────────────────────────────────────────
            var ctxMenu   = document.getElementById('ctxMenu');
            var ctxTarget = null;

            document.getElementById('editor').addEventListener('contextmenu', function(e) {
              e.preventDefault();
              var el = e.target;
              if (!el || el.id === 'editor' || el === document.body) {
                var sel = window.getSelection();
                if (sel && sel.rangeCount > 0) {
                  el = sel.getRangeAt(0).commonAncestorContainer;
                  if (el.nodeType === 3) el = el.parentElement;
                }
              }
              if (!el || el.id === 'editor' || el === document.body) return;
              ctxTarget = el;
              ctxMenu.style.display = 'block';
              ctxMenu.style.left = e.clientX + 'px';
              ctxMenu.style.top  = e.clientY + 'px';
            });

            document.addEventListener('click', function() { ctxMenu.style.display = 'none'; });

            document.getElementById('ctxStyle').addEventListener('click', function() {
              if (!ctxTarget) return;
              var current = ctxTarget.getAttribute('style') || '';
              var css = prompt('Enter inline CSS (e.g. color:red; font-size:18px;):', current);
              if (css !== null) ctxTarget.setAttribute('style', css);
              ctxMenu.style.display = 'none';
            });

            document.getElementById('ctxLink').addEventListener('click', function() {
              if (!ctxTarget) return;
              var href = prompt('Enter URL:', 'https://');
              if (href) {
                if (ctxTarget.tagName === 'A') {
                  ctxTarget.href = href;
                } else {
                  var a = document.createElement('a');
                  a.href = href;
                  ctxTarget.parentNode.insertBefore(a, ctxTarget);
                  a.appendChild(ctxTarget);
                }
              }
              ctxMenu.style.display = 'none';
            });

            document.getElementById('ctxRemove').addEventListener('click', function() {
              if (ctxTarget && ctxTarget.parentNode) ctxTarget.parentNode.removeChild(ctxTarget);
              ctxMenu.style.display = 'none';
            });

            // ── Image resize ──────────────────────────────────────────────────
            var selectedImg = null;
            var handle = null;
            var startX, startY, startW, startH;

            function removeHandle() {
              if (handle && handle.parentNode) handle.parentNode.removeChild(handle);
              handle = null;
              if (selectedImg) selectedImg.classList.remove('selected');
              selectedImg = null;
            }

            function attachHandle(img) {
              removeHandle();
              selectedImg = img;
              img.classList.add('selected');
              handle = document.createElement('div');
              handle.className = 'resize-handle';
              positionHandle(img);
              document.body.appendChild(handle);
              handle.addEventListener('mousedown', function(e) {
                e.preventDefault(); e.stopPropagation();
                startX = e.clientX; startY = e.clientY;
                startW = img.offsetWidth; startH = img.offsetHeight;
                document.addEventListener('mousemove', onDrag);
                document.addEventListener('mouseup', onDragEnd);
              });
            }

            function positionHandle(img) {
              if (!handle) return;
              var r = img.getBoundingClientRect();
              handle.style.left = (r.right - 6) + 'px';
              handle.style.top  = (r.bottom - 6) + 'px';
            }

            function onDrag(e) {
              var newW = Math.max(20, startW + (e.clientX - startX));
              selectedImg.style.width  = newW + 'px';
              selectedImg.style.height = Math.round(newW * startH / startW) + 'px';
              positionHandle(selectedImg);
            }

            function onDragEnd() {
              document.removeEventListener('mousemove', onDrag);
              document.removeEventListener('mouseup', onDragEnd);
            }

            document.getElementById('editor').addEventListener('click', function(e) {
              if (e.target.tagName === 'IMG') { attachHandle(e.target); }
              else { removeHandle(); }
            });

            document.addEventListener('scroll', function() {
              if (selectedImg) positionHandle(selectedImg);
            }, true);

            // ── Font / size / color helpers ────────────────────────────────────
            function applyFontFamily(name) {
              document.execCommand('fontName', false, name);
            }

            function applyFontSize(px) {
              var sel = window.getSelection();
              if (!sel || !sel.rangeCount || sel.isCollapsed) return;
              var range = sel.getRangeAt(0);
              var span = document.createElement('span');
              span.style.fontSize = px + 'px';
              try {
                range.surroundContents(span);
              } catch(e) {
                document.execCommand('insertHTML', false,
                  '<span style="font-size:' + px + 'px">' + sel.toString() + '</span>');
              }
            }

            function applyForeColor(hex) {
              document.execCommand('foreColor', false, hex);
            }
            </script>
            </body>
            </html>
            """;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        WebEngine engine = editorView.getEngine();
        engine.setJavaScriptEnabled(true);
        engine.loadContent(EDITOR_HTML);

        // Make window.prompt() work for the context-menu style dialog
        engine.setPromptHandler(pd -> {
            TextInputDialog dlg = new TextInputDialog(pd.getDefaultValue());
            dlg.setTitle("Style Editor");
            dlg.setHeaderText(pd.getMessage());
            dlg.setContentText(null);
            return dlg.showAndWait().orElse(null);
        });

        // Font family list
        fontFamilyBox.getItems().addAll(
            "Arial", "Georgia", "Times New Roman", "Courier New", "Verdana",
            "Trebuchet MS", "Tahoma", "Impact", "Comic Sans MS", "Palatino Linotype"
        );
        fontFamilyBox.setValue("Arial");
        fontFamilyBox.setOnAction(e -> {
            String font = fontFamilyBox.getValue();
            if (font != null) {
                editorView.getEngine().executeScript(
                    "applyFontFamily(" + jsStr(font) + ")");
            }
        });

        // Color picker
        textColorPicker.setValue(Color.BLACK);
        textColorPicker.setOnAction(e -> {
            Color c = textColorPicker.getValue();
            String hex = String.format("#%02x%02x%02x",
                (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255));
            editorView.getEngine().executeScript("applyForeColor(" + jsStr(hex) + ")");
        });

        // Populate template library on startup
        refreshTemplateLibrary();

        AppContext.get().setTemplateStudioController(this);
    }

    // ── Template library panel ────────────────────────────────────────────────

    @FXML
    private void onToggleLibrary() {
        boolean nowVisible = !templateLibraryPanel.isVisible();
        templateLibraryPanel.setVisible(nowVisible);
        templateLibraryPanel.setManaged(nowVisible);
        if (nowVisible) {
            refreshTemplateLibrary();
        }
    }

    /**
     * Scans the templates directory and populates the library panel with clickable cards.
     */
    public void refreshTemplateLibrary() {
        templateCardList.getChildren().clear();

        Path templatesDir = Path.of(System.getProperty("user.home"), ".outlookautoemailier", "templates");
        if (!Files.isDirectory(templatesDir)) {
            Label emptyLabel = new Label("No templates saved yet.");
            emptyLabel.getStyleClass().add("help-label");
            emptyLabel.setWrapText(true);
            templateCardList.getChildren().add(emptyLabel);
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(templatesDir, "*.json")) {
            boolean hasTemplates = false;
            for (Path file : stream) {
                hasTemplates = true;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, String> data = objectMapper.readValue(file.toFile(), Map.class);
                    String name = data.getOrDefault("name", file.getFileName().toString());
                    String subject = data.getOrDefault("subject", "");

                    VBox card = buildTemplateCard(name, subject, file);
                    templateCardList.getChildren().add(card);
                } catch (Exception ex) {
                    log.warn("Could not parse template file: {}", file.getFileName(), ex);
                }
            }

            if (!hasTemplates) {
                Label emptyLabel = new Label("No templates saved yet.");
                emptyLabel.getStyleClass().add("help-label");
                emptyLabel.setWrapText(true);
                templateCardList.getChildren().add(emptyLabel);
            }
        } catch (Exception ex) {
            log.error("Failed to scan templates directory", ex);
        }
    }

    private VBox buildTemplateCard(String name, String subject, Path filePath) {
        VBox card = new VBox(4);
        card.getStyleClass().add("template-card");

        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("template-card-name");
        nameLabel.setMaxWidth(Double.MAX_VALUE);

        card.getChildren().add(nameLabel);

        if (subject != null && !subject.isBlank()) {
            String preview = subject.length() > 40 ? subject.substring(0, 40) + "..." : subject;
            Label subjectLabel = new Label(preview);
            subjectLabel.getStyleClass().add("template-card-subject");
            subjectLabel.setMaxWidth(Double.MAX_VALUE);
            card.getChildren().add(subjectLabel);
        }

        card.setOnMouseClicked(e -> loadTemplateFromCard(filePath));
        return card;
    }

    /**
     * Loads a template from the given file path into the editor fields.
     */
    private void loadTemplateFromCard(Path filePath) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> data = objectMapper.readValue(filePath.toFile(), Map.class);
            templateNameField.setText(data.getOrDefault("name", ""));
            String body = data.getOrDefault("body", "");
            editorView.getEngine().executeScript(
                "document.getElementById('editor').innerHTML = " + jsStr(body));
            log.info("Loaded template from library: {}", filePath.getFileName());
        } catch (Exception ex) {
            showAlert("Load Failed", "Could not read template: " + ex.getMessage());
            log.error("Failed to load template from card: {}", filePath, ex);
        }
    }

    // ── Formatting commands ───────────────────────────────────────────────────

    @FXML private void onBold()      { exec("bold"); }
    @FXML private void onItalic()    { exec("italic"); }
    @FXML private void onUnderline() { exec("underline"); }
    @FXML private void onUndo()      { exec("undo"); }
    @FXML private void onRedo()      { exec("redo"); }
    @FXML private void onH1()        { execFormatBlock("h1"); }
    @FXML private void onH2()        { execFormatBlock("h2"); }
    @FXML private void onH3()        { execFormatBlock("h3"); }

    @FXML
    private void onApplyFontSize() {
        String raw = fontSizeField.getText().trim();
        if (raw.isEmpty()) return;
        try {
            int px = Integer.parseInt(raw.replaceAll("[^0-9]", ""));
            if (px > 0) {
                editorView.getEngine().executeScript("applyFontSize(" + px + ")");
            }
        } catch (NumberFormatException ignored) {}
    }

    @FXML
    private void onInsertDivider() {
        editorView.getEngine().executeScript(
            "document.execCommand('insertHTML', false, '<hr/><p><br></p>')");
    }

    @FXML
    private void onInsertUl() { exec("insertUnorderedList"); }

    @FXML
    private void onClear() {
        editorView.getEngine().executeScript(
            "document.getElementById('editor').innerHTML = '<p>Start writing your email here\u2026</p>'");
    }

    @FXML
    private void onInsertButton() {
        Dialog<ButtonResult> dlg = new Dialog<>();
        dlg.setTitle("Insert Link Button");
        dlg.setHeaderText(null);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        TextField textField = new TextField("Click Here");
        TextField urlField  = new TextField("https://");
        grid.add(new Label("Button text:"), 0, 0);
        grid.add(textField, 1, 0);
        grid.add(new Label("URL:"), 0, 1);
        grid.add(urlField, 1, 1);

        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.setResultConverter(bt ->
            bt == ButtonType.OK ? new ButtonResult(textField.getText(), urlField.getText()) : null);

        Optional<ButtonResult> result = dlg.showAndWait();
        result.ifPresent(r -> {
            String html = "<a href='" + escapeAttr(r.url) + "'>"
                        + escapeHtml(r.text) + "</a>&nbsp;";
            editorView.getEngine().executeScript(
                "document.execCommand('insertHTML', false, " + jsStr(html) + ")");
        });
    }

    @FXML
    private void onInsertImage(ActionEvent event) {
        ContextMenu menu = new ContextMenu();

        MenuItem fromLibrary = new MenuItem("From Image Library");
        fromLibrary.setOnAction(e -> insertImageFromLibrary());

        MenuItem fromComputer = new MenuItem("From Computer");
        fromComputer.setOnAction(e -> insertImageFromComputer());

        menu.getItems().addAll(fromLibrary, new SeparatorMenuItem(), fromComputer);

        // Anchor the context menu below the Image toolbar button
        if (event.getSource() instanceof javafx.scene.Node btn) {
            javafx.geometry.Bounds screenBounds = btn.localToScreen(btn.getBoundsInLocal());
            if (screenBounds != null) {
                menu.show(btn, screenBounds.getMinX(), screenBounds.getMaxY());
                return;
            }
        }
        menu.show(getStage());
    }

    /**
     * Opens the Image Library picker dialog and inserts the selected image
     * using its hosted Drive public URL.
     */
    private void insertImageFromLibrary() {
        ImageLibraryPickerDialog picker = new ImageLibraryPickerDialog(getStage());
        Optional<ImageLibraryItem> result = picker.showAndWait();
        result.ifPresent(item -> {
            String url = item.getPublicUrl();
            if (url == null || url.isBlank()) {
                showAlert("No URL", "This image does not have a public URL. "
                        + "Re-upload it in the Image Library tab.");
                return;
            }
            String alt = escapeAttr(item.getFileName());
            String html = "<img src='" + escapeAttr(url) + "' alt='" + alt
                        + "' class='resizable' style='max-width:100%;height:auto;display:block;'/>&#8203;";
            editorView.getEngine().executeScript(
                "document.execCommand('insertHTML', false, " + jsStr(html) + ")");
            log.info("Inserted image from library: {} ({})", item.getFileName(), url);
        });
    }

    /**
     * Opens a FileChooser to insert a local image as base64 (original behaviour).
     */
    private void insertImageFromComputer() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Insert Image");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp"));
        File file = chooser.showOpenDialog(getStage());
        if (file == null) return;
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String mime  = guessMime(file.getName());
            String b64   = Base64.getEncoder().encodeToString(bytes);
            String html  = "<img src='data:" + mime + ";base64," + b64
                         + "' class='resizable' style='max-width:100%;height:auto;'/>&#8203;";
            editorView.getEngine().executeScript(
                "document.execCommand('insertHTML', false, " + jsStr(html) + ")");
        } catch (Exception ex) {
            showAlert("Image Error", ex.getMessage());
        }
    }

    @FXML
    private void onInsertVar(ActionEvent e) {
        if (!(e.getSource() instanceof Button btn)) return;
        String token = (String) btn.getUserData();
        editorView.getEngine().executeScript(
            "document.execCommand('insertText', false, " + jsStr(token) + ")");
    }

    @FXML
    private void onGetHtml() {
        String html = getEditorHtml();
        TextArea area = new TextArea(html);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(15);
        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle("Generated HTML");
        dlg.getDialogPane().setContent(area);
        dlg.getDialogPane().setPrefWidth(700);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dlg.showAndWait();
    }

    @FXML
    private void onUseInCompose() {
        String html = getEditorHtml();
        ComposeController compose = AppContext.get().getComposeController();
        if (compose == null) {
            showAlert("Not Ready", "Open the Compose pane at least once first.");
            return;
        }
        compose.setHtmlBody(html);
        MainController main = AppContext.get().getMainController();
        if (main != null) main.navigateToCompose();
    }

    @FXML
    private void onSaveTemplate() {
        String name = templateNameField.getText().trim();
        if (name.isEmpty()) {
            showAlert("Name Required", "Please enter a template name before saving.");
            return;
        }
        try {
            Path dir  = Path.of(System.getProperty("user.home"), ".outlookautoemailier", "templates");
            Files.createDirectories(dir);
            Path file = dir.resolve(name.replaceAll("[^a-zA-Z0-9._-]", "_") + ".json");

            Map<String, String> data = new LinkedHashMap<>();
            data.put("name", name);
            data.put("subject", "");
            data.put("body", getEditorHtml());
            data.put("html", "true");
            data.put("savedAt", LocalDateTime.now().toString());

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
            showAlert("Saved", "Template \"" + name + "\" saved to:\n" + file);

            // Refresh the library panel so the new template appears immediately
            refreshTemplateLibrary();
        } catch (Exception ex) {
            showAlert("Save Failed", ex.getMessage());
        }
    }

    @FXML
    private void onLoadTemplate() {
        Path dir = Path.of(System.getProperty("user.home"), ".outlookautoemailier", "templates");
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Load Email Template");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Templates", "*.json"));
        if (Files.isDirectory(dir)) chooser.setInitialDirectory(dir.toFile());

        File selected = chooser.showOpenDialog(getStage());
        if (selected == null) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> data = objectMapper.readValue(selected, Map.class);
            templateNameField.setText(data.getOrDefault("name", ""));
            String body = data.getOrDefault("body", "");
            editorView.getEngine().executeScript(
                "document.getElementById('editor').innerHTML = " + jsStr(body));
        } catch (Exception ex) {
            showAlert("Load Failed", "Could not read template: " + ex.getMessage());
        }
    }

    // ── AI panel ──────────────────────────────────────────────────────────────

    @FXML
    private void onToggleAiPanel() {
        boolean nowVisible = !aiPanel.isVisible();
        aiPanel.setVisible(nowVisible);
        aiPanel.setManaged(nowVisible);
        aiToggleBtn.setText(nowVisible ? "Close AI" : "AI Assist");
    }

    @FXML
    private void onAiGenerate() {
        String prompt = aiPromptArea.getText().trim();
        if (prompt.isEmpty()) {
            aiStatusLabel.setText("Please describe the email you want.");
            return;
        }
        aiGenerateBtn.setDisable(true);
        aiStatusLabel.setText("Generating with Gemini\u2026");

        String imageContext = ImageLibraryStore.getInstance().buildGeminiContext();
        GeminiEmailAgent.generateWithLibraryAsync(prompt, imageContext)
                .thenAccept(html -> Platform.runLater(() -> {
                    setEditorHtml(html);
                    aiStatusLabel.setText("Done! Template applied to editor.");
                    aiGenerateBtn.setDisable(false);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        aiStatusLabel.setText("Error: " + msg);
                        aiGenerateBtn.setDisable(false);
                        log.warn("Gemini generation failed", ex);
                    });
                    return null;
                });
    }

    /** Replaces the entire editor content with the given HTML. */
    public void setEditorHtml(String html) {
        editorView.getEngine().executeScript(
                "document.getElementById('editor').innerHTML = " + jsStr(html));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String getEditorHtml() {
        Object result = editorView.getEngine().executeScript(
            "document.getElementById('editor').innerHTML");
        return result != null ? result.toString() : "";
    }

    private void exec(String command) {
        editorView.getEngine().executeScript(
            "document.execCommand('" + command + "', false, null)");
    }

    private void execFormatBlock(String tag) {
        editorView.getEngine().executeScript(
            "document.execCommand('formatBlock', false, '<" + tag + ">')");
    }

    private Stage getStage() {
        return (Stage) editorView.getScene().getWindow();
    }

    private static String jsStr(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'")
                      .replace("\n", "\\n").replace("\r", "") + "'";
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("'", "&#39;").replace("\"", "&quot;");
    }

    private static String guessMime(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private static void showAlert(String title, String message) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(message);
        a.showAndWait();
    }

    private record ButtonResult(String text, String url) {}
}
