package com.outlookautoemailier.smtp;

/**
 * Immutable SMTP connection configuration.
 *
 * <p>Instances are created via the {@link Builder} fluent API or through one
 * of the pre-configured factory methods ({@link #office365()}, {@link #gmail()}).
 * All field values are set at construction time; the class carries no mutable
 * state and is therefore safe to share across threads without synchronisation.</p>
 *
 * <p>Typical usage:
 * <pre>{@code
 *   SmtpConfig config = SmtpConfig.office365();
 *   SmtpConfig custom = new SmtpConfig.Builder()
 *       .host("mail.example.com")
 *       .port(465)
 *       .startTlsEnabled(false)
 *       .useOAuth2(false)
 *       .connectionTimeoutMs(5_000)
 *       .readTimeoutMs(15_000)
 *       .build();
 * }</pre>
 * </p>
 */
public class SmtpConfig {

    // ------------------------------------------------------------------
    //  Fields
    // ------------------------------------------------------------------

    /** Hostname or IP address of the SMTP server. */
    private final String  host;

    /** TCP port the SMTP server listens on (commonly 587 for STARTTLS, 465 for SSL). */
    private final int     port;

    /** Whether to upgrade the connection to TLS via the STARTTLS command. */
    private final boolean startTlsEnabled;

    /**
     * When {@code true} the session authenticates using the XOAUTH2 SASL
     * mechanism (access token from {@link com.outlookautoemailier.model.EmailAccount}).
     * When {@code false} a username/password credential is used instead.
     */
    private final boolean useOAuth2;

    /** Maximum time (ms) to wait when establishing the TCP connection. */
    private final int     connectionTimeoutMs;

    /** Maximum time (ms) to wait for a response from the server after a command. */
    private final int     readTimeoutMs;

    // ------------------------------------------------------------------
    //  Constructor (private — use Builder or factory methods)
    // ------------------------------------------------------------------

    private SmtpConfig(Builder builder) {
        this.host               = builder.host;
        this.port               = builder.port;
        this.startTlsEnabled    = builder.startTlsEnabled;
        this.useOAuth2          = builder.useOAuth2;
        this.connectionTimeoutMs = builder.connectionTimeoutMs;
        this.readTimeoutMs      = builder.readTimeoutMs;
    }

    // ------------------------------------------------------------------
    //  Factory methods
    // ------------------------------------------------------------------

    /**
     * Returns a pre-configured {@code SmtpConfig} for Microsoft Office 365.
     *
     * <ul>
     *   <li>Host: {@code smtp.office365.com}</li>
     *   <li>Port: 587 (STARTTLS)</li>
     *   <li>STARTTLS: enabled</li>
     *   <li>OAuth2: enabled (XOAUTH2)</li>
     *   <li>Connection timeout: 10 000 ms</li>
     *   <li>Read timeout: 30 000 ms</li>
     * </ul>
     *
     * @return an {@code SmtpConfig} configured for Office 365
     */
    public static SmtpConfig office365() {
        return new Builder()
                .host("smtp.office365.com")
                .port(587)
                .startTlsEnabled(true)
                .useOAuth2(true)
                .connectionTimeoutMs(10_000)
                .readTimeoutMs(30_000)
                .build();
    }

    /**
     * Returns a pre-configured {@code SmtpConfig} for Gmail.
     *
     * <ul>
     *   <li>Host: {@code smtp.gmail.com}</li>
     *   <li>Port: 587 (STARTTLS)</li>
     *   <li>STARTTLS: enabled</li>
     *   <li>OAuth2: disabled (username/password)</li>
     *   <li>Connection timeout: 10 000 ms</li>
     *   <li>Read timeout: 30 000 ms</li>
     * </ul>
     *
     * @return an {@code SmtpConfig} configured for Gmail
     */
    public static SmtpConfig gmail() {
        return new Builder()
                .host("smtp.gmail.com")
                .port(587)
                .startTlsEnabled(true)
                .useOAuth2(false)
                .connectionTimeoutMs(10_000)
                .readTimeoutMs(30_000)
                .build();
    }

    /**
     * Returns a pre-configured {@code SmtpConfig} for Gmail with OAuth2 (XOAUTH2).
     *
     * <ul>
     *   <li>Host: {@code smtp.gmail.com}</li>
     *   <li>Port: 587 (STARTTLS)</li>
     *   <li>OAuth2: enabled (XOAUTH2 with Google access token)</li>
     * </ul>
     */
    public static SmtpConfig gmailOAuth2() {
        return new Builder()
                .host("smtp.gmail.com")
                .port(587)
                .startTlsEnabled(true)
                .useOAuth2(true)
                .connectionTimeoutMs(10_000)
                .readTimeoutMs(30_000)
                .build();
    }

    // ------------------------------------------------------------------
    //  Getters
    // ------------------------------------------------------------------

    /** SMTP server hostname or IP address. */
    public String  getHost()                { return host; }

    /** SMTP server port number. */
    public int     getPort()                { return port; }

    /** Whether STARTTLS negotiation is enabled. */
    public boolean isStartTlsEnabled()      { return startTlsEnabled; }

    /** Whether the XOAUTH2 mechanism is used for authentication. */
    public boolean isUseOAuth2()            { return useOAuth2; }

    /** TCP connection-establishment timeout in milliseconds. */
    public int     getConnectionTimeoutMs() { return connectionTimeoutMs; }

    /** Server-response read timeout in milliseconds. */
    public int     getReadTimeoutMs()       { return readTimeoutMs; }

    // ------------------------------------------------------------------
    //  Object overrides
    // ------------------------------------------------------------------

    @Override
    public String toString() {
        return String.format(
                "SmtpConfig{host='%s', port=%d, starttls=%b, oauth2=%b, connTimeout=%d ms, readTimeout=%d ms}",
                host, port, startTlsEnabled, useOAuth2, connectionTimeoutMs, readTimeoutMs);
    }

    // ------------------------------------------------------------------
    //  Builder
    // ------------------------------------------------------------------

    /**
     * Fluent builder for {@link SmtpConfig}.  Defaults match the Office 365
     * profile so that minimal configuration is needed for the most common
     * deployment scenario.
     */
    public static class Builder {

        private String  host                = "smtp.office365.com";
        private int     port                = 587;
        private boolean startTlsEnabled     = true;
        private boolean useOAuth2           = true;
        private int     connectionTimeoutMs = 10_000;
        private int     readTimeoutMs       = 30_000;

        /** Sets the SMTP server hostname or IP. */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /** Sets the SMTP server port. */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /** Enables or disables STARTTLS. */
        public Builder startTlsEnabled(boolean v) {
            this.startTlsEnabled = v;
            return this;
        }

        /** Enables or disables OAuth2 (XOAUTH2) authentication. */
        public Builder useOAuth2(boolean v) {
            this.useOAuth2 = v;
            return this;
        }

        /** Sets the TCP connection timeout in milliseconds. */
        public Builder connectionTimeoutMs(int ms) {
            this.connectionTimeoutMs = ms;
            return this;
        }

        /** Sets the server-response read timeout in milliseconds. */
        public Builder readTimeoutMs(int ms) {
            this.readTimeoutMs = ms;
            return this;
        }

        /**
         * Builds and returns a new {@link SmtpConfig} with the accumulated settings.
         *
         * @return a fully initialised, immutable {@code SmtpConfig}
         */
        public SmtpConfig build() {
            return new SmtpConfig(this);
        }
    }
}
