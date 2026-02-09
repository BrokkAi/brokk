package ai.brokk.executor;

import static java.nio.charset.StandardCharsets.UTF_8;

import ai.brokk.BuildInfo;
import ai.brokk.ContextManager;
import ai.brokk.SessionManager;
import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.SpecialTextType;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.executor.routers.RouterUtil;
import ai.brokk.executor.routers.SessionsRouter;
import ai.brokk.project.MainProject;
import ai.brokk.util.Messages;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Valid argument keys that the application accepts
    private static final Set<String> VALID_ARGS =
            Set.of("exec-id", "listen-addr", "auth-token", "workspace-dir", "brokk-api-key", "proxy-setting", "help");

    private static final ai.brokk.AbstractService.ReasoningLevel[] REASONING_LEVEL_VALUES =
            ai.brokk.AbstractService.ReasoningLevel.values();

    private static final Set<String> ALLOWED_REASONING_LEVELS =
            Arrays.stream(REASONING_LEVEL_VALUES).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private static final String ALLOWED_REASONING_LEVELS_LIST =
            Arrays.stream(REASONING_LEVEL_VALUES).map(Enum::name).collect(Collectors.joining(", "));

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
        this.jobStore = new JobStore(workspaceDir.resolve(".brokk").resolve("jobs"));
        this.sessionManager = new SessionManager(sessionsDir);

        // Initialize headless context asynchronously to avoid blocking constructor
        // Pass false to resume the last active session from workspace.properties
        // instead of always creating a new one (which would clobber the desktop app's session)
        this.initThread = new Thread(
                () -> {
                    try {
                        this.contextManager.createHeadless(BuildAgent.BuildDetails.EMPTY, false);
                        sessionLoaded = true;
                        headlessInit.complete(null);
                        logger.info("ContextManager headless initialization complete, session loaded");
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
        // Sessions router handles:
        // - POST /v1/sessions                 (create a new session by name)
        // - PUT  /v1/sessions                 (import/load an existing session from a zip)
        // - GET  /v1/sessions/{sessionId}     (download a session zip)
        var sessionsRouter =
                new SessionsRouter(this.contextManager, this.sessionManager, val -> this.sessionLoaded = val);
        this.server.registerAuthenticatedContext("/v1/sessions", sessionsRouter);

        var jobsRouter = new ai.brokk.executor.routers.JobsRouter(
                this.contextManager,
                this.jobStore,
                new ai.brokk.executor.jobs.JobRunner(this.contextManager, this.jobStore),
                this.jobReservation,
                this.headlessInit);
        this.server.registerAuthenticatedContext("/v1/jobs", jobsRouter);

        this.server.registerAuthenticatedContext("/v1/context", this::handleGetContext);
        this.server.registerAuthenticatedContext("/v1/context/drop", this::handlePostContextDrop);
        this.server.registerAuthenticatedContext("/v1/context/pin", this::handlePostContextPin);
        this.server.registerAuthenticatedContext("/v1/context/readonly", this::handlePostContextReadonly);
        this.server.registerAuthenticatedContext("/v1/context/compress-history", this::handlePostCompressHistory);
        this.server.registerAuthenticatedContext("/v1/context/clear-history", this::handlePostClearHistory);
        this.server.registerAuthenticatedContext("/v1/context/drop-all", this::handlePostDropAll);
        this.server.registerAuthenticatedContext("/v1/context/files", this::handlePostContextFiles);
        this.server.registerAuthenticatedContext("/v1/context/classes", this::handlePostContextClasses);
        this.server.registerAuthenticatedContext("/v1/context/methods", this::handlePostContextMethods);
        this.server.registerAuthenticatedContext("/v1/context/text", this::handlePostContextText);

        logger.info("HeadlessExecutorMain initialized successfully");
    }

    private void handleHealthLive(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "GET")) {
            return;
        }

        var response = Map.of("execId", this.execId.toString(), "version", BuildInfo.version, "protocolVersion", 1);

        SimpleHttpServer.sendJsonResponse(exchange, response);
    }

    private void handleHealthReady(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "GET")) {
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
        if (!RouterUtil.ensureMethod(exchange, "GET")) {
            return;
        }

        var response = Map.of("execId", this.execId.toString(), "version", BuildInfo.version, "protocolVersion", 1);

        SimpleHttpServer.sendJsonResponse(exchange, response);
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

    // ============================================================================
    // Context Query and Mutation Endpoints
    // ============================================================================

    /**
     * Classify a fragment into a ChipKind string, replicating the logic from ChipColorUtils.classify().
     */
    private static String classifyChipKind(ContextFragment fragment) {
        if (fragment.getType() == ContextFragment.FragmentType.SKELETON) {
            return "SUMMARY";
        }
        if (!fragment.isValid()) {
            return "INVALID";
        }
        if (fragment.getType().isEditable()) {
            return "EDIT";
        }
        if (fragment.getType() == ContextFragment.FragmentType.HISTORY) {
            return "HISTORY";
        }
        if (fragment instanceof ContextFragments.StringFragment sf
                && SpecialTextType.TASK_LIST
                        .description()
                        .equals(sf.description().renderNowOrNull())) {
            return "TASK_LIST";
        }
        return "OTHER";
    }

    /**
     * Estimate the token count for a fragment using the same approach as the GUI.
     */
    private static int estimateFragmentTokens(ContextFragment f) {
        try {
            if (f.isText() || f.getType().isOutput()) {
                var text = f.text().renderNowOr("");
                if (!text.isBlank()) {
                    return Messages.getApproximateTokens(text);
                }
            }
        } catch (Exception e) {
            // Silently return 0 if token estimation fails
        }
        return 0;
    }

    /**
     * GET /v1/context - Returns the current context state including all fragments
     * with their chip classification, token counts, and pin/readonly status.
     */
    void handleGetContext(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "GET")) {
            return;
        }

        try {
            var live = contextManager.liveContext();
            var fragments = live.getAllFragmentsInDisplayOrder();

            var fragmentList = new ArrayList<Map<String, Object>>();
            int totalUsedTokens = 0;

            for (var fragment : fragments) {
                var map = new HashMap<String, Object>();
                map.put("id", fragment.id());
                map.put("type", fragment.getType().name());
                map.put("shortDescription", fragment.shortDescription().renderNowOr(""));
                map.put("chipKind", classifyChipKind(fragment));
                map.put("pinned", live.isPinned(fragment));
                map.put("readonly", live.isMarkedReadonly(fragment));
                map.put("valid", fragment.isValid());
                map.put("editable", fragment.getType().isEditable());

                int tokens = estimateFragmentTokens(fragment);
                map.put("tokens", tokens);
                totalUsedTokens += tokens;

                fragmentList.add(map);
            }

            // Use 200K as a reasonable default max; a more precise value would require
            // knowing the selected model's context window.
            int maxTokens = 200_000;

            var response = Map.of(
                    "fragments", fragmentList,
                    "usedTokens", totalUsedTokens,
                    "maxTokens", maxTokens);

            SimpleHttpServer.sendJsonResponse(exchange, response);
        } catch (Exception e) {
            logger.error("Error handling GET /v1/context", e);
            var error = ErrorPayload.internalError("Failed to retrieve context", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    private record DropFragmentsRequest(List<String> fragmentIds) {}

    /**
     * POST /v1/context/drop - Drop fragments by ID.
     */
    void handlePostContextDrop(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }

        try {
            var request = RouterUtil.parseJsonOr400(exchange, DropFragmentsRequest.class, "/v1/context/drop");
            if (request == null) {
                return;
            }

            if (request.fragmentIds() == null || request.fragmentIds().isEmpty()) {
                RouterUtil.sendValidationError(exchange, "fragmentIds must not be empty");
                return;
            }

            var idSet = new HashSet<>(request.fragmentIds());
            var live = contextManager.liveContext();
            var toDrop = live.allFragments().filter(f -> idSet.contains(f.id())).collect(Collectors.toList());

            if (toDrop.isEmpty()) {
                RouterUtil.sendValidationError(exchange, "No matching fragments found for the given IDs");
                return;
            }

            contextManager.drop(toDrop);
            logger.info("Dropped {} fragments (session={})", toDrop.size(), contextManager.getCurrentSessionId());

            var response = Map.of("dropped", toDrop.size());
            SimpleHttpServer.sendJsonResponse(exchange, response);
        } catch (Exception e) {
            logger.error("Error handling POST /v1/context/drop", e);
            var error = ErrorPayload.internalError("Failed to drop fragments", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    private record PinFragmentRequest(String fragmentId, boolean pinned) {}

    /**
     * POST /v1/context/pin - Toggle pin status of a fragment.
     */
    void handlePostContextPin(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }

        try {
            var request = RouterUtil.parseJsonOr400(exchange, PinFragmentRequest.class, "/v1/context/pin");
            if (request == null) {
                return;
            }

            if (request.fragmentId() == null || request.fragmentId().isBlank()) {
                RouterUtil.sendValidationError(exchange, "fragmentId is required");
                return;
            }

            var live = contextManager.liveContext();
            var fragment = live.allFragments()
                    .filter(f -> f.id().equals(request.fragmentId()))
                    .findFirst()
                    .orElse(null);

            if (fragment == null) {
                RouterUtil.sendValidationError(exchange, "Fragment not found: " + request.fragmentId());
                return;
            }

            contextManager.pushContext(ctx -> ctx.withPinned(fragment, request.pinned()));
            logger.info(
                    "Set pinned={} for fragment {} (session={})",
                    request.pinned(),
                    request.fragmentId(),
                    contextManager.getCurrentSessionId());

            var response = Map.of("fragmentId", request.fragmentId(), "pinned", request.pinned());
            SimpleHttpServer.sendJsonResponse(exchange, response);
        } catch (Exception e) {
            logger.error("Error handling POST /v1/context/pin", e);
            var error = ErrorPayload.internalError("Failed to toggle pin", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    private record ReadonlyFragmentRequest(String fragmentId, boolean readonly) {}

    /**
     * POST /v1/context/readonly - Toggle readonly status of a fragment.
     */
    void handlePostContextReadonly(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }

        try {
            var request = RouterUtil.parseJsonOr400(exchange, ReadonlyFragmentRequest.class, "/v1/context/readonly");
            if (request == null) {
                return;
            }

            if (request.fragmentId() == null || request.fragmentId().isBlank()) {
                RouterUtil.sendValidationError(exchange, "fragmentId is required");
                return;
            }

            var live = contextManager.liveContext();
            var fragment = live.allFragments()
                    .filter(f -> f.id().equals(request.fragmentId()))
                    .findFirst()
                    .orElse(null);

            if (fragment == null) {
                RouterUtil.sendValidationError(exchange, "Fragment not found: " + request.fragmentId());
                return;
            }

            if (!fragment.getType().isEditable()) {
                RouterUtil.sendValidationError(exchange, "Fragment is not editable and cannot be marked readonly");
                return;
            }

            contextManager.pushContext(ctx -> ctx.setReadonly(fragment, request.readonly()));
            logger.info(
                    "Set readonly={} for fragment {} (session={})",
                    request.readonly(),
                    request.fragmentId(),
                    contextManager.getCurrentSessionId());

            var response = Map.of("fragmentId", request.fragmentId(), "readonly", request.readonly());
            SimpleHttpServer.sendJsonResponse(exchange, response);
        } catch (Exception e) {
            logger.error("Error handling POST /v1/context/readonly", e);
            var error = ErrorPayload.internalError("Failed to toggle readonly", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    /**
     * POST /v1/context/compress-history - Compress conversation history.
     */
    void handlePostCompressHistory(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }

        try {
            contextManager.compressHistoryAsync();
            logger.info("Initiated history compression (session={})", contextManager.getCurrentSessionId());

            var response = Map.of("status", "compressing");
            SimpleHttpServer.sendJsonResponse(exchange, 202, response);
        } catch (Exception e) {
            logger.error("Error handling POST /v1/context/compress-history", e);
            var error = ErrorPayload.internalError("Failed to compress history", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    /**
     * POST /v1/context/clear-history - Clear conversation history.
     */
    void handlePostClearHistory(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }

        try {
            contextManager.clearHistory();
            logger.info("Cleared history (session={})", contextManager.getCurrentSessionId());

            var response = Map.of("status", "cleared");
            SimpleHttpServer.sendJsonResponse(exchange, response);
        } catch (Exception e) {
            logger.error("Error handling POST /v1/context/clear-history", e);
            var error = ErrorPayload.internalError("Failed to clear history", e);
            SimpleHttpServer.sendJsonResponse(exchange, 500, error);
        }
    }

    /**
     * POST /v1/context/drop-all - Drop all context fragments.
     */
    void handlePostDropAll(HttpExchange exchange) throws IOException {
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }

        try {
            contextManager.dropAll();
            logger.info("Dropped all context (session={})", contextManager.getCurrentSessionId());

            var response = Map.of("status", "dropped");
            SimpleHttpServer.sendJsonResponse(exchange, response);
        } catch (Exception e) {
            logger.error("Error handling POST /v1/context/drop-all", e);
            var error = ErrorPayload.internalError("Failed to drop all context", e);
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
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }

        try {
            var request = RouterUtil.parseJsonOr400(exchange, AddContextFilesRequest.class, "/v1/context/files");
            if (request == null) {
                return;
            }

            if (request.relativePaths().isEmpty()) {
                RouterUtil.sendValidationError(exchange, "relativePaths must not be empty");
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
                RouterUtil.sendValidationError(exchange, msg);
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
                        .allFragments()
                        .filter(f -> f instanceof ContextFragments.PathFragment)
                        .map(f -> (ContextFragments.PathFragment) f)
                        .filter(p -> {
                            var bf = p.file();
                            if (!(bf instanceof ProjectFile)) {
                                return false;
                            }
                            var a = bf.absPath();
                            var b = projectFile.absPath();
                            return a.equals(b);
                        })
                        .map(ContextFragment::id)
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
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }

        try {
            var request = RouterUtil.parseJsonOr400(exchange, AddContextClassesRequest.class, "/v1/context/classes");
            if (request == null) {
                return;
            }

            if (request.classNames().isEmpty()) {
                RouterUtil.sendValidationError(exchange, "classNames must not be empty");
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
                RouterUtil.sendValidationError(exchange, msg);
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
                        .allFragments()
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
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }

        try {
            var request = RouterUtil.parseJsonOr400(exchange, AddContextMethodsRequest.class, "/v1/context/methods");
            if (request == null) {
                return;
            }

            if (request.methodNames().isEmpty()) {
                RouterUtil.sendValidationError(exchange, "methodNames must not be empty");
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
                RouterUtil.sendValidationError(exchange, msg);
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
        if (!RouterUtil.ensureMethod(exchange, "POST")) {
            return;
        }

        try {
            var request = RouterUtil.parseJsonOr400(exchange, AddContextTextRequest.class, "/v1/context/text");
            if (request == null) {
                return;
            }

            var text = request.text();
            if (text.isBlank()) {
                logger.info("Rejected pasted text: blank");
                RouterUtil.sendValidationError(exchange, "text must not be blank");
                return;
            }

            final int MAX_BYTES = 1024 * 1024; // 1 MiB
            int byteLen = text.getBytes(UTF_8).length;
            if (byteLen > MAX_BYTES) {
                logger.info("Rejected pasted text: bytes={} exceeds limit", byteLen);
                RouterUtil.sendValidationError(exchange, "text exceeds maximum size of " + MAX_BYTES + " bytes");
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
