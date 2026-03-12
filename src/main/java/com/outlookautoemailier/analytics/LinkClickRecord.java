package com.outlookautoemailier.analytics;

/**
 * Aggregated click analytics for one link URL within a campaign batch.
 */
public class LinkClickRecord {

    private final String batchId;
    private final String originalUrl;
    private final int    clickCount;
    private final int    uniqueClickerCount;

    public LinkClickRecord(String batchId, String originalUrl,
                           int clickCount, int uniqueClickerCount) {
        this.batchId            = batchId;
        this.originalUrl        = originalUrl;
        this.clickCount         = clickCount;
        this.uniqueClickerCount = uniqueClickerCount;
    }

    /** Click rate as % of total recipients (0–100). Returns 0 if no recipients. */
    public double clickRatePct(int totalRecipients) {
        return totalRecipients == 0 ? 0.0 : (uniqueClickerCount * 100.0) / totalRecipients;
    }

    public String getBatchId()            { return batchId; }
    public String getOriginalUrl()        { return originalUrl; }
    public int    getClickCount()         { return clickCount; }
    public int    getUniqueClickerCount() { return uniqueClickerCount; }
}
