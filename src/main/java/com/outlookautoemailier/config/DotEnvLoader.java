package com.outlookautoemailier.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads sensitive configuration from {@code ~/.outlookautoemailier/.env}.
 *
 * <p>Format: one {@code KEY=value} pair per line.
 * Lines beginning with {@code #} are treated as comments and ignored.
 * Leading/trailing whitespace is stripped from both keys and values.
 *
 * <p>Supported keys:
 * <ul>
 *   <li>{@code IMGBB_API_KEY}</li>
 *   <li>{@code GOOGLE_CLIENT_ID}</li>
 *   <li>{@code GOOGLE_CLIENT_SECRET}</li>
 *   <li>{@code SUPABASE_SERVICE_ROLE_KEY}</li>
 * </ul>
 */
public final class DotEnvLoader {

    private static final Logger log = LoggerFactory.getLogger(DotEnvLoader.class);

    private static volatile Map<String, String> values;

    private DotEnvLoader() {}

    /**
     * Returns the value for {@code key}, or an empty string if not found.
     */
    public static String get(String key) {
        return loaded().getOrDefault(key, "");
    }

    /** Convenience: returns true when the key is present and non-blank. */
    public static boolean has(String key) {
        return !get(key).isBlank();
    }

    /** Clears the cache so the file is re-read on the next call. */
    public static synchronized void reload() {
        values = null;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private static Map<String, String> loaded() {
        if (values == null) {
            synchronized (DotEnvLoader.class) {
                if (values == null) {
                    values = parse();
                }
            }
        }
        return values;
    }

    private static Map<String, String> parse() {
        Map<String, String> map = new HashMap<>();
        // Look for .env in: 1) current working directory, 2) user-home fallback
        Path file = Path.of(System.getProperty("user.dir"), ".env");
        if (!Files.exists(file)) {
            file = Path.of(System.getProperty("user.home"), ".outlookautoemailier", ".env");
        }

        if (!Files.exists(file)) {
            log.debug(".env file not found in working directory or user home; sensitive keys will be empty.");
            return map;
        }

        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 1) continue;
                String key   = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                // Strip surrounding quotes if present
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                         || (value.startsWith("'")  && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                map.put(key, value);
            }
            log.info(".env loaded from {} ({} key(s)).", file, map.size());
        } catch (IOException e) {
            log.error("Failed to read .env file at {}: {}", file, e.getMessage());
        }
        return map;
    }
}
