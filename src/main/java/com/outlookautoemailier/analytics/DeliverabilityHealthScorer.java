package com.outlookautoemailier.analytics;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Computes a composite deliverability health score (0-100) from campaign batch
 * data and unsubscribe count.
 *
 * <p>The score is a weighted blend of four components:</p>
 * <ol>
 *   <li><b>Delivery health (40%)</b> -- ratio of successful sends to total attempts</li>
 *   <li><b>Failure trend (25%)</b> -- recent (7-day) failure rate vs prior</li>
 *   <li><b>Unsub rate (20%)</b> -- unsubscribes as a percentage of total sent</li>
 *   <li><b>Recency (15%)</b> -- whether there has been any sending activity in the last 90 days</li>
 * </ol>
 *
 * <p>All methods are stateless and thread-safe.</p>
 */
public final class DeliverabilityHealthScorer {

    private DeliverabilityHealthScorer() {}

    /**
     * Computes the deliverability health score.
     *
     * @param batches    all campaign batches; empty returns 0
     * @param unsubCount total number of unsubscribed addresses
     * @return integer score in [0, 100]
     */
    public static int compute(List<EmailBatch> batches, int unsubCount) {
        if (batches == null || batches.isEmpty()) return 0;

        long totalSent   = batches.stream().mapToInt(EmailBatch::getSentCount).sum();
        long totalFailed = batches.stream().mapToInt(EmailBatch::getFailedCount).sum();
        long totalAttempted = totalSent + totalFailed;

        // Component 1: Delivery health (40%)
        double deliveryHealth = totalAttempted == 0 ? 0.0 : (double) totalSent / totalAttempted;

        // Component 2: Failure trend -- last 7 days vs prior (25%)
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime sevenDaysAgo = now.minusDays(7);

        long recentSent = 0, recentFailed = 0;
        long olderSent = 0, olderFailed = 0;
        for (EmailBatch b : batches) {
            if (b.getSentAt() != null && b.getSentAt().isAfter(sevenDaysAgo)) {
                recentSent   += b.getSentCount();
                recentFailed += b.getFailedCount();
            } else {
                olderSent   += b.getSentCount();
                olderFailed += b.getFailedCount();
            }
        }
        double recentFailRate = (recentSent + recentFailed) > 0
                ? (double) recentFailed / (recentSent + recentFailed) : 0.0;
        double olderFailRate = (olderSent + olderFailed) > 0
                ? (double) olderFailed / (olderSent + olderFailed) : 0.0;
        double failureTrend = recentFailRate <= olderFailRate ? 1.0
                : Math.max(0.0, 1.0 - (recentFailRate - olderFailRate) * 2);

        // Component 3: Unsub rate (20%)
        double unsubRate = totalSent == 0 ? 0.0 : (double) unsubCount / totalSent * 100;
        double unsubScore;
        if (unsubRate < 0.5) unsubScore = 1.0;
        else if (unsubRate < 1.0) unsubScore = 0.7;
        else if (unsubRate < 2.0) unsubScore = 0.4;
        else unsubScore = 0.0;

        // Component 4: Recency (15%)
        boolean recentActivity = batches.stream().anyMatch(b ->
                b.getSentAt() != null && b.getSentAt().isAfter(now.minusDays(90)));
        double recency = recentActivity ? 1.0 : 0.5;

        double raw = (0.40 * deliveryHealth) + (0.25 * failureTrend)
                   + (0.20 * unsubScore) + (0.15 * recency);
        return Math.min(100, Math.max(0, (int) (raw * 100)));
    }

    /**
     * Returns a human-readable tier label for the given score.
     *
     * @param score health score (0-100)
     * @return one of "Excellent", "Good", "Fair", or "Poor"
     */
    public static String label(int score) {
        if (score >= 80) return "Excellent";
        if (score >= 60) return "Good";
        if (score >= 40) return "Fair";
        return "Poor";
    }

    /**
     * Returns the CSS-compatible colour hex string for the given score tier.
     *
     * @param score health score (0-100)
     * @return hex colour string (e.g. "#16a34a")
     */
    public static String color(int score) {
        if (score >= 80) return "#16a34a";
        if (score >= 60) return "#2563eb";
        if (score >= 40) return "#d97706";
        return "#dc2626";
    }
}
