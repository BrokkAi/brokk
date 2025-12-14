package ai.brokk.ctl.http;

import ai.brokk.ctl.CtlKeyManager;
import ai.brokk.ctl.InstanceRecord;
import ai.brokk.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Minimal in-process HTTP control server exposing a single endpoint:
 * GET /v1/ctl-info
 *
 * Authentication: requires header "Brokk-CTL-Key" to match the per-user key managed by CtlKeyManager.
 *
 * This is intentionally small and test-friendly for Phase 1.
 */
public final class CtlHttpServer {
    private final InstanceRecord instanceRecord;
    private final CtlKeyManager keyManager;
    private final String host;
    private final int requestedPort;
    private HttpServer server;

    public CtlHttpServer(InstanceRecord instanceRecord, CtlKeyManager keyManager, String host, int port) {
        this.instanceRecord = Objects.requireNonNull(instanceRecord);
        this.keyManager = Objects.requireNonNull(keyManager);
        this.host = Objects.requireNonNull(host);
        this.requestedPort = port;
    }

    /**
     * Start the server. Binds to the provided host and port (port 0 -> ephemeral).
     */
    public synchronized void start() throws IOException {
        if (server != null) return;
        server = HttpServer.create(new InetSocketAddress(host, requestedPort), 0);
        server.createContext("/v1/ctl-info", new CtlInfoHandler());
        server.setExecutor(null);
        server.start();
    }

    public synchronized void stop(int delaySeconds) {
        if (server != null) {
            server.stop(delaySeconds);
            server = null;
        }
    }

    /**
     * Returns the bound port (useful when started with port 0).
     * Requires server to be started.
     */
    public synchronized int getPort() {
        if (server == null) throw new IllegalStateException("server not started");
        return server.getAddress().getPort();
    }

    private class CtlInfoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendJson(exchange, 405, Map.of("error", "method_not_allowed"));
                    return;
                }

                // auth
                Optional<String> keyOpt = keyManager.readKey();
                String serverKey = keyOpt.orElse(null);
                String provided = exchange.getRequestHeaders().getFirst("Brokk-CTL-Key");
                if (serverKey == null || provided == null || !provided.equals(serverKey)) {
                    sendJson(exchange, 401, Map.of("error", "unauthorized"));
                    return;
                }

                // build response preserving nulls and ordering
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("instanceId", instanceRecord.instanceId);
                out.put("pid", instanceRecord.pid);
                out.put("listenAddr", instanceRecord.listenAddr);
                out.put("projects", instanceRecord.projects);
                out.put("brokkctlVersion", instanceRecord.brokkctlVersion);
                List<String> caps = new ArrayList<>();
                caps.add("projects.list");
                caps.add("sessions.create");
                caps.add("exec.start");
                out.put("supportedCapabilities", caps);

                sendJson(exchange, 200, out);
            } finally {
                // ensure exchange closed in all paths (sendJson closes the stream)
            }
        }
    }

    private static void sendJson(HttpExchange exchange, int statusCode, Object payload) throws IOException {
        byte[] bytes = Json.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        } finally {
            exchange.close();
        }
    }
}
