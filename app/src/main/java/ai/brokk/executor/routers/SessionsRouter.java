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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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

        if (method.equals("POST") && normalizedPath.equals("/v1/sessions")) {
            handleCreateSession(exchange);
            return;
        }
        if (method.equals("POST") && normalizedPath.equals("/v1/sessions/switch")) {
            handleSwitchSession(exchange);
            return;
        }
        if (method.equals("POST") && normalizedPath.equals("/v1/sessions/rename")) {
            handleRenameSession(exchange);
            return;
        }
        if (method.equals("PUT") && normalizedPath.equals("/v1/sessions")) {
            handlePutSession(exchange);
            return;
        }
        if (method.equals("GET")) {
            if (normalizedPath.equals("/v1/sessions")) {
                handleListSessions(exchange);
                return;
            }
            if (normalizedPath.equals("/v1/sessions/current")) {
                handleCurrentSession(exchange);
                return;
            }
            var parseResult = RouterUtil.parseSessionPath(normalizedPath);
            if (parseResult.status() == RouterUtil.SessionPathStatus.VALID) {
                handleGetSessionZip(exchange, Objects.requireNonNull(parseResult.sessionId()));
                return;
            }
            if (parseResult.status() == RouterUtil.SessionPathStatus.INVALID_SESSION_ID) {
                RouterUtil.sendValidationError(exchange, "Invalid session ID in path");
                return;
            }
            var error = ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found");
            SimpleHttpServer.sendJsonResponse(exchange, 404, error);
            return;
        }
        RouterUtil.sendMethodNotAllowed(exchange);
    }

    private void handleListSessions(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "GET")) return;
        try {
            var sessions = sessionManager.listSessions();
            var currentSessionId = contextManager.getCurrentSessionId();

            var sessionList = new ArrayList<Map<String, Object>>();
            for (var s : sessions) {
                var map = new HashMap<String, Object>();
                map.put("id", s.id().toString());
                map.put("name", s.name());
                map.put("created", s.created());
                map.put("modified", s.modified());

                var stats = sessionManager.countSessionStats(s.id());
                map.put("aiResponses", stats.aiResponses());
                map.put("totalTasks", stats.tasks().total());
                map.put("incompleteTasks", stats.tasks().incomplete());

                sessionList.add(map);
            }

            var response = Map.of("sessions", sessionList, "currentSessionId", currentSessionId.toString());
            SimpleHttpServer.sendJsonResponse(exchange, response);
        } catch (Exception e) {
            logger.error("Error handling GET /v1/sessions", e);
            var error = ErrorPayload.internalError("Failed to list sessions", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    private void handleCurrentSession(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "GET")) return;
        try {
            var currentSessionId = contextManager.getCurrentSessionId();
            var sessions = sessionManager.listSessions();
            var current = sessions.stream()
                    .filter(s -> s.id().equals(currentSessionId))
                    .findFirst()
                    .orElse(null);

            Map<String, Object> response;
            if (current != null) {
                response = Map.of(
                        "id", current.id().toString(),
                        "name", current.name(),
                        "created", current.created(),
                        "modified", current.modified());
            } else {
                response = Map.of("id", currentSessionId.toString(), "name", "Session", "created", 0L, "modified", 0L);
            }
            SimpleHttpServer.sendJsonResponse(exchange, response);
        } catch (Exception e) {
            logger.error("Error handling GET /v1/sessions/current", e);
            var error = ErrorPayload.internalError("Failed to get current session", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    private void handleRenameSession(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }

        RenameSessionRequest request =
                RouterUtil.parseJsonOr400(exchange, RenameSessionRequest.class, "/v1/sessions/rename");
        if (request == null) {
            return;
        }

        if (request.sessionId().isBlank()) {
            RouterUtil.sendValidationError(exchange, "sessionId is required");
            return;
        }
        if (request.name().isBlank()) {
            RouterUtil.sendValidationError(exchange, "Session name is required and must not be blank");
            return;
        }

        String rawName = request.name().strip();
        if (rawName.length() > 200) {
            RouterUtil.sendValidationError(exchange, "Session name must not exceed 200 characters");
            return;
        }

        UUID sessionId;
        try {
            sessionId = UUID.fromString(request.sessionId());
        } catch (IllegalArgumentException e) {
            RouterUtil.sendValidationError(exchange, "Invalid sessionId: " + request.sessionId());
            return;
        }

        try {
            boolean renamed = sessionManager.renameSession(sessionId, rawName);
            if (!renamed) {
                var error = ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Session not found: " + sessionId);
                SimpleHttpServer.sendJsonResponse(exchange, 404, error);
                return;
            }

            logger.info("Renamed session {} to '{}' via HTTP", sessionId, rawName);

            var response = Map.of("status", "ok", "sessionId", sessionId.toString(), "name", rawName);
            SimpleHttpServer.sendJsonResponse(exchange, response);
        } catch (Exception e) {
            logger.error("Error handling POST /v1/sessions/rename for session {}", sessionId, e);
            var error = ErrorPayload.internalError("Failed to rename session", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    private void handleSwitchSession(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }

        SwitchSessionRequest request =
                RouterUtil.parseJsonOr400(exchange, SwitchSessionRequest.class, "/v1/sessions/switch");
        if (request == null) {
            return;
        }

        if (request.sessionId().isBlank()) {
            RouterUtil.sendValidationError(exchange, "sessionId is required");
            return;
        }

        UUID sessionId;
        try {
            sessionId = UUID.fromString(request.sessionId());
        } catch (IllegalArgumentException e) {
            RouterUtil.sendValidationError(exchange, "Invalid sessionId: " + request.sessionId());
            return;
        }

        try {
            contextManager.switchSessionAsync(sessionId).get(30, TimeUnit.SECONDS);
            logger.info("Switched to session: {}", sessionId);
            sessionLoadedSetter.accept(true);

            var response = Map.of("status", "ok", "sessionId", sessionId.toString());
            SimpleHttpServer.sendJsonResponse(exchange, response);
        } catch (Exception e) {
            var cause = e.getCause() != null ? e.getCause() : e;
            logger.error("Error switching to session {}", sessionId, cause);
            var error = ErrorPayload.internalError("Failed to switch session: " + cause.getMessage(), cause);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
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

    private record SwitchSessionRequest(String sessionId) {}

    private record RenameSessionRequest(String sessionId, String name) {}
}
