package com.outlookautoemailier.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.outlookautoemailier.config.DotEnvLoader;

/**
 * Calls the Google Gemini API to generate professional HTML email body content
 * from a natural-language prompt.
 *
 * <p>Reads {@code GEMINI_API_KEY} from the {@code .env} file.
 * Uses {@code gemini-2.0-flash} for fast, cost-effective generation.
 *
 * <h3>System prompt (RAG context)</h3>
 * The system prompt instructs Gemini to act as an HTML email template designer,
 * returning only the inner body HTML — no wrapping document tags, no markdown
 * fences, inline CSS throughout for email-client compatibility.
 */
public final class GeminiEmailAgent {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmailAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=";

    /**
     * System prompt that establishes the RAG context.  Gemini will always follow
     * these rules before acting on the user's specific request.
     */
    private static final String SYSTEM_PROMPT =
            "You are an expert HTML email template designer for a university mass-email application. " +
            "Your ONLY job is to produce ready-to-use HTML email body content.\n\n" +
            "STRICT OUTPUT RULES:\n" +
            "1. Return ONLY the inner HTML — NO <html>, <head>, or <body> wrapper tags.\n" +
            "2. Use INLINE CSS on every element (no <style> blocks — email clients strip them).\n" +
            "3. Use table-based layout for pixel-perfect rendering in Outlook, Gmail, and Apple Mail.\n" +
            "4. Include a professional header area, a clear content section, and a footer.\n" +
            "5. Where contextually appropriate, use these personalisation variables:\n" +
            "   {{firstName}}, {{lastName}}, {{email}}, {{company}}, {{jobTitle}}\n" +
            "6. Do NOT output markdown, code fences, explanations, or comments — raw HTML only.\n" +
            "7. Make the design clean, modern, and visually compelling.\n\n" +
            "User request: ";

    private GeminiEmailAgent() {}

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Generates HTML email body content from a natural-language prompt.
     *
     * @param userPrompt the user's description of the email they want
     * @return a CompletableFuture resolving to the HTML string
     */
    public static CompletableFuture<String> generateAsync(String userPrompt) {
        return CompletableFuture.supplyAsync(() -> {
            String apiKey = DotEnvLoader.get("GEMINI_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException(
                        "GEMINI_API_KEY is not set in your .env file. " +
                        "Add: GEMINI_API_KEY=your_key_here");
            }
            try {
                String body = buildRequestJson(SYSTEM_PROMPT + userPrompt);
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(GEMINI_URL + apiKey))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp =
                        client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() != 200) {
                    String errMsg = extractErrorMessage(resp.body());
                    log.warn("Gemini API returned {}: {}", resp.statusCode(), resp.body());
                    throw new RuntimeException("Gemini API error " + resp.statusCode() + ": " + errMsg);
                }
                return extractHtml(resp.body());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Gemini request failed: " + e.getMessage(), e);
            }
        });
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /** Builds the Gemini request JSON body using Jackson. */
    private static String buildRequestJson(String fullPrompt) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();

        ArrayNode contents = root.putArray("contents");
        ObjectNode content = contents.addObject();
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", fullPrompt);

        ObjectNode genConfig = root.putObject("generationConfig");
        genConfig.put("temperature", 0.7);
        genConfig.put("maxOutputTokens", 4096);

        return MAPPER.writeValueAsString(root);
    }

    /**
     * Extracts the generated text from Gemini's JSON response and cleans it up:
     * strips markdown fences if present, and extracts only the body content if
     * Gemini wrapped the output in a full HTML document.
     */
    private static String extractHtml(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        String text = root.at("/candidates/0/content/parts/0/text").asText(null);
        if (text == null || text.isBlank()) {
            throw new RuntimeException("Gemini returned an empty response.");
        }

        // Strip markdown code fences (```html ... ``` or ``` ... ```)
        text = text.replaceFirst("(?s)^\\s*```(?:html)?\\s*\n?", "");
        text = text.replaceFirst("(?s)\\s*```\\s*$", "");

        // If Gemini returned a full HTML document, extract just the body content
        Pattern bodyPat = Pattern.compile("(?is)<body[^>]*>(.*)</body>");
        Matcher m = bodyPat.matcher(text);
        if (m.find()) {
            text = m.group(1);
        }

        return text.trim();
    }

    /** Extracts a human-readable error message from Gemini's error JSON. */
    private static String extractErrorMessage(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode msg = root.at("/error/message");
            if (!msg.isMissingNode()) return msg.asText();
        } catch (Exception ignored) {}
        return json.substring(0, Math.min(200, json.length()));
    }
}
