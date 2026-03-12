package com.outlookautoemailier.smtp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Replaces {@code <a href>} links in an HTML email body with Supabase
 * redirect URLs that record the click event before forwarding the user
 * to the original destination.
 *
 * <p>Unsubscribe links, {@code mailto:} links, and {@code tel:} links are
 * left untouched so that compliance-critical links are never intercepted.</p>
 */
public final class LinkTracker {

    private static final String SUPABASE_URL = "https://tgbhgwdgqinxwxedhnmc.supabase.co";
    private static final String ANON_KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
            + ".eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InRnYmhnd2RncWlueHd4ZWRobm1jIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzMyOTI2NjEsImV4cCI6MjA4ODg2ODY2MX0"
            + ".wwlqMBYKU53gv2mG15MM70allTNevZut8Mkg5mOu4Mw";

    private LinkTracker() {}

    /**
     * Parses {@code html} and replaces every trackable link with a Supabase
     * {@code track-click} redirect URL embedding {@code trackingId} and
     * {@code batchId}.
     *
     * @param html        the HTML email body
     * @param trackingId  per-recipient tracking ID (from {@code SentEmailRecord})
     * @param batchId     campaign batch ID (may be {@code null})
     * @return the HTML body with tracking links injected
     */
    public static String injectTrackingLinks(String html, String trackingId, String batchId) {
        if (html == null || html.isBlank()) return html;

        Document doc = Jsoup.parseBodyFragment(html);
        Elements links = doc.select("a[href]");

        for (Element a : links) {
            String href = a.attr("href").trim();
            if (href.isBlank()) continue;
            if (href.startsWith("mailto:") || href.startsWith("tel:")) continue;
            if (href.toLowerCase(java.util.Locale.ROOT).contains("unsubscribe")) continue;

            String encoded = URLEncoder.encode(href, StandardCharsets.UTF_8);
            String trackUrl = SUPABASE_URL + "/functions/v1/track-click"
                    + "?apikey=" + ANON_KEY
                    + "&tracking_id=" + URLEncoder.encode(trackingId, StandardCharsets.UTF_8)
                    + "&batch_id=" + URLEncoder.encode(batchId != null ? batchId : "", StandardCharsets.UTF_8)
                    + "&url=" + encoded;
            a.attr("href", trackUrl);
        }

        return doc.body().html();
    }
}
