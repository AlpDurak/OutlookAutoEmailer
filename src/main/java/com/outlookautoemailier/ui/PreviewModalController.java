package com.outlookautoemailier.ui;

import com.outlookautoemailier.model.Contact;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.web.WebView;

import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controller for {@code PreviewModal.fxml}.
 *
 * <p>Renders the composed email with per-recipient variable resolution
 * inside a {@link WebView}, and provides previous/next navigation to
 * flip through all recipients.
 */
public class PreviewModalController implements Initializable {

    private static final Pattern UNRESOLVED_TOKEN = Pattern.compile("\\{\\{\\w+}}");

    @FXML private Label   subjectLabel;
    @FXML private Label   recipientLabel;
    @FXML private Label   counterLabel;
    @FXML private Label   unresolvedWarning;
    @FXML private WebView previewWebView;
    @FXML private Button  prevButton;
    @FXML private Button  nextButton;

    private List<Contact> contacts = Collections.emptyList();
    private String subjectTemplate = "";
    private String bodyTemplate = "";
    private int currentIndex = 0;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // No AppContext registration needed — this is a transient dialog controller
    }

    // ── Public API (called by ComposeController before showing the dialog) ──

    /**
     * Configures the preview with the recipient list and template strings.
     *
     * @param contacts        the list of recipients to preview
     * @param subjectTemplate the raw subject template with {{variable}} tokens
     * @param bodyTemplate    the raw body template with {{variable}} tokens
     */
    public void setData(List<Contact> contacts, String subjectTemplate, String bodyTemplate) {
        this.contacts = contacts != null ? contacts : Collections.emptyList();
        this.subjectTemplate = subjectTemplate != null ? subjectTemplate : "";
        this.bodyTemplate = bodyTemplate != null ? bodyTemplate : "";
        this.currentIndex = 0;
        renderCurrent();
    }

    // ── FXML action handlers ────────────────────────────────────────────────

    @FXML
    private void onPrevious() {
        if (currentIndex > 0) {
            currentIndex--;
            renderCurrent();
        }
    }

    @FXML
    private void onNext() {
        if (currentIndex < contacts.size() - 1) {
            currentIndex++;
            renderCurrent();
        }
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private void renderCurrent() {
        if (contacts.isEmpty()) {
            subjectLabel.setText("(no recipients)");
            recipientLabel.setText("");
            counterLabel.setText("");
            prevButton.setDisable(true);
            nextButton.setDisable(true);
            previewWebView.getEngine().loadContent("<p>No recipients to preview.</p>");
            return;
        }

        Contact contact = contacts.get(currentIndex);

        // Resolve templates
        String resolvedSubject = resolveTemplate(subjectTemplate, contact);
        String resolvedBody = resolveTemplate(bodyTemplate, contact);

        // Update subject label
        subjectLabel.setText(resolvedSubject);

        // Update recipient info
        String displayName = nvl(contact.getDisplayName());
        String email = nvl(contact.getPrimaryEmail());
        recipientLabel.setText(displayName.isEmpty() ? email : displayName + " (" + email + ")");
        counterLabel.setText("Recipient " + (currentIndex + 1) + " of " + contacts.size());

        // Navigation button state
        prevButton.setDisable(currentIndex == 0);
        nextButton.setDisable(currentIndex >= contacts.size() - 1);

        // Check for unresolved tokens
        boolean subjectHasUnresolved = UNRESOLVED_TOKEN.matcher(resolvedSubject).find();
        boolean bodyHasUnresolved = UNRESOLVED_TOKEN.matcher(resolvedBody).find();
        if (subjectHasUnresolved || bodyHasUnresolved) {
            unresolvedWarning.setText("Warning: unresolved variables detected — "
                    + findUnresolvedTokens(resolvedSubject + " " + resolvedBody));
            unresolvedWarning.setVisible(true);
            unresolvedWarning.setManaged(true);
            // Apply warning style to subject if it contains unresolved tokens
            if (subjectHasUnresolved) {
                subjectLabel.getStyleClass().add("preview-subject-warning");
            } else {
                subjectLabel.getStyleClass().remove("preview-subject-warning");
            }
        } else {
            unresolvedWarning.setVisible(false);
            unresolvedWarning.setManaged(false);
            subjectLabel.getStyleClass().remove("preview-subject-warning");
        }

        // Render HTML in WebView
        previewWebView.getEngine().loadContent(resolvedBody);
    }

    /**
     * Finds all distinct unresolved {{...}} tokens in the given text.
     */
    private static String findUnresolvedTokens(String text) {
        Matcher matcher = UNRESOLVED_TOKEN.matcher(text);
        java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return String.join(", ", tokens);
    }

    // ── Template resolution (mirrors ComposeController logic) ───────────────

    static String resolveTemplate(String template, Contact contact) {
        if (template == null) return "";
        return template
                .replace("{{firstName}}", nvl(contact.getFirstName()))
                .replace("{{lastName}}",  nvl(contact.getLastName()))
                .replace("{{email}}",     nvl(contact.getPrimaryEmail()))
                .replace("{{company}}",   nvl(contact.getCompany()))
                .replace("{{jobTitle}}",  nvl(contact.getJobTitle()));
    }

    private static String nvl(String value) {
        return value != null ? value : "";
    }
}
