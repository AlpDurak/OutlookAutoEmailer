package com.outlookautoemailier.api;

import com.microsoft.graph.models.User;
import com.outlookautoemailier.model.Contact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Converts raw Microsoft Graph {@link User} objects (from the /users endpoint)
 * into the application's domain {@link Contact} model.
 *
 * <p>Email priority: userPrincipalName is used as the primary address because
 * university student accounts follow the pattern 25comp1019@isik.edu.tr.
 * The SMTP mail address (if present and different) is added as a secondary address.
 */
public class ContactFetcher {

    private static final Logger log = LoggerFactory.getLogger(ContactFetcher.class);

    /**
     * Fetches all organisation users from Graph API and converts them to
     * domain Contact objects.
     *
     * @param client a connected {@link GraphApiClient}
     * @return unmodifiable list of domain Contact objects
     */
    public List<Contact> fetchAll(GraphApiClient client) {
        return convert(client.fetchRawUsers());
    }

    /**
     * Converts a list of raw Graph User objects to domain Contact objects,
     * skipping any entries that cause mapping errors.
     */
    public List<Contact> convert(List<User> users) {
        if (users == null) return Collections.emptyList();

        return users.stream()
                .map(this::mapToModel)
                .filter(c -> c != null && !c.getPrimaryEmail().isBlank())
                .collect(Collectors.toUnmodifiableList());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Contact mapToModel(User u) {
        try {
            Contact.Builder builder = Contact.builder()
                    .id(nullSafe(u.id))
                    .displayName(nullSafe(u.displayName))
                    .firstName(nullSafe(u.givenName))
                    .lastName(nullSafe(u.surname))
                    .company(nullSafe(u.companyName != null ? u.companyName : u.department))
                    .jobTitle(nullSafe(u.jobTitle))
                    .phone(extractPrimaryPhone(u.businessPhones));

            // userPrincipalName is the student email (e.g. 25comp1019@isik.edu.tr) — always present
            String upn = nullSafe(u.userPrincipalName);
            if (!upn.isBlank()) {
                builder.addEmailAddress(upn);
            }

            // Add SMTP mail address as a secondary address if different from UPN
            String mail = nullSafe(u.mail);
            if (!mail.isBlank() && !mail.equalsIgnoreCase(upn)) {
                builder.addEmailAddress(mail);
            }

            return builder.build();
        } catch (Exception e) {
            log.warn("Skipping user '{}' due to mapping error: {}", u.id, e.getMessage(), e);
            return null;
        }
    }

    private String extractPrimaryPhone(List<String> phones) {
        if (phones == null || phones.isEmpty()) return "";
        return phones.get(0);
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }
}
