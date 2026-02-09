package ai.brokk.executor.routers;

import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import com.google.common.base.Splitter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Shared utilities for HTTP routing, request validation, and path parsing.
 */
@NullMarked
public final class RouterUtil {
    private static final Logger logger = LogManager.getLogger(RouterUtil.class);

    private RouterUtil() {}

    /**
     * Ensures the request method matches the expected method.
     * Sends 405 Method Not Allowed if it doesn't match.
     */
    public static boolean ensureMethod(HttpExchange exchange, String expected) throws IOException {
        if (!exchange.getRequestMethod().equals(expected)) {
            sendMethodNotAllowed(exchange);
            return false;
        }
        return true;
    }

    /**
     * Sends a 405 Method Not Allowed response with a structured error payload.
     */
    public static void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        var error = ErrorPayload.of(ErrorPayload.Code.METHOD_NOT_ALLOWED, "Method not allowed");
        SimpleHttpServer.sendJsonResponse(exchange, 405, error);
    }

    /**
     * Sends a 400 Bad Request response with a validation error message.
     */
    public static void sendValidationError(HttpExchange exchange, String message) throws IOException {
        SimpleHttpServer.sendJsonResponse(exchange, 400, ErrorPayload.validationError(message));
    }

    /**
     * Parses JSON from the request body or sends a 400 Bad Request if parsing fails.
     */
    public static <T> @Nullable T parseJsonOr400(HttpExchange exchange, Class<T> valueType, String route)
            throws IOException {
        try {
            return SimpleHttpServer.parseJsonRequest(exchange, valueType);
        } catch (Exception parseEx) {
            logger.warn("Invalid JSON in {}: {}", route, parseEx.toString());
            sendValidationError(exchange, "Invalid JSON request body");
            return null;
        }
    }

    /**
     * Extract jobId from path like /v1/jobs/abc123 or /v1/jobs/abc123/events.
     * Returns null if the path is invalid or if the extracted jobId is blank.
     */
    public static @Nullable String extractJobIdFromPath(String path) {
        var parts = Splitter.on('/').splitToList(path);
        if (parts.size() >= 4 && "jobs".equals(parts.get(2))) {
            var jobId = parts.get(3);
            if (jobId == null || jobId.isBlank()) {
                return null;
            }
            return jobId;
        }
        return null;
    }

    /**
     * Parse query string into a map with URL decoding.
     */
    public static Map<String, String> parseQueryParams(@Nullable String query) {
        var params = new HashMap<String, String>();
        if (query == null || query.isBlank()) {
            return params;
        }

        for (var pair : Splitter.on('&').split(query)) {
            var keyValue = pair.split("=", 2);
            var rawKey = keyValue[0];
            var rawValue = keyValue.length > 1 ? keyValue[1] : "";

            String key;
            String value;
            try {
                key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                key = rawKey;
            }
            try {
                value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                value = rawValue;
            }

            params.put(key, value);
        }
        return params;
    }

    public enum SessionPathStatus {
        VALID,
        INVALID_SESSION_ID,
        NOT_FOUND
    }

    public record SessionPathParseResult(SessionPathStatus status, @Nullable UUID sessionId) {}

    /**
     * Parses a session-specific path to extract the session UUID.
     */
    public static SessionPathParseResult parseSessionPath(String path) {
        var normalizedPath = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        var basePath = "/v1/sessions/";
        if (!normalizedPath.startsWith(basePath)) {
            return new SessionPathParseResult(SessionPathStatus.NOT_FOUND, null);
        }
        if (normalizedPath.equals("/v1/sessions")) {
            return new SessionPathParseResult(SessionPathStatus.NOT_FOUND, null);
        }
        var suffix = normalizedPath.substring(basePath.length());
        if (suffix.isBlank() || suffix.contains("/")) {
            return new SessionPathParseResult(SessionPathStatus.NOT_FOUND, null);
        }
        try {
            var sessionId = UUID.fromString(suffix);
            return new SessionPathParseResult(SessionPathStatus.VALID, sessionId);
        } catch (IllegalArgumentException e) {
            return new SessionPathParseResult(SessionPathStatus.INVALID_SESSION_ID, null);
        }
    }
}
