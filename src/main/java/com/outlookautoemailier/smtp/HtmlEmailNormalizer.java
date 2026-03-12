package com.outlookautoemailier.smtp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Normalises an HTML email body for maximum cross-client compatibility.
 *
 * <h3>What it does</h3>
 * <ul>
 *   <li>Wraps the content in a table-based layout (required by Outlook / Word renderer).</li>
 *   <li>Adds a proper email DOCTYPE and UTF-8 meta tag.</li>
 *   <li>Moves any {@code <style>} block content inline on to matching elements.</li>
 *   <li>Ensures images have {@code display:block} and no border.</li>
 *   <li>Strips CSS properties that are unsupported in Outlook's Word rendering engine
 *       ({@code flexbox}, {@code grid}, CSS variables, etc.).</li>
 *   <li>Sets {@code font-family} and {@code font-size} defaults on the wrapper so
 *       Gmail's CSS reset does not strip them.</li>
 * </ul>
 *
 * <p>Input can be a fragment (just body HTML from the WYSIWYG editor) or a full document;
 * the output is always a complete, standalone HTML document ready to embed as the email body.</p>
 */
public class HtmlEmailNormalizer {

    private static final Logger log = LoggerFactory.getLogger(HtmlEmailNormalizer.class);

    /** Default safe sans-serif stack understood by every email client. */
    private static final String SAFE_FONT_STACK =
            "Arial, 'Helvetica Neue', Helvetica, sans-serif";

    /** Default font size that survives Gmail's CSS reset. */
    private static final String DEFAULT_FONT_SIZE = "14px";

    /** Default text colour. */
    private static final String DEFAULT_COLOR = "#333333";

    /** Maximum content width — keeps emails readable on wide displays. */
    private static final int MAX_WIDTH_PX = 640;

    private HtmlEmailNormalizer() {}

    /**
     * Normalises {@code rawHtml} into a cross-client-safe email document.
     *
     * @param rawHtml the raw HTML fragment or full document from the template editor
     * @return a complete, normalised HTML document as a string
     */
    public static String normalize(String rawHtml) {
        if (rawHtml == null || rawHtml.isBlank()) {
            return buildWrapper("");
        }

        try {
            // Parse leniently — handles both fragments and full documents
            Document doc = Jsoup.parse(rawHtml, "", Parser.htmlParser());
            doc.outputSettings().charset("UTF-8").prettyPrint(false);

            // Strip @import (Google Fonts etc.) and @font-face from style blocks —
            // these are blocked by most email clients and cause rendering issues.
            doc.select("style").forEach(styleEl -> {
                String css = styleEl.html();
                // Remove @import lines
                css = css.replaceAll("(?i)@import[^;]+;", "");
                // Remove @font-face blocks
                css = css.replaceAll("(?i)@font-face\\s*\\{[^}]*\\}", "");
                styleEl.html(css);
            });

            // Extract the inner body content
            Element body = doc.body();
            String bodyHtml = body != null ? body.html() : rawHtml;

            // Fix images: ensure display:block, remove default border, add max-width
            Document workDoc = Jsoup.parseBodyFragment(bodyHtml);
            workDoc.select("img").forEach(img -> {
                String existing = img.attr("style");
                StringBuilder style = new StringBuilder();
                if (!existing.isBlank()) {
                    style.append(existing);
                    if (!existing.endsWith(";")) style.append(";");
                }
                // Only add if not already set
                if (!existing.contains("display")) style.append("display:block;");
                if (!existing.contains("border")) style.append("border:0;");
                if (!existing.contains("max-width")) style.append("max-width:100%;height:auto;");
                img.attr("style", style.toString());
                // Always add alt attribute for accessibility
                if (img.attr("alt").isBlank()) img.attr("alt", "");
            });

            // Fix links: ensure colour is preserved (Gmail overrides link colour)
            workDoc.select("a").forEach(a -> {
                String existing = a.attr("style");
                if (!existing.contains("color")) {
                    a.attr("style", "color:#0078d4;" + existing);
                }
            });

            // Remove Outlook-incompatible properties from every inline style
            workDoc.select("[style]").forEach(el -> {
                String cleaned = removeOutlookIncompatible(el.attr("style"));
                el.attr("style", cleaned);
            });

            // Inline safe font-family on every text element that lacks one,
            // because Gmail strips <style> blocks and won't inherit from the wrapper.
            String[] textTags = {"p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "td", "div", "span", "a"};
            for (String tag : textTags) {
                workDoc.select(tag).forEach(el -> {
                    String existing = el.attr("style");
                    if (!existing.toLowerCase().contains("font-family")) {
                        String prefix = "font-family:" + SAFE_FONT_STACK + ";";
                        el.attr("style", prefix + (existing.isBlank() ? "" : existing));
                    }
                    // Also strip Outlook-incompatible from each element's inline style
                    String cleaned = removeOutlookIncompatible(el.attr("style"));
                    el.attr("style", cleaned);
                });
            }

            String cleanBodyHtml = workDoc.body().html();
            return buildWrapper(cleanBodyHtml);

        } catch (Exception e) {
            log.warn("HtmlEmailNormalizer failed, using original HTML: {}", e.getMessage());
            return buildWrapper(rawHtml);
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Wraps the body content in a table-based layout that is understood by all
     * major email clients including Outlook (which uses Word's rendering engine).
     */
    private static String buildWrapper(String bodyContent) {
        return "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\" "
             + "\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">\n"
             + "<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"en\">\n"
             + "<head>\n"
             + "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"/>\n"
             + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>\n"
             + "<!--[if mso]>"
             + "<xml><o:OfficeDocumentSettings>"
             + "<o:PixelsPerInch>96</o:PixelsPerInch>"
             + "</o:OfficeDocumentSettings></xml>"
             + "<![endif]-->\n"
             + "<style type=\"text/css\">\n"
             + "  body,#bodyTable{width:100%!important;margin:0!important;padding:0!important;}\n"
             + "  body{background-color:#f4f4f4;}\n"
             + "  img{-ms-interpolation-mode:bicubic;}\n"
             + "  a{word-wrap:break-word;}\n"
             + "  p{margin:0 0 1em 0;}\n"
             + "</style>\n"
             + "</head>\n"
             + "<body style=\"margin:0;padding:0;background-color:#f4f4f4;"
             + "font-family:" + SAFE_FONT_STACK + ";font-size:" + DEFAULT_FONT_SIZE + ";\">\n"
             + "<!--[if (gte mso 9)|(IE)]>"
             + "<table width=\"" + MAX_WIDTH_PX + "\" align=\"center\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td>"
             + "<![endif]-->\n"
             + "<table id=\"bodyTable\" width=\"100%\" border=\"0\" cellpadding=\"0\" cellspacing=\"0\" "
             + "style=\"max-width:" + MAX_WIDTH_PX + "px;margin:0 auto;\">\n"
             + "  <tr>\n"
             + "    <td align=\"left\" valign=\"top\" "
             + "style=\"padding:20px;background-color:#ffffff;"
             + "font-family:" + SAFE_FONT_STACK + ";"
             + "font-size:" + DEFAULT_FONT_SIZE + ";"
             + "color:" + DEFAULT_COLOR + ";\">\n"
             + bodyContent + "\n"
             + "    </td>\n"
             + "  </tr>\n"
             + "</table>\n"
             + "<!--[if (gte mso 9)|(IE)]></td></tr></table><![endif]-->\n"
             + "</body>\n"
             + "</html>";
    }

    /**
     * Strips CSS declaration values that Outlook / Word rendering engine does not support.
     * Keeps all other declarations intact.
     *
     * <p>Removed properties: {@code display:flex/grid}, CSS custom properties ({@code --*}),
     * flex/grid shorthands, {@code border-radius} (breaks Outlook Word renderer),
     * {@code box-shadow}, {@code text-shadow}, {@code background-image} with gradients,
     * {@code transition}, {@code animation}, and {@code transform}.</p>
     */
    private static String removeOutlookIncompatible(String style) {
        if (style == null || style.isBlank()) return style;

        String[] declarations = style.split(";");
        StringBuilder result = new StringBuilder();
        for (String decl : declarations) {
            String trimmed = decl.trim();
            if (trimmed.isEmpty()) continue;
            String lower = trimmed.toLowerCase();
            // Skip flexbox / grid layout declarations
            if (lower.matches(".*display\\s*:\\s*(flex|grid|inline-flex|inline-grid).*")) continue;
            // Skip CSS custom properties
            if (trimmed.startsWith("--")) continue;
            // Skip flex-direction, align-items, justify-content etc.
            if (lower.matches("(flex-|align-items|justify-content|grid-).*")) continue;
            // Skip border-radius (any form) — Outlook Word renderer ignores it, breaks layout
            if (lower.startsWith("border-radius") || lower.startsWith("border-top-left-radius")
                    || lower.startsWith("border-top-right-radius")
                    || lower.startsWith("border-bottom-left-radius")
                    || lower.startsWith("border-bottom-right-radius")) continue;
            // Skip box-shadow — not supported in Outlook
            if (lower.startsWith("box-shadow")) continue;
            // Skip text-shadow — not supported in Outlook
            if (lower.startsWith("text-shadow")) continue;
            // Skip background-image with CSS gradients — not supported in Outlook
            if (lower.startsWith("background-image") && lower.contains("gradient")) continue;
            // Skip transition / animation / transform — have no effect or not supported in email
            if (lower.startsWith("transition")) continue;
            if (lower.startsWith("animation")) continue;
            if (lower.startsWith("transform")) continue;
            result.append(trimmed).append(";");
        }
        return result.toString();
    }
}
