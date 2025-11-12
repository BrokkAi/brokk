package ai.brokk.executor.http;

import ai.brokk.executor.jobs.ErrorPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Lightweight wrapper around JDK HttpServer with Bearer token authentication middleware
 * and JSON request/response handling utilities.
 */
public final class SimpleHttpServer {
    private static final Logger logger = LogManager.getLogger(SimpleHttpServer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final AtomicInteger workerThreadCounter = new AtomicInteger(0);

    private final HttpServer httpServer;
    private final String authToken;

    /**
     * Create a new SimpleHttpServer.
     *
     * @param host          The hostname or IP address to bind to (e.g., "localhost", "127.0.0.1")
     * @param port          The port to bind to
     * @param authToken     The Bearer token for authentication (reject requests without "Bearer <authToken>")
     * @param threadCount   Number of worker threads in the thread pool
     * @throws IOException  If the server cannot be created
     */
    public SimpleHttpServer(String host, int port, String authToken, int threadCount) throws IOException {
        this.authToken = authToken;
        this.httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);

        var executor = Executors.newFixedThreadPool(threadCount, r -> {
            var t = new Thread(r, "SimpleHttpServer-Worker-" + workerThreadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.httpServer.setExecutor(executor);

        logger.info("SimpleHttpServer created: {}:{} with {} worker threads", host, port, threadCount);
    }

    /**
     * Register an unauthenticated endpoint.
     *
     * @param path    The URI path (e.g., "/health/live")
     * @param handler The handler to invoke
     */
    public void registerUnauthenticatedContext(String path, CheckedHttpHandler handler) {
        this.httpServer.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception e) {
                logger.error("Unhandled exception in handler for {}", path, e);
                sendJsonResponse(exchange, 500, ErrorPayload.internalError("Internal server error", e));
            }
        });
        logger.debug("Registered unauthenticated context: {}", path);
    }

    /**
     * Register an authenticated endpoint (requires valid Bearer token).
     *
     * @param path    The URI path (e.g., "/v1/executor")
     * @param handler The handler to invoke (only called if auth succeeds)
     */
    public void registerAuthenticatedContext(String path, CheckedHttpHandler handler) {
        this.httpServer.createContext(path, exchange -> {
            try {
                // Check Authorization header
                var authHeader = exchange.getRequestHeaders().getFirst("Authorization");
                if (authHeader == null || !authHeader.equals("Bearer " + this.authToken)) {
                    logger.warn("Unauthorized request to {} (missing or invalid Authorization header)", path);
                    sendJsonResponse(exchange, 401, ErrorPayload.of(ErrorPayload.Code.UNAUTHORIZED, "Unauthorized"));
                    return;
                }

                // Auth passed, delegate to handler
                handler.handle(exchange);
            } catch (Exception e) {
                logger.error("Unhandled exception in handler for {}", path, e);
                sendJsonResponse(exchange, 500, ErrorPayload.internalError("Internal server error", e));
            }
        });
        logger.debug("Registered authenticated context: {}", path);
    }

    /**
     * Parse JSON from the request body.
     *
     * @param exchange The HTTP exchange
     * @param valueType The class to deserialize into
     * @param <T> The type parameter
     * @return The deserialized object, or null if parsing fails
     */
    public static <T> @Nullable T parseJsonRequest(HttpExchange exchange, Class<T> valueType) {
        try (InputStream is = exchange.getRequestBody()) {
            return objectMapper.readValue(is, valueType);
        } catch (Exception e) {
            logger.warn("Failed to parse JSON request body", e);
            return null;
        }
    }

    /**
     * Send a JSON response with status 200 OK.
     *
     * @param exchange The HTTP exchange
     * @param responseObject The object to serialize as JSON
     * @throws IOException If writing to the exchange fails
     */
    public static void sendJsonResponse(HttpExchange exchange, Object responseObject) throws IOException {
        sendJsonResponse(exchange, 200, responseObject);
    }

    /**
     * Send a JSON response with the specified status code.
     *
     * @param exchange The HTTP exchange
     * @param statusCode The HTTP status code (e.g., 200, 404, 500)
     * @param responseObject The object to serialize as JSON
     * @throws IOException If writing to the exchange fails
     */
    public static void sendJsonResponse(HttpExchange exchange, int statusCode, Object responseObject)
            throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.add("Content-Type", "application/json; charset=UTF-8");

        var jsonBytes = objectMapper.writeValueAsBytes(responseObject);
        exchange.sendResponseHeaders(statusCode, jsonBytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(jsonBytes);
        }
        exchange.close();
    }

    /**
     * Send an error response with the specified status code and message.
     *
     * @param exchange The HTTP exchange
     * @param statusCode The HTTP status code (e.g., 400, 401, 404, 500)
     * @param errorMessage The error message to include in the response
     * @throws IOException If writing to the exchange fails
     * @deprecated Use {@link #sendJsonResponse(HttpExchange, int, Object)} with {@link ErrorPayload}
     *             to return structured error responses with consistent schema.
     *             For example: {@code sendJsonResponse(exchange, 404, ErrorPayload.of("NOT_FOUND", "Resource not found"))}
     */
    @Deprecated
    public static void sendErrorResponse(HttpExchange exchange, int statusCode, String errorMessage)
            throws IOException {
        var response = Map.of("error", errorMessage);
        sendJsonResponse(exchange, statusCode, response);
    }

    /**
     * Start the HTTP server. This method blocks until the server is ready.
     */
    public void start() {
        this.httpServer.start();
        logger.info("SimpleHttpServer started");
    }

    /**
     * Stop the HTTP server gracefully.
     *
     * @param delaySeconds The number of seconds to wait for current exchanges to complete
     */
    public void stop(int delaySeconds) {
        this.httpServer.stop(delaySeconds);
        logger.info("SimpleHttpServer stopped");
    }

    /**
     * Return the actual port the server is bound to.
     *
     * @return the listening port number
     */
    public int getPort() {
        return this.httpServer.getAddress().getPort();
    }

    /**
     * Functional interface for HTTP handlers that may throw exceptions.
     */
    @FunctionalInterface
    public interface CheckedHttpHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
