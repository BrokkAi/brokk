package ai.brokk.executor.routers;

import ai.brokk.ContextManager;
import ai.brokk.SessionManager;
import ai.brokk.concurrent.AtomicWrites;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

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
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String normalizedPath = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;

        if ("/v1/sessions".equals(normalizedPath)) {
            switch (method) {
                case "POST" -> handleCreateSession(exchange);
                case "PUT" -> handlePutSession(exchange);
                case "GET" -> handleListSessions(exchange);
                default -> RouterUtil.sendMethodNotAllowed(exchange);
            }
            return;
        }

        // Session-specific paths
        RouterUtil.SessionPathParseResult parsed = RouterUtil.parseSessionPath(normalizedPath);
        switch (parsed.status()) {
            case INVALID_SESSION_ID -> {
                RouterUtil.sendValidationError(exchange, "Invalid session ID in path");
                return;
            }
            case NOT_FOUND -> {
                var error = ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found");
                SimpleHttpServer.sendJsonResponse(exchange, 404, error);
                return;
            }
            case VALID -> {
                // fall-through below
            }
        }

        UUID sessionId = parsed.sessionId();
        assert sessionId != null;
        String base = "/v1/sessions/" + sessionId;
        String suffix = normalizedPath.length() > base.length() ? normalizedPath.substring(base.length()) : "";

        try {
            if (suffix.isEmpty()) {
                if ("GET".equals(method)) {
                    handleGetSessionZip(exchange, sessionId);
                } else if ("DELETE".equals(method)) {
                    handleDeleteSession(exchange, sessionId);
                } else {
                    RouterUtil.sendMethodNotAllowed(exchange);
                }
            } else if ("/rename".equals(suffix) && "POST".equals(method)) {
                handleRenameSession(exchange, sessionId);
            } else if ("/copy".equals(suffix) && "POST".equals(method)) {
                handleCopySession(exchange, sessionId);
            } else {
                var error = ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found");
                SimpleHttpServer.sendJsonResponse(exchange, 404, error);
            }
        } catch (RuntimeException e) {
            logger.warn("Error handling sessions request {} {}: {}", method, normalizedPath, e.toString());
            throw e;
        }
    }

    private void handleCreateSession(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }
        CreateSessionRequest req = RouterUtil.parseJsonOr400(exchange, CreateSessionRequest.class, "/v1/sessions");
        if (req == null) {
            return;
        }
        String name = req.name == null ? "" : req.name.trim();
        if (name.isEmpty()) {
            RouterUtil.sendValidationError(exchange, "Session name is required and must not be blank");
            return;
        }
        if (name.length() > 200) {
            RouterUtil.sendValidationError(exchange, "Session name must be at most 200 characters");
            return;
        }
        try {
            contextManager.createSessionAsync(name).get(30, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            logger.warn("Timed out waiting for createSessionAsync completion: {}", te.toString());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while creating session", ie);
        } catch (Exception e) {
            throw new IOException("Failed to create session", e);
        }

        @Nullable UUID currentId = contextManager.getCurrentSessionId();
        if (currentId == null) {
            logger.warn("ContextManager returned null currentSessionId after createSessionAsync");
        } else {
            sessionLoadedSetter.accept(true);
        }
        String idStr = currentId != null ? currentId.toString() : "";
        CreateSessionResponse resp = new CreateSessionResponse(idStr, name);
        SimpleHttpServer.sendJsonResponse(exchange, 201, resp);
    }

    private void handlePutSession(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "PUT")) {
            return;
        }
        String header = exchange.getRequestHeaders().getFirst("X-Session-Id");
        UUID sessionId;
        if (header != null && !header.isBlank()) {
            try {
                sessionId = UUID.fromString(header.trim());
            } catch (IllegalArgumentException e) {
                RouterUtil.sendValidationError(exchange, "Invalid X-Session-Id header; must be a UUID");
                return;
            }
        } else {
            sessionId = UUID.randomUUID();
        }

        Path sessionsDir = contextManager.getProject().getSessionManager().getSessionsDir();
        Files.createDirectories(sessionsDir);
        Path zipPath = sessionsDir.resolve(sessionId.toString() + ".zip");

        AtomicWrites.save(zipPath, out -> {
            try (InputStream in = exchange.getRequestBody();
                    OutputStream o = out) {
                byte[] buf = new byte[64 * 1024];
                int read;
                while ((read = in.read(buf)) != -1) {
                    o.write(buf, 0, read);
                }
            }
        });

        try {
            contextManager.switchSessionAsync(sessionId).get(30, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            throw new IOException("Timed out waiting for session switch after import", te);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while switching session after import", ie);
        } catch (Exception e) {
            throw new IOException("Failed to switch session after import", e);
        }

        sessionLoadedSetter.accept(true);
        ImportSessionResponse resp = new ImportSessionResponse(sessionId.toString());
        SimpleHttpServer.sendJsonResponse(exchange, 201, resp);
    }

    private void handleGetSessionZip(HttpExchange exchange, UUID sessionId) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "GET")) {
            return;
        }
        Path zipPath = sessionManager.getSessionsDir().resolve(sessionId.toString() + ".zip");
        if (!Files.exists(zipPath)) {
            var error = ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Session not found");
            SimpleHttpServer.sendJsonResponse(exchange, 404, error);
            return;
        }
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/zip");
        headers.set("Content-Disposition", "attachment; filename=\"" + sessionId + ".zip\"");

        long size = Files.size(zipPath);
        exchange.sendResponseHeaders(200, size);
        try (OutputStream os = exchange.getResponseBody();
                InputStream in = Files.newInputStream(zipPath)) {
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) != -1) {
                os.write(buf, 0, read);
            }
        } finally {
            exchange.close();
        }
    }

    private void handleListSessions(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "GET")) {
            return;
        }
        List<SessionManager.SessionInfo> infos = sessionManager.listSessions();
        List<SessionDto> dtos = infos.stream()
                .map(info -> new SessionDto(
                        info.id().toString(), info.name(), info.created(), info.modified(), info.version()))
                .toList();
        ListSessionsResponse resp = new ListSessionsResponse(dtos);
        SimpleHttpServer.sendJsonResponse(exchange, resp);
    }

    private void handleRenameSession(HttpExchange exchange, UUID sessionId) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }
        RenameSessionRequest req =
                RouterUtil.parseJsonOr400(exchange, RenameSessionRequest.class, "/v1/sessions/{id}/rename");
        if (req == null) {
            return;
        }
        String name = req.name == null ? "" : req.name.trim();
        if (name.isEmpty()) {
            RouterUtil.sendValidationError(exchange, "Session name is required and must not be blank");
            return;
        }
        if (name.length() > 200) {
            RouterUtil.sendValidationError(exchange, "Session name must be at most 200 characters");
            return;
        }
        try {
            sessionManager.renameSession(sessionId, name);
        } catch (RuntimeException e) {
            logger.warn("renameSession failed for {}: {}", sessionId, e.toString());
            var error = ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Session not found");
            SimpleHttpServer.sendJsonResponse(exchange, 404, error);
            return;
        }
        SessionNameResponse resp = new SessionNameResponse(sessionId.toString(), name);
        SimpleHttpServer.sendJsonResponse(exchange, resp);
    }

    private void handleCopySession(HttpExchange exchange, UUID sessionId) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }
        CopySessionRequest req =
                RouterUtil.parseJsonOr400(exchange, CopySessionRequest.class, "/v1/sessions/{id}/copy");
        if (req == null) {
            return;
        }
        String name = req.name == null ? "" : req.name.trim();
        if (name.isEmpty()) {
            RouterUtil.sendValidationError(exchange, "Session name is required and must not be blank");
            return;
        }
        if (name.length() > 200) {
            RouterUtil.sendValidationError(exchange, "Session name must be at most 200 characters");
            return;
        }
        SessionManager.SessionInfo copied;
        try {
            copied = sessionManager.copySession(sessionId, name);
        } catch (Exception e) {
            // copySession may throw checked exceptions (I/O, archive errors, etc.)
            // Treat as NOT_FOUND when the session cannot be copied, preserving prior behavior.
            logger.warn("copySession failed for {}: {}", sessionId, e.toString());
            var error = ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Session not found");
            SimpleHttpServer.sendJsonResponse(exchange, 404, error);
            return;
        }
        SessionNameResponse resp = new SessionNameResponse(copied.id().toString(), copied.name());
        SimpleHttpServer.sendJsonResponse(exchange, 201, resp);
    }

    private void handleDeleteSession(HttpExchange exchange, UUID sessionId) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "DELETE")) {
            return;
        }
        try {
            sessionManager.deleteSession(sessionId);
        } catch (RuntimeException e) {
            logger.warn("deleteSession failed for {}: {}", sessionId, e.toString());
            var error = ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Session not found");
            SimpleHttpServer.sendJsonResponse(exchange, 404, error);
            return;
        }
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private record CreateSessionRequest(@Nullable String name) {}

    private record CreateSessionResponse(String sessionId, String name) {}

    private record ImportSessionResponse(String sessionId) {}

    private record SessionDto(String id, String name, long created, long modified, @Nullable String version) {}

    private record ListSessionsResponse(List<SessionDto> sessions) {}

    private record RenameSessionRequest(@Nullable String name) {}

    private record CopySessionRequest(@Nullable String name) {}

    private record SessionNameResponse(String sessionId, String name) {}
}
