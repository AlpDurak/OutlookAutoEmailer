package com.outlookautoemailier.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists {@link ContactGroup} objects to
 * {@code ~/.outlookautoemailier/contact-groups.json}.
 * Thread-safe singleton.
 */
public class ContactGroupStore {

    private static final Logger log = LoggerFactory.getLogger(ContactGroupStore.class);
    private static final ContactGroupStore INSTANCE = new ContactGroupStore();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path storePath;
    /** Ordered map: id → group */
    private final Map<String, ContactGroup> groups = Collections.synchronizedMap(new LinkedHashMap<>());

    private ContactGroupStore() {
        storePath = Path.of(System.getProperty("user.home"),
                ".outlookautoemailier", "contact-groups.json");
        try {
            Files.createDirectories(storePath.getParent());
            loadFromDisk();
        } catch (Exception e) {
            log.error("ContactGroupStore init failed", e);
        }
    }

    public static ContactGroupStore getInstance() { return INSTANCE; }

    public void addGroup(ContactGroup group) {
        groups.put(group.getId(), group);
        saveToDisk();
    }

    public void removeGroup(String id) {
        groups.remove(id);
        saveToDisk();
    }

    public void updateGroup(ContactGroup group) {
        groups.put(group.getId(), group);
        saveToDisk();
    }

    public List<ContactGroup> getAll() {
        synchronized (groups) { return new ArrayList<>(groups.values()); }
    }

    public ContactGroup findById(String id) { return groups.get(id); }

    // ── persistence ──────────────────────────────────────────────────────────

    private void saveToDisk() {
        try {
            ArrayNode arr = MAPPER.createArrayNode();
            synchronized (groups) {
                for (ContactGroup g : groups.values()) {
                    ObjectNode n = MAPPER.createObjectNode();
                    n.put("id",        g.getId());
                    n.put("name",      g.getName());
                    n.put("createdAt", g.getCreatedAt() != null ? g.getCreatedAt().toString() : "");
                    ArrayNode emails = n.putArray("emails");
                    g.getContactEmails().forEach(emails::add);
                    arr.add(n);
                }
            }
            Files.writeString(storePath, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(arr),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            log.error("ContactGroupStore save failed", e);
        }
    }

    private void loadFromDisk() throws Exception {
        if (!Files.exists(storePath)) return;
        JsonNode root = MAPPER.readTree(storePath.toFile());
        if (!root.isArray()) return;
        for (JsonNode n : root) {
            String id   = n.path("id").asText(null);
            String name = n.path("name").asText("Unnamed");
            String createdAtStr = n.path("createdAt").asText("");
            LocalDateTime createdAt = createdAtStr.isBlank() ? LocalDateTime.now() : LocalDateTime.parse(createdAtStr);
            List<String> emails = new ArrayList<>();
            n.path("emails").forEach(e -> emails.add(e.asText()));
            if (id != null) groups.put(id, new ContactGroup(id, name, emails, createdAt));
        }
        log.info("Loaded {} contact groups from disk.", groups.size());
    }
}
