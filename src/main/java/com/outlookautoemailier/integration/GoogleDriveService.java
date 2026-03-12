package com.outlookautoemailier.integration;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.outlookautoemailier.config.DotEnvLoader;

/**
 * Google Drive API v3 operations via REST (no SDK dependency).
 *
 * <p>Manages a dedicated "OutlookAutoEmailer Images" folder in the user's
 * Google Drive.  Images uploaded through this service are shared publicly
 * ("anyone with link can view") so they can be embedded in HTML emails.
 *
 * <h3>Authentication</h3>
 * <p>Uses OAuth2 Authorization Code + PKCE flow, same pattern as
 * {@link com.outlookautoemailier.security.GoogleOAuth2Helper} but with
 * a different scope ({@code drive.file}) and callback port (8767).
 * Tokens are persisted to {@code ~/.outlookautoemailier/google-drive-tokens.json}.</p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Call {@link #tryRestoreSession()} on startup to silently restore tokens.</li>
 *   <li>If that returns false, call {@link #authenticate()} to launch the browser flow.</li>
 *   <li>Once authenticated, use {@link #uploadFile}, {@link #uploadBase64Image},
 *       {@link #listImages}, {@link #deleteFile}.</li>
 * </ol>
 */
public class GoogleDriveService {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Singleton ────────────────────────────────────────────────────────────

    private static volatile GoogleDriveService INSTANCE;

    public static GoogleDriveService getInstance() {
        if (INSTANCE == null) {
            synchronized (GoogleDriveService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new GoogleDriveService();
                }
            }
        }
        return INSTANCE;
    }

    // ── OAuth constants ──────────────────────────────────────────────────────

    private static final String AUTH_URL     = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL    = "https://oauth2.googleapis.com/token";
    private static final String REDIRECT_URI = "http://localhost:8767/callback";
    private static final String SCOPE        = "https://www.googleapis.com/auth/drive.file openid email";
    private static final int    CALLBACK_PORT          = 8767;
    private static final int    AUTH_TIMEOUT_MINUTES    = 5;

    // ── Drive API constants ──────────────────────────────────────────────────

    private static final String DRIVE_FILES_URL  = "https://www.googleapis.com/drive/v3/files";
    private static final String DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart";
    private static final String FOLDER_NAME      = "OutlookAutoEmailer Images";
    private static final String FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";

    // ── Token persistence ────────────────────────────────────────────────────

    private static final Path TOKEN_PATH = Path.of(
            System.getProperty("user.home"), ".outlookautoemailier", "google-drive-tokens.json");

    // ── State ────────────────────────────────────────────────────────────────

    private String accessToken;
    private String refreshToken;
    private long   expiresAtEpochSeconds;
    private String imageFolderId;

    private GoogleDriveService() {}

    // ── Public API — Authentication ──────────────────────────────────────────

    /**
     * Returns the direct viewable URL for a Google Drive file.
     *
     * @param fileId the Google Drive file ID
     * @return a URL that serves the file content directly
     */
    public static String getDirectUrl(String fileId) {
        return "https://drive.google.com/uc?export=view&id=" + fileId;
    }

    /**
     * Returns {@code true} if a valid (non-expired) Drive access token is available.
     */
    public boolean isAuthenticated() {
        return accessToken != null
                && !accessToken.isBlank()
                && Instant.now().getEpochSecond() < expiresAtEpochSeconds - 60;
    }

    /**
     * Tries to restore a session from saved tokens, refreshing if needed.
     *
     * @return {@code true} if a valid session was restored
     */
    public boolean tryRestoreSession() {
        try {
            if (!Files.exists(TOKEN_PATH)) return false;
            JsonNode json = MAPPER.readTree(TOKEN_PATH.toFile());
            accessToken  = json.path("access_token").asText("");
            refreshToken = json.path("refresh_token").asText("");
            expiresAtEpochSeconds = json.path("expires_at").asLong(0);

            if (accessToken.isBlank() || refreshToken.isBlank()) return false;

            // Check if expired (with 5-minute buffer)
            if (Instant.now().getEpochSecond() >= expiresAtEpochSeconds - 300) {
                return refreshAccessToken();
            }

            // Restore cached folder ID
            imageFolderId = json.path("folder_id").asText(null);

            log.info("Google Drive session restored from saved tokens.");
            return true;
        } catch (Exception e) {
            log.debug("Drive session restore failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Starts the interactive browser-based OAuth2 login flow for Google Drive.
     * Blocks until the user completes login (or timeout).
     *
     * @throws Exception on auth failure or timeout
     */
    public void authenticate() throws Exception {
        String clientId     = DotEnvLoader.get("GOOGLE_CLIENT_ID");
        String clientSecret = DotEnvLoader.get("GOOGLE_CLIENT_SECRET");
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET must be set in .env");
        }

        // 1. Generate PKCE pair
        String codeVerifier  = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);

        // 2. Start local callback server
        CompletableFuture<String> codeFuture = new CompletableFuture<>();
        HttpServer callbackServer = startCallbackServer(codeFuture);

        try {
            // 3. Build and open the authorization URL
            String authUrl = AUTH_URL
                    + "?client_id="             + enc(clientId)
                    + "&redirect_uri="          + enc(REDIRECT_URI)
                    + "&response_type=code"
                    + "&scope="                 + enc(SCOPE)
                    + "&access_type=offline"
                    + "&prompt=consent"
                    + "&code_challenge="        + codeChallenge
                    + "&code_challenge_method=S256";

            log.info("Opening Google Drive auth URL in browser...");
            openBrowser(authUrl);

            // 4. Wait for callback
            String code = codeFuture.get(AUTH_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (code == null || code.isBlank()) {
                throw new Exception("No authorization code received.");
            }

            // 5. Exchange code for tokens
            exchangeCodeForTokens(code, codeVerifier, clientId, clientSecret);

            // 6. Persist tokens
            persistTokens();

            log.info("Google Drive OAuth2 authentication successful.");
        } finally {
            callbackServer.stop(0);
        }
    }

    // ── Public API — Drive Operations ────────────────────────────────────────

    /**
     * Creates the "OutlookAutoEmailer Images" folder in Drive root if it
     * does not already exist.  Caches the folder ID for subsequent calls.
     *
     * @return the Drive folder ID
     * @throws Exception if the API call fails
     */
    public String getOrCreateImageFolder() throws Exception {
        ensureAuthenticated();

        if (imageFolderId != null) {
            return imageFolderId;
        }

        // Search for existing folder
        String query = "name='" + FOLDER_NAME + "' and mimeType='" + FOLDER_MIME_TYPE
                     + "' and trashed=false";
        HttpClient client = newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_FILES_URL + "?q=" + enc(query) + "&fields=files(id,name)"))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        checkResponse(resp, "search for image folder");

        JsonNode files = MAPPER.readTree(resp.body()).path("files");
        if (files.isArray() && !files.isEmpty()) {
            imageFolderId = files.get(0).path("id").asText();
            persistTokens(); // cache folder ID
            log.info("Found existing Drive folder: {} ({})", FOLDER_NAME, imageFolderId);
            return imageFolderId;
        }

        // Create new folder
        String metadata = MAPPER.writeValueAsString(Map.of(
                "name", FOLDER_NAME,
                "mimeType", FOLDER_MIME_TYPE));

        HttpRequest createReq = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_FILES_URL))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(metadata))
                .build();
        HttpResponse<String> createResp = client.send(createReq, HttpResponse.BodyHandlers.ofString());
        checkResponse(createResp, "create image folder");

        imageFolderId = MAPPER.readTree(createResp.body()).path("id").asText();
        persistTokens(); // cache folder ID
        log.info("Created Drive folder: {} ({})", FOLDER_NAME, imageFolderId);
        return imageFolderId;
    }

    /**
     * Uploads a file to the images folder using multipart upload.
     * Sets sharing to "anyone with link can view".
     *
     * @param data     the raw file bytes
     * @param fileName the desired file name in Drive
     * @param mimeType the MIME type (e.g. "image/png")
     * @return the Google Drive file ID
     * @throws Exception if the upload or sharing call fails
     */
    public String uploadFile(byte[] data, String fileName, String mimeType) throws Exception {
        ensureAuthenticated();
        String folderId = getOrCreateImageFolder();

        // Build multipart request body
        String boundary = "----DriveUpload" + UUID.randomUUID().toString().replace("-", "");
        String metadataJson = MAPPER.writeValueAsString(Map.of(
                "name", fileName,
                "parents", List.of(folderId)));

        // Assemble multipart body
        byte[] headerBytes = ("--" + boundary + "\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                + metadataJson + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        byte[] body = new byte[headerBytes.length + data.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
        System.arraycopy(data, 0, body, headerBytes.length, data.length);
        System.arraycopy(footerBytes, 0, body, headerBytes.length + data.length, footerBytes.length);

        HttpClient client = newHttpClient();
        HttpRequest uploadReq = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_UPLOAD_URL))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "multipart/related; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> uploadResp = client.send(uploadReq, HttpResponse.BodyHandlers.ofString());
        checkResponse(uploadResp, "upload file to Drive");

        String fileId = MAPPER.readTree(uploadResp.body()).path("id").asText();
        log.info("Uploaded file '{}' to Drive: {}", fileName, fileId);

        // Share publicly — anyone with link can view
        sharePublicly(client, fileId);

        return fileId;
    }

    /**
     * Uploads a Base64-encoded image to Drive and returns the public direct URL.
     * This is the primary method used by {@link com.outlookautoemailier.smtp.SmtpSender}
     * to replace inline data-URI images in HTML emails.
     *
     * @param base64Data    raw Base64 image data (without data-URI prefix)
     * @param suggestedName a suggested file name (e.g. "inline-image-1.png")
     * @return the public direct URL: {@code https://drive.google.com/uc?export=view&id=FILE_ID}
     * @throws Exception if the upload fails
     */
    public String uploadBase64Image(String base64Data, String suggestedName) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(base64Data);

        // Infer MIME type from the first few bytes (magic numbers)
        String mimeType = inferMimeType(decoded, suggestedName);

        String fileId = uploadFile(decoded, suggestedName, mimeType);
        return getDirectUrl(fileId);
    }

    /**
     * Lists all files in the images folder.
     *
     * @return list of Drive file metadata records
     * @throws Exception if the API call fails
     */
    public List<DriveFile> listImages() throws Exception {
        ensureAuthenticated();
        String folderId = getOrCreateImageFolder();

        String query = "'" + folderId + "' in parents and trashed=false";
        String fields = "files(id,name,mimeType,thumbnailLink,size,createdTime)";

        HttpClient client = newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_FILES_URL
                        + "?q=" + enc(query)
                        + "&fields=" + enc(fields)
                        + "&orderBy=createdTime desc"
                        + "&pageSize=100"))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        checkResponse(resp, "list images from Drive");

        List<DriveFile> result = new ArrayList<>();
        JsonNode files = MAPPER.readTree(resp.body()).path("files");
        if (files.isArray()) {
            for (JsonNode f : files) {
                result.add(new DriveFile(
                        f.path("id").asText(),
                        f.path("name").asText(),
                        f.path("mimeType").asText(),
                        f.path("thumbnailLink").asText(null),
                        f.path("size").asLong(0),
                        f.path("createdTime").asText("")));
            }
        }
        return result;
    }

    /**
     * Deletes a file from Google Drive by its file ID.
     *
     * @param fileId the Drive file ID to delete
     * @throws Exception if the API call fails
     */
    public void deleteFile(String fileId) throws Exception {
        ensureAuthenticated();

        HttpClient client = newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_FILES_URL + "/" + fileId))
                .header("Authorization", "Bearer " + accessToken)
                .DELETE()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        // DELETE returns 204 No Content on success
        if (resp.statusCode() != 204 && resp.statusCode() != 200) {
            log.warn("Drive delete returned {}: {}", resp.statusCode(), resp.body());
        } else {
            log.info("Deleted Drive file: {}", fileId);
        }
    }

    /**
     * Metadata record for a file stored in Google Drive.
     */
    public record DriveFile(String id, String name, String mimeType,
                            String thumbnailLink, long size, String createdTime) {}

    // ── Private — OAuth helpers ──────────────────────────────────────────────

    private void exchangeCodeForTokens(String code, String codeVerifier,
                                        String clientId, String clientSecret) throws Exception {
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
            throw new Exception("Token exchange failed: "
                    + json.path("error_description").asText(resp.body()));
        }

        accessToken  = json.path("access_token").asText();
        refreshToken = json.path("refresh_token").asText();
        long expiresIn = json.path("expires_in").asLong(3600);
        expiresAtEpochSeconds = Instant.now().getEpochSecond() + expiresIn;
    }

    private boolean refreshAccessToken() {
        try {
            String clientId     = DotEnvLoader.get("GOOGLE_CLIENT_ID");
            String clientSecret = DotEnvLoader.get("GOOGLE_CLIENT_SECRET");
            if (clientId.isBlank() || clientSecret.isBlank()) return false;

            String body = "client_id="     + enc(clientId)
                        + "&client_secret=" + enc(clientSecret)
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
                log.warn("Drive token refresh failed: {}", json.path("error_description").asText());
                return false;
            }

            accessToken = json.path("access_token").asText();
            long expiresIn = json.path("expires_in").asLong(3600);
            expiresAtEpochSeconds = Instant.now().getEpochSecond() + expiresIn;
            // refresh_token is not returned on refresh — keep existing

            persistTokens();
            log.info("Google Drive access token refreshed.");
            return true;
        } catch (Exception e) {
            log.warn("Drive token refresh exception: {}", e.getMessage());
            return false;
        }
    }

    private void ensureAuthenticated() throws Exception {
        if (!isAuthenticated()) {
            // Try a silent refresh
            if (refreshToken != null && !refreshToken.isBlank()) {
                if (!refreshAccessToken()) {
                    throw new IllegalStateException(
                            "Google Drive is not authenticated. Call authenticate() first.");
                }
            } else {
                throw new IllegalStateException(
                        "Google Drive is not authenticated. Call authenticate() first.");
            }
        }
    }

    private void persistTokens() {
        try {
            Files.createDirectories(TOKEN_PATH.getParent());
            Map<String, Object> data = new HashMap<>();
            data.put("access_token",  accessToken);
            data.put("refresh_token", refreshToken);
            data.put("expires_at",    expiresAtEpochSeconds);
            if (imageFolderId != null) {
                data.put("folder_id", imageFolderId);
            }
            Files.writeString(TOKEN_PATH,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            log.error("Failed to persist Drive tokens: {}", e.getMessage());
        }
    }

    // ── Private — Drive helpers ──────────────────────────────────────────────

    private void sharePublicly(HttpClient client, String fileId) throws Exception {
        String permJson = MAPPER.writeValueAsString(Map.of(
                "type", "anyone",
                "role", "reader"));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_FILES_URL + "/" + fileId + "/permissions"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(permJson))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("Failed to share file {} publicly: {} {}", fileId, resp.statusCode(), resp.body());
        } else {
            log.debug("File {} shared publicly.", fileId);
        }
    }

    private static String inferMimeType(byte[] data, String fileName) {
        // Check magic bytes
        if (data.length >= 8) {
            if (data[0] == (byte) 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
                return "image/png";
            }
            if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8) {
                return "image/jpeg";
            }
            if (data[0] == 0x47 && data[1] == 0x49 && data[2] == 0x46) {
                return "image/gif";
            }
            if (data[0] == 0x52 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x46) {
                return "image/webp";
            }
        }
        // Fall back to file extension
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".png"))  return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".gif"))  return "image/gif";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".svg"))  return "image/svg+xml";
        }
        return "image/png"; // safe default
    }

    private static void checkResponse(HttpResponse<String> resp, String action) throws Exception {
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("Drive API error during " + action
                    + " (HTTP " + resp.statusCode() + "): " + resp.body());
        }
    }

    private static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    // ── Private — OAuth callback + PKCE ──────────────────────────────────────

    private HttpServer startCallbackServer(CompletableFuture<String> codeFuture) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", CALLBACK_PORT), 0);
        server.createContext("/callback", exchange -> {
            try {
                String query = exchange.getRequestURI().getQuery();
                String code = extractParam(query, "code");
                String html = "<html><body style='font-family:sans-serif;text-align:center;padding:40px'>"
                            + "<h2>&#10003; Google Drive Connected</h2>"
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
            Thread t = new Thread(r, "gdrive-oauth-callback");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        log.debug("Google Drive OAuth callback server started on port {}", CALLBACK_PORT);
        return server;
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
                Runtime.getRuntime().exec(new String[]{"rundll32", "url.dll,FileProtocolHandler", url});
            }
        } catch (Exception e) {
            log.warn("Could not open browser automatically. Navigate to: {}", url);
        }
    }
}
