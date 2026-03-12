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
import java.util.stream.Collectors;

/**
 * Persists {@link ImageLibraryItem} objects to
 * {@code ~/.outlookautoemailier/image-library.json}.
 * Thread-safe singleton, following the same pattern as {@link ContactGroupStore}.
 */
public class ImageLibraryStore {

    private static final Logger log = LoggerFactory.getLogger(ImageLibraryStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ImageLibraryStore INSTANCE = new ImageLibraryStore();

    private final Path storePath;
    /** Ordered map: id -> item */
    private final Map<String, ImageLibraryItem> items = Collections.synchronizedMap(new LinkedHashMap<>());

    private ImageLibraryStore() {
        storePath = Path.of(System.getProperty("user.home"),
                ".outlookautoemailier", "image-library.json");
        try {
            Files.createDirectories(storePath.getParent());
            loadFromDisk();
        } catch (Exception e) {
            log.error("ImageLibraryStore init failed", e);
        }
    }

    public static ImageLibraryStore getInstance() { return INSTANCE; }

    // ── CRUD operations ──────────────────────────────────────────────────────

    public void addImage(ImageLibraryItem item) {
        items.put(item.getId(), item);
        saveToDisk();
    }

    public void removeImage(String id) {
        items.remove(id);
        saveToDisk();
    }

    public void updateImage(ImageLibraryItem item) {
        items.put(item.getId(), item);
        saveToDisk();
    }

    public List<ImageLibraryItem> getAll() {
        synchronized (items) { return new ArrayList<>(items.values()); }
    }

    public ImageLibraryItem findById(String id) {
        return items.get(id);
    }

    public List<ImageLibraryItem> findByTag(String tag) {
        synchronized (items) {
            return items.values().stream()
                    .filter(i -> i.hasTag(tag))
                    .collect(Collectors.toList());
        }
    }

    // ── Gemini context builder ───────────────────────────────────────────────

    /**
     * Builds a context string describing all available images for Gemini.
     * Each image's URL, file name, tags, and notes are included so the AI
     * can decide which images to embed in the generated email.
     *
     * @return a multi-line description, or an empty string if no images exist
     */
    public String buildGeminiContext() {
        List<ImageLibraryItem> all = getAll();
        if (all.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("AVAILABLE IMAGES IN YOUR LIBRARY:\n");
        for (ImageLibraryItem img : all) {
            sb.append("- URL: ").append(img.getPublicUrl())
              .append(" | File: ").append(img.getFileName())
              .append(" | Tags: ").append(String.join(", ", img.getTags()));
            if (img.getNotes() != null && !img.getNotes().isBlank()) {
                sb.append(" | Notes: ").append(img.getNotes());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private void saveToDisk() {
        try {
            ArrayNode arr = MAPPER.createArrayNode();
            synchronized (items) {
                for (ImageLibraryItem img : items.values()) {
                    ObjectNode n = MAPPER.createObjectNode();
                    n.put("id",           img.getId());
                    n.put("fileName",     img.getFileName());
                    n.put("driveFileId",  img.getDriveFileId());
                    n.put("publicUrl",    img.getPublicUrl());
                    n.put("thumbnailUrl", img.getThumbnailUrl() != null ? img.getThumbnailUrl() : "");
                    ArrayNode tags = n.putArray("tags");
                    img.getTags().forEach(tags::add);
                    n.put("notes",      img.getNotes() != null ? img.getNotes() : "");
                    n.put("uploadedAt", img.getUploadedAt() != null ? img.getUploadedAt().toString() : "");
                    arr.add(n);
                }
            }
            Files.writeString(storePath, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(arr),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            log.error("ImageLibraryStore save failed", e);
        }
    }

    private void loadFromDisk() throws Exception {
        if (!Files.exists(storePath)) return;
        JsonNode root = MAPPER.readTree(storePath.toFile());
        if (!root.isArray()) return;
        for (JsonNode n : root) {
            String id          = n.path("id").asText(null);
            String fileName    = n.path("fileName").asText("");
            String driveFileId = n.path("driveFileId").asText("");
            String publicUrl   = n.path("publicUrl").asText("");
            String thumbUrl    = n.path("thumbnailUrl").asText("");
            String notes       = n.path("notes").asText("");
            String uploadedStr = n.path("uploadedAt").asText("");

            List<String> tags = new ArrayList<>();
            n.path("tags").forEach(t -> tags.add(t.asText()));

            LocalDateTime uploadedAt = uploadedStr.isBlank()
                    ? LocalDateTime.now() : LocalDateTime.parse(uploadedStr);

            if (id != null) {
                ImageLibraryItem item = new ImageLibraryItem(
                        id, fileName, driveFileId, publicUrl,
                        thumbUrl.isBlank() ? null : thumbUrl,
                        tags, notes.isBlank() ? null : notes, uploadedAt);
                items.put(id, item);
            }
        }
        log.info("Loaded {} image library items from disk.", items.size());
    }
}
