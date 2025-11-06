package ai.brokk.sessions;

import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.git.GitRepo;
import ai.brokk.util.Json;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * HTTP controller for session management endpoints.
 *
 * <p>Handles routing for session-related operations including prompt forwarding to headless
 * executors.
 */
public class SessionController {
    private static final Logger logger = LogManager.getLogger(SessionController.class);
    private static final Pattern PROMPT_PATH_PATTERN =
            Pattern.compile("^/api/sessions/([^/]+)/prompt$");
    private static final Pattern STREAM_PATH_PATTERN =
            Pattern.compile("^/api/sessions/([^/]+)/stream$");
    private static final Pattern MERGE_PATH_PATTERN =
            Pattern.compile("^/api/sessions/([^/]+)/merge$");

    private final SessionRegistry registry;
    private final String corsOrigin;

    public SessionController(SessionRegistry registry) {
        this.registry = registry;
        this.corsOrigin = System.getenv().getOrDefault("CORS_ALLOWED_ORIGIN", "http://localhost:5174");
    }

    /**
     * Registers all session-related routes with the HTTP server.
     *
     * @param server the HTTP server to register routes with
     */
    public void registerRoutes(SimpleHttpServer server) {
        server.registerUnauthenticatedContext("/api/sessions/", this::handleSessionsRouter);
    }

    private void handleSessionsRouter(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);

        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        var path = exchange.getRequestURI().getPath();
        
        var promptMatcher = PROMPT_PATH_PATTERN.matcher(path);
        if (promptMatcher.matches()) {
            handlePrompt(exchange, promptMatcher.group(1));
            return;
        }

        var streamMatcher = STREAM_PATH_PATTERN.matcher(path);
        if (streamMatcher.matches()) {
            handleStream(exchange, streamMatcher.group(1));
            return;
        }

        var mergeMatcher = MERGE_PATH_PATTERN.matcher(path);
        if (mergeMatcher.matches()) {
            handleMerge(exchange, mergeMatcher.group(1));
            return;
        }

        sendError(
                exchange,
                404,
                ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Endpoint not found"));
    }

    private void handlePrompt(HttpExchange exchange, String sessionIdStr) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(
                    exchange,
                    405,
                    ErrorPayload.of(ErrorPayload.Code.METHOD_NOT_ALLOWED, "Method not allowed"));
            return;
        }

        UUID sessionId;
        try {
            sessionId = UUID.fromString(sessionIdStr);
        } catch (IllegalArgumentException e) {
            sendError(
                    exchange,
                    400,
                    ErrorPayload.of(ErrorPayload.Code.BAD_REQUEST, "Invalid session ID format"));
            return;
        }

        var sessionInfo = registry.get(sessionId);
        if (sessionInfo == null) {
            sendError(
                    exchange,
                    404,
                    ErrorPayload.of(
                            ErrorPayload.Code.JOB_NOT_FOUND,
                            "Session not found: " + sessionId));
            return;
        }

        PromptRequest request;
        try {
            request = SimpleHttpServer.parseJsonRequest(exchange, PromptRequest.class);
        } catch (Exception e) {
            logger.warn("Failed to parse prompt request: {}", e.getMessage());
            sendError(
                    exchange,
                    400,
                    ErrorPayload.validationError("Invalid request body: " + e.getMessage()));
            return;
        }

        try {
            var response = forwardPromptToExecutor(sessionInfo, request);
            SimpleHttpServer.sendJsonResponse(exchange, 200, response);
        } catch (SocketTimeoutException e) {
            logger.error("Timeout forwarding prompt to executor for session {}", sessionId, e);
            sendError(
                    exchange,
                    504,
                    ErrorPayload.of(
                            ErrorPayload.Code.TIMEOUT,
                            "Executor request timed out: " + e.getMessage()));
        } catch (IOException e) {
            logger.error("Failed to forward prompt to executor for session {}", sessionId, e);
            sendError(
                    exchange,
                    502,
                    ErrorPayload.of(
                            ErrorPayload.Code.INTERNAL_ERROR,
                            "Failed to communicate with executor: " + e.getMessage()));
        }
    }

    private PromptResponse forwardPromptToExecutor(SessionInfo sessionInfo, PromptRequest request)
            throws IOException {
        var executorUrl =
                "http://127.0.0.1:"
                        + sessionInfo.port()
                        + "/api/sessions/"
                        + sessionInfo.id()
                        + "/jobs";

        var jobRequest =
                Map.of(
                        "sessionId", sessionInfo.id().toString(),
                        "taskInput", request.prompt(),
                        "autoCommit", false,
                        "autoCompress", false,
                        "plannerModel", "gpt-5",
                        "codeModel", "gpt-5-mini");

        logger.debug("Forwarding prompt to executor at {} for session {}", executorUrl, sessionInfo.id());

        var connection = (HttpURLConnection) URI.create(executorUrl).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + sessionInfo.authToken());
        connection.setDoOutput(true);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(30000);

        try (var os = connection.getOutputStream()) {
            var requestBody = Json.toJson(jobRequest);
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }

        var responseCode = connection.getResponseCode();
        if (responseCode >= 400) {
            var errorStream = connection.getErrorStream();
            String errorBody = "";
            if (errorStream != null) {
                errorBody = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new IOException(
                    "Executor returned error "
                            + responseCode
                            + (errorBody.isEmpty() ? "" : ": " + errorBody));
        }

        try (var is = connection.getInputStream()) {
            var responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap = Json.getMapper().readValue(responseBody, Map.class);
            var jobId = (String) responseMap.getOrDefault("jobId", responseMap.get("id"));
            var status = (String) responseMap.get("status");

            logger.info("Successfully created job {} for session {}", jobId, sessionInfo.id());

            return new PromptResponse(jobId != null ? jobId : "unknown", status);
        }
    }

    private void handleStream(HttpExchange exchange, String sessionIdStr) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(
                    exchange,
                    405,
                    ErrorPayload.of(ErrorPayload.Code.METHOD_NOT_ALLOWED, "Method not allowed"));
            return;
        }

        UUID sessionId;
        try {
            sessionId = UUID.fromString(sessionIdStr);
        } catch (IllegalArgumentException e) {
            sendError(
                    exchange,
                    400,
                    ErrorPayload.of(ErrorPayload.Code.BAD_REQUEST, "Invalid session ID format"));
            return;
        }

        var sessionInfo = registry.get(sessionId);
        if (sessionInfo == null) {
            sendError(
                    exchange,
                    404,
                    ErrorPayload.of(
                            ErrorPayload.Code.JOB_NOT_FOUND,
                            "Session not found: " + sessionId));
            return;
        }

        var executorUrl = "http://127.0.0.1:" + sessionInfo.port() + "/v1/stream";
        
        logger.debug("Proxying SSE stream from executor at {} for session {}", executorUrl, sessionId);

        try {
            var connection = (HttpURLConnection) URI.create(executorUrl).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + sessionInfo.authToken());
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(0);
            connection.connect();

            var responseCode = connection.getResponseCode();
            if (responseCode >= 400) {
                var errorStream = connection.getErrorStream();
                String errorBody = "";
                if (errorStream != null) {
                    errorBody = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
                }
                logger.error("Executor returned error {} for stream: {}", responseCode, errorBody);
                sendError(
                        exchange,
                        502,
                        ErrorPayload.of(
                                ErrorPayload.Code.INTERNAL_ERROR,
                                "Executor stream failed with status " + responseCode +
                                (errorBody.isEmpty() ? "" : ": " + errorBody)));
                return;
            }

            addCorsHeaders(exchange);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
            exchange.getResponseHeaders().add("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            try (var is = connection.getInputStream();
                 var os = exchange.getResponseBody()) {
                
                var buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                    os.flush();
                }
            } catch (IOException e) {
                logger.debug("Stream connection closed for session {}: {}", sessionId, e.getMessage());
            }
        } catch (SocketTimeoutException e) {
            logger.error("Timeout connecting to executor stream for session {}", sessionId, e);
            sendError(
                    exchange,
                    504,
                    ErrorPayload.of(
                            ErrorPayload.Code.TIMEOUT,
                            "Timeout connecting to executor stream"));
        } catch (IOException e) {
            logger.error("Failed to proxy stream for session {}", sessionId, e);
            sendError(
                    exchange,
                    502,
                    ErrorPayload.of(
                            ErrorPayload.Code.INTERNAL_ERROR,
                            "Failed to connect to executor stream: " + e.getMessage()));
        } finally {
            exchange.close();
        }
    }

    void handleMerge(HttpExchange exchange, String sessionIdStr) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(
                    exchange,
                    405,
                    ErrorPayload.of(ErrorPayload.Code.METHOD_NOT_ALLOWED, "Method not allowed"));
            return;
        }

        UUID sessionId;
        try {
            sessionId = UUID.fromString(sessionIdStr);
        } catch (IllegalArgumentException e) {
            sendError(
                    exchange,
                    400,
                    ErrorPayload.of(ErrorPayload.Code.BAD_REQUEST, "Invalid session ID format"));
            return;
        }

        var sessionInfo = registry.get(sessionId);
        if (sessionInfo == null) {
            sendError(
                    exchange,
                    404,
                    ErrorPayload.of(
                            ErrorPayload.Code.JOB_NOT_FOUND,
                            "Session not found: " + sessionId));
            return;
        }

        MergeRequest request;
        try {
            request = SimpleHttpServer.parseJsonRequest(exchange, MergeRequest.class);
            if (request == null) {
                request = new MergeRequest(null, false);
            }
        } catch (Exception e) {
            logger.warn("Failed to parse merge request: {}", e.getMessage());
            sendError(
                    exchange,
                    400,
                    ErrorPayload.validationError("Invalid request body: " + e.getMessage()));
            return;
        }

        try {
            var worktreePath = sessionInfo.worktreePath();
            var sessionBranch = sessionInfo.branch();

            try (var repo = new GitRepo(worktreePath)) {
                var defaultBranch = repo.getDefaultBranch();

                logger.info(
                        "Merging session {} branch {} into {} in worktree {}",
                        sessionId,
                        sessionBranch,
                        defaultBranch,
                        worktreePath);

                repo.checkout(defaultBranch);

                var mergeMode = parseMergeMode(request.mode());
                var result = repo.performMerge(sessionBranch, mergeMode);

                var conflicts = GitRepo.hasConflicts(result);
                var success = GitRepo.isMergeSuccessful(result, mergeMode);
                var message = GitRepo.describeMergeResult(result, mergeMode);
                var mergeStatusStr = result.getMergeStatus().toString();
                var fastForward = mergeStatusStr.contains("FAST_FORWARD");

                String status;
                if (success) {
                    status = "merged";
                } else if (conflicts) {
                    status = "conflicts";
                } else if (mergeStatusStr.contains("ALREADY_UP_TO_DATE")) {
                    status = "up_to_date";
                } else {
                    status = mergeStatusStr.toLowerCase().replace('_', '-');
                }

                logger.info(
                        "Merge result for session {}: status={}, conflicts={}, success={}",
                        sessionId,
                        status,
                        conflicts,
                        success);

                if (success && request.close()) {
                    logger.info("Closing session {} after successful merge", sessionId);
                    try {
                        sessionInfo.process().destroy();
                        var terminated =
                                sessionInfo.process().waitFor(2, TimeUnit.SECONDS);
                        if (!terminated) {
                            logger.warn(
                                    "Session {} process did not terminate gracefully",
                                    sessionId);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.warn("Interrupted while waiting for process termination", e);
                    } finally {
                        registry.delete(sessionId);
                    }
                }

                var response =
                        new MergeResponse(
                                status,
                                mergeMode.name(),
                                defaultBranch,
                                sessionBranch,
                                fastForward,
                                conflicts,
                                message);

                int statusCode = conflicts ? 409 : 200;
                SimpleHttpServer.sendJsonResponse(exchange, statusCode, response);
            }
        } catch (Exception e) {
            logger.error("Failed to merge session {}", sessionId, e);
            sendError(
                    exchange,
                    500,
                    ErrorPayload.internalError("Merge operation failed: " + e.getMessage(), e));
        }
    }

    private GitRepo.MergeMode parseMergeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return GitRepo.MergeMode.MERGE_COMMIT;
        }

        return switch (mode.toLowerCase()) {
            case "squash" -> GitRepo.MergeMode.SQUASH_COMMIT;
            case "rebase" -> GitRepo.MergeMode.REBASE_MERGE;
            default -> GitRepo.MergeMode.MERGE_COMMIT;
        };
    }

    private void addCorsHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", this.corsOrigin);
        headers.add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private void sendError(HttpExchange exchange, int statusCode, ErrorPayload error)
            throws IOException {
        addCorsHeaders(exchange);
        SimpleHttpServer.sendJsonResponse(exchange, statusCode, error);
    }
}
