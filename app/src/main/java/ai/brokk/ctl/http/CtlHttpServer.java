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

public final class CtlHttpServer {
    private final InstanceRecord instanceRecord;
    private final CtlKeyManager keyManager;
    private final String host;
    private final int requestedPort;
    private com.sun.net.httpserver.HttpServer server;

    public CtlHttpServer(InstanceRecord instanceRecord, CtlKeyManager keyManager, String host, int port) {
        this.instanceRecord = java.util.Objects.requireNonNull(instanceRecord);
        this.keyManager = java.util.Objects.requireNonNull(keyManager);
        this.host = java.util.Objects.requireNonNull(host);
        this.requestedPort = port;
    }

    /**
     * Start the server. Binds to the provided host and port (port 0 -> ephemeral).
     */
    public synchronized void start() throws java.io.IOException {
        if (server != null) return;
        server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(host, requestedPort), 0);
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

    private class CtlInfoHandler implements com.sun.net.httpserver.HttpHandler {
        @Override
        public void handle(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendJsonWithVersion(exchange, 405, java.util.Map.of("error", "method_not_allowed"));
                    return;
                }

                // auth
                java.util.Optional<String> keyOpt = keyManager.readKey();
                String serverKey = keyOpt.orElse(null);
                String provided = exchange.getRequestHeaders().getFirst("Brokk-CTL-Key");
                if (serverKey == null || provided == null || !provided.equals(serverKey)) {
                    sendJsonWithVersion(exchange, 401, java.util.Map.of("error", "unauthorized"));
                    return;
                }

                // protocol negotiation: check client Brokk-CTL-Version header
                String clientVer = exchange.getRequestHeaders().getFirst("Brokk-CTL-Version");
                String serverVer = instanceRecord.brokkctlVersion;
                if (clientVer != null && !clientVer.isBlank()) {
                    int[] c = parseMajorMinor(clientVer);
                    int[] s = parseMajorMinor(serverVer);
                    if (c != null && s != null) {
                        int cMajor = c[0], cMinor = c[1];
                        int sMajor = s[0], sMinor = s[1];
                        // same major, client requests newer minor -> feature rejection
                        if (cMajor == sMajor && cMinor > sMinor) {
                            java.util.List<String> caps = List.of("projects.list", "sessions.create", "exec.start");
                            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
                            body.put("error", "PROTOCOL_UNSUPPORTED_FEATURE");
                            body.put("supportedCapabilities", caps);
                            // include server version in the error payload so the response header can be populated
                            body.put("brokkctlVersion", serverVer);
                            sendJsonWithVersion(exchange, 409, body);
                            return;
                        }
                        // incompatible major - respond with incompatibility (require client to opt-in)
                        if (cMajor != sMajor) {
                            java.util.List<String> caps = List.of("projects.list", "sessions.create", "exec.start");
                            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
                            body.put("error", "PROTOCOL_INCOMPATIBLE");
                            body.put("supportedCapabilities", caps);
                            // include server version in the error payload so clients always receive the server's version
                            body.put("brokkctlVersion", serverVer);
                            sendJsonWithVersion(exchange, 409, body);
                            return;
                        }
                    }
                }

                // build response preserving nulls and ordering
                java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
                out.put("instanceId", instanceRecord.instanceId);
                out.put("pid", instanceRecord.pid);
                out.put("listenAddr", instanceRecord.listenAddr);
                out.put("projects", instanceRecord.projects);
                out.put("brokkctlVersion", instanceRecord.brokkctlVersion);
                java.util.List<String> caps = new java.util.ArrayList<>();
                caps.add("projects.list");
                caps.add("sessions.create");
                caps.add("exec.start");
                out.put("supportedCapabilities", caps);

                sendJsonWithVersion(exchange, 200, out);
            } finally {
                // ensure exchange closed in all paths (sendJsonWithVersion closes the stream)
            }
        }

        /**
         * Parse a semantic-like version into [major, minor, patch] ints.
         * Returns null if parsing fails.
         */
        private int[] parseMajorMinor(String v) {
            if (v == null) return null;
            // strip non-numeric and non-dot suffix characters like "-test"
            String cleaned = v.replaceAll("[^0-9\\.]", "");
            if (cleaned.isBlank()) return null;
            String[] parts = cleaned.split("\\.");
            try {
                int major = parts.length > 0 && !parts[0].isBlank() ? Integer.parseInt(parts[0]) : 0;
                int minor = parts.length > 1 && !parts[1].isBlank() ? Integer.parseInt(parts[1]) : 0;
                int patch = parts.length > 2 && !parts[2].isBlank() ? Integer.parseInt(parts[2]) : 0;
                return new int[] { major, minor, patch };
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private static void sendJsonWithVersion(com.sun.net.httpserver.HttpExchange exchange, int statusCode, Object payload) throws java.io.IOException {
        String serverVersion = null;
        // try to read server version from payload when available
        if (payload instanceof java.util.Map) {
            Object v = ((java.util.Map<?, ?>) payload).get("brokkctlVersion");
            if (v instanceof String) serverVersion = (String) v;
        }
        // fallback: if payload contained supportedCapabilities but not brokkctlVersion, try to preserve header sent earlier
        if (serverVersion == null) {
            // if request had a server-side version stored in attributes, try that (not used here)
        }

        byte[] bytes = ai.brokk.util.Json.toJson(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (serverVersion != null) {
            exchange.getResponseHeaders().set("Brokk-CTL-Version", serverVersion);
        } else {
            // If no server version available, ensure header is present but empty to signal negotiation occurred
            exchange.getResponseHeaders().set("Brokk-CTL-Version", "");
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (java.io.OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        } finally {
            exchange.close();
        }
    }
}
