package com.outlookautoemailier.analytics;

/**
 * Formerly served tracking pixels. Feature removed — class kept as a stub
 * to avoid breaking any serialised data that may reference it.
 *
 * @deprecated No longer used. Will be deleted in a future cleanup.
 */
@Deprecated
public class TrackingPixelServer {
    public void start() {}
    public void stop()  {}
    public boolean isRunning() { return false; }
    public int getPort()       { return 0; }
}
