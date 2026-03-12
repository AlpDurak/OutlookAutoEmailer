package com.outlookautoemailier.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A named group of contacts.
 * Immutable name/id; the member list is mutable via add/remove.
 */
public class ContactGroup {

    private final String id;
    private String name;
    private final List<String> contactEmails; // primary email addresses of members
    private final LocalDateTime createdAt;

    /** Full constructor (used for deserialisation). */
    public ContactGroup(String id, String name, List<String> contactEmails, LocalDateTime createdAt) {
        this.id            = id;
        this.name          = name;
        this.contactEmails = new ArrayList<>(contactEmails);
        this.createdAt     = createdAt;
    }

    /** Convenience constructor for new groups. */
    public ContactGroup(String name) {
        this(UUID.randomUUID().toString(), name, new ArrayList<>(), LocalDateTime.now());
    }

    public String          getId()            { return id; }
    public String          getName()          { return name; }
    public void            setName(String n)  { this.name = n; }
    public LocalDateTime   getCreatedAt()     { return createdAt; }
    public List<String>    getContactEmails() { return Collections.unmodifiableList(contactEmails); }

    public void addEmail(String email) {
        if (email != null && !email.isBlank() && !contactEmails.contains(email))
            contactEmails.add(email);
    }

    public void removeEmail(String email) { contactEmails.remove(email); }

    public boolean containsEmail(String email) { return contactEmails.contains(email); }

    public int size() { return contactEmails.size(); }

    @Override public String toString() { return name + " (" + contactEmails.size() + ")"; }
}
