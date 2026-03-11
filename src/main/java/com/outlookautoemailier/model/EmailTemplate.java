package com.outlookautoemailier.model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Immutable email template that supports simple mustache-style placeholder
 * substitution for personalising messages before dispatch.
 *
 * <p>Supported placeholders (case-sensitive):
 * <ul>
 *   <li>{@code {{firstName}}}</li>
 *   <li>{@code {{lastName}}}</li>
 *   <li>{@code {{email}}}  — resolved to the contact's primary email</li>
 *   <li>{@code {{company}}}</li>
 *   <li>{@code {{jobTitle}}}</li>
 * </ul>
 * </p>
 */
public final class EmailTemplate {

    private final String        name;
    private final String        subject;
    private final String        body;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    private EmailTemplate(Builder builder) {
        this.name      = builder.name;
        this.subject   = builder.subject;
        this.body      = builder.body;
        this.createdAt = builder.createdAt != null ? builder.createdAt : LocalDateTime.now();
        this.updatedAt = builder.updatedAt != null ? builder.updatedAt : LocalDateTime.now();
    }

    // ------------------------------------------------------------------ //
    //  Placeholder resolution                                              //
    // ------------------------------------------------------------------ //

    /**
     * Returns the subject line with all placeholders replaced by the
     * corresponding values from {@code contact}.  Null-safe: missing values
     * are replaced with an empty string.
     *
     * @param contact the contact whose data drives substitution
     * @return resolved subject string
     */
    public String resolveSubject(Contact contact) {
        return resolvePlaceholders(subject, contact);
    }

    /**
     * Returns the body with all placeholders replaced by the corresponding
     * values from {@code contact}.  Null-safe: missing values are replaced
     * with an empty string.
     *
     * @param contact the contact whose data drives substitution
     * @return resolved body string
     */
    public String resolveBody(Contact contact) {
        return resolvePlaceholders(body, contact);
    }

    /**
     * Core substitution logic.  Uses {@link String#replace(CharSequence, CharSequence)}
     * so every occurrence of each placeholder is replaced (not just the first).
     */
    private String resolvePlaceholders(String template, Contact contact) {
        if (template == null || contact == null) {
            return template;
        }

        String firstName = nullSafe(contact.getFirstName());
        String lastName  = nullSafe(contact.getLastName());
        String email     = nullSafe(contact.getPrimaryEmail());
        String company   = nullSafe(contact.getCompany());
        String jobTitle  = nullSafe(contact.getJobTitle());

        return template
                .replace("{{firstName}}", firstName)
                .replace("{{lastName}}",  lastName)
                .replace("{{email}}",     email)
                .replace("{{company}}",   company)
                .replace("{{jobTitle}}",  jobTitle);
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }

    // ------------------------------------------------------------------ //
    //  Getters                                                             //
    // ------------------------------------------------------------------ //

    public String getName() {
        return name;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    // ------------------------------------------------------------------ //
    //  Object overrides                                                    //
    // ------------------------------------------------------------------ //

    @Override
    public String toString() {
        return "EmailTemplate{"
                + "name='" + name + '\''
                + ", subject='" + subject + '\''
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EmailTemplate other)) return false;
        return Objects.equals(name, other.name)
                && Objects.equals(subject, other.subject)
                && Objects.equals(body, other.body)
                && Objects.equals(createdAt, other.createdAt)
                && Objects.equals(updatedAt, other.updatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, subject, body, createdAt, updatedAt);
    }

    // ------------------------------------------------------------------ //
    //  Builder                                                             //
    // ------------------------------------------------------------------ //

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String        name;
        private String        subject;
        private String        body;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        private Builder() {}

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder body(String body) {
            this.body = body;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public EmailTemplate build() {
            Objects.requireNonNull(name,    "name must not be null");
            Objects.requireNonNull(subject, "subject must not be null");
            Objects.requireNonNull(body,    "body must not be null");
            return new EmailTemplate(this);
        }
    }
}
