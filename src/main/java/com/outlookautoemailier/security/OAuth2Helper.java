package com.outlookautoemailier.security;

import com.microsoft.aad.msal4j.IAccount;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.aad.msal4j.InteractiveRequestParameters;
import com.microsoft.aad.msal4j.PublicClientApplication;
import com.microsoft.aad.msal4j.SilentParameters;
import com.outlookautoemailier.config.AppConfig;
import com.outlookautoemailier.model.EmailAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Date;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * OAuth2 authentication helper backed by MSAL4J.
 *
 * <p>Supports two authentication modes:
 * <ol>
 *   <li><b>Interactive</b> ({@link #authenticate}) — opens the default system
 *       browser and completes the Authorization Code + PKCE flow via a local
 *       loopback redirect server on port 8765.</li>
 *   <li><b>Silent</b> ({@link #refreshToken}) — attempts a silent token
 *       acquisition using MSAL's internal token cache; falls back to
 *       interactive if the cache does not contain a usable refresh token.</li>
 * </ol>
 *
 * <p>After every successful authentication the resulting tokens are persisted
 * in the {@link CredentialStore} so they survive application restarts.
 *
 * <p>Configuration is loaded from {@code application.properties} on the class-path:
 * <pre>
 *   oauth.clientId=&lt;Azure app registration client ID&gt;
 *   oauth.tenantId=&lt;Azure tenant ID, or "common"&gt;
 * </pre>
 *
 * <p>Security notes:
 * <ul>
 *   <li>MSAL4J performs PKCE internally for public-client interactive flows.</li>
 *   <li>Access tokens are never written to log output.</li>
 *   <li>Tokens approaching expiry (within 5 minutes) are proactively refreshed
 *       by {@link #getValidToken}.</li>
 *   <li>A separate {@link PublicClientApplication} instance is maintained per
 *       account type so that MSAL's token cache does not cross-contaminate
 *       the SOURCE and SENDER account sessions.</li>
 * </ul>
 */
public class OAuth2Helper {

    private static final Logger log = LoggerFactory.getLogger(OAuth2Helper.class);

    // ------------------------------------------------------------------
    //  Well-known scope sets
    // ------------------------------------------------------------------

    /** Scopes required to read contacts via Microsoft Graph. */
    public static final String[] GRAPH_SCOPES = {
            "https://graph.microsoft.com/User.ReadBasic.All",
            "https://graph.microsoft.com/User.Read"
    };

    /** Scopes required to send mail via SMTP AUTH with OAuth2 bearer tokens. */
    public static final String[] SMTP_SCOPES = {
            "https://outlook.office.com/SMTP.Send",
            "offline_access"
    };

    // ------------------------------------------------------------------
    //  OAuth2 endpoints
    // ------------------------------------------------------------------

    private static final String AUTHORITY_BASE = "https://login.microsoftonline.com/";

    /**
     * Loopback redirect URI registered in the Azure app registration.
     * MSAL4J spins up a temporary HTTP listener on this port to receive
     * the authorization code callback.
     */
    private static final String REDIRECT_URI = "http://localhost:8765";

    /** Proactive refresh window: refresh the token if it expires within 5 minutes. */
    private static final long TOKEN_EXPIRY_BUFFER_MS = 5L * 60L * 1000L;

    // ------------------------------------------------------------------
    //  Instance state
    // ------------------------------------------------------------------

    private final CredentialStore credentialStore;
    private final String clientId;
    private final String tenantId;

    /**
     * In-memory cache of the most recent {@link IAuthenticationResult} per
     * account type.  Only populated after a successful interactive or silent
     * authentication within this JVM session.
     */
    private final Map<EmailAccount.AccountType, IAuthenticationResult> tokenCache =
            new EnumMap<>(EmailAccount.AccountType.class);

    /**
     * Per-account-type MSAL {@link PublicClientApplication} instances, created
     * lazily and cached for the lifetime of this helper.
     */
    private final Map<EmailAccount.AccountType, PublicClientApplication> pca =
            new EnumMap<>(EmailAccount.AccountType.class);

    // ------------------------------------------------------------------
    //  Constructor
    // ------------------------------------------------------------------

    /**
     * Constructs an {@code OAuth2Helper}, reading {@code azure.client.id} and
     * {@code azure.tenant.id} from {@link AppConfig} (which applies .env overrides).
     *
     * @param credentialStore the encrypted store used to persist and restore tokens
     */
    public OAuth2Helper(CredentialStore credentialStore) {
        this.credentialStore = credentialStore;
        AppConfig cfg = AppConfig.getInstance();
        this.clientId = cfg.getAzureClientId();
        this.tenantId = cfg.getAzureTenantId();
        log.debug("OAuth2Helper initialised for tenantId={} clientId={}",
                  tenantId, clientId);
    }

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    /**
     * Initiates an interactive OAuth2 Authorization Code + PKCE flow.
     *
     * <p>The method blocks until the user completes sign-in in the browser
     * or the MSAL timeout elapses.  On success, the token is persisted to
     * the {@link CredentialStore} and cached in-memory.
     *
     * @param type   the account role being authenticated
     * @param scopes the OAuth2 scopes to request (use {@link #GRAPH_SCOPES}
     *               or {@link #SMTP_SCOPES})
     * @return the MSAL authentication result containing the access token
     * @throws Exception if authentication fails or the browser cannot be opened
     */
    public IAuthenticationResult authenticate(EmailAccount.AccountType type,
                                              String[] scopes) throws Exception {
        log.info("Starting interactive OAuth2 authentication for account type: {}", type);

        PublicClientApplication app = getOrCreatePca(type);

        InteractiveRequestParameters parameters = InteractiveRequestParameters
                .builder(new URI(REDIRECT_URI))
                .scopes(Set.of(scopes))
                .build();

        CompletableFuture<IAuthenticationResult> future =
                app.acquireToken(parameters);
        IAuthenticationResult result = future.get();

        log.info("Interactive authentication succeeded for account type: {}", type);
        persistToken(type, result);
        tokenCache.put(type, result);
        return result;
    }

    /**
     * Attempts a silent token refresh using MSAL's cached account/refresh token.
     *
     * <p>If the silent attempt fails (e.g. the refresh token has expired or the
     * token cache is empty) the method falls back to {@link #authenticate}.
     *
     * @param type   the account role whose token should be refreshed
     * @param scopes the OAuth2 scopes to request
     * @return a fresh {@link IAuthenticationResult}
     * @throws Exception if both silent and interactive auth fail
     */
    public IAuthenticationResult refreshToken(EmailAccount.AccountType type,
                                              String[] scopes) throws Exception {
        log.debug("Attempting silent token refresh for account type: {}", type);

        PublicClientApplication app = getOrCreatePca(type);

        // Attempt to locate a cached account inside MSAL's token cache.
        Optional<IAccount> cachedAccount = app.getAccounts().join().stream().findFirst();

        if (cachedAccount.isPresent()) {
            try {
                SilentParameters silentParams = SilentParameters
                        .builder(Set.of(scopes), cachedAccount.get())
                        .forceRefresh(true)
                        .build();

                IAuthenticationResult result = app.acquireTokenSilently(silentParams).get();
                log.info("Silent token refresh succeeded for account type: {}", type);
                persistToken(type, result);
                tokenCache.put(type, result);
                return result;
            } catch (Exception e) {
                log.warn("Silent token refresh failed for account type {}; "
                         + "falling back to interactive auth. Cause: {}", type, e.getMessage());
            }
        } else {
            log.debug("No cached MSAL account found for type {}; proceeding to interactive auth.", type);
        }

        // Fall back to interactive authentication.
        return authenticate(type, scopes);
    }

    /**
     * Returns a valid access token string, performing a proactive silent refresh
     * if the cached token will expire within the next 5 minutes.
     *
     * @param type   the account role
     * @param scopes the OAuth2 scopes required
     * @return a non-null, non-expired access token string
     * @throws Exception if token acquisition fails
     */
    public String getValidToken(EmailAccount.AccountType type,
                                String[] scopes) throws Exception {
        IAuthenticationResult cached = tokenCache.get(type);

        if (cached != null) {
            Date expiresOn = cached.expiresOnDate();
            boolean nearExpiry = expiresOn != null
                    && (expiresOn.getTime() - System.currentTimeMillis()) < TOKEN_EXPIRY_BUFFER_MS;

            if (!nearExpiry) {
                log.debug("Returning cached access token for account type: {} (expires: {})",
                          type, expiresOn);
                return cached.accessToken();
            }
            log.debug("Cached token for type {} is near expiry ({}); refreshing proactively.",
                      type, expiresOn);
        } else {
            log.debug("No in-memory token cached for type {}; initiating authentication.", type);
        }

        IAuthenticationResult fresh = refreshToken(type, scopes);
        return fresh.accessToken();
    }

    /**
     * Returns {@code true} if a non-expired token is present in the in-memory
     * cache for the given account type.
     *
     * <p>This check is intentionally lightweight (no I/O, no network) and is
     * suitable for UI state queries.  Use {@link #getValidToken} for an
     * authoritative token that triggers refresh if needed.
     *
     * @param type the account role to check
     * @return {@code true} if a valid, non-expired token is cached
     */
    public boolean hasValidToken(EmailAccount.AccountType type) {
        IAuthenticationResult cached = tokenCache.get(type);
        if (cached == null) {
            return false;
        }
        Date expiresOn = cached.expiresOnDate();
        if (expiresOn == null) {
            // Treat unknown expiry as expired (fail-safe).
            return false;
        }
        boolean valid = expiresOn.getTime() > System.currentTimeMillis();
        log.debug("hasValidToken({}) = {} (expiresOn: {})", type, valid, expiresOn);
        return valid;
    }

    /**
     * Clears all in-memory cached tokens and removes persisted credentials for
     * the given account type from the {@link CredentialStore}.
     *
     * <p>After this call the user will need to re-authenticate interactively.
     *
     * @param type    the account role to sign out
     * @throws Exception if the credential store cannot be updated
     */
    public void revokeTokens(EmailAccount.AccountType type) throws Exception {
        log.info("Revoking tokens for account type: {}", type);

        tokenCache.remove(type);

        // Remove from MSAL internal cache as well.
        PublicClientApplication app = pca.get(type);
        if (app != null) {
            app.getAccounts().join().forEach(account -> {
                try {
                    app.removeAccount(account).join();
                    log.debug("Removed MSAL account {} from internal cache for type {}.",
                              account.username(), type);
                } catch (Exception e) {
                    log.warn("Failed to remove MSAL account {} for type {}: {}",
                             account.username(), type, e.getMessage());
                }
            });
            pca.remove(type);
        }

        credentialStore.clearCredentials(type);
        log.info("Tokens revoked and credentials cleared for account type: {}", type);
    }

    // ------------------------------------------------------------------
    //  Private helpers
    // ------------------------------------------------------------------

    /**
     * Returns the {@link PublicClientApplication} for {@code type}, creating
     * and caching a new one if none exists yet.
     *
     * @param type the account role
     * @return a configured MSAL {@code PublicClientApplication}
     * @throws Exception if the MSAL builder fails
     */
    private PublicClientApplication getOrCreatePca(EmailAccount.AccountType type) throws Exception {
        if (!pca.containsKey(type)) {
            String authority = AUTHORITY_BASE + tenantId;
            log.debug("Creating PublicClientApplication for type {} — authority: {}", type, authority);

            PublicClientApplication app = PublicClientApplication
                    .builder(clientId)
                    .authority(authority)
                    .build();

            pca.put(type, app);
        }
        return pca.get(type);
    }

    /**
     * Stores the access and ID tokens from an {@link IAuthenticationResult}
     * into the {@link CredentialStore} under the given account type.
     *
     * <p>Token values are never written to the log.
     *
     * @param type   account role acting as the storage namespace
     * @param result the MSAL result to persist
     * @throws Exception if the credential store write fails
     */
    private void persistToken(EmailAccount.AccountType type,
                              IAuthenticationResult result) throws Exception {
        Map<String, String> creds = new java.util.HashMap<>();
        creds.put("accessToken",  result.accessToken());

        // MSAL does not expose the raw refresh token; store expiry for auditing.
        if (result.expiresOnDate() != null) {
            creds.put("expiresOn", String.valueOf(result.expiresOnDate().getTime()));
        }
        if (result.account() != null && result.account().username() != null) {
            creds.put("username", result.account().username());
        }

        credentialStore.saveCredentials(type, creds);
        log.debug("Token persisted to CredentialStore for account type: {}", type);
    }

}
