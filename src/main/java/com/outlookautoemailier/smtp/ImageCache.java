package com.outlookautoemailier.smtp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Singleton cache that maps an MD5 hash of Base64 image data to an already-hosted
 * imgbb URL.  Prevents re-uploading the same image on every email send.
 *
 * <p>The cache is persisted to {@code ~/.outlookautoemailier/image-cache.json} and
 * loaded on first access.</p>
 */
public class ImageCache {

    private static final Logger log = LoggerFactory.getLogger(ImageCache.class);
    private static final ImageCache INSTANCE = new ImageCache();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path cachePath;
    private final Map<String, String> md5ToUrl;

    private ImageCache() {
        cachePath = Path.of(System.getProperty("user.home"),
                ".outlookautoemailier", "image-cache.json");
        md5ToUrl = loadFromDisk();
    }

    public static ImageCache getInstance() { return INSTANCE; }

    /**
     * Returns the cached hosted URL for the given Base64 data, or {@code null} if not cached.
     *
     * @param base64Data raw Base64 image data (without the data-URI prefix)
     */
    public synchronized String get(String base64Data) {
        return md5ToUrl.get(md5(base64Data));
    }

    /**
     * Stores the mapping from the given Base64 data to its hosted URL and persists to disk.
     *
     * @param base64Data raw Base64 image data
     * @param url        the hosted URL returned by the image host
     */
    public synchronized void put(String base64Data, String url) {
        md5ToUrl.put(md5(base64Data), url);
        saveToDisk();
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private Map<String, String> loadFromDisk() {
        if (!Files.exists(cachePath)) return new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> loaded = MAPPER.readValue(cachePath.toFile(), Map.class);
            log.info("ImageCache: loaded {} entries from disk.", loaded.size());
            return loaded;
        } catch (Exception e) {
            log.warn("ImageCache: failed to load cache from {}: {}", cachePath, e.getMessage());
            return new HashMap<>();
        }
    }

    private void saveToDisk() {
        try {
            Files.createDirectories(cachePath.getParent());
            MAPPER.writeValue(cachePath.toFile(), md5ToUrl);
        } catch (IOException e) {
            log.warn("ImageCache: failed to persist cache: {}", e.getMessage());
        }
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            // Fallback: use first 64 chars of input as key
            return input.length() > 64 ? input.substring(0, 64) : input;
        }
    }
}
