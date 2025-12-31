package ai.brokk.executor;

import static java.nio.charset.StandardCharsets.UTF_8;

import ai.brokk.BuildInfo;
import ai.brokk.ContextManager;
import ai.brokk.SessionManager;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.project.MainProject;
import com.google.common.base.Splitter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class HeadlessExecutorMain {
    private static final Logger logger = LogManager.getLogger(HeadlessExecutorMain.class);

    // Valid argument keys that the application accepts
    private static final Set<String> VALID_ARGS =
            Set.of("exec-id", "listen-addr", "auth-token", "workspace-dir", "brokk-api-key", "proxy-setting", "help");

    private final UUID execId;
    private final SimpleHttpServer server;
    private final ContextManager contextManager;
    private final JobStore jobStore;
    private final SessionManager sessionManager;
    private final JobReservation jobReservation = new JobReservation();
    private final ai.brokk.executor.jobs.JobRunner jobRunner;
    // Indicates whether a session has been loaded (either uploaded or created) at least once.
    // Used to gate /health/ready until the first session is available.
    private volatile boolean sessionLoaded = false;

    /**
     * Result of parsing command-line arguments, including both parsed args and invalid keys.
     */
    private record ParseArgsResult(Map<String, String> args, Set<String> invalidKeys) {}

    /*
     * Parse command-line arguments into a map of normalized keys to values.
     * Supports both --key value and --key=value forms.
     * Returns both valid parsed args and any unrecognized keys found.
     */
    private static ParseArgsResult parseArgs(String[] args) {
        var result = new HashMap<String, String>();
        var invalidKeys = new HashSet<String>();
        for (int i = 0; i < args.length; i++) {
            var arg = args[i];
            if (arg.startsWith("--")) {
                var withoutPrefix = arg.substring(2);
                String key;
                String value;

                if (withoutPrefix.contains("=")) {
                    // Form: --key=value
                    var parts = withoutPrefix.split("=", 2);
                    key = parts[0];
                    value = parts.length > 1 ? parts[1] : "";
                } else {
                    // Form: --key value
                    key = withoutPrefix;
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        value = args[++i];
                    } else {
                        value = "";
                    }
                }

                // Track invalid keys
                if (!VALID_ARGS.contains(key)) {
                    invalidKeys.add(key);
                } else {
                    result.put(key, value);
                }
            }
        }
        return new ParseArgsResult(result, invalidKeys);
    }

    /*
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

    /**
     * Create a copy of the parsed arguments map with sensitive values redacted.
     * Sensitive keys include: auth-token, brokk-api-key
     *
     * @param parsedArgs the original parsed arguments map
     * @return a new map with sensitive values replaced with [REDACTED]
     */
    private static Map<String, String> redactSensitiveArgs(Map<String, String> parsedArgs) {
        var redacted = new HashMap<>(parsedArgs);
        if (redacted.containsKey("auth-token")) {
            redacted.put("auth-token", "[REDACTED]");
        }
        if (redacted.containsKey("brokk-api-key")) {
            redacted.put("brokk-api-key", "[REDACTED]");
        }
        return redacted;
    }

    /**
     * Print usage/help information and exit.
     * If invalidArgs is non-empty, prints an error message first and exits with code 1.
     * If invalidArgs is empty, prints help and exits with code 0.
     */
    private static void printUsageAndExit(Set<String> invalidArgs) {
        if (!invalidArgs.isEmpty()) {
            System.err.println("Error: Unknown argument(s): "
                    + invalidArgs.stream().map(arg -> "--" + arg).collect(Collectors.joining(", ")));
            System.err.println();
        }

        System.out.println("Usage: java HeadlessExecutorMain [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --exec-id <uuid>           Executor UUID (required)");
        System.out.println("  --listen-addr <host:port>  Address to listen on (required)");
        System.out.println("  --auth-token <token>       Authentication token (required)");
        System.out.println("  --workspace-dir <path>     Path to workspace directory (required)");
        System.out.println("  --brokk-api-key <key>      Brokk API key override (optional)");
        System.out.println("  --proxy-setting <setting>  LLM proxy: BROKK, LOCALHOST, STAGING (optional)");
        System.out.println("  --help                     Show this help message");
        System.out.println();
        System.out.println("Arguments can also be provided via environment variables:");
        System.out.println("  EXEC_ID, LISTEN_ADDR, AUTH_TOKEN, WORKSPACE_DIR, BROKK_API_KEY, PROXY_SETTING");
        System.out.println();

        System.exit(invalidArgs.isEmpty() ? 0 : 1);
    }

    public HeadlessExecutorMain(UUID execId, String listenAddr, String authToken, ContextManager contextManager)
            throws IOException {
        this.execId = execId;
        this.contextManager = contextManager;

        // Parse listen address
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

        // Derive workspace and sessions paths from the provided ContextManager
        var workspaceDir = contextManager.getProject().getRoot();
        var sessionsDir = contextManager.getProject().getSessionManager().getSessionsDir();

        logger.info(
                "Initializing HeadlessExecutorMain: execId={}, listen={}:{}, workspace={}",
                execId,
                host,
                port,
                workspaceDir);

        // Ensure sessions directory exists
        Files.createDirectories(sessionsDir);

        // Initialize JobStore and SessionManager
        this.jobStore = new JobStore(workspaceDir.resolve(".brokk").resolve("jobs"));
        this.sessionManager = new SessionManager(sessionsDir);

        // Initialize headless context asynchronously to avoid blocking constructor
        var initThread = new Thread(
                () -> {
                    try {
                        this.contextManager.createHeadless();
                        logger.info("ContextManager headless initialization complete");
                    } catch (Exception e) {
                        logger.warn("ContextManager headless initialization failed", e);
                    }
                },
                "ContextManager-Init");
        initThread.setDaemon(true);
        initThread.start();

        // Initialize JobRunner
        this.jobRunner = new ai.brokk.executor.jobs.JobRunner(this.contextManager, this.jobStore);

        // Create HTTP server with authentication
        this.server = new SimpleHttpServer(host, port, authToken, 4);

        // Register endpoints
        this.server.registerUnauthenticatedContext("/health/live", this::handleHealthLive);
        this.server.registerUnauthenticatedContext("/health/ready", this::handleHealthReady);
        this.server.registerUnauthenticatedContext("/v1/executor", this::handleExecutor);
        // Sessions router handles:
        // - POST /v1/sessions                 (create a new session by name)
        // - PUT  /v1/sessions                 (import/load an existing session from a zip)
        // - GET  /v1/sessions/{sessionId}     (download a session zip)
        this.server.registerAuthenticatedContext("/v1/sessions", this::handleSessionsRouter);
        this.server.registerAuthenticatedContext("/v1/jobs", this::handleJobsRouter);
        this.server.registerAuthenticatedContext("/v1/context/files", this::handlePostContextFiles);
        this.server.registerAuthenticatedContext("/v1/context/classes", this::handlePostContextClasses);
        this.server.registerAuthenticatedContext("/v1/context/methods", this::handlePostContextMethods);
        this.server.registerAuthenticatedContext("/v1/context/text", this::handlePostContextText);

        logger.info("HeadlessExecutorMain initialized successfully");
    }

    private void handleHealthLive(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            var error = ErrorPayload.of(ErrorPayload.Code.METHOD_NOT_ALLOWED, "Method not allowed");
            SimpleHttpServer.sendJsonResponse(exchange, 405, error);
            return;
        }

        var response = Map.of("execId", this.execId.toString(), "version", BuildInfo.version, "protocolVersion", 1);

        SimpleHttpServer.sendJsonResponse(exchange, response);
    }

    private void handleHealthReady(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            var error = ErrorPayload.of(ErrorPayload.Code.METHOD_NOT_ALLOWED, "Method not allowed");
            SimpleHttpServer.sendJsonResponse(exchange, 405, error);
            return;
        }

        if (!sessionLoaded) {
            logger.info("/health/ready requested before session is loaded; returning 503");
            var error = ErrorPayload.of("NOT_READY", "No session loaded");
            SimpleHttpServer.sendJsonResponse(exchange, 503, error);
            return;
        }

        var sessionId = contextManager.getCurrentSessionId();

        var response = Map.of("status", "ready", "sessionId", sessionId.toString());
        SimpleHttpServer.sendJsonResponse(exchange, response);
    }

    private void handleExecutor(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            var error = ErrorPayload.of(ErrorPayload.Code.METHOD_NOT_ALLOWED, "Method not allowed");
            SimpleHttpServer.sendJsonResponse(exchange, 405, error);
            return;
        }

        var response = Map.of("execId", this.execId.toString(), "version", BuildInfo.version, "protocolVersion", 1);

        SimpleHttpServer.sendJsonResponse(exchange, response);
    }

    /**
     * Asynchronously execute a job. Called after a new job is created.
     * Delegates to JobRunner and handles per-job cleanup and reservation lifecycle.
     *
     * @param jobId the job identifier
     * @param jobSpec the job specification
     * @param seededTextFragmentIds IDs of any pasted text fragments seeded for this job
     */
    private void executeJobAsync(String jobId, JobSpec jobSpec, List<String> seededTextFragmentIds) {
        logger.info("Starting job execution: {}, session={}", jobId, contextManager.getCurrentSessionId());
        final List<String> fragmentIds = List.copyOf(seededTextFragmentIds);

        jobRunner.runAsync(jobId, jobSpec).whenComplete((unused, throwable) -> {
            try {
                if (!fragmentIds.isEmpty()) {
                    var idSet = new java.util.HashSet<>(fragmentIds);
                    var live = contextManager.liveContext();
                    var toDrop = live.allFragments()
                            .filter(f -> idSet.contains(f.id()))
                            .collect(Collectors.toList());

                    if (!toDrop.isEmpty()) {
                        try {
                            contextManager.drop(toDrop);
                            logger.info(
                                    "Cleaned up job-scoped text fragments: requested={}, foundAndDropped={}, session={}",
                                    fragmentIds.size(),
                                    toDrop.size(),
                                    contextManager.getCurrentSessionId());
                        } catch (Exception dropEx) {
                            logger.warn(
                                    "Cleanup failed for job-scoped text fragments: requested={}, found={}, session={}, error={}",
                                    fragmentIds.size(),
                                    toDrop.size(),
                                    contextManager.getCurrentSessionId(),
                                    dropEx.toString());
                        }
                    } else {
                        logger.info(
                                "No job-scoped text fragments found to clean up: requested={}, session={}",
                                fragmentIds.size(),
                                contextManager.getCurrentSessionId());
                    }
                }
            } catch (Exception cleanupEx) {
                logger.warn(
                        "Unexpected error during cleanup for job {}: requestedFragments={}, error={}",
                        jobId,
                        fragmentIds.size(),
                        cleanupEx.toString());
            } finally {
                if (throwable != null) {
                    logger.error(
                            "Job {} execution failed (session={})",
                            jobId,
                            contextManager.getCurrentSessionId(),
                            throwable);
                } else {
                    logger.info("Job {} execution finished (session={})", jobId, contextManager.getCurrentSessionId());
                }
                // Release reservation only if we still own it; CAS avoids clearing another job's reservation.
                jobReservation.releaseIfOwner(jobId);
            }
        });
    }

    public void start() {
        this.server.start();
        logger.info(
                "HeadlessExecutorMain HTTP server started on endpoints: /health/live, /v1/sessions, /v1/jobs, etc.; cmSession={}",
                contextManager.getCurrentSessionId());
    }

    public void stop(int delaySeconds) {
        try {
            this.contextManager.close();
        } catch (Exception e) {
            logger.warn("Error closing ContextManager", e);
        }
        try {
            this.sessionManager.close();
        } catch (Exception e) {
            logger.warn("Error closing SessionManager", e);
        }
        this.server.stop(delaySeconds);
        logger.info("HeadlessExecutorMain stopped");
    }

    /**
     * Return the actual port the server is bound to.
     *
     * @return the listening port number
     */
    public int getPort() {
        return this.server.getPort();
    }

    // ============================================================================
    // Helpers
    // ============================================================================

    /**
     * Extract jobId from path like /v1/jobs/abc123 or /v1/jobs/abc123/events.
     * Returns null if the path is invalid or if the extracted jobId is blank.
     */
    static @org.jetbrains.annotations.Nullable String extractJobIdFromPath(String path) {
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
     * Parse query string into a map.
     */
    static Map<String, String> parseQueryParams(@org.jetbrains.annotations.Nullable String query) {
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

    private static void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        var error = ErrorPayload.of(ErrorPayload.Code.METHOD_NOT_ALLOWED, "Method not allowed");
        SimpleHttpServer.sendJsonResponse(exchange, 405, error);
    }

    private static boolean ensureMethod(HttpExchange exchange, String expected) throws IOException {
        if (!exchange.getRequestMethod().equals(expected)) {
            sendMethodNotAllowed(exchange);
            return false;
        }
        return true;
    }

    private static void sendValidationError(HttpExchange exchange, String message) throws IOException {
        SimpleHttpServer.sendJsonResponse(exchange, 400, ErrorPayload.validationError(message));
    }

    private static <T> @Nullable T parseJsonOr400(HttpExchange exchange, Class<T> valueType, String route)
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
     * Try to reserve the exclusive job slot for the given jobId.
     * Uses CAS to ensure only one concurrent job is executing.
     *
     * @param jobId the job identifier attempting to reserve the slot
     * @return true if reservation succeeded; false if another job holds the slot
     */
    private boolean tryReserveJobSlot(String jobId) {
        assert !jobId.isBlank();
        return jobReservation.tryReserve(jobId);
    }

    // ============================================================================
    // Router for /v1/sessions endpoints
    // ============================================================================

    /**
     * Route requests to /v1/sessions based on HTTP method.
     * - POST: create a new session by name
     * - PUT: import/load an existing session zip
     * - GET: download a session zip by session ID
     */
    void handleSessionsRouter(HttpExchange exchange) throws IOException {
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
            var parts = Splitter.on('/').omitEmptyStrings().splitToList(normalizedPath);
            if (parts.size() >= 3 && "v1".equals(parts.get(0)) && "sessions".equals(parts.get(1))) {
                var sessionIdText = parts.get(2);
                boolean validPath = parts.size() == 3;
                if (!validPath) {
                    var error = ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found");
                    SimpleHttpServer.sendJsonResponse(exchange, 404, error);
                    return;
                }
                if (sessionIdText.isBlank()) {
                    sendValidationError(exchange, "Session ID is required");
                    return;
                }
                try {
                    var sessionId = UUID.fromString(sessionIdText);
                    handleGetSessionZip(exchange, sessionId);
                } catch (IllegalArgumentException e) {
                    sendValidationError(exchange, "Invalid session ID in path");
                }
                return;
            }
            var error = ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found");
            SimpleHttpServer.sendJsonResponse(exchange, 404, error);
            return;
        }
        var error = ErrorPayload.of(ErrorPayload.Code.METHOD_NOT_ALLOWED, "Method not allowed");
        SimpleHttpServer.sendJsonResponse(exchange, 405, error);
    }

    // ============================================================================
    // Router for /v1/jobs endpoints
    // ============================================================================

    /**
     * Route requests to /v1/jobs and sub-paths based on method and path.
     */
    void handleJobsRouter(HttpExchange exchange) throws IOException {
        var path = exchange.getRequestURI().getPath();
        var method = exchange.getRequestMethod();

        // POST /v1/jobs - create job
        if (path.equals("/v1/jobs") && method.equals("POST")) {
            handlePostJobs(exchange);
            return;
        }

        // Extract jobId from path for other operations
        var jobId = extractJobIdFromPath(path);
        if (jobId == null || jobId.isBlank()) {
            var error = ErrorPayload.of(ErrorPayload.Code.BAD_REQUEST, "Invalid job path");
            SimpleHttpServer.sendJsonResponse(exchange, 400, error);
            return;
        }

        // GET /v1/jobs/{jobId} - get job status
        if (path.equals("/v1/jobs/" + jobId) && method.equals("GET")) {
            handleGetJob(exchange, jobId);
            return;
        }

        // GET /v1/jobs/{jobId}/events - get job events
        if (path.equals("/v1/jobs/" + jobId + "/events") && method.equals("GET")) {
            handleGetJobEvents(exchange, jobId);
            return;
        }

        // POST /v1/jobs/{jobId}/cancel - cancel job
        if (path.equals("/v1/jobs/" + jobId + "/cancel") && method.equals("POST")) {
            handleCancelJob(exchange, jobId);
            return;
        }

        // GET /v1/jobs/{jobId}/diff - get diff
        if (path.equals("/v1/jobs/" + jobId + "/diff") && method.equals("GET")) {
            handleGetJobDiff(exchange, jobId);
            return;
        }

        // Unknown endpoint
        var error = ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found");
        SimpleHttpServer.sendJsonResponse(exchange, 404, error);
    }

    // ============================================================================
    // Session and Job Handlers
    // ============================================================================

    /**
     * POST /v1/sessions - Create a new session programmatically.
     * <p>
     * <b>Authentication:</b> Required (via Authorization header)
     * <p>
     * <b>Request Body (JSON):</b>
     * <pre>
     * {
     *   "name": "Session Name"
     * }
     * </pre>
     * <p>
     * <b>Response (201 Created):</b>
     * <pre>
     * {
     *   "sessionId": "uuid",
     *   "name": "Session Name"
     * }
     * </pre>
     * <p>
     * <b>Validation:</b>
     * <ul>
     *   <li>Session name is required and must not be blank</li>
     *   <li>Session name must not exceed 200 characters (after trimming)</li>
     * </ul>
     * <p>
     * <b>Side Effects:</b>
     * <ul>
     *   <li>Creates a new session in the SessionManager</li>
     *   <li>Switches ContextManager to the newly created session</li>
     *   <li>Sets sessionLoaded flag to true, enabling /health/ready</li>
     * </ul>
     */
    void handleCreateSession(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            var error = ErrorPayload.of(ErrorPayload.Code.METHOD_NOT_ALLOWED, "Method not allowed");
            SimpleHttpServer.sendJsonResponse(exchange, 405, error);
            return;
        }

        CreateSessionRequest request;
        try {
            request = SimpleHttpServer.parseJsonRequest(exchange, CreateSessionRequest.class);
        } catch (Exception parseEx) {
            logger.warn("Invalid JSON in POST /v1/sessions", parseEx);
            var error = ErrorPayload.validationError("Invalid JSON request body");
            SimpleHttpServer.sendJsonResponse(exchange, 400, error);
            return;
        }

        if (request == null) {
            var error = ErrorPayload.validationError("Request body is required");
            SimpleHttpServer.sendJsonResponse(exchange, 400, error);
            return;
        }

        if (request.name().isBlank()) {
            var error = ErrorPayload.validationError("Session name is required and must not be blank");
            SimpleHttpServer.sendJsonResponse(exchange, 400, error);
            return;
        }

        var sessionName = request.name().strip();

        if (sessionName.length() > 200) {
            var error = ErrorPayload.validationError("Session name must not exceed 200 characters");
            SimpleHttpServer.sendJsonResponse(exchange, 400, error);
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

            System.out.println("Session created: " + sessionId + " (" + sessionName + ")");
            System.out.println("Executor ready to accept requests.");

            sessionLoaded = true;

            var response = Map.of("sessionId", sessionId.toString(), "name", sessionName);
            SimpleHttpServer.sendJsonResponse(exchange, 201, response);
        } catch (Exception e) {
            logger.error("Error handling POST /v1/sessions", e);
            var error = ErrorPayload.internalError("Failed to create session", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    /**
     * PUT /v1/sessions - Upload and import a session from a zip file.
     * <p>
     * <b>Authentication:</b> Required (via Authorization header)
     * <p>
     * <b>Request Headers:</b>
     * <ul>
     *   <li>X-Session-Id (optional): UUID for the session; generated if not provided</li>
     *   <li>Content-Type: application/zip (binary zip data in request body)</li>
     * </ul>
     * <p>
     * <b>Response (201 Created):</b>
     * <pre>
     * {
     *   "sessionId": "uuid"
     * }
     * </pre>
     * <p>
     * <b>Side Effects:</b>
     * <ul>
     *   <li>Writes the zip file to sessionsDir/{sessionId}.zip</li>
     *   <li>Switches ContextManager to the imported session</li>
     *   <li>Sets sessionLoaded flag to true, enabling /health/ready</li>
     * </ul>
     */
    void handlePutSession(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("PUT")) {
            var error = ErrorPayload.of(ErrorPayload.Code.METHOD_NOT_ALLOWED, "Method not allowed");
            SimpleHttpServer.sendJsonResponse(exchange, 405, error);
            return;
        }

        try {
            // Get sessionId from header or generate new one
            var sessionIdHeader = exchange.getRequestHeaders().getFirst("X-Session-Id");
            var sessionId = sessionIdHeader != null && !sessionIdHeader.isBlank()
                    ? UUID.fromString(sessionIdHeader)
                    : UUID.randomUUID();

            // Read zip data from request body
            byte[] zipData;
            try (InputStream requestBody = exchange.getRequestBody()) {
                zipData = requestBody.readAllBytes();
            }

            // Import session and get the imported session ID
            importSessionZip(zipData, sessionId);

            var response = Map.of("sessionId", sessionId.toString());
            SimpleHttpServer.sendJsonResponse(exchange, 201, response);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid session ID in header", e);
            var error = ErrorPayload.validationError("Invalid X-Session-Id header: " + e.getMessage());
            SimpleHttpServer.sendJsonResponse(exchange, 400, error);
        } catch (Exception e) {
            logger.error("Error handling PUT /v1/sessions", e);
            var error = ErrorPayload.internalError("Failed to process session upload", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    /**
     * GET /v1/sessions/{sessionId} - Download a session zip.
     */
    void handleGetSessionZip(HttpExchange exchange, UUID sessionId) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            sendMethodNotAllowed(exchange);
            return;
        }

        var sessionZipPath =
                sessionManager.getSessionsDir().resolve(sessionId + ".zip");
        if (!Files.exists(sessionZipPath)) {
            var error = ErrorPayload.of(
                    ErrorPayload.Code.NOT_FOUND, "Session zip not found for session " + sessionId);
            SimpleHttpServer.sendJsonResponse(exchange, 404, error);
            return;
        }

        try {
            long fileSize = Files.size(sessionZipPath);
            var headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/zip");
            headers.set("Content-Disposition", "attachment; filename=\"" + sessionId + ".zip\"");
            exchange.sendResponseHeaders(200, fileSize);
            try (var responseBody = exchange.getResponseBody()) {
                Files.copy(sessionZipPath, responseBody);
            }
        } catch (IOException e) {
            logger.error("Failed to stream session zip {}", sessionId, e);
            var error = ErrorPayload.internalError("Failed to stream session zip", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    /**
     * Import a session zip file by writing it to sessionsDir and switching ContextManager to it.
     * This helper encapsulates the core session import logic for reusability.
     *
     * @param zipData the zip file contents
     * @param sessionId the UUID for this session
     * @throws IOException if writing the zip file fails
     * @throws Exception if switching the session fails
     */
    void importSessionZip(byte[] zipData, UUID sessionId) throws Exception {
        // Write zip file to the sessions directory as reported by the project's SessionManager.
        // This ensures we store the uploaded session in the exact location expected by SessionManager
        // and avoids mismatches that can lead to missing session zip files during loading.
        var cmSessionsDir = contextManager.getProject().getSessionManager().getSessionsDir();
        Files.createDirectories(cmSessionsDir);
        var sessionZipPath = cmSessionsDir.resolve(sessionId.toString() + ".zip");
        Files.write(sessionZipPath, zipData, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        logger.info("Session zip stored: {} ({})", sessionId, sessionZipPath);

        // Switch ContextManager to this session with timeout to avoid indefinite blocking
        try {
            contextManager.switchSessionAsync(sessionId).get(3, TimeUnit.SECONDS);
            logger.info(
                    "Switched to session: {}; active session now: {}", sessionId, contextManager.getCurrentSessionId());
        } catch (TimeoutException e) {
            logger.warn("Timed out switching to session {}; continuing asynchronously", sessionId);
        }

        System.out.println("Session imported: " + sessionId);
        System.out.println("Executor ready to accept requests.");

        // Mark executor as ready to serve requests that require a session.
        sessionLoaded = true;
    }

    /**
     * POST /v1/jobs - Create job with idempotency key.
     */
    void handlePostJobs(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            var error = ErrorPayload.of(ErrorPayload.Code.METHOD_NOT_ALLOWED, "Method not allowed");
            SimpleHttpServer.sendJsonResponse(exchange, 405, error);
            return;
        }
        try {
            var idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                var error = ErrorPayload.validationError("Idempotency-Key header is required");
                SimpleHttpServer.sendJsonResponse(exchange, 400, error);
                return;
            }
            var sessionIdStr = exchange.getRequestHeaders().getFirst("X-Session-Id");
            UUID sessionId = null;
            if (sessionIdStr != null && !sessionIdStr.isBlank()) {
                try {
                    sessionId = UUID.fromString(sessionIdStr);
                } catch (IllegalArgumentException e) {
                    var error = ErrorPayload.validationError("Invalid Session-Id format: must be a valid UUID");
                    SimpleHttpServer.sendJsonResponse(exchange, 400, error);
                    return;
                }
            }
            var githubToken = exchange.getRequestHeaders().getFirst("X-Github-Token");

            // Parse JobSpec payload from request body
            var jobSpecRequest = SimpleHttpServer.parseJsonRequest(exchange, JobSpecRequest.class);
            if (jobSpecRequest == null) {
                var error = ErrorPayload.validationError("Invalid JobSpec in request body");
                SimpleHttpServer.sendJsonResponse(exchange, 400, error);
                return;
            }

            var plannerModel = Objects.requireNonNullElse(jobSpecRequest.plannerModel(), "")
                    .strip();
            if (plannerModel.isBlank()) {
                var error = ErrorPayload.validationError("plannerModel is required");
                SimpleHttpServer.sendJsonResponse(exchange, 400, error);
                return;
            }

            var tags = jobSpecRequest.tags();
            if (tags != null) {
                if (sessionId != null) {
                    tags.put("session_id", sessionId.toString());
                }
                if (githubToken != null && !githubToken.isBlank()) {
                    tags.put("github_token", githubToken);
                }
            }

            Map<String, String> safeTags = tags != null ? Map.copyOf(tags) : Map.of();
            boolean preScanFlag = Objects.requireNonNullElse(jobSpecRequest.preScan(), false);

            // Optional job-scoped context text: accept from either top-level contextText or nested context.text
            var requestedJobContextTexts = new ArrayList<String>();
            var topLevelTexts = jobSpecRequest.contextText();
            if (topLevelTexts != null) {
                requestedJobContextTexts.addAll(topLevelTexts);
            }
            var contextObj = jobSpecRequest.context();
            if (contextObj != null && contextObj.text() != null) {
                requestedJobContextTexts.addAll(contextObj.text());
            }
            int requestedTopLevelCount = topLevelTexts != null ? topLevelTexts.size() : 0;
            int requestedNestedCount = (contextObj != null && contextObj.text() != null)
                    ? contextObj.text().size()
                    : 0;
            logger.info(
                    "Job {} context text presence: topLevel={}, nested={}, total={}",
                    idempotencyKey,
                    requestedTopLevelCount,
                    requestedNestedCount,
                    requestedJobContextTexts.size());

            // Validate context text entries if any were supplied
            final int MAX_BYTES = 1024 * 1024; // 1 MiB
            var validJobContextTexts = new ArrayList<String>();
            var invalidContextEntries = new ArrayList<String>();
            if (!requestedJobContextTexts.isEmpty()) {
                for (int i = 0; i < requestedJobContextTexts.size(); i++) {
                    var t = requestedJobContextTexts.get(i);
                    if (t == null || t.isBlank()) {
                        invalidContextEntries.add("index " + i + " (blank)");
                        continue;
                    }
                    int byteLen = t.getBytes(UTF_8).length;
                    if (byteLen > MAX_BYTES) {
                        invalidContextEntries.add("index " + i + " (exceeds " + MAX_BYTES + " bytes)");
                        continue;
                    }
                    validJobContextTexts.add(t);
                }
                logger.info(
                        "Job {} context text validation: valid={}, invalid={}",
                        idempotencyKey,
                        validJobContextTexts.size(),
                        invalidContextEntries.size());
                if (validJobContextTexts.isEmpty()) {
                    var msg = "No valid context text provided";
                    if (!invalidContextEntries.isEmpty()) {
                        msg += "; invalid: " + String.join(", ", invalidContextEntries);
                    }
                    sendValidationError(exchange, msg);
                    return;
                }
            }

            var jobSpec = JobSpec.of(
                    jobSpecRequest.taskInput(),
                    jobSpecRequest.autoCommit(),
                    jobSpecRequest.autoCompress(),
                    plannerModel,
                    jobSpecRequest.scanModel(),
                    jobSpecRequest.codeModel(),
                    preScanFlag,
                    safeTags);

            // Create or get job (idempotent)
            var createResult = jobStore.createOrGetJob(idempotencyKey, jobSpec);
            var jobId = createResult.jobId();
            var isNewJob = createResult.isNewJob();

            logger.info(
                    "Job {}: isNewJob={}, jobId={}, sessionId={}, currentCmSession={}",
                    idempotencyKey,
                    isNewJob,
                    jobId,
                    sessionId,
                    jobSpecRequest.sessionId());

            // Load job status
            var status = jobStore.loadStatus(jobId);
            var state = status != null ? status.state() : "queued";

            var response = new HashMap<String, Object>();
            response.put("jobId", jobId);
            response.put("state", state);

            if (isNewJob) {
                // Atomically reserve the job slot; fail fast if another job is in progress
                if (!tryReserveJobSlot(jobId)) {
                    logger.info(
                            "Job reservation failed; another job in progress: {}, requested jobId={}, idempotencyKey={}, cmSession={}",
                            jobReservation.current(),
                            jobId,
                            idempotencyKey,
                            contextManager.getCurrentSessionId());
                    var error = ErrorPayload.of("JOB_IN_PROGRESS", "A job is currently executing");
                    SimpleHttpServer.sendJsonResponse(exchange, 409, error);
                    return;
                }
                logger.info(
                        "Job reservation succeeded; jobId={}, idempotencyKey={}, cmSession={}",
                        jobId,
                        idempotencyKey,
                        contextManager.getCurrentSessionId());
                try {
                    // Add any validated job-scoped context text fragments before starting execution
                    var contextTextFragmentIds = new ArrayList<String>();
                    if (!validJobContextTexts.isEmpty()) {
                        for (var txt : validJobContextTexts) {
                            contextManager.addPastedTextFragment(txt);
                            var live = contextManager.liveContext();
                            var fragments = live.getAllFragmentsInDisplayOrder();
                            for (int i = fragments.size() - 1; i >= 0; i--) {
                                var f = fragments.get(i);
                                if (f.getType() == ContextFragment.FragmentType.PASTE_TEXT) {
                                    var id = f.id();
                                    if (!contextTextFragmentIds.contains(id)) {
                                        contextTextFragmentIds.add(id);
                                    }
                                    break;
                                }
                            }
                        }
                        int totalChars = validJobContextTexts.stream()
                                .mapToInt(String::length)
                                .sum();
                        logger.info(
                                "Added {} job-scoped context text fragments (totalChars={})",
                                contextTextFragmentIds.size(),
                                totalChars);
                        response.put("contextTextFragmentIds", contextTextFragmentIds);
                    }

                    // Start execution asynchronously; release reservation and cleanup in callback or on failure
                    executeJobAsync(jobId, jobSpec, contextTextFragmentIds);
                } catch (Exception ex) {
                    // Release reservation if scheduling failed before the async pipeline was established
                    var rolledBack = jobReservation.releaseIfOwner(jobId);
                    logger.warn(
                            "Reservation rollback after scheduling failure; jobId={}, idempotencyKey={}, rolledBack={}, cmSession={}",
                            jobId,
                            idempotencyKey,
                            rolledBack,
                            contextManager.getCurrentSessionId());
                    logger.error("Failed to start job {}", jobId, ex);
                    var error = ErrorPayload.internalError("Failed to start job execution", ex);
                    SimpleHttpServer.sendJsonResponse(exchange, 500, error);
                    return;
                }
                SimpleHttpServer.sendJsonResponse(exchange, 201, response);
            } else {
                SimpleHttpServer.sendJsonResponse(exchange, 200, response);
            }
        } catch (Exception e) {
            logger.error("Error handling POST /v1/jobs", e);
            var error = ErrorPayload.internalError("Failed to create job", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    /**
     * GET /v1/jobs/{jobId} - Get job status.
     */
    void handleGetJob(HttpExchange exchange, String jobId) throws IOException {
        try {

            var status = jobStore.loadStatus(jobId);
            if (status == null) {
                var error = ErrorPayload.jobNotFound(jobId);
                SimpleHttpServer.sendJsonResponse(exchange, 404, error);
                return;
            }

            SimpleHttpServer.sendJsonResponse(exchange, status);
        } catch (Exception e) {
            logger.error("Error handling GET /v1/jobs/{jobId}", e);
            var error = ErrorPayload.internalError("Failed to retrieve job status", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    /**
     * GET /v1/jobs/{jobId}/events - Get job events.
     */
    void handleGetJobEvents(HttpExchange exchange, String jobId) throws IOException {
        try {

            // Parse query parameters
            var queryParams = parseQueryParams(exchange.getRequestURI().getQuery());
            long afterSeq = -1;
            int limit = 100;

            if (queryParams.containsKey("after")) {
                try {
                    afterSeq = Long.parseLong(queryParams.get("after"));
                } catch (NumberFormatException e) {
                    var error = ErrorPayload.validationError("Invalid 'after' parameter");
                    SimpleHttpServer.sendJsonResponse(exchange, 400, error);
                    return;
                }
            }

            if (queryParams.containsKey("limit")) {
                try {
                    limit = Math.min(1000, Integer.parseInt(queryParams.get("limit")));
                } catch (NumberFormatException e) {
                    var error = ErrorPayload.validationError("Invalid 'limit' parameter");
                    SimpleHttpServer.sendJsonResponse(exchange, 400, error);
                    return;
                }
            }

            var events = jobStore.readEvents(jobId, afterSeq, limit);
            long nextAfter = events.isEmpty() ? afterSeq : events.getLast().seq();

            var response = Map.of(
                    "events", events,
                    "nextAfter", nextAfter);

            SimpleHttpServer.sendJsonResponse(exchange, response);
        } catch (Exception e) {
            logger.error("Error handling GET /v1/jobs/{jobId}/events", e);
            var error = ErrorPayload.internalError("Failed to retrieve events", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    /**
     * POST /v1/jobs/{jobId}/cancel - Cancel job execution.
     */
    void handleCancelJob(HttpExchange exchange, String jobId) throws IOException {
        try {

            // Request job cancellation via JobRunner
            jobRunner.cancel(jobId);
            logger.info("Cancelled job: {}, session={}", jobId, contextManager.getCurrentSessionId());

            exchange.sendResponseHeaders(202, 0);
            exchange.close();
        } catch (Exception e) {
            logger.error("Error handling POST /v1/jobs/{jobId}/cancel", e);
            var error = ErrorPayload.internalError("Failed to cancel job", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    /**
     * GET /v1/jobs/{jobId}/diff - Get git diff for job.
     */
    void handleGetJobDiff(HttpExchange exchange, String jobId) throws IOException {
        try {

            var status = jobStore.loadStatus(jobId);
            if (status == null) {
                var error = ErrorPayload.jobNotFound(jobId);
                SimpleHttpServer.sendJsonResponse(exchange, 404, error);
                return;
            }

            try {
                var repo = contextManager.getProject().getRepo();
                var diff = repo.diff();

                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
                exchange.sendResponseHeaders(200, diff.getBytes(UTF_8).length);
                try (var os = exchange.getResponseBody()) {
                    os.write(diff.getBytes(UTF_8));
                }
                exchange.close();
            } catch (UnsupportedOperationException e) {
                logger.info("Git not available for job {}, session={}", jobId, contextManager.getCurrentSessionId());
                var error = ErrorPayload.of("NO_GIT", "Git is not available in this workspace");
                SimpleHttpServer.sendJsonResponse(exchange, 409, error);
            }
        } catch (Exception e) {
            logger.error("Error handling GET /v1/jobs/{jobId}/diff", e);
            var error = ErrorPayload.internalError("Failed to compute diff", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }
    /**
     * POST /v1/context/files - Add files to the current session context.
     * <p>
     * <b>Authentication:</b> Required (via Authorization header)
     * <p>
     * <b>Request Body (JSON):</b>
     * <pre>
     * {
     *   "relativePaths": ["src/main/java/com/example/Foo.java", "src/test/java/com/example/FooTest.java"]
     * }
     * </pre>
     * <p>
     * <b>Response (200 OK):</b>
     * <pre>
     * {
     *   "added": [
     *     { "id": "1", "relativePath": "src/main/java/com/example/Foo.java" },
     *     { "id": "2", "relativePath": "src/test/java/com/example/FooTest.java" }
     *   ]
     * }
     * </pre>
     * <p>
     * <b>Validation:</b>
     * <ul>
     *   <li>Paths must be relative (not absolute)</li>
     *   <li>Paths must not escape the workspace (no "../.." attacks)</li>
     *   <li>Paths must refer to regular files that exist</li>
     *   <li>At least one valid path must be provided</li>
     * </ul>
     * <p>
     * <b>Side Effects:</b>
     * <ul>
     *   <li>Adds valid files to the current session's live context</li>
     *   <li>Emits a notification via IConsoleIO</li>
     *   <li>Pushes a new frozen context to history</li>
     * </ul>
     */
    void handlePostContextFiles(HttpExchange exchange) throws IOException {
        if (!ensureMethod(exchange, "POST")) {
            return;
        }

        try {
            var request = parseJsonOr400(exchange, AddContextFilesRequest.class, "/v1/context/files");
            if (request == null) {
                return;
            }

            if (request.relativePaths().isEmpty()) {
                sendValidationError(exchange, "relativePaths must not be empty");
                return;
            }

            var root = contextManager.getProject().getRoot();
            var validProjectFiles = new HashSet<ProjectFile>();
            var invalidPaths = new ArrayList<String>();

            for (var pathStr : request.relativePaths()) {
                if (pathStr == null || pathStr.isBlank()) {
                    invalidPaths.add("(blank path)");
                    continue;
                }

                var pathObj = Path.of(pathStr);

                if (pathObj.isAbsolute()) {
                    invalidPaths.add(pathStr + " (absolute path not allowed)");
                    continue;
                }

                var absolutePath = root.resolve(pathObj).normalize();

                if (!absolutePath.startsWith(root)) {
                    invalidPaths.add(pathStr + " (escapes workspace)");
                    continue;
                }

                if (!Files.isRegularFile(absolutePath)) {
                    invalidPaths.add(pathStr + " (not a regular file or does not exist)");
                    continue;
                }

                var normalizedRelPath = root.relativize(absolutePath).toString();

                try {
                    var projectFile = contextManager.toFile(normalizedRelPath);
                    validProjectFiles.add(projectFile);
                } catch (Exception e) {
                    logger.warn("Failed to convert path to ProjectFile: {}", normalizedRelPath, e);
                    invalidPaths.add(normalizedRelPath + " (conversion error)");
                }
            }

            if (validProjectFiles.isEmpty()) {
                var msg = "No valid relative paths provided";
                if (!invalidPaths.isEmpty()) {
                    msg += "; invalid: " + String.join(", ", invalidPaths);
                }
                sendValidationError(exchange, msg);
                return;
            }

            var before = contextManager.getFilesInContext();

            contextManager.addFiles(validProjectFiles);

            var after = contextManager.getFilesInContext();

            var addedFiles = after.stream().filter(pf -> !before.contains(pf)).collect(Collectors.toList());

            var addedContextFiles = new ArrayList<AddedContextFile>();
            var liveContext = contextManager.liveContext();

            for (var projectFile : addedFiles) {
                var fragId = liveContext
                        .fileFragments()
                        .filter(f -> f instanceof ContextFragments.PathFragment)
                        .map(f -> (ContextFragments.PathFragment) f)
                        .filter(p -> {
                            var bf = p.file();
                            if (!(bf instanceof ai.brokk.analyzer.ProjectFile)) {
                                return false;
                            }
                            var a = bf.absPath();
                            var b = projectFile.absPath();
                            return a.equals(b);
                        })
                        .map(ai.brokk.context.ContextFragment::id)
                        .findFirst()
                        .orElse("");

                var relPath = root.relativize(projectFile.absPath()).toString();
                addedContextFiles.add(new AddedContextFile(fragId, relPath));
            }

            var response = new AddContextFilesResponse(addedContextFiles);
            logger.info(
                    "Added {} files to context (session={}): {}",
                    addedContextFiles.size(),
                    contextManager.getCurrentSessionId(),
                    addedContextFiles.stream()
                            .map(AddedContextFile::relativePath)
                            .collect(Collectors.joining(", ")));

            SimpleHttpServer.sendJsonResponse(exchange, 200, response);
        } catch (Exception e) {
            logger.error("Error handling POST /v1/context/files", e);
            var error = ErrorPayload.internalError("Failed to add files to context", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    private record CreateSessionRequest(String name) {}

    private record JobSpecRequest(
            String sessionId,
            String taskInput,
            boolean autoCommit,
            boolean autoCompress,
            @Nullable String plannerModel,
            @Nullable String scanModel,
            @Nullable String codeModel,
            @Nullable Boolean preScan,
            @Nullable Map<String, String> tags,
            @Nullable List<String> contextText,
            @Nullable ContextPayload context) {}

    private record ContextPayload(@Nullable List<String> text) {}

    private record AddContextFilesRequest(List<String> relativePaths) {}

    private record AddedContextFile(String id, String relativePath) {}

    private record AddContextFilesResponse(List<AddedContextFile> added) {}

    private record AddContextClassesRequest(List<String> classNames) {}

    private record AddedContextClass(String id, String className) {}

    private record AddContextClassesResponse(List<AddedContextClass> added) {}

    private record AddContextMethodsRequest(List<String> methodNames) {}

    private record AddedContextMethod(String id, String methodName) {}

    private record AddContextMethodsResponse(List<AddedContextMethod> added) {}

    private record AddContextTextRequest(String text) {}

    private record AddContextTextResponse(String id, int chars) {}

    /**
     * POST /v1/context/classes - Add class summaries to the current session context.
     * <p>
     * <b>Authentication:</b> Required (via Authorization header)
     * <p>
     * <b>Request Body (JSON):</b>
     * <pre>
     * {
     *   "classNames": ["com.example.Foo", "com.example.Bar"]
     * }
     * </pre>
     * <p>
     * <b>Response (200 OK):</b>
     * <pre>
     * {
     *   "added": [
     *     { "id": "1", "className": "com.example.Foo" },
     *     { "id": "2", "className": "com.example.Bar" }
     *   ]
     * }
     * </pre>
     * <p>
     * <b>Validation:</b>
     * <ul>
     *   <li>Class names must be non-empty and non-blank</li>
     *   <li>At least one valid class name must be provided</li>
     *   <li>Class names are resolved via the analyzer; invalid names are skipped with a warning</li>
     * </ul>
     * <p>
     * <b>Side Effects:</b>
     * <ul>
     *   <li>Adds class summary fragments to the current session's live context</li>
     *   <li>Emits a notification via IConsoleIO</li>
     *   <li>Pushes a new frozen context to history</li>
     * </ul>
     */
    void handlePostContextClasses(HttpExchange exchange) throws IOException {
        if (!ensureMethod(exchange, "POST")) {
            return;
        }

        try {
            var request = parseJsonOr400(exchange, AddContextClassesRequest.class, "/v1/context/classes");
            if (request == null) {
                return;
            }

            if (request.classNames().isEmpty()) {
                sendValidationError(exchange, "classNames must not be empty");
                return;
            }

            var analyzer = contextManager.getAnalyzerUninterrupted();
            var validClassNames = new ArrayList<String>();
            var invalidNames = new ArrayList<String>();

            for (var className : request.classNames()) {
                if (className == null || className.isBlank()) {
                    invalidNames.add("(blank name)");
                    continue;
                }

                var trimmed = className.strip();

                var definitions = analyzer.getDefinitions(trimmed);
                if (definitions.isEmpty()) {
                    invalidNames.add(trimmed + " (not found in analyzer)");
                    continue;
                }

                var codeUnit = definitions.stream().filter(CodeUnit::isClass).findFirst();
                if (codeUnit.isEmpty()) {
                    invalidNames.add(trimmed + " (not a class)");
                    continue;
                }

                validClassNames.add(trimmed);
            }

            if (validClassNames.isEmpty()) {
                var msg = "No valid class names provided";
                if (!invalidNames.isEmpty()) {
                    msg += "; invalid: " + String.join(", ", invalidNames);
                }
                sendValidationError(exchange, msg);
                return;
            }

            contextManager.addSummaries(
                    Set.of(),
                    validClassNames.stream()
                            .flatMap(name ->
                                    analyzer.getDefinitions(name).stream().filter(CodeUnit::isClass))
                            .collect(Collectors.toSet()));

            var addedClasses = new ArrayList<AddedContextClass>();
            var liveContext = contextManager.liveContext();

            for (var className : validClassNames) {
                var fragId = liveContext
                        .virtualFragments()
                        .filter(f -> f instanceof ContextFragments.SummaryFragment)
                        .map(f -> (ContextFragments.SummaryFragment) f)
                        .filter(s -> s.getTargetIdentifier().contains(className))
                        .map(ContextFragment::id)
                        .findFirst()
                        .orElse("");

                addedClasses.add(new AddedContextClass(fragId, className));
            }

            var response = new AddContextClassesResponse(addedClasses);
            logger.info(
                    "Added {} classes to context (session={}): {}",
                    addedClasses.size(),
                    contextManager.getCurrentSessionId(),
                    addedClasses.stream().map(AddedContextClass::className).collect(Collectors.joining(", ")));

            SimpleHttpServer.sendJsonResponse(exchange, 200, response);
        } catch (Exception e) {
            logger.error("Error handling POST /v1/context/classes", e);
            var error = ErrorPayload.internalError("Failed to add classes to context", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    /**
     * POST /v1/context/methods - Add method sources to the current session context.
     * <p>
     * <b>Authentication:</b> Required (via Authorization header)
     * <p>
     * <b>Request Body (JSON):</b>
     * <pre>
     * {
     *   "methodNames": ["com.example.Foo.bar", "com.example.Baz.qux"]
     * }
     * </pre>
     * <p>
     * <b>Response (200 OK):</b>
     * <pre>
     * {
     *   "added": [
     *     { "id": "1", "methodName": "com.example.Foo.bar" },
     *     { "id": "2", "methodName": "com.example.Baz.qux" }
     *   ]
     * }
     * </pre>
     * <p>
     * <b>Validation:</b>
     * <ul>
     *   <li>Method names must be non-empty and non-blank</li>
     *   <li>At least one valid method name must be provided</li>
     *   <li>Method names are resolved via the analyzer; invalid names are skipped with a warning</li>
     * </ul>
     * <p>
     * <b>Side Effects:</b>
     * <ul>
     *   <li>Adds code (method source) fragments to the current session's live context</li>
     *   <li>Emits a notification via IConsoleIO</li>
     *   <li>Pushes a new frozen context to history</li>
     * </ul>
     */
    void handlePostContextMethods(HttpExchange exchange) throws IOException {
        if (!ensureMethod(exchange, "POST")) {
            return;
        }

        try {
            var request = parseJsonOr400(exchange, AddContextMethodsRequest.class, "/v1/context/methods");
            if (request == null) {
                return;
            }

            if (request.methodNames().isEmpty()) {
                sendValidationError(exchange, "methodNames must not be empty");
                return;
            }

            var analyzer = contextManager.getAnalyzerUninterrupted();
            var validMethodNames = new ArrayList<String>();
            var invalidNames = new ArrayList<String>();

            for (var methodName : request.methodNames()) {
                if (methodName == null || methodName.isBlank()) {
                    invalidNames.add("(blank name)");
                    continue;
                }

                var trimmed = methodName.strip();

                var definitions = analyzer.getDefinitions(trimmed);
                if (definitions.isEmpty()) {
                    invalidNames.add(trimmed + " (not found in analyzer)");
                    continue;
                }

                var codeUnit = definitions.stream().filter(CodeUnit::isFunction).findFirst();
                if (codeUnit.isEmpty()) {
                    invalidNames.add(trimmed + " (not a method)");
                    continue;
                }

                validMethodNames.add(trimmed);
            }

            if (validMethodNames.isEmpty()) {
                var msg = "No valid method names provided";
                if (!invalidNames.isEmpty()) {
                    msg += "; invalid: " + String.join(", ", invalidNames);
                }
                sendValidationError(exchange, msg);
                return;
            }

            var addedMethods = new ArrayList<AddedContextMethod>();

            for (var methodName : validMethodNames) {
                var fragment = new ContextFragments.CodeFragment(contextManager, methodName);
                contextManager.addFragments(fragment);
                addedMethods.add(new AddedContextMethod(fragment.id(), methodName));
            }

            var response = new AddContextMethodsResponse(addedMethods);
            logger.info(
                    "Added {} methods to context (session={}): {}",
                    addedMethods.size(),
                    contextManager.getCurrentSessionId(),
                    addedMethods.stream().map(AddedContextMethod::methodName).collect(Collectors.joining(", ")));

            SimpleHttpServer.sendJsonResponse(exchange, 200, response);
        } catch (Exception e) {
            logger.error("Error handling POST /v1/context/methods", e);
            var error = ErrorPayload.internalError("Failed to add methods to context", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    void handlePostContextText(HttpExchange exchange) throws IOException {
        if (!ensureMethod(exchange, "POST")) {
            return;
        }

        try {
            var request = parseJsonOr400(exchange, AddContextTextRequest.class, "/v1/context/text");
            if (request == null) {
                return;
            }

            var text = request.text();
            if (text.isBlank()) {
                logger.info("Rejected pasted text: blank");
                sendValidationError(exchange, "text must not be blank");
                return;
            }

            final int MAX_BYTES = 1024 * 1024; // 1 MiB
            int byteLen = text.getBytes(UTF_8).length;
            if (byteLen > MAX_BYTES) {
                logger.info("Rejected pasted text: bytes={} exceeds limit", byteLen);
                sendValidationError(exchange, "text exceeds maximum size of " + MAX_BYTES + " bytes");
                return;
            }

            contextManager.addPastedTextFragment(text);

            var live = contextManager.liveContext();
            var fragments = live.getAllFragmentsInDisplayOrder();
            String fragmentId = "";
            for (int i = fragments.size() - 1; i >= 0; i--) {
                var f = fragments.get(i);
                if (f.getType() == ContextFragment.FragmentType.PASTE_TEXT) {
                    fragmentId = f.id();
                    break;
                }
            }

            var chars = text.length();
            logger.info("Added pasted text to context: chars={}", chars);

            var response = new AddContextTextResponse(fragmentId, chars);
            SimpleHttpServer.sendJsonResponse(exchange, 200, response);
        } catch (Exception e) {
            logger.error("Error handling POST /v1/context/text", e);
            var error = ErrorPayload.internalError("Failed to add text to context", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    public static void main(String[] args) {
        try {
            // Parse command-line arguments and validate them
            var parseResult = parseArgs(args);
            var parsedArgs = parseResult.args();
            var invalidKeys = parseResult.invalidKeys();

            // Log parsed arguments (with sensitive values redacted) early for debugging
            var redactedArgs = redactSensitiveArgs(parsedArgs);
            var argsDisplay = redactedArgs.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", "));
            logger.info("Parsed arguments: {}", argsDisplay);
            System.out.println("Parsed arguments: {" + argsDisplay + "}");

            // Check for help flag or invalid arguments
            if (parsedArgs.containsKey("help")) {
                printUsageAndExit(Set.of());
            }

            if (!invalidKeys.isEmpty()) {
                printUsageAndExit(invalidKeys);
            }

            // Get configuration from args or environment
            var execIdStr = getConfigValue(parsedArgs, "exec-id", "EXEC_ID");
            if (execIdStr == null || execIdStr.isBlank()) {
                throw new IllegalArgumentException(
                        "EXEC_ID must be provided via --exec-id argument or EXEC_ID environment variable");
            }
            var execId = UUID.fromString(execIdStr);

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

            var brokkApiKey = getConfigValue(parsedArgs, "brokk-api-key", "BROKK_API_KEY");

            var proxySettingStr = getConfigValue(parsedArgs, "proxy-setting", "PROXY_SETTING");
            @Nullable MainProject.LlmProxySetting proxySetting = null;
            if (proxySettingStr != null && !proxySettingStr.isBlank()) {
                try {
                    proxySetting = MainProject.LlmProxySetting.valueOf(proxySettingStr.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "Invalid proxy setting: '"
                                    + proxySettingStr
                                    + "'. Must be one of: BROKK, LOCALHOST, STAGING",
                            e);
                }
            }

            var workspaceDirStr = getConfigValue(parsedArgs, "workspace-dir", "WORKSPACE_DIR");
            if (workspaceDirStr == null || workspaceDirStr.isBlank()) {
                throw new IllegalArgumentException(
                        "WORKSPACE_DIR must be provided via --workspace-dir argument or WORKSPACE_DIR environment variable");
            }
            var workspaceDir = Path.of(workspaceDirStr);

            // Build ContextManager from workspace
            var project = new MainProject(workspaceDir);
            var contextManager = new ContextManager(project);

            // Set per-executor Brokk API key override if provided
            if (brokkApiKey != null && !brokkApiKey.isBlank()) {
                MainProject.setHeadlessBrokkApiKeyOverride(brokkApiKey);
                logger.info("Using executor-specific Brokk API key (length={})", brokkApiKey.length());
            }

            // Set per-executor proxy setting override if provided
            if (proxySetting != null) {
                MainProject.setHeadlessProxySettingOverride(proxySetting);
                logger.info("Using executor-specific proxy setting: {}", proxySetting);
            }

            var derivedSessionsDir = workspaceDir.resolve(".brokk").resolve("sessions");

            logger.info(
                    "Starting HeadlessExecutorMain with config: execId={}, listenAddr={}, workspaceDir={}, sessionsDir={}",
                    execId,
                    listenAddr,
                    workspaceDir,
                    derivedSessionsDir);

            // Print startup banner and config summary to console
            System.out.println();
            System.out.println("Brokk Headless Executor starting...");
            System.out.println("  execId:      " + execId);
            System.out.println("  listenAddr:  " + listenAddr);
            System.out.println("  workspaceDir: " + workspaceDir);
            System.out.println("  brokkApiKey:  "
                    + (brokkApiKey != null && !brokkApiKey.isBlank() ? "(provided)" : "(using global config)"));
            System.out.println(
                    "  proxySetting: " + (proxySetting != null ? proxySetting.name() : "(using global config)"));
            System.out.println();
            System.out.println("Available HTTP Endpoints:");
            System.out.println();
            System.out.println("  Unauthenticated (Health & Info):");
            System.out.println("    GET  /health/live       - executor liveness probe");
            System.out.println("    GET  /health/ready      - readiness probe (503 until session loaded)");
            System.out.println("    GET  /v1/executor       - executor info and protocol version");
            System.out.println();
            System.out.println("  Authenticated (require Authorization header):");
            System.out.println("    POST /v1/sessions                 - create a new session by name");
            System.out.println("    PUT  /v1/sessions                 - import/load a session from zip");
            System.out.println("    GET  /v1/sessions/{sessionId}     - download a session zip");
            System.out.println("    POST /v1/jobs                     - create and start a job");
            System.out.println("    GET  /v1/jobs/{jobId}             - get job status");
            System.out.println("    GET  /v1/jobs/{jobId}/events      - stream job execution events");
            System.out.println("    POST /v1/jobs/{jobId}/cancel      - cancel job execution");
            System.out.println("    GET  /v1/jobs/{jobId}/diff        - get git diff for job");
            System.out.println("    POST /v1/context/files            - add files to session context");
            System.out.println("    POST /v1/context/classes          - add class summaries to context");
            System.out.println("    POST /v1/context/methods          - add method sources to context");
            System.out.println("    POST /v1/context/text             - add pasted text to context");
            System.out.println();

            // Create and start executor
            var executor = new HeadlessExecutorMain(execId, listenAddr, authToken, contextManager);
            executor.start();

            // Print effective bind address and curl example after server starts
            var boundPort = executor.getPort();
            var listenParts = Splitter.on(':').splitToList(listenAddr);
            var boundHost = listenParts.get(0);
            System.out.println("Executor listening on http://" + boundHost + ":" + boundPort);
            System.out.println("Try: curl http://" + boundHost + ":" + boundPort + "/health/live");
            System.out.println();

            // Add shutdown hook
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                System.out.println();
                                System.out.println("Shutting down Brokk Headless Executor...");
                                logger.info("Shutdown signal received, stopping executor");
                                executor.stop(5);
                                System.out.println("Executor stopped.");
                            },
                            "HeadlessExecutor-ShutdownHook"));
            logger.info("HeadlessExecutorMain is running");
            Thread.currentThread().join(); // Keep the main thread alive
        } catch (InterruptedException e) {
            System.out.println("HeadlessExecutorMain interrupted: " + e.getMessage());
            logger.info("HeadlessExecutorMain interrupted", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.out.println("Fatal error starting HeadlessExecutorMain: " + e.getMessage());
            logger.error("Fatal error in HeadlessExecutorMain", e);
            System.exit(1);
        }
    }
}
