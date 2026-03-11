package com.outlookautoemailier.ui;

import com.outlookautoemailier.AppContext;
import com.outlookautoemailier.api.ContactFetcher;
import com.outlookautoemailier.model.Contact;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Controller for {@code ContactList.fxml}.
 *
 * <p>Displays Outlook contacts in a sortable, filterable {@link TableView}.
 * Each row has a check-box for bulk selection; selected contacts can be
 * forwarded to the Compose pane.
 *
 * <p>Sprint 2: {@link #onRefresh()} fetches live data from the Graph API via a
 * JavaFX {@link Service}, and {@link #onSendToSelected()} navigates to the
 * Compose pane via {@link AppContext}.
 */
public class ContactListController implements Initializable {

    private static final Logger log = LoggerFactory.getLogger(ContactListController.class);

    // ── Toolbar ──────────────────────────────────────────────────────────────
    @FXML private TextField searchField;

    // ── Count / bulk-action row ───────────────────────────────────────────────
    @FXML private Label contactCountLabel;
    @FXML private Label statusLabel;

    // ── Table and its columns ────────────────────────────────────────────────
    @FXML private TableView<ContactRow>   contactTable;
    @FXML private TableColumn<ContactRow, Boolean> selectColumn;
    @FXML private TableColumn<ContactRow, String>  nameColumn;
    @FXML private TableColumn<ContactRow, String>  emailColumn;
    @FXML private TableColumn<ContactRow, String>  companyColumn;
    @FXML private TableColumn<ContactRow, String>  jobTitleColumn;

    // ── Data ─────────────────────────────────────────────────────────────────

    /** Master list; all TableView data flows from here. */
    private final ObservableList<ContactRow> allContacts = FXCollections.observableArrayList();

    /** Filtered view driven by {@link #searchField}. */
    private FilteredList<ContactRow> filteredContacts;

    /**
     * Thin mutable wrapper around {@link Contact} that adds a JavaFX
     * {@link SimpleBooleanProperty} for the check-box selection column.
     */
    public static final class ContactRow {

        private final Contact contact;
        private final SimpleBooleanProperty selected = new SimpleBooleanProperty(false);

        public ContactRow(Contact contact) {
            this.contact = contact;
        }

        public Contact getContact()               { return contact; }
        public boolean isSelected()               { return selected.get(); }
        public void    setSelected(boolean value) { selected.set(value); }
        public SimpleBooleanProperty selectedProperty() { return selected; }
    }

    // ── Initializable ────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        configureCellValueFactories();
        bindSearchFilter();
        updateContactCount();
        AppContext.get().setContactListController(this);
    }

    // ── Column wiring ────────────────────────────────────────────────────────

    private void configureCellValueFactories() {
        // Check-box column — editable
        contactTable.setEditable(true);
        selectColumn.setCellValueFactory(
                cellData -> cellData.getValue().selectedProperty());
        selectColumn.setCellFactory(
                CheckBoxTableCell.forTableColumn(selectColumn));
        selectColumn.setEditable(true);

        nameColumn.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().getContact().getDisplayName()));

        emailColumn.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().getContact().getPrimaryEmail()));

        companyColumn.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().getContact().getCompany()));

        jobTitleColumn.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().getContact().getJobTitle()));
    }

    private void bindSearchFilter() {
        filteredContacts = new FilteredList<>(allContacts, p -> true);

        searchField.textProperty().addListener((obs, oldText, newText) -> {
            String term = (newText == null) ? "" : newText.trim().toLowerCase(Locale.ROOT);
            if (term.isEmpty()) {
                filteredContacts.setPredicate(row -> true);
            } else {
                filteredContacts.setPredicate(buildSearchPredicate(term));
            }
            updateContactCount();
        });

        // SortedList keeps TableView column-sort gestures working
        SortedList<ContactRow> sortedContacts = new SortedList<>(filteredContacts);
        sortedContacts.comparatorProperty().bind(contactTable.comparatorProperty());
        contactTable.setItems(sortedContacts);
    }

    private Predicate<ContactRow> buildSearchPredicate(String term) {
        return row -> {
            Contact c = row.getContact();
            return contains(c.getDisplayName(), term)
                    || contains(c.getPrimaryEmail(), term)
                    || contains(c.getCompany(), term)
                    || contains(c.getJobTitle(), term);
        };
    }

    private static boolean contains(String value, String term) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(term);
    }

    // ── FXML action handlers ─────────────────────────────────────────────────

    /**
     * Triggers a refresh of contacts from the Microsoft Graph API via a
     * background {@link Service} so the FX thread is never blocked.
     */
    @FXML
    private void onRefresh() {
        AppContext ctx = AppContext.get();
        if (ctx.getGraphApiClient() == null) {
            statusLabel.setText("Not connected — authenticate in Accounts first.");
            return;
        }
        statusLabel.setText("Loading contacts…");

        Service<List<Contact>> service = new Service<>() {
            @Override
            protected Task<List<Contact>> createTask() {
                return new Task<>() {
                    @Override
                    protected List<Contact> call() throws Exception {
                        ContactFetcher fetcher = ctx.getContactFetcher() != null
                                ? ctx.getContactFetcher()
                                : new ContactFetcher();
                        return fetcher.fetchAll(ctx.getGraphApiClient());
                    }
                };
            }
        };

        service.setOnSucceeded(e -> {
            List<Contact> contacts = service.getValue();
            loadContacts(contacts);
            statusLabel.setText("Loaded " + contacts.size() + " contact(s).");
        });
        service.setOnFailed(e -> {
            Throwable ex = service.getException();
            statusLabel.setText("Failed: " + (ex != null ? ex.getMessage() : "unknown error"));
            log.error("Contact refresh failed", ex);
        });
        service.start();
    }

    /** Selects all currently visible (filtered) rows. */
    @FXML
    private void onSelectAll() {
        filteredContacts.forEach(row -> row.setSelected(true));
        // Refresh the column so check-boxes repaint
        selectColumn.setVisible(false);
        selectColumn.setVisible(true);
    }

    /** Deselects all currently visible (filtered) rows. */
    @FXML
    private void onDeselectAll() {
        filteredContacts.forEach(row -> row.setSelected(false));
        selectColumn.setVisible(false);
        selectColumn.setVisible(true);
    }

    /**
     * Collects the checked contacts, forwards them to {@link ComposeController},
     * and navigates to the Compose pane via {@link MainController}.
     */
    @FXML
    private void onSendToSelected() {
        List<Contact> selected = allContacts.stream()
                .filter(ContactRow::isSelected)
                .map(ContactRow::getContact)
                .collect(Collectors.toList());

        if (selected.isEmpty()) {
            statusLabel.setText("No contacts selected. Use the check-boxes to pick recipients.");
            return;
        }

        statusLabel.setText(selected.size() + " contact(s) sent to Compose.");

        if (!selected.isEmpty()) {
            AppContext ctx = AppContext.get();
            if (ctx.getComposeController() != null) {
                ctx.getComposeController().setRecipients(selected);
            }
            if (ctx.getMainController() != null) {
                ctx.getMainController().navigateToCompose();
            }
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Replaces the current contact list with {@code contacts} and refreshes
     * the table.  Safe to call from any thread — posts to the FX thread if
     * needed.
     *
     * @param contacts the fresh list returned by the Graph API client
     */
    public void loadContacts(List<Contact> contacts) {
        Runnable update = () -> {
            allContacts.setAll(
                    contacts.stream()
                            .map(ContactRow::new)
                            .collect(Collectors.toList())
            );
            updateContactCount();
            statusLabel.setText("Loaded " + contacts.size() + " contact(s).");
        };

        if (javafx.application.Platform.isFxApplicationThread()) {
            update.run();
        } else {
            javafx.application.Platform.runLater(update);
        }
    }

    /**
     * Returns the contacts whose check-box is currently ticked.
     *
     * @return immutable snapshot of selected contacts
     */
    public List<Contact> getSelectedContacts() {
        return allContacts.stream()
                .filter(ContactRow::isSelected)
                .map(ContactRow::getContact)
                .collect(Collectors.toUnmodifiableList());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void updateContactCount() {
        int total    = allContacts.size();
        int visible  = (filteredContacts != null) ? filteredContacts.size() : total;
        int selected = (int) allContacts.stream().filter(ContactRow::isSelected).count();

        if (searchField.getText() == null || searchField.getText().isBlank()) {
            contactCountLabel.setText(total + " contact" + (total == 1 ? "" : "s")
                    + (selected > 0 ? "  ·  " + selected + " selected" : ""));
        } else {
            contactCountLabel.setText(visible + " of " + total + " shown"
                    + (selected > 0 ? "  ·  " + selected + " selected" : ""));
        }
    }
}
