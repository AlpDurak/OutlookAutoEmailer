package com.outlookautoemailier.api;

import com.microsoft.graph.authentication.IAuthenticationProvider;
import com.microsoft.graph.models.User;
import com.microsoft.graph.requests.GraphServiceClient;
import com.microsoft.graph.requests.UserCollectionPage;
import com.outlookautoemailier.model.EmailAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Connects to Microsoft Graph API and fetches organisation users from
 * the source account's Entra ID (Azure AD) tenant.
 *
 * <p>Uses the /users endpoint (requires User.ReadBasic.All scope) instead of
 * /me/contacts, so all 33,000+ university accounts are discoverable.
 *
 * <p>Authentication is provided externally via the access token stored in
 * the EmailAccount object.
 */
public class GraphApiClient {

    private static final Logger log = LoggerFactory.getLogger(GraphApiClient.class);
    private static final int PAGE_SIZE = 999;

    private GraphServiceClient<okhttp3.Request> graphClient;
    private final EmailAccount account;

    public GraphApiClient(EmailAccount account) {
        this.account = account;
    }

    /**
     * Initialises the Graph SDK client using the access token from the account.
     * Must be called before fetchRawUsers().
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
     * Fetches ALL organisation users from Entra ID using pagination.
     * Follows @odata.nextLink until all pages are retrieved.
     * Use ContactFetcher to convert results to the domain Contact model.
     *
     * @return unmodifiable list of raw Graph User objects
     */
    public List<User> fetchRawUsers() {
        if (graphClient == null) {
            throw new IllegalStateException("GraphApiClient not connected. Call connect() first.");
        }
        log.info("Fetching organisation users from Graph API /users (paginated)...");

        List<User> allUsers = new ArrayList<>();
        UserCollectionPage page = graphClient.users()
                .buildRequest()
                .top(PAGE_SIZE)
                .select("id,displayName,givenName,surname,mail,userPrincipalName,jobTitle,department,companyName,businessPhones")
                .get();

        int pageNum = 1;
        while (page != null) {
            List<User> pageUsers = page.getCurrentPage();
            if (pageUsers != null) {
                allUsers.addAll(pageUsers);
                log.debug("Page {}: {} users (running total: {})",
                        pageNum, pageUsers.size(), allUsers.size());
            }

            var nextPageBuilder = page.getNextPage();
            if (nextPageBuilder == null) break;

            log.debug("Following nextLink for page {}...", ++pageNum);
            try {
                page = nextPageBuilder.buildRequest().get();
            } catch (Exception e) {
                log.error("Error fetching users page {}: {}", pageNum, e.getMessage(), e);
                break;
            }
        }

        log.info("Fetched {} users total across {} page(s).", allUsers.size(), pageNum);
        return Collections.unmodifiableList(allUsers);
    }

    public void disconnect() {
        graphClient = null;
        log.info("GraphApiClient disconnected.");
    }
}
