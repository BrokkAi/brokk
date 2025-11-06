package ai.brokk.sessions;

import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.util.Json;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
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
    private static final String CORS_ORIGIN = "http://localhost:5174";

    private final SessionRegistry registry;

    public SessionController(SessionRegistry registry) {
        this.registry = registry;
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
        var matcher = PROMPT_PATH_PATTERN.matcher(path);

        if (matcher.matches()) {
            handlePrompt(exchange, matcher.group(1));
        } else {
            sendError(
                    exchange,
                    404,
                    ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Endpoint not found"));
        }
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

    private void addCorsHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", CORS_ORIGIN);
        headers.add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private void sendError(HttpExchange exchange, int statusCode, ErrorPayload error)
            throws IOException {
        addCorsHeaders(exchange);
        SimpleHttpServer.sendJsonResponse(exchange, statusCode, error);
    }
}
