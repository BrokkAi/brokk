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
        if (method.equals("PUT") && normalizedPath.equals("/v1/sessions")) {
            handlePutSession(exchange);
            return;
        }
        if (method.equals("GET")) {
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
            TimeoutException timeoutEx = null;
            try {
                // Attempt to create and wait briefly for the ContextManager to set the active session.
                contextManager.createSessionAsync(sessionName).get(3, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                logger.warn("Timed out creating session {}; continuing asynchronously", sessionName);
                timeoutEx = e;
            }

            // Give the ContextManager a small window to publish the new active session id if it didn't complete
            // synchronously. This avoids returning a null sessionId to clients while still allowing the creation to
            // proceed asynchronously in background.
            UUID sessionId = contextManager.getCurrentSessionId();
            long waitUntil = System.currentTimeMillis() + 2000;
            while (sessionId == null && System.currentTimeMillis() < waitUntil) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                sessionId = contextManager.getCurrentSessionId();
            }

            if (sessionId == null) {
                // Session creation did not complete in time - return an error rather than synthesizing a fake ID
                logger.error(
                        "ContextManager did not report a session after creation for '{}'; returning 500", sessionName);
                var error = ErrorPayload.internalError(
                        "Session creation did not complete in time",
                        timeoutEx != null ? timeoutEx : new TimeoutException("Session capture timed out"));
                SimpleHttpServer.sendJsonResponse(exchange, 500, error);
                return;
            }

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
}
