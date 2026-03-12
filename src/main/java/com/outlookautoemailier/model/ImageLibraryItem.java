package com.outlookautoemailier.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Represents an image stored in Google Drive with metadata (tags, notes)
 * that can be used as context for Gemini email generation.
 */
public class ImageLibraryItem {

    private final String id;
    private String fileName;
    private String driveFileId;
    private String publicUrl;
    private String thumbnailUrl;
    private final List<String> tags;
    private String notes;
    private final LocalDateTime uploadedAt;

    /**
     * Full constructor (used for deserialisation).
     */
    public ImageLibraryItem(String id, String fileName, String driveFileId,
                            String publicUrl, String thumbnailUrl,
                            List<String> tags, String notes, LocalDateTime uploadedAt) {
        this.id           = id;
        this.fileName     = fileName;
        this.driveFileId  = driveFileId;
        this.publicUrl    = publicUrl;
        this.thumbnailUrl = thumbnailUrl;
        this.tags         = new ArrayList<>(tags != null ? tags : List.of());
        this.notes        = notes;
        this.uploadedAt   = uploadedAt;
    }

    /**
     * Convenience constructor for new uploads.
     */
    public ImageLibraryItem(String fileName, String driveFileId, String publicUrl) {
        this(UUID.randomUUID().toString(), fileName, driveFileId, publicUrl,
             null, new ArrayList<>(), null, LocalDateTime.now());
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String        getId()           { return id; }
    public String        getFileName()     { return fileName; }
    public String        getDriveFileId()  { return driveFileId; }
    public String        getPublicUrl()    { return publicUrl; }
    public String        getThumbnailUrl() { return thumbnailUrl; }
    public List<String>  getTags()         { return Collections.unmodifiableList(tags); }
    public String        getNotes()        { return notes; }
    public LocalDateTime getUploadedAt()   { return uploadedAt; }

    // ── Setters ──────────────────────────────────────────────────────────────

    public void setFileName(String fileName)         { this.fileName = fileName; }
    public void setDriveFileId(String driveFileId)   { this.driveFileId = driveFileId; }
    public void setPublicUrl(String publicUrl)       { this.publicUrl = publicUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }
    public void setNotes(String notes)               { this.notes = notes; }

    // ── Tag operations ───────────────────────────────────────────────────────

    public void addTag(String tag) {
        if (tag != null && !tag.isBlank() && !tags.contains(tag.strip())) {
            tags.add(tag.strip());
        }
    }

    public void removeTag(String tag) {
        tags.remove(tag);
    }

    public void setTags(List<String> newTags) {
        tags.clear();
        if (newTags != null) tags.addAll(newTags);
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    @Override
    public String toString() {
        return fileName + " [" + String.join(", ", tags) + "]";
    }
}
