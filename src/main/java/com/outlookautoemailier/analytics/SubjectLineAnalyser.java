package com.outlookautoemailier.analytics;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Analyses subject line features across campaign batches and correlates them
 * with unique open rates to surface actionable copy insights.
 *
 * <p>All methods are stateless and thread-safe.</p>
 */
public final class SubjectLineAnalyser {

    private SubjectLineAnalyser() {}

    private static final Pattern PERSONALIZATION_TAG = Pattern.compile("\\{\\{.+?}}");
    private static final Pattern HAS_NUMBER          = Pattern.compile("\\d");
    private static final int     MIN_BATCHES         = 15;

    /**
     * Holds the open-rate comparison for a single feature ("has question mark",
     * "short subject", etc.).
     */
    public static class FeatureInsight {
        private final String featureName;
        private final int    withCount;
        private final double avgOpenRateWith;
        private final int    withoutCount;
        private final double avgOpenRateWithout;

        public FeatureInsight(String featureName, int withCount, double avgOpenRateWith,
                              int withoutCount, double avgOpenRateWithout) {
            this.featureName        = featureName;
            this.withCount          = withCount;
            this.avgOpenRateWith    = avgOpenRateWith;
            this.withoutCount       = withoutCount;
            this.avgOpenRateWithout = avgOpenRateWithout;
        }

        public String  getFeatureName()        { return featureName; }
        public int     getWithCount()          { return withCount; }
        public double  getAvgOpenRateWith()    { return avgOpenRateWith; }
        public int     getWithoutCount()       { return withoutCount; }
        public double  getAvgOpenRateWithout() { return avgOpenRateWithout; }

        /** Positive = feature helps opens; negative = feature hurts opens. */
        public double getLift() {
            return avgOpenRateWith - avgOpenRateWithout;
        }
    }

    /**
     * Analyses subject lines across all batches and returns a list of
     * feature insights sorted by absolute lift (most impactful first).
     *
     * @param batches all campaign batches with open-rate data
     * @return list of insights; empty if fewer than {@value MIN_BATCHES} batches
     */
    public static List<FeatureInsight> analyse(List<EmailBatch> batches) {
        if (batches == null || batches.size() < MIN_BATCHES) {
            return Collections.emptyList();
        }

        // Only consider batches with at least one delivered email
        List<EmailBatch> valid = new ArrayList<>();
        for (EmailBatch b : batches) {
            if (b.getSentCount() > 0 && b.getSubject() != null && !b.getSubject().isBlank()) {
                valid.add(b);
            }
        }
        if (valid.size() < MIN_BATCHES) return Collections.emptyList();

        List<FeatureInsight> results = new ArrayList<>();

        // Feature: Length buckets
        results.add(computeInsight("Short subject (<=40 chars)", valid,
                b -> b.getSubject().length() <= 40));
        results.add(computeInsight("Medium subject (41-65 chars)", valid,
                b -> b.getSubject().length() >= 41 && b.getSubject().length() <= 65));
        results.add(computeInsight("Long subject (66+ chars)", valid,
                b -> b.getSubject().length() >= 66));

        // Feature: Question mark
        results.add(computeInsight("Has question mark", valid,
                b -> b.getSubject().contains("?")));

        // Feature: Has number
        results.add(computeInsight("Contains a number", valid,
                b -> HAS_NUMBER.matcher(b.getSubject()).find()));

        // Feature: Personalization tags
        results.add(computeInsight("Has personalisation ({{...}})", valid,
                b -> PERSONALIZATION_TAG.matcher(b.getSubject()).find()));

        // Feature: Word count (short = 1-4, long = 8+)
        results.add(computeInsight("Short (1-4 words)", valid,
                b -> wordCount(b.getSubject()) <= 4));
        results.add(computeInsight("Long (8+ words)", valid,
                b -> wordCount(b.getSubject()) >= 8));

        // Remove insights where both sides have 0 batches; sort by absolute lift
        results.removeIf(f -> f.getWithCount() == 0 && f.getWithoutCount() == 0);
        results.sort((a, b) -> Double.compare(
                Math.abs(b.getLift()), Math.abs(a.getLift())));

        return results;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface FeatureTest {
        boolean test(EmailBatch b);
    }

    private static FeatureInsight computeInsight(String name, List<EmailBatch> batches,
                                                  FeatureTest test) {
        double sumWith = 0, sumWithout = 0;
        int countWith = 0, countWithout = 0;
        for (EmailBatch b : batches) {
            double rate = b.uniqueOpenRatePct();
            if (test.test(b)) {
                sumWith += rate;
                countWith++;
            } else {
                sumWithout += rate;
                countWithout++;
            }
        }
        double avgWith    = countWith > 0    ? sumWith / countWith       : 0.0;
        double avgWithout = countWithout > 0 ? sumWithout / countWithout : 0.0;
        return new FeatureInsight(name, countWith, avgWith, countWithout, avgWithout);
    }

    private static int wordCount(String s) {
        if (s == null || s.isBlank()) return 0;
        return s.trim().split("\\s+").length;
    }
}
