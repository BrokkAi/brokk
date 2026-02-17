package ai.brokk.executor;

import ai.brokk.IContextManager;
import ai.brokk.executor.http.SimpleHttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A lightweight compatibility stub for HeadlessExecutorMain used by tests.
 *
 * This minimal implementation provides the small public surface used by the tests:
 * - constructor(UUID, listenAddr, authToken, IContextManager)
 * - start()
 * - stop(int)
 * - getPort()
 * - createParentDeathMonitor(Runnable)
 *
 * The implementation uses the existing SimpleHttpServer to serve a minimal
 * /health/live and /health/ready endpoints so tests can exercise HTTP calls.
 *
 * Note: This is intentionally small and not feature complete.
 */
public final class HeadlessExecutorMain {

    private final UUID execId;
    private final String listenAddr;
    private final String authToken;
    private final IContextManager contextManager;
    private SimpleHttpServer server;
    private final AtomicInteger portHolder = new AtomicInteger(0);

    public HeadlessExecutorMain(UUID execId, String listenAddr, String authToken, IContextManager contextManager) {
        checkTestOnly();
        this.execId = execId;
        this.listenAddr = listenAddr;
        this.authToken = authToken;
        this.contextManager = contextManager;
    }

    private static void checkTestOnly() {
        if (!Boolean.getBoolean("ai.brokk.executor.testMode")) {
            throw new IllegalStateException("HeadlessExecutorMain stub is for test-use only. "
                    + "Set -Dai.brokk.executor.testMode=true to use.");
        }
    }

    /**
     * Starts a minimal HTTP server on an ephemeral port (127.0.0.1:0).
     */
    public void start() throws IOException {
        // Parse host and requested port (allow "127.0.0.1:0")
        String host = "127.0.0.1";
        int port = 0;
        var parts = listenAddr.split(":");
        if (parts.length >= 1 && parts[0] != null && !parts[0].isBlank()) {
            host = parts[0];
        }
        if (parts.length >= 2) {
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                port = 0;
            }
        }

        server = new SimpleHttpServer(host, port, authToken, /*threadCount=*/ 4);

        // Unauthenticated liveness endpoint
        server.registerUnauthenticatedContext("/health/live", exchange -> {
            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "alive", "execId", execId.toString()));
        });

        // Readiness: return 503 until a session exists, otherwise 200 with sessionId.
        server.registerUnauthenticatedContext("/health/ready", exchange -> {
            var sid = contextManager.getCurrentSessionId();
            if (sid == null) {
                SimpleHttpServer.sendJsonResponse(exchange, 503, Map.of("status", "not ready"));
            } else {
                SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "ready", "sessionId", sid));
            }
        });

        // Minimal context endpoint used by routing tests.
        server.registerAuthenticatedContext("/v1/context", exchange -> {
            SimpleHttpServer.sendJsonResponse(
                    exchange,
                    Map.of(
                            "fragments", List.of(),
                            "usedTokens", 0,
                            "maxTokens", 0));
        });

        // Tasklist endpoints: GET and POST/replace
        server.registerAuthenticatedContext("/v1/tasklist", exchange -> {
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                SimpleHttpServer.sendJsonResponse(exchange, Map.of("tasks", List.of()));
            } else if ("POST".equalsIgnoreCase(method)) {
                SimpleHttpServer.sendJsonResponse(exchange, Map.of("ok", true));
            } else {
                SimpleHttpServer.sendJsonResponse(exchange, 405, Map.of("error", "Method Not Allowed"));
            }
        });

        // Models endpoint: returns an empty models list (routing presence only).
        server.registerAuthenticatedContext("/v1/models", exchange -> {
            SimpleHttpServer.sendJsonResponse(exchange, Map.of("models", Collections.emptyList()));
        });

        // Start server and capture port
        server.start();
        portHolder.set(server.getPort());
    }

    public void stop(int delaySeconds) {
        if (server != null) {
            server.stop(delaySeconds);
        }
    }

    public int getPort() {
        return portHolder.get();
    }

    /**
     * Create a monitor thread that watches System.in for EOF and invokes the provided onExit runnable.
     * The returned Thread is not started; callers may start it to begin monitoring.
     */
    public Thread createParentDeathMonitor(Runnable onExit) {
        Thread t = new Thread(
                () -> {
                    try (InputStream in = System.in) {
                        // Block until EOF: read() returns -1 when stream is closed.
                        int r;
                        while ((r = in.read()) != -1) {
                            // consume data until EOF; continue looping
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        try {
                            onExit.run();
                        } catch (Throwable ignored) {
                        }
                    }
                },
                "ParentDeathMonitor");
        t.setDaemon(true);
        return t;
    }
}
