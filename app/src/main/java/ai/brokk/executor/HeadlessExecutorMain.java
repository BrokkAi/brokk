package ai.brokk.executor;

import ai.brokk.BuildInfo;
import ai.brokk.ContextManager;
import ai.brokk.SessionManager;
import ai.brokk.agents.BuildAgent;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.executor.jobs.JobRunner;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.executor.routers.ContextRouter;
import ai.brokk.executor.routers.JobsRouter;
import ai.brokk.executor.routers.ModelsRouter;
import ai.brokk.executor.routers.RouterUtil;
import ai.brokk.executor.routers.SessionsRouter;
import ai.brokk.project.MainProject;
import com.google.common.base.Splitter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    private final Thread initThread;
    private final CompletableFuture<Void> headlessInit = new CompletableFuture<>();
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
        this.jobStore = new JobStore(workspaceDir.resolve(".brokk"));
        this.sessionManager = new SessionManager(sessionsDir);

        // Initialize headless context asynchronously to avoid blocking constructor
        // Pass false to resume the last active session from workspace.properties
        // instead of always creating a new one (which would clobber the desktop app's session)
        this.initThread = new Thread(
                () -> {
                    try {
                        this.contextManager.createHeadless(BuildAgent.BuildDetails.EMPTY, false);
                        headlessInit.complete(null);
                        logger.info("ContextManager headless initialization complete");
                    } catch (Exception e) {
                        headlessInit.completeExceptionally(e);
                        logger.warn("ContextManager headless initialization failed", e);
                    }
                },
                "ContextManager-Init");
        this.initThread.setDaemon(true);
        this.initThread.start();

        // Create HTTP server with authentication
        this.server = new SimpleHttpServer(host, port, authToken, 4);

        // Register endpoints
        this.server.registerUnauthenticatedContext("/health/live", this::handleHealthLive);
        this.server.registerUnauthenticatedContext("/health/ready", this::handleHealthReady);
        this.server.registerUnauthenticatedContext("/v1/executor", this::handleExecutor);

        var sessionsRouter =
                new SessionsRouter(this.contextManager, this.sessionManager, val -> this.sessionLoaded = val);
        this.server.registerAuthenticatedContext("/v1/sessions", sessionsRouter);

        var jobsRouter = new JobsRouter(
                this.contextManager,
                this.jobStore,
                new JobRunner(this.contextManager, this.jobStore),
                this.jobReservation,
                this.headlessInit);
        this.server.registerAuthenticatedContext("/v1/jobs", jobsRouter);

        var contextRouter = new ContextRouter(this.contextManager);
        this.server.registerAuthenticatedContext("/v1/context", contextRouter);
        this.server.registerAuthenticatedContext("/v1/tasklist", contextRouter);

        var modelsRouter = new ModelsRouter(this.contextManager);
        this.server.registerAuthenticatedContext("/v1/models", modelsRouter);

        logger.info("HeadlessExecutorMain initialized successfully");
    }

    private void handleHealthLive(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "GET")) {
            return;
        }
        var response = Map.of("execId", this.execId.toString(), "version", BuildInfo.version, "protocolVersion", 1);
        SimpleHttpServer.sendJsonResponse(exchange, response);
    }

    /**
     * Health readiness endpoint.
     *
     * Note for maintainers: this endpoint signals that the headless executor has a session loaded
     * at the time the request is served. The "sessionId" returned here is whatever the
     * ContextManager reports as its current session when the request is handled. It is intentionally
     * a snapshot of the current state and does NOT guarantee that this is the same session id that
     * was most recently created or imported by a caller. The ContextManager may asynchronously
     * quarantine, migrate, or replace sessions (for example, during import, validation, or
     * compatibility migration), so the active session id can change as background tasks complete.
     *
     * Keep the handler behavior unchanged: it returns 503 if we have not yet loaded any session,
     * otherwise it returns the ContextManager's current session id at request time.
     */
    private void handleHealthReady(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "GET")) {
            return;
        }

        if (!sessionLoaded) {
            // sessionLoaded is a local flag driven by session lifecycle events (e.g. SessionsRouter).
            // A false value means no session has been marked as loaded for this headless executor yet,
            // so we return 503 NOT_READY.
            logger.info("/health/ready requested before any session is marked loaded; returning 503");
            var error = ErrorPayload.of("NOT_READY", "No session loaded");
            SimpleHttpServer.sendJsonResponse(exchange, 503, error);
            return;
        }

        // Query the ContextManager for the session that is current at the instant of this request.
        // This value may differ from session ids returned by recent create/import operations because
        // ContextManager performs asynchronous work (quarantine, migration, etc.) that can change
        // the active session after those operations complete.
        var sessionId = contextManager.getCurrentSessionId();

        // Log readiness along with the concrete current session id to make it clear in the logs which session
        // satisfied the readiness check. Tests and callers should not rely on this id matching any
        // previously-created/imported id unless they explicitly verify it themselves.
        logger.info("/health/ready served; current sessionId={}", sessionId);

        var response = Map.of("status", "ready", "sessionId", String.valueOf(sessionId));
        SimpleHttpServer.sendJsonResponse(exchange, response);
    }

    private void handleExecutor(HttpExchange exchange) throws IOException {
        if (RouterUtil.ensureMethod(exchange, "GET")) {
            var response = Map.of("execId", this.execId.toString(), "version", BuildInfo.version, "protocolVersion", 1);
            SimpleHttpServer.sendJsonResponse(exchange, response);
        }
    }

    public void start() {
        this.server.start();
        logger.info(
                "HeadlessExecutorMain HTTP server started on endpoints: /health/live, /v1/sessions, /v1/jobs, etc.; cmSession={}",
                contextManager.getCurrentSessionId());
    }

    public void stop(int delaySeconds) {
        // Interrupt and wait for init thread to prevent resource leak
        initThread.interrupt();
        try {
            initThread.join(5000); // Wait up to 5 seconds
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

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
     * Test hook: allows tests in the same package to force the sessionLoaded flag.
     *
     * This is intentionally package-private and annotated @TestOnly to avoid use in
     * production code. It enables tests to simulate the transient condition where the
     * executor has observed a session lifecycle event (sessionLoaded==true) but the
     * ContextManager currently reports no active session id.
     */
    @org.jetbrains.annotations.TestOnly
    void setSessionLoadedForTests(boolean value) {
        this.sessionLoaded = value;
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
            System.out.println("    POST /v1/jobs/pr-review           - create a PR review job");
            System.out.println("    GET  /v1/jobs/{jobId}             - get job status");
            System.out.println("    GET  /v1/jobs/{jobId}/events      - stream job execution events");
            System.out.println("    POST /v1/jobs/{jobId}/cancel      - cancel job execution");
            System.out.println("    GET  /v1/jobs/{jobId}/diff        - get git diff for job");
            System.out.println("    GET  /v1/context                  - get current context state");
            System.out.println("    POST /v1/context/drop             - drop fragments by ID");
            System.out.println("    POST /v1/context/pin              - toggle fragment pin status");
            System.out.println("    POST /v1/context/readonly         - toggle fragment readonly status");
            System.out.println("    POST /v1/context/compress-history - compress conversation history");
            System.out.println("    POST /v1/context/clear-history    - clear conversation history");
            System.out.println("    POST /v1/context/drop-all         - drop all context");
            System.out.println("    POST /v1/context/files            - add files to session context");
            System.out.println("    POST /v1/context/classes          - add class summaries to context");
            System.out.println("    POST /v1/context/methods          - add method sources to context");
            System.out.println("    POST /v1/context/text             - add pasted text to context");
            System.out.println("    GET  /v1/tasklist                 - get current task list content");
            System.out.println("    POST /v1/tasklist                 - replace current task list content");
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
