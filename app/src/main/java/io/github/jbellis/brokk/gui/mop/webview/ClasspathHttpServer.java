package io.github.jbellis.brokk.gui.mop.webview;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A lightweight HTTP server to serve static resources from the classpath.
 * Used to avoid CORS issues with WebView when loading resources from a JAR.
 */
public final class ClasspathHttpServer {
    private static final Logger logger = LogManager.getLogger(ClasspathHttpServer.class);
    @Nullable
    private static volatile ClasspathHttpServer instance;
    private final HttpServer server;
    private final int port;
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

    private static final int START_PORT = 27783;
    private static final int MAX_PORT_ATTEMPTS = 10;

    private ClasspathHttpServer() throws IOException {
        int currentPort = START_PORT;
        IOException lastException = null;
        HttpServer tempServer = null;
        int tempPort = -1;

        // Try binding to ports starting from START_PORT up to MAX_PORT_ATTEMPTS
        for (int attempt = 0; attempt < MAX_PORT_ATTEMPTS; attempt++) {
            try {
                tempServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), currentPort), 0);
                tempPort = tempServer.getAddress().getPort();
                logger.info("Starting embedded HTTP server on port {}", tempPort);
                break;
            } catch (IOException e) {
                logger.warn("Port {} is in use, trying next port", currentPort);
                lastException = e;
                currentPort++;
            }
        }

        if (tempServer == null) {
            throw new IOException("Could not find an available port after " + MAX_PORT_ATTEMPTS + " attempts", lastException);
        }
        
        this.server = tempServer;
        this.port = tempPort;

        // Handle requests by serving resources from the classpath
        server.createContext("/", this::handleRequest);
        // Use a small thread pool for handling requests
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            var t = new Thread(r, "ClasspathHttpServer-" + r.hashCode());
            t.setDaemon(true);
            return t;
        }));
        server.start();
        logger.info("Embedded HTTP server started on port {}", port);
    }

    /**
     * Ensures the server is started and returns the port it's running on.
     *
     * @return the port number of the running server
     */
    public static int ensureStarted() {
        if (instance == null) {
            synchronized (ClasspathHttpServer.class) {
                if (instance == null) {
                    try {
                        instance = new ClasspathHttpServer();
                    } catch (IOException e) {
                        logger.error("Failed to start embedded HTTP server", e);
                        throw new RuntimeException("Could not start embedded HTTP server", e);
                    }
                }
            }
        }
        return instance.port;
    }

    /**
     * Shuts down the server if it has been started.
     */
    public static void shutdown() {
        if (instance != null && instance.isShuttingDown.compareAndSet(false, true)) {
            logger.info("Shutting down embedded HTTP server on port {}", instance.port);
            instance.server.stop(1);
            logger.info("Embedded HTTP server on port {} shut down", instance.port);
            instance = null;
        }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        if (isShuttingDown.get()) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }
        String resourcePath = "/mop-web" + path;

        logger.debug("Serving resource: {}", resourcePath);
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                logger.warn("Resource not found: {}", resourcePath);
                exchange.sendResponseHeaders(404, -1);
            } else {
                var headers = exchange.getResponseHeaders();
                headers.add("Content-Type", guessContentType(resourcePath));
                // Set cache control conditionally based on resource type
                if (path.endsWith("/index.html")) {
                    // Entry point must be re-validated on every visit
                    headers.add("Cache-Control", "no-cache, max-age=0, must-revalidate");
                } else {
                    // Other static assets are assumed to be immutable
                    headers.add("Cache-Control", "public, max-age=31536000, immutable");
                }
                exchange.sendResponseHeaders(200, 0);
                in.transferTo(exchange.getResponseBody());
            }
        } catch (Exception e) {
            logger.error("Error serving resource {}: {}", resourcePath, e.getMessage(), e);
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
        }
    }

    private static String guessContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".js") || path.endsWith(".mjs")) return "application/javascript; charset=UTF-8";
        if (path.endsWith(".css")) return "text/css; charset=UTF-8";
        if (path.endsWith(".json")) return "application/json; charset=UTF-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }
}
