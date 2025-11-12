package ai.brokk.executor.manager;

import ai.brokk.BuildInfo;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.executor.manager.auth.TokenService;
import ai.brokk.executor.manager.exec.ExecutorPool;
import ai.brokk.executor.manager.provision.SessionSpec;
import ai.brokk.executor.manager.provision.WorktreeProvisioner;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * HeadlessSessionManager orchestrates a pool of HeadlessExecutorMain processes,
 * each with isolated Git worktree environments. It provides a unified HTTP API
 * for session and job management, proxying requests to the appropriate executor.
 */
public final class HeadlessSessionManager {
    private static final Logger logger = LogManager.getLogger(HeadlessSessionManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID managerId;
    private final SimpleHttpServer server;
    private final int poolSize;
    private final Path worktreeBaseDir;
    private final String masterAuthToken;
    private final TokenService tokenService;
    private final WorktreeProvisioner provisioner;
    private final ExecutorPool pool;

    /**
     * Parse command-line arguments into a map of normalized keys to values.
     * Supports both --key value and --key=value forms.
     */
    private static Map<String, String> parseArgs(String[] args) {
        var result = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            var arg = args[i];
            if (arg.startsWith("--")) {
                var withoutPrefix = arg.substring(2);
                String key;
                String value;

                if (withoutPrefix.contains("=")) {
                    var parts = withoutPrefix.split("=", 2);
                    key = parts[0];
                    value = parts.length > 1 ? parts[1] : "";
                } else {
                    key = withoutPrefix;
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        value = args[++i];
                    } else {
                        value = "";
                    }
                }

                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * Get configuration value from either parsed args or environment variable.
     * Returns null/blank only if both are absent.
     */
    @Nullable
    private static String getConfigValue(Map<String, String> parsedArgs, String argKey, String envVarName) {
        var argValue = parsedArgs.get(argKey);
        if (argValue != null && !argValue.isBlank()) {
            return argValue;
        }
        return System.getenv(envVarName);
    }

    public HeadlessSessionManager(
            UUID managerId,
            String listenAddr,
            String authToken,
            int poolSize,
            Path worktreeBaseDir,
            String executorClasspath)
            throws IOException {
        this.managerId = managerId;
        this.poolSize = poolSize;
        this.worktreeBaseDir = worktreeBaseDir;
        this.masterAuthToken = authToken;
        this.tokenService = new TokenService(authToken);
        this.provisioner = new WorktreeProvisioner(worktreeBaseDir);
        this.pool = new ExecutorPool(provisioner, executorClasspath);

        var parts = Splitter.on(':').splitToList(listenAddr);
        if (parts.size() != 2) {
            throw new IllegalArgumentException("LISTEN_ADDR must be in format host:port, got: " + listenAddr);
        }
        var host = parts.get(0);
        int port;
        try {
            port = Integer.parseInt(parts.get(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port in LISTEN_ADDR: " + parts.get(1), e);
        }

        logger.info(
                "Initializing HeadlessSessionManager: managerId={}, listen={}:{}, poolSize={}, worktreeBaseDir={}, executorClasspath={}",
                managerId,
                host,
                port,
                poolSize,
                worktreeBaseDir,
                executorClasspath);

        Files.createDirectories(worktreeBaseDir);

        this.server = new SimpleHttpServer(host, port, authToken, 8);

        this.server.registerUnauthenticatedContext("/health/live", this::handleHealthLive);
        this.server.registerUnauthenticatedContext("/health/ready", this::handleHealthReady);
        this.server.registerUnauthenticatedContext("/v1", this::handleV1Router);

        logger.info("HeadlessSessionManager initialized successfully");
    }

    /**
     * Check authorization header for either master token or valid session token.
     * Returns null if authorized, or an ErrorPayload to send as response if not authorized.
     */
    @Nullable
    private ErrorPayload checkAuth(HttpExchange exchange, @Nullable UUID requiredSessionId) {
        var authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || authHeader.isBlank()) {
            return ErrorPayload.of("UNAUTHORIZED", "Missing Authorization header");
        }

        if (!authHeader.startsWith("Bearer ")) {
            return ErrorPayload.of("UNAUTHORIZED", "Invalid Authorization header format");
        }

        var token = authHeader.substring("Bearer ".length()).strip();

        if (token.equals(masterAuthToken)) {
            return null;
        }

        try {
            var sessionToken = tokenService.validate(token);
            if (requiredSessionId != null && !sessionToken.sessionId().equals(requiredSessionId)) {
                return ErrorPayload.of("FORBIDDEN", "Token does not grant access to this session");
            }
            return null;
        } catch (TokenService.InvalidTokenException e) {
            logger.debug("Invalid session token: {}", e.getMessage());
            return ErrorPayload.of("UNAUTHORIZED", "Invalid or expired token");
        }
    }

    /**
     * Extract sessionId from a session-scoped token in the Authorization header.
     * Returns null with an appropriate ErrorPayload if extraction fails.
     * Master tokens are rejected as they have no session scope.
     */
    @Nullable
    private UUID extractSessionId(HttpExchange exchange) throws IOException {
        var authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || authHeader.isBlank()) {
            var error = ErrorPayload.of("UNAUTHORIZED", "Missing Authorization header");
            SimpleHttpServer.sendJsonResponse(exchange, 401, error);
            return null;
        }

        if (!authHeader.startsWith("Bearer ")) {
            var error = ErrorPayload.of("UNAUTHORIZED", "Invalid Authorization header format");
            SimpleHttpServer.sendJsonResponse(exchange, 401, error);
            return null;
        }

        var token = authHeader.substring("Bearer ".length()).strip();

        if (token.equals(masterAuthToken)) {
            var error = ErrorPayload.of("FORBIDDEN", "Job APIs require a session-scoped token");
            SimpleHttpServer.sendJsonResponse(exchange, 403, error);
            return null;
        }

        try {
            var sessionToken = tokenService.validate(token);
            return sessionToken.sessionId();
        } catch (TokenService.InvalidTokenException e) {
            logger.debug("Invalid session token: {}", e.getMessage());
            var error = ErrorPayload.of("UNAUTHORIZED", "Invalid or expired token");
            SimpleHttpServer.sendJsonResponse(exchange, 401, error);
            return null;
        }
    }

    /**
     * Mint a new session-scoped token with default validity of 1 hour.
     *
     * @param sessionId the session ID to encode in the token
     * @return the signed token string
     */
    public String mintSessionToken(UUID sessionId) {
        return tokenService.mint(sessionId, Duration.ofHours(1));
    }

    private void handleHealthLive(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            var error = ErrorPayload.of(ErrorPayload.Code.METHOD_NOT_ALLOWED, "Method not allowed");
            SimpleHttpServer.sendJsonResponse(exchange, 405, error);
            return;
        }

        var response = Map.of(
                "managerId",
                this.managerId.toString(),
                "version",
                BuildInfo.version,
                "protocolVersion",
                1,
                "poolSize",
                poolSize);

        SimpleHttpServer.sendJsonResponse(exchange, response);
    }

    private void handleHealthReady(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            var error = ErrorPayload.of(ErrorPayload.Code.METHOD_NOT_ALLOWED, "Method not allowed");
            SimpleHttpServer.sendJsonResponse(exchange, 405, error);
            return;
        }

        var authError = checkAuth(exchange, null);
        if (authError != null) {
            SimpleHttpServer.sendJsonResponse(exchange, 401, authError);
            return;
        }

        var hasCapacity = pool.size() < poolSize;
        var provisionerHealthy = provisioner.healthcheck();

        if (!hasCapacity) {
            logger.info("/health/ready: no capacity (activeExecutors={}, poolSize={})", pool.size(), poolSize);
            var error = ErrorPayload.of("NO_CAPACITY", "Executor pool is at capacity");
            SimpleHttpServer.sendJsonResponse(exchange, 503, error);
            return;
        }

        if (!provisionerHealthy) {
            logger.warn(
                    "/health/ready: provisioner unhealthy (worktreeBaseDir={}, exists={}, writable={})",
                    worktreeBaseDir,
                    Files.exists(worktreeBaseDir),
                    Files.isWritable(worktreeBaseDir));
            var error = ErrorPayload.of("PROVISIONER_UNHEALTHY", "Worktree base directory is not accessible");
            SimpleHttpServer.sendJsonResponse(exchange, 503, error);
            return;
        }

        var response = Map.of(
                "status", "ready",
                "activeExecutors", pool.size(),
                "poolSize", poolSize,
                "availableCapacity", poolSize - pool.size());
        SimpleHttpServer.sendJsonResponse(exchange, response);
    }

    private void handleV1Router(HttpExchange exchange) throws IOException {
        var authError = checkAuth(exchange, null);
        if (authError != null) {
            SimpleHttpServer.sendJsonResponse(exchange, 401, authError);
            return;
        }

        var path = exchange.getRequestURI().getPath();
        var method = exchange.getRequestMethod();

        if (path.equals("/v1/sessions") && method.equals("POST")) {
            handleCreateSession(exchange);
            return;
        }

        if (path.startsWith("/v1/jobs")) {
            handleJobProxy(exchange);
            return;
        }

        var error = ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found");
        SimpleHttpServer.sendJsonResponse(exchange, 404, error);
    }

    private void handleCreateSession(HttpExchange exchange) throws IOException {
        if (pool.size() >= poolSize) {
            logger.info("Pool at capacity, rejecting session creation request");
            exchange.getResponseHeaders().add("Retry-After", "30");
            var error = ErrorPayload.of("CAPACITY_EXCEEDED", "Executor pool is at capacity");
            SimpleHttpServer.sendJsonResponse(exchange, 429, error);
            return;
        }

        CreateSessionRequest request;
        try {
            request = SimpleHttpServer.parseJsonRequest(exchange, CreateSessionRequest.class);
        } catch (Exception e) {
            logger.warn("Invalid JSON in POST /v1/sessions", e);
            var error = ErrorPayload.validationError("Invalid JSON request body");
            SimpleHttpServer.sendJsonResponse(exchange, 400, error);
            return;
        }

        if (request == null || request.name() == null || request.name().isBlank()) {
            var error = ErrorPayload.validationError("Session name is required");
            SimpleHttpServer.sendJsonResponse(exchange, 400, error);
            return;
        }

        if (request.repoPath() == null || request.repoPath().isBlank()) {
            var error = ErrorPayload.validationError("repoPath is required");
            SimpleHttpServer.sendJsonResponse(exchange, 400, error);
            return;
        }

        var provisionId = UUID.randomUUID();
        var repoPath = Path.of(request.repoPath());
        var spec = new SessionSpec(provisionId, repoPath, request.ref());

        try {
            var handle = pool.spawn(spec);

            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            var executorUrl = "http://" + handle.host() + ":" + handle.port() + "/v1/sessions";
            var executorRequest = HttpRequest.newBuilder(URI.create(executorUrl))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(Map.of("name", request.name()))))
                    .header("Authorization", "Bearer " + handle.authToken())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .build();

            var executorResponse = client.send(executorRequest, HttpResponse.BodyHandlers.ofString());

            if (executorResponse.statusCode() != 201) {
                logger.error(
                        "Executor session creation failed: status={}, body={}",
                        executorResponse.statusCode(),
                        executorResponse.body());
                pool.shutdown(provisionId);
                var error = ErrorPayload.internalError("Executor failed to create session", null);
                SimpleHttpServer.sendJsonResponse(exchange, 500, error);
                return;
            }

            var executorBody =
                    objectMapper.readValue(executorResponse.body(), new TypeReference<Map<String, Object>>() {});
            var sessionId = UUID.fromString((String) executorBody.get("sessionId"));

            pool.updateSessionId(provisionId, sessionId);

            var token = mintSessionToken(sessionId);

            var response = Map.of(
                    "sessionId", sessionId.toString(),
                    "state", "ready",
                    "token", token);

            SimpleHttpServer.sendJsonResponse(exchange, 201, response);

        } catch (ExecutorPool.ExecutorSpawnException e) {
            logger.warn("Failed to spawn executor for session", e);
            exchange.getResponseHeaders().add("Retry-After", "60");
            var error = ErrorPayload.of("SPAWN_FAILED", "Failed to start executor: " + e.getMessage());
            SimpleHttpServer.sendJsonResponse(exchange, 429, error);
        } catch (Exception e) {
            logger.error("Error creating session", e);
            var error = ErrorPayload.internalError("Failed to create session", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    /**
     * Proxy job API requests to the executor for the session identified by the token.
     */
    private void handleJobProxy(HttpExchange exchange) throws IOException {
        var sessionId = extractSessionId(exchange);
        if (sessionId == null) {
            return;
        }

        var handle = pool.get(sessionId);
        if (handle == null) {
            logger.warn("No active executor found for session {}", sessionId);
            var error = ErrorPayload.of("SESSION_NOT_FOUND", "No active executor for this session");
            SimpleHttpServer.sendJsonResponse(exchange, 404, error);
            return;
        }

        pool.touch(sessionId);

        var path = exchange.getRequestURI().getPath();
        var query = exchange.getRequestURI().getQuery();
        var method = exchange.getRequestMethod();

        var executorUrl = "http://" + handle.host() + ":" + handle.port() + path;
        if (query != null && !query.isBlank()) {
            executorUrl += "?" + query;
        }

        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            var requestBuilder = HttpRequest.newBuilder(URI.create(executorUrl))
                    .header("Authorization", "Bearer " + handle.authToken())
                    .timeout(Duration.ofMinutes(5));

            if (method.equals("POST")) {
                var contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                if (contentType != null) {
                    requestBuilder.header("Content-Type", contentType);
                }

                var idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                if (idempotencyKey != null) {
                    requestBuilder.header("Idempotency-Key", idempotencyKey);
                }

                byte[] body;
                try (var inputStream = exchange.getRequestBody()) {
                    body = inputStream.readAllBytes();
                }
                requestBuilder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
            } else {
                requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            var executorRequest = requestBuilder.build();

            var executorResponse = client.send(executorRequest, HttpResponse.BodyHandlers.ofInputStream());

            exchange.getResponseHeaders()
                    .set("Content-Type", executorResponse.headers()
                            .firstValue("Content-Type")
                            .orElse("application/json"));

            exchange.sendResponseHeaders(executorResponse.statusCode(), 0);

            try (var executorBody = executorResponse.body();
                    var responseBody = exchange.getResponseBody()) {
                executorBody.transferTo(responseBody);
            }

            exchange.close();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while proxying request to executor for session {}", sessionId, e);
            var error = ErrorPayload.internalError("Request interrupted", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        } catch (Exception e) {
            logger.error("Error proxying request to executor for session {}", sessionId, e);
            var error = ErrorPayload.internalError("Failed to proxy request to executor", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    private record CreateSessionRequest(String name, String repoPath, @Nullable String ref) {}

    public void start() {
        this.server.start();
        logger.info("HeadlessSessionManager HTTP server started; listening on port {}", server.getPort());
    }

    public void stop(int delaySeconds) {
        pool.shutdownAll();
        this.server.stop(delaySeconds);
        logger.info("HeadlessSessionManager stopped");
    }

    public int getPort() {
        return this.server.getPort();
    }

    public static void main(String[] args) {
        try {
            var parsedArgs = parseArgs(args);

            var managerIdStr = getConfigValue(parsedArgs, "manager-id", "MANAGER_ID");
            var managerId =
                    (managerIdStr != null && !managerIdStr.isBlank()) ? UUID.fromString(managerIdStr) : UUID.randomUUID();

            var listenAddr = getConfigValue(parsedArgs, "listen-addr", "LISTEN_ADDR");
            if (listenAddr == null || listenAddr.isBlank()) {
                throw new IllegalArgumentException(
                        "LISTEN_ADDR must be provided via --listen-addr argument or LISTEN_ADDR environment variable");
            }

            var authToken = getConfigValue(parsedArgs, "auth-token", "AUTH_TOKEN");
            if (authToken == null || authToken.isBlank()) {
                throw new IllegalArgumentException(
                        "AUTH_TOKEN must be provided via --auth-token argument or AUTH_TOKEN environment variable");
            }

            var poolSizeStr = getConfigValue(parsedArgs, "pool-size", "POOL_SIZE");
            if (poolSizeStr == null || poolSizeStr.isBlank()) {
                throw new IllegalArgumentException(
                        "POOL_SIZE must be provided via --pool-size argument or POOL_SIZE environment variable");
            }
            int poolSize;
            try {
                poolSize = Integer.parseInt(poolSizeStr);
                if (poolSize < 1) {
                    throw new IllegalArgumentException("POOL_SIZE must be at least 1");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid POOL_SIZE: " + poolSizeStr, e);
            }

            var worktreeBaseDirStr = getConfigValue(parsedArgs, "worktree-base-dir", "WORKTREE_BASE_DIR");
            if (worktreeBaseDirStr == null || worktreeBaseDirStr.isBlank()) {
                throw new IllegalArgumentException(
                        "WORKTREE_BASE_DIR must be provided via --worktree-base-dir argument or WORKTREE_BASE_DIR environment variable");
            }
            var worktreeBaseDir = Path.of(worktreeBaseDirStr);

            var executorClasspath = getConfigValue(parsedArgs, "executor-classpath", "EXECUTOR_CLASSPATH");
            if (executorClasspath == null || executorClasspath.isBlank()) {
                executorClasspath = System.getProperty("java.class.path");
                logger.info("Using current classpath for executors: {}", executorClasspath);
            }

            logger.info(
                    "Starting HeadlessSessionManager with config: managerId={}, listenAddr={}, poolSize={}, worktreeBaseDir={}",
                    managerId,
                    listenAddr,
                    poolSize,
                    worktreeBaseDir);

            var manager = new HeadlessSessionManager(
                    managerId, listenAddr, authToken, poolSize, worktreeBaseDir, executorClasspath);
            manager.start();

            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                logger.info("Shutdown signal received, stopping manager");
                                manager.stop(5);
                            },
                            "HeadlessSessionManager-ShutdownHook"));

            logger.info("HeadlessSessionManager is running");
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            logger.info("HeadlessSessionManager interrupted", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Fatal error in HeadlessSessionManager", e);
            System.exit(1);
        }
    }
}
