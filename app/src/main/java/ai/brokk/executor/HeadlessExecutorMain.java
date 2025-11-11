package ai.brokk.executor;

import static java.nio.charset.StandardCharsets.UTF_8;

import ai.brokk.BuildInfo;
import ai.brokk.ContextManager;
import ai.brokk.MainProject;
import ai.brokk.SessionManager;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.executor.jobs.JobStore;
import com.google.common.base.Splitter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class HeadlessExecutorMain {
    private static final Logger logger = LogManager.getLogger(HeadlessExecutorMain.class);

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

    /*
     * Parse command-line arguments into a map of normalized keys to values.
     * Supports both --key value and --key=value forms.
     * Normalized keys: exec-id, listen-addr, auth-token, workspace-dir, sessions-dir.
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

                // Normalize the key
                result.put(key, value);
            }
        }
        return result;
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
        // - POST /v1/sessions  (create a new session by name)
        // - PUT  /v1/sessions  (import/load an existing session from a zip)
        this.server.registerAuthenticatedContext("/v1/sessions", this::handleSessionsRouter);
        this.server.registerAuthenticatedContext("/v1/jobs", this::handleJobsRouter);

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
     * Delegates to JobRunner and manages currentJobId lifecycle.
     */
    private void executeJobAsync(String jobId, ai.brokk.executor.jobs.JobSpec jobSpec) {
        logger.info("Starting job execution: {}, session={}", jobId, contextManager.getCurrentSessionId());
        jobRunner.runAsync(jobId, jobSpec).whenComplete((unused, throwable) -> {
            if (throwable != null) {
                logger.error(
                        "Job {} execution failed (session={})", jobId, contextManager.getCurrentSessionId(), throwable);
            } else {
                logger.info("Job {} execution finished (session={})", jobId, contextManager.getCurrentSessionId());
            }
            // Release reservation only if we still own it; CAS avoids clearing another job's reservation.
            jobReservation.releaseIfOwner(jobId);
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
     */
    void handleSessionsRouter(HttpExchange exchange) throws IOException {
        var method = exchange.getRequestMethod();
        if (method.equals("POST")) {
            handleCreateSession(exchange);
            return;
        }
        if (method.equals("PUT")) {
            handlePutSession(exchange);
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
     * Import a session zip file by writing it to sessionsDir and switching ContextManager to it.
     * This helper encapsulates the core session import logic for reusability.
     *
     * @param zipData the zip file contents
     * @param sessionId the UUID for this session
     * @throws IOException if writing the zip file fails
     * @throws Exception if switching the session fails
     */
    void importSessionZip(byte[] zipData, UUID sessionId) throws Exception {
        // Write zip file to the directory ContextManager/SessionManager expect: <workspace>/.brokk/sessions
        var cmSessionsDir =
                contextManager.getProject().getRoot().resolve(".brokk").resolve("sessions");
        Files.createDirectories(cmSessionsDir);
        var sessionZipPath = cmSessionsDir.resolve(sessionId + ".zip");
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
            Map<String, String> safeTags = tags != null ? Map.copyOf(tags) : Map.of();
            var jobSpec = ai.brokk.executor.jobs.JobSpec.of(
                    jobSpecRequest.taskInput(),
                    jobSpecRequest.autoCommit(),
                    jobSpecRequest.autoCompress(),
                    plannerModel,
                    jobSpecRequest.codeModel(),
                    safeTags);

            // Create or get job (idempotent)
            var createResult = jobStore.createOrGetJob(idempotencyKey, jobSpec);
            var jobId = createResult.jobId();
            var isNewJob = createResult.isNewJob();

            logger.info(
                    "Job {}: isNewJob={}, jobId={}, requestedSessionId={}",
                    idempotencyKey,
                    isNewJob,
                    jobId,
                    jobSpecRequest.sessionId());

            // Load job status
            var status = jobStore.loadStatus(jobId);
            var state = status != null ? status.state() : "queued";

            var response = Map.of(
                    "jobId", jobId,
                    "state", state);

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
                    // Start execution asynchronously; release reservation in callback or on failure
                    executeJobAsync(jobId, jobSpec);
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

    private record CreateSessionRequest(String name) {}

    private record JobSpecRequest(
            String sessionId,
            String taskInput,
            boolean autoCommit,
            boolean autoCompress,
            @Nullable String plannerModel,
            @Nullable String codeModel,
            @Nullable Map<String, String> tags) {}

    public static void main(String[] args) {
        try {
            // Parse command-line arguments
            var parsedArgs = parseArgs(args);

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

            var workspaceDirStr = getConfigValue(parsedArgs, "workspace-dir", "WORKSPACE_DIR");
            if (workspaceDirStr == null || workspaceDirStr.isBlank()) {
                throw new IllegalArgumentException(
                        "WORKSPACE_DIR must be provided via --workspace-dir argument or WORKSPACE_DIR environment variable");
            }
            var workspaceDir = Path.of(workspaceDirStr);

            // Build ContextManager from workspace
            var project = new MainProject(workspaceDir);
            var contextManager = new ContextManager(project);

            var derivedSessionsDir = workspaceDir.resolve(".brokk").resolve("sessions");

            logger.info(
                    "Starting HeadlessExecutorMain with config: execId={}, listenAddr={}, workspaceDir={}, sessionsDir={}",
                    execId,
                    listenAddr,
                    workspaceDir,
                    derivedSessionsDir);

            // Create and start executor
            var executor = new HeadlessExecutorMain(execId, listenAddr, authToken, contextManager);
            executor.start();

            // Add shutdown hook
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                logger.info("Shutdown signal received, stopping executor");
                                executor.stop(5);
                            },
                            "HeadlessExecutor-ShutdownHook"));

            logger.info("HeadlessExecutorMain is running");
            Thread.currentThread().join(); // Keep the main thread alive
        } catch (InterruptedException e) {
            logger.info("HeadlessExecutorMain interrupted", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Fatal error in HeadlessExecutorMain", e);
            System.exit(1);
        }
    }
}
