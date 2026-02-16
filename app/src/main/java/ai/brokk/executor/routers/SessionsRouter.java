package ai.brokk.executor.routers;

import ai.brokk.ContextManager;
import ai.brokk.SessionManager;
import ai.brokk.concurrent.AtomicWrites;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

/**
 * Router for /v1/sessions endpoints.
 */
@NullMarked
public final class SessionsRouter implements SimpleHttpServer.CheckedHttpHandler {
    private static final Logger logger = LogManager.getLogger(SessionsRouter.class);

    private final ContextManager contextManager;
    private final SessionManager sessionManager;
    private final Consumer<Boolean> sessionLoadedSetter;

    public SessionsRouter(
            ContextManager contextManager, SessionManager sessionManager, Consumer<Boolean> sessionLoadedSetter) {
        this.contextManager = contextManager;
        this.sessionManager = sessionManager;
        this.sessionLoadedSetter = sessionLoadedSetter;
    }

    @Override
    public void handle(HttpExchange exchange) throws Exception {
        var method = exchange.getRequestMethod();
        var path = exchange.getRequestURI().getPath();
        var normalizedPath = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;

        // Create / import handled specially
        if (method.equals("POST") && normalizedPath.equals("/v1/sessions")) {
            handleCreateSession(exchange);
            return;
        }
        if (method.equals("PUT") && normalizedPath.equals("/v1/sessions")) {
            handlePutSession(exchange);
            return;
        }

        // List sessions
        if (method.equals("GET") && normalizedPath.equals("/v1/sessions")) {
            handleListSessions(exchange);
            return;
        }

        // sessionId-specific paths: either /v1/sessions/{id} (GET zip, PATCH rename, DELETE) or /v1/sessions/{id}/copy
        if (normalizedPath.startsWith("/v1/sessions/")) {
            // First try parseSessionPath for simple /v1/sessions/{id}
            var parseResult = RouterUtil.parseSessionPath(normalizedPath);
            if (parseResult.status() == RouterUtil.SessionPathStatus.VALID) {
                var sessionId = Objects.requireNonNull(parseResult.sessionId());
                // GET on specific id returns ZIP (existing behavior)
                if (method.equals("GET")) {
                    handleGetSessionZip(exchange, sessionId);
                    return;
                }
                if (method.equals("PATCH")) {
                    handlePatchRenameSession(exchange, sessionId);
                    return;
                }
                if (method.equals("DELETE")) {
                    handleDeleteSession(exchange, sessionId);
                    return;
                }
            } else if (parseResult.status() == RouterUtil.SessionPathStatus.INVALID_SESSION_ID) {
                RouterUtil.sendValidationError(exchange, "Invalid session ID in path");
                return;
            }

            // If path is /v1/sessions/{id}/copy
            if (method.equals("POST") && normalizedPath.endsWith("/copy")) {
                // extract id between base and /copy
                var base = "/v1/sessions/";
                var suffix = normalizedPath.substring(base.length(), normalizedPath.length() - "/copy".length());
                if (suffix.isBlank()) {
                    RouterUtil.sendValidationError(exchange, "Session ID is required for copy");
                    return;
                }
                UUID sessionId;
                try {
                    sessionId = UUID.fromString(suffix);
                } catch (IllegalArgumentException e) {
                    RouterUtil.sendValidationError(exchange, "Invalid session ID in path");
                    return;
                }
                handleCopySession(exchange, sessionId);
                return;
            }
        }

        // Fallbacks
        RouterUtil.sendMethodNotAllowed(exchange);
    }

    private void handleListSessions(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "GET")) {
            return;
        }
        try {
            List<SessionManager.SessionInfo> sessions = sessionManager.listSessions();
            List<Map<String, Object>> out = new ArrayList<>();
            for (var s : sessions) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", s.id().toString());
                m.put("name", s.name());
                m.put("created", s.created());
                m.put("modified", s.modified());
                m.put("version", s.version());
                m.put("createdAt", s.createdAt().toString());
                m.put("lastModified", s.lastModified().toString());
                out.add(m);
            }
            SimpleHttpServer.sendJsonResponse(exchange, out);
        } catch (Exception e) {
            logger.error("Error handling GET /v1/sessions", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, ErrorPayload.internalError("Failed to list sessions", e));
        }
    }

    private void handlePatchRenameSession(HttpExchange exchange, UUID sessionId) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "PATCH")) {
            return;
        }
        var req = RouterUtil.parseJsonOr400(exchange, RenameSessionRequest.class, "/v1/sessions/{id}");
        if (req == null) return;

        var newName = req.name().strip();
        if (newName.isBlank()) {
            RouterUtil.sendValidationError(exchange, "Session name is required and must not be blank");
            return;
        }
        if (newName.length() > 200) {
            RouterUtil.sendValidationError(exchange, "Session name must not exceed 200 characters");
            return;
        }

        try {
            // Use renameSessionAsync on contextManager; it expects a Future<String>
            CompletableFuture<String> nameFuture = CompletableFuture.completedFuture(newName);
            try {
                contextManager.renameSessionAsync(sessionId, nameFuture).get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                logger.warn("Timed out renaming session {}; continuing asynchronously", sessionId);
            }

            // Attempt to find updated SessionInfo from sessionManager
            var updated = sessionManager.listSessions().stream()
                    .filter(si -> si.id().equals(sessionId))
                    .findFirst()
                    .orElse(null);

            if (updated == null) {
                SimpleHttpServer.sendJsonResponse(
                        exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Session not found: " + sessionId));
                return;
            }

            Map<String, Object> resp = new HashMap<>();
            resp.put("id", updated.id().toString());
            resp.put("name", updated.name());
            resp.put("created", updated.created());
            resp.put("modified", updated.modified());
            resp.put("version", updated.version());
            SimpleHttpServer.sendJsonResponse(exchange, resp);
        } catch (IllegalArgumentException e) {
            RouterUtil.sendValidationError(exchange, "Invalid session ID: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error handling PATCH /v1/sessions/{}", sessionId, e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, ErrorPayload.internalError("Failed to rename session", e));
        }
    }

    private void handleDeleteSession(HttpExchange exchange, UUID sessionId) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "DELETE")) {
            return;
        }
        try {
            try {
                contextManager.deleteSessionAsync(sessionId).get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                logger.warn("Timed out deleting session {}; continuing asynchronously", sessionId);
            }

            SimpleHttpServer.sendJsonResponse(exchange, Map.of("deleted", sessionId.toString()));
        } catch (IllegalArgumentException e) {
            RouterUtil.sendValidationError(exchange, "Invalid session ID: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error handling DELETE /v1/sessions/{}", sessionId, e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, ErrorPayload.internalError("Failed to delete session", e));
        }
    }

    private void handleCopySession(HttpExchange exchange, UUID sessionId) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }
        var req = RouterUtil.parseJsonOr400(exchange, RenameSessionRequest.class, "/v1/sessions/{id}/copy");
        if (req == null) return;

        String copyName = req.name().isBlank()
                ? "Copy of " + sessionId.toString()
                : req.name().strip();
        if (copyName.length() > 200) {
            RouterUtil.sendValidationError(exchange, "Session name must not exceed 200 characters");
            return;
        }

        try {
            // Prefer using SessionManager.copySession to obtain new SessionInfo directly.
            SessionManager.SessionInfo newInfo = sessionManager.copySession(sessionId, copyName);

            // Optionally attempt to inform ContextManager
            try {
                var fut = contextManager.copySessionAsync(sessionId, newInfo.name());
                // copySessionAsync may not return the new id; we won't rely on it.
                try {
                    fut.get(5, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    logger.warn("Timed out contextManager.copySessionAsync for {}; continuing", sessionId);
                }
            } catch (Exception ignore) {
                // Non-fatal; SessionManager already created the copy on disk.
                logger.debug("ContextManager.copySessionAsync not available or failed: {}", ignore.toString());
            }

            Map<String, Object> resp = new HashMap<>();
            resp.put("id", newInfo.id().toString());
            resp.put("name", newInfo.name());
            resp.put("created", newInfo.created());
            resp.put("modified", newInfo.modified());
            resp.put("version", newInfo.version());
            SimpleHttpServer.sendJsonResponse(exchange, 201, resp);
        } catch (IllegalArgumentException e) {
            RouterUtil.sendValidationError(exchange, "Invalid session ID: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error handling POST /v1/sessions/{}/copy", sessionId, e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, ErrorPayload.internalError("Failed to copy session", e));
        }
    }

    private void handleCreateSession(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }

        CreateSessionRequest request = RouterUtil.parseJsonOr400(exchange, CreateSessionRequest.class, "/v1/sessions");
        if (request == null) {
            return;
        }

        if (request.name().isBlank()) {
            RouterUtil.sendValidationError(exchange, "Session name is required and must not be blank");
            return;
        }

        var sessionName = request.name().strip();

        if (sessionName.length() > 200) {
            RouterUtil.sendValidationError(exchange, "Session name must not exceed 200 characters");
            return;
        }

        try {
            try {
                contextManager.createSessionAsync(sessionName).get(3, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                logger.warn("Timed out creating session {}; continuing asynchronously", sessionName);
            }

            var sessionId = contextManager.getCurrentSessionId();
            logger.info("Created new session: {} ({})", sessionName, sessionId);

            sessionLoadedSetter.accept(true);

            var response = Map.of("sessionId", sessionId.toString(), "name", sessionName);
            SimpleHttpServer.sendJsonResponse(exchange, 201, response);
        } catch (Exception e) {
            logger.error("Error handling POST /v1/sessions", e);
            var error = ErrorPayload.internalError("Failed to create session", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    private void handlePutSession(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "PUT")) {
            return;
        }

        try {
            var sessionIdHeader = exchange.getRequestHeaders().getFirst("X-Session-Id");
            var sessionId = sessionIdHeader != null && !sessionIdHeader.isBlank()
                    ? UUID.fromString(sessionIdHeader)
                    : UUID.randomUUID();

            try (InputStream requestBody = exchange.getRequestBody()) {
                importSessionZip(requestBody, sessionId);
            }

            var response = Map.of("sessionId", sessionId.toString());
            SimpleHttpServer.sendJsonResponse(exchange, 201, response);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid session ID in header", e);
            RouterUtil.sendValidationError(exchange, "Invalid X-Session-Id header: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error handling PUT /v1/sessions", e);
            var error = ErrorPayload.internalError("Failed to process session upload", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    private void handleGetSessionZip(HttpExchange exchange, UUID sessionId) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "GET")) {
            return;
        }

        var sessionZipPath = sessionManager.getSessionsDir().resolve(sessionId + ".zip");
        if (!Files.exists(sessionZipPath)) {
            var error = ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Session zip not found for session " + sessionId);
            SimpleHttpServer.sendJsonResponse(exchange, 404, error);
            return;
        }

        boolean headersSent = false;
        try {
            var headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/zip");
            headers.set("Content-Disposition", "attachment; filename=\"" + sessionId + ".zip\"");
            exchange.sendResponseHeaders(200, 0);
            headersSent = true;
            try (var responseBody = exchange.getResponseBody()) {
                Files.copy(sessionZipPath, responseBody);
            }
        } catch (IOException e) {
            logger.error("Failed to stream session zip {}", sessionId, e);
            if (headersSent) {
                exchange.close();
                return;
            }
            var error = ErrorPayload.internalError("Failed to stream session zip", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    private void importSessionZip(InputStream zipStream, UUID sessionId) throws Exception {
        var cmSessionsDir = contextManager.getProject().getSessionManager().getSessionsDir();
        Files.createDirectories(cmSessionsDir);
        var sessionZipPath = cmSessionsDir.resolve(sessionId.toString() + ".zip");

        AtomicWrites.save(sessionZipPath, out -> {
            byte[] buffer = new byte[64 * 1024];
            int bytesRead;
            while ((bytesRead = zipStream.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        });

        logger.info("Session zip stored: {} ({})", sessionId, sessionZipPath);

        try {
            contextManager.switchSessionAsync(sessionId).get(30, TimeUnit.SECONDS);
            logger.info(
                    "Switched to session: {}; active session now: {}", sessionId, contextManager.getCurrentSessionId());
        } catch (TimeoutException e) {
            throw new IOException("Timed out switching to session " + sessionId + " after 30 seconds", e);
        }

        sessionLoadedSetter.accept(true);
    }

    private record CreateSessionRequest(String name) {}

    private record RenameSessionRequest(String name) {}
}
