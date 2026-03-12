package com.outlookautoemailier.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.outlookautoemailier.AppContext;
import com.outlookautoemailier.model.Contact;
import com.outlookautoemailier.model.ContactGroup;
import com.outlookautoemailier.model.ContactGroupStore;
import com.outlookautoemailier.model.EmailJob;
import com.outlookautoemailier.model.EmailTemplate;
import com.outlookautoemailier.queue.EmailQueue;
import com.outlookautoemailier.security.SpamGuard;
import com.outlookautoemailier.ui.MainController;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.control.ToggleButton;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.ResourceBundle;

/**
 * Controller for {@code Compose.fxml}.
 *
 * <p>Handles:
 * <ul>
 *   <li>Template save/load (stub — Sprint 2 wires JSON serialisation).</li>
 *   <li>Personalisation variable insertion into subject/body fields.</li>
 *   <li>Per-contact preview dialog.</li>
 *   <li>Dispatching email jobs to the queue (stub — Sprint 2 wires EmailQueue).</li>
 * </ul>
 */
public class ComposeController implements Initializable {

    // ── Template controls ────────────────────────────────────────────────────
    @FXML private TextField templateNameField;

    // ── Campaign name ─────────────────────────────────────────────────────────
    @FXML private TextField campaignNameField;

    // ── Subject row ──────────────────────────────────────────────────────────
    @FXML private TextField subjectField;

    // ── Body area ────────────────────────────────────────────────────────────
    @FXML private TextArea bodyArea;

    // ── Footer ───────────────────────────────────────────────────────────────
    @FXML private Label recipientCountLabel;

    // ── HTML mode controls ────────────────────────────────────────────────────
    @FXML private ToggleButton htmlModeToggle;
    @FXML private WebView      htmlPreview;

    // ── Group selector ────────────────────────────────────────────────────────
    @FXML private ComboBox<ContactGroup> groupComboBox;

    // ── State ────────────────────────────────────────────────────────────────

    /** Contacts forwarded from the Contacts view. */
    private List<Contact> recipients = new ArrayList<>();

    /** Whether the body area is in HTML mode. */
    private boolean htmlMode = false;

    // ── Initializable ────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        AppContext.get().setComposeController(this);

        // Live HTML preview: update WebView whenever body text changes in HTML mode
        bodyArea.textProperty().addListener((obs, oldVal, newVal) -> {
            if (htmlMode && htmlPreview != null && htmlPreview.isVisible()) {
                htmlPreview.getEngine().loadContent(newVal != null ? newVal : "");
            }
        });

        // Populate group ComboBox
        refreshGroupCombo();
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Populates the recipient list and updates the footer counter.
     *
     * <p>Called by {@link ContactListController#onSendToSelected()} after the
     * user has checked contacts and clicked "Send to Selected".
     *
     * @param contacts the selected contacts; must not be {@code null}
     */
    public void setRecipients(List<Contact> contacts) {
        this.recipients = contacts != null
                ? Collections.unmodifiableList(new ArrayList<>(contacts))
                : Collections.emptyList();
        updateRecipientCountLabel();
    }

    /** @return an unmodifiable view of the current recipient list. */
    public List<Contact> getRecipients() {
        return Collections.unmodifiableList(recipients);
    }

    /**
     * Sets the body area to the given HTML string and activates HTML mode.
     * Called by {@link TemplateStudioController#onUseInCompose()} to transfer
     * the designed template directly into the Compose pane.
     *
     * @param html the HTML content to place in the body area
     */
    public void setHtmlBody(String html) {
        bodyArea.setText(html);
        if (htmlModeToggle != null) {
            htmlModeToggle.setSelected(true);
            htmlMode = true;
            onToggleHtmlMode();
        }
    }

    // ── FXML action handlers ─────────────────────────────────────────────────

    /**
     * Inserts a personalisation variable token into either the subject field
     * or the body area, depending on the {@code userData} attribute of the
     * clicked button.
     *
     * <p>The {@code userData} attribute value should be either {@code "subject"}
     * (inserts into {@link #subjectField}) or {@code "body"} (inserts into
     * {@link #bodyArea}).  The token text is the button's own label text.
     *
     * @param e the action event fired by one of the variable-insertion buttons
     */
    @FXML
    private void onInsertVariable(ActionEvent e) {
        if (!(e.getSource() instanceof Button btn)) return;

        String token  = btn.getText();           // e.g. "{{firstName}}"
        String target = (String) btn.getUserData(); // "subject" or "body"

        if ("subject".equals(target)) {
            insertIntoTextInput(subjectField, token);
        } else {
            insertIntoTextArea(bodyArea, token);
        }
    }

    @FXML
    private void onToggleHtmlMode() {
        htmlMode = htmlModeToggle.isSelected();
        if (htmlPreview != null) {
            htmlPreview.setVisible(htmlMode);
            htmlPreview.setManaged(htmlMode);
            if (htmlMode) {
                htmlPreview.getEngine().loadContent(
                        bodyArea.getText() != null ? bodyArea.getText() : "");
            }
        }
        bodyArea.setPromptText(htmlMode
                ? "Write HTML here. e.g. <h1>Hello {{firstName}}</h1><p>...</p>"
                : "Write your email body here. Use the buttons below to insert personalization variables.");
    }

    /**
     * Serialises the current subject + body to a JSON template file in the
     * user's home directory.
     *
     * <p>Writes to {@code ~/.outlookautoemailier/templates/<name>.json} using
     * Jackson's {@link ObjectMapper}. The directory is created if it does not
     * already exist.
     */
    @FXML
    private void onSaveTemplate() {
        String name = templateNameField.getText().trim();
        if (name.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "Template Name Required",
                    "Please enter a template name before saving.");
            return;
        }
        try {
            Path dir = Path.of(System.getProperty("user.home"), ".outlookautoemailier", "templates");
            Files.createDirectories(dir);
            Path file = dir.resolve(name.replaceAll("[^a-zA-Z0-9._-]", "_") + ".json");

            Map<String, String> data = new LinkedHashMap<>();
            data.put("name", name);
            data.put("subject", subjectField.getText());
            data.put("body", bodyArea.getText());
            data.put("html", String.valueOf(htmlMode));
            data.put("savedAt", LocalDateTime.now().toString());

            ObjectMapper mapper = new ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);

            showAlert(Alert.AlertType.INFORMATION, "Template Saved",
                    "Template \"" + name + "\" saved to:\n" + file);
        } catch (Exception ex) {
            showAlert(Alert.AlertType.ERROR, "Save Failed", ex.getMessage());
        }
    }

    /**
     * Opens a file chooser, reads a JSON template, and populates
     * subject/body fields.
     *
     * <p>Shows a {@link FileChooser} filtered to {@code *.json}, deserialises
     * the selected file using Jackson, and populates the template name, subject,
     * and body fields from the stored values.
     */
    @FXML
    private void onLoadTemplate() {
        Path dir = Path.of(System.getProperty("user.home"), ".outlookautoemailier", "templates");

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Load Email Template");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JSON Templates", "*.json"));
        if (Files.isDirectory(dir)) {
            chooser.setInitialDirectory(dir.toFile());
        }

        Stage stage = (Stage) subjectField.getScene().getWindow();
        File selected = chooser.showOpenDialog(stage);
        if (selected == null) return;

        try {
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, String> data = mapper.readValue(selected, Map.class);

            templateNameField.setText(data.getOrDefault("name", ""));
            subjectField.setText(data.getOrDefault("subject", ""));
            bodyArea.setText(data.getOrDefault("body", ""));
            boolean loadedHtml = Boolean.parseBoolean(data.getOrDefault("html", "false"));
            if (htmlModeToggle != null) {
                htmlModeToggle.setSelected(loadedHtml);
                htmlMode = loadedHtml;
                onToggleHtmlMode();
            }
        } catch (Exception ex) {
            showAlert(Alert.AlertType.ERROR, "Load Failed",
                    "Could not read template: " + ex.getMessage());
        }
    }

    /**
     * Shows a preview dialog with the first recipient's resolved subject and body.
     *
     * <p>TODO Sprint 2: replace the stub with a proper modal that renders the
     * full resolved template for the first (or a user-chosen) contact.
     */
    @FXML
    private void onPreview() {
        if (recipients.isEmpty()) {
            showAlert(Alert.AlertType.WARNING,
                    "No Recipients",
                    "Select at least one contact from the Contacts pane first.");
            return;
        }

        Contact first   = recipients.get(0);
        String subject  = resolveTemplate(subjectField.getText(), first);
        String body     = resolveTemplate(bodyArea.getText(), first);

        Alert preview = new Alert(Alert.AlertType.INFORMATION);
        preview.setTitle("Email Preview — " + first.getDisplayName());
        preview.setHeaderText("Subject: " + subject);
        preview.setContentText(body.length() > 800 ? body.substring(0, 800) + "…" : body);
        preview.getDialogPane().setPrefWidth(600);
        preview.showAndWait();
    }

    /**
     * Creates one {@code EmailJob} per recipient and submits them to the
     * {@code EmailQueue}.
     *
     * <p>Validates that recipients, subject, and body are all present, then runs
     * a {@link SpamGuard} content check with an optional user confirmation step
     * before enqueueing the jobs via {@link AppContext}.
     */
    @FXML
    private void onSendEmails() {
        if (recipients.isEmpty()) {
            showAlert(Alert.AlertType.WARNING,
                    "No Recipients",
                    "Select at least one contact from the Contacts pane first.");
            return;
        }
        if (subjectField.getText().isBlank()) {
            showAlert(Alert.AlertType.WARNING,
                    "Subject Required",
                    "Please enter a subject for the email.");
            return;
        }
        if (bodyArea.getText().isBlank()) {
            showAlert(Alert.AlertType.WARNING,
                    "Body Required",
                    "Please enter a message body.");
            return;
        }

        // Build EmailTemplate from current compose fields
        EmailTemplate template = EmailTemplate.builder()
                .name(templateNameField.getText().isBlank() ? "Untitled" : templateNameField.getText().trim())
                .subject(subjectField.getText())
                .body(bodyArea.getText())
                .html(htmlMode)
                .build();

        // Resolve campaign name (campaignNameField takes priority over template name)
        String campaignName = (campaignNameField != null && !campaignNameField.getText().isBlank())
                ? campaignNameField.getText().trim()
                : template.getName();

        // Create a batch record to group all jobs from this compose action
        String batchId = java.util.UUID.randomUUID().toString();
        com.outlookautoemailier.analytics.EmailBatch batch =
                new com.outlookautoemailier.analytics.EmailBatch(
                        batchId,
                        campaignName,
                        template.getSubject(),
                        java.time.LocalDateTime.now(),
                        recipients.size()
                );
        com.outlookautoemailier.analytics.BatchStore.getInstance().addBatch(batch);
        com.outlookautoemailier.integration.SupabaseAnalyticsSync.pushBatchAsync(batch);

        // Create one EmailJob per recipient, all sharing the same batchId
        List<EmailJob> jobs = recipients.stream()
                .map(contact -> EmailJob.builder()
                        .batchId(batchId)
                        .contact(contact)
                        .template(template)
                        .priority(5)
                        .maxAttempts(3)
                        .build())
                .collect(Collectors.toList());

        // Check content warnings via SpamGuard
        SpamGuard guard = new SpamGuard();
        List<String> warnings = guard.checkContent(subjectField.getText(), bodyArea.getText());
        if (!warnings.isEmpty()) {
            Alert warn = new Alert(Alert.AlertType.WARNING);
            warn.setTitle("Spam Guard Warning");
            warn.setHeaderText("The following issues were detected:");
            warn.setContentText(String.join("\n", warnings) + "\n\nProceed anyway?");
            warn.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            Optional<ButtonType> result = warn.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.OK) return;
        }

        // Enqueue jobs
        AppContext ctx = AppContext.get();
        EmailQueue queue = ctx.getEmailQueue();
        if (queue == null) {
            showAlert(Alert.AlertType.ERROR, "Queue Not Ready",
                    "The email queue is not initialised. Please connect your accounts first.");
            return;
        }
        queue.enqueueAll(jobs);

        // Navigate to the Queue tab automatically
        MainController mainCtrl = AppContext.get().getMainController();
        if (mainCtrl != null) {
            mainCtrl.navigateToQueue();
        }
    }

    // ── Group selector helpers ────────────────────────────────────────────────

    public void refreshGroupCombo() {
        if (groupComboBox == null) return;
        java.util.List<ContactGroup> groups = ContactGroupStore.getInstance().getAll();
        groupComboBox.setItems(javafx.collections.FXCollections.observableArrayList(groups));
        // Display only the group name
        groupComboBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(ContactGroup g) { return g == null ? "" : g.getName(); }
            @Override public ContactGroup fromString(String s) { return null; }
        });
    }

    @FXML
    private void onGroupSelected() {
        if (groupComboBox == null) return;
        ContactGroup selected = groupComboBox.getValue();
        if (selected == null) return;
        // Get full Contact objects by email
        ContactListController clc = AppContext.get().getContactListController();
        if (clc == null) {
            showAlert(Alert.AlertType.WARNING, "Contacts Not Loaded",
                    "Please load contacts first before selecting a group.");
            return;
        }
        java.util.Set<String> emails = new java.util.HashSet<>(selected.getContactEmails());
        java.util.List<com.outlookautoemailier.model.Contact> contacts = clc.getContactsByEmails(emails);
        if (contacts.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "Empty Group",
                    "No loaded contacts match the emails in group \"" + selected.getName() + "\".\n"
                  + "Make sure contacts are loaded first.");
            return;
        }
        setRecipients(contacts);
        showAlert(Alert.AlertType.INFORMATION, "Group Selected",
                selected.getName() + ": " + contacts.size() + " recipient(s) selected.");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Inserts {@code token} at the current caret position (or replaces the
     * selection) in a {@link TextField}.
     */
    private static void insertIntoTextInput(TextField field, String token) {
        IndexRange selection = field.getSelection();
        String current = field.getText() == null ? "" : field.getText();
        String updated = current.substring(0, selection.getStart())
                + token
                + current.substring(selection.getEnd());
        field.setText(updated);
        field.positionCaret(selection.getStart() + token.length());
        field.requestFocus();
    }

    /**
     * Inserts {@code token} at the current caret position (or replaces the
     * selection) in a {@link TextArea}.
     */
    private static void insertIntoTextArea(TextArea area, String token) {
        IndexRange selection = area.getSelection();
        String current = area.getText() == null ? "" : area.getText();
        String updated = current.substring(0, selection.getStart())
                + token
                + current.substring(selection.getEnd());
        area.setText(updated);
        area.positionCaret(selection.getStart() + token.length());
        area.requestFocus();
    }

    /**
     * Resolves personalisation variables in {@code template} against the
     * given {@link Contact}.
     *
     * @param template the raw template string (may be {@code null})
     * @param contact  the contact whose fields are substituted
     * @return resolved string; never {@code null}
     */
    private static String resolveTemplate(String template, Contact contact) {
        if (template == null) return "";
        return template
                .replace("{{firstName}}", nvl(contact.getFirstName()))
                .replace("{{lastName}}",  nvl(contact.getLastName()))
                .replace("{{email}}",     nvl(contact.getPrimaryEmail()))
                .replace("{{company}}",   nvl(contact.getCompany()));
    }

    private static String nvl(String value) {
        return value != null ? value : "";
    }

    private void updateRecipientCountLabel() {
        int n = recipients.size();
        recipientCountLabel.setText(n + " recipient" + (n == 1 ? "" : "s") + " selected");
    }

    private static void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
