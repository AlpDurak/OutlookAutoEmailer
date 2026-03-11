package com.outlookautoemailier.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable domain model representing a single Outlook contact.
 * Constructed exclusively via the nested {@link Builder}.
 */
public final class Contact {

    private final String id;
    private final String displayName;
    private final String firstName;
    private final String lastName;
    private final List<String> emailAddresses;
    private final String phone;
    private final String company;
    private final String jobTitle;

    private Contact(Builder builder) {
        this.id            = builder.id;
        this.displayName   = builder.displayName;
        this.firstName     = builder.firstName;
        this.lastName      = builder.lastName;
        this.emailAddresses = Collections.unmodifiableList(
                new ArrayList<>(builder.emailAddresses));
        this.phone         = builder.phone;
        this.company       = builder.company;
        this.jobTitle      = builder.jobTitle;
    }

    // ------------------------------------------------------------------ //
    //  Getters                                                             //
    // ------------------------------------------------------------------ //

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    /** Returns an unmodifiable view of the email address list. */
    public List<String> getEmailAddresses() {
        return emailAddresses;
    }

    /**
     * Convenience accessor — returns the first email address or an empty
     * string when none is present.
     */
    public String getPrimaryEmail() {
        return emailAddresses.isEmpty() ? "" : emailAddresses.get(0);
    }

    public String getPhone() {
        return phone;
    }

    public String getCompany() {
        return company;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    // ------------------------------------------------------------------ //
    //  Object overrides                                                    //
    // ------------------------------------------------------------------ //

    @Override
    public String toString() {
        return "Contact{"
                + "id='" + id + '\''
                + ", displayName='" + displayName + '\''
                + ", firstName='" + firstName + '\''
                + ", lastName='" + lastName + '\''
                + ", emailAddresses=" + emailAddresses
                + ", phone='" + phone + '\''
                + ", company='" + company + '\''
                + ", jobTitle='" + jobTitle + '\''
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Contact other)) return false;
        return Objects.equals(id, other.id)
                && Objects.equals(displayName, other.displayName)
                && Objects.equals(firstName, other.firstName)
                && Objects.equals(lastName, other.lastName)
                && Objects.equals(emailAddresses, other.emailAddresses)
                && Objects.equals(phone, other.phone)
                && Objects.equals(company, other.company)
                && Objects.equals(jobTitle, other.jobTitle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, displayName, firstName, lastName,
                emailAddresses, phone, company, jobTitle);
    }

    // ------------------------------------------------------------------ //
    //  Builder                                                             //
    // ------------------------------------------------------------------ //

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String id;
        private String displayName;
        private String firstName;
        private String lastName;
        private List<String> emailAddresses = new ArrayList<>();
        private String phone;
        private String company;
        private String jobTitle;

        private Builder() {}

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder firstName(String firstName) {
            this.firstName = firstName;
            return this;
        }

        public Builder lastName(String lastName) {
            this.lastName = lastName;
            return this;
        }

        public Builder emailAddresses(List<String> emailAddresses) {
            this.emailAddresses = emailAddresses != null
                    ? new ArrayList<>(emailAddresses)
                    : new ArrayList<>();
            return this;
        }

        public Builder addEmailAddress(String email) {
            if (email != null && !email.isBlank()) {
                this.emailAddresses.add(email);
            }
            return this;
        }

        public Builder phone(String phone) {
            this.phone = phone;
            return this;
        }

        public Builder company(String company) {
            this.company = company;
            return this;
        }

        public Builder jobTitle(String jobTitle) {
            this.jobTitle = jobTitle;
            return this;
        }

        public Contact build() {
            return new Contact(this);
        }
    }
}
