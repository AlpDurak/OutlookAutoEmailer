package com.outlookautoemailier.api;

import com.microsoft.graph.models.EmailAddress;
import com.outlookautoemailier.model.Contact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Converts raw {@link com.microsoft.graph.models.Contact} objects returned by
 * the Microsoft Graph SDK into the application's own {@link Contact} domain
 * model.
 *
 * <p>All field mappings are null-safe: missing Graph values produce empty
 * strings or empty lists rather than {@code NullPointerException}.</p>
 */
public class ContactFetcher {

    private static final Logger log = LoggerFactory.getLogger(ContactFetcher.class);

    // ------------------------------------------------------------------ //
    //  Public API                                                          //
    // ------------------------------------------------------------------ //

    /**
     * Converts a list of raw Graph contacts to a list of domain
     * {@link Contact} objects.
     *
     * @param raw list of Graph SDK contact objects; may be {@code null}
     * @return non-null, possibly empty list of domain contacts
     */
    public List<Contact> convert(List<com.microsoft.graph.models.Contact> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }

        List<Contact> result = new ArrayList<>(raw.size());
        for (com.microsoft.graph.models.Contact graphContact : raw) {
            if (graphContact == null) {
                continue;
            }
            try {
                result.add(mapToModel(graphContact));
            } catch (Exception e) {
                log.warn("Skipping contact '{}' due to mapping error: {}",
                        graphContact.id, e.getMessage(), e);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Convenience method that fetches raw contacts from the given
     * {@link GraphApiClient} and converts them in one step.
     *
     * @param client a connected {@link GraphApiClient}
     * @return non-null, possibly empty list of domain contacts
     */
    public List<Contact> fetchAll(GraphApiClient client) {
        List<com.microsoft.graph.models.Contact> raw = client.fetchRawContacts();
        List<Contact> contacts = convert(raw);
        log.info("ContactFetcher: converted {} contacts.", contacts.size());
        return contacts;
    }

    // ------------------------------------------------------------------ //
    //  Mapping logic                                                       //
    // ------------------------------------------------------------------ //

    /**
     * Maps a single Graph {@link com.microsoft.graph.models.Contact} to the
     * application's domain {@link Contact}.
     */
    private Contact mapToModel(com.microsoft.graph.models.Contact g) {
        Contact.Builder builder = Contact.builder()
                .id(nullSafe(g.id))
                .displayName(nullSafe(g.displayName))
                .firstName(nullSafe(g.givenName))
                .lastName(nullSafe(g.surname))
                .company(nullSafe(g.companyName))
                .jobTitle(nullSafe(g.jobTitle))
                .phone(extractPrimaryPhone(g.businessPhones));

        // Email addresses
        List<EmailAddress> graphEmails = g.emailAddresses;
        if (graphEmails != null) {
            for (EmailAddress ea : graphEmails) {
                if (ea != null && ea.address != null && !ea.address.isBlank()) {
                    builder.addEmailAddress(ea.address);
                }
            }
        }

        return builder.build();
    }

    /**
     * Extracts the first business phone number from the list, or returns an
     * empty string when the list is null or empty.
     */
    private String extractPrimaryPhone(List<String> phones) {
        if (phones == null || phones.isEmpty()) {
            return "";
        }
        String first = phones.get(0);
        return first != null ? first : "";
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }
}
