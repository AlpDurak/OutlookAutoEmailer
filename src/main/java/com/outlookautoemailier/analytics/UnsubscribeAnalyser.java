package com.outlookautoemailier.analytics;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.util.*;

/**
 * Analyses unsubscribe/suppression data to surface trends in the Analytics UI.
 *
 * <p>All methods are stateless and pure — they accept pre-loaded data maps
 * and return computed results.  Thread-safe by design (no mutable state).</p>
 */
public final class UnsubscribeAnalyser {

    private UnsubscribeAnalyser() {}

    /**
     * Groups unsubscribe events by ISO week and returns the count per week.
     *
     * <p>The returned map is a {@link TreeMap} sorted by week key
     * (format: {@code YYYY-Www}, e.g. {@code 2024-W03}).</p>
     *
     * @param data map of email address to unsubscribe timestamp
     * @return sorted map of ISO week label to count; never {@code null}
     */
    public static Map<String, Long> weeklyUnsubscribeCounts(Map<String, LocalDateTime> data) {
        if (data == null || data.isEmpty()) {
            return new TreeMap<>();
        }

        TreeMap<String, Long> result = new TreeMap<>();
        for (LocalDateTime ts : data.values()) {
            if (ts == null) continue;
            int year = ts.get(IsoFields.WEEK_BASED_YEAR);
            int week = ts.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            String key = String.format("%d-W%02d", year, week);
            result.merge(key, 1L, Long::sum);
        }
        return result;
    }

    /**
     * Computes the unsubscribe rate per campaign batch.
     *
     * <p>For each unsubscribed email, the method finds the most recent
     * {@link SentEmailRecord} addressed to that email and attributes the
     * unsub to that record's {@code batchId}.  The rate is computed as
     * {@code unsubs / totalRecipients * 100}.</p>
     *
     * @param unsubs  map of email to unsubscribe timestamp
     * @param sends   all sent email records
     * @param batches all campaign batches
     * @return map of batchId to unsubscribe rate percentage; never {@code null}
     */
    public static Map<String, Double> campaignUnsubscribeRates(
            Map<String, LocalDateTime> unsubs,
            List<SentEmailRecord> sends,
            List<EmailBatch> batches) {

        if (unsubs == null || unsubs.isEmpty() || sends == null || batches == null) {
            return Collections.emptyMap();
        }

        // Build a lookup: email -> most recent SentEmailRecord (by sentAt)
        Map<String, SentEmailRecord> latestSend = new HashMap<>();
        for (SentEmailRecord r : sends) {
            if (r.getRecipientEmail() == null || r.getBatchId() == null) continue;
            String key = r.getRecipientEmail().toLowerCase(Locale.ROOT);
            SentEmailRecord existing = latestSend.get(key);
            if (existing == null
                    || (r.getSentAt() != null && existing.getSentAt() != null
                        && r.getSentAt().isAfter(existing.getSentAt()))) {
                latestSend.put(key, r);
            }
        }

        // Count unsubs per batchId
        Map<String, Integer> unsubsPerBatch = new HashMap<>();
        for (String email : unsubs.keySet()) {
            SentEmailRecord rec = latestSend.get(email.toLowerCase(Locale.ROOT));
            if (rec != null && rec.getBatchId() != null) {
                unsubsPerBatch.merge(rec.getBatchId(), 1, Integer::sum);
            }
        }

        // Build batch lookup for total recipients
        Map<String, Integer> totalPerBatch = new HashMap<>();
        for (EmailBatch b : batches) {
            totalPerBatch.put(b.getId(), b.getTotalRecipients());
        }

        // Compute rates
        Map<String, Double> rates = new HashMap<>();
        for (Map.Entry<String, Integer> entry : unsubsPerBatch.entrySet()) {
            int total = totalPerBatch.getOrDefault(entry.getKey(), 0);
            double rate = total > 0
                    ? (entry.getValue() * 100.0) / total
                    : 0.0;
            rates.put(entry.getKey(), rate);
        }
        return rates;
    }

    /**
     * Counts how many unsubscribe events occurred in the current calendar month.
     *
     * @param data map of email to unsubscribe timestamp
     * @return count of unsubs this month; non-negative
     */
    public static int countThisMonth(Map<String, LocalDateTime> data) {
        if (data == null || data.isEmpty()) {
            return 0;
        }
        YearMonth current = YearMonth.now();
        int count = 0;
        for (LocalDateTime ts : data.values()) {
            if (ts != null && YearMonth.from(ts).equals(current)) {
                count++;
            }
        }
        return count;
    }
}
