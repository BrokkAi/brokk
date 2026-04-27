package ai.brokk.acp;

import ai.brokk.BuildInfo;
import ai.brokk.ContextManager;
import ai.brokk.IAppContextManager;
import ai.brokk.SessionManager.SessionInfo;
import ai.brokk.concurrent.AtomicWrites;
import ai.brokk.concurrent.LoggingFuture;
import ai.brokk.context.Context;
import ai.brokk.executor.jobs.JobRunner;
import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.mcpclient.HttpMcpServer;
import ai.brokk.mcpclient.McpServer;
import ai.brokk.mcpclient.McpUtils;
import ai.brokk.mcpclient.StdioMcpServer;
import ai.brokk.tasks.TaskList;
import ai.brokk.util.Messages;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * ACP agent implementation that exposes Brokk's code intelligence and execution
 * capabilities via the Agent Client Protocol.
 *
 * <p>Handles the full ACP lifecycle: initialization, session management, prompt
 * execution, mode/model configuration, and cancellation.
 */
public class BrokkAcpAgent {
    private static final Logger logger = LogManager.getLogger(BrokkAcpAgent.class);

    private static final String DEFAULT_CODE_MODEL = "gemini-3-flash-preview";
    private static final String DEFAULT_REASONING_LEVEL = "medium";
    private static final String DEFAULT_REASONING_LEVEL_CODE = "disable";
    private static final Set<String> REASONING_LEVEL_IDS = Set.of("low", "medium", "high", "disable", "default");
    private static final Path ACP_SETTINGS_PATH =
            Path.of(System.getProperty("user.home"), ".brokk", "acp_settings.json");

    private static final List<AcpSchema.SessionMode> AVAILABLE_MODES = List.of(
            new AcpSchema.SessionMode("LUTZ", "LUTZ", "Agentic loop with task list"),
            new AcpSchema.SessionMode("CODE", "CODE", "Code changes only"),
            new AcpSchema.SessionMode("ASK", "ASK", "Question answering"),
            new AcpSchema.SessionMode("PLAN", "PLAN", "Planning only"));

    private final ContextManager cm;
    private final JobRunner jobRunner;
    private final JobStore jobStore;

    // Per-session state
    private final Map<String, String> modeBySession = new ConcurrentHashMap<>();
    private final Map<String, String> modelBySession = new ConcurrentHashMap<>();
    private final Map<String, String> reasoningBySession = new ConcurrentHashMap<>();
    private final Map<String, String> activeJobBySession = new ConcurrentHashMap<>();
    private final Map<String, Map<String, PermissionVerdict>> stickyPermissionsBySession = new ConcurrentHashMap<>();
    private final Map<String, List<McpServer>> mcpServersBySession = new ConcurrentHashMap<>();
    private static final InheritableThreadLocal<List<McpServer>> SESSION_MCP_SCOPE = new InheritableThreadLocal<>();
    private final Map<String, TaskList.TaskListData> lastTaskListBySession = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.atomic.AtomicReference<TaskList.TaskListData>>
            pendingPlanBySession = new ConcurrentHashMap<>();
    private final Map<String, List<String>> rejectedMcpServersBySession = new ConcurrentHashMap<>();
    private volatile @Nullable IAppContextManager.ContextListener taskListListener;

    /**
     * Gate that controls whether stdio MCP servers supplied over ACP are allowed to launch.
     * stdio MCP runs an arbitrary client-supplied command line on this process's host, so we
     * default to disallowing it. Operators who trust their ACP client may opt in via the
     * {@code BROKK_ACP_ALLOW_STDIO_MCP} environment variable.
     */
    private static final boolean ALLOW_ACP_STDIO_MCP =
            "1".equals(System.getenv("BROKK_ACP_ALLOW_STDIO_MCP"))
                    || "true".equalsIgnoreCase(System.getenv("BROKK_ACP_ALLOW_STDIO_MCP"));

    public enum PermissionVerdict {
        ALLOW,
        DENY
    }

    // Persisted defaults (loaded once on construction)
    private volatile String defaultModelId;
    private volatile String defaultReasoningLevel;

    // Set by BrokkAcpRuntime to enable session updates outside of prompt context
    private volatile @Nullable SessionUpdateSender sessionUpdateSender;

    @FunctionalInterface
    interface SessionUpdateSender {
        void sendSessionUpdate(String sessionId, AcpSchema.SessionUpdate update);
    }

    public BrokkAcpAgent(ContextManager cm, JobRunner jobRunner, JobStore jobStore) {
        this.cm = cm;
        this.jobRunner = jobRunner;
        this.jobStore = jobStore;

        var defaults = loadAcpDefaults();
        this.defaultModelId = defaults.defaultModel;
        this.defaultReasoningLevel = defaults.defaultReasoning;
        logger.info("ACP defaults loaded: model={}, reasoning={}", defaultModelId, defaultReasoningLevel);
    }

    public void setSessionUpdateSender(SessionUpdateSender sessionUpdateSender) {
        this.sessionUpdateSender = sessionUpdateSender;
    }

    /** Registers a listener that emits {@code plan} session updates whenever the task list changes. */
    public void start() {
        if (taskListListener != null) {
            return;
        }
        taskListListener = this::onContextChanged;
        cm.addContextListener(taskListListener);
    }

    /** Removes the task-list listener. Idempotent. */
    public void stop() {
        var listener = taskListListener;
        if (listener != null) {
            cm.removeContextListener(listener);
            taskListListener = null;
        }
    }

    /**
     * Clears all per-session state. Called from {@link BrokkAcpRuntime#close()} so that long-lived
     * processes don't accumulate maps for sessions whose ACP clients disconnected without sending
     * {@code session/close}. Idempotent.
     */
    public void clearAllSessions() {
        modeBySession.clear();
        modelBySession.clear();
        reasoningBySession.clear();
        activeJobBySession.clear();
        stickyPermissionsBySession.clear();
        mcpServersBySession.clear();
        rejectedMcpServersBySession.clear();
        lastTaskListBySession.clear();
        pendingPlanBySession.clear();
    }

    private void onContextChanged(Context newCtx) {
        var sender = sessionUpdateSender;
        if (sender == null) {
            return;
        }
        // Capture the current session id at listener-fire time. Note: ContextManager has no
        // session-of-origin on Context, so this is a best-effort attribution. The dedup against
        // lastTaskListBySession below makes mis-attribution self-healing on the next change.
        var sessionId = cm.getCurrentSessionId().toString();
        TaskList.TaskListData data;
        try {
            data = newCtx.getTaskListDataOrEmpty();
        } catch (Exception e) {
            logger.debug("Could not read task list for session {}", sessionId, e);
            return;
        }
        if (data.equals(lastTaskListBySession.get(sessionId))) {
            return;
        }
        // Single-flight per-session: coalesce concurrent updates so the client always sees the
        // newest plan, never an older one delivered after a newer one (out-of-order virtual threads
        // were a real risk under churn).
        var pending = pendingPlanBySession.computeIfAbsent(
                sessionId, k -> new java.util.concurrent.atomic.AtomicReference<>());
        var displaced = pending.getAndSet(data);
        if (displaced != null) {
            // A flush is already running; it will pick up our value before sending.
            return;
        }
        LoggingFuture.runVirtual(() -> drainPendingPlan(sessionId, sender, pending));
    }

    private void drainPendingPlan(
            String sessionId,
            SessionUpdateSender sender,
            java.util.concurrent.atomic.AtomicReference<TaskList.TaskListData> pending) {
        TaskList.TaskListData latest;
        while ((latest = pending.getAndSet(null)) != null) {
            try {
                lastTaskListBySession.put(sessionId, latest);
                sender.sendSessionUpdate(sessionId, buildPlanUpdate(latest));
            } catch (Exception e) {
                logger.warn("Failed to send plan update for session {}", sessionId, e);
            }
        }
    }

    private static AcpSchema.Plan buildPlanUpdate(TaskList.TaskListData data) {
        var entries = data.tasks().stream()
                .map(t -> new AcpSchema.PlanEntry(
                        t.title(),
                        AcpSchema.PlanEntryPriority.MEDIUM,
                        t.done() ? AcpSchema.PlanEntryStatus.COMPLETED : AcpSchema.PlanEntryStatus.PENDING))
                .toList();
        return new AcpSchema.Plan("plan", entries);
    }

    public AcpProtocol.InitializeResponse initialize() {
        logger.info("ACP initialize");
        var capabilities = new AcpProtocol.AgentCapabilities(
                true, // loadSession
                new AcpSchema.McpCapabilities(true, false), // http only; Brokk has no SSE transport
                new AcpSchema.PromptCapabilities(null, true, null), // embeddedContext = true
                new AcpProtocol.SessionCapabilities(
                        new AcpProtocol.SessionListCapabilities(null),
                        new AcpProtocol.SessionResumeCapabilities(null),
                        new AcpProtocol.SessionCloseCapabilities(null),
                        new AcpProtocol.SessionForkCapabilities(null),
                        null),
                Map.of("brokk", Map.of("context", true, "completions", true, "costs", true)));
        return new AcpProtocol.InitializeResponse(
                AcpSchema.LATEST_PROTOCOL_VERSION,
                capabilities,
                List.of(),
                new AcpSchema.Implementation("brokk", BuildInfo.version, "Brokk Code Intelligence"),
                null);
    }

    public AcpSchema.AuthenticateResponse authenticate() {
        return new AcpSchema.AuthenticateResponse();
    }

    public AcpSchema.NewSessionResponse newSession(AcpSchema.NewSessionRequest request) {
        logger.info("ACP new session, cwd={}", request.cwd());

        // Create session in ContextManager and use its UUID as the ACP session ID
        cm.createSessionAsync("ACP Session").join();
        var sessionId = cm.getCurrentSessionId().toString();

        modeBySession.put(sessionId, "LUTZ");
        reasoningBySession.put(sessionId, defaultReasoningLevel);
        applySessionMcpServers(sessionId, request.mcpServers());

        var modeState = new AcpSchema.SessionModeState("LUTZ", AVAILABLE_MODES);
        var modelState = buildModelState(sessionId);
        var meta = buildVariantMeta(sessionId);

        scheduleAvailableCommandsUpdate(sessionId);

        return new AcpSchema.NewSessionResponse(sessionId, modeState, modelState, meta);
    }

    public AcpSchema.LoadSessionResponse loadSession(AcpSchema.LoadSessionRequest request) {
        logger.info("ACP load session {}", request.sessionId());
        var sessionId = request.sessionId();

        // Switch to the requested session, or fail if it doesn't exist
        var sessions = cm.getProject().getSessionManager().listSessions();
        var target = sessions.stream()
                .filter(s -> s.id().toString().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown session: " + sessionId));
        cm.switchSessionAsync(target.id()).join();

        modeBySession.putIfAbsent(sessionId, "LUTZ");
        reasoningBySession.putIfAbsent(sessionId, defaultReasoningLevel);
        applySessionMcpServers(sessionId, request.mcpServers());

        var modeState = new AcpSchema.SessionModeState(modeBySession.getOrDefault(sessionId, "LUTZ"), AVAILABLE_MODES);
        var modelState = buildModelState(sessionId);
        var meta = buildVariantMeta(sessionId);

        // Schedule conversation replay and commands advertisement after response is sent
        scheduleConversationReplay(sessionId);
        scheduleAvailableCommandsUpdate(sessionId);

        return new AcpSchema.LoadSessionResponse(modeState, modelState, meta);
    }

    public AcpProtocol.ResumeSessionResponse resumeSession(AcpProtocol.ResumeSessionRequest request) {
        logger.info("ACP resume session {}", request.sessionId());
        var sessionId = request.sessionId();
        switchToKnownSession(sessionId);

        modeBySession.putIfAbsent(sessionId, "LUTZ");
        reasoningBySession.putIfAbsent(sessionId, defaultReasoningLevel);
        applySessionMcpServers(sessionId, request.mcpServers());

        var modeState = new AcpSchema.SessionModeState(modeBySession.getOrDefault(sessionId, "LUTZ"), AVAILABLE_MODES);
        var modelState = buildModelState(sessionId);
        var meta = buildVariantMeta(sessionId);

        scheduleAvailableCommandsUpdate(sessionId);
        return new AcpProtocol.ResumeSessionResponse(modeState, modelState, meta);
    }

    public AcpProtocol.ListSessionsResponse listSessions(AcpProtocol.ListSessionsRequest request) {
        logger.info("ACP list sessions cwd={}", request.cwd());
        var rootPath = cm.getProject().getRoot().toAbsolutePath().normalize();
        var root = rootPath.toString();
        if (request.cwd() != null
                && !request.cwd().isBlank()
                && !Path.of(request.cwd()).toAbsolutePath().normalize().equals(rootPath)) {
            return new AcpProtocol.ListSessionsResponse(List.of(), null, null);
        }
        var sessions = cm.getProject().getSessionManager().listSessions().stream()
                .map(session -> {
                    var stats = cm.getProject().getSessionManager().countSessionStats(session.id());
                    var meta = Map.<String, Object>of(
                            "brokk",
                            Map.of(
                                    "aiResponses", stats.aiResponses(),
                                    "totalTasks", stats.tasks().total(),
                                    "incompleteTasks", stats.tasks().incomplete()));
                    return new AcpProtocol.SessionInfo(
                            session.id().toString(),
                            root,
                            session.name(),
                            Instant.ofEpochMilli(session.modified()).toString(),
                            meta);
                })
                .toList();
        return new AcpProtocol.ListSessionsResponse(sessions, null, null);
    }

    public AcpProtocol.CloseSessionResponse closeSession(AcpProtocol.CloseSessionRequest request) {
        var sessionId = request.sessionId();
        logger.info("ACP close session {}", sessionId);
        var jobId = activeJobBySession.remove(sessionId);
        if (jobId != null) {
            jobRunner.cancel(jobId);
        }
        modeBySession.remove(sessionId);
        modelBySession.remove(sessionId);
        reasoningBySession.remove(sessionId);
        stickyPermissionsBySession.remove(sessionId);
        mcpServersBySession.remove(sessionId);
        rejectedMcpServersBySession.remove(sessionId);
        lastTaskListBySession.remove(sessionId);
        pendingPlanBySession.remove(sessionId);
        return new AcpProtocol.CloseSessionResponse(null);
    }

    public AcpProtocol.ForkSessionResponse forkSession(AcpProtocol.ForkSessionRequest request) {
        logger.info("ACP fork session {}", request.sessionId());
        assertCompatibleCwd(request.cwd());

        var target = findSession(request.sessionId());
        try {
            var copied = cm.getProject().getSessionManager().copySession(target.id(), "Fork of " + target.name());
            var forkSessionId = copied.id().toString();
            cm.switchSessionAsync(copied.id()).join();

            modeBySession.put(forkSessionId, modeBySession.getOrDefault(request.sessionId(), "LUTZ"));
            var model = modelBySession.get(request.sessionId());
            if (model != null) {
                modelBySession.put(forkSessionId, model);
            }
            reasoningBySession.put(
                    forkSessionId, reasoningBySession.getOrDefault(request.sessionId(), defaultReasoningLevel));
            // Inherit MCP servers from parent unless the fork request supplies a fresh list.
            if (request.mcpServers() != null) {
                applySessionMcpServers(forkSessionId, request.mcpServers());
            } else {
                var parentServers = mcpServersBySession.get(request.sessionId());
                if (parentServers != null) {
                    mcpServersBySession.put(forkSessionId, List.copyOf(parentServers));
                }
            }

            var modeState =
                    new AcpSchema.SessionModeState(modeBySession.getOrDefault(forkSessionId, "LUTZ"), AVAILABLE_MODES);
            var modelState = buildModelState(forkSessionId);
            var meta = buildVariantMeta(forkSessionId);

            scheduleAvailableCommandsUpdate(forkSessionId);
            return new AcpProtocol.ForkSessionResponse(forkSessionId, modeState, modelState, meta);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fork session " + request.sessionId(), e);
        }
    }

    public AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, AcpPromptContext promptContext) {
        var sessionId = request.sessionId();
        var text = request.text();
        logger.info(
                "ACP prompt session={} text={}",
                sessionId,
                text != null ? text.substring(0, Math.min(80, text.length())) : "(empty)");

        if (text == null || text.isBlank()) {
            promptContext.sendMessage("Error: empty prompt");
            return AcpSchema.PromptResponse.endTurn();
        }

        // Handle slash commands
        if (text.strip().toLowerCase(Locale.ROOT).startsWith("/context")) {
            handleContextCommand(sessionId, promptContext);
            return AcpSchema.PromptResponse.endTurn();
        }

        // Switch to the requested session so the job executes against the correct state
        var sessions = cm.getProject().getSessionManager().listSessions();
        var target = sessions.stream()
                .filter(s -> s.id().toString().equals(sessionId))
                .findFirst();
        if (target.isEmpty()) {
            promptContext.sendMessage("Error: unknown session " + sessionId);
            return AcpSchema.PromptResponse.endTurn();
        }
        cm.switchSessionAsync(target.get().id()).join();

        // Build JobSpec with reasoning levels from session state
        var mode = modeBySession.getOrDefault(sessionId, "LUTZ");
        var model = modelBySession.getOrDefault(sessionId, "");
        var reasoning = reasoningBySession.getOrDefault(sessionId, DEFAULT_REASONING_LEVEL);
        var tags = new HashMap<String, String>();
        tags.put("mode", mode);

        // Fall back to planner model if default code model is not available
        var availableModels = cm.getService().getAvailableModels();
        var codeModel = availableModels.containsKey(DEFAULT_CODE_MODEL) ? DEFAULT_CODE_MODEL : model;

        var spec = new JobSpec(
                text, // taskInput
                true, // autoCommit
                true, // autoCompress
                model, // plannerModel
                null, // scanModel
                codeModel, // codeModel
                false, // preScan
                tags, // tags
                null, // sourceBranch
                null, // targetBranch
                reasoning, // reasoningLevel
                DEFAULT_REASONING_LEVEL_CODE, // reasoningLevelCode
                null, // temperature
                null, // temperatureCode
                false, // skipVerification
                null); // maxIssueFixAttempts

        // Create console adapter and run the job
        var acpConsole = new AcpConsoleIO(promptContext);
        var jobId = UUID.randomUUID().toString();
        activeJobBySession.put(sessionId, jobId);

        try (var mcpScope = installMcpScope(sessionId);
                var fsScope = AcpFileBridge.install(promptContext, promptContext.getClientCapabilities())) {
            // Create job in store
            jobStore.createOrGetJob(jobId, spec);

            // Run using the ACP console -- JobRunner handles cm.setIo swap internally
            jobRunner.runAsync(jobId, spec, acpConsole).join();
        } catch (Exception e) {
            logger.error("Job execution failed", e);
            promptContext.sendMessage("\n**Error:** " + e.getMessage() + "\n");
        } finally {
            activeJobBySession.remove(sessionId, jobId);
        }

        return AcpSchema.PromptResponse.endTurn();
    }

    public AcpSchema.SetSessionModeResponse setMode(AcpSchema.SetSessionModeRequest request) {
        var sessionId = request.sessionId();
        var modeId = request.modeId();
        logger.info("ACP set mode session={} mode={}", sessionId, modeId);

        var validMode = AVAILABLE_MODES.stream().anyMatch(m -> m.id().equals(modeId));
        if (validMode) {
            modeBySession.put(sessionId, modeId);
            var sender = sessionUpdateSender;
            if (sender != null) {
                sender.sendSessionUpdate(sessionId, new AcpSchema.CurrentModeUpdate("current_mode_update", modeId));
            }
        }

        return new AcpSchema.SetSessionModeResponse();
    }

    public AcpSchema.SetSessionModelResponse setModel(AcpSchema.SetSessionModelRequest request) {
        var sessionId = request.sessionId();
        var modelId = request.modelId();
        logger.info("ACP set model session={} model={}", sessionId, modelId);

        // Parse "model/variant" format: split on last "/" and check if last segment is a reasoning level
        var parsed = parseModelSelection(modelId);
        modelBySession.put(sessionId, parsed.baseModel);
        if (parsed.reasoning != null) {
            reasoningBySession.put(sessionId, parsed.reasoning);
        }

        logger.info("ACP set model parsed: base={} reasoning={}", parsed.baseModel, parsed.reasoning);

        // Persist updated defaults
        saveAcpDefaults(parsed.baseModel, reasoningBySession.getOrDefault(sessionId, DEFAULT_REASONING_LEVEL));

        return new AcpSchema.SetSessionModelResponse();
    }

    /**
     * Returns the MCP servers the client supplied for {@code sessionId} via {@code session/new} or
     * a related session method. Empty when the client passed none.
     */
    public List<McpServer> mcpServersFor(String sessionId) {
        return mcpServersBySession.getOrDefault(sessionId, List.of());
    }

    /**
     * Installs the per-session MCP server list as an inheritable thread-local for the duration of
     * the returned scope. Consumers (e.g. {@code LutzAgent.initMcpTools}) read the list via
     * {@link #currentSessionMcpServers()}.
     */
    public AutoCloseable installMcpScope(String sessionId) {
        return ThreadLocalScope.install(SESSION_MCP_SCOPE, mcpServersFor(sessionId));
    }

    /** Returns the MCP server list visible to the active prompt thread, or an empty list. */
    public static List<McpServer> currentSessionMcpServers() {
        var current = SESSION_MCP_SCOPE.get();
        return current == null ? List.of() : current;
    }

    private void applySessionMcpServers(String sessionId, @Nullable List<AcpSchema.McpServer> acpServers) {
        rejectedMcpServersBySession.remove(sessionId);
        if (acpServers == null || acpServers.isEmpty()) {
            mcpServersBySession.remove(sessionId);
            return;
        }
        var converted = new ArrayList<McpServer>();
        var rejected = new ArrayList<String>();
        for (var acp : acpServers) {
            var brokkServer = convertAcpMcpServer(acp);
            if (brokkServer == null) {
                rejected.add(acpServerName(acp));
            } else {
                converted.add(brokkServer);
            }
        }
        if (converted.isEmpty()) {
            mcpServersBySession.remove(sessionId);
        } else {
            mcpServersBySession.put(sessionId, List.copyOf(converted));
            // Log only the names — full McpServer toString may include bearer tokens or env vars.
            var names = converted.stream().map(BrokkAcpAgent::serverName).toList();
            logger.info("ACP session {} registered MCP server(s): {}", sessionId, names);
        }
        if (!rejected.isEmpty()) {
            rejectedMcpServersBySession.put(sessionId, List.copyOf(rejected));
            logger.warn("ACP session {} rejected MCP server(s): {}", sessionId, rejected);
        }
    }

    private static String acpServerName(AcpSchema.McpServer s) {
        if (s instanceof AcpSchema.McpServerHttp h) return h.name();
        if (s instanceof AcpSchema.McpServerStdio st) return st.name();
        if (s instanceof AcpSchema.McpServerSse se) return se.name();
        return s.getClass().getSimpleName();
    }

    private static @Nullable McpServer convertAcpMcpServer(AcpSchema.McpServer server) {
        if (server instanceof AcpSchema.McpServerHttp http) {
            try {
                var url = new URI(http.url()).toURL();
                // Only forward an Authorization header that's already a Bearer token. Non-Bearer
                // schemes (Basic, ApiKey, custom) would otherwise be silently re-prefixed with
                // "Bearer " by McpUtils.buildTransport, corrupting the auth scheme.
                String bearerToken = http.headers() == null
                        ? null
                        : http.headers().stream()
                                .filter(h -> "authorization".equalsIgnoreCase(h.name()))
                                .map(AcpSchema.HttpHeader::value)
                                .filter(v -> v != null && v.regionMatches(true, 0, "Bearer ", 0, 7))
                                .findFirst()
                                .orElse(null);
                return McpUtils.withDiscoveredTools(new HttpMcpServer(http.name(), url, null, bearerToken));
            } catch (Exception e) {
                logger.warn("Failed to convert ACP HTTP MCP server {}: {}", http.name(), e.getMessage());
                return null;
            }
        }
        if (server instanceof AcpSchema.McpServerStdio stdio) {
            if (!ALLOW_ACP_STDIO_MCP) {
                logger.warn(
                        "ACP MCP server {} is stdio; spawning client-supplied processes is disabled by default. "
                                + "Set BROKK_ACP_ALLOW_STDIO_MCP=1 to enable (only for trusted ACP clients).",
                        stdio.name());
                return null;
            }
            try {
                Map<String, String> env = stdio.env() == null
                        ? Map.of()
                        : stdio.env().stream()
                                .collect(java.util.stream.Collectors.toMap(
                                        AcpSchema.EnvVariable::name, AcpSchema.EnvVariable::value));
                return McpUtils.withDiscoveredTools(
                        new StdioMcpServer(stdio.name(), stdio.command(), List.copyOf(stdio.args()), env, null));
            } catch (Exception e) {
                logger.warn("Failed to convert ACP stdio MCP server {}: {}", stdio.name(), e.getMessage());
                return null;
            }
        }
        if (server instanceof AcpSchema.McpServerSse sse) {
            logger.warn("ACP MCP server {} is SSE; Brokk does not support SSE transport, dropping", sse.name());
            return null;
        }
        logger.warn("Unknown ACP MCP server variant: {}", server.getClass().getSimpleName());
        return null;
    }

    private static String serverName(McpServer server) {
        if (server instanceof HttpMcpServer http) return http.name();
        if (server instanceof StdioMcpServer stdio) return stdio.name();
        return server.getClass().getSimpleName();
    }

    public Optional<PermissionVerdict> stickyPermissionFor(String sessionId, String toolName) {
        var sessionMap = stickyPermissionsBySession.get(sessionId);
        if (sessionMap == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessionMap.get(toolName));
    }

    public void rememberPermission(String sessionId, String toolName, PermissionVerdict verdict) {
        stickyPermissionsBySession
                .computeIfAbsent(sessionId, ignored -> new ConcurrentHashMap<>())
                .put(toolName, verdict);
    }

    public void cancel(AcpSchema.CancelNotification notification) {
        var sessionId = notification.sessionId();
        logger.info("ACP cancel session={}", sessionId);
        var jobId = activeJobBySession.get(sessionId);
        if (jobId != null) {
            jobRunner.cancel(jobId);
        } else {
            logger.info("ACP cancel: no active job for session {}", sessionId);
        }
    }

    private void switchToKnownSession(String sessionId) {
        var target = findSession(sessionId);
        cm.switchSessionAsync(target.id()).join();
    }

    private SessionInfo findSession(String sessionId) {
        return cm.getProject().getSessionManager().listSessions().stream()
                .filter(s -> s.id().toString().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown session: " + sessionId));
    }

    private void assertCompatibleCwd(String cwd) {
        var rootPath = cm.getProject().getRoot().toAbsolutePath().normalize();
        if (!Path.of(cwd).toAbsolutePath().normalize().equals(rootPath)) {
            throw new IllegalArgumentException("Session cwd does not match project root: " + cwd);
        }
    }

    // ---- Model state with reasoning variants ----

    private AcpSchema.SessionModelState buildModelState(String sessionId) {
        var service = cm.getService();
        var availableModels = service.getAvailableModels();

        // Build model list with reasoning variants (model/low, model/medium, model/high, etc.)
        var models = new ArrayList<AcpSchema.ModelInfo>();
        availableModels.keySet().stream().sorted().forEach(name -> {
            models.add(new AcpSchema.ModelInfo(name, name, null));
            for (var level : List.of("low", "medium", "high", "disable")) {
                models.add(new AcpSchema.ModelInfo(name + "/" + level, name + " (" + level + ")", null));
            }
        });

        if (models.isEmpty()) {
            models.add(new AcpSchema.ModelInfo("default", "Default Model", null));
        }

        // Resolve current model, preferring persisted default for new sessions
        var baseModel = modelBySession.getOrDefault(sessionId, defaultModelId);
        // Validate against available models; fall back to first if not found
        var modelNames = availableModels.keySet();
        if (!baseModel.isEmpty() && !modelNames.contains(baseModel) && !modelNames.isEmpty()) {
            baseModel = modelNames.stream().sorted().findFirst().orElse(baseModel);
        }
        modelBySession.putIfAbsent(sessionId, baseModel);

        // Format current model ID with variant
        var reasoning = reasoningBySession.getOrDefault(sessionId, DEFAULT_REASONING_LEVEL);
        var currentModelId = formatModelIdWithVariant(baseModel, reasoning);

        return new AcpSchema.SessionModelState(currentModelId, List.copyOf(models));
    }

    private Map<String, Object> buildVariantMeta(String sessionId) {
        var baseModel = modelBySession.getOrDefault(sessionId, defaultModelId);
        var reasoning = reasoningBySession.getOrDefault(sessionId, DEFAULT_REASONING_LEVEL);
        var variants = modelVariantsFor(baseModel);
        var brokk = new HashMap<String, Object>();
        brokk.put("modelId", baseModel);
        brokk.put("variant", reasoning);
        brokk.put("availableVariants", variants);
        var rejected = rejectedMcpServersBySession.get(sessionId);
        if (rejected != null && !rejected.isEmpty()) {
            // Surface MCP servers that the client supplied but Brokk dropped (SSE, stdio without
            // BROKK_ACP_ALLOW_STDIO_MCP, conversion failures). Without this the client sees no
            // tools from these and has no way to learn why.
            brokk.put("rejectedMcpServers", rejected);
        }
        return Map.of("brokk", Map.copyOf(brokk));
    }

    private List<String> modelVariantsFor(String modelName) {
        var service = cm.getService();
        if (!service.supportsReasoningEffort(modelName)) {
            return List.of();
        }
        var variants = new ArrayList<>(List.of("low", "medium", "high"));
        if (service.supportsReasoningDisable(modelName)) {
            variants.add("disable");
        }
        return List.copyOf(variants);
    }

    private static String formatModelIdWithVariant(String baseModel, String reasoning) {
        if (reasoning.equals("default") || reasoning.isEmpty()) {
            return baseModel;
        }
        return baseModel + "/" + reasoning;
    }

    // ---- Model selection parsing ----

    private record ParsedModelSelection(String baseModel, @Nullable String reasoning) {}

    private ParsedModelSelection parseModelSelection(String modelId) {
        if (modelId.isBlank()) {
            return new ParsedModelSelection("", null);
        }
        var raw = modelId.strip();

        // Check for "model/variant" format: split on last "/"
        int lastSlash = raw.lastIndexOf('/');
        if (lastSlash > 0 && lastSlash < raw.length() - 1) {
            var candidateVariant = raw.substring(lastSlash + 1).strip().toLowerCase(Locale.ROOT);
            if (REASONING_LEVEL_IDS.contains(candidateVariant)) {
                var baseModel = raw.substring(0, lastSlash).strip();
                return new ParsedModelSelection(baseModel, candidateVariant);
            }
        }

        return new ParsedModelSelection(raw, null);
    }

    // ---- Conversation replay ----

    private void scheduleConversationReplay(String sessionId) {
        var sender = this.sessionUpdateSender;
        if (sender == null) {
            logger.debug("No sessionUpdateSender available, skipping conversation replay for session {}", sessionId);
            return;
        }

        Thread.startVirtualThread(() -> {
            try {
                var taskHistory = cm.liveContext().getTaskHistory();
                for (var task : taskHistory) {
                    // Send user prompt as UserMessageChunk
                    var description = task.description();
                    if (!description.isBlank()) {
                        sender.sendSessionUpdate(
                                sessionId,
                                new AcpSchema.UserMessageChunk(
                                        "user_message_chunk", new AcpSchema.TextContent(description)));
                    }

                    // Send agent response as AgentMessageChunk
                    var mopMarkdown = task.mopMarkdown();
                    var responseText = mopMarkdown != null ? mopMarkdown : task.summary();
                    if (responseText != null && !responseText.isBlank()) {
                        sender.sendSessionUpdate(
                                sessionId,
                                new AcpSchema.AgentMessageChunk(
                                        "agent_message_chunk", new AcpSchema.TextContent(responseText)));
                    }
                }
                logger.info("Conversation replay complete for session {} ({} entries)", sessionId, taskHistory.size());
            } catch (Exception e) {
                logger.warn("Conversation replay failed for session {}", sessionId, e);
            }
        });
    }

    // ---- Slash commands ----

    private void handleContextCommand(String sessionId, AcpPromptContext promptContext) {
        logger.info("ACP /context command for session {}", sessionId);
        var live = cm.liveContext();
        var fragments = live.getAllFragmentsInDisplayOrder();

        record FragmentRow(String name, int tokens, double pct) {}

        int totalTokens = 0;
        var rows = new ArrayList<FragmentRow>();
        for (var f : fragments) {
            var name = f.shortDescription().renderNowOr("Unknown");
            int tokens = f.isText() || f.getType().isOutput()
                    ? Messages.getApproximateTokens(f.text().renderNowOr(""))
                    : 0;
            totalTokens += tokens;
            rows.add(new FragmentRow(name, tokens, 0));
        }

        int base = totalTokens > 0 ? totalTokens : 1;
        var withPct = rows.stream()
                .map(r -> new FragmentRow(r.name, r.tokens, (r.tokens / (double) base) * 100))
                .sorted((a, b) -> Double.compare(b.pct, a.pct))
                .toList();

        var top = new ArrayList<>(withPct.stream().limit(4).toList());
        if (withPct.size() > 4) {
            int otherTokens =
                    withPct.stream().skip(4).mapToInt(FragmentRow::tokens).sum();
            double otherPct =
                    withPct.stream().skip(4).mapToDouble(FragmentRow::pct).sum();
            top.add(new FragmentRow("(other)", otherTokens, otherPct));
        }

        var sb = new StringBuilder();
        sb.append("| Fragment | Tokens | % Context |\n");
        sb.append("|---|---:|---:|\n");
        if (top.isEmpty()) {
            sb.append("| (none) | 0 | 0.00% |\n");
        } else {
            for (var row : top) {
                sb.append("| %s | %,d | %.2f%% |\n".formatted(row.name, row.tokens, row.pct));
            }
        }
        sb.append("\n**Total Tokens:** %,d\n".formatted(totalTokens));

        promptContext.sendMessage(sb.toString());
    }

    private void scheduleAvailableCommandsUpdate(String sessionId) {
        var sender = this.sessionUpdateSender;
        if (sender == null) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                var commands =
                        List.of(new AcpSchema.AvailableCommand("context", "Show current context snapshot", null));
                sender.sendSessionUpdate(
                        sessionId, new AcpSchema.AvailableCommandsUpdate("available_commands_update", commands));
            } catch (Exception e) {
                logger.warn("Failed to send available commands for session {}", sessionId, e);
            }
        });
    }

    // ---- ACP defaults persistence ----

    private record AcpDefaults(String defaultModel, String defaultReasoning) {}

    private static AcpDefaults loadAcpDefaults() {
        try {
            if (!Files.exists(ACP_SETTINGS_PATH)) {
                return new AcpDefaults("", DEFAULT_REASONING_LEVEL);
            }
            var mapper = new ObjectMapper();
            var tree = mapper.readTree(ACP_SETTINGS_PATH.toFile());
            var model = tree.has("default_model") ? tree.get("default_model").asText("") : "";
            var reasoning = tree.has("default_reasoning")
                    ? tree.get("default_reasoning").asText(DEFAULT_REASONING_LEVEL)
                    : DEFAULT_REASONING_LEVEL;
            if (!REASONING_LEVEL_IDS.contains(reasoning)) {
                logger.warn(
                        "Persisted ACP reasoning {} invalid; falling back to {}", reasoning, DEFAULT_REASONING_LEVEL);
                reasoning = DEFAULT_REASONING_LEVEL;
            }
            return new AcpDefaults(model.strip(), reasoning.strip());
        } catch (Exception e) {
            logger.warn("Failed to load ACP defaults, using built-in defaults", e);
            return new AcpDefaults("", DEFAULT_REASONING_LEVEL);
        }
    }

    private static void saveAcpDefaults(String model, String reasoning) {
        try {
            var dir = ACP_SETTINGS_PATH.getParent();
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            var mapper = new ObjectMapper();
            var json = mapper.writeValueAsString(Map.of("default_model", model, "default_reasoning", reasoning));
            AtomicWrites.save(ACP_SETTINGS_PATH, json);
            logger.debug("Saved ACP defaults: model={}, reasoning={}", model, reasoning);
        } catch (IOException e) {
            logger.warn("Failed to save ACP defaults", e);
        }
    }
}
