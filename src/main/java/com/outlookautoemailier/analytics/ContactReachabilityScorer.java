package com.outlookautoemailier.analytics;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Computes a per-contact reachability score (0-100) based on delivery history,
 * suppression status, and recency of activity.
 */
public final class ContactReachabilityScorer {

    private ContactReachabilityScorer() {}

    /**
     * Computes reachability scores for every distinct recipient found in the
     * supplied send records.
     *
     * <p>Formula:
     * <pre>
     *   S = 0.60 * deliveryRate + 0.30 * notSuppressed + 0.10 * recentActivityBonus
     *   score = clamp((int)(S * 100), 0, 100)
     * </pre>
     *
     * @param records    all sent-email records (may include SENT and FAILED)
     * @param suppressed the set of suppressed/unsubscribed email addresses
     * @return map of recipientEmail to integer score (0-100)
     */
    public static Map<String, Integer> computeScores(List<SentEmailRecord> records,
                                                      Set<String> suppressed) {
        if (records == null || records.isEmpty()) return Collections.emptyMap();

        // Aggregate per recipient
        Map<String, int[]> counts = new HashMap<>();      // [sentCount, failedCount]
        Map<String, LocalDateTime> lastSend = new HashMap<>();

        for (SentEmailRecord r : records) {
            String email = r.getRecipientEmail();
            if (email == null || email.isBlank()) continue;

            int[] c = counts.computeIfAbsent(email, k -> new int[2]);
            if ("FAILED".equals(r.getStatus())) {
                c[1]++;
            } else {
                c[0]++;
            }

            if (r.getSentAt() != null) {
                lastSend.merge(email, r.getSentAt(),
                        (existing, incoming) -> incoming.isAfter(existing) ? incoming : existing);
            }
        }

        LocalDateTime cutoff = LocalDateTime.now().minus(90, ChronoUnit.DAYS);
        Set<String> suppressedLower = new HashSet<>();
        if (suppressed != null) {
            for (String s : suppressed) {
                suppressedLower.add(s.toLowerCase(Locale.ROOT));
            }
        }

        Map<String, Integer> scores = new HashMap<>();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            String email = entry.getKey();
            int[] c = entry.getValue();
            int sentCount = c[0];
            int failedCount = c[1];

            double deliveryRate = (sentCount + failedCount) > 0
                    ? (double) sentCount / (sentCount + failedCount)
                    : 0.0;

            double notSuppressed = suppressedLower.contains(email.toLowerCase(Locale.ROOT))
                    ? 0.0 : 1.0;

            LocalDateTime last = lastSend.get(email);
            double recentActivityBonus = (last != null && last.isAfter(cutoff)) ? 1.0 : 0.0;

            double s = (0.60 * deliveryRate) + (0.30 * notSuppressed) + (0.10 * recentActivityBonus);
            int score = Math.max(0, Math.min(100, (int) (s * 100)));

            scores.put(email, score);
        }

        return scores;
    }

    /**
     * Categorises the scores into three buckets.
     *
     * @param scores map produced by {@link #computeScores}
     * @return {@code [reachableCount, atRiskCount, unreachableCount]} where
     *         Reachable = 80-100, At Risk = 50-79, Unreachable = 0-49
     */
    public static int[] categorize(Map<String, Integer> scores) {
        int reachable = 0, atRisk = 0, unreachable = 0;
        for (int score : scores.values()) {
            if (score >= 80) reachable++;
            else if (score >= 50) atRisk++;
            else unreachable++;
        }
        return new int[]{ reachable, atRisk, unreachable };
    }
}
