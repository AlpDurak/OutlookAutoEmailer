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
     * System prompt prefix that establishes the RAG context with Isik IEEE CS branding.
     * Gemini will always follow these rules before acting on the user's specific request.
     * Image library context is appended dynamically when available.
     */
    private static final String SYSTEM_PROMPT_PREFIX =
            "You are an expert HTML email template designer for I\u015f\u0131k University IEEE Computer Society " +
            "(I\u015f\u0131k IEEE CS). Your ONLY job is to produce ready-to-use HTML email body content.\n\n" +

            "BRAND GUIDELINES \u2014 I\u015f\u0131k IEEE CS:\n" +
            "- Primary color: #00629B (IEEE Blue)\n" +
            "- Secondary color: #1a2233 (Dark navy for text)\n" +
            "- Accent color: #F7A800 (IEEE Gold/Amber for highlights and CTAs)\n" +
            "- Background: White (#ffffff) with light gray sections (#f4f7fb)\n" +
            "- Font: Arial, 'Helvetica Neue', Helvetica, sans-serif\n" +
            "- Header: Use IEEE Blue background with white text, include the organization logo if available\n" +
            "- Buttons/CTAs: IEEE Gold (#F7A800) background with dark text, or IEEE Blue with white text\n" +
            "- Maintain a professional, academic, yet modern tone\n" +
            "- The organization name is: I\u015f\u0131k IEEE Computer Society (or I\u015f\u0131k IEEE CS for short)\n\n" +

            "STRICT OUTPUT RULES:\n" +
            "1. Return ONLY the inner HTML \u2014 NO <html>, <head>, or <body> wrapper tags.\n" +
            "2. Use INLINE CSS on every element (no <style> blocks \u2014 email clients strip them).\n" +
            "3. Use table-based layout for pixel-perfect rendering in Outlook, Gmail, and Apple Mail.\n" +
            "4. Include a professional header area and clear content sections.\n" +
            "5. Do NOT include a footer section \u2014 the application automatically adds a branded footer " +
            "with social links, unsubscribe, and attribution. Your output should end after the main content.\n" +
            "6. Where contextually appropriate, use these personalisation variables:\n" +
            "   {{firstName}}, {{lastName}}, {{email}}, {{company}}, {{jobTitle}}\n" +
            "7. Do NOT output markdown, code fences, explanations, or comments \u2014 raw HTML only.\n" +
            "8. Make the design clean, modern, and visually compelling following the IEEE brand colors.\n" +
            "9. When images are available in the image library, USE THEM by embedding their URLs in <img> tags.\n" +
            "   Always include width, height, alt text, and style=\"display:block;border:0;\" on images.\n" +
            "   If a logo image is available (tagged 'logo'), use it in the email header.\n\n";

    /**
     * Legacy system prompt kept for backward compatibility via {@link #generateAsync(String)}.
     * Delegates to the new prefix-based prompt with no image library context.
     */
    @SuppressWarnings("unused")
    private static final String SYSTEM_PROMPT = SYSTEM_PROMPT_PREFIX + "User request: ";

    /**
     * System prompt for performance analysis.  Gemini acts as a data analyst
     * reviewing email campaign metrics and providing actionable insights.
     */
    private static final String ANALYST_SYSTEM_PROMPT =
            "You are a senior email campaign performance analyst reviewing metrics for a mass-email " +
            "desktop application.  You receive JSON data containing batch metrics, failure reasons, " +
            "and group performance.\n\n" +
            "RULES:\n" +
            "1. Provide 3-5 concise, actionable insights based on the data.\n" +
            "2. Highlight any concerning trends (high failure rates, declining open rates, etc.).\n" +
            "3. Suggest specific improvements (send time optimisation, subject line changes, list hygiene).\n" +
            "4. Use plain text, no markdown formatting.\n" +
            "5. Keep the total response under 300 words.\n" +
            "6. If data is sparse, say so and recommend collecting more before drawing conclusions.\n\n" +
            "Campaign data:\n";

    private GeminiEmailAgent() {}

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the {@code GEMINI_API_KEY} is present and non-blank
     * in the {@code .env} file.
     */
    public static boolean isConfigured() {
        return DotEnvLoader.has("GEMINI_API_KEY");
    }

    /**
     * Sends campaign performance data (as a JSON string) to Gemini for analysis
     * and returns the AI-generated insights.
     *
     * @param contextJson JSON string containing batch metrics, failure reasons, etc.
     * @return a CompletableFuture resolving to the analysis text
     */
    public static CompletableFuture<String> analyzePerformanceAsync(String contextJson) {
        return CompletableFuture.supplyAsync(() -> {
            String apiKey = DotEnvLoader.get("GEMINI_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException(
                        "GEMINI_API_KEY is not set in your .env file.");
            }
            try {
                String body = buildRequestJson(ANALYST_SYSTEM_PROMPT + contextJson);
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
                    log.warn("Gemini analytics API returned {}: {}", resp.statusCode(), resp.body());
                    throw new RuntimeException("Gemini API error " + resp.statusCode() + ": " + errMsg);
                }
                return extractText(resp.body());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Gemini analytics request failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Generates HTML email body content from a natural-language prompt.
     * Backward-compatible entry point that delegates to
     * {@link #generateWithLibraryAsync(String, String)} with no image context.
     *
     * @param userPrompt the user's description of the email they want
     * @return a CompletableFuture resolving to the HTML string
     */
    public static CompletableFuture<String> generateAsync(String userPrompt) {
        return generateWithLibraryAsync(userPrompt, null);
    }

    /**
     * Generates HTML email body content from a natural-language prompt,
     * optionally including image library context so Gemini can embed
     * available images in the generated template.
     *
     * @param userPrompt          the user's description of the email they want
     * @param imageLibraryContext  a description of available images (from
     *                             {@link com.outlookautoemailier.model.ImageLibraryStore#buildGeminiContext()}),
     *                             or {@code null} / blank to omit
     * @return a CompletableFuture resolving to the HTML string
     */
    public static CompletableFuture<String> generateWithLibraryAsync(String userPrompt,
                                                                      String imageLibraryContext) {
        return CompletableFuture.supplyAsync(() -> {
            String apiKey = DotEnvLoader.get("GEMINI_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException(
                        "GEMINI_API_KEY is not set in your .env file. " +
                        "Add: GEMINI_API_KEY=your_key_here");
            }
            try {
                // Build the full prompt: branding prefix + optional image context + user request
                StringBuilder fullPrompt = new StringBuilder(SYSTEM_PROMPT_PREFIX);
                if (imageLibraryContext != null && !imageLibraryContext.isBlank()) {
                    fullPrompt.append(imageLibraryContext).append("\n\n");
                }
                fullPrompt.append("User request: ").append(userPrompt);

                String body = buildRequestJson(fullPrompt.toString());
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

    /**
     * Extracts raw text from Gemini's JSON response (no HTML cleanup).
     * Used for analytics insights where the response is plain text.
     */
    private static String extractText(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        String text = root.at("/candidates/0/content/parts/0/text").asText(null);
        if (text == null || text.isBlank()) {
            throw new RuntimeException("Gemini returned an empty response.");
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
