package com.outlookautoemailier.api;

import com.microsoft.graph.authentication.IAuthenticationProvider;
import com.microsoft.graph.models.Contact;
import com.microsoft.graph.requests.ContactCollectionPage;
import com.microsoft.graph.requests.GraphServiceClient;
import com.outlookautoemailier.model.EmailAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Connects to Microsoft Graph API and fetches contacts from the source account.
 * Authentication is handled externally via OAuth2Helper; this class receives
 * a valid access token through the EmailAccount object.
 */
public class GraphApiClient {

    private static final Logger log = LoggerFactory.getLogger(GraphApiClient.class);
    private static final int PAGE_SIZE = 100;

    private GraphServiceClient<okhttp3.Request> graphClient;
    private final EmailAccount account;

    public GraphApiClient(EmailAccount account) {
        this.account = account;
    }

    /**
     * Initializes the Graph SDK client using the access token from the account.
     * Must be called before fetchRawContacts().
     */
    public void connect() {
        IAuthenticationProvider authProvider =
                requestUrl -> CompletableFuture.completedFuture(account.getAccessToken());

        graphClient = GraphServiceClient.builder()
                .authenticationProvider(authProvider)
                .buildClient();
        log.info("GraphApiClient connected for account: {}", account.getEmailAddress());
    }

    /**
     * Fetches ALL contacts from the source account using pagination.
     * Follows nextLink until all pages are retrieved.
     * Returns raw Graph Contact objects; use ContactFetcher to convert to model.
     */
    public List<Contact> fetchRawContacts() {
        if (graphClient == null) {
            throw new IllegalStateException("GraphApiClient not connected. Call connect() first.");
        }
        log.info("Fetching contacts from Graph API (paginated)...");

        List<Contact> allContacts = new ArrayList<>();
        ContactCollectionPage page = graphClient.me().contacts()
                .buildRequest()
                .top(PAGE_SIZE)
                .select("id,displayName,givenName,surname,emailAddresses,businessPhones,companyName,jobTitle")
                .get();

        int pageNum = 1;
        while (page != null) {
            List<Contact> pageContacts = page.getCurrentPage();
            if (pageContacts != null) {
                allContacts.addAll(pageContacts);
                log.debug("Fetched page {}: {} contacts (running total: {})",
                        pageNum, pageContacts.size(), allContacts.size());
            }

            var nextPageBuilder = page.getNextPage();
            if (nextPageBuilder == null) {
                break;
            }

            log.debug("Following nextLink for page {}...", ++pageNum);
            try {
                page = nextPageBuilder.buildRequest().get();
            } catch (Exception e) {
                log.error("Error fetching contacts page {}: {}", pageNum, e.getMessage(), e);
                break;
            }
        }

        log.info("Fetched {} contacts total across {} page(s).", allContacts.size(), pageNum);
        return Collections.unmodifiableList(allContacts);
    }

    public void disconnect() {
        graphClient = null;
        log.info("GraphApiClient disconnected.");
    }
}
