package ai.brokk.executor.routers;

import ai.brokk.ContextManager;
import ai.brokk.SessionManager;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

/**
 * HTTP router for session management.
 *
 * Supported endpoints:
 * - GET  /v1/sessions              -> list sessions
 * - POST /v1/sessions              -> create session (existing behavior)
 * - PUT  /v1/sessions              -> import session zip (existing behavior)
 * - GET  /v1/sessions/{id}         -> download session zip (existing behavior)
 * - DELETE /v1/sessions/{id}       -> delete session
 *
 * This class keeps existing behaviors for create/import/download and adds listing and deletion.
 */
@NullMarked
public final class SessionsRouter implements SimpleHttpServer.CheckedHttpHandler {
    private static final Logger logger = LogManager.getLogger(SessionsRouter.class);

    private final ContextManager contextManager;
    private final SessionManager sessionManager;
    private final java.util.function.Consumer<Boolean> sessionLoadedCallback;

    public SessionsRouter(
            ContextManager contextManager,
            SessionManager sessionManager,
            java.util.function.Consumer<Boolean> sessionLoadedCallback) {
        this.contextManager = contextManager;
        this.sessionManager = sessionManager;
        this.sessionLoadedCallback = sessionLoadedCallback;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        var path = exchange.getRequestURI().getPath();
        var normalized = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        try {
            if ("/v1/sessions".equals(normalized)) {
                switch (exchange.getRequestMethod()) {
                    case "GET" -> handleListSessions(exchange);
                    case "POST" -> handleCreateSession(exchange);
                    case "PUT" -> handlePutSession(exchange);
                    default -> RouterUtil.sendMethodNotAllowed(exchange);
                }
                return;
            }

            if (normalized.startsWith("/v1/sessions/")) {
                var parse = RouterUtil.parseSessionPath(normalized);
                switch (parse.status()) {
                    case NOT_FOUND ->
                        SimpleHttpServer.sendJsonResponse(
                                exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found"));
                    case INVALID_SESSION_ID -> RouterUtil.sendValidationError(exchange, "Invalid session ID in path");
                    case VALID -> {
                        UUID sid = parse.sessionId();
                        switch (exchange.getRequestMethod()) {
                            case "GET" -> handleGetSessionZip(exchange, sid);
                            case "DELETE" -> handleDeleteSession(exchange, sid);
                            default -> RouterUtil.sendMethodNotAllowed(exchange);
                        }
                    }
                }
                return;
            }

            SimpleHttpServer.sendJsonResponse(exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found"));
        } catch (Exception e) {
            logger.error("Unhandled error in SessionsRouter for path {}: {}", path, e.getMessage(), e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, ErrorPayload.internalError("Internal server error", e));
        }
    }

    private void handleListSessions(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "GET")) {
            return;
        }
        try {
            List<SessionManager.SessionInfo> infos = sessionManager.listSessions();
            UUID current = contextManager.getCurrentSessionId();
            var sessions = infos.stream()
                    .map(si -> new SessionDto(
                            si.id().toString(),
                            si.name(),
                            si.created(),
                            si.modified(),
                            si.id().equals(current),
                            si.version() == null || si.version().isBlank()))
                    .collect(Collectors.toList());
            var resp = new SessionsListResponse(sessions);
            SimpleHttpServer.sendJsonResponse(exchange, 200, resp);
        } catch (Exception e) {
            logger.error("Failed to list sessions", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, ErrorPayload.internalError("Failed to list sessions", e));
        }
    }

    private void handleDeleteSession(HttpExchange exchange, UUID sessionId) throws IOException {
        try {
            if (!RouterUtil.ensureMethod(exchange, "DELETE")) {
                return;
            }

            boolean exists = sessionManager.listSessions().stream()
                    .anyMatch(si -> si.id().equals(sessionId));
            if (!exists) {
                SimpleHttpServer.sendJsonResponse(
                        exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Session not found"));
                return;
            }

            try {
                // Ensure session files are removed on disk promptly to avoid leaking temp files
                // Perform direct SessionManager deletion synchronously, then request ContextManager
                // to perform any higher-level cleanup asynchronously.
                try {
                    sessionManager.deleteSession(sessionId);
                } catch (Exception se) {
                    logger.warn("SessionManager.deleteSession threw while deleting {}: {}", sessionId, se.toString());
                }

                contextManager.deleteSessionAsync(sessionId).get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                logger.warn("Timed out waiting for deleteSessionAsync to complete for {}", sessionId);
                // proceed, deletion continues asynchronously
            }

            // 204 No Content
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(204, -1);
        } catch (Exception e) {
            logger.error("Failed to delete session {}", sessionId, e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, ErrorPayload.internalError("Failed to delete session", e));
        }
    }

    // --------------------------
    // Existing behaviors (compatible)
    // --------------------------

    private void handleCreateSession(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }

        var req = RouterUtil.parseJsonOr400(exchange, CreateSessionRequest.class, "/v1/sessions");
        if (req == null) return;
        if (req.name() == null || req.name().isBlank()) {
            RouterUtil.sendValidationError(exchange, "Missing or blank 'name' in request");
            return;
        }

        try {
            contextManager.createSessionAsync(req.name()).get(10, TimeUnit.SECONDS);
            UUID current = contextManager.getCurrentSessionId();
            // report session loaded
            sessionLoadedCallback.accept(true);
            SimpleHttpServer.sendJsonResponse(exchange, 201, new CreateSessionResponse(current.toString(), req.name()));
        } catch (Exception e) {
            logger.error("Failed to create session", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, ErrorPayload.internalError("Failed to create session", e));
        }
    }

    private void handlePutSession(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "PUT")) {
            return;
        }

        // Accept zip bytes, optional X-Session-Id header
        try (InputStream in = exchange.getRequestBody()) {
            byte[] data = in.readAllBytes();
            String header = exchange.getRequestHeaders().getFirst("X-Session-Id");
            UUID sessionId = null;
            if (header != null && !header.isBlank()) {
                try {
                    sessionId = UUID.fromString(header);
                } catch (IllegalArgumentException e) {
                    RouterUtil.sendValidationError(exchange, "Invalid X-Session-Id header");
                    return;
                }
            } else {
                // SessionManager.newSessionId() is package-private; generate a new UUID here instead.
                // The SessionManager.newSession(...) call (used elsewhere) will create canonical session metadata.
                sessionId = UUID.randomUUID();
            }

            Path zipPath = sessionManager.getSessionsDir().resolve(sessionId.toString() + ".zip");
            Files.createDirectories(zipPath.getParent());
            Files.write(zipPath, data);

            // Switch to session asynchronously and wait briefly
            try {
                contextManager.switchSessionAsync(sessionId).get(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                logger.debug("switchSessionAsync did not complete quickly: {}", ex.toString());
            }

            sessionLoadedCallback.accept(true);
            SimpleHttpServer.sendJsonResponse(exchange, 201, new PutSessionResponse(sessionId.toString()));
        } catch (Exception e) {
            logger.error("Failed to import session zip", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, ErrorPayload.internalError("Failed to import session", e));
        }
    }

    private void handleGetSessionZip(HttpExchange exchange, UUID sessionId) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "GET")) {
            return;
        }
        Path zipPath = sessionManager.getSessionsDir().resolve(sessionId.toString() + ".zip");
        if (!Files.exists(zipPath)) {
            SimpleHttpServer.sendJsonResponse(
                    exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Session not found"));
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "application/zip");
        exchange.sendResponseHeaders(200, Files.size(zipPath));
        try (var os = exchange.getResponseBody();
                var is = Files.newInputStream(zipPath)) {
            is.transferTo(os);
        }
    }

    // DTOs and helpers
    private record SessionDto(
            String id, String name, long created, long modified, boolean active, boolean versionSupported) {}

    private record SessionsListResponse(List<SessionDto> sessions) {}

    private record CreateSessionRequest(String name) {}

    private record CreateSessionResponse(String sessionId, String name) {}

    private record PutSessionResponse(String sessionId) {}
}
