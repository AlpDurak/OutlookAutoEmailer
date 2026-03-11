package com.outlookautoemailier.model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Immutable domain model representing an Outlook/Microsoft 365 account used
 * either as the contact source (Graph API) or as the SMTP sender.
 *
 * <p>Token expiry is evaluated against {@link LocalDateTime#now()} so callers
 * should refresh the token before it expires, typically via OAuth2Helper.</p>
 */
public final class EmailAccount {

    /**
     * Discriminates between the account that owns the contacts (SOURCE) and
     * the account used to dispatch emails (SENDER).
     */
    public enum AccountType {
        SOURCE,
        SENDER
    }

    /**
     * Identifies which email service provider the account belongs to.
     * Controls which SMTP configuration and authentication mechanism is used.
     */
    public enum EmailProvider {
        MICROSOFT,
        GMAIL
    }

    private final String        tenantId;
    private final String        clientId;
    private final String        accessToken;
    private final String        refreshToken;
    private final String        emailAddress;
    private final AccountType   accountType;
    private final LocalDateTime tokenExpiresAt;
    private final EmailProvider provider;

    private EmailAccount(Builder builder) {
        this.tenantId       = builder.tenantId;
        this.clientId       = builder.clientId;
        this.accessToken    = builder.accessToken;
        this.refreshToken   = builder.refreshToken;
        this.emailAddress   = builder.emailAddress;
        this.accountType    = builder.accountType;
        this.tokenExpiresAt = builder.tokenExpiresAt;
        this.provider       = builder.provider != null ? builder.provider : EmailProvider.MICROSOFT;
    }

    // ------------------------------------------------------------------ //
    //  Business logic                                                      //
    // ------------------------------------------------------------------ //

    /**
     * Returns {@code true} when the access token has expired or when no
     * expiry timestamp has been set (defensive: treat unknown as expired).
     */
    public boolean isTokenExpired() {
        if (tokenExpiresAt == null) {
            return true;
        }
        return LocalDateTime.now().isAfter(tokenExpiresAt);
    }

    // ------------------------------------------------------------------ //
    //  Getters                                                             //
    // ------------------------------------------------------------------ //

    public String getTenantId() {
        return tenantId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public String getEmailAddress() {
        return emailAddress;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public LocalDateTime getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public EmailProvider getProvider() {
        return provider;
    }

    // ------------------------------------------------------------------ //
    //  Object overrides                                                    //
    // ------------------------------------------------------------------ //

    @Override
    public String toString() {
        // Intentionally omit tokens from the string representation.
        return "EmailAccount{"
                + "emailAddress='" + emailAddress + '\''
                + ", accountType=" + accountType
                + ", tenantId='" + tenantId + '\''
                + ", clientId='" + clientId + '\''
                + ", tokenExpiresAt=" + tokenExpiresAt
                + ", tokenExpired=" + isTokenExpired()
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EmailAccount other)) return false;
        return Objects.equals(tenantId, other.tenantId)
                && Objects.equals(clientId, other.clientId)
                && Objects.equals(emailAddress, other.emailAddress)
                && accountType == other.accountType
                && Objects.equals(tokenExpiresAt, other.tokenExpiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, clientId, emailAddress, accountType, tokenExpiresAt);
    }

    // ------------------------------------------------------------------ //
    //  Builder                                                             //
    // ------------------------------------------------------------------ //

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String        tenantId;
        private String        clientId;
        private String        accessToken;
        private String        refreshToken;
        private String        emailAddress;
        private AccountType   accountType;
        private LocalDateTime tokenExpiresAt;
        private EmailProvider provider;

        private Builder() {}

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        public Builder accessToken(String accessToken) {
            this.accessToken = accessToken;
            return this;
        }

        public Builder refreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
            return this;
        }

        public Builder emailAddress(String emailAddress) {
            this.emailAddress = emailAddress;
            return this;
        }

        public Builder accountType(AccountType accountType) {
            this.accountType = accountType;
            return this;
        }

        public Builder tokenExpiresAt(LocalDateTime tokenExpiresAt) {
            this.tokenExpiresAt = tokenExpiresAt;
            return this;
        }

        public Builder provider(EmailProvider provider) {
            this.provider = provider;
            return this;
        }

        public EmailAccount build() {
            Objects.requireNonNull(emailAddress, "emailAddress must not be null");
            Objects.requireNonNull(accountType,  "accountType must not be null");
            return new EmailAccount(this);
        }
    }
}
