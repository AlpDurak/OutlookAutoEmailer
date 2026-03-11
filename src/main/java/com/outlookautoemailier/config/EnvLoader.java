package com.outlookautoemailier.config;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple .env file parser. Loads environment variables from a .env file
 * in the project root without external dependencies.
 */
public class EnvLoader {

    private static final Logger log = LoggerFactory.getLogger(EnvLoader.class);

    /**
     * Loads variables from a .env file and returns them as a Map.
     * Supports simple KEY=VALUE format with # comments and empty lines.
     *
     * @param envPath path to the .env file
     * @return map of environment variables; empty map if file doesn't exist
     */
    public static Map<String, String> load(Path envPath) {
        Map<String, String> envVars = new HashMap<>();

        if (!Files.exists(envPath)) {
            log.debug(".env file not found at: {}", envPath);
            return envVars;
        }

        try (BufferedReader reader = Files.newBufferedReader(envPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();

                // Skip comments and empty lines
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // Parse KEY=VALUE
                int equalsIndex = line.indexOf('=');
                if (equalsIndex <= 0) {
                    log.warn(".env line {} has invalid format (no '=' found): {}", lineNum, line);
                    continue;
                }

                String key = line.substring(0, equalsIndex).trim();
                String value = line.substring(equalsIndex + 1).trim();

                // Remove surrounding quotes if present
                if ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }

                envVars.put(key, value);
                log.debug("Loaded .env: {} = {}", key, maskSensitive(key, value));
            }

            log.info("Loaded {} variables from .env file: {}", envVars.size(), envPath);
        } catch (Exception e) {
            log.error("Failed to read .env file {}: {}", envPath, e.getMessage());
        }

        return envVars;
    }

    /**
     * Gets a value from the .env map, with a default fallback.
     */
    public static String get(Map<String, String> envVars, String key, String defaultValue) {
        return envVars.getOrDefault(key, defaultValue);
    }

    /**
     * Masks sensitive values in logs (shows only first 4 and last 4 chars).
     */
    private static String maskSensitive(String key, String value) {
        if (key.toUpperCase().contains("SECRET") || 
            key.toUpperCase().contains("TOKEN") || 
            key.toUpperCase().contains("KEY") || 
            key.toUpperCase().contains("ID")) {
            if (value.length() > 8) {
                return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
            }
            return "****";
        }
        return value;
    }
}
