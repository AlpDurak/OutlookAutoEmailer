package com.outlookautoemailier.analytics;

import java.util.ArrayList;
import java.util.List;

/**
 * Analyses sent-email records to compute per-hour delivery success rates
 * and recommend optimal send windows.
 */
public final class SendTimeAnalyser {

    private static final int MIN_SENDS_THRESHOLD = 10;

    private SendTimeAnalyser() { /* utility class */ }

    /**
     * Computes hourly success rates from the given records.
     *
     * @return a 24-element array where index {@code h} is the success rate
     *         (0.0–1.0) for hour {@code h}, or 0.0 if fewer than
     *         {@value #MIN_SENDS_THRESHOLD} sends occurred in that hour.
     */
    public static double[] computeHourlySuccessRates(List<SentEmailRecord> records) {
        int[] totalPerHour   = new int[24];
        int[] successPerHour = new int[24];

        for (SentEmailRecord r : records) {
            if (r.getSentAt() == null) continue;
            int hour = r.getSentAt().getHour();
            totalPerHour[hour]++;
            if ("SENT".equals(r.getStatus())) {
                successPerHour[hour]++;
            }
        }

        double[] rates = new double[24];
        for (int h = 0; h < 24; h++) {
            if (totalPerHour[h] < MIN_SENDS_THRESHOLD) {
                rates[h] = 0.0;
            } else {
                rates[h] = (double) successPerHour[h] / totalPerHour[h];
            }
        }
        return rates;
    }

    /**
     * Returns a human-readable recommendation of the top 3 consecutive-hour
     * windows by average success rate.
     *
     * <p>Example output: {@code "Best: 9-11 AM (94%), 2-4 PM (89%), 6-8 PM (85%)"}
     *
     * @param rates a 24-element success rate array (0.0–1.0) as returned by
     *              {@link #computeHourlySuccessRates}
     * @return recommendation text, or a "not enough data" message
     */
    public static String getRecommendedWindows(double[] rates) {
        // Build 2-hour consecutive windows and rank them
        List<Window> windows = new ArrayList<>();
        for (int start = 0; start < 24; start++) {
            int end = (start + 1) % 24;
            double r0 = rates[start];
            double r1 = rates[end];
            // Skip windows where either hour has insufficient data
            if (r0 == 0.0 && r1 == 0.0) continue;
            double avg = (r0 + r1) / 2.0;
            if (avg > 0.0) {
                windows.add(new Window(start, (start + 2) % 24, avg));
            }
        }

        if (windows.isEmpty()) {
            return "Not enough data to recommend send windows (need 10+ sends per hour).";
        }

        // Sort descending by average rate
        windows.sort((a, b) -> Double.compare(b.avgRate, a.avgRate));

        // Pick up to 3 non-overlapping windows
        List<Window> top = new ArrayList<>();
        for (Window w : windows) {
            if (top.size() >= 3) break;
            boolean overlaps = false;
            for (Window sel : top) {
                if (hoursOverlap(w.startHour, w.endHour, sel.startHour, sel.endHour)) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) top.add(w);
        }

        StringBuilder sb = new StringBuilder("Best: ");
        for (int i = 0; i < top.size(); i++) {
            if (i > 0) sb.append(", ");
            Window w = top.get(i);
            sb.append(formatHour(w.startHour)).append("-").append(formatHour(w.endHour));
            sb.append(String.format(" (%.0f%%)", w.avgRate * 100));
        }
        return sb.toString();
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    private static boolean hoursOverlap(int s1, int e1, int s2, int e2) {
        // Simple check: any hour in window 1 is also in window 2
        for (int h = s1; h != e1; h = (h + 1) % 24) {
            for (int g = s2; g != e2; g = (g + 1) % 24) {
                if (h == g) return true;
            }
        }
        return false;
    }

    private static String formatHour(int hour) {
        if (hour == 0)  return "12 AM";
        if (hour == 12) return "12 PM";
        if (hour < 12)  return hour + " AM";
        return (hour - 12) + " PM";
    }

    private static final class Window {
        final int startHour;
        final int endHour;
        final double avgRate;

        Window(int startHour, int endHour, double avgRate) {
            this.startHour = startHour;
            this.endHour   = endHour;
            this.avgRate   = avgRate;
        }
    }
}
