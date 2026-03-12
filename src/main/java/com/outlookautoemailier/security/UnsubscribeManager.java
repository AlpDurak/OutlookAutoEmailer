package com.outlookautoemailier.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages a persistent, per-user suppression list that prevents the mailer
 * from ever sending to opted-out or otherwise blocked addresses.
 *
 * <h2>Storage</h2>
 * <p>The suppression list is stored in a plain-text file at
 * {@code $HOME/.outlookautoemailier/unsubscribed.txt}.  Each suppressed
 * address occupies one line in lowercase.  Lines that begin with {@code #}
 * are comments and are ignored when the file is parsed.  Immediately
 * following each address line the manager writes a timestamp comment:
 * <pre>
 *   user@example.com
 *   # unsubscribed: 2024-01-15T10:30:00
 * </pre>
 *
 * <h2>Concurrency</h2>
 * <p>The in-memory set is backed by a {@link ConcurrentHashMap} key-set, so
 * concurrent reads ({@link #isSuppressed(String)}, {@link #filterAllowed})
 * are lock-free.  Methods that mutate the file ({@link #addUnsubscribe},
 * {@link #removeSuppression}) acquire the instance monitor to serialise
 * disk writes.
 *
 * <h2>Singleton</h2>
 * <p>A single shared instance is exposed via {@link #getInstance()} so that
 * all components of the JVM share the same in-memory set.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   UnsubscribeManager mgr = UnsubscribeManager.getInstance();
 *   mgr.ensureLoaded();
 *
 *   // Before sending a batch:
 *   List<String> allowed = mgr.filterAllowed(recipients);
 *
 *   // When a user opts out:
 *   mgr.addUnsubscribe("user@example.com");
 * }</pre>
 */
public class UnsubscribeManager {

    private static final Logger log = LoggerFactory.getLogger(UnsubscribeManager.class);

    // ------------------------------------------------------------------
    //  Constants
    // ------------------------------------------------------------------

    /** ISO-8601 local datetime formatter used for timestamp comment lines. */
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** Prefix written before each unsubscribe timestamp comment. */
    private static final String COMMENT_PREFIX = "# unsubscribed: ";

    // ------------------------------------------------------------------
    //  Singleton
    // ------------------------------------------------------------------

    /** Lazily-initialised, JVM-wide singleton instance. */
    private static volatile UnsubscribeManager instance;

    /**
     * Returns the shared JVM-wide {@code UnsubscribeManager} instance.
     *
     * <p>Uses double-checked locking for thread-safe lazy initialisation.
     *
     * @return the singleton instance; never {@code null}
     */
    public static UnsubscribeManager getInstance() {
        if (instance == null) {
            synchronized (UnsubscribeManager.class) {
                if (instance == null) {
                    instance = new UnsubscribeManager();
                    log.debug("UnsubscribeManager singleton created.");
                }
            }
        }
        return instance;
    }

    // ------------------------------------------------------------------
    //  Instance state
    // ------------------------------------------------------------------

    /** Absolute path to the persistent suppression list file. */
    private final Path storageFile;

    /**
     * In-memory suppression set.  All entries are stored in lowercase.
     * Backed by {@link ConcurrentHashMap} to allow lock-free reads.
     */
    private final Set<String> suppressedEmails = ConcurrentHashMap.newKeySet();

    /**
     * Maps each suppressed email address to the timestamp when it was
     * unsubscribed.  Populated during {@link #ensureLoaded()} by parsing
     * the {@code # unsubscribed: <ISO datetime>} comment lines, and updated
     * in {@link #addUnsubscribe(String)}.
     */
    private final ConcurrentHashMap<String, LocalDateTime> suppressedWithTimestamps =
            new ConcurrentHashMap<>();

    /**
     * Guards against loading the file more than once.  Writes are still
     * serialised through the instance monitor.
     */
    private boolean loaded = false;

    // ------------------------------------------------------------------
    //  Constructor (private — use getInstance())
    // ------------------------------------------------------------------

    private UnsubscribeManager() {
        this.storageFile = Paths.get(
                System.getProperty("user.home"),
                ".outlookautoemailier",
                "unsubscribed.txt");
        log.debug("UnsubscribeManager storage file: {}", storageFile);
    }

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    /**
     * Loads the suppression list from disk if it has not been loaded yet.
     *
     * <p>This method is idempotent — repeated calls after the first successful
     * load are no-ops.  It is safe to call from multiple threads; only the
     * first thread to arrive will perform the I/O.
     *
     * <p>If the storage file does not exist the suppression list is treated as
     * empty and {@code loaded} is set to {@code true} so that subsequent calls
     * remain no-ops.
     */
    public synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }

        if (!Files.exists(storageFile)) {
            log.info("Suppression list file not found at {}; starting with an empty list.",
                     storageFile);
            loaded = true;
            return;
        }

        try {
            List<String> lines = Files.readAllLines(storageFile, StandardCharsets.UTF_8);
            int count = 0;
            String lastEmail = null;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith(COMMENT_PREFIX)) {
                    // Parse timestamp and associate with the preceding email
                    if (lastEmail != null) {
                        try {
                            String tsStr = trimmed.substring(COMMENT_PREFIX.length()).trim();
                            LocalDateTime ts = LocalDateTime.parse(tsStr, TIMESTAMP_FMT);
                            suppressedWithTimestamps.put(lastEmail, ts);
                        } catch (Exception ex) {
                            log.debug("Could not parse timestamp from line: {}", trimmed);
                        }
                    }
                    continue;
                }
                if (trimmed.startsWith("#")) {
                    continue;
                }
                lastEmail = trimmed.toLowerCase(Locale.ROOT);
                suppressedEmails.add(lastEmail);
                count++;
            }
            loaded = true;
            log.info("Suppression list loaded from {}: {} address(es), {} with timestamps.",
                     storageFile, count, suppressedWithTimestamps.size());
        } catch (IOException e) {
            log.error("Failed to load suppression list from {}: {}", storageFile, e.getMessage(), e);
            // Do not set loaded = true; allow a retry on the next call.
        }
    }

    /**
     * Adds an email address to the suppression list and immediately persists it
     * to disk by appending two lines to the storage file:
     * <ol>
     *   <li>The normalised (lowercase, trimmed) email address.</li>
     *   <li>A timestamp comment: {@code # unsubscribed: <ISO datetime>}.</li>
     * </ol>
     *
     * <p>If the address is already suppressed the in-memory set is unchanged and
     * only the append occurs, creating a duplicate entry in the file (harmless,
     * as duplicates are collapsed into the set on re-load).
     *
     * @param email the address to suppress; must not be {@code null}
     * @throws IllegalArgumentException if {@code email} is {@code null}
     */
    public void addUnsubscribe(String email) {
        if (email == null) {
            throw new IllegalArgumentException("email must not be null");
        }

        ensureLoaded();

        String normalised = email.trim().toLowerCase(Locale.ROOT);
        suppressedEmails.add(normalised);

        LocalDateTime now = LocalDateTime.now();
        suppressedWithTimestamps.put(normalised, now);

        String timestamp = now.format(TIMESTAMP_FMT);
        List<String> lines = List.of(normalised, COMMENT_PREFIX + timestamp);

        synchronized (this) {
            try {
                ensureStorageDirectoryExists();
                Files.write(storageFile, lines, StandardCharsets.UTF_8,
                            StandardOpenOption.APPEND, StandardOpenOption.CREATE);
                log.info("Address suppressed and persisted: {}  (unsubscribed at {}).",
                         normalised, timestamp);
            } catch (IOException e) {
                log.error("Failed to persist unsubscribe for {}: {}", normalised, e.getMessage(), e);
            }
        }
    }

    /**
     * Returns {@code true} if {@code email} is on the suppression list.
     *
     * <p>The check is case-insensitive: the input is normalised to lowercase
     * before the set lookup.
     *
     * @param email the address to test; {@code null} returns {@code false}
     * @return {@code true} if the address is suppressed
     */
    public boolean isSuppressed(String email) {
        if (email == null) {
            return false;
        }
        ensureLoaded();
        return suppressedEmails.contains(email.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Filters a list of email addresses, retaining only those that are
     * <em>not</em> on the suppression list.
     *
     * <p>The original list is not modified.
     *
     * @param emails the candidate addresses; {@code null} input returns an empty list
     * @return a new mutable list of permitted addresses; never {@code null}
     */
    public List<String> filterAllowed(List<String> emails) {
        if (emails == null || emails.isEmpty()) {
            log.debug("filterAllowed() called with null or empty list; returning empty result.");
            return new ArrayList<>();
        }

        ensureLoaded();

        List<String> allowed = new ArrayList<>(emails.size());
        int blocked = 0;

        for (String email : emails) {
            if (email == null) {
                blocked++;
                continue;
            }
            if (suppressedEmails.contains(email.trim().toLowerCase(Locale.ROOT))) {
                blocked++;
                log.debug("filterAllowed() blocked suppressed address: {}", email);
            } else {
                allowed.add(email);
            }
        }

        log.debug("filterAllowed() processed {} address(es): {} allowed, {} blocked.",
                  emails.size(), allowed.size(), blocked);
        return allowed;
    }

    /**
     * Returns an unmodifiable snapshot of all currently suppressed addresses.
     *
     * @return an unmodifiable {@link Set} of lowercase email addresses;
     *         never {@code null}
     */
    public Set<String> getAllSuppressed() {
        ensureLoaded();
        return Collections.unmodifiableSet(suppressedEmails);
    }

    /**
     * Removes an address from the suppression list and rewrites the entire
     * storage file so the removed address no longer appears.
     *
     * <p>This is an administrative operation used to re-enable delivery to an
     * address that was previously suppressed in error.  The rewrite is
     * performed atomically via a temporary file and a filesystem move.
     *
     * @param email the address to re-enable; case-insensitive; must not be
     *              {@code null}
     * @throws IllegalArgumentException if {@code email} is {@code null}
     */
    public synchronized void removeSuppression(String email) {
        if (email == null) {
            throw new IllegalArgumentException("email must not be null");
        }

        ensureLoaded();

        String normalised = email.trim().toLowerCase(Locale.ROOT);
        boolean removed = suppressedEmails.remove(normalised);

        if (!removed) {
            log.info("removeSuppression(): address '{}' was not in the suppression list.",
                     normalised);
            return;
        }

        // Rewrite the file with the current in-memory set (stripped address removed).
        rewriteStorageFile();
        log.info("Suppression removed and file rewritten for address: {}", normalised);
    }

    /**
     * Returns the number of addresses currently on the suppression list.
     *
     * @return a non-negative integer
     */
    public int count() {
        ensureLoaded();
        return suppressedEmails.size();
    }

    /**
     * Returns an unmodifiable view of all suppressed addresses with their
     * unsubscribe timestamps.  Addresses whose timestamp could not be parsed
     * from the file are omitted.
     *
     * @return an unmodifiable {@link Map} of lowercase email to timestamp;
     *         never {@code null}
     */
    public Map<String, LocalDateTime> getAllSuppressedWithTimestamps() {
        ensureLoaded();
        return Collections.unmodifiableMap(suppressedWithTimestamps);
    }

    // ------------------------------------------------------------------
    //  Private helpers
    // ------------------------------------------------------------------

    /**
     * Rewrites the entire storage file from the current in-memory set.
     *
     * <p>Each address is written on its own line followed by a comment line
     * using a fixed placeholder timestamp (the original timestamp is not
     * retained across a rewrite, which is an acceptable trade-off for the
     * administrative use-case).
     *
     * <p>The write is performed via a temporary file that is then atomically
     * moved over the target path so that a crash mid-write cannot corrupt the
     * file.
     *
     * <p><b>Must be called while holding the instance monitor.</b>
     */
    private void rewriteStorageFile() {
        try {
            ensureStorageDirectoryExists();

            List<String> lines = new ArrayList<>();
            lines.add("# OutlookAutoEmailer suppression list");
            lines.add("# Format: one email per line, lowercase.");
            lines.add("# Comment lines begin with #.");
            lines.add("");

            String rewriteTimestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
            for (String addr : suppressedEmails) {
                lines.add(addr);
                lines.add(COMMENT_PREFIX + rewriteTimestamp);
            }

            Path tmp = storageFile.resolveSibling("unsubscribed.txt.tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, storageFile,
                       StandardCopyOption.REPLACE_EXISTING,
                       StandardCopyOption.ATOMIC_MOVE);

            log.debug("Suppression file rewritten with {} address(es).", suppressedEmails.size());
        } catch (IOException e) {
            log.error("Failed to rewrite suppression file at {}: {}", storageFile, e.getMessage(), e);
        }
    }

    /**
     * Creates the parent directory of {@link #storageFile} if it does not
     * already exist.
     *
     * @throws IOException if the directory cannot be created
     */
    private void ensureStorageDirectoryExists() throws IOException {
        Path parent = storageFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
            log.debug("Created suppression list directory: {}", parent);
        }
    }
}
