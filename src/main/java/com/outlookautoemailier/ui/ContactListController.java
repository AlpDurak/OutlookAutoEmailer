package com.outlookautoemailier.ui;

import com.outlookautoemailier.AppContext;
import com.outlookautoemailier.api.ContactFetcher;
import com.outlookautoemailier.model.Contact;
import com.outlookautoemailier.util.StudentEmailParser;
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
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.FlowPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Controller for {@code ContactList.fxml}.
 *
 * Displays Outlook contacts in a sortable, filterable TableView.
 * Supports student-email filtering by registration year, major, and faculty.
 * Email format: {2-digit year}{major letters}{student id}@domain
 * e.g. 25comp1019@isik.edu.tr → year=25 (2025), major=comp
 */
public class ContactListController implements Initializable {

    private static final Logger log = LoggerFactory.getLogger(ContactListController.class);

    // ── Faculty → major codes mapping ────────────────────────────────────────
    private static final Map<String, Set<String>> FACULTY_MAJORS = new LinkedHashMap<>();
    static {
        FACULTY_MAJORS.put("Engineering & Natural Sciences",
                Set.of("comp", "soft", "elec", "mech", "ie", "ce", "ee", "che", "math",
                        "phys", "bio", "chem", "env", "bme"));
        FACULTY_MAJORS.put("Business & Economics",
                Set.of("bba", "acc", "fin", "mgmt", "econ", "mba", "bus"));
        FACULTY_MAJORS.put("Architecture",
                Set.of("arch", "id", "gd"));
        FACULTY_MAJORS.put("Law",
                Set.of("law", "hukuk", "huk"));
        FACULTY_MAJORS.put("Arts & Social Sciences",
                Set.of("psy", "soc", "phil", "hist", "eng", "comm", "pr", "edu"));
    }

    // ── FXML — toolbar ────────────────────────────────────────────────────────
    @FXML private TextField searchField;

    // ── FXML — filter panel ───────────────────────────────────────────────────
    @FXML private TitledPane filterPane;
    @FXML private TextField  minYearField;
    @FXML private FlowPane   facultyFilterPane;
    @FXML private FlowPane   majorFilterPane;

    // ── FXML — loading overlay ────────────────────────────────────────────────
    @FXML private javafx.scene.layout.VBox loadingOverlay;
    @FXML private javafx.scene.control.ProgressIndicator loadingSpinner;
    @FXML private javafx.scene.control.Label loadingLabel;

    // ── FXML — count / bulk-action row ────────────────────────────────────────
    @FXML private Label contactCountLabel;
    @FXML private Label statusLabel;

    // ── FXML — table ──────────────────────────────────────────────────────────
    @FXML private TableView<ContactRow>            contactTable;
    @FXML private TableColumn<ContactRow, Boolean> selectColumn;
    @FXML private TableColumn<ContactRow, String>  nameColumn;
    @FXML private TableColumn<ContactRow, String>  emailColumn;
    @FXML private TableColumn<ContactRow, String>  companyColumn;
    @FXML private TableColumn<ContactRow, String>  jobTitleColumn;

    // ── Data ──────────────────────────────────────────────────────────────────
    private final ObservableList<ContactRow> allContacts = FXCollections.observableArrayList();
    private FilteredList<ContactRow> filteredContacts;

    // ── Filter state ──────────────────────────────────────────────────────────
    /** Major codes currently checked in the major filter (null = no major filter applied). */
    private final Set<String> selectedMajors = new LinkedHashSet<>();
    /** Faculty names currently checked in the faculty filter (null = no faculty filter). */
    private final Set<String> selectedFaculties = new LinkedHashSet<>();
    /** Whether the major/faculty filter panes have been built for the current contact set. */
    private boolean filterPanesBuilt = false;

    // ─────────────────────────────────────────────────────────────────────────
    //  Inner model
    // ─────────────────────────────────────────────────────────────────────────

    public static final class ContactRow {
        private final Contact contact;
        private final SimpleBooleanProperty selected = new SimpleBooleanProperty(false);

        public ContactRow(Contact contact) { this.contact = contact; }

        public Contact getContact()               { return contact; }
        public boolean isSelected()               { return selected.get(); }
        public void    setSelected(boolean value) { selected.set(value); }
        public SimpleBooleanProperty selectedProperty() { return selected; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Initializable
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        configureCellValueFactories();
        bindSearchFilter();
        updateContactCount();
        AppContext.get().setContactListController(this);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Column wiring
    // ─────────────────────────────────────────────────────────────────────────

    private void configureCellValueFactories() {
        contactTable.setEditable(true);
        selectColumn.setCellValueFactory(cd -> cd.getValue().selectedProperty());
        selectColumn.setCellFactory(
                javafx.scene.control.cell.CheckBoxTableCell.forTableColumn(selectColumn));
        selectColumn.setEditable(true);

        nameColumn.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getContact().getDisplayName()));
        emailColumn.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getContact().getPrimaryEmail()));
        companyColumn.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getContact().getCompany()));
        jobTitleColumn.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().getContact().getJobTitle()));
    }

    private void bindSearchFilter() {
        filteredContacts = new FilteredList<>(allContacts, row -> matchesAllFilters(row, ""));

        searchField.textProperty().addListener((obs, oldText, newText) -> {
            applyFilters();
            updateContactCount();
        });

        minYearField.textProperty().addListener((obs, o, n) -> {
            applyFilters();
            updateContactCount();
        });

        SortedList<ContactRow> sortedContacts = new SortedList<>(filteredContacts);
        sortedContacts.comparatorProperty().bind(contactTable.comparatorProperty());
        contactTable.setItems(sortedContacts);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Filter logic
    // ─────────────────────────────────────────────────────────────────────────

    private void applyFilters() {
        String term = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        filteredContacts.setPredicate(row -> matchesAllFilters(row, term));
    }

    private boolean matchesAllFilters(ContactRow row, String searchTerm) {
        Contact c = row.getContact();

        // 1. Text search
        if (!searchTerm.isEmpty()) {
            boolean textMatch = contains(c.getDisplayName(), searchTerm)
                    || contains(c.getPrimaryEmail(), searchTerm)
                    || contains(c.getCompany(), searchTerm)
                    || contains(c.getJobTitle(), searchTerm);
            if (!textMatch) return false;
        }

        // 2. Student email filters (year, major, faculty)
        Optional<StudentEmailParser.ParsedStudentEmail> parsed =
                StudentEmailParser.parse(c.getPrimaryEmail());

        if (parsed.isPresent()) {
            StudentEmailParser.ParsedStudentEmail se = parsed.get();

            // Year filter: hide students registered before minYear
            int minYear = parseMinYear();
            if (se.year() < minYear) return false;

            // Major filter
            if (!selectedMajors.isEmpty() && !selectedMajors.contains(se.major())) {
                return false;
            }

            // Faculty filter
            if (!selectedFaculties.isEmpty()) {
                String detectedFaculty = facultyOf(se.major());
                if (!selectedFaculties.contains(detectedFaculty)) {
                    return false;
                }
            }
        } else {
            // Non-student email: hide if any major/faculty filter is active
            // (only show non-student emails when filters are cleared)
            if (!selectedMajors.isEmpty() || !selectedFaculties.isEmpty()) {
                return false;
            }
            // Year filter doesn't apply to non-student emails; always show them
            // (unless there's a major/faculty filter active, handled above)
        }

        return true;
    }

    private int parseMinYear() {
        String raw = minYearField.getText();
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String facultyOf(String major) {
        if (major == null) return "Other";
        String lc = major.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Set<String>> entry : FACULTY_MAJORS.entrySet()) {
            if (entry.getValue().contains(lc)) return entry.getKey();
        }
        return "Other";
    }

    private static boolean contains(String value, String term) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(term);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Dynamic filter pane population
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the faculty and major CheckBox controls from the loaded contacts.
     * Discovers all distinct majors from student emails in the contact list,
     * groups them into faculties, then populates both FlowPanes.
     */
    private void rebuildFilterPanes() {
        // Collect distinct majors present in the contact set
        Set<String> knownMajors = new TreeSet<>();
        for (ContactRow row : allContacts) {
            StudentEmailParser.parse(row.getContact().getPrimaryEmail())
                    .ifPresent(se -> knownMajors.add(se.major()));
        }

        // Determine which faculties are represented
        Set<String> knownFaculties = new LinkedHashSet<>();
        for (String major : knownMajors) {
            knownFaculties.add(facultyOf(major));
        }

        // Build faculty checkboxes
        facultyFilterPane.getChildren().clear();
        for (String faculty : knownFaculties) {
            CheckBox cb = new CheckBox(faculty);
            cb.setSelected(selectedFaculties.contains(faculty));
            cb.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
                if (isSelected) selectedFaculties.add(faculty);
                else selectedFaculties.remove(faculty);
                applyFilters();
                updateContactCount();
            });
            facultyFilterPane.getChildren().add(cb);
        }

        // Build major checkboxes
        majorFilterPane.getChildren().clear();
        for (String major : knownMajors) {
            CheckBox cb = new CheckBox(major.toUpperCase(Locale.ROOT));
            cb.setSelected(selectedMajors.contains(major));
            cb.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
                if (isSelected) selectedMajors.add(major);
                else selectedMajors.remove(major);
                applyFilters();
                updateContactCount();
            });
            majorFilterPane.getChildren().add(cb);
        }

        filterPanesBuilt = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  FXML action handlers
    // ─────────────────────────────────────────────────────────────────────────

    private void setLoading(boolean loading, String message) {
        loadingOverlay.setVisible(loading);
        loadingOverlay.setManaged(loading);
        if (loading && message != null) {
            loadingLabel.setText(message);
        }
        if (loading) {
            loadingSpinner.setProgress(-1); // indeterminate
        }
    }

    @FXML
    void onRefresh() {
        AppContext ctx = AppContext.get();
        if (ctx.getGraphApiClient() == null) {
            statusLabel.setText("Not connected — authenticate in Accounts first.");
            return;
        }
        statusLabel.setText("Loading contacts\u2026");

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
            setLoading(false, null);
            List<Contact> contacts = service.getValue();
            loadContacts(contacts);
        });
        service.setOnFailed(e -> {
            setLoading(false, null);
            Throwable ex = service.getException();
            statusLabel.setText("Failed: " + (ex != null ? ex.getMessage() : "unknown error"));
            log.error("Contact refresh failed", ex);
        });
        setLoading(true, "Loading contacts\u2026");
        service.start();
    }

    @FXML
    private void onSelectAll() {
        filteredContacts.forEach(row -> row.setSelected(true));
        selectColumn.setVisible(false);
        selectColumn.setVisible(true);
    }

    @FXML
    private void onDeselectAll() {
        filteredContacts.forEach(row -> row.setSelected(false));
        selectColumn.setVisible(false);
        selectColumn.setVisible(true);
    }

    @FXML
    private void onClearFilters() {
        minYearField.setText("20");
        selectedMajors.clear();
        selectedFaculties.clear();
        // Uncheck all faculty checkboxes
        facultyFilterPane.getChildren().stream()
                .filter(n -> n instanceof CheckBox)
                .forEach(n -> ((CheckBox) n).setSelected(false));
        // Uncheck all major checkboxes
        majorFilterPane.getChildren().stream()
                .filter(n -> n instanceof CheckBox)
                .forEach(n -> ((CheckBox) n).setSelected(false));
        applyFilters();
        updateContactCount();
    }

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

        AppContext ctx = AppContext.get();
        if (ctx.getComposeController() != null) {
            ctx.getComposeController().setRecipients(selected);
        }
        if (ctx.getMainController() != null) {
            ctx.getMainController().navigateToCompose();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Replaces the contact list with fresh data, rebuilds filter panes, and
     * applies the current filters. Thread-safe — posts to the FX thread if needed.
     */
    public void loadContacts(List<Contact> contacts) {
        Runnable update = () -> {
            allContacts.setAll(
                    contacts.stream().map(ContactRow::new).collect(Collectors.toList()));
            rebuildFilterPanes();
            applyFilters();
            updateContactCount();
            statusLabel.setText("Loaded " + contacts.size() + " contact(s).");
        };

        if (javafx.application.Platform.isFxApplicationThread()) {
            update.run();
        } else {
            javafx.application.Platform.runLater(update);
        }
    }

    /** Returns the contacts whose check-box is currently ticked. */
    public List<Contact> getSelectedContacts() {
        return allContacts.stream()
                .filter(ContactRow::isSelected)
                .map(ContactRow::getContact)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Programmatically triggers a contact refresh. Called by AppContext after
     * GraphApiClient is initialised so contacts load automatically after login.
     */
    public void triggerRefresh() {
        onRefresh();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void updateContactCount() {
        int total    = allContacts.size();
        int visible  = (filteredContacts != null) ? filteredContacts.size() : total;
        int selected = (int) allContacts.stream().filter(ContactRow::isSelected).count();

        if (searchField.getText() == null || searchField.getText().isBlank()) {
            contactCountLabel.setText(total + " contact" + (total == 1 ? "" : "s")
                    + (selected > 0 ? "  \u00b7  " + selected + " selected" : ""));
        } else {
            contactCountLabel.setText(visible + " of " + total + " shown"
                    + (selected > 0 ? "  \u00b7  " + selected + " selected" : ""));
        }
    }
}
