package com.outlookautoemailier.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;

/**
 * Pre-flight DNS checker that verifies SPF, DKIM, and DMARC records are
 * properly configured for a sending domain.
 *
 * <p>Operators should call {@link #runPreflight(String)} before starting a
 * bulk-send campaign.  The resulting {@link PreflightReport} describes which
 * DNS records are present, lists any actionable warnings, and exposes a
 * {@link PreflightReport#isReadyToSend()} flag that is {@code true} only when
 * the minimum viable configuration (a valid SPF record) is in place.
 *
 * <h2>Records checked</h2>
 * <ul>
 *   <li><b>SPF</b> — TXT record at the domain root starting with {@code v=spf1}
 *       (RFC 7208).</li>
 *   <li><b>DKIM</b> — TXT record at
 *       {@code <selector>._domainkey.<domain>} (RFC 6376).  Common selectors are
 *       tried in order until one is found.</li>
 *   <li><b>DMARC</b> — TXT record at {@code _dmarc.<domain>} starting with
 *       {@code v=DMARC1} (RFC 7489).</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   DkimSpfChecker checker = new DkimSpfChecker();
 *   DkimSpfChecker.PreflightReport report = checker.runPreflight("company.com");
 *
 *   report.getWarnings().forEach(w -> log.warn("DNS preflight: {}", w));
 *   if (!report.isReadyToSend()) {
 *       throw new IllegalStateException("Domain is not ready to send — fix DNS first.");
 *   }
 * }</pre>
 */
public class DkimSpfChecker {

    private static final Logger log = LoggerFactory.getLogger(DkimSpfChecker.class);

    // ------------------------------------------------------------------
    //  DNS constants
    // ------------------------------------------------------------------

    /** Maximum milliseconds to wait for a DNS TXT response before giving up. */
    private static final int DNS_TIMEOUT_MS = 3000;

    /**
     * Ordered list of DKIM selectors to probe when no explicit selector array is
     * supplied.  The first selector that resolves to a TXT record wins.
     * Microsoft 365 uses {@code selector1} / {@code selector2} by default.
     */
    private static final String[] COMMON_DKIM_SELECTORS = {
            "selector1", "selector2", "default", "google", "k1", "mail"
    };

    // ------------------------------------------------------------------
    //  Inner enum: RecordStatus
    // ------------------------------------------------------------------

    /**
     * Represents the outcome of a single DNS record lookup.
     */
    public enum RecordStatus {

        /** The expected DNS record was found. */
        PRESENT,

        /** The DNS query succeeded but no matching record was found. */
        ABSENT,

        /** The DNS query itself failed (timeout, NXDOMAIN, network issue, etc.). */
        ERROR
    }

    // ------------------------------------------------------------------
    //  Inner class: PreflightReport
    // ------------------------------------------------------------------

    /**
     * Immutable value object that captures the complete result of a
     * {@link DkimSpfChecker#runPreflight(String)} call.
     *
     * <p>Instances are created exclusively by {@link DkimSpfChecker#runPreflight(String)}.
     */
    public static final class PreflightReport {

        /** The domain that was checked (normalised to lower-case). */
        private final String domain;

        /** Outcome of the SPF TXT record lookup. */
        private final RecordStatus spfStatus;

        /**
         * The raw SPF TXT record value (e.g. {@code "v=spf1 include:spf.protection.outlook.com ~all"}),
         * or {@code null} if the record is absent or the lookup failed.
         */
        private final String spfRecord;

        /** Outcome of the DKIM TXT record lookup across common selectors. */
        private final RecordStatus dkimStatus;

        /**
         * The DKIM selector that produced a positive result (e.g. {@code "selector1"}),
         * or {@code null} if no DKIM record was found.
         */
        private final String dkimSelector;

        /** Outcome of the DMARC TXT record lookup. */
        private final RecordStatus dmarcStatus;

        /**
         * The raw DMARC TXT record value (e.g. {@code "v=DMARC1; p=none; ..."}),
         * or {@code null} if absent or the lookup failed.
         */
        private final String dmarcRecord;

        /**
         * Human-readable advisory messages for any missing or potentially
         * misconfigured records.  Empty when all records are correctly set up.
         */
        private final List<String> warnings;

        /**
         * {@code true} if the minimum viable sending configuration is in place —
         * defined as SPF being {@link RecordStatus#PRESENT}.
         */
        private final boolean readyToSend;

        /**
         * Full constructor.  The warnings list is defensively copied and wrapped
         * in an unmodifiable view.
         *
         * @param domain       the normalised sending domain
         * @param spfStatus    result of the SPF lookup
         * @param spfRecord    raw SPF record value; may be {@code null}
         * @param dkimStatus   result of the DKIM probe
         * @param dkimSelector the selector that matched; may be {@code null}
         * @param dmarcStatus  result of the DMARC lookup
         * @param dmarcRecord  raw DMARC record value; may be {@code null}
         * @param warnings     list of advisory messages; defensively copied
         */
        public PreflightReport(String domain,
                               RecordStatus spfStatus,
                               String spfRecord,
                               RecordStatus dkimStatus,
                               String dkimSelector,
                               RecordStatus dmarcStatus,
                               String dmarcRecord,
                               List<String> warnings) {
            this.domain       = domain;
            this.spfStatus    = spfStatus;
            this.spfRecord    = spfRecord;
            this.dkimStatus   = dkimStatus;
            this.dkimSelector = dkimSelector;
            this.dmarcStatus  = dmarcStatus;
            this.dmarcRecord  = dmarcRecord;
            this.warnings     = Collections.unmodifiableList(new ArrayList<>(warnings));
            this.readyToSend  = spfStatus == RecordStatus.PRESENT;
        }

        // ---- Getters --------------------------------------------------------

        /**
         * Returns the domain that was checked.
         *
         * @return the lower-case normalised domain string; never {@code null}
         */
        public String getDomain() {
            return domain;
        }

        /**
         * Returns the result of the SPF TXT record lookup.
         *
         * @return {@link RecordStatus#PRESENT}, {@link RecordStatus#ABSENT}, or
         *         {@link RecordStatus#ERROR}; never {@code null}
         */
        public RecordStatus getSpfStatus() {
            return spfStatus;
        }

        /**
         * Returns the raw SPF record value as found in DNS.
         *
         * @return the record string, or {@code null} if the SPF record is absent
         *         or the lookup failed
         */
        public String getSpfRecord() {
            return spfRecord;
        }

        /**
         * Returns the result of the DKIM selector probe.
         *
         * @return {@link RecordStatus#PRESENT}, {@link RecordStatus#ABSENT}, or
         *         {@link RecordStatus#ERROR}; never {@code null}
         */
        public RecordStatus getDkimStatus() {
            return dkimStatus;
        }

        /**
         * Returns the DKIM selector that resolved successfully.
         *
         * @return the selector string (e.g. {@code "selector1"}), or {@code null}
         *         if no DKIM record was found
         */
        public String getDkimSelector() {
            return dkimSelector;
        }

        /**
         * Returns the result of the DMARC TXT record lookup.
         *
         * @return {@link RecordStatus#PRESENT}, {@link RecordStatus#ABSENT}, or
         *         {@link RecordStatus#ERROR}; never {@code null}
         */
        public RecordStatus getDmarcStatus() {
            return dmarcStatus;
        }

        /**
         * Returns the raw DMARC record value as found in DNS.
         *
         * @return the record string, or {@code null} if the DMARC record is absent
         *         or the lookup failed
         */
        public String getDmarcRecord() {
            return dmarcRecord;
        }

        /**
         * Returns the list of human-readable advisory warnings generated during
         * the preflight check.
         *
         * <p>Each entry is a self-contained instruction that can be acted on by
         * the domain administrator.  The list is empty when all records are
         * correctly configured.
         *
         * @return an unmodifiable, possibly empty list; never {@code null}
         */
        public List<String> getWarnings() {
            return warnings;
        }

        /**
         * Returns {@code true} if the domain meets the minimum viable sending
         * configuration — defined as having a valid SPF record.
         *
         * <p>Even when this returns {@code true}, the caller should still review
         * {@link #getWarnings()} for advisory issues (missing DKIM, missing DMARC).
         *
         * @return {@code true} if it is safe to proceed with sending
         */
        public boolean isReadyToSend() {
            return readyToSend;
        }

        /**
         * Returns a compact, human-readable multi-line summary of the report.
         *
         * <p>Example:
         * <pre>
         * PreflightReport[domain=company.com]
         *   SPF   : PRESENT  (v=spf1 include:spf.protection.outlook.com ~all)
         *   DKIM  : PRESENT  (selector=selector1)
         *   DMARC : ABSENT
         *   Ready : false
         *   Warnings (1):
         *     [1] No DMARC record found. ...
         * </pre>
         *
         * @return a non-null string
         */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("PreflightReport[domain=").append(domain).append("]\n");
            sb.append("  SPF   : ").append(spfStatus);
            if (spfRecord != null) {
                sb.append("  (").append(spfRecord).append(')');
            }
            sb.append('\n');
            sb.append("  DKIM  : ").append(dkimStatus);
            if (dkimSelector != null) {
                sb.append("  (selector=").append(dkimSelector).append(')');
            }
            sb.append('\n');
            sb.append("  DMARC : ").append(dmarcStatus);
            if (dmarcRecord != null) {
                sb.append("  (").append(dmarcRecord).append(')');
            }
            sb.append('\n');
            sb.append("  Ready : ").append(readyToSend).append('\n');

            if (!warnings.isEmpty()) {
                sb.append("  Warnings (").append(warnings.size()).append("):\n");
                for (int i = 0; i < warnings.size(); i++) {
                    sb.append("    [").append(i + 1).append("] ").append(warnings.get(i)).append('\n');
                }
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    /**
     * Runs a full pre-flight DNS check for the given sending domain.
     *
     * <p>The method:
     * <ol>
     *   <li>Checks SPF (TXT record at the domain root).</li>
     *   <li>Probes {@link #COMMON_DKIM_SELECTORS} in order until a DKIM record
     *       is found or all selectors are exhausted.</li>
     *   <li>Checks DMARC (TXT record at {@code _dmarc.<domain>}).</li>
     *   <li>Builds a {@link PreflightReport} with human-readable warnings for
     *       any missing or misconfigured records.</li>
     * </ol>
     *
     * @param domain the sending domain to check (e.g. {@code "company.com"});
     *               must not be {@code null} or blank
     * @return a {@link PreflightReport} with all findings; never {@code null}
     * @throws IllegalArgumentException if {@code domain} is null or blank
     */
    public PreflightReport runPreflight(String domain) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain must not be null or blank");
        }

        String normalised = domain.trim().toLowerCase(Locale.ROOT);
        log.info("Starting DNS preflight checks for domain: {}", normalised);

        // ------------------------------------------------------------------
        // Step 1: SPF check
        // ------------------------------------------------------------------
        RecordStatus spfStatus = checkSpf(normalised);
        String       spfRecord = getSpfRecord(normalised);

        // ------------------------------------------------------------------
        // Step 2: DKIM check — try each common selector until one succeeds
        // ------------------------------------------------------------------
        RecordStatus dkimStatus   = checkDkim(normalised, COMMON_DKIM_SELECTORS);
        String       dkimSelector = findDkimSelector(normalised, COMMON_DKIM_SELECTORS);

        // ------------------------------------------------------------------
        // Step 3: DMARC check
        // ------------------------------------------------------------------
        RecordStatus dmarcStatus = checkDmarc(normalised);
        String       dmarcRecord = getDmarcRecord(normalised);

        // ------------------------------------------------------------------
        // Step 4: Build warnings
        // ------------------------------------------------------------------
        List<String> warnings = new ArrayList<>();

        if (spfStatus == RecordStatus.ABSENT) {
            warnings.add("No SPF record found for '" + normalised
                    + "'. Add: v=spf1 include:spf.protection.outlook.com ~all");
        }

        if (dkimStatus != RecordStatus.PRESENT) {
            warnings.add("No DKIM record found for '" + normalised
                    + "'. Configure DKIM signing in Microsoft 365 admin.");
        }

        if (dmarcStatus == RecordStatus.ABSENT) {
            warnings.add("No DMARC record at _dmarc." + normalised
                    + ". Recommended: v=DMARC1; p=none; rua=mailto:postmaster@" + normalised);
        }

        if (spfStatus == RecordStatus.PRESENT && spfRecord != null
                && !spfRecord.contains("spf.protection.outlook.com")
                && !spfRecord.contains("include:protection.outlook.com")) {
            warnings.add("SPF record exists but may not cover Office 365 servers.");
        }

        // ------------------------------------------------------------------
        // Step 5: Log summary and return the report
        // ------------------------------------------------------------------
        log.info("Preflight complete for '{}' — SPF={}, DKIM={}, DMARC={}, readyToSend={}, warnings={}",
                normalised, spfStatus, dkimStatus, dmarcStatus,
                spfStatus == RecordStatus.PRESENT, warnings.size());

        return new PreflightReport(
                normalised,
                spfStatus,
                spfRecord,
                dkimStatus,
                dkimSelector,
                dmarcStatus,
                dmarcRecord,
                warnings
        );
    }

    /**
     * Returns {@link RecordStatus#PRESENT} if a TXT record starting with
     * {@code v=spf1} exists at the domain root.
     *
     * @param domain the domain to check; must not be {@code null}
     * @return {@link RecordStatus#PRESENT}, {@link RecordStatus#ABSENT}, or
     *         {@link RecordStatus#ERROR}
     */
    public RecordStatus checkSpf(String domain) {
        log.debug("Checking SPF TXT record for: {}", domain);
        List<String> records = lookupTxt(domain);

        if (records == null) {
            log.debug("SPF lookup returned DNS error for: {}", domain);
            return RecordStatus.ERROR;
        }

        for (String record : records) {
            if (record != null && record.toLowerCase(Locale.ROOT).startsWith("v=spf1")) {
                log.debug("SPF record found for {}: {}", domain, record);
                return RecordStatus.PRESENT;
            }
        }

        log.debug("No SPF record found for: {}", domain);
        return RecordStatus.ABSENT;
    }

    /**
     * Returns the raw SPF TXT record value for the given domain, or {@code null}
     * if absent or if the DNS lookup failed.
     *
     * @param domain the domain to query; must not be {@code null}
     * @return the SPF record string (e.g. {@code "v=spf1 include:spf.protection.outlook.com ~all"}),
     *         or {@code null}
     */
    public String getSpfRecord(String domain) {
        List<String> records = lookupTxt(domain);
        if (records == null) {
            return null;
        }
        for (String record : records) {
            if (record != null && record.toLowerCase(Locale.ROOT).startsWith("v=spf1")) {
                return record;
            }
        }
        return null;
    }

    /**
     * Tries each selector in the supplied array in order and returns
     * {@link RecordStatus#PRESENT} on the first match.
     *
     * <p>If any selector probe encounters a DNS error the status is
     * downgraded to {@link RecordStatus#ERROR} but probing continues.  If no
     * selector matches, the final status is {@link RecordStatus#ABSENT} (or
     * {@link RecordStatus#ERROR} if at least one probe failed with a DNS error
     * and none succeeded).
     *
     * @param domain    the sending domain; must not be {@code null}
     * @param selectors the selectors to probe in order; must not be {@code null}
     * @return {@link RecordStatus#PRESENT}, {@link RecordStatus#ABSENT}, or
     *         {@link RecordStatus#ERROR}
     */
    public RecordStatus checkDkim(String domain, String[] selectors) {
        RecordStatus finalStatus = RecordStatus.ABSENT;

        for (String selector : selectors) {
            String hostname = selector + "._domainkey." + domain;
            log.debug("DKIM probe — selector='{}', hostname='{}'", selector, hostname);
            List<String> records = lookupTxt(hostname);

            if (records == null) {
                // DNS error on this selector — note it but keep trying.
                finalStatus = RecordStatus.ERROR;
                continue;
            }

            for (String record : records) {
                if (record != null && record.toLowerCase(Locale.ROOT).contains("v=dkim1")) {
                    log.debug("DKIM record found at {}: {}", hostname, record);
                    return RecordStatus.PRESENT;
                }
            }
        }

        return finalStatus;
    }

    /**
     * Convenience single-selector overload used internally.
     *
     * @param domain   the sending domain; must not be {@code null}
     * @param selector the DKIM selector to probe; must not be {@code null}
     * @return {@link RecordStatus#PRESENT}, {@link RecordStatus#ABSENT}, or
     *         {@link RecordStatus#ERROR}
     */
    public RecordStatus checkDkim(String domain, String selector) {
        return checkDkim(domain, new String[]{selector});
    }

    /**
     * Returns the first selector in the supplied array that has a TXT record
     * at {@code <selector>._domainkey.<domain>}, or {@code null} if no matching
     * selector is found.
     *
     * @param domain    the sending domain; must not be {@code null}
     * @param selectors the selectors to probe in order; must not be {@code null}
     * @return the matching selector string, or {@code null}
     */
    public String findDkimSelector(String domain, String[] selectors) {
        for (String selector : selectors) {
            String hostname = selector + "._domainkey." + domain;
            List<String> records = lookupTxt(hostname);

            if (records == null) {
                continue; // DNS error — skip to next selector
            }

            for (String record : records) {
                if (record != null && record.toLowerCase(Locale.ROOT).contains("v=dkim1")) {
                    log.debug("findDkimSelector: matched selector '{}' for domain '{}'",
                            selector, domain);
                    return selector;
                }
            }
        }
        return null;
    }

    /**
     * Returns {@link RecordStatus#PRESENT} if {@code _dmarc.<domain>} has a
     * TXT record starting with {@code v=DMARC1}.
     *
     * @param domain the sending domain; must not be {@code null}
     * @return {@link RecordStatus#PRESENT}, {@link RecordStatus#ABSENT}, or
     *         {@link RecordStatus#ERROR}
     */
    public RecordStatus checkDmarc(String domain) {
        String hostname = "_dmarc." + domain;
        log.debug("Checking DMARC TXT record at: {}", hostname);
        List<String> records = lookupTxt(hostname);

        if (records == null) {
            log.debug("DMARC lookup returned DNS error for: {}", hostname);
            return RecordStatus.ERROR;
        }

        for (String record : records) {
            if (record != null && record.toLowerCase(Locale.ROOT).startsWith("v=dmarc1")) {
                log.debug("DMARC record found at {}: {}", hostname, record);
                return RecordStatus.PRESENT;
            }
        }

        log.debug("No DMARC record found at: {}", hostname);
        return RecordStatus.ABSENT;
    }

    /**
     * Returns the raw DMARC TXT record value for {@code _dmarc.<domain>},
     * or {@code null} if absent or if the DNS lookup failed.
     *
     * @param domain the sending domain (without the {@code _dmarc.} prefix);
     *               must not be {@code null}
     * @return the DMARC record string, or {@code null}
     */
    public String getDmarcRecord(String domain) {
        String hostname = "_dmarc." + domain;
        List<String> records = lookupTxt(hostname);
        if (records == null) {
            return null;
        }
        for (String record : records) {
            if (record != null && record.toLowerCase(Locale.ROOT).startsWith("v=dmarc1")) {
                return record;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  Private helpers
    // ------------------------------------------------------------------

    /**
     * Performs a JNDI DNS TXT lookup for the given hostname.
     *
     * <p>The lookup is bounded by {@value #DNS_TIMEOUT_MS} ms.  All TXT string
     * fragments returned by the DNS server are joined per RFC 4408 §3.1.3 and
     * each resource record is added as a separate list entry.
     *
     * @param hostname the fully-qualified hostname to query (e.g.
     *                 {@code "_dmarc.company.com"}); must not be {@code null}
     * @return a (possibly empty) list of TXT record string values, or
     *         {@code null} if the DNS lookup failed with a {@link NamingException}
     */
    private List<String> lookupTxt(String hostname) {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial",      "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", String.valueOf(DNS_TIMEOUT_MS));

            InitialDirContext ctx   = new InitialDirContext(env);
            Attributes        attrs = ctx.getAttributes(hostname, new String[]{"TXT"});
            Attribute         txtAttr = attrs.get("TXT");

            if (txtAttr == null) {
                ctx.close();
                return List.of();
            }

            List<String> results = new ArrayList<>();
            NamingEnumeration<?> enumeration = txtAttr.getAll();
            while (enumeration.hasMore()) {
                Object value = enumeration.next();
                if (value != null) {
                    results.add(value.toString());
                }
            }

            ctx.close();
            log.debug("DNS TXT lookup for '{}' returned {} record(s).", hostname, results.size());
            return results;

        } catch (NamingException e) {
            log.debug("DNS TXT lookup failed for {}: {}", hostname, e.getMessage());
            return null;
        }
    }
}
