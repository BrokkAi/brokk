package ai.brokk.executor.manager;

import ai.brokk.BuildInfo;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.executor.manager.auth.TokenService;
import com.google.common.base.Splitter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
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

    private final UUID managerId;
    private final SimpleHttpServer server;
    private final int poolSize;
    private final Path worktreeBaseDir;
    private final int activeExecutors = 0; // Placeholder until ExecutorPool is implemented
    private final String masterAuthToken;
    private final TokenService tokenService;

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
            UUID managerId, String listenAddr, String authToken, int poolSize, Path worktreeBaseDir)
            throws IOException {
        this.managerId = managerId;
        this.poolSize = poolSize;
        this.worktreeBaseDir = worktreeBaseDir;
        this.masterAuthToken = authToken;
        this.tokenService = new TokenService(authToken);

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
                "Initializing HeadlessSessionManager: managerId={}, listen={}:{}, poolSize={}, worktreeBaseDir={}",
                managerId,
                host,
                port,
                poolSize,
                worktreeBaseDir);

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

        var hasCapacity = activeExecutors < poolSize;
        var provisionerHealthy = Files.isDirectory(worktreeBaseDir) && Files.isWritable(worktreeBaseDir);

        if (!hasCapacity) {
            logger.info("/health/ready: no capacity (activeExecutors={}, poolSize={})", activeExecutors, poolSize);
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
                "activeExecutors", activeExecutors,
                "poolSize", poolSize,
                "availableCapacity", poolSize - activeExecutors);
        SimpleHttpServer.sendJsonResponse(exchange, response);
    }

    private void handleV1Router(HttpExchange exchange) throws IOException {
        var authError = checkAuth(exchange, null);
        if (authError != null) {
            SimpleHttpServer.sendJsonResponse(exchange, 401, authError);
            return;
        }

        var error = ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found");
        SimpleHttpServer.sendJsonResponse(exchange, 404, error);
    }

    public void start() {
        this.server.start();
        logger.info("HeadlessSessionManager HTTP server started; listening on port {}", server.getPort());
    }

    public void stop(int delaySeconds) {
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

            logger.info(
                    "Starting HeadlessSessionManager with config: managerId={}, listenAddr={}, poolSize={}, worktreeBaseDir={}",
                    managerId,
                    listenAddr,
                    poolSize,
                    worktreeBaseDir);

            var manager = new HeadlessSessionManager(managerId, listenAddr, authToken, poolSize, worktreeBaseDir);
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
