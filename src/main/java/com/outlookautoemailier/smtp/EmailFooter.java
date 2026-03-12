package com.outlookautoemailier.smtp;

public class EmailFooter {
    private EmailFooter() {}

    /**
     * Returns a table-based HTML footer compatible with Outlook, Gmail, and Apple Mail.
     *
     * @param senderEmail    the sender's email address (unused; kept for API compatibility)
     * @param recipientEmail the recipient's email address (encoded in the unsubscribe URL)
     * @return HTML string for the footer section
     */
    public static String generate(String senderEmail, String recipientEmail) {
        String rec  = (recipientEmail != null) ? recipientEmail : "";

        // Unsubscribe via Supabase edge function
        // The anon key is passed as apikey so the link works without an Authorization header.
        // The anon key is intentionally public (RLS restricts it to INSERT-only on this table).
        String anonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
                + ".eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InRnYmhnd2RncWlueHd4ZWRobm1jIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzMyOTI2NjEsImV4cCI6MjA4ODg2ODY2MX0"
                + ".wwlqMBYKU53gv2mG15MM70allTNevZut8Mkg5mOu4Mw";
        String unsubUrl = "https://tgbhgwdgqinxwxedhnmc.supabase.co/functions/v1/unsubscribe"
                + "?apikey=" + anonKey
                + "&email=" + java.net.URLEncoder.encode(rec, java.nio.charset.StandardCharsets.UTF_8);

        return
            // Divider line
            "<table width=\"100%\" border=\"0\" cellpadding=\"0\" cellspacing=\"0\" "
          + "style=\"border-top:2px solid #e2e8f0;margin-top:32px;\">"
          + "<tr><td style=\"padding:0;\"></td></tr></table>\n"

            // Footer content table
          + "<table width=\"100%\" border=\"0\" cellpadding=\"0\" cellspacing=\"0\" "
          + "style=\"background-color:#f8fafc;\">"
          + "<tr><td align=\"center\" style=\"padding:24px 20px;"
          + "font-family:Arial,'Helvetica Neue',Helvetica,sans-serif;"
          + "font-size:12px;color:#64748b;\">\n"

            // Social links row — nested table
          + "<table border=\"0\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:0 auto 16px auto;\">"
          + "<tr>"

            // Website button
          + "<td style=\"padding:0 6px;\">"
          + "<a href=\"https://isikieee.com.tr/\" target=\"_blank\" "
          + "style=\"display:inline-block;padding:9px 18px;"
          + "background-color:#2563eb;color:#ffffff;"
          + "font-family:Arial,sans-serif;font-size:13px;font-weight:bold;"
          + "text-decoration:none;border:none;\">"
          + "&#127760; Website"
          + "</a></td>"

            // Instagram button
          + "<td style=\"padding:0 6px;\">"
          + "<a href=\"https://instagram.com/isikieee\" target=\"_blank\" "
          + "style=\"display:inline-block;padding:9px 18px;"
          + "background-color:#e1306c;color:#ffffff;"
          + "font-family:Arial,sans-serif;font-size:13px;font-weight:bold;"
          + "text-decoration:none;border:none;\">"
          + "&#128247; @isikieee"
          + "</a></td>"

            // LinkedIn button
          + "<td style=\"padding:0 6px;\">"
          + "<a href=\"https://linkedin.com/company/isik-ieee\" target=\"_blank\" "
          + "style=\"display:inline-block;padding:9px 18px;"
          + "background-color:#0a66c2;color:#ffffff;"
          + "font-family:Arial,sans-serif;font-size:13px;font-weight:bold;"
          + "text-decoration:none;border:none;\">"
          + "&#128101; isik-ieee"
          + "</a></td>"

          + "</tr></table>\n"

            // Attribution
          + "<p style=\"margin:0 0 10px 0;"
          + "font-family:Arial,sans-serif;font-size:12px;color:#475569;\">"
          + "Developed by <strong>Alp Durak</strong>, I&#351;&#305;k IEEE Computer Society"
          + "</p>\n"

            // Unsubscribe
          + "<p style=\"margin:0;"
          + "font-family:Arial,sans-serif;font-size:11px;color:#94a3b8;\">"
          + "You are receiving this email because you are a member of our community. "
          + "<a href=\"" + unsubUrl + "\" "
          + "style=\"color:#2563eb;text-decoration:underline;\">Unsubscribe</a>"
          + "</p>\n"

          + "</td></tr></table>";
    }
}
