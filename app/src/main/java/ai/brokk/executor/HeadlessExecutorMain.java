package ai.brokk.executor;

import static java.util.Objects.requireNonNull;

import ai.brokk.BuildInfo;
import ai.brokk.ContextManager;
import ai.brokk.cli.HeadlessConsole;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.JobRunner;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.executor.routers.ActivityRouter;
import ai.brokk.executor.routers.AgentsRouter;
import ai.brokk.executor.routers.AuthRouter;
import ai.brokk.executor.routers.CompletionsRouter;
import ai.brokk.executor.routers.ContextRouter;
import ai.brokk.executor.routers.DependenciesRouter;
import ai.brokk.executor.routers.FavoritesRouter;
import ai.brokk.executor.routers.GitHubAuthRouter;
import ai.brokk.executor.routers.JobsRouter;
import ai.brokk.executor.routers.ModelConfigRouter;
import ai.brokk.executor.routers.ModelsRouter;
import ai.brokk.executor.routers.OpenAiAuthRouter;
import ai.brokk.executor.routers.RepoRouter;
import ai.brokk.executor.routers.ReviewRouter;
import ai.brokk.executor.routers.RouterUtil;
import ai.brokk.executor.routers.SessionsRouter;
import ai.brokk.executor.routers.SettingsRouter;
import ai.brokk.project.MainProject;
import ai.brokk.project.ModelProperties;
import com.google.common.base.Splitter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
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
import org.jetbrains.annotations.TestOnly;

public final class HeadlessExecutorMain {
    private static final Logger logger = LogManager.getLogger(HeadlessExecutorMain.class);

    // Valid argument keys that the application accepts
    private static final Set<String> VALID_ARGS = Set.of(
            "exec-id",
            "listen-addr",
            "auth-token",
            "workspace-dir",
            "brokk-api-key",
            "proxy-setting",
            "vendor",
            "exit-on-stdin-eof",
            "help");

    private final UUID execId;
    private final SimpleHttpServer server;
    private final ContextManager contextManager;
    private final JobStore jobStore;
    private final JobRunner jobRunner;
    private final JobReservation jobReservation = new JobReservation();
    private final Thread initThread;
    private final CompletableFuture<Void> headlessInit = new CompletableFuture<>();

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

    private static boolean parseBooleanValue(String rawValue, String sourceName) {
        var normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "", "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default ->
                throw new IllegalArgumentException("Invalid boolean value for " + sourceName + ": '" + rawValue
                        + "'. Expected one of true/false, 1/0, yes/no, on/off.");
        };
    }

    private static boolean getBooleanConfigValue(
            Map<String, String> parsedArgs, String argKey, String envVarName, boolean defaultValue) {
        if (parsedArgs.containsKey(argKey)) {
            return parseBooleanValue(parsedArgs.get(argKey), "--" + argKey);
        }

        var envValue = System.getenv(envVarName);
        if (envValue != null && !envValue.isBlank()) {
            return parseBooleanValue(envValue, envVarName);
        }

        return defaultValue;
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
        System.out.println(
                "  --vendor <vendor>          Other-models vendor: Default, Anthropic, Gemini, OpenAI, OpenAI - Codex (optional)");
        System.out.println(
                "  --exit-on-stdin-eof[=bool] Exit when stdin closes/errors (default: false; env EXIT_ON_STDIN_EOF)");
        System.out.println("  --help                     Show this help message");
        System.out.println();
        System.out.println("Arguments can also be provided via environment variables:");
        System.out.println(
                "  EXEC_ID, LISTEN_ADDR, AUTH_TOKEN, WORKSPACE_DIR, BROKK_API_KEY, PROXY_SETTING, EXIT_ON_STDIN_EOF");
        System.out.println();

        System.exit(invalidArgs.isEmpty() ? 0 : 1);
    }

    public HeadlessExecutorMain(UUID execId, String listenAddr, String authToken, ContextManager contextManager)
            throws IOException {
        // Headless executor can be constructed directly in tests/in-process callers, not only via main().
        // Set this early so any Swing/AWT calls route to headless toolkit and do not require X11 libraries.
        System.setProperty("java.awt.headless", "true");
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

        // Initialize JobStore
        this.jobStore = new JobStore(workspaceDir.resolve(".brokk"));

        // Initialize headless context asynchronously to avoid blocking constructor
        // Pass false to resume the last active session from workspace.properties
        // instead of always creating a new one (which would clobber the desktop app's session)
        this.initThread = new Thread(
                () -> {
                    try {
                        this.contextManager.createHeadless(false, new HeadlessConsole());
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

        var sessionsRouter = new SessionsRouter(
                this.contextManager, this.contextManager.getProject().getSessionManager());
        this.server.registerAuthenticatedContext("/v1/sessions", sessionsRouter);

        this.jobRunner = new JobRunner(this.contextManager, this.jobStore);
        var agentStore = this.contextManager.getAgentStore();
        var jobsRouter = new JobsRouter(
                this.contextManager, this.jobStore, this.jobRunner, this.jobReservation, this.headlessInit, agentStore);
        this.server.registerAuthenticatedContext("/v1/jobs", jobsRouter);

        var agentsRouter = new AgentsRouter(agentStore);
        this.server.registerAuthenticatedContext("/v1/agents", agentsRouter);

        var contextRouter = new ContextRouter(this.contextManager);
        this.server.registerAuthenticatedContext("/v1/context", contextRouter);
        this.server.registerAuthenticatedContext("/v1/tasklist", contextRouter);
        this.server.registerAuthenticatedContext("/v1/session/costs", contextRouter);

        var repoRouter = new RepoRouter(this.contextManager);
        this.server.registerAuthenticatedContext("/v1/repo", repoRouter);

        var modelsRouter = new ModelsRouter(this.contextManager);
        this.server.registerAuthenticatedContext("/v1/models", modelsRouter);

        var authRouter = new AuthRouter(this.contextManager);
        this.server.registerAuthenticatedContext("/v1/auth", authRouter);

        var modelConfigRouter = new ModelConfigRouter(this.contextManager);
        this.server.registerAuthenticatedContext("/v1/model-config", modelConfigRouter);

        var activityRouter = new ActivityRouter(this.contextManager);
        this.server.registerAuthenticatedContext("/v1/activity", activityRouter);

        var completionsRouter = new CompletionsRouter(this.contextManager);
        this.server.registerAuthenticatedContext("/v1/completions", completionsRouter);

        var favoritesRouter = new FavoritesRouter();
        this.server.registerAuthenticatedContext("/v1/favorites", favoritesRouter);

        var openAiAuthRouter = new OpenAiAuthRouter();
        this.server.registerAuthenticatedContext("/v1/openai/oauth", openAiAuthRouter);

        var gitHubAuthRouter = new GitHubAuthRouter(this.contextManager);
        this.server.registerAuthenticatedContext("/v1/github/oauth", gitHubAuthRouter);

        var reviewRouter = new ReviewRouter(this.jobStore, this.jobRunner, this.jobReservation, this.headlessInit);
        this.server.registerAuthenticatedContext("/v1/review", reviewRouter);

        var dependenciesRouter = new DependenciesRouter(this.contextManager);
        this.server.registerAuthenticatedContext("/v1/dependencies", dependenciesRouter);

        var settingsRouter = new SettingsRouter(this.contextManager);
        this.server.registerAuthenticatedContext("/v1/settings", settingsRouter);

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
     * Deprecated compatibility endpoint.
     *
     * <p>/health/ready now mirrors process liveness and always returns HTTP 200 when the server is
     * up. It remains temporarily to keep older clients working while they migrate to /health/live.
     */
    private void handleHealthReady(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "GET")) {
            return;
        }
        exchange.getResponseHeaders().add("Deprecation", "true");
        exchange.getResponseHeaders().add("Warning", "299 - \"Deprecated endpoint: use /health/live\"");

        var response = new HashMap<String, Object>();
        response.put("status", "ready");
        response.put("sessionId", resolveReadySessionId());
        response.put("execId", this.execId.toString());
        response.put("version", BuildInfo.version);
        response.put("protocolVersion", 1);
        logger.info("/health/ready served as deprecated liveness alias");
        SimpleHttpServer.sendJsonResponse(exchange, response);
    }

    private @Nullable String resolveReadySessionId() {
        try {
            var sessions = contextManager.getProject().getSessionManager().listSessions();
            if (sessions.isEmpty()) {
                return null;
            }

            var currentSessionId = contextManager.getCurrentSessionId();
            var hasKnownActiveSession =
                    sessions.stream().anyMatch(session -> session.id().equals(currentSessionId));
            if (hasKnownActiveSession) {
                return currentSessionId.toString();
            }

            // Headless startup can still be reconciling the active session while session metadata
            // already exists on disk. Report the newest known session instead of a transient null.
            return sessions.getFirst().id().toString();
        } catch (Exception e) {
            logger.warn("Failed to resolve sessionId for /health/ready payload", e);
            return null;
        }
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

        this.jobRunner.shutdown();

        try {
            this.contextManager.close();
        } catch (Exception e) {
            logger.warn("Error closing ContextManager", e);
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

    @TestOnly
    static String formatFatalStartupError(Throwable throwable) {
        var rootCause = throwable;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }

        var details = new StringBuilder("Fatal error starting HeadlessExecutorMain");
        if (throwable.getMessage() != null && !throwable.getMessage().isBlank()) {
            details.append(": ").append(throwable.getMessage());
        }
        if (rootCause != throwable) {
            details.append(System.lineSeparator())
                    .append("Root cause: ")
                    .append(rootCause.getClass().getSimpleName());
            if (rootCause.getMessage() != null && !rootCause.getMessage().isBlank()) {
                details.append(": ").append(rootCause.getMessage());
            }
        }

        details.append(System.lineSeparator()).append("Exception chain:");
        var seen = new HashSet<Throwable>();
        var current = throwable;
        while (current != null && seen.add(current)) {
            details.append(System.lineSeparator())
                    .append("  - ")
                    .append(current.getClass().getName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                details.append(": ").append(current.getMessage());
            }
            current = current.getCause();
        }

        details.append(System.lineSeparator()).append("Stack trace:");
        var sw = new StringWriter();
        try (var pw = new PrintWriter(sw)) {
            throwable.printStackTrace(pw);
        }
        details.append(System.lineSeparator()).append(sw);
        return details.toString();
    }

    public static void main(String[] args) {
        try {
            // Must be set before any Swing/AWT code paths are touched.
            System.setProperty("java.awt.headless", "true");
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

            var exitOnStdinEof = getBooleanConfigValue(parsedArgs, "exit-on-stdin-eof", "EXIT_ON_STDIN_EOF", false);

            // Build ContextManager from workspace
            var project = new MainProject(workspaceDir);

            // Apply vendor preference and role mappings (if requested)
            String vendorArg = parsedArgs.get("vendor");
            if (vendorArg != null && !vendorArg.isBlank()) {
                String requestedVendor = vendorArg.trim();
                String canonicalVendor;
                if (ModelProperties.DEFAULT_VENDOR.equalsIgnoreCase(requestedVendor)) {
                    canonicalVendor = ModelProperties.DEFAULT_VENDOR;
                } else {
                    canonicalVendor = ModelProperties.getAvailableVendors().stream()
                            .filter(v -> v.equalsIgnoreCase(requestedVendor))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Invalid vendor: '" + requestedVendor + "'. Must be one of: "
                                            + ModelProperties.DEFAULT_VENDOR + ", "
                                            + String.join(", ", ModelProperties.getAvailableVendors())));
                }

                if (ModelProperties.DEFAULT_VENDOR.equals(canonicalVendor)) {
                    for (ModelProperties.ModelType type : ModelProperties.ModelType.values()) {
                        if (type != ModelProperties.ModelType.CODE && type != ModelProperties.ModelType.ARCHITECT) {
                            project.removeModelConfig(type);
                        }
                    }
                    MainProject.setOtherModelsVendorPreference("");
                    logger.info("Cleared other-models vendor preference and internal role overrides");
                } else {
                    if ("OpenAI - Codex".equals(canonicalVendor) && !MainProject.isOpenAiCodexOauthConnected()) {
                        throw new IllegalArgumentException(
                                "OpenAI - Codex selected but Codex OAuth is not connected; connect/login first.");
                    }
                    var vendorModels = requireNonNull(
                            ModelProperties.getVendorModels(canonicalVendor),
                            "Vendor models unexpectedly null for " + canonicalVendor);
                    vendorModels.forEach(project::setModelConfig);
                    MainProject.setOtherModelsVendorPreference(canonicalVendor);
                    logger.info("Applied other-models vendor preference: {}", canonicalVendor);
                }
            }

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
            if (vendorArg != null && !vendorArg.isBlank()) {
                System.out.println("  vendor:      " + vendorArg.trim());
            }
            System.out.println();
            System.out.println("  exitOnStdinEof: " + exitOnStdinEof);
            System.out.println();
            System.out.println("Available HTTP Endpoints:");
            System.out.println();
            System.out.println("  Unauthenticated (Health & Info):");
            System.out.println("    GET  /health/live       - executor liveness probe");
            System.out.println("    GET  /health/ready      - deprecated alias of /health/live");
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
            System.out.println("    POST /v1/repo/commit              - commit current changes");
            System.out.println("    GET  /v1/completions              - file and symbol completions");
            System.out.println("    GET  /v1/favorites                - user's favorite model configs");
            System.out.println("    POST /v1/review/submit            - submit guided code review job");
            System.out.println("    GET  /v1/model-config             - current CODE/ARCHITECT model configs");
            System.out.println("    POST /v1/model-config             - update CODE/ARCHITECT model config");
            System.out.println("    GET  /v1/dependencies             - list all imported dependencies");
            System.out.println("    GET  /v1/settings                 - get all project settings");
            System.out.println("    POST /v1/settings                 - update all project settings");
            System.out.println("    GET  /v1/agents                   - list custom agent definitions");
            System.out.println("    POST /v1/agents                   - create a custom agent");
            System.out.println("    GET  /v1/agents/{name}            - get an agent definition");
            System.out.println("    PUT  /v1/agents/{name}            - update an agent definition");
            System.out.println("    DELETE /v1/agents/{name}          - delete an agent definition");
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

            if (exitOnStdinEof) {
                // Monitor stdin for EOF / parent-death signal.
                // This thread blocks on System.in.read() and will initiate a controlled shutdown
                // if EOF is observed or an IOException occurs. It is a daemon so it won't
                // prevent JVM shutdown if other non-daemon threads remain.
                Thread stdinMonitor = new Thread(
                        () -> {
                            try {
                                // Read until EOF (-1) or exception. We don't process the bytes; we only
                                // treat EOF as a signal that the parent has gone away.
                                int read;
                                while ((read = System.in.read()) != -1) {
                                    // Consume bytes without processing. If stdin is a terminal, this will
                                    // block until user input / EOF and thus not interfere with normal usage.
                                }
                                logger.info("System.in closed (EOF detected). Initiating controlled shutdown.");
                            } catch (IOException e) {
                                logger.info(
                                        "IOException while monitoring System.in; initiating controlled shutdown.", e);
                            } catch (Throwable t) {
                                logger.warn(
                                        "Unexpected error in System.in monitor; initiating controlled shutdown.", t);
                            } finally {
                                try {
                                    // Try to stop executor gracefully; use a short delay to speed shutdown.
                                    executor.stop(5);
                                } catch (Exception e) {
                                    logger.warn("Error while stopping executor from stdin monitor", e);
                                } finally {
                                    // Ensure process exits even if shutdown hook doesn't run immediately.
                                    System.exit(0);
                                }
                            }
                        },
                        "HeadlessExecutor-StdInMonitor");
                stdinMonitor.setDaemon(true);
                stdinMonitor.start();
                logger.info("System.in EOF monitor enabled; process will exit when stdin closes or errors.");
            } else {
                logger.info("System.in EOF monitor disabled; executor will ignore stdin closure.");
            }

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
            var formattedError = formatFatalStartupError(e);
            System.err.println(formattedError);
            System.out.println(formattedError);
            logger.error("Fatal error in HeadlessExecutorMain", e);
            System.exit(1);
        }
    }
}
