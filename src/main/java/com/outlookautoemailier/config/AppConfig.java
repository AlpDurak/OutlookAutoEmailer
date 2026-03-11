package com.outlookautoemailier.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Typed, validated, singleton configuration loader.
 * Reads application.properties from the classpath and exposes strongly-typed
 * accessors for every supported key. Missing or malformed values fall back to
 * safe defaults and emit a WARN log so misconfiguration is caught early.
 *
 * <p>Thread-safe: uses double-checked locking for lazy initialisation.</p>
 */
public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    // ---------------------------------------------------------------------------
    // Singleton state
    // ---------------------------------------------------------------------------

    private static volatile AppConfig INSTANCE;

    /**
     * Returns the shared AppConfig instance, loading it from
     * {@code /application.properties} on the first call.
     */
    public static AppConfig getInstance() {
        if (INSTANCE == null) {
            synchronized (AppConfig.class) {
                if (INSTANCE == null) {
                    INSTANCE = load();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Clears the cached instance so that the next call to {@link #getInstance()}
     * re-reads the properties file. Intended for use in tests only.
     */
    public static void reload() {
        synchronized (AppConfig.class) {
            INSTANCE = null;
        }
        log.debug("AppConfig instance cleared — will reload on next access.");
    }

    // ---------------------------------------------------------------------------
    // Fields
    // ---------------------------------------------------------------------------

    private final String azureClientId;
    private final String azureTenantId;
    private final String smtpHost;
    private final int smtpPort;
    private final boolean smtpStartTls;
    private final int rateLimitEmailsPerHour;
    private final long rateLimitDelayMinMs;
    private final long rateLimitDelayMaxMs;
    private final int retryMaxAttempts;
    private final double retryBackoffMultiplier;
    private final String loggingLevel;

    // ---------------------------------------------------------------------------
    // Constructor (private — use getInstance())
    // ---------------------------------------------------------------------------

    private AppConfig(Properties props) {
        azureClientId  = props.getProperty("azure.client.id", "");
        azureTenantId  = props.getProperty("azure.tenant.id", "");
        smtpHost       = props.getProperty("smtp.host", "smtp.office365.com");
        smtpStartTls   = parseBooleanProp(props, "smtp.starttls.enabled", true);
        smtpPort       = parseIntProp(props,    "smtp.port",                      587);
        rateLimitEmailsPerHour = parseIntProp(props, "rate.limit.emails.per.hour", 100);
        rateLimitDelayMinMs    = parseLongProp(props, "rate.limit.delay.min.ms",  3000L);
        rateLimitDelayMaxMs    = parseLongProp(props, "rate.limit.delay.max.ms",  8000L);
        retryMaxAttempts       = parseIntProp(props,  "retry.max.attempts",           3);
        retryBackoffMultiplier = parseDoubleProp(props, "retry.backoff.multiplier",  2.0);
        loggingLevel           = props.getProperty("logging.level", "INFO");

        validate();
    }

    // ---------------------------------------------------------------------------
    // Loading helper
    // ---------------------------------------------------------------------------

    private static AppConfig load() {
        return new AppConfig(loadMergedProperties());
    }

    /**
     * Builds a merged {@link Properties} by layering two sources:
     * <ol>
     *   <li><b>Classpath defaults</b> ({@code /application.properties}) — lowest priority.</li>
     *   <li><b>User-home override</b>
     *       ({@code $HOME/.outlookautoemailier/application.properties}) — highest priority;
     *       any key present here silently overrides the classpath value.</li>
     * </ol>
     *
     * <p>Missing sources are tolerated with a WARN log; a completely empty
     * {@code Properties} object is returned when both sources fail so that
     * callers fall back to hard-coded defaults.</p>
     *
     * @return the merged properties; never {@code null}
     */
    private static Properties loadMergedProperties() {
        Properties merged = new Properties();

        // 1. Load classpath defaults first (lowest priority)
        try (InputStream classpathIn = AppConfig.class.getResourceAsStream("/application.properties")) {
            if (classpathIn != null) {
                merged.load(classpathIn);
                log.info("application.properties loaded from classpath.");
            } else {
                LoggerFactory.getLogger(AppConfig.class)
                        .warn("No classpath application.properties found.");
            }
        } catch (IOException e) {
            LoggerFactory.getLogger(AppConfig.class)
                    .warn("Failed to load classpath properties: {}", e.getMessage());
        }

        // 2. Override with user home settings (highest priority)
        Path userProps = Paths.get(System.getProperty("user.home"),
                ".outlookautoemailier", "application.properties");
        if (Files.exists(userProps)) {
            try (InputStream userIn = Files.newInputStream(userProps)) {
                Properties userOverrides = new Properties();
                userOverrides.load(userIn);
                merged.putAll(userOverrides);
                LoggerFactory.getLogger(AppConfig.class)
                        .info("Loaded user settings override from: {}", userProps);
            } catch (IOException e) {
                LoggerFactory.getLogger(AppConfig.class)
                        .warn("Failed to load user properties override: {}", e.getMessage());
            }
        }

        return merged;
    }

    // ---------------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------------

    private void validate() {
        if (azureClientId.startsWith("YOUR_")) {
            log.warn("Azure client ID is a placeholder value — update azure.client.id in application.properties.");
        }
        if (azureTenantId.startsWith("YOUR_")) {
            log.warn("Azure tenant ID is a placeholder value — update azure.tenant.id in application.properties.");
        }
    }

    // ---------------------------------------------------------------------------
    // Parsing helpers
    // ---------------------------------------------------------------------------

    private static int parseIntProp(Properties props, String key, int defaultValue) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) {
            log.warn("Property '{}' is missing — using default: {}", key, defaultValue);
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Property '{}' has invalid int value '{}' — using default: {}", key, raw, defaultValue);
            return defaultValue;
        }
    }

    private static long parseLongProp(Properties props, String key, long defaultValue) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) {
            log.warn("Property '{}' is missing — using default: {}", key, defaultValue);
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Property '{}' has invalid long value '{}' — using default: {}", key, raw, defaultValue);
            return defaultValue;
        }
    }

    private static double parseDoubleProp(Properties props, String key, double defaultValue) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) {
            log.warn("Property '{}' is missing — using default: {}", key, defaultValue);
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Property '{}' has invalid double value '{}' — using default: {}", key, raw, defaultValue);
            return defaultValue;
        }
    }

    private static boolean parseBooleanProp(Properties props, String key, boolean defaultValue) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) {
            log.warn("Property '{}' is missing — using default: {}", key, defaultValue);
            return defaultValue;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    // ---------------------------------------------------------------------------
    // Public getters
    // ---------------------------------------------------------------------------

    public String getAzureClientId() {
        return azureClientId;
    }

    public String getAzureTenantId() {
        return azureTenantId;
    }

    public String getSmtpHost() {
        return smtpHost;
    }

    public int getSmtpPort() {
        return smtpPort;
    }

    public boolean isSmtpStartTls() {
        return smtpStartTls;
    }

    public int getRateLimitEmailsPerHour() {
        return rateLimitEmailsPerHour;
    }

    public long getRateLimitDelayMinMs() {
        return rateLimitDelayMinMs;
    }

    public long getRateLimitDelayMaxMs() {
        return rateLimitDelayMaxMs;
    }

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public double getRetryBackoffMultiplier() {
        return retryBackoffMultiplier;
    }

    public String getLoggingLevel() {
        return loggingLevel;
    }

    // ---------------------------------------------------------------------------
    // Diagnostics
    // ---------------------------------------------------------------------------

    /**
     * Returns a human-readable summary of all config values (masks sensitive fields).
     */
    public String summary() {
        return String.format(
            "AppConfig{azure.clientId=%s, azure.tenantId=%s, " +
            "smtp=%s:%d(starttls=%b), " +
            "rate=%d/hr delay=%d-%dms, " +
            "retry=max%d x%.1f}",
            maskId(azureClientId), maskId(azureTenantId),
            smtpHost, smtpPort, smtpStartTls,
            rateLimitEmailsPerHour, rateLimitDelayMinMs, rateLimitDelayMaxMs,
            retryMaxAttempts, retryBackoffMultiplier
        );
    }

    private static String maskId(String id) {
        if (id == null || id.length() < 8) return "***";
        return id.substring(0, 4) + "****" + id.substring(id.length() - 4);
    }
}
