package com.outlookautoemailier.analytics;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal embedded HTTP server that serves a 1x1 transparent tracking pixel
 * and records email-open events in {@link SentEmailStore}.
 *
 * Pixel URL format: http://localhost:{PORT}/track/{trackingId}
 */
public class TrackingPixelServer {

    private static final Logger log = LoggerFactory.getLogger(TrackingPixelServer.class);

    /** Default port; change via constructor if needed. */
    public static final int DEFAULT_PORT = 27321;

    // Smallest possible transparent 1x1 GIF (43 bytes), Base64-encoded
    private static final byte[] PIXEL_BYTES = Base64.getDecoder().decode(
        "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");

    private final int port;
    private HttpServer server;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public TrackingPixelServer() { this(DEFAULT_PORT); }

    public TrackingPixelServer(int port) { this.port = port; }

    public void start() {
        if (running.get()) return;
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
            server.createContext("/track/", exchange -> {
                try {
                    String path = exchange.getRequestURI().getPath(); // /track/{id}
                    String trackingId = path.substring("/track/".length());

                    SentEmailRecord record = SentEmailStore.getInstance().findByTrackingId(trackingId);
                    if (record != null) {
                        record.recordOpen();
                        SentEmailStore.getInstance().flush();
                        log.info("Email opened: trackingId={} recipient={} opens={}",
                                trackingId, record.getRecipientEmail(), record.getOpenCount());

                        // Notify analytics controller on FX thread if available
                        javafx.application.Platform.runLater(() -> {
                            com.outlookautoemailier.AppContext ctx = com.outlookautoemailier.AppContext.get();
                            if (ctx.getAnalyticsController() != null) {
                                ctx.getAnalyticsController().refresh();
                            }
                        });
                    }

                    exchange.getResponseHeaders().set("Content-Type", "image/gif");
                    exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache");
                    exchange.sendResponseHeaders(200, PIXEL_BYTES.length);
                    exchange.getResponseBody().write(PIXEL_BYTES);
                    exchange.getResponseBody().close();
                } catch (Exception e) {
                    log.warn("TrackingPixelServer handler error: {}", e.getMessage());
                }
            });
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "tracking-pixel-server");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            running.set(true);
            log.info("TrackingPixelServer started on port {}", port);
        } catch (Exception e) {
            log.error("TrackingPixelServer failed to start: {}", e.getMessage());
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
        running.set(false);
    }

    public boolean isRunning() { return running.get(); }
    public int getPort()       { return port; }

    /** Returns the full pixel URL for a given tracking ID. */
    public String pixelUrl(String trackingId) {
        return "http://localhost:" + port + "/track/" + trackingId;
    }
}
