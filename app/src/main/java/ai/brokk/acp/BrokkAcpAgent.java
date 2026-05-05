package ai.brokk.acp;

import ai.brokk.AbstractService;
import ai.brokk.BuildInfo;
import ai.brokk.ContextManager;
import ai.brokk.IAppContextManager;
import ai.brokk.IssueProvider;
import ai.brokk.Service;
import ai.brokk.SessionManager.SessionInfo;
import ai.brokk.SettingsChangeListener;
import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.agents.BuildAgent.ModuleBuildEntry;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.concurrent.AtomicWrites;
import ai.brokk.concurrent.LoggingFuture;
import ai.brokk.context.Context;
import ai.brokk.exception.GlobalExceptionHandler;
import ai.brokk.executor.jobs.JobRunner;
import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.issues.IssueProviderType;
import ai.brokk.issues.IssuesProviderConfig.GithubConfig;
import ai.brokk.issues.IssuesProviderConfig.JiraConfig;
import ai.brokk.mcpclient.HttpMcpServer;
import ai.brokk.mcpclient.McpServer;
import ai.brokk.mcpclient.McpUtils;
import ai.brokk.mcpclient.StdioMcpServer;
import ai.brokk.openai.OpenAiOAuthService;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.project.ModelProperties;
import ai.brokk.tasks.TaskList;
import ai.brokk.util.Environment;
import ai.brokk.util.LegacyFramingParser;
import ai.brokk.util.Messages;
import ai.brokk.util.ShellConfig;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String DEFAULT_CODE_MODEL = "gemini-3-flash-preview";
    private static final String DEFAULT_REASONING_LEVEL = "medium";
    private static final String DEFAULT_REASONING_LEVEL_CODE = "disable";
    private static final Set<String> REASONING_LEVEL_IDS = Set.of("low", "medium", "high", "disable", "default");

    private static final int MODEL_DISCOVERY_INITIAL_ATTEMPTS = 3;
    private static final int MODEL_DISCOVERY_RECOVERY_ATTEMPTS = 2;
    private static final long MODEL_DISCOVERY_INITIAL_BACKOFF_MS = 200;
    private static final Set<String> KNOWN_CONFIG_SECTIONS = Set.of(
            "buildDetails",
            "projectSettings",
            "shellConfig",
            "issueProvider",
            "dataRetentionPolicy",
            "analyzerLanguages",
            "global");

    /**
     * Leaf keys under {@code global} that are only consumed by the Brokk Swing desktop app
     * (theme, notifications, layout flags, JVM-launcher tuning, etc.). They are hidden from the
     * ACP {@code /config} snapshot and writes to them are rejected — setting them in ACP would
     * succeed silently with no observable effect, which confuses users.
     *
     * <p>Package-private so {@code BrokkAcpAgentTest} can assert exhaustively that the snapshot
     * omits every key here, catching drift if a future contributor re-adds a {@code map.put}.
     */
    static final Set<String> GUI_ONLY_GLOBAL_KEYS = Set.of(
            "theme",
            "codeBlockWrapMode",
            "startupOpenMode",
            "watchServiceImplPreference",
            "otherModelsVendorPreference",
            "jvmMemorySettings",
            "advancedMode",
            "diffUnifiedView",
            "persistPerProjectBounds",
            "instructionsTabInsertIndentation",
            "verticalActivityLayout",
            "notifications");

    private static final String ACP_SETTINGS_PATH_PROPERTY = "brokk.acp.settings.path";
    private static final Path DEFAULT_ACP_SETTINGS_PATH =
            Path.of(System.getProperty("user.home"), ".brokk", "acp_settings.json");

    private static final List<AcpSchema.SessionMode> AVAILABLE_MODES = List.of(
            new AcpSchema.SessionMode("LUTZ", "LUTZ", "Agentic loop with task list"),
            new AcpSchema.SessionMode("CODE", "CODE", "Code changes only"),
            new AcpSchema.SessionMode("ASK", "ASK", "Question answering"),
            new AcpSchema.SessionMode("PLAN", "PLAN", "Planning only"));

    /**
     * One workspace's worth of state. ACP supports per-session {@code cwd}, so each distinct
     * project root gets its own bundle. Bundles are created lazily on the first session that
     * names that root and reused for later sessions on the same root, mirroring the swap-on-cwd
     * logic in {@code brokk-code/brokk_code/acp_server.py:ensure_ready}.
     */
    public record WorkspaceBundle(ContextManager cm, JobRunner jobRunner, JobStore jobStore, Path root) {}

    /** Strategy for materializing a bundle for a given canonical project root. */
    @FunctionalInterface
    public interface WorkspaceBundleFactory {
        WorkspaceBundle create(Path root);
    }

    /** Default workspace dir used when {@code session/new} doesn't supply a {@code cwd}. */
    private final Path defaultWorkspaceDir;

    private final WorkspaceBundleFactory bundleFactory;

    /** Bundles keyed by canonical absolute project root. */
    private final Map<Path, WorkspaceBundle> bundlesByRoot = new ConcurrentHashMap<>();

    /** Per-bundle listeners attached to each bundle's ContextManager (for orderly removal). */
    private final Map<Path, IAppContextManager.ContextListener> listenersByRoot = new ConcurrentHashMap<>();

    /** Maps each active session id to the bundle it belongs to. */
    private final Map<String, WorkspaceBundle> bundleBySession = new ConcurrentHashMap<>();

    // Per-session state
    private final Map<String, String> modeBySession = new ConcurrentHashMap<>();
    private final Map<String, String> modelBySession = new ConcurrentHashMap<>();
    private final Map<String, String> codeModelBySession = new ConcurrentHashMap<>();
    private final Map<String, String> codeReasoningBySession = new ConcurrentHashMap<>();
    private final Set<String> sessionsOnFallbackCatalog = ConcurrentHashMap.newKeySet();
    private final Map<String, String> reasoningBySession = new ConcurrentHashMap<>();
    private final Map<String, String> activeJobBySession = new ConcurrentHashMap<>();
    private final Map<String, Map<String, PermissionVerdict>> stickyPermissionsBySession = new ConcurrentHashMap<>();

    /**
     * Cross-session persistent permission rules, keyed by project root. Loaded lazily on first
     * access for a project; survives ACP session close (intentionally NOT cleared by
     * {@link #clearAllSessions()}). Backing file is {@code <projectRoot>/.brokk/permission_rules.json}.
     */
    private final Map<Path, PermissionRules> rulesByProjectRoot = new ConcurrentHashMap<>();

    private final Map<String, PermissionMode> permissionModeBySession = new ConcurrentHashMap<>();
    private final Map<String, Boolean> sandboxDisabledBySession = new ConcurrentHashMap<>();
    private final Map<String, List<McpServer>> mcpServersBySession = new ConcurrentHashMap<>();
    private static final InheritableThreadLocal<List<McpServer>> SESSION_MCP_SCOPE = new InheritableThreadLocal<>();
    private final Map<String, TaskList.TaskListData> lastTaskListBySession = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<TaskList.TaskListData>> pendingPlanBySession = new ConcurrentHashMap<>();
    private final Map<String, List<String>> rejectedMcpServersBySession = new ConcurrentHashMap<>();

    /** True once {@link #start()} has been called; new bundles register the listener on creation. */
    private volatile boolean listenerActive = false;

    /**
     * Gate that controls whether stdio MCP servers supplied over ACP are allowed to launch.
     * stdio MCP runs an arbitrary client-supplied command line on this process's host, so we
     * default to disallowing it. Operators who trust their ACP client may opt in via the
     * {@code BROKK_ACP_ALLOW_STDIO_MCP} environment variable.
     */
    private static final boolean ALLOW_ACP_STDIO_MCP = "1".equals(System.getenv("BROKK_ACP_ALLOW_STDIO_MCP"))
            || "true".equalsIgnoreCase(System.getenv("BROKK_ACP_ALLOW_STDIO_MCP"));

    public enum PermissionVerdict {
        ALLOW,
        ALLOW_NO_SANDBOX,
        DENY
    }

    // Persisted defaults (loaded once on construction, refreshed by setters)
    private volatile String defaultModelId;
    private volatile String defaultReasoningLevel;
    private volatile String defaultCodeModelId;
    private volatile String defaultCodeReasoningLevel;

    // Set by BrokkAcpRuntime to enable session updates outside of prompt context
    private volatile @Nullable SessionUpdateSender sessionUpdateSender;

    // Set by BrokkAcpRuntime at initialize when the client is below MIN_CLIENT_VERSIONS. When
    // non-null, prompt() short-circuits with this message instead of invoking the LLM.
    private volatile @Nullable String compatibilityWarning;

    @FunctionalInterface
    interface SessionUpdateSender {
        void sendSessionUpdate(String sessionId, AcpSchema.SessionUpdate update);
    }

    /**
     * In-flight Codex OAuth attempt. {@link OpenAiOAuthService} only supports one OAuth flow per
     * JVM (static lock, single bound port 1455), so this is held as a single
     * {@link AtomicReference} rather than per-session. Cleared when the OAuth callback fires, the
     * timeout expires, or the owning session is closed.
     */
    private record LoginPending(String sessionId, SettingsChangeListener listener, ScheduledFuture<?> timeout) {}

    private final AtomicReference<LoginPending> pendingLogin = new AtomicReference<>();

    private static final ScheduledExecutorService LOGIN_TIMEOUT_EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "acp-codex-login-timeout");
        t.setDaemon(true);
        return t;
    });

    private static final long LOGIN_TIMEOUT_MINUTES = 5;

    /**
     * Production constructor. Bundles are created lazily by {@code factory} on the first session
     * that names a given {@code cwd}, with {@code defaultWorkspaceDir} used when a session omits
     * {@code cwd}. This is the path used by {@link AcpServerMain}.
     */
    public BrokkAcpAgent(Path defaultWorkspaceDir, WorkspaceBundleFactory factory) {
        this.defaultWorkspaceDir = defaultWorkspaceDir.toAbsolutePath().normalize();
        this.bundleFactory = factory;

        var defaults = loadAcpDefaults();
        this.defaultModelId = defaults.defaultModel;
        this.defaultReasoningLevel = defaults.defaultReasoning;
        this.defaultCodeModelId = defaults.defaultCodeModel;
        this.defaultCodeReasoningLevel = defaults.defaultCodeReasoning;
        logger.info(
                "ACP defaults loaded: model={}, reasoning={}, codeModel={}, codeReasoning={}, defaultRoot={}",
                defaultModelId,
                defaultReasoningLevel,
                defaultCodeModelId,
                defaultCodeReasoningLevel,
                this.defaultWorkspaceDir);
    }

    /**
     * Test/legacy constructor: pre-populates a single bundle bound to the given {@code cm}'s
     * project root. The factory is a no-op fallback that throws if a session asks for a different
     * cwd — production callers should use the {@code (Path, WorkspaceBundleFactory)} constructor.
     */
    public BrokkAcpAgent(ContextManager cm, JobRunner jobRunner, JobStore jobStore) {
        this(cm.getProject().getRoot(), root -> {
            throw new UnsupportedOperationException(
                    "Legacy BrokkAcpAgent constructor cannot create new bundles for " + root);
        });
        var root = this.defaultWorkspaceDir;
        bundlesByRoot.put(root, new WorkspaceBundle(cm, jobRunner, jobStore, root));
    }

    public void setSessionUpdateSender(SessionUpdateSender sessionUpdateSender) {
        this.sessionUpdateSender = sessionUpdateSender;
    }

    public void setCompatibilityWarning(@Nullable String warning) {
        this.compatibilityWarning = warning;
    }

    /**
     * Returns the bundle for {@code requestedCwd}, creating it on first use. Defaults to the
     * configured {@link #defaultWorkspaceDir} when {@code requestedCwd} is null/blank. Bundles are
     * cached by canonical absolute path; calls for the same cwd reuse the same bundle.
     */
    private WorkspaceBundle bundleFor(@Nullable String requestedCwd) {
        Path canonical;
        if (requestedCwd == null || requestedCwd.isBlank()) {
            canonical = defaultWorkspaceDir;
        } else {
            canonical = Path.of(requestedCwd).toAbsolutePath().normalize();
        }
        return bundlesByRoot.computeIfAbsent(canonical, this::createAndRegisterBundle);
    }

    private WorkspaceBundle createAndRegisterBundle(Path root) {
        logger.info("Creating workspace bundle for {}", root);
        var bundle = bundleFactory.create(root);
        if (listenerActive) {
            attachListener(bundle);
        }
        return bundle;
    }

    private void attachListener(WorkspaceBundle bundle) {
        IAppContextManager.ContextListener listener = ctx -> onContextChanged(bundle, ctx);
        listenersByRoot.put(bundle.root(), listener);
        bundle.cm().addContextListener(listener);
    }

    /** Returns the bundle that owns {@code sessionId}, or throws if the session is unknown. */
    private WorkspaceBundle bundleForSession(String sessionId) {
        var bundle = bundleBySession.get(sessionId);
        if (bundle == null) {
            throw new IllegalArgumentException("Unknown session: " + sessionId);
        }
        return bundle;
    }

    /** Returns the bundle for {@code sessionId}, or empty if no session has registered yet. */
    private Optional<WorkspaceBundle> tryBundleForSession(String sessionId) {
        return Optional.ofNullable(bundleBySession.get(sessionId));
    }

    /** Registers a listener that emits {@code plan} session updates whenever the task list changes. */
    public void start() {
        if (listenerActive) {
            return;
        }
        listenerActive = true;
        for (var bundle : bundlesByRoot.values()) {
            attachListener(bundle);
        }
    }

    /** Removes the task-list listener from every bundle. Idempotent. */
    public void stop() {
        if (!listenerActive) {
            return;
        }
        listenerActive = false;
        for (var entry : listenersByRoot.entrySet()) {
            var bundle = bundlesByRoot.get(entry.getKey());
            if (bundle != null) {
                bundle.cm().removeContextListener(entry.getValue());
            }
        }
        listenersByRoot.clear();
    }

    /**
     * Clears all per-session state. Called from {@link BrokkAcpRuntime#close()} so that long-lived
     * processes don't accumulate maps for sessions whose ACP clients disconnected without sending
     * {@code session/close}. Idempotent.
     */
    public void clearAllSessions() {
        modeBySession.clear();
        modelBySession.clear();
        codeModelBySession.clear();
        reasoningBySession.clear();
        activeJobBySession.clear();
        stickyPermissionsBySession.clear();
        permissionModeBySession.clear();
        sandboxDisabledBySession.clear();
        mcpServersBySession.clear();
        rejectedMcpServersBySession.clear();
        lastTaskListBySession.clear();
        pendingPlanBySession.clear();
        sessionsOnFallbackCatalog.clear();
        bundleBySession.clear();
        var pending = pendingLogin.getAndSet(null);
        if (pending != null) {
            pending.timeout().cancel(false);
            MainProject.removeSettingsChangeListener(pending.listener());
        }
    }

    /**
     * Closes every workspace bundle (cancels active jobs, closes the ContextManager). Called from
     * {@link AcpServerMain}'s shutdown hook.
     */
    public void closeAllBundles() {
        stop();
        for (var bundle : bundlesByRoot.values()) {
            try {
                bundle.jobRunner().shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down JobRunner for {}", bundle.root(), e);
            }
            try {
                bundle.cm().close();
            } catch (Exception e) {
                logger.warn("Error closing ContextManager for {}", bundle.root(), e);
            }
        }
        bundlesByRoot.clear();
    }

    /** Snapshot of currently-loaded bundles, used by the runtime to advertise project state. */
    public Set<Path> activeBundleRoots() {
        return Set.copyOf(bundlesByRoot.keySet());
    }

    private void onContextChanged(WorkspaceBundle bundle, Context newCtx) {
        var sender = sessionUpdateSender;
        if (sender == null) {
            return;
        }
        // Capture the current session id at listener-fire time. Note: ContextManager has no
        // session-of-origin on Context, so this is a best-effort attribution. The dedup against
        // lastTaskListBySession below makes mis-attribution self-healing on the next change.
        var sessionId = bundle.cm().getCurrentSessionId().toString();
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
        var pending = pendingPlanBySession.computeIfAbsent(sessionId, k -> new AtomicReference<>());
        var displaced = pending.getAndSet(data);
        if (displaced != null) {
            // A flush is already running; it will pick up our value before sending.
            return;
        }
        LoggingFuture.runVirtual(() -> drainPendingPlan(sessionId, sender, pending));
    }

    private void drainPendingPlan(
            String sessionId, SessionUpdateSender sender, AtomicReference<TaskList.TaskListData> pending) {
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

    public AcpProtocol.NewSessionResponseExt newSession(AcpSchema.NewSessionRequest request) {
        logger.info("ACP new session, cwd={}", request.cwd());
        var bundle = bundleFor(request.cwd());

        // Create session in this bundle's ContextManager and use its UUID as the ACP session ID
        bundle.cm().createSessionAsync("ACP Session").join();
        var sessionId = bundle.cm().getCurrentSessionId().toString();
        bundleBySession.put(sessionId, bundle);

        modeBySession.put(sessionId, "LUTZ");
        reasoningBySession.put(sessionId, defaultReasoningLevel);
        permissionModeBySession.put(sessionId, PermissionMode.DEFAULT);
        applySessionMcpServers(sessionId, request.mcpServers());

        var modeState = new AcpSchema.SessionModeState("LUTZ", AVAILABLE_MODES);
        var startupState =
                buildStartupSessionState(sessionId, modelBySession.getOrDefault(sessionId, defaultModelId), null);
        var meta = buildVariantMeta(sessionId);

        scheduleAvailableCommandsUpdate(sessionId);

        return new AcpProtocol.NewSessionResponseExt(
                sessionId, modeState, startupState.modelState(), startupState.configOptions(), meta);
    }

    public AcpProtocol.LoadSessionResponseExt loadSession(AcpSchema.LoadSessionRequest request) {
        logger.info("ACP load session {} cwd={}", request.sessionId(), request.cwd());
        var sessionId = request.sessionId();
        var bundle = bundleFor(request.cwd());

        // Switch to the requested session in this bundle's ContextManager, or fail if not present
        var sessions = bundle.cm().getProject().getSessionManager().listSessions();
        var target = sessions.stream()
                .filter(s -> s.id().toString().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown session: " + sessionId));
        bundle.cm().switchSessionAsync(target.id()).join();
        bundleBySession.put(sessionId, bundle);

        modeBySession.putIfAbsent(sessionId, "LUTZ");
        reasoningBySession.putIfAbsent(sessionId, defaultReasoningLevel);
        permissionModeBySession.putIfAbsent(sessionId, PermissionMode.DEFAULT);
        applySessionMcpServers(sessionId, request.mcpServers());

        var modeState = new AcpSchema.SessionModeState(modeBySession.getOrDefault(sessionId, "LUTZ"), AVAILABLE_MODES);
        var startupState =
                buildStartupSessionState(sessionId, modelBySession.getOrDefault(sessionId, defaultModelId), null);
        var meta = buildVariantMeta(sessionId);

        // Schedule conversation replay and commands advertisement after response is sent
        scheduleConversationReplay(sessionId);
        scheduleAvailableCommandsUpdate(sessionId);

        return new AcpProtocol.LoadSessionResponseExt(
                modeState, startupState.modelState(), startupState.configOptions(), meta);
    }

    public AcpProtocol.ResumeSessionResponse resumeSession(AcpProtocol.ResumeSessionRequest request) {
        logger.info("ACP resume session {} cwd={}", request.sessionId(), request.cwd());
        var sessionId = request.sessionId();
        var bundle = bundleFor(request.cwd());
        switchToKnownSession(bundle, sessionId);
        bundleBySession.put(sessionId, bundle);

        modeBySession.putIfAbsent(sessionId, "LUTZ");
        reasoningBySession.putIfAbsent(sessionId, defaultReasoningLevel);
        permissionModeBySession.putIfAbsent(sessionId, PermissionMode.DEFAULT);
        applySessionMcpServers(sessionId, request.mcpServers());

        var modeState = new AcpSchema.SessionModeState(modeBySession.getOrDefault(sessionId, "LUTZ"), AVAILABLE_MODES);
        var startupState =
                buildStartupSessionState(sessionId, modelBySession.getOrDefault(sessionId, defaultModelId), null);
        var meta = buildVariantMeta(sessionId);

        scheduleAvailableCommandsUpdate(sessionId);
        return new AcpProtocol.ResumeSessionResponse(
                modeState, startupState.modelState(), startupState.configOptions(), meta);
    }

    public AcpProtocol.ListSessionsResponse listSessions(AcpProtocol.ListSessionsRequest request) {
        logger.info("ACP list sessions cwd={}", request.cwd());
        // Listing is read-only: return empty when no bundle has been materialized for this cwd
        // yet (rather than auto-creating one — a client probing a path shouldn't trigger project
        // initialization).
        Path canonical = (request.cwd() == null || request.cwd().isBlank())
                ? defaultWorkspaceDir
                : Path.of(request.cwd()).toAbsolutePath().normalize();
        var bundle = bundlesByRoot.get(canonical);
        if (bundle == null) {
            return new AcpProtocol.ListSessionsResponse(List.of(), null, null);
        }
        var rootStr = bundle.root().toString();
        var sessions = bundle.cm().getProject().getSessionManager().listSessions().stream()
                .map(session -> {
                    var stats = bundle.cm().getProject().getSessionManager().countSessionStats(session.id());
                    var meta = Map.<String, Object>of(
                            "brokk",
                            Map.of(
                                    "aiResponses", stats.aiResponses(),
                                    "totalTasks", stats.tasks().total(),
                                    "incompleteTasks", stats.tasks().incomplete()));
                    return new AcpProtocol.SessionInfo(
                            session.id().toString(),
                            rootStr,
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
        var bundle = tryBundleForSession(sessionId);
        var jobId = activeJobBySession.remove(sessionId);
        if (jobId != null) {
            bundle.ifPresent(b -> b.jobRunner().cancel(jobId));
        }
        modeBySession.remove(sessionId);
        modelBySession.remove(sessionId);
        codeModelBySession.remove(sessionId);
        codeReasoningBySession.remove(sessionId);
        reasoningBySession.remove(sessionId);
        stickyPermissionsBySession.remove(sessionId);
        sessionsOnFallbackCatalog.remove(sessionId);
        permissionModeBySession.remove(sessionId);
        sandboxDisabledBySession.remove(sessionId);
        mcpServersBySession.remove(sessionId);
        rejectedMcpServersBySession.remove(sessionId);
        lastTaskListBySession.remove(sessionId);
        pendingPlanBySession.remove(sessionId);
        bundleBySession.remove(sessionId);
        cancelPendingLoginIfOwnedBy(sessionId);
        return new AcpProtocol.CloseSessionResponse(null);
    }

    /**
     * If a Codex login attempt is in flight and owned by {@code sessionId}, unregister its listener
     * and cancel its timeout. The OAuth callback server itself is left alone — if a callback still
     * arrives later it will flip the global flag harmlessly with no listener to notify.
     */
    private void cancelPendingLoginIfOwnedBy(String sessionId) {
        var pending = pendingLogin.get();
        if (pending != null && pending.sessionId().equals(sessionId)) {
            cancelPending(pending);
        }
    }

    public AcpProtocol.ForkSessionResponse forkSession(AcpProtocol.ForkSessionRequest request) {
        logger.info("ACP fork session {} cwd={}", request.sessionId(), request.cwd());
        var bundle = bundleFor(request.cwd());
        // Forks must originate from a session in the same bundle as the requested cwd.
        var existingBundle = bundleBySession.get(request.sessionId());
        if (existingBundle != null && !existingBundle.root().equals(bundle.root())) {
            throw new IllegalArgumentException("Cannot fork session " + request.sessionId()
                    + " across workspace roots: " + existingBundle.root() + " vs " + bundle.root());
        }

        var target = findSession(bundle, request.sessionId());
        try {
            var copied =
                    bundle.cm().getProject().getSessionManager().copySession(target.id(), "Fork of " + target.name());
            var forkSessionId = copied.id().toString();
            bundle.cm().switchSessionAsync(copied.id()).join();
            bundleBySession.put(forkSessionId, bundle);

            modeBySession.put(forkSessionId, modeBySession.getOrDefault(request.sessionId(), "LUTZ"));
            var model = modelBySession.get(request.sessionId());
            if (model != null) {
                modelBySession.put(forkSessionId, model);
            }
            reasoningBySession.put(
                    forkSessionId, reasoningBySession.getOrDefault(request.sessionId(), defaultReasoningLevel));
            permissionModeBySession.put(
                    forkSessionId, permissionModeBySession.getOrDefault(request.sessionId(), PermissionMode.DEFAULT));
            // sandboxDisabledBySession is intentionally NOT inherited: a fork resets to
            // sandbox-enabled. Treating fork as a fresh session for the kernel-sandbox toggle is
            // the safer default; users who want the parent's looser posture can re-issue
            // `/sandbox off` in the fork.
            // Inherit MCP servers from parent unless the fork request supplies a fresh list.
            if (request.mcpServers() != null) {
                applySessionMcpServers(forkSessionId, request.mcpServers());
            } else {
                var parentServers = mcpServersBySession.get(request.sessionId());
                if (parentServers != null) {
                    mcpServersBySession.put(forkSessionId, List.copyOf(parentServers));
                }
            }

            var parentCodeModel = codeModelBySession.getOrDefault(request.sessionId(), "");
            var parentCodeReasoning =
                    codeReasoningBySession.getOrDefault(request.sessionId(), defaultCodeReasoningLevel);
            var modeState =
                    new AcpSchema.SessionModeState(modeBySession.getOrDefault(forkSessionId, "LUTZ"), AVAILABLE_MODES);
            var startupState = buildStartupSessionState(
                    forkSessionId,
                    modelBySession.getOrDefault(forkSessionId, defaultModelId),
                    new NormalizedModelSelection(parentCodeModel, parentCodeReasoning));
            var meta = buildVariantMeta(forkSessionId);

            scheduleAvailableCommandsUpdate(forkSessionId);
            return new AcpProtocol.ForkSessionResponse(
                    forkSessionId, modeState, startupState.modelState(), startupState.configOptions(), meta);
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

        var bundleOpt = tryBundleForSession(sessionId);
        if (bundleOpt.isEmpty()) {
            promptContext.sendMessage("Error: unknown session " + sessionId);
            return AcpSchema.PromptResponse.endTurn();
        }
        var bundle = bundleOpt.get();

        // If the connecting client is too old, short-circuit before invoking any LLM: the user's
        // prompt will not be honored, but they'll see why. We have to do this in prompt() rather
        // than newSession() because JetBrains AI Assistant silently drops agent_message_chunk
        // events that arrive outside of an active turn.
        var warning = compatibilityWarning;
        if (warning != null) {
            promptContext.sendMessage("**[Brokk]** " + warning);
            return AcpSchema.PromptResponse.endTurn();
        }

        var slashHandled = handleSlashCommand(sessionId, text, bundle, promptContext);
        if (slashHandled) {
            return AcpSchema.PromptResponse.endTurn();
        }

        // Switch to the requested session so the job executes against the correct state
        var sessions = bundle.cm().getProject().getSessionManager().listSessions();
        var target = sessions.stream()
                .filter(s -> s.id().toString().equals(sessionId))
                .findFirst();
        if (target.isEmpty()) {
            promptContext.sendMessage("Error: unknown session " + sessionId);
            return AcpSchema.PromptResponse.endTurn();
        }
        bundle.cm().switchSessionAsync(target.get().id()).join();

        // Best-effort auto-rename for sessions still on a placeholder name; uses the user's prompt.
        try {
            bundle.cm()
                    .getProject()
                    .getSessionManager()
                    .autoRenameIfDefault(target.get().id(), text);
        } catch (Exception e) {
            logger.warn("Failed to auto-rename ACP session {}: {}", sessionId, e.getMessage());
        }

        // Materialize @-mentioned files (resource_link / embedded resource blocks) into the
        // workspace so every mode -- especially ASK, which has no tool loop -- can see them.
        // Mirrors brokk-code/brokk_code/acp_server.py:extract_resource_file_paths +
        // executor.add_context_files. Failures are logged but never abort the prompt.
        attachPromptResources(request.prompt(), bundle);

        // Build JobSpec with reasoning levels from session state
        var mode = modeBySession.getOrDefault(sessionId, "LUTZ");
        var model = modelBySession.getOrDefault(sessionId, "");
        var reasoning = reasoningBySession.getOrDefault(sessionId, DEFAULT_REASONING_LEVEL);
        var tags = new HashMap<String, String>();
        tags.put("mode", mode);

        var codeSelection = normalizeCodeModelSelection(sessionId, preferredCodeBaseModel(sessionId), null, true);
        var codeModel = codeSelection.baseModel();
        var codeReasoning = codeSelection.reasoning();

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
                codeReasoning, // reasoningLevelCode
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
            bundle.jobStore().createOrGetJob(jobId, spec);

            // Run using the ACP console -- JobRunner handles cm.setIo swap internally
            bundle.jobRunner().runAsync(jobId, spec, acpConsole).join();
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

    /** Returns the active permission mode for {@code sessionId}, defaulting to {@code DEFAULT}. */
    public PermissionMode permissionModeFor(String sessionId) {
        return permissionModeBySession.getOrDefault(sessionId, PermissionMode.DEFAULT);
    }

    /**
     * Returns whether the kernel sandbox has been disabled for {@code sessionId} via the
     * {@code /sandbox off} slash command. Defaults to {@code false} (sandbox enabled). Affects
     * shell commands only; other tools do not consult {@link ai.brokk.util.SandboxPolicy}.
     */
    public boolean isSandboxDisabledFor(String sessionId) {
        return sandboxDisabledBySession.getOrDefault(sessionId, false);
    }

    /**
     * Sets the per-session sandbox-disabled flag. Used by the {@code /sandbox} slash command;
     * not exposed via {@code session/set_config_option} on purpose (slash-only by design).
     */
    void setSandboxDisabledFor(String sessionId, boolean disabled) {
        if (disabled) {
            sandboxDisabledBySession.put(sessionId, true);
        } else {
            sandboxDisabledBySession.remove(sessionId);
        }
    }

    /**
     * Handler for {@code session/set_config_option}. Routes on {@code configId} to either the
     * permission-mode store or the existing {@link #setMode} logic for behavior modes. Returns the
     * updated set of dropdowns so clients can refresh their UI.
     */
    public AcpProtocol.SetSessionConfigOptionResponse setSessionConfigOption(
            AcpProtocol.SetSessionConfigOptionRequest request) {
        var sessionId = request.sessionId();
        var configId = request.configId();
        var value = request.value();
        logger.info("ACP set config_option session={} config={} value={}", sessionId, configId, value);

        switch (configId) {
            case "permission_mode" -> {
                var parsed = PermissionMode.parse(value);
                if (parsed.isEmpty()) {
                    throw new IllegalArgumentException("Unknown permission mode '" + value
                            + "'. Supported: default, acceptEdits, readOnly, bypassPermissions");
                }
                permissionModeBySession.put(sessionId, parsed.get());
            }
            case "behavior_mode" -> {
                var validMode = AVAILABLE_MODES.stream().anyMatch(m -> m.id().equals(value));
                if (!validMode) {
                    throw new IllegalArgumentException("Unknown behavior mode '" + value
                            + "'. Supported: "
                            + AVAILABLE_MODES.stream()
                                    .map(AcpSchema.SessionMode::id)
                                    .toList());
                }
                setMode(new AcpSchema.SetSessionModeRequest(sessionId, value));
            }
            case "model_selection" -> setModel(new AcpSchema.SetSessionModelRequest(sessionId, value));
            case "code_model_selection" -> setCodeModel(sessionId, value);
            default ->
                throw new IllegalArgumentException("Unknown configId '" + configId
                        + "'. Supported: permission_mode, behavior_mode, model_selection, code_model_selection");
        }
        return new AcpProtocol.SetSessionConfigOptionResponse(buildConfigOptions(sessionId), null);
    }

    /**
     * Builds the dropdown advertisement for one session. Behavior, permission, planner-model, and
     * code-model selectors are emitted via {@code configOptions} — IntelliJ hides the legacy
     * {@code modes} / {@code models} channels once anything appears here, so every selector must
     * come through this channel or it vanishes from the UI.
     *
     * <p>The planner-model selector remains a flat {@code name/variant} list because IntelliJ
     * doesn't render the spec'd {@code thought_level} category yet. When it does, we should split
     * planner model and reasoning into adjacent dropdowns to match the legacy two-picker UX.
     */
    private List<AcpProtocol.SessionConfigOption> buildConfigOptions(String sessionId) {
        var state = resolveSessionModelState(sessionId, modelBySession.getOrDefault(sessionId, defaultModelId), null);
        return buildConfigOptions(sessionId, state.availableModels(), state.plannerSelection(), state.codeSelection());
    }

    private List<AcpProtocol.SessionConfigOption> buildConfigOptions(
            String sessionId,
            Map<String, String> availableModels,
            NormalizedModelSelection plannerSelection,
            NormalizedModelSelection codeSelection) {
        var currentBehavior = modeBySession.getOrDefault(sessionId, "LUTZ");
        return List.of(
                behaviorConfigOption(currentBehavior),
                permissionConfigOption(permissionModeFor(sessionId)),
                modelConfigOption(sessionId, availableModels, plannerSelection),
                codeModelConfigOption(sessionId, availableModels, codeSelection));
    }

    private AcpProtocol.SessionConfigOption modelConfigOption(
            String sessionId, Map<String, String> availableModels, NormalizedModelSelection selection) {
        var options = buildModelSelectOptions(sessionId, availableModels);
        if (options.isEmpty()) {
            options = List.of(new AcpProtocol.SessionConfigSelectOption("default", "Default Model", null));
        }

        var currentValue = formatModelIdWithVariant(selection.baseModel(), selection.reasoning());
        if (currentValue.isBlank()) {
            currentValue = options.getFirst().value();
        }

        return AcpProtocol.SessionConfigOption.select(
                "model_selection",
                "Model",
                "Selects the LLM and reasoning effort for this session.",
                "model",
                currentValue,
                options);
    }

    private AcpProtocol.SessionConfigOption codeModelConfigOption(
            String sessionId, Map<String, String> availableModels, NormalizedModelSelection selection) {
        var options = buildModelSelectOptions(sessionId, availableModels);
        if (options.isEmpty()) {
            options = List.of(new AcpProtocol.SessionConfigSelectOption("default", "Default Code Model", null));
        }

        var currentValue = formatModelIdWithVariant(selection.baseModel(), selection.reasoning());
        if (currentValue.isBlank()) {
            currentValue = options.getFirst().value();
        }

        return AcpProtocol.SessionConfigOption.select(
                "code_model_selection",
                "Code Model",
                "Selects the code model and reasoning effort for this session.",
                "model",
                currentValue,
                options);
    }

    /**
     * Resolves the code-model state for {@code sessionId}: validates {@code requestedBaseModel}
     * against the live catalog (falling back to {@code DEFAULT_CODE_MODEL}, then the planner
     * model, then the first sorted entry), then sanitizes the requested or persisted reasoning
     * level against the chosen model's capabilities. Mirrors {@link #normalizeModelSelection}
     * for the code-model side.
     */
    private NormalizedModelSelection normalizeCodeModelSelection(
            String sessionId,
            String requestedBaseModel,
            @Nullable String requestedReasoning,
            boolean persistResolvedState) {
        var service = bundleForSession(sessionId).cm().getService();
        var availableModels = resolveAvailableModels(sessionId, service, MODEL_DISCOVERY_RECOVERY_ATTEMPTS);
        var plannerBaseModel = modelBySession.getOrDefault(sessionId, defaultModelId);
        return normalizeCodeModelSelection(
                sessionId,
                availableModels,
                service,
                plannerBaseModel,
                requestedBaseModel,
                requestedReasoning,
                persistResolvedState);
    }

    private NormalizedModelSelection normalizeCodeModelSelection(
            String sessionId,
            Map<String, String> availableModels,
            AbstractService service,
            String plannerBaseModel,
            String requestedBaseModel,
            @Nullable String requestedReasoning,
            boolean persistResolvedState) {
        var baseModel = resolveCodeBaseModel(availableModels, requestedBaseModel, plannerBaseModel);
        var sourceReasoning = requestedReasoning != null
                ? requestedReasoning
                : codeReasoningBySession.getOrDefault(sessionId, defaultCodeReasoningLevel);
        var reasoning = sanitizeReasoningLevelForModel(baseModel, sourceReasoning, service);
        if (persistResolvedState) {
            codeModelBySession.put(sessionId, baseModel);
            codeReasoningBySession.put(sessionId, reasoning);
        } else {
            codeModelBySession.putIfAbsent(sessionId, baseModel);
            codeReasoningBySession.putIfAbsent(sessionId, reasoning);
            var existingModel = codeModelBySession.get(sessionId);
            var existingReasoning = codeReasoningBySession.get(sessionId);
            assert existingModel != null;
            assert existingReasoning != null;
            if (!existingModel.equals(baseModel)) {
                codeModelBySession.put(sessionId, baseModel);
            }
            if (!existingReasoning.equals(reasoning)) {
                codeReasoningBySession.put(sessionId, reasoning);
            }
        }
        return new NormalizedModelSelection(baseModel, reasoning);
    }

    /**
     * Returns the existing per-session code-model selection if any, otherwise the planner model.
     * Used to seed {@link #normalizeCodeModelSelection} when a session resumes/loads — the
     * normalize step then validates this preference against the live catalog.
     */
    private String preferredCodeBaseModel(String sessionId) {
        var preferred = codeModelBySession.getOrDefault(sessionId, "");
        if (preferred.isEmpty()) {
            preferred = defaultCodeModelId;
        }
        if (preferred.isEmpty()) {
            preferred = modelBySession.getOrDefault(sessionId, defaultModelId);
        }
        return preferred;
    }

    private String resolveCodeBaseModel(
            Map<String, String> availableModels, String requestedModel, String plannerBaseModel) {
        var stripped = requestedModel.strip();
        if (!stripped.isEmpty() && availableModels.containsKey(stripped)) {
            return stripped;
        }
        if (availableModels.containsKey(DEFAULT_CODE_MODEL)) {
            return DEFAULT_CODE_MODEL;
        }
        var plannerModel = plannerBaseModel.strip();
        if (!plannerModel.isEmpty() && availableModels.containsKey(plannerModel)) {
            return plannerModel;
        }
        return firstSortedModel(availableModels).orElse("default");
    }

    private static AcpProtocol.SessionConfigOption behaviorConfigOption(String currentValue) {
        var options = AVAILABLE_MODES.stream()
                .map(m -> new AcpProtocol.SessionConfigSelectOption(m.id(), m.name(), m.description()))
                .toList();
        return AcpProtocol.SessionConfigOption.select(
                "behavior_mode",
                "Mode",
                "Controls Brokk's overall behavior style for this session.",
                currentValue,
                options);
    }

    private static AcpProtocol.SessionConfigOption permissionConfigOption(PermissionMode current) {
        var options = List.of(
                new AcpProtocol.SessionConfigSelectOption(
                        "default", "Default", "Ask for permission before each tool call"),
                new AcpProtocol.SessionConfigSelectOption(
                        "acceptEdits", "Accept Edits", "Auto-allow edits; ask for everything else"),
                new AcpProtocol.SessionConfigSelectOption(
                        "readOnly", "Read-only", "Refuse every edit, deletion, move, or shell command"),
                new AcpProtocol.SessionConfigSelectOption(
                        "bypassPermissions",
                        "Bypass Permissions",
                        "Allow all tool calls without prompting (use with care)"));
        return AcpProtocol.SessionConfigOption.select(
                "permission_mode",
                "Permission",
                "Controls which tool calls require user approval.",
                current.asString(),
                options);
    }

    public AcpSchema.SetSessionModelResponse setModel(AcpSchema.SetSessionModelRequest request) {
        var sessionId = request.sessionId();
        var modelId = request.modelId();
        logger.info("ACP set model session={} model={}", sessionId, modelId);

        // Parse "model/variant" format: split on last "/" and check if last segment is a reasoning level
        var parsed = parseModelSelection(modelId);
        var service = bundleForSession(sessionId).cm().getService();
        var availableModels = resolveAvailableModels(sessionId, service, MODEL_DISCOVERY_RECOVERY_ATTEMPTS);
        var requestedBaseModel = parsed.baseModel();
        var requestedReasoning = parsed.reasoning() != null
                ? parsed.reasoning()
                : reasoningBySession.getOrDefault(sessionId, DEFAULT_REASONING_LEVEL);
        var normalized = normalizeModelSelection(sessionId, availableModels, service, requestedBaseModel, true);
        var normalizedReasoning = sanitizeReasoningLevelForModel(normalized.baseModel(), requestedReasoning, service);

        modelBySession.put(sessionId, normalized.baseModel());
        reasoningBySession.put(sessionId, normalizedReasoning);

        logger.info(
                "ACP set model parsed: base={} reasoning={} normalizedBase={} normalizedReasoning={}",
                parsed.baseModel(),
                parsed.reasoning(),
                normalized.baseModel(),
                normalizedReasoning);

        // Promote the resolved selection to the catalog-wide defaults so subsequent new/loaded
        // sessions pick up the user's choice — both in this JVM and after restart via persistence.
        defaultModelId = normalized.baseModel();
        defaultReasoningLevel = normalizedReasoning;
        persistAllDefaults();

        refreshCatalogIfFallback(sessionId);

        return new AcpSchema.SetSessionModelResponse();
    }

    /**
     * Code-model counterpart of {@link #setModel}. Normalises the raw {@code "model"} or
     * {@code "model/variant"} string against the live catalog and the model's reasoning
     * capabilities, then promotes the resolved selection to the catalog-wide defaults so
     * subsequent new/loaded sessions — including after a server restart via persistence —
     * pick up the user's choice instead of falling back to the planner model.
     */
    private void setCodeModel(String sessionId, String value) {
        var parsed = parseModelSelection(value);
        var normalized = normalizeCodeModelSelection(sessionId, parsed.baseModel(), parsed.reasoning(), true);
        defaultCodeModelId = normalized.baseModel();
        var normalizedReasoning = normalized.reasoning();
        if (normalizedReasoning != null) {
            defaultCodeReasoningLevel = normalizedReasoning;
        }
        persistAllDefaults();
    }

    /**
     * If {@code sessionId} was last seen on the {@code BASE_MODEL_IDS} fallback catalog, re-attempts
     * live discovery. Clears the fallback flag on success so the next {@link #buildModelState}
     * returns the real catalog. Best-effort: silently no-ops if discovery still fails.
     */
    private void refreshCatalogIfFallback(String sessionId) {
        if (!sessionsOnFallbackCatalog.contains(sessionId)) {
            return;
        }
        var service = bundleForSession(sessionId).cm().getService();
        var fresh = discoverModelsWithRetry(service, MODEL_DISCOVERY_RECOVERY_ATTEMPTS);
        if (!fresh.isEmpty()) {
            sessionsOnFallbackCatalog.remove(sessionId);
            logger.info("Model catalog recovered for session {} after fallback ({} models)", sessionId, fresh.size());
        }
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
                                .collect(Collectors.toMap(AcpSchema.EnvVariable::name, AcpSchema.EnvVariable::value));
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

    /**
     * Looks up a cross-session persisted permission verdict for {@code (toolName, argMatch)} in
     * the rules file owned by this session's project. Returns empty when the session has no
     * registered bundle, or when no rule matches. Mirrors {@link #stickyPermissionFor} but persists
     * across restarts.
     */
    @Blocking
    public Optional<PermissionVerdict> persistentPermissionFor(String sessionId, String toolName, String argMatch) {
        var bundle = tryBundleForSession(sessionId);
        if (bundle.isEmpty()) {
            return Optional.empty();
        }
        return rulesFor(bundle.get().root()).lookup(toolName, argMatch);
    }

    /**
     * Persists a permission verdict for {@code (toolName, argMatch)} to disk under the session's
     * project root. Failure to write is logged and swallowed — the verdict still lives in the
     * in-memory sticky cache for the rest of this session.
     */
    @Blocking
    public void rememberPermissionPersistently(
            String sessionId, String toolName, String argMatch, PermissionVerdict verdict) {
        var bundle = tryBundleForSession(sessionId);
        if (bundle.isEmpty()) {
            logger.warn("rememberPermissionPersistently called for unknown session {}; skipping disk write", sessionId);
            return;
        }
        var root = bundle.get().root();
        var store = rulesFor(root);
        store.put(toolName, argMatch, verdict);
        try {
            store.save(root);
        } catch (IOException e) {
            logger.warn(
                    "Failed to persist permission rule ({}, {}, {}) under {}: {}",
                    toolName,
                    argMatch,
                    verdict,
                    root,
                    e.getMessage());
        }
    }

    /**
     * Returns the per-project rules store, lazily loading from disk on first access. Cached so
     * subsequent lookups don't re-parse the file on every prompt.
     */
    private PermissionRules rulesFor(Path projectRoot) {
        return rulesByProjectRoot.computeIfAbsent(projectRoot, PermissionRules::loadForProject);
    }

    public void cancel(AcpSchema.CancelNotification notification) {
        var sessionId = notification.sessionId();
        logger.info("ACP cancel session={}", sessionId);
        var jobId = activeJobBySession.get(sessionId);
        if (jobId == null) {
            logger.info("ACP cancel: no active job for session {}", sessionId);
            return;
        }
        tryBundleForSession(sessionId)
                .ifPresentOrElse(
                        b -> b.jobRunner().cancel(jobId),
                        () -> logger.warn("ACP cancel: no bundle for session {}", sessionId));
    }

    private void switchToKnownSession(WorkspaceBundle bundle, String sessionId) {
        var target = findSession(bundle, sessionId);
        bundle.cm().switchSessionAsync(target.id()).join();
    }

    private SessionInfo findSession(WorkspaceBundle bundle, String sessionId) {
        return bundle.cm().getProject().getSessionManager().listSessions().stream()
                .filter(s -> s.id().toString().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown session: " + sessionId));
    }

    // ---- Model state with reasoning variants ----

    private StartupSessionState buildStartupSessionState(
            String sessionId,
            String requestedPlannerBaseModel,
            @Nullable NormalizedModelSelection requestedCodeSelection) {
        var state = resolveSessionModelState(sessionId, requestedPlannerBaseModel, requestedCodeSelection);
        return new StartupSessionState(
                buildModelState(sessionId, state.availableModels(), state.plannerSelection()),
                buildConfigOptions(
                        sessionId, state.availableModels(), state.plannerSelection(), state.codeSelection()));
    }

    private Map<String, String> resolveAvailableModels(String sessionId, AbstractService service, int attempts) {
        var availableModels = discoverModelsWithRetry(service, attempts);
        if (availableModels.isEmpty()) {
            availableModels = ModelProperties.BASE_MODEL_IDS.stream().collect(Collectors.toMap(id -> id, id -> id));
            sessionsOnFallbackCatalog.add(sessionId);
            logger.warn("Model discovery returned no models; using BASE_MODEL_IDS fallback for session {}", sessionId);
        } else {
            sessionsOnFallbackCatalog.remove(sessionId);
        }
        return availableModels;
    }

    private ResolvedSessionModelState resolveSessionModelState(
            String sessionId,
            String requestedPlannerBaseModel,
            @Nullable NormalizedModelSelection requestedCodeSelection) {
        var service = bundleForSession(sessionId).cm().getService();
        var availableModels = resolveAvailableModels(sessionId, service, MODEL_DISCOVERY_INITIAL_ATTEMPTS);

        revalidatePersistedDefaults(sessionId, availableModels, service);

        var plannerSelection =
                normalizeModelSelection(sessionId, availableModels, service, requestedPlannerBaseModel, true);
        var preferredCodeSelection = requestedCodeSelection != null
                ? requestedCodeSelection
                : new NormalizedModelSelection(preferredCodeBaseModel(sessionId), null);
        var codeSelection = normalizeCodeModelSelection(
                sessionId,
                availableModels,
                service,
                plannerSelection.baseModel(),
                preferredCodeSelection.baseModel(),
                preferredCodeSelection.reasoning(),
                true);

        return new ResolvedSessionModelState(availableModels, plannerSelection, codeSelection);
    }

    private AcpSchema.SessionModelState buildModelState(
            String sessionId, Map<String, String> availableModels, NormalizedModelSelection selection) {
        var models = buildModelStateOptions(sessionId, availableModels);
        var currentModelId = formatModelIdWithVariant(selection.baseModel(), selection.reasoning());
        return new AcpSchema.SessionModelState(currentModelId, models);
    }

    /**
     * Pure decision logic for reasoning-level sanitization. Returns {@code level} unchanged when
     * it is a known id and supported by the model's capabilities; otherwise returns {@code "default"}.
     * Decoupled from {@link AbstractService} so unit tests can exercise every combination cheaply.
     * Mirrors the deleted Python bridge's {@code _sanitize_reasoning_level_for_model}. See #3421.
     */
    static String sanitizeReasoningLevel(String level, boolean supportsEffort, boolean supportsDisable) {
        var normalized = REASONING_LEVEL_IDS.contains(level) ? level : "default";
        if (!supportsEffort && !"default".equals(normalized)) {
            return "default";
        }
        if ("disable".equals(normalized) && !supportsDisable) {
            return "default";
        }
        return normalized;
    }

    /**
     * Sanitizes a reasoning level against a model's reasoning capabilities. A blank model name
     * short-circuits to enum-level normalization (no service lookup); useful when the persisted
     * default has no chosen model yet. See #3421.
     */
    private static String sanitizeReasoningLevelForModel(String modelName, String level, AbstractService service) {
        if (modelName.isBlank()) {
            return REASONING_LEVEL_IDS.contains(level) ? level : "default";
        }
        return sanitizeReasoningLevel(
                level, service.supportsReasoningEffort(modelName), service.supportsReasoningDisable(modelName));
    }

    /**
     * Re-validates the persisted catalog-wide ACP defaults against the live catalog and the
     * chosen model's reasoning capabilities. If a default is stale (model retired upstream,
     * reasoning level not supported by the model), updates the in-memory default and re-persists
     * to {@code ~/.brokk/acp_settings.json}. Mirrors the per-{@code new_session} validation in
     * the deleted Python bridge. See #3421.
     *
     * <p>Skipped when the session is on the {@code BASE_MODEL_IDS} fallback catalog -- writing
     * sanitized defaults based on a fallback would erase the user's configured choice as soon
     * as live discovery comes back.
     */
    private void revalidatePersistedDefaults(
            String sessionId, Map<String, String> availableModels, AbstractService service) {
        if (sessionsOnFallbackCatalog.contains(sessionId) || availableModels.isEmpty()) {
            return;
        }

        var modelChanged = false;
        if (!defaultModelId.isEmpty() && !availableModels.containsKey(defaultModelId)) {
            var fallback = firstSortedModel(availableModels).orElse(null);
            if (fallback != null && !fallback.equals(defaultModelId)) {
                logger.info(
                        "Persisted ACP default model {} not in current catalog; falling back to {}",
                        defaultModelId,
                        fallback);
                defaultModelId = fallback;
                modelChanged = true;
            }
        }

        var sanitized = sanitizeReasoningLevelForModel(defaultModelId, defaultReasoningLevel, service);
        var reasoningChanged = !sanitized.equals(defaultReasoningLevel);
        if (reasoningChanged) {
            logger.info(
                    "Persisted ACP reasoning {} invalid for model {}; falling back to {}",
                    defaultReasoningLevel,
                    defaultModelId,
                    sanitized);
            defaultReasoningLevel = sanitized;
        }

        // An empty defaultCodeModelId is a sentinel meaning "fall back to the planner default" --
        // leave it empty in that case rather than synthesising a value here, since the live
        // resolution path in preferredCodeBaseModel handles the fallback per session.
        var codeModelChanged = false;
        if (!defaultCodeModelId.isEmpty() && !availableModels.containsKey(defaultCodeModelId)) {
            var fallback = firstSortedModel(availableModels).orElse(null);
            if (fallback != null && !fallback.equals(defaultCodeModelId)) {
                logger.info(
                        "Persisted ACP default code model {} not in current catalog; falling back to {}",
                        defaultCodeModelId,
                        fallback);
                defaultCodeModelId = fallback;
                codeModelChanged = true;
            }
        }

        // Sanitise code reasoning against the *resolved* code model: if defaultCodeModelId is
        // empty (meaning "use the planner default"), validate against defaultModelId instead so
        // the persisted code reasoning stays compatible with whatever the code-model fallback
        // resolves to in practice.
        var codeReasoningTarget = defaultCodeModelId.isEmpty() ? defaultModelId : defaultCodeModelId;
        var sanitizedCode = sanitizeReasoningLevelForModel(codeReasoningTarget, defaultCodeReasoningLevel, service);
        var codeReasoningChanged = !sanitizedCode.equals(defaultCodeReasoningLevel);
        if (codeReasoningChanged) {
            logger.info(
                    "Persisted ACP code reasoning {} invalid for model {}; falling back to {}",
                    defaultCodeReasoningLevel,
                    codeReasoningTarget,
                    sanitizedCode);
            defaultCodeReasoningLevel = sanitizedCode;
        }

        if (modelChanged || reasoningChanged || codeModelChanged || codeReasoningChanged) {
            persistAllDefaults();
        }
    }

    /**
     * Calls {@code service.getAvailableModels()} with bounded exponential backoff. Returns the
     * first non-empty result, or an empty map if all attempts return empty. Backoff sequence is
     * {@link #MODEL_DISCOVERY_INITIAL_BACKOFF_MS} doubling per retry.
     */
    private static Map<String, String> discoverModelsWithRetry(AbstractService service, int attempts) {
        long backoff = MODEL_DISCOVERY_INITIAL_BACKOFF_MS;
        Map<String, String> latest = Map.of();
        for (int i = 0; i < attempts; i++) {
            latest = service.getAvailableModels();
            if (!latest.isEmpty()) {
                return latest;
            }
            if (i < attempts - 1) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return latest;
                }
                backoff *= 2;
            }
        }
        return latest;
    }

    /**
     * Returns the first model id from {@code availableModels} in alphabetical order, or empty
     * when the catalog has no entries. Centralises the "pick a deterministic fallback model from
     * the live catalog" pattern used by {@code resolveCodeBaseModel} and
     * {@code revalidatePersistedDefaults} for both planner and code defaults.
     */
    private static Optional<String> firstSortedModel(Map<String, String> availableModels) {
        return availableModels.keySet().stream().sorted().findFirst();
    }

    private Map<String, Object> buildVariantMeta(String sessionId) {
        var baseModel = modelBySession.getOrDefault(sessionId, defaultModelId);
        var reasoning = reasoningBySession.getOrDefault(sessionId, DEFAULT_REASONING_LEVEL);
        var variants = modelVariantsFor(sessionId, baseModel);
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

    private List<String> modelVariantsFor(String sessionId, String modelName) {
        var service = bundleForSession(sessionId).cm().getService();
        if (!service.supportsReasoningEffort(modelName)) {
            return List.of();
        }
        var variants = new ArrayList<>(List.of("low", "medium", "high"));
        if (service.supportsReasoningDisable(modelName)) {
            variants.add("disable");
        }
        return List.copyOf(variants);
    }

    private List<AcpProtocol.SessionConfigSelectOption> buildModelSelectOptions(
            String sessionId, Map<String, String> availableModels) {
        return availableModels.keySet().stream()
                .sorted()
                .<AcpProtocol.SessionConfigSelectOption>mapMulti((name, downstream) -> {
                    downstream.accept(new AcpProtocol.SessionConfigSelectOption(name, name, null));
                    modelVariantsFor(sessionId, name)
                            .forEach(level -> downstream.accept(new AcpProtocol.SessionConfigSelectOption(
                                    name + "/" + level, name + " (" + level + ")", null)));
                })
                .toList();
    }

    private List<AcpSchema.ModelInfo> buildModelStateOptions(String sessionId, Map<String, String> availableModels) {
        return availableModels.keySet().stream()
                .sorted()
                .<AcpSchema.ModelInfo>mapMulti((name, downstream) -> {
                    downstream.accept(new AcpSchema.ModelInfo(name, name, null));
                    modelVariantsFor(sessionId, name)
                            .forEach(level -> downstream.accept(
                                    new AcpSchema.ModelInfo(name + "/" + level, name + " (" + level + ")", null)));
                })
                .toList();
    }

    private NormalizedModelSelection normalizeModelSelection(
            String sessionId,
            Map<String, String> availableModels,
            AbstractService service,
            String preferredBaseModel,
            boolean persistResolvedState) {
        var modelNames = availableModels.keySet();
        var baseModel = preferredBaseModel.strip();
        if ((baseModel.isEmpty() || !modelNames.contains(baseModel)) && !modelNames.isEmpty()) {
            baseModel = modelNames.stream().sorted().findFirst().orElse(baseModel);
        }
        var reasoning = sanitizeReasoningLevelForModel(
                baseModel, reasoningBySession.getOrDefault(sessionId, DEFAULT_REASONING_LEVEL), service);
        if (persistResolvedState) {
            modelBySession.put(sessionId, baseModel);
            reasoningBySession.put(sessionId, reasoning);
        } else {
            modelBySession.putIfAbsent(sessionId, baseModel);
            reasoningBySession.putIfAbsent(sessionId, reasoning);
            var existingModel = modelBySession.get(sessionId);
            var existingReasoning = reasoningBySession.get(sessionId);
            assert existingModel != null;
            assert existingReasoning != null;
            if (!existingModel.equals(baseModel)) {
                modelBySession.put(sessionId, baseModel);
            }
            if (!existingReasoning.equals(reasoning)) {
                reasoningBySession.put(sessionId, reasoning);
            }
        }
        return new NormalizedModelSelection(baseModel, reasoning);
    }

    private static String formatModelIdWithVariant(String baseModel, @Nullable String reasoning) {
        if (reasoning == null || reasoning.isEmpty() || reasoning.equals("default")) {
            return baseModel;
        }
        return baseModel + "/" + reasoning;
    }

    // ---- Model selection parsing ----

    private record ParsedModelSelection(String baseModel, @Nullable String reasoning) {}

    private record NormalizedModelSelection(String baseModel, @Nullable String reasoning) {}

    private record ResolvedSessionModelState(
            Map<String, String> availableModels,
            NormalizedModelSelection plannerSelection,
            NormalizedModelSelection codeSelection) {}

    private record StartupSessionState(
            AcpSchema.SessionModelState modelState, List<AcpProtocol.SessionConfigOption> configOptions) {}

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

        var bundleOpt = tryBundleForSession(sessionId);
        if (bundleOpt.isEmpty()) {
            logger.debug("No bundle for session {}, skipping conversation replay", sessionId);
            return;
        }
        var bundle = bundleOpt.get();

        Thread.startVirtualThread(() -> {
            try {
                var taskHistory = bundle.cm().liveContext().getTaskHistory();
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
                    var rawText = mopMarkdown != null ? mopMarkdown : task.summary();
                    var responseText = rawText == null ? null : LegacyFramingParser.strip(rawText);
                    if (responseText != null && !responseText.isBlank()) {
                        sender.sendSessionUpdate(sessionId, AcpRequestContext.agentMessageChunk(responseText));
                    }
                }
                logger.info("Conversation replay complete for session {} ({} entries)", sessionId, taskHistory.size());
            } catch (Exception e) {
                logger.warn("Conversation replay failed for session {}", sessionId, e);
            }
        });
    }

    // ---- Prompt resource attachment ----

    /**
     * Walks {@code blocks} for {@code resource_link} / embedded {@code resource} entries, resolves
     * each URI to a workspace-relative path under {@code bundle.root()}, and adds the resulting
     * files to the bundle's workspace as editable {@code ProjectPathFragment}s. Mirrors the Python
     * ACP bridge's {@code extract_resource_file_paths} + {@code add_context_files} flow so a manual
     * {@code @file} attachment lands in the workspace before any mode (LUTZ, CODE, ASK, PLAN) runs.
     *
     * <p>Failures to resolve individual URIs are logged at debug and skipped; failures to attach
     * the resolved set are logged at warn -- neither aborts the prompt.
     */
    private void attachPromptResources(@Nullable List<AcpSchema.ContentBlock> blocks, WorkspaceBundle bundle) {
        var relPaths = extractResourceRelPaths(blocks, bundle.root());
        if (relPaths.isEmpty()) {
            return;
        }
        var files = new ArrayList<ProjectFile>();
        for (var rel : relPaths) {
            try {
                files.add(bundle.cm().toFile(rel));
            } catch (IllegalArgumentException e) {
                logger.debug("Skipping ACP resource path {} (rejected by toFile): {}", rel, e.getMessage());
            }
        }
        if (files.isEmpty()) {
            return;
        }
        try {
            bundle.cm().addFiles(files);
            logger.info("Attached {} ACP prompt resource(s) to workspace: {}", files.size(), files);
        } catch (Exception e) {
            logger.warn("Failed attaching ACP prompt resources to workspace: {}", files, e);
        }
    }

    /**
     * Extracts workspace-relative file paths from {@code resource_link} and embedded
     * {@code resource} content blocks. URIs may be {@code file://} absolute or bare relative;
     * either are resolved against {@code root}. Paths that escape the root are silently dropped.
     * Result is deduped while preserving insertion order.
     */
    static List<String> extractResourceRelPaths(@Nullable List<AcpSchema.ContentBlock> blocks, Path root) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        var normalizedRoot = root.toAbsolutePath().normalize();
        var seen = new LinkedHashSet<String>();
        for (var block : blocks) {
            var uri = uriFromBlock(block);
            if (uri == null || uri.isBlank()) {
                continue;
            }
            var rel = resolveRelativePath(uri, normalizedRoot);
            if (rel != null) {
                seen.add(rel);
            }
        }
        return List.copyOf(seen);
    }

    private static @Nullable String uriFromBlock(AcpSchema.ContentBlock block) {
        if (block instanceof AcpSchema.ResourceLink rl) {
            return rl.uri();
        }
        if (block instanceof AcpSchema.Resource r) {
            var nested = r.resource();
            if (nested instanceof AcpSchema.TextResourceContents trc) {
                return trc.uri();
            }
            if (nested instanceof AcpSchema.BlobResourceContents brc) {
                return brc.uri();
            }
        }
        return null;
    }

    private static @Nullable String resolveRelativePath(String uri, Path normalizedRoot) {
        Path absPath;
        try {
            if (uri.startsWith("file:")) {
                absPath = Path.of(URI.create(uri));
            } else {
                var p = Path.of(uri);
                absPath = p.isAbsolute() ? p : normalizedRoot.resolve(p);
            }
        } catch (IllegalArgumentException e) {
            // Catches both bad URI syntax and InvalidPathException (a subclass).
            logger.debug("Skipping unparseable ACP resource URI {}: {}", uri, e.getMessage());
            return null;
        }
        absPath = absPath.toAbsolutePath().normalize();
        if (!absPath.startsWith(normalizedRoot)) {
            logger.debug("ACP resource URI {} resolves to {} outside root {}", uri, absPath, normalizedRoot);
            return null;
        }
        var rel = normalizedRoot.relativize(absPath);
        if (rel.toString().isEmpty()) {
            return null;
        }
        return rel.toString().replace('\\', '/');
    }

    // ---- Slash commands ----

    /**
     * Single source of truth for ACP slash commands. Both {@link #handleSlashCommand} (dispatch)
     * and {@link #scheduleAvailableCommandsUpdate} (advertise to client) iterate this list, so a
     * new command needs to be registered exactly once. Instance field rather than {@code static}
     * because handlers close over {@code this}.
     *
     * <p>The {@code name} is stored without the leading slash to match {@link
     * AcpSchema.AvailableCommand}'s wire format; dispatch strips the slash before lookup.
     */
    @FunctionalInterface
    private interface SlashCommandHandler {
        void run(String sessionId, String arg, WorkspaceBundle bundle, AcpPromptContext promptContext);
    }

    private record SlashCommand(
            String name, String description, @Nullable String inputHint, SlashCommandHandler handler) {}

    private final List<SlashCommand> slashCommands = List.of(
            new SlashCommand(
                    "context",
                    "Show current context snapshot",
                    null,
                    (sessionId, arg, bundle, ctx) -> handleContextCommand(bundle, ctx)),
            new SlashCommand(
                    "config",
                    "Show or update editable Brokk configuration",
                    "[<path> [value] | {section:{...}}] e.g. global.exceptionReportingEnabled false",
                    (sessionId, arg, bundle, ctx) -> handleConfigCommand(sessionId, arg, bundle, ctx)),
            new SlashCommand(
                    "sandbox",
                    "Show or toggle the kernel sandbox for shell commands",
                    "on|off",
                    (sessionId, arg, bundle, ctx) -> handleSandboxCommand(sessionId, arg, ctx)),
            new SlashCommand(
                    "codex-login",
                    "Sign in to Codex (OpenAI OAuth) or disconnect",
                    "[status|disconnect|open]",
                    (sessionId, arg, bundle, ctx) -> handleCodexLoginCommand(sessionId, arg, ctx)));

    private final Map<String, SlashCommand> slashCommandsByName =
            slashCommands.stream().collect(Collectors.toUnmodifiableMap(SlashCommand::name, c -> c));

    private boolean handleSlashCommand(
            String sessionId, String text, WorkspaceBundle bundle, AcpPromptContext promptContext) {
        var stripped = text.strip();
        if (!stripped.startsWith("/")) {
            return false;
        }

        var parts = stripped.split("\\s+", 2);
        var name = parts[0].substring(1).toLowerCase(Locale.ROOT);
        var command = slashCommandsByName.get(name);
        if (command == null) {
            return false;
        }
        var arg = parts.length > 1 ? parts[1] : "";
        command.handler().run(sessionId, arg, bundle, promptContext);
        return true;
    }

    private void handleContextCommand(WorkspaceBundle bundle, AcpPromptContext promptContext) {
        logger.info("ACP /context command for bundle {}", bundle.root());
        var live = bundle.cm().liveContext();
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

    private void handleConfigCommand(
            String sessionId, String payload, WorkspaceBundle bundle, AcpPromptContext promptContext) {
        var trimmedPayload = payload.trim();
        if (trimmedPayload.isEmpty()) {
            promptContext.sendMessage(renderConfigSnapshot(sessionId, bundle));
            return;
        }

        if (trimmedPayload.startsWith("{") || trimmedPayload.startsWith("[")) {
            JsonNode update;
            try {
                update = OBJECT_MAPPER.readTree(trimmedPayload);
            } catch (JsonProcessingException e) {
                promptContext.sendMessage(
                        "Error: invalid JSON payload for /config. Example: /config {\"global\":{\"theme\":\"dark\"}}\n\n"
                                + e.getOriginalMessage());
                return;
            }
            if (!update.isObject()) {
                promptContext.sendMessage(
                        "Error: /config update payload must be a JSON object. Example: /config {\"projectSettings\":{\"commitMessageFormat\":\"feat: {{description}}\"}}");
                return;
            }
            applyAndReport(sessionId, bundle, update, promptContext);
            return;
        }

        var parts = trimmedPayload.split("\\s+", 2);
        var path = parts[0];
        if (parts.length == 1) {
            handleConfigPathShow(sessionId, bundle, path, promptContext);
        } else {
            handleConfigPathSet(sessionId, bundle, path, parts[1].trim(), promptContext);
        }
    }

    private void applyAndReport(
            String sessionId, WorkspaceBundle bundle, JsonNode update, AcpPromptContext promptContext) {
        try {
            applyConfigUpdate(sessionId, bundle, update);
            promptContext.sendMessage(
                    "Updated configuration successfully.\n\n" + renderConfigSnapshot(sessionId, bundle));
        } catch (IllegalArgumentException e) {
            promptContext.sendMessage("Error updating configuration: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to handle /config for bundle {}", bundle.root(), e);
            promptContext.sendMessage("Error updating configuration: " + e.getMessage());
        }
    }

    /**
     * Handles {@code /sandbox} with optional {@code on|off} arg. The toggle is per-session and
     * in-memory only: a fresh session always starts with the sandbox enabled, and {@link
     * #closeSession} / {@link #clearAllSessions} drop the flag with the rest of the per-session
     * state. Disabling it makes shell commands run with {@link ai.brokk.util.SandboxPolicy#NONE}
     * instead of {@link ai.brokk.util.SandboxPolicy#WORKSPACE_WRITE}, by upgrading the {@code
     * ALLOW} verdicts produced in {@link AcpRequestContext#askPermissionDetailed} to {@code
     * ALLOW_NO_SANDBOX}.
     *
     * <p>Asymmetry note: {@code /sandbox on} re-enables the sandbox for <em>future</em> approvals,
     * but does not retroactively dégrade tools the user previously approved with "Always allow
     * without sandbox" in this session — those sticky verdicts are honored as the user's explicit
     * earlier choice. To force them re-prompt, start a new session.
     */
    private void handleSandboxCommand(String sessionId, String arg, AcpPromptContext promptContext) {
        var trimmed = arg.trim().toLowerCase(Locale.ROOT);
        var platformNote = Environment.isSandboxAvailable()
                ? ""
                : "\n\n_Note: kernel sandbox is not available on this platform (Windows, or Linux without Bubblewrap installed); this toggle has no effect on shell command isolation._";
        switch (trimmed) {
            case "" -> {
                var state = isSandboxDisabledFor(sessionId) ? "**disabled**" : "**enabled**";
                promptContext.sendMessage("Sandbox is currently " + state
                        + " for this session. Affects shell commands only.\n\nUsage: `/sandbox on|off`"
                        + platformNote);
            }
            case "on" -> {
                setSandboxDisabledFor(sessionId, false);
                logger.info("ACP /sandbox on session={}", sessionId);
                promptContext.sendMessage(
                        "Sandbox **enabled** for this session. Shell commands will run with filesystem isolation."
                                + "\n\n_Note: tools you previously approved with \"Always allow without sandbox\" in this session keep that approval. Start a new session to force re-prompting._"
                                + platformNote);
            }
            case "off" -> {
                setSandboxDisabledFor(sessionId, true);
                logger.info("ACP /sandbox off session={}", sessionId);
                promptContext.sendMessage(
                        "Sandbox **disabled** for this session. Shell commands will run without filesystem isolation until you re-enable with `/sandbox on` or start a new session."
                                + platformNote);
            }
            default ->
                promptContext.sendMessage("Error: unknown /sandbox argument '" + arg.trim()
                        + "'. Usage: `/sandbox on|off` (or `/sandbox` to show status).");
        }
    }

    /**
     * Handles {@code /codex-login} with optional subcommand.
     *
     * <p>Reuses {@link OpenAiOAuthService#startAuthorization(java.awt.Component)} — the same flow
     * used by the GUI sign-in. The browser opens locally on the user's machine; the OAuth callback
     * lands on {@code http://localhost:1455/auth/callback}, forwards to the Brokk backend, and
     * flips {@link MainProject#setOpenAiCodexOauthConnected(boolean)}. We register a one-shot
     * {@link SettingsChangeListener} so the agent can push an async confirmation message to the
     * session once the user completes the browser flow, with a 5-minute timeout as the failsafe.
     */
    private void handleCodexLoginCommand(String sessionId, String arg, AcpPromptContext promptContext) {
        var trimmed = arg.trim().toLowerCase(Locale.ROOT);
        switch (trimmed) {
            case "" -> startCodexLogin(sessionId, promptContext);
            case "status" -> showCodexLoginStatus(promptContext);
            case "disconnect" -> disconnectCodex(sessionId, promptContext);
            case "open" -> reopenCodexLoginBrowser(promptContext);
            default ->
                promptContext.sendMessage("Error: unknown /codex-login argument '" + arg.trim()
                        + "'. Usage: `/codex-login [status|disconnect|open]`.");
        }
    }

    private void startCodexLogin(String sessionId, AcpPromptContext promptContext) {
        if (MainProject.isOpenAiCodexOauthConnected()) {
            promptContext.sendMessage(
                    "Already signed in to Codex. Use `/codex-login disconnect` to sign out, or `/codex-login status` to check.");
            return;
        }
        if (MainProject.getBrokkKey().isBlank()) {
            promptContext.sendMessage(
                    "Brokk key is not set. Set it in the Brokk GUI (Settings) or via the `BROKK_KEY` environment variable, then retry `/codex-login`.");
            return;
        }

        SettingsChangeListener listener = new SettingsChangeListener() {
            @Override
            public void openAiOauthConnectionChanged() {
                if (!MainProject.isOpenAiCodexOauthConnected()) {
                    return;
                }
                var current = pendingLogin.get();
                if (current == null || !current.sessionId().equals(sessionId)) {
                    return;
                }
                if (!pendingLogin.compareAndSet(current, null)) {
                    return;
                }
                try {
                    current.timeout().cancel(false);
                } finally {
                    MainProject.removeSettingsChangeListener(this);
                }
                pushSessionMessage(sessionId, "Codex sign-in successful.");
                logger.info("ACP /codex-login completed for session {}", sessionId);
                // Mirror SettingsGlobalPanel.maybeRunCodexAutoSetup so ACP and GUI converge after
                // sign-in: install Codex favorites, then reload Service so getAvailableModels
                // sees the new OAuth-only catalog. Run off the OAuth callback thread.
                LoggingFuture.runVirtual(() -> {
                    MainProject.saveFavoriteModels(ModelProperties.CODEX_OAUTH_FAVORITES);
                    var bundle = bundleBySession.get(sessionId);
                    if (bundle != null) {
                        bundle.cm().reloadService();
                    }
                });
            }
        };

        // Raw ScheduledExecutorService swallows runnable exceptions; wrap so unexpected throws
        // surface through the project-wide handler instead of disabling future scheduled tasks.
        ScheduledFuture<?> timeout = LOGIN_TIMEOUT_EXEC.schedule(
                () -> {
                    try {
                        var current = pendingLogin.get();
                        if (current == null || !current.sessionId().equals(sessionId)) {
                            return;
                        }
                        if (!pendingLogin.compareAndSet(current, null)) {
                            return;
                        }
                        MainProject.removeSettingsChangeListener(current.listener());
                        OpenAiOAuthService.cancelAuthorization();
                        pushSessionMessage(
                                sessionId,
                                "Codex sign-in timed out after " + LOGIN_TIMEOUT_MINUTES
                                        + " minutes. Run `/codex-login` again to retry.");
                        logger.info("ACP /codex-login timed out for session {}", sessionId);
                    } catch (Throwable t) {
                        GlobalExceptionHandler.handle(t, st -> {});
                    }
                },
                LOGIN_TIMEOUT_MINUTES,
                TimeUnit.MINUTES);

        var pending = new LoginPending(sessionId, listener, timeout);
        if (!pendingLogin.compareAndSet(null, pending)) {
            timeout.cancel(false);
            var current = pendingLogin.get();
            var owner = current == null ? "another" : current.sessionId();
            promptContext.sendMessage("Another session (" + owner
                    + ") is already signing in to Codex. Try again in a moment, or run `/codex-login status`.");
            return;
        }

        MainProject.addSettingsChangeListener(listener);

        try {
            OpenAiOAuthService.startAuthorization(null);
        } catch (IllegalStateException e) {
            // OpenAiOAuthService re-throws this in headless mode when the callback server cannot
            // bind port 1455 (typically: another process or a stale Brokk GUI is using it).
            cancelPending(pending);
            logger.warn("ACP /codex-login failed to start OAuth server", e);
            promptContext.sendMessage(
                    "Failed to start the Codex OAuth callback server on port 1455. Another process may be using it. Close any other Brokk instance and retry.");
            return;
        } catch (RuntimeException e) {
            cancelPending(pending);
            logger.warn("ACP /codex-login failed", e);
            promptContext.sendMessage("Failed to start Codex sign-in: " + e.getMessage());
            return;
        }

        var url = OpenAiOAuthService.getPendingAuthorizationUrl();
        var base =
                """
                Opened your browser to sign in to Codex.

                Complete the OAuth flow in the browser; this chat will receive a confirmation when done. Times out in %d minutes."""
                        .formatted(LOGIN_TIMEOUT_MINUTES);
        var msg = url == null ? base : base + "\n\nIf nothing opened, paste this URL:\n\n" + url;
        promptContext.sendMessage(msg);
        logger.info("ACP /codex-login started for session {}", sessionId);
    }

    private void showCodexLoginStatus(AcpPromptContext promptContext) {
        var connected = MainProject.isOpenAiCodexOauthConnected();
        var pending = pendingLogin.get();
        var status = "Codex OAuth: " + (connected ? "**connected**" : "**not connected**") + ".";
        var msg = pending == null
                ? status
                : status + "\n\nA sign-in attempt is in flight (session " + pending.sessionId() + ").";
        promptContext.sendMessage(msg);
    }

    private void disconnectCodex(String sessionId, AcpPromptContext promptContext) {
        if (!MainProject.isOpenAiCodexOauthConnected()) {
            promptContext.sendMessage("Codex is not connected; nothing to disconnect.");
            return;
        }
        promptContext.sendMessage("Disconnecting Codex...");
        // Service.disconnectCodexOauth() is a synchronous HTTP DELETE; off-load it so the slash
        // response returns immediately and the prompt thread is not blocked on the backend.
        LoggingFuture.runVirtual(() -> {
            var error = Service.disconnectCodexOauth();
            if (error == null) {
                MainProject.setOpenAiCodexOauthConnected(false);
                // Drop any in-flight login on this JVM so its listener cannot fire a phantom
                // "successful" message on a future unrelated reconnection.
                var pending = pendingLogin.get();
                if (pending != null) {
                    cancelPending(pending);
                }
                pushSessionMessage(sessionId, "Codex sign-in disconnected.");
                logger.info("ACP /codex-login disconnect succeeded");
            } else {
                pushSessionMessage(sessionId, "Failed to disconnect Codex: " + error);
                logger.warn("ACP /codex-login disconnect failed: {}", error);
            }
        });
    }

    private void reopenCodexLoginBrowser(AcpPromptContext promptContext) {
        // Only honor /codex-login open while an attempt is actually in flight. Without this guard
        // we would re-open a stale URL whose pending state was already torn down by cancelPending,
        // which would race the still-bound server.
        if (pendingLogin.get() == null) {
            promptContext.sendMessage("No pending Codex sign-in. Run `/codex-login` first.");
            return;
        }
        var url = OpenAiOAuthService.getPendingAuthorizationUrl();
        if (url == null) {
            promptContext.sendMessage("No pending Codex sign-in. Run `/codex-login` first.");
            return;
        }
        Environment.openInBrowser(url, null);
        promptContext.sendMessage("Re-opened browser. If nothing opened, paste this URL:\n\n" + url);
    }

    private void cancelPending(LoginPending pending) {
        if (!pendingLogin.compareAndSet(pending, null)) {
            return;
        }
        pending.timeout().cancel(false);
        MainProject.removeSettingsChangeListener(pending.listener());
        // Tear down the local callback server so port 1455 and the PKCE verifier do not linger.
        OpenAiOAuthService.cancelAuthorization();
    }

    private void pushSessionMessage(String sessionId, String text) {
        var sender = sessionUpdateSender;
        if (sender == null) {
            logger.debug("No sessionUpdateSender available; dropping message for session {}", sessionId);
            return;
        }
        try {
            sender.sendSessionUpdate(sessionId, AcpRequestContext.agentMessageChunk(text));
        } catch (Exception e) {
            logger.warn("Failed to push session message to {}", sessionId, e);
        }
    }

    private void handleConfigPathShow(
            String sessionId, WorkspaceBundle bundle, String path, AcpPromptContext promptContext) {
        var segments = splitConfigPath(path);
        if (segments.isEmpty()) {
            promptContext.sendMessage("Error: invalid configuration path: " + path);
            return;
        }
        Object cursor = buildConfigSnapshot(sessionId, bundle);
        for (var segment : segments) {
            if (cursor instanceof Map<?, ?> map && map.containsKey(segment)) {
                cursor = map.get(segment);
            } else {
                promptContext.sendMessage("Error: configuration path not found: " + path);
                return;
            }
        }
        try {
            var rendered = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(cursor);
            promptContext.sendMessage("Configuration at `%s`:\n```json\n%s\n```".formatted(path, rendered));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to render configuration path " + path, e);
        }
    }

    private void handleConfigPathSet(
            String sessionId, WorkspaceBundle bundle, String path, String rawValue, AcpPromptContext promptContext) {
        var segments = splitConfigPath(path);
        if (segments.isEmpty()) {
            promptContext.sendMessage("Error: invalid configuration path: " + path);
            return;
        }
        if (!KNOWN_CONFIG_SECTIONS.contains(segments.getFirst())) {
            promptContext.sendMessage("Error: unknown configuration section: " + segments.getFirst()
                    + ". Known sections: " + String.join(", ", KNOWN_CONFIG_SECTIONS));
            return;
        }
        JsonNode leaf;
        try {
            leaf = OBJECT_MAPPER.readTree(rawValue);
        } catch (JsonProcessingException ignore) {
            leaf = TextNode.valueOf(rawValue);
        }
        var factory = JsonNodeFactory.instance;
        var root = factory.objectNode();
        ObjectNode cursor = root;
        for (int i = 0; i < segments.size() - 1; i++) {
            var next = factory.objectNode();
            cursor.set(segments.get(i), next);
            cursor = next;
        }
        cursor.set(segments.getLast(), leaf);
        applyAndReport(sessionId, bundle, root, promptContext);
    }

    private static List<String> splitConfigPath(String path) {
        var segments = List.of(path.split("\\."));
        if (segments.stream().anyMatch(String::isEmpty)) {
            return List.of();
        }
        return segments;
    }

    private String renderConfigSnapshot(String sessionId, WorkspaceBundle bundle) {
        var snapshot = buildConfigSnapshot(sessionId, bundle);
        try {
            return """
                    Current editable Brokk configuration.

                    Usage:
                    - `/config <path> <value>` to set a value, e.g. `/config global.exceptionReportingEnabled false`
                    - `/config <path> <json>` to set a JSON literal, e.g. `/config buildDetails.exclusionPatterns ["target","build"]`
                    - `/config <path>` to show just that section/value, e.g. `/config global`
                    - `/config {"section": {...}}` to apply a batch JSON update

                    Editable sections:
                    - buildDetails
                    - projectSettings
                    - shellConfig
                    - issueProvider
                    - dataRetentionPolicy
                    - analyzerLanguages
                    - global

                    Note: GUI-only settings (theme, notifications, layout flags, jvmMemorySettings, etc.)
                    are configurable only in the Brokk desktop app. Including them in a `/config` batch
                    rejects the entire update — strip them client-side before sending.

                    ```json
                    %s
                    ```
                    """
                    .formatted(OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to render configuration snapshot", e);
        }
    }

    private Map<String, Object> buildConfigSnapshot(String sessionId, WorkspaceBundle bundle) {
        var project = bundle.cm().getProject();
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("buildDetails", buildBuildDetailsMap(project.awaitBuildDetails()));
        snapshot.put("projectSettings", buildProjectSettingsMap(project));
        snapshot.put("shellConfig", buildShellConfigMap(project.getShellConfig()));
        snapshot.put("issueProvider", buildIssueProviderMap(project.getIssuesProvider()));
        snapshot.put("dataRetentionPolicy", project.getDataRetentionPolicy().name());
        snapshot.put("analyzerLanguages", buildAnalyzerLanguagesMap(project));
        snapshot.put("global", buildGlobalSettingsMap(sessionId, bundle));
        return snapshot;
    }

    private Map<String, Object> buildBuildDetailsMap(BuildDetails details) {
        var map = new LinkedHashMap<String, Object>();
        map.put("buildLintCommand", details.buildLintCommand());
        map.put("buildLintEnabled", details.buildLintEnabled());
        map.put("testAllCommand", details.testAllCommand());
        map.put("testAllEnabled", details.testAllEnabled());
        map.put("testSomeCommand", details.testSomeCommand());
        map.put("testSomeEnabled", details.testSomeEnabled());
        map.put("exclusionPatterns", new ArrayList<>(details.exclusionPatterns()));
        map.put("environmentVariables", new LinkedHashMap<>(details.environmentVariables()));
        map.put("maxBuildAttempts", details.maxBuildAttempts());
        map.put("afterTaskListCommand", details.afterTaskListCommand());
        var modules = details.modules().stream()
                .map(module -> {
                    var moduleMap = new LinkedHashMap<String, Object>();
                    moduleMap.put("alias", module.alias());
                    moduleMap.put("relativePath", module.relativePath());
                    moduleMap.put("buildLintCommand", module.buildLintCommand());
                    moduleMap.put("testAllCommand", module.testAllCommand());
                    moduleMap.put("testSomeCommand", module.testSomeCommand());
                    moduleMap.put("language", module.language());
                    return moduleMap;
                })
                .toList();
        map.put("modules", modules);
        return map;
    }

    private Map<String, Object> buildProjectSettingsMap(IProject project) {
        var map = new LinkedHashMap<String, Object>();
        map.put("commitMessageFormat", project.getCommitMessageFormat());
        map.put("codeAgentTestScope", project.getCodeAgentTestScope().name());
        map.put("runCommandTimeoutSeconds", project.getRunCommandTimeoutSeconds());
        map.put("testCommandTimeoutSeconds", project.getTestCommandTimeoutSeconds());
        map.put("autoUpdateLocalDependencies", project.getAutoUpdateLocalDependencies());
        map.put("autoUpdateGitDependencies", project.getAutoUpdateGitDependencies());
        return map;
    }

    private Map<String, Object> buildShellConfigMap(ShellConfig config) {
        var map = new LinkedHashMap<String, Object>();
        map.put("executable", config.executable());
        map.put("args", new ArrayList<>(config.args()));
        return map;
    }

    private Map<String, Object> buildIssueProviderMap(IssueProvider provider) {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", provider.type().name());

        Object config = null;
        if (provider.config() instanceof GithubConfig github) {
            config = Map.of("owner", github.owner(), "repo", github.repo(), "host", github.host());
        } else if (provider.config() instanceof JiraConfig jira) {
            config = Map.of("baseUrl", jira.baseUrl(), "apiToken", jira.apiToken(), "projectKey", jira.projectKey());
        }
        map.put("config", config);
        return map;
    }

    private Map<String, Object> buildAnalyzerLanguagesMap(IProject project) {
        var map = new LinkedHashMap<String, Object>();
        map.put(
                "configured",
                project.getAnalyzerLanguages().stream()
                        .filter(lang -> lang != Languages.NONE)
                        .map(Language::internalName)
                        .toList());
        map.put(
                "detected",
                Languages.findLanguagesInProject(project).stream()
                        .filter(lang -> lang != Languages.NONE)
                        .map(Language::internalName)
                        .toList());
        map.put(
                "available",
                Languages.ALL_LANGUAGES.stream()
                        .filter(lang -> lang != Languages.NONE)
                        .map(lang -> Map.of("name", lang.name(), "internalName", lang.internalName()))
                        .toList());
        return map;
    }

    private Map<String, Object> buildGlobalSettingsMap(String sessionId, WorkspaceBundle bundle) {
        var service = bundle.cm().getService();
        var map = new LinkedHashMap<String, Object>();

        map.put("brokkKey", MainProject.getBrokkKey());
        map.put("proxySetting", MainProject.getProxySetting().name());
        map.put(
                "customEndpoint",
                Map.of(
                        "url",
                        MainProject.getCustomEndpointUrl(),
                        "apiKey",
                        MainProject.getCustomEndpointApiKey(),
                        "model",
                        MainProject.getCustomEndpointModel()));
        map.put(
                "github",
                Map.of(
                        "token",
                        MainProject.getGitHubToken(),
                        "cloneProtocol",
                        MainProject.getGitHubCloneProtocol(),
                        "shallowCloneEnabled",
                        MainProject.getGitHubShallowCloneEnabled(),
                        "shallowCloneDepth",
                        MainProject.getGitHubShallowCloneDepth()));
        map.put("exceptionReportingEnabled", MainProject.getExceptionReportingEnabled());
        map.put("openAiCodexOauthConnected", MainProject.isOpenAiCodexOauthConnected());
        map.put(
                "favoriteModels",
                MainProject.loadFavoriteModels().stream()
                        .map(favorite -> Map.of(
                                "alias",
                                favorite.alias(),
                                "config",
                                Map.of(
                                        "name",
                                        favorite.config().name(),
                                        "reasoning",
                                        favorite.config().reasoning().toString().toLowerCase(Locale.ROOT),
                                        "tier",
                                        favorite.config().tier().toString().toLowerCase(Locale.ROOT))))
                        .toList());
        map.put(
                "sessionDefaults",
                Map.of(
                        "plannerModel",
                        modelBySession.getOrDefault(sessionId, defaultModelId),
                        "plannerReasoning",
                        reasoningBySession.getOrDefault(sessionId, defaultReasoningLevel),
                        "codeModel",
                        codeModelBySession.getOrDefault(sessionId, preferredCodeBaseModel(sessionId)),
                        "codeReasoning",
                        codeReasoningBySession.getOrDefault(sessionId, defaultCodeReasoningLevel)));
        map.put(
                "availableModels",
                service.getAvailableModels().keySet().stream().sorted().toList());

        return map;
    }

    private void applyConfigUpdate(String sessionId, WorkspaceBundle bundle, JsonNode update) {
        var project = bundle.cm().getProject();
        if (update.has("buildDetails")) {
            applyBuildSettings(project, requireObject(update, "buildDetails"));
        }
        if (update.has("projectSettings")) {
            applyProjectSettings(project, requireObject(update, "projectSettings"));
        }
        if (update.has("shellConfig")) {
            applyShellConfig(project, requireObject(update, "shellConfig"));
        }
        if (update.has("issueProvider")) {
            applyIssueProvider(project, update.get("issueProvider"));
        }
        if (update.has("dataRetentionPolicy")) {
            applyDataRetention(project, requireText(update, "dataRetentionPolicy"));
        }
        if (update.has("analyzerLanguages")) {
            applyAnalyzerLanguages(project, requireObject(update, "analyzerLanguages"));
        }
        if (update.has("global")) {
            applyGlobalSettings(sessionId, bundle, requireObject(update, "global"));
        }
    }

    private void applyBuildSettings(IProject project, JsonNode request) {
        var current = project.awaitBuildDetails();

        String buildLintCommand = request.has("buildLintCommand")
                ? textOrEmpty(request.get("buildLintCommand"))
                : current.buildLintCommand();
        boolean buildLintEnabled = request.has("buildLintEnabled")
                ? request.get("buildLintEnabled").asBoolean()
                : current.buildLintEnabled();
        String testAllCommand =
                request.has("testAllCommand") ? textOrEmpty(request.get("testAllCommand")) : current.testAllCommand();
        boolean testAllEnabled =
                request.has("testAllEnabled") ? request.get("testAllEnabled").asBoolean() : current.testAllEnabled();
        String testSomeCommand = request.has("testSomeCommand")
                ? textOrEmpty(request.get("testSomeCommand"))
                : current.testSomeCommand();
        boolean testSomeEnabled =
                request.has("testSomeEnabled") ? request.get("testSomeEnabled").asBoolean() : current.testSomeEnabled();
        Set<String> exclusionPatterns = request.has("exclusionPatterns")
                ? stringSet(request.get("exclusionPatterns"))
                : current.exclusionPatterns();
        Map<String, String> environmentVariables = request.has("environmentVariables")
                ? stringMap(request.get("environmentVariables"))
                : current.environmentVariables();
        Integer maxBuildAttempts;
        if (request.has("maxBuildAttempts")) {
            var maxBuildAttemptsNode = request.get("maxBuildAttempts");
            maxBuildAttempts = maxBuildAttemptsNode != null && !maxBuildAttemptsNode.isNull()
                    ? maxBuildAttemptsNode.asInt()
                    : null;
        } else {
            maxBuildAttempts = current.maxBuildAttempts();
        }
        String afterTaskListCommand = request.has("afterTaskListCommand")
                ? textOrEmpty(request.get("afterTaskListCommand"))
                : current.afterTaskListCommand();

        List<ModuleBuildEntry> modules;
        if (request.has("modules")) {
            modules = new ArrayList<>();
            for (var moduleNode : iterable(requireArray(request, "modules"))) {
                modules.add(new ModuleBuildEntry(
                        textOrEmpty(moduleNode.get("alias")),
                        textOrEmpty(moduleNode.get("relativePath")),
                        textOrEmpty(moduleNode.get("buildLintCommand")),
                        textOrEmpty(moduleNode.get("testAllCommand")),
                        textOrEmpty(moduleNode.get("testSomeCommand")),
                        textOrEmpty(moduleNode.get("language"))));
            }
        } else {
            modules = current.modules();
        }

        project.saveBuildDetails(new BuildDetails(
                buildLintCommand,
                buildLintEnabled,
                testAllCommand,
                testAllEnabled,
                testSomeCommand,
                testSomeEnabled,
                exclusionPatterns,
                environmentVariables,
                maxBuildAttempts,
                afterTaskListCommand,
                modules));
    }

    private void applyProjectSettings(IProject project, JsonNode request) {
        var mainProject = project.getMainProject();
        if (request.has("commitMessageFormat")) {
            project.setCommitMessageFormat(textOrEmpty(request.get("commitMessageFormat")));
        }
        if (request.has("codeAgentTestScope")) {
            project.setCodeAgentTestScope(IProject.CodeAgentTestScope.fromString(
                    textOrEmpty(request.get("codeAgentTestScope")), project.getCodeAgentTestScope()));
        }
        if (request.has("runCommandTimeoutSeconds")) {
            mainProject.setRunCommandTimeoutSeconds(
                    request.get("runCommandTimeoutSeconds").asLong());
        }
        if (request.has("testCommandTimeoutSeconds")) {
            mainProject.setTestCommandTimeoutSeconds(
                    request.get("testCommandTimeoutSeconds").asLong());
        }
        if (request.has("autoUpdateLocalDependencies")) {
            project.setAutoUpdateLocalDependencies(
                    request.get("autoUpdateLocalDependencies").asBoolean());
        }
        if (request.has("autoUpdateGitDependencies")) {
            project.setAutoUpdateGitDependencies(
                    request.get("autoUpdateGitDependencies").asBoolean());
        }
    }

    private void applyShellConfig(IProject project, JsonNode request) {
        var executable = requireText(request, "executable");
        if (executable.isBlank()) {
            throw new IllegalArgumentException("shellConfig.executable is required");
        }
        var args = request.has("args") ? stringList(request.get("args")) : List.<String>of();
        project.setShellConfig(new ShellConfig(executable, args));
    }

    private void applyIssueProvider(IProject project, JsonNode requestNode) {
        var typeStr = requireText(requestNode, "type");
        IssueProviderType type;
        try {
            type = IssueProviderType.valueOf(typeStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid issueProvider.type: " + typeStr);
        }

        IssueProvider provider =
                switch (type) {
                    case NONE -> IssueProvider.none();
                    case GITHUB -> {
                        var configNode = requestNode.get("config");
                        if (configNode == null || configNode.isNull()) {
                            yield IssueProvider.github();
                        }
                        yield IssueProvider.github(
                                configNode.has("owner") ? textOrEmpty(configNode.get("owner")) : "",
                                configNode.has("repo") ? textOrEmpty(configNode.get("repo")) : "",
                                configNode.has("host") ? textOrEmpty(configNode.get("host")) : "");
                    }
                    case JIRA -> {
                        var configNode = requireObject(requestNode, "config");
                        yield IssueProvider.jira(
                                configNode.has("baseUrl") ? textOrEmpty(configNode.get("baseUrl")) : "",
                                configNode.has("apiToken") ? textOrEmpty(configNode.get("apiToken")) : "",
                                configNode.has("projectKey") ? textOrEmpty(configNode.get("projectKey")) : "");
                    }
                };
        project.setIssuesProvider(provider);
    }

    private void applyDataRetention(IProject project, String policyStr) {
        MainProject.DataRetentionPolicy policy;
        try {
            policy = MainProject.DataRetentionPolicy.valueOf(policyStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid dataRetentionPolicy: " + policyStr + ". Must be IMPROVE_BROKK or MINIMAL");
        }
        if (policy == MainProject.DataRetentionPolicy.UNSET) {
            throw new IllegalArgumentException("Cannot set dataRetentionPolicy to UNSET");
        }
        project.setDataRetentionPolicy(policy);
    }

    private void applyAnalyzerLanguages(IProject project, JsonNode request) {
        var languagesNode = request.has("languages") ? request.get("languages") : request;
        var languages = new LinkedHashSet<Language>();
        for (var value : iterable(requireArrayValue(languagesNode, "analyzerLanguages.languages"))) {
            try {
                var language = Languages.valueOf(value.asText());
                if (language != Languages.NONE) {
                    languages.add(language);
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid analyzer language: " + value.asText());
            }
        }
        project.setAnalyzerLanguages(languages);
    }

    private void applyGlobalSettings(String sessionId, WorkspaceBundle bundle, JsonNode global) {
        rejectGuiOnlyKeys(global);
        if (global.has("brokkKey")) {
            MainProject.setBrokkKey(textOrEmpty(global.get("brokkKey")));
        }
        if (global.has("proxySetting")) {
            try {
                MainProject.setLlmProxySetting(MainProject.LlmProxySetting.valueOf(
                        requireText(global, "proxySetting").toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid global.proxySetting: " + requireText(global, "proxySetting"));
            }
        }
        if (global.has("customEndpoint")) {
            var customEndpoint = requireObject(global, "customEndpoint");
            if (customEndpoint.has("url")) {
                var url = textOrEmpty(customEndpoint.get("url")).trim();
                if (url.isBlank()) {
                    throw new IllegalArgumentException("global.customEndpoint.url must not be blank");
                }
                try {
                    var uri = URI.create(url);
                    if (uri.getScheme() == null || !uri.getScheme().matches("https?")) {
                        throw new IllegalArgumentException("scheme must be http or https");
                    }
                    if (uri.getHost() == null || uri.getHost().isBlank()) {
                        throw new IllegalArgumentException("host must not be blank");
                    }
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid global.customEndpoint.url: " + url);
                }
                MainProject.setCustomEndpointUrl(url);
            }
            if (customEndpoint.has("apiKey")) {
                MainProject.setCustomEndpointApiKey(textOrEmpty(customEndpoint.get("apiKey")));
            }
            if (customEndpoint.has("model")) {
                MainProject.setCustomEndpointModel(textOrEmpty(customEndpoint.get("model")));
            }
        }
        if (global.has("github")) {
            var github = requireObject(global, "github");
            if (github.has("token")) {
                MainProject.setGitHubToken(textOrEmpty(github.get("token")));
            }
            if (github.has("cloneProtocol")) {
                MainProject.setGitHubCloneProtocol(textOrEmpty(github.get("cloneProtocol")));
            }
            if (github.has("shallowCloneEnabled")) {
                MainProject.setGitHubShallowCloneEnabled(
                        github.get("shallowCloneEnabled").asBoolean());
            }
            if (github.has("shallowCloneDepth")) {
                MainProject.setGitHubShallowCloneDepth(
                        github.get("shallowCloneDepth").asInt());
            }
        }
        if (global.has("exceptionReportingEnabled")) {
            MainProject.setExceptionReportingEnabled(
                    global.get("exceptionReportingEnabled").asBoolean());
        }
        if (global.has("openAiCodexOauthConnected")) {
            MainProject.setOpenAiCodexOauthConnected(
                    global.get("openAiCodexOauthConnected").asBoolean());
        }
        if (global.has("favoriteModels")) {
            MainProject.saveFavoriteModels(parseFavoriteModels(global.get("favoriteModels")));
        }
        if (global.has("sessionDefaults")) {
            var sessionDefaults = requireObject(global, "sessionDefaults");
            var defaultsTouched = false;
            if (sessionDefaults.has("plannerModel") || sessionDefaults.has("plannerReasoning")) {
                var plannerModel = sessionDefaults.has("plannerModel")
                        ? textOrEmpty(sessionDefaults.get("plannerModel"))
                        : modelBySession.getOrDefault(sessionId, defaultModelId);
                var plannerReasoning = sessionDefaults.has("plannerReasoning")
                        ? textOrEmpty(sessionDefaults.get("plannerReasoning"))
                        : reasoningBySession.getOrDefault(sessionId, defaultReasoningLevel);
                var normalized = normalizeModelSelection(
                        sessionId,
                        bundle.cm().getService().getAvailableModels(),
                        bundle.cm().getService(),
                        plannerModel,
                        true);
                var sanitizedReasoning = sanitizeReasoningLevelForModel(
                        normalized.baseModel(), plannerReasoning, bundle.cm().getService());
                modelBySession.put(sessionId, normalized.baseModel());
                reasoningBySession.put(sessionId, sanitizedReasoning);
                defaultModelId = normalized.baseModel();
                defaultReasoningLevel = sanitizedReasoning;
                defaultsTouched = true;
            }
            if (sessionDefaults.has("codeModel") || sessionDefaults.has("codeReasoning")) {
                var codeModel = sessionDefaults.has("codeModel")
                        ? textOrEmpty(sessionDefaults.get("codeModel"))
                        : preferredCodeBaseModel(sessionId);
                var codeReasoning = sessionDefaults.has("codeReasoning")
                        ? textOrEmpty(sessionDefaults.get("codeReasoning"))
                        : codeReasoningBySession.getOrDefault(sessionId, defaultCodeReasoningLevel);
                var normalizedCode = normalizeCodeModelSelection(sessionId, codeModel, codeReasoning, true);
                defaultCodeModelId = normalizedCode.baseModel();
                var normalizedCodeReasoning = normalizedCode.reasoning();
                if (normalizedCodeReasoning != null) {
                    defaultCodeReasoningLevel = normalizedCodeReasoning;
                }
                defaultsTouched = true;
            }
            if (defaultsTouched) {
                persistAllDefaults();
            }
        }
    }

    private List<Service.FavoriteModel> parseFavoriteModels(JsonNode node) {
        var favorites = new ArrayList<Service.FavoriteModel>();
        for (var favoriteNode : iterable(requireArrayValue(node, "global.favoriteModels"))) {
            var alias = requireText(favoriteNode, "alias");
            var config = requireObject(favoriteNode, "config");
            var name = requireText(config, "name");
            var reasoning = AbstractService.ReasoningLevel.fromString(
                    config.has("reasoning") ? textOrEmpty(config.get("reasoning")) : null,
                    AbstractService.ReasoningLevel.DEFAULT);
            var tier = AbstractService.ProcessingTier.fromString(
                    config.has("tier") ? textOrEmpty(config.get("tier")) : null);
            favorites.add(new Service.FavoriteModel(alias, new AbstractService.ModelConfig(name, reasoning, tier)));
        }
        return favorites;
    }

    private static JsonNode requireObject(JsonNode parent, String fieldName) {
        var node = parent.get(fieldName);
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(fieldName + " must be a JSON object");
        }
        return node;
    }

    private static void rejectGuiOnlyKeys(JsonNode global) {
        var rejected = new ArrayList<String>();
        global.properties().forEach(entry -> {
            if (GUI_ONLY_GLOBAL_KEYS.contains(entry.getKey())) {
                rejected.add("global." + entry.getKey());
            }
        });
        if (!rejected.isEmpty()) {
            throw new IllegalArgumentException(String.join(", ", rejected)
                    + " can only be set in the Brokk desktop app; these settings have no effect in ACP.");
        }
    }

    private static JsonNode requireArray(JsonNode parent, String fieldName) {
        var node = parent.get(fieldName);
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException(fieldName + " must be a JSON array");
        }
        return node;
    }

    private static JsonNode requireArrayValue(JsonNode node, String fieldName) {
        if (!node.isArray()) {
            throw new IllegalArgumentException(fieldName + " must be a JSON array");
        }
        return node;
    }

    private static String requireText(JsonNode parent, String fieldName) {
        var node = parent.get(fieldName);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return textOrEmpty(node);
    }

    private static String textOrEmpty(@Nullable JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private static List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            throw new IllegalArgumentException("Expected JSON array of strings");
        }
        var values = new ArrayList<String>();
        for (var value : iterable(node)) {
            values.add(value.asText());
        }
        return List.copyOf(values);
    }

    private static Set<String> stringSet(JsonNode node) {
        return new LinkedHashSet<>(stringList(node));
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("Expected JSON object of string values");
        }
        var values = new LinkedHashMap<String, String>();
        node.properties()
                .forEach(entry -> values.put(entry.getKey(), entry.getValue().asText("")));
        return Map.copyOf(values);
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        return node::elements;
    }

    private void scheduleAvailableCommandsUpdate(String sessionId) {
        var sender = this.sessionUpdateSender;
        if (sender == null) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                var commands = slashCommands.stream()
                        .map(c -> new AcpSchema.AvailableCommand(
                                c.name(),
                                c.description(),
                                c.inputHint() == null ? null : new AcpSchema.AvailableCommandInput(c.inputHint())))
                        .toList();
                sender.sendSessionUpdate(
                        sessionId, new AcpSchema.AvailableCommandsUpdate("available_commands_update", commands));
            } catch (Exception e) {
                logger.warn("Failed to send available commands for session {}", sessionId, e);
            }
        });
    }

    // ---- ACP defaults persistence ----

    private record AcpDefaults(
            String defaultModel, String defaultReasoning, String defaultCodeModel, String defaultCodeReasoning) {}

    private static Path acpSettingsPath() {
        var override = System.getProperty(ACP_SETTINGS_PATH_PROPERTY);
        if (override == null || override.isBlank()) {
            return DEFAULT_ACP_SETTINGS_PATH;
        }
        return Path.of(override).toAbsolutePath().normalize();
    }

    private static AcpDefaults loadAcpDefaults() {
        try {
            var settingsPath = acpSettingsPath();
            if (!Files.exists(settingsPath)) {
                return new AcpDefaults("", DEFAULT_REASONING_LEVEL, "", DEFAULT_REASONING_LEVEL_CODE);
            }
            var mapper = new ObjectMapper();
            var tree = mapper.readTree(settingsPath.toFile());
            var model = tree.has("default_model") ? tree.get("default_model").asText("") : "";
            var reasoning = (tree.has("default_reasoning")
                            ? tree.get("default_reasoning").asText(DEFAULT_REASONING_LEVEL)
                            : DEFAULT_REASONING_LEVEL)
                    .strip();
            if (!REASONING_LEVEL_IDS.contains(reasoning)) {
                logger.warn(
                        "Persisted ACP reasoning {} invalid; falling back to {}", reasoning, DEFAULT_REASONING_LEVEL);
                reasoning = DEFAULT_REASONING_LEVEL;
            }
            var codeModel = tree.has("default_code_model")
                    ? tree.get("default_code_model").asText("")
                    : "";
            var codeReasoning = (tree.has("default_code_reasoning")
                            ? tree.get("default_code_reasoning").asText(DEFAULT_REASONING_LEVEL_CODE)
                            : DEFAULT_REASONING_LEVEL_CODE)
                    .strip();
            if (!REASONING_LEVEL_IDS.contains(codeReasoning)) {
                logger.warn(
                        "Persisted ACP code reasoning {} invalid; falling back to {}",
                        codeReasoning,
                        DEFAULT_REASONING_LEVEL_CODE);
                codeReasoning = DEFAULT_REASONING_LEVEL_CODE;
            }
            return new AcpDefaults(model.strip(), reasoning, codeModel.strip(), codeReasoning);
        } catch (Exception e) {
            logger.warn("Failed to load ACP defaults, using built-in defaults", e);
            return new AcpDefaults("", DEFAULT_REASONING_LEVEL, "", DEFAULT_REASONING_LEVEL_CODE);
        }
    }

    /**
     * Persists the current values of all four global default fields to {@code ~/.brokk/acp_settings.json}.
     * Callers should update the in-memory {@code defaultModelId} / {@code defaultReasoningLevel} /
     * {@code defaultCodeModelId} / {@code defaultCodeReasoningLevel} fields before invoking this so
     * the on-disk snapshot matches the live state. Used after planner-model and code-model selection
     * changes to keep the next session startup aligned.
     */
    private void persistAllDefaults() {
        saveAcpDefaults(defaultModelId, defaultReasoningLevel, defaultCodeModelId, defaultCodeReasoningLevel);
    }

    private static void saveAcpDefaults(String model, String reasoning, String codeModel, String codeReasoning) {
        try {
            var settingsPath = acpSettingsPath();
            var dir = settingsPath.getParent();
            assert dir != null;
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            var mapper = new ObjectMapper();
            var json = mapper.writeValueAsString(Map.of(
                    "default_model",
                    model,
                    "default_reasoning",
                    reasoning,
                    "default_code_model",
                    codeModel,
                    "default_code_reasoning",
                    codeReasoning));
            AtomicWrites.save(settingsPath, json);
            logger.debug(
                    "Saved ACP defaults: model={}, reasoning={}, codeModel={}, codeReasoning={}",
                    model,
                    reasoning,
                    codeModel,
                    codeReasoning);
        } catch (IOException e) {
            logger.warn("Failed to save ACP defaults", e);
        }
    }
}
