package com.outlookautoemailier.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Implements Google OAuth2 Authorization Code + PKCE for desktop applications.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Generate PKCE {@code code_verifier} + {@code code_challenge}.</li>
 *   <li>Start a temporary HTTP server on {@code http://localhost:8766/callback}.</li>
 *   <li>Open the default browser to Google's authorization URL.</li>
 *   <li>Wait (up to 5 minutes) for the authorization code callback.</li>
 *   <li>Exchange the code for {@code access_token} + {@code refresh_token}.</li>
 *   <li>Persist tokens in {@link CredentialStore} under the "GMAIL" key.</li>
 * </ol>
 *
 * <p>2FA is handled transparently by the Google login UI in the browser.</p>
 */
public class GoogleOAuth2Helper {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuth2Helper.class);

    private static final String AUTH_URL   = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL  = "https://oauth2.googleapis.com/token";
    private static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke";
    private static final String REDIRECT_URI = "http://localhost:8766/callback";
    /** Gmail SMTP scope. */
    private static final String SCOPE = "https://mail.google.com/";

    private static final int CALLBACK_PORT = 8766;
    private static final int AUTH_TIMEOUT_MINUTES = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CredentialStore credentialStore;
    private final String clientId;
    private final String clientSecret;

    public GoogleOAuth2Helper(CredentialStore credentialStore, String clientId, String clientSecret) {
        this.credentialStore = credentialStore;
        this.clientId        = clientId;
        this.clientSecret    = clientSecret;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Result returned after a successful OAuth2 flow.
     */
    public record TokenResult(String accessToken, String refreshToken,
                              long expiresAtEpochSeconds, String email) {}

    /**
     * Starts the interactive browser-based OAuth2 login flow.
     * Blocks until the user completes login (or timeout).
     *
     * @return a {@link TokenResult} with access and refresh tokens
     * @throws Exception on auth failure or timeout
     */
    public TokenResult authenticate() throws Exception {
        // 1. Generate PKCE pair
        String codeVerifier  = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);

        // 2. Start local callback server
        CompletableFuture<String> codeFuture = new CompletableFuture<>();
        HttpServer callbackServer = startCallbackServer(codeFuture);

        try {
            // 3. Build and open the authorization URL
            String authUrl = buildAuthUrl(codeChallenge);
            log.info("Opening Google auth URL in browser...");
            openBrowser(authUrl);

            // 4. Wait for callback
            String code = codeFuture.get(AUTH_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (code == null || code.isBlank()) throw new Exception("No authorization code received.");

            // 5. Exchange code for tokens
            TokenResult result = exchangeCodeForTokens(code, codeVerifier);

            // 6. Persist to CredentialStore
            Map<String, String> creds = new HashMap<>();
            creds.put("accessToken",  result.accessToken());
            creds.put("refreshToken", result.refreshToken());
            creds.put("expiresAt",    String.valueOf(result.expiresAtEpochSeconds()));
            creds.put("email",        result.email() != null ? result.email() : "");
            creds.put("clientId",     clientId);
            creds.put("clientSecret", clientSecret);
            credentialStore.saveCredentials(
                com.outlookautoemailier.model.EmailAccount.AccountType.SENDER, creds);
            log.info("Google OAuth2 tokens persisted to CredentialStore.");
            return result;

        } finally {
            callbackServer.stop(0);
        }
    }

    /**
     * Silently refreshes the access token using a stored refresh token.
     * Returns {@code null} if no refresh token is stored.
     */
    public TokenResult refreshAccessToken() throws Exception {
        Map<String, String> stored;
        try {
            stored = credentialStore.loadCredentials(
                com.outlookautoemailier.model.EmailAccount.AccountType.SENDER);
        } catch (Exception e) {
            return null;
        }

        String refreshToken = stored.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) return null;

        String storedClientId     = stored.getOrDefault("clientId",     clientId);
        String storedClientSecret = stored.getOrDefault("clientSecret", clientSecret);

        String body = "client_id="     + enc(storedClientId)
                    + "&client_secret=" + enc(storedClientSecret)
                    + "&refresh_token=" + enc(refreshToken)
                    + "&grant_type=refresh_token";

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode json = MAPPER.readTree(resp.body());

        if (json.has("error")) {
            log.warn("Google token refresh failed: {}", json.path("error_description").asText());
            return null;
        }

        String newAccess = json.path("access_token").asText();
        long expiresIn   = json.path("expires_in").asLong(3600);
        long expiresAt   = Instant.now().getEpochSecond() + expiresIn;
        String email     = stored.getOrDefault("email", "");

        // Update stored tokens
        Map<String, String> updated = new HashMap<>(stored);
        updated.put("accessToken", newAccess);
        updated.put("expiresAt",   String.valueOf(expiresAt));
        credentialStore.saveCredentials(
            com.outlookautoemailier.model.EmailAccount.AccountType.SENDER, updated);

        log.info("Google access token refreshed successfully.");
        return new TokenResult(newAccess, refreshToken, expiresAt, email);
    }

    /**
     * Loads stored Google tokens and returns them if the access token is still
     * valid (or successfully refreshed). Returns {@code null} if no valid session.
     */
    public TokenResult tryRestoreSession() {
        try {
            Map<String, String> stored = credentialStore.loadCredentials(
                com.outlookautoemailier.model.EmailAccount.AccountType.SENDER);
            if (stored.isEmpty() || !stored.containsKey("refreshToken")) return null;
            if (!stored.containsKey("clientId")) return null; // not a Google session

            long expiresAt = Long.parseLong(stored.getOrDefault("expiresAt", "0"));
            boolean expired = Instant.now().getEpochSecond() >= expiresAt - 300;

            if (!expired) {
                return new TokenResult(
                    stored.get("accessToken"),
                    stored.get("refreshToken"),
                    expiresAt,
                    stored.getOrDefault("email", ""));
            }
            // Try silent refresh
            return refreshAccessToken();
        } catch (Exception e) {
            log.debug("Google session restore failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private String buildAuthUrl(String codeChallenge) {
        return AUTH_URL
             + "?client_id="             + enc(clientId)
             + "&redirect_uri="          + enc(REDIRECT_URI)
             + "&response_type=code"
             + "&scope="                 + enc(SCOPE)
             + "&access_type=offline"
             + "&prompt=consent"
             + "&code_challenge="        + codeChallenge
             + "&code_challenge_method=S256";
    }

    private HttpServer startCallbackServer(CompletableFuture<String> codeFuture) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", CALLBACK_PORT), 0);
        server.createContext("/callback", exchange -> {
            try {
                String query = exchange.getRequestURI().getQuery();
                String code = extractParam(query, "code");
                String html = "<html><body style='font-family:sans-serif;text-align:center;padding:40px'>"
                            + "<h2>&#10003; Authentication Successful</h2>"
                            + "<p>You can close this window and return to the app.</p>"
                            + "</body></html>";
                exchange.sendResponseHeaders(200, html.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(html.getBytes()); }
                codeFuture.complete(code != null ? code : "");
            } catch (Exception e) {
                codeFuture.completeExceptionally(e);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "google-oauth-callback");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        log.debug("Google OAuth callback server started on port {}", CALLBACK_PORT);
        return server;
    }

    private TokenResult exchangeCodeForTokens(String code, String codeVerifier) throws Exception {
        String body = "client_id="     + enc(clientId)
                    + "&client_secret=" + enc(clientSecret)
                    + "&code="          + enc(code)
                    + "&code_verifier=" + enc(codeVerifier)
                    + "&redirect_uri="  + enc(REDIRECT_URI)
                    + "&grant_type=authorization_code";

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode json = MAPPER.readTree(resp.body());

        if (json.has("error")) {
            throw new Exception("Token exchange failed: " + json.path("error_description").asText(resp.body()));
        }

        String access  = json.path("access_token").asText();
        String refresh = json.path("refresh_token").asText();
        long expiresIn = json.path("expires_in").asLong(3600);
        long expiresAt = Instant.now().getEpochSecond() + expiresIn;

        // Decode email from id_token if present
        String email = "";
        String idToken = json.path("id_token").asText("");
        if (!idToken.isBlank()) {
            try {
                String[] parts = idToken.split("\\.");
                if (parts.length >= 2) {
                    String payload = new String(Base64.getUrlDecoder().decode(
                        parts[1] + "==".substring(0, (4 - parts[1].length() % 4) % 4)));
                    JsonNode claims = MAPPER.readTree(payload);
                    email = claims.path("email").asText("");
                }
            } catch (Exception e) { /* ignore */ }
        }

        return new TokenResult(access, refresh, expiresAt, email);
    }

    private static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String generateCodeChallenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
            verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String extractParam(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            } else {
                // Fallback: try system command
                Runtime.getRuntime().exec(new String[]{"rundll32", "url.dll,FileProtocolHandler", url});
            }
        } catch (Exception e) {
            log.warn("Could not open browser automatically. Navigate to: {}", url);
        }
    }
}
