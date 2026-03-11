package com.outlookautoemailier.smtp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Uploads Base64 image data to imgbb.com and returns the public hosted URL.
 * Requires a free API key from https://imgbb.com/signup
 */
public class ImageHostingService {

    private static final Logger log = LoggerFactory.getLogger(ImageHostingService.class);
    private static final String IMGBB_URL = "https://api.imgbb.com/1/upload";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ImageHostingService() {}

    /**
     * Uploads a Base64-encoded image to imgbb and returns the hosted URL.
     *
     * @param base64Data raw Base64 image string (no data URI prefix)
     * @param apiKey     imgbb API key
     * @return public HTTPS URL of the uploaded image
     * @throws Exception if the upload fails or the API returns an error
     */
    public static String uploadBase64(String base64Data, String apiKey) throws Exception {
        String body = "image=" + URLEncoder.encode(base64Data, StandardCharsets.UTF_8);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(IMGBB_URL + "?key=" + apiKey))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = MAPPER.readTree(response.body());

        if (!json.path("success").asBoolean(false)) {
            throw new RuntimeException("imgbb upload failed (status " + response.statusCode() + "): " + response.body());
        }
        String url = json.path("data").path("url").asText();
        log.info("Image uploaded to imgbb: {}", url);
        return url;
    }
}
