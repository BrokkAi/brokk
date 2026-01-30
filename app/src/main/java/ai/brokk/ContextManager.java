package ai.brokk;

import static ai.brokk.SessionManager.SessionInfo;
import static ai.brokk.context.ContextHistory.SNAPSHOT_AWAIT_TIMEOUT;
import static java.lang.Math.max;
import static java.util.Objects.requireNonNull;

import ai.brokk.agents.ArchitectAgent;
import ai.brokk.agents.BuildAgent;
import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.analyzer.BrokkFile;
import ai.brokk.analyzer.CallSite;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.cli.HeadlessConsole;
import ai.brokk.concurrent.ExecutorsUtil;
import ai.brokk.concurrent.LoggingExecutorService;
import ai.brokk.concurrent.LoggingFuture;
import ai.brokk.concurrent.UserActionManager;
import ai.brokk.concurrent.UserActionManager.ThrowingRunnable;
import ai.brokk.context.Context;
import ai.brokk.context.ContextDelta;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.ContextFragments.PathFragment;
import ai.brokk.context.ContextHistory;
import ai.brokk.context.ContextHistory.UndoResult;
import ai.brokk.exception.GlobalExceptionHandler;
import ai.brokk.git.GitDistance;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitWorkflow;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.ExceptionAwareSwingWorker;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.prompts.SummarizerPrompts;
import ai.brokk.tasks.TaskList;
import ai.brokk.tools.*;
import ai.brokk.util.*;
import ai.brokk.watchservice.AbstractWatchService;
import ai.brokk.watchservice.FileWatcherHelper;
import ai.brokk.watchservice.NoopWatchService;
import ai.brokk.watchservice.WatchServiceFactory;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.awt.Image;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

/**
 * Manages the current and previous context, along with other state like prompts and message history.
 *
 * <p>Updated to: - Remove OperationResult, - Move UI business logic from Chrome to here as asynchronous tasks, -
 * Directly call into Chrome’s UI methods from background tasks (via invokeLater), - Provide separate async methods for
 * “Go”, “Ask”, “Search”, context additions, etc.
 */
public class ContextManager implements IContextManager, AutoCloseable {
    private static final Logger logger = LogManager.getLogger(ContextManager.class);

    private IConsoleIO io; // for UI feedback - Initialized in createGui

    @Nullable
    private IAnalyzerWrapper analyzerWrapper; // also initialized in createGui/createHeadless

    // Run main user-driven tasks in background (Code/Ask/Search/Run)
    // Only one of these can run at a time
    private final UserActionManager userActions;

    // Regex to identify test files. Matches the word "test"/"tests" (case-insensitive)
    // when it appears as its own path segment or at a camel-case boundary, as well as
    // common JS/TS conventions: *.spec.<ext>, *.test.<ext>, and files under __tests__/.
    static final Pattern TEST_FILE_PATTERN = Pattern.compile(".*"
            + "(?:"
            + "(?:[/\\\\.]|\\b|_|(?<=[a-z])(?=[A-Z])|(?<=[A-Z]))"
            + "(?i:tests?)"
            + "(?:[/\\\\.]|\\b|_|(?=[A-Z][^a-z])|(?=[A-Z][a-z])|$)"
            + "|"
            + "(?i:\\.(?:spec|test)\\.[^/\\\\.]+$)"
            + ")"
            + ".*");

    public static final String DEFAULT_SESSION_NAME = "New Session";
    // Cutoff: sessions modified on or after this UTC instant will NOT be migrated
    private static final long TASKLIST_MIGRATION_CUTOFF_MS =
            Instant.parse("2025-11-30T00:00:00Z").toEpochMilli();

    /**
     * Identifies if a file contains tests. Uses the analyzer's semantic knowledge if available,
     * otherwise falls back to filename-based heuristics.
     */
    public static boolean isTestFile(ProjectFile file, @Nullable IAnalyzer analyzer) {
        // 1. If analyzer knows, trust it
        if (analyzer != null && !analyzer.isEmpty()) {
            if (analyzer.containsTests(file)) {
                return true;
            }
            // Analyzer exists but says no tests — could still fall through
            // to heuristics for languages where analyzer doesn't implement this
        }

        // 2. Filename/path heuristics as fallback
        return TEST_FILE_PATTERN.matcher(file.toString()).matches();
    }

    private LoggingExecutorService createLoggingExecutorService(ExecutorService toWrap) {
        return new LoggingExecutorService(
                toWrap,
                th -> GlobalExceptionHandler.handle(
                        th, st -> io.showNotification(IConsoleIO.NotificationRole.ERROR, st)));
    }

    // Context modification tasks (Edit/Read/Summarize/Drop/etc)
    // Multiple of these can run concurrently
    private final LoggingExecutorService contextActionExecutor = createLoggingExecutorService(new ThreadPoolExecutor(
            4,
            4, // Core and Max are same due to unbounded queue behavior
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), // Unbounded queue
            ExecutorsUtil.createNamedThreadFactory("ContextTask")));

    // Internal background tasks (unrelated to user actions)
    // Lots of threads allowed since AutoContext updates get dropped here
    // Use unbounded queue to prevent task rejection
    private final LoggingExecutorService backgroundTasks = createLoggingExecutorService(new ThreadPoolExecutor(
            max(8, Runtime.getRuntime().availableProcessors()), // Core and Max are same
            max(8, Runtime.getRuntime().availableProcessors()),
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), // Unbounded queue to prevent rejection
            ExecutorsUtil.createNamedThreadFactory("BackgroundTask")));

    private final LoggingExecutorService syncExecutor = createLoggingExecutorService(
            Executors.newSingleThreadExecutor(ExecutorsUtil.createNamedThreadFactory("SessionSync")));

    private final Service.Provider serviceProvider;

    private final IProject project;

    // Cached exception reporter for this context
    private final ExceptionReporter exceptionReporter;

    private final ToolRegistry toolRegistry;

    // Current session tracking
    private UUID currentSessionId;

    // Context history for undo/redo functionality (stores frozen contexts)
    private ContextHistory contextHistory;
    private final List<ContextListener> contextListeners = new CopyOnWriteArrayList<>();
    private final List<AnalyzerCallback> analyzerCallbacks = new CopyOnWriteArrayList<>();
    // Listeners that want to be notified when the Service (models/stt) is reinitialized.
    private final List<Runnable> serviceReloadListeners = new CopyOnWriteArrayList<>();
    private final LowMemoryWatcherManager lowMemoryWatcherManager;

    // balance-notification state
    private boolean lowBalanceNotified = false;
    private boolean freeTierNotified = false;

    // BuildAgent task tracking for cancellation
    private volatile @Nullable CompletableFuture<BuildDetails> buildAgentFuture;

    // Style guide generation completion tracking
    private volatile CompletableFuture<String> styleGuideFuture = CompletableFuture.completedFuture("");

    // Track whether style guide generation was skipped due to missing Git
    // Used by PostGitStyleRegenerationStep to offer regeneration after Git is configured
    private volatile boolean styleGenerationSkipped = false;

    // Service reload state to prevent concurrent reloads
    private final AtomicBoolean isReloadingService = new AtomicBoolean(false);

    // Publicly exposed flag for the exact TaskScope window
    private final AtomicBoolean taskScopeInProgress = new AtomicBoolean(false);

    private boolean sessionsSyncActive = false;

    // Indicates whether this ContextManager was initialized in headless mode (no Swing UI).
    // Default is false; set to true by createHeadless(...).
    private boolean headlessMode = false;

    @SuppressWarnings("NullAway.Init")
    private AbstractWatchService watchService;

    @Override
    public ExecutorService getBackgroundTasks() {
        return backgroundTasks;
    }

    @Override
    public void addContextListener(ContextListener listener) {
        contextListeners.add(listener);
    }

    @Override
    public void removeContextListener(ContextListener listener) {
        contextListeners.remove(listener);
    }

    @Override
    public void addAnalyzerCallback(AnalyzerCallback callback) {
        analyzerCallbacks.add(callback);
    }

    @Override
    public void removeAnalyzerCallback(AnalyzerCallback callback) {
        analyzerCallbacks.remove(callback);
    }

    /**
     * Register a Runnable to be invoked when the Service (models / STT) is reinitialized. The Runnable is executed on
     * the EDT to allow UI updates.
     */
    public void addServiceReloadListener(Runnable listener) {
        serviceReloadListeners.add(listener);
    }

    /** Remove a previously registered service reload listener. */
    public void removeServiceReloadListener(Runnable listener) {
        serviceReloadListeners.remove(listener);
    }

    public void addFileChangeListener(AbstractWatchService.Listener listener) {
        watchService.addListener(listener);
    }

    public void removeFileChangeListener(AbstractWatchService.Listener listener) {
        watchService.removeListener(listener);
    }

    public ContextManager(IProject project) {
        this(project, new ServiceWrapper());
    }

    public ContextManager(IProject project, Service.Provider serviceProvider) {
        this.project = project;

        this.contextHistory = new ContextHistory(new Context(this));
        this.serviceProvider = serviceProvider;
        this.serviceProvider.reinit(project);

        // Initialize exception reporter with lazy service access
        this.exceptionReporter = new ExceptionReporter(this.serviceProvider::get);

        // set up global tools
        this.toolRegistry = ToolRegistry.empty()
                .builder()
                .register(new SearchTools(this))
                .register(new GitTools(this))
                .build();

        // dummy ConsoleIO until Chrome is constructed; necessary because Chrome starts submitting background tasks
        // immediately during construction, which means our own reference to it will still be null
        this.io = new IConsoleIO() {
            @Override
            public void toolError(String msg, String title) {
                logger.info(msg);
            }

            @Override
            public void llmOutput(String token, ChatMessageType type, LlmOutputMeta meta) {
                // pass
            }
        };
        this.userActions = new UserActionManager(this.io);

        // Begin monitoring for excessive memory usage
        this.lowMemoryWatcherManager = new LowMemoryWatcherManager(this.backgroundTasks);
        this.lowMemoryWatcherManager.registerWithStrongReference(
                () -> LowMemoryWatcherManager.LowMemoryWarningManager.alertUser(this.io),
                LowMemoryWatcher.LowMemoryWatcherType.ONLY_AFTER_GC);

        this.currentSessionId = SessionManager.newSessionId();
    }

    private void initializeCurrentSessionAndHistory(boolean forceNew) {
        // Core session selection/creation: determine which session id to load.
        var lastActiveSessionId = ((AbstractProject) project).getLastActiveSession();
        var sessionManager = project.getSessionManager();
        var sessions = sessionManager.listSessions();
        UUID sessionIdToLoad;
        if (forceNew
                || lastActiveSessionId.isEmpty()
                || sessions.stream().noneMatch(s -> s.id().equals(lastActiveSessionId.get()))) {
            var newSessionInfo = sessionManager.newSession(DEFAULT_SESSION_NAME);
            sessionIdToLoad = newSessionInfo.id();
            logger.info("Created and loaded new session: {}", newSessionInfo.id());
        } else {
            // Try to resume the last active session for this worktree
            sessionIdToLoad = lastActiveSessionId.get();
            logger.info("Resuming last active session {}", sessionIdToLoad);
        }

        // Set the current session id (core state) before attempting to load history.
        this.currentSessionId = sessionIdToLoad;

        // Load session contents (core operation). This may return null when history is corrupted/unreadable.
        var loadedCH = sessionManager.loadHistoryAndRefresh(currentSessionId, this);
        if (loadedCH == null) {
            if (forceNew) {
                // Create a fresh history for the new session
                contextHistory = new ContextHistory(new Context(this));
            } else {
                // Retry once by forcing a new session to avoid infinite recursion by preserving early exit semantics.
                initializeCurrentSessionAndHistory(true);
                return;
            }
        } else {
            contextHistory = loadedCH;
        }

        // Persist active session information (core) and perform post-activation steps that are non-GUI.
        updateActiveSession(currentSessionId);

        // Finalize activation: run core migration and notify listeners/UI in a mode-safe way.
        finalizeSessionActivation(currentSessionId);

        // Continue with optional migration/sync tasks.
        migrateToSessionsV3IfNeeded().thenRun(this::submitSessionSyncIfActive);
    }

    private CompletableFuture<Void> migrateToSessionsV3IfNeeded() {
        if (project instanceof MainProject mainProject && !mainProject.isMigrationsToSessionsV3Complete()) {
            return submitBackgroundTask("Quarantine unreadable sessions", () -> {
                var sessionManager = project.getSessionManager();

                // Scan .zip files directly and quarantine unreadable ones; exercise history loading to trigger
                // migration
                var report = sessionManager.quarantineUnreadableSessions(this);

                // Mark migration pass complete to avoid re-running on subsequent startups
                mainProject.setMigrationsToSessionsV3Complete(true);

                // Log and refresh UI if anything was moved
                logger.info("Quarantine complete; moved {} unreadable session zip(s).", report.movedCount());
                if (report.movedCount() > 0 && io instanceof Chrome) {
                    project.sessionsListChanged();
                }

                // If the active session was unreadable, create a new session and notify the user
                if (report.quarantinedSessionIds().contains(currentSessionId)) {
                    createOrReuseSession(DEFAULT_SESSION_NAME);
                    if (isHeadlessMode()) {
                        // In headless mode there is no EDT/Swing; call notification synchronously
                        io.systemNotify(
                                "Your previously active session was unreadable and has been moved to the 'unreadable' folder. A new session has been created.",
                                "Session Quarantined",
                                JOptionPane.WARNING_MESSAGE);
                    } else {
                        SwingUtilities.invokeLater(() -> io.systemNotify(
                                "Your previously active session was unreadable and has been moved to the 'unreadable' folder. A new session has been created.",
                                "Session Quarantined",
                                JOptionPane.WARNING_MESSAGE));
                    }
                }
            });
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Called from Brokk to finish wiring up references to Chrome and Coder
     *
     * <p>Returns the future doing off-EDT context loading
     */
    public CompletableFuture<Void> createGui() {
        assert SwingUtilities.isEventDispatchThread();

        // Ensure guides are loaded/generated off EDT
        // (Chrome's constructor calls scheduleGitConfigurationAfterInit which needs the styleGuideFuture)
        styleGuideFuture = ensureGuidesAsync();

        Path globalGitignorePath = null;
        if (project instanceof AbstractProject abstractProject) {
            globalGitignorePath = abstractProject.getGlobalGitignorePath().orElse(null);
        }

        // Use project.getRoot() for gitRepoRoot so worktrees resolve their external git metadata
        this.watchService = WatchServiceFactory.create(
                project.getRoot(),
                project.hasGit() ? project.getRoot() : null,
                globalGitignorePath,
                List.of() // Start with empty listeners
                );
        watchService.start(CompletableFuture.completedFuture(null));

        this.io = new Chrome(this);
        this.toolRegistry.register(new UiTools((Chrome) this.io));
        this.userActions.setIo(this.io);

        // Check if project root is outside git repository and show warning
        if (project.getRepo() instanceof GitRepo gitRepo) {
            var workTreeRoot = gitRepo.getWorkTreeRoot();
            var projectRoot = project.getRoot();
            if (!projectRoot.startsWith(workTreeRoot)) {
                String message = String.format(
                        "This project is outside the git repository.%n%n" + "Project: %s%n"
                                + "Git repository: %s%n%n"
                                + "Gitignore filtering cannot be applied correctly and will be disabled.%n"
                                + "Files that should be ignored may appear in the project.%n%n"
                                + "To fix this, open the project from within the git repository root.",
                        projectRoot, workTreeRoot);
                this.io.systemNotify(message, "Gitignore Configuration Warning", JOptionPane.WARNING_MESSAGE);
            }
        }

        var analyzerListener = createAnalyzerListener();
        // Create AnalyzerWrapper with injected watch service
        this.analyzerWrapper = new AnalyzerWrapper(project, analyzerListener, watchService);

        // Add ContextManager's file watch listener dynamically
        var fileWatchListener = createFileWatchListener();
        watchService.addListener(fileWatchListener);

        this.sessionsSyncActive = MainProject.getProxySetting() != MainProject.LlmProxySetting.LOCALHOST
                && !MainProject.getBrokkKey().isBlank()
                && !project.getRemoteProjectName().isBlank();

        // Load saved context history or create a new one
        CompletableFuture<Void> contextTask = submitBackgroundTask("Loading saved context", () -> {
            try {
                initializeCurrentSessionAndHistory(false);
            } finally {
                io.hideSessionSwitchSpinner();
            }
        });

        // Ensure build details are loaded/generated asynchronously
        // (style guide is handled by ensureGuidesAsync() called earlier)
        ensureBuildDetailsAsync();
        cleanupOldHistoryAsync();

        checkBalanceAndNotify();

        return contextTask;
    }

    private AnalyzerListener createAnalyzerListener() {
        // anything heavyweight needs to be moved off the listener thread since these are invoked by
        // the single-threaded analyzer executor

        return new AnalyzerListener() {
            @Override
            public void onBlocked() {
                if (userActions.isCurrentThreadCancelableAction()) {
                    io.systemNotify(
                            AnalyzerWrapper.ANALYZER_BUSY_MESSAGE,
                            AnalyzerWrapper.ANALYZER_BUSY_TITLE,
                            JOptionPane.INFORMATION_MESSAGE);
                }
            }

            @Override
            public void beforeEachBuild() {
                if (io instanceof Chrome chrome) {
                    chrome.showAnalyzerRebuildStatus();
                }

                // Notify analyzer callbacks
                for (var callback : analyzerCallbacks) {
                    submitBackgroundTask("Code Intelligence pre-build", callback::beforeEachBuild);
                }
            }

            @Override
            public void afterEachBuild(boolean externalRequest) {
                submitBackgroundTask("Code Intelligence post-build", () -> {
                    if (io instanceof Chrome chrome) {
                        chrome.hideAnalyzerRebuildStatus();
                    }

                    // Wait for context load to finish, with a timeout
                    long startTime = System.currentTimeMillis();
                    long timeoutMillis = 5000; // 5 seconds
                    while (liveContext().isEmpty() && (System.currentTimeMillis() - startTime < timeoutMillis)) {
                        Thread.onSpinWait();
                    }
                    if (liveContext().isEmpty()) {
                        logger.warn(
                                "Context did not load within 5 seconds after analyzer build. Continuing with empty context.");
                    }

                    // Update context w/ new analyzer
                    // ignore "load external changes" done by the build agent itself
                    // the build agent pauses the analyzer, which is our indicator
                    if (!requireNonNull(analyzerWrapper).isPause()) {
                        processExternalFileChangesIfNeeded();
                        io.updateWorkspace();
                    }

                    if (externalRequest && io instanceof Chrome chrome) {
                        chrome.notifyActionComplete("Analyzer rebuild completed");
                    }

                    // Notify analyzer callbacks
                    for (var callback : analyzerCallbacks) {
                        submitBackgroundTask(
                                "Code Intelligence post-build", () -> callback.afterEachBuild(externalRequest));
                    }
                });
            }

            @Override
            public void onAnalyzerReady() {
                logger.debug("Analyzer became ready, triggering symbol lookup refresh");
                for (var callback : analyzerCallbacks) {
                    submitBackgroundTask("Code Intelligence ready", callback::onAnalyzerReady);
                }
            }

            @Override
            public void onProgress(int completed, int total, String description) {
                // Update progress bar on "Rebuilding Code Intelligence" status strip
                if (io instanceof Chrome chrome) {
                    chrome.updateAnalyzerProgress(completed, total, description);
                }
            }
        };
    }

    /**
     * Creates a file watch listener that receives raw file system events directly from IWatchService.
     * This listener handles git metadata changes, tracked file changes, and preview window refreshes.
     */
    AbstractWatchService.Listener createFileWatchListener() {
        Path gitRepoRoot = project.hasGit() ? project.getRepo().getGitTopLevel() : null;
        FileWatcherHelper helper = new FileWatcherHelper(gitRepoRoot);

        return batch -> {
            logger.trace("ContextManager file watch listener received events batch: {}", batch);

            // Handle untracked gitignore file changes by invalidating project cache
            // (must happen before classification so trackedFiles reflects new gitignore rules)
            if (batch.isUntrackedGitignoreChanged()) {
                logger.debug("Untracked gitignore files changed, invalidating project file cache");
                project.invalidateAllFiles();
            }

            // Classify the changes using helper
            var trackedFiles = project.getRepo().getTrackedFiles();
            var classification = helper.classifyChanges(batch, trackedFiles);

            // 1) Handle git metadata changes
            if (classification.gitMetadataChanged) {
                logger.debug("Git metadata changes detected by ContextManager");
                handleGitMetadataChange();
            }

            // 2) Handle tracked file changes
            if (classification.trackedFilesChanged) {
                logger.debug(
                        "Tracked file changes detected by ContextManager ({} files)",
                        classification.changedTrackedFiles.size());
                handleTrackedFileChange(classification.changedTrackedFiles);
            }
        };
    }

    /**
     * Handles git metadata changes (.git directory modifications).
     * This includes branch switches, commits, pulls, etc.
     */
    void handleGitMetadataChange() {
        try {
            var branch = project.getRepo().getCurrentBranch();
            logger.debug("Git metadata changed, current branch: {}", branch);
        } catch (Exception e) {
            logger.debug("Unable to get current branch after git change", e);
        }

        project.getRepo().invalidateCaches();
        io.updateGitRepo();

        // Notify analyzer callbacks
        for (var callback : analyzerCallbacks) {
            submitBackgroundTask("Update for Git changes", callback::onRepoChange);
        }
    }

    /**
     * Gets the set of ProjectFiles currently in the context.
     * This includes files from PathFragments (ProjectPathFragment, GitFileFragment, ExternalPathFragment).
     *
     * @return Set of ProjectFiles in the current context
     */
    Set<ProjectFile> getContextFiles() {
        return liveContext()
                .allFragments()
                .filter(f -> f instanceof PathFragment)
                .map(f -> (PathFragment) f)
                .map(ContextFragments.PathFragment::file)
                .filter(bf -> bf instanceof ProjectFile)
                .map(bf -> (ProjectFile) bf)
                .collect(Collectors.toSet());
    }

    /**
     * Handles tracked file changes (modifications to files tracked by git).
     * This refreshes UI components that display file contents.
     * <p>
     * Phase 6 optimization: Checks if changed files are in context before refreshing workspace.
     *
     * @param changedFiles Set of files that changed (may be empty for backward compatibility)
     */
    void handleTrackedFileChange(Set<ProjectFile> changedFiles) {
        submitBackgroundTask("Update for FS changes", () -> {
            // Invalidate caches
            project.getRepo().invalidateCaches();
            project.invalidateAllFiles();

            // Phase 6 optimization: Only check for context file changes if we have specific changed files
            boolean contextFilesChanged = false;
            if (!changedFiles.isEmpty()) {
                Set<ProjectFile> contextFiles = getContextFiles();
                contextFilesChanged = changedFiles.stream().anyMatch(contextFiles::contains);
                logger.debug(
                        "Tracked files changed: {} total, {} in context",
                        changedFiles.size(),
                        contextFilesChanged ? "some" : "none");
            } else {
                // Backward compatibility path or overflow - assume context may have changed
                contextFilesChanged = true;
            }

            // Update workspace only if context files were affected
            if (contextFilesChanged && processExternalFileChangesIfNeeded(changedFiles)) {
                // analyzer refresh will call this too, but it will be delayed
                io.updateWorkspace();
                logger.debug("Workspace updated due to context file changes");
            }
        });

        // Notify analyzer callbacks - they determine their own filtering
        for (var callback : analyzerCallbacks) {
            submitBackgroundTask("Update for FS changes", callback::onTrackedFileChange);
        }
    }

    @Override
    public void notifyLiveDependenciesChanged() {
        for (var callback : analyzerCallbacks) {
            submitBackgroundTask("Update for dependency changes", callback::onLiveDependenciesChanged);
        }
    }

    /** Submits a background task to clean up old LLM session history directories. */
    private void cleanupOldHistoryAsync() {
        submitBackgroundTask("Cleaning up LLM history", this::cleanupOldHistory);
    }

    /**
     * Scans the LLM history directory (located within the master project's .brokk/sessions) and deletes subdirectories
     * (individual session zips) whose last modified time is older than one week. This method runs synchronously but is
     * intended to be called from a background task. Note: This currently cleans up based on Llm.getHistoryBaseDir which
     * might point to a different location than the new Project.sessionsDir. This should be unified. For now, using
     * Llm.getHistoryBaseDir. If Llm.getHistoryBaseDir needs project.getMasterRootPathForConfig(), it should be updated
     * there. Assuming Llm.getHistoryBaseDir correctly points to the shared LLM log location.
     */
    private void cleanupOldHistory() {
        var historyBaseDir = Llm.getHistoryBaseDir(project.getMasterRootPathForConfig());
        if (!Files.isDirectory(historyBaseDir)) {
            logger.debug("LLM history log directory {} does not exist, skipping cleanup.", historyBaseDir);
            return;
        }

        var cutoff = Instant.now().minus(Duration.ofDays(7));
        var deletedCount = new AtomicInteger(0);

        logger.trace("Scanning LLM history directory {} for entries modified before {}", historyBaseDir, cutoff);
        try (var stream = Files.list(historyBaseDir)) {
            stream.filter(Files::isDirectory) // Process only directories
                    .forEach(entry -> {
                        Instant lastModifiedTime;
                        try {
                            lastModifiedTime = Files.getLastModifiedTime(entry).toInstant();
                        } catch (IOException e) {
                            // Log error getting last modified time for a specific entry, but continue with others
                            logger.error("Error checking last modified time for history entry: {}", entry, e);
                            return;
                        }
                        if (lastModifiedTime.isBefore(cutoff)) {
                            logger.trace(
                                    "Attempting to delete old history directory (modified {}): {}",
                                    lastModifiedTime,
                                    entry);
                            if (FileUtil.deleteRecursively(entry)) {
                                deletedCount.incrementAndGet();
                            } else {
                                logger.error("Failed to fully delete old history directory: {}", entry);
                            }
                        }
                    });
        } catch (IOException e) {
            // Log error listing the base history directory itself
            logger.error("Error listing LLM history directory {}", historyBaseDir, e);
        }

        int count = deletedCount.get();
        if (count > 0) {
            logger.debug("Deleted {} old LLM history directories.", count);
        } else {
            logger.debug("No old LLM history directories found to delete.");
        }
    }

    @Override
    public IProject getProject() {
        return project;
    }

    @Override
    public IAnalyzerWrapper getAnalyzerWrapper() {
        return requireNonNull(analyzerWrapper);
    }

    @Override
    public IAnalyzer getAnalyzer() throws InterruptedException {
        return requireNonNull(analyzerWrapper).get();
    }

    @Override
    public IAnalyzer getAnalyzerUninterrupted() {
        try {
            return requireNonNull(analyzerWrapper).get();
        } catch (InterruptedException e) {
            throw new CancellationException(e.getMessage());
        }
    }

    /**
     * Return the currently selected FROZEN context from history in the UI. For operations, use topContext() to get the
     * live context.
     */
    public @Nullable Context selectedContext() {
        return contextHistory.getSelectedContext();
    }

    /**
     * Returns the current live, dynamic Context. Besides being dynamic (they load their text() content on demand, based
     * on the current files and Analyzer), live Fragments have a working sources() implementation.
     */
    @Override
    public Context liveContext() {
        return contextHistory.liveContext();
    }

    public Path getRoot() {
        return project.getRoot();
    }

    /**
     * Returns true when this ContextManager is running in headless mode (no Swing UI).
     *
     * Package-private on purpose: allows internal callers in the same package to branch behavior
     * without exposing headless concerns to external callers.
     */
    boolean isHeadlessMode() {
        return headlessMode;
    }

    /** Returns the Models instance associated with this context manager. */
    @Override
    public AbstractService getService() {
        return serviceProvider.get();
    }

    @Override
    public void reportException(Throwable th) {
        reportException(th, Map.of());
    }

    @Override
    public void reportException(Throwable th, Map<String, String> optionalFields) {
        CompletableFuture.runAsync(() -> {
            exceptionReporter.reportException(th, optionalFields);
        });
    }

    /**
     * "Exclusive actions" are short-lived, local actions that prevent new LLM actions from being started while they
     * run; only one will run at a time. These will NOT be wired up to cancellation mechanics.
     */
    public CompletableFuture<Void> submitExclusiveAction(Runnable task) {
        return userActions.submitExclusiveAction(task);
    }

    public <T> CompletableFuture<T> submitExclusiveAction(Callable<T> task) {
        return userActions.submitExclusiveAction(task);
    }

    /**
     * THE PROVIDED TASK IS RESPONSIBLE FOR HANDLING InterruptedException WITHOUT PROPAGATING IT FURTHER.
     */
    public CompletableFuture<Void> submitLlmAction(ThrowingRunnable task) {
        return userActions.submitLlmAction(task);
    }

    // TODO should we just merge ContextTask w/ BackgroundTask?
    public CompletableFuture<Void> submitContextTask(Runnable task) {
        return contextActionExecutor.submit(task);
    }

    /** Attempts to re‑interrupt the thread currently executing a user‑action task. Safe to call repeatedly. */
    public void interruptLlmAction() {
        userActions.cancelActiveAction();
    }

    /** Add the given files to editable. */
    @Override
    public void addFiles(Collection<ProjectFile> files) {
        addPathFragments(toPathFragments(files));
    }

    /** Add the given files to editable. */
    public void addPathFragments(List<? extends PathFragment> fragments) {
        if (fragments.isEmpty()) {
            return;
        }
        // addFragments already handles semantic deduplication via hasSameSource
        pushContext(currentLiveCtx -> currentLiveCtx.addFragments(fragments));
        String message = "Edit " + contextDescription(fragments);
        io.showNotification(IConsoleIO.NotificationRole.INFO, message);
    }

    /** Drop all context. */
    public void dropAll() {
        pushContext(Context::removeAll);
    }

    /** Drop fragments by their IDs. */
    public void drop(Collection<? extends ContextFragment> fragments) {
        pushContext(currentLiveCtx -> currentLiveCtx.removeFragments(fragments));
        String message = "Remove " + contextDescription(fragments);
        io.showNotification(IConsoleIO.NotificationRole.INFO, message);
    }

    /** Clear conversation history and task list. */
    public void clearHistory() {
        pushContext(currentLiveCtx -> currentLiveCtx.clearHistory().withTaskList(new TaskList.TaskListData(List.of())));
    }

    /** Clear conversation history only, preserving the task list. */
    public void clearHistoryOnly() {
        pushContext(Context::clearHistory);
    }

    /**
     * Drops fragments with HISTORY-aware semantics: - If selection is empty: drop all and reset selected context to the
     * latest (top) context. - If selection includes HISTORY: clear history, then drop only non-HISTORY fragments. -
     * Else: drop the selected fragments as-is.
     */
    public void dropWithHistorySemantics(Collection<? extends ContextFragment> selectedFragments) {
        if (selectedFragments.isEmpty()) {
            if (liveContext().isEmpty()) {
                return;
            }
            dropAll();
            return;
        }

        boolean hasHistory =
                selectedFragments.stream().anyMatch(f -> f.getType() == ContextFragment.FragmentType.HISTORY);

        if (hasHistory) {
            clearHistory();
            var nonHistory = selectedFragments.stream()
                    .filter(f -> f.getType() != ContextFragment.FragmentType.HISTORY)
                    .toList();
            if (!nonHistory.isEmpty()) {
                drop(nonHistory);
            }
        } else {
            drop(selectedFragments);
        }
    }

    /**
     * Drops a single history entry by its sequence number. If the sequence is not found in the current top context's
     * history, this is a no-op.
     *
     * <p>Creates a new context state with: - updated task history (with the entry removed), and - null parsedOutput.
     *
     * <p>Special behavior: - sequence == -1 means "drop the last item of the history"
     *
     * @param sequence the TaskEntry.sequence() to remove, or -1 to remove the last entry
     */
    public void dropHistoryEntryBySequence(int sequence) {
        var currentHistory = liveContext().getTaskHistory();

        if (currentHistory.isEmpty()) {
            return;
        }

        final int seqToDrop = (sequence == -1) ? currentHistory.getLast().sequence() : sequence;

        var newHistory = currentHistory.stream()
                .filter(entry -> entry.sequence() != seqToDrop)
                .toList();

        // If nothing changed, return early
        if (newHistory.size() == currentHistory.size()) {
            return;
        }

        // Push an updated context with the modified history
        pushContext(currentLiveCtx -> currentLiveCtx.withHistory(newHistory).withParsedOutput(null));

        io.showNotification(IConsoleIO.NotificationRole.INFO, "Remove history entry " + seqToDrop);
    }

    /** request code-intel rebuild */
    @Override
    public void requestRebuild() {
        project.getRepo().invalidateCaches();
        requireNonNull(analyzerWrapper).requestRebuild();
    }

    /** undo last context change */
    public Future<?> undoContextAsync() {
        return submitExclusiveAction(() -> {
            if (undoContext()) {
                io.showNotification(IConsoleIO.NotificationRole.INFO, "Undo most recent step");
            } else {
                io.showNotification(IConsoleIO.NotificationRole.INFO, "Nothing to undo");
            }
            return null;
        });
    }

    @Override
    @Blocking
    public boolean undoContext() throws InterruptedException {
        return withContextResolvedAndWatcherPaused(() -> {
            UndoResult result = contextHistory.undo(1, io, project);
            if (result.wasUndone()) {
                notifyContextListeners(liveContext());
                project.getSessionManager()
                        .saveHistory(contextHistory, currentSessionId); // Save history of frozen contexts
                if (!result.changedFiles().isEmpty()) {
                    requireNonNull(analyzerWrapper).updateFiles(result.changedFiles());
                }
                return true;
            }
            return false;
        });
    }

    /** undo changes until we reach the target FROZEN context */
    public Future<?> undoContextUntilAsync(Context targetFrozenContext) {
        return submitExclusiveAction(() -> {
            UndoResult result = withContextResolvedAndWatcherPaused(
                    () -> contextHistory.undoUntil(targetFrozenContext, io, project));
            if (result.wasUndone()) {
                notifyContextListeners(liveContext());
                project.getSessionManager().saveHistory(contextHistory, currentSessionId);
                String message = "Undid " + result.steps() + " step" + (result.steps() > 1 ? "s" : "") + "!";
                io.showNotification(IConsoleIO.NotificationRole.INFO, message);
                if (!result.changedFiles().isEmpty()) {
                    requireNonNull(analyzerWrapper).updateFiles(result.changedFiles());
                }
            } else {
                io.showNotification(IConsoleIO.NotificationRole.INFO, "Context not found or already at that point");
            }
            return null;
        });
    }

    /** redo last undone context */
    public Future<?> redoContextAsync() {
        return submitExclusiveAction(() -> {
            ContextHistory.RedoResult redoResult =
                    withContextResolvedAndWatcherPaused(() -> contextHistory.redo(io, project));
            if (redoResult.wasRedone()) {
                notifyContextListeners(liveContext());
                project.getSessionManager().saveHistory(contextHistory, currentSessionId);
                io.showNotification(IConsoleIO.NotificationRole.INFO, "Redo!");
                if (!redoResult.changedFiles().isEmpty()) {
                    requireNonNull(analyzerWrapper).updateFiles(redoResult.changedFiles());
                }
            } else {
                io.showNotification(IConsoleIO.NotificationRole.INFO, "no redo state available");
            }
            return null;
        });
    }

    /**
     * Reset the live context to match the files and fragments from a historical context. A new state
     * representing this reset is pushed to history.
     */
    public Future<?> resetContextToAsync(Context targetContext) {
        return submitExclusiveAction(() -> {
            var newLive = Context.createFrom(
                            targetContext, liveContext(), liveContext().getTaskHistory())
                    .copyAndRefresh();
            contextHistory.pushContext(newLive);
            contextHistory.addResetEdge(targetContext, newLive);
            SwingUtilities.invokeLater(() -> notifyContextListeners(newLive));
            project.getSessionManager().saveHistory(contextHistory, currentSessionId);
            io.showNotification(IConsoleIO.NotificationRole.INFO, "Reset workspace to historical state");
        });
    }

    /**
     * Copies fragments from a historical context to the current live context.
     * For HistoryFragment items, appends their task entries to the current live history.
     * For other fragments, adds them via addFragments.
     *
     * @param fragments The fragments to copy to the current context.
     * @return A Future representing the completion of the task.
     */
    public Future<?> copyFragmentsToCurrentContextAsync(List<ContextFragment> fragments) {
        if (fragments.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return submitExclusiveAction(() -> {
            // Separate history fragments from other fragments
            List<ContextFragments.HistoryFragment> historyFragments = new ArrayList<>();
            List<ContextFragment> otherFragments = new ArrayList<>();

            for (var fragment : fragments) {
                if (fragment instanceof ContextFragments.HistoryFragment historyFragment) {
                    historyFragments.add(historyFragment);
                } else {
                    otherFragments.add(fragment);
                }
            }

            // Build new history if history fragments were collected
            List<TaskEntry> newHistory = List.of();
            if (!historyFragments.isEmpty()) {
                List<TaskEntry> currentHistory = new ArrayList<>(liveContext().getTaskHistory());
                Set<TaskEntry> existingEntries = new HashSet<>(currentHistory);

                for (var historyFragment : historyFragments) {
                    for (TaskEntry entry : historyFragment.entries()) {
                        if (existingEntries.add(entry)) {
                            currentHistory.add(entry);
                        }
                    }
                }
                currentHistory.sort(Comparator.comparingInt(TaskEntry::sequence));
                newHistory = List.copyOf(currentHistory);
            }

            // Single atomic context update combining both history and other fragments
            final List<TaskEntry> finalNewHistory = newHistory;
            pushContext(currentLiveCtx -> {
                Context updated = currentLiveCtx;
                if (!finalNewHistory.isEmpty()) {
                    updated = updated.withHistory(finalNewHistory);
                }
                if (!otherFragments.isEmpty()) {
                    updated = updated.addFragments(otherFragments);
                }
                return updated;
            });

            // Build notification message
            String actionMessage;
            if (fragments.size() == 1) {
                String shortDesc = fragments.getFirst().shortDescription().renderNowOr("fragment");
                actionMessage = "Copy to current context: " + shortDesc;
            } else if (fragments.size() == 2) {
                String desc1 = fragments.get(0).shortDescription().renderNowOr("fragment");
                String desc2 = fragments.get(1).shortDescription().renderNowOr("fragment");
                actionMessage = "Copy to current context: " + desc1 + ", " + desc2;
            } else {
                actionMessage = "Copy to current context: " + fragments.size() + " fragments";
            }

            io.showNotification(IConsoleIO.NotificationRole.INFO, actionMessage);
        });
    }

    /**
     * Reset the live context and its history to match a historical context. A new state representing this
     * reset is pushed to history.
     */
    public Future<?> resetContextToIncludingHistoryAsync(Context targetContext) {
        return submitExclusiveAction(() -> {
            var newLive = Context.createFrom(targetContext, liveContext(), targetContext.getTaskHistory())
                    .copyAndRefresh();
            contextHistory.pushContext(newLive);
            contextHistory.addResetEdge(targetContext, newLive);
            SwingUtilities.invokeLater(() -> notifyContextListeners(newLive));
            project.getSessionManager().saveHistory(contextHistory, currentSessionId);
            io.showNotification(IConsoleIO.NotificationRole.INFO, "Reset workspace and history to historical state");
        });
    }

    /**
     * Handles pasting an image from the clipboard. Submits a task to summarize the image and adds a PasteImageFragment
     * to the context.
     *
     * @param image The java.awt.Image pasted from the clipboard.
     */
    public ContextFragments.AnonymousImageFragment addPastedImageFragment(
            Image image, @Nullable String descriptionOverride) {
        CompletableFuture<String> descriptionFuture;
        if (descriptionOverride != null && !descriptionOverride.isBlank()) {
            descriptionFuture = CompletableFuture.completedFuture(descriptionOverride);
        } else {
            descriptionFuture = submitSummarizePastedImage(image);
        }

        // Must be final for lambda capture in pushContext
        final var fragment = new ContextFragments.AnonymousImageFragment(this, image, descriptionFuture);
        pushContext(currentLiveCtx -> currentLiveCtx.addFragments(fragment));
        return fragment;
    }

    /**
     * Handles pasting an image from the clipboard without a predefined description. This will trigger asynchronous
     * summarization of the image.
     *
     * @param image The java.awt.Image pasted from the clipboard.
     */
    public void addPastedImageFragment(Image image) {
        addPastedImageFragment(image, null);
    }

    /**
     * Handles capturing text, e.g. from a code block in the MOP. Submits a task to summarize the text and adds a
     * PasteTextFragment to the context.
     *
     * @param text The text to capture.
     */
    public void addPastedTextFragment(String text) {
        var rawFuture = new DescribePasteWorker(this, text).execute();

        var descriptionFuture = rawFuture.thenApply(DescribePasteWorker.PasteInfo::description);
        var syntaxStyleFuture = rawFuture.thenApply(DescribePasteWorker.PasteInfo::syntaxStyle);

        var fragment = new ContextFragments.PasteTextFragment(this, text, descriptionFuture, syntaxStyleFuture);
        addFragments(fragment);
    }

    /** Captures text from the LLM output area and adds it to the context. Called from Chrome's capture button. */
    public void captureTextFromContextAsync() {
        submitContextTask(() -> {
            // Capture from the selected frozen context in history view
            var selectedFrozenCtx = requireNonNull(selectedContext()); // This is from history, frozen

            var history = selectedFrozenCtx.getTaskHistory();
            if (history.isEmpty()) {
                io.systemNotify("No content to capture", "Capture failed", JOptionPane.WARNING_MESSAGE);
                return;
            }

            var last = history.getLast();
            var log = last.log();
            if (log != null) {
                addFragments(log);
                return;
            }
            io.systemNotify("No content to capture", "Capture failed", JOptionPane.WARNING_MESSAGE);
        });
    }

    /** usage for identifier with control over including test files */
    public void usageForIdentifier(String identifier, boolean includeTestFiles) {
        var fragment = new ContextFragments.UsageFragment(this, identifier, includeTestFiles);
        pushContext(currentLiveCtx -> currentLiveCtx.addFragments(fragment));
        String message = "Added uses of " + identifier + (includeTestFiles ? " (including tests)" : "");
        io.showNotification(IConsoleIO.NotificationRole.INFO, message);
    }

    public void sourceCodeForCodeUnit(IAnalyzer analyzer, CodeUnit codeUnit) {
        String sourceCode = analyzer.getSource(codeUnit, true).orElse(null);

        if (sourceCode != null) {
            var fragment = new ContextFragments.StringFragment(
                    this,
                    sourceCode,
                    "Source code for " + codeUnit.fqName(),
                    codeUnit.source().getSyntaxStyle());
            pushContext(currentLiveCtx -> currentLiveCtx.addFragments(fragment));
            String message = "Add source code for " + codeUnit.shortName();
            io.showNotification(IConsoleIO.NotificationRole.INFO, message);
        } else {
            // Notify user of failed source capture
            SwingUtilities.invokeLater(() -> {
                io.systemNotify(
                        "Could not capture source code for: " + codeUnit.shortName()
                                + "\n\nThis may be due to unsupported symbol type or missing source ranges.",
                        "Capture Source Failed",
                        JOptionPane.WARNING_MESSAGE);
            });
        }
    }

    public void addCallersForMethod(String methodName, int depth, Map<String, List<CallSite>> callgraph) {
        if (callgraph.isEmpty()) {
            io.showNotification(
                    IConsoleIO.NotificationRole.INFO, "No callers found for " + methodName + " (pre-check).");
            return;
        }
        var fragment = new ContextFragments.UsageFragment(this, methodName);
        pushContext(currentLiveCtx -> currentLiveCtx.addFragments(fragment));
        io.showNotification(IConsoleIO.NotificationRole.INFO, "Add usages for callers of " + methodName);
    }

    /** callees for method */
    public void calleesForMethod(String methodName, int depth, Map<String, List<CallSite>> callgraph) {
        if (callgraph.isEmpty()) {
            io.showNotification(
                    IConsoleIO.NotificationRole.INFO, "No callees found for " + methodName + " (pre-check).");
            return;
        }
        var fragment = new ContextFragments.UsageFragment(this, methodName);
        pushContext(currentLiveCtx -> currentLiveCtx.addFragments(fragment));
        io.showNotification(IConsoleIO.NotificationRole.INFO, "Add usages for methods called by " + methodName);
    }

    /** parse stacktrace */
    public boolean addStacktraceFragment(StackTrace stacktrace) {
        var exception = requireNonNull(stacktrace.getExceptionType());
        var sources = new HashSet<CodeUnit>();
        var content = new StringBuilder();
        IAnalyzer localAnalyzer = getAnalyzerUninterrupted();

        for (var element : stacktrace.getFrames()) {
            var methodFullName = element.getClassName() + "." + element.getMethodName();
            localAnalyzer.getDefinitions(methodFullName).stream()
                    .findFirst()
                    .filter(CodeUnit::isFunction)
                    .ifPresent(methodCu -> {
                        var methodSource = localAnalyzer.getSource(methodCu, true);
                        if (methodSource.isPresent()) {
                            localAnalyzer.parentOf(methodCu).ifPresent(sources::add);
                            content.append(methodFullName).append(":\n");
                            content.append(methodSource.get()).append("\n\n");
                        }
                    });
        }

        if (content.isEmpty()) {
            logger.debug("No relevant methods found in stacktrace -- adding as text");
            return false;
        }
        var fragment = new ContextFragments.StacktraceFragment(
                this, sources, stacktrace.getOriginalText(), exception, content.toString());
        pushContext(currentLiveCtx -> currentLiveCtx.addFragments(fragment));
        return true;
    }

    /**
     * Summarize files and classes, adding skeleton fragments to the context.
     *
     * @param files A set of ProjectFiles to summarize (extracts all classes within them).
     * @param classes A set of specific CodeUnits (classes, methods, etc.) to summarize.
     * @return true if any summaries were successfully added, false otherwise.
     */
    public boolean addSummaries(Set<ProjectFile> files, Set<CodeUnit> classes) {
        IAnalyzer analyzer = getAnalyzerUninterrupted();
        if (analyzer.isEmpty()) {
            io.toolError("Code Intelligence is empty; nothing to add");
            return false;
        }

        List<ContextFragments.SummaryFragment> marshalledSummaries = new ArrayList<>();

        // Marshall SummaryFragments for files
        if (!files.isEmpty()) {
            for (var pf : files) {
                marshalledSummaries.add(new ContextFragments.SummaryFragment(
                        this, pf.toString(), ContextFragment.SummaryType.FILE_SKELETONS));
            }
        }

        // Marshall SummaryFragments for classes
        List<String> classFqns = classes.stream().map(CodeUnit::fqName).toList();
        if (!classFqns.isEmpty()) {
            for (var fqn : classFqns) {
                marshalledSummaries.add(
                        new ContextFragments.SummaryFragment(this, fqn, ContextFragment.SummaryType.CODEUNIT_SKELETON));
            }
        }

        if (marshalledSummaries.isEmpty()) {
            io.toolError("No files or classes provided to summarize.");
            return false;
        }

        // Atomic update to context
        addFragments(marshalledSummaries);

        // Notifications
        if (!files.isEmpty()) {
            io.showNotification(IConsoleIO.NotificationRole.INFO, "Summarize " + joinFilesForOutput(files));
        }
        if (!classFqns.isEmpty()) {
            io.showNotification(IConsoleIO.NotificationRole.INFO, "Summarize " + joinClassesForOutput(classFqns));
        }

        return true;
    }

    private static String joinClassesForOutput(List<String> classFqns) {
        var toJoin = classFqns.stream().sorted().toList();
        if (toJoin.size() <= 2) {
            return String.join(", ", toJoin);
        }
        return "%d classes".formatted(toJoin.size());
    }

    /**
     * Returns a short summary for a collection of context fragments: - If there are 0 fragments, returns "0 fragments".
     * - If there are 1 or 2 fragments, returns a comma-delimited list of their short descriptions. - Otherwise returns
     * "<count> fragments".
     *
     * <p>Note: Parameters are non-null by default in this codebase (NullAway).
     */
    private static String contextDescription(Collection<? extends ContextFragment> fragments) {
        int count = fragments.size();
        if (count == 0) {
            return "0 fragments";
        }
        if (count <= 2) {
            return fragments.stream()
                    .map(ContextFragment::shortDescription)
                    .map(cv -> cv.renderNowOr("<pending>"))
                    .collect(Collectors.joining(", "));
        }
        return count + " fragments";
    }

    private static String joinFilesForOutput(Collection<? extends BrokkFile> files) {
        var toJoin = files.stream().map(BrokkFile::getFileName).sorted().toList();
        if (files.size() <= 2) {
            return joinClassesForOutput(toJoin);
        }
        return "%d files".formatted(files.size());
    }

    public List<ChatMessage> getHistoryMessagesForCopy() {
        var taskHistory = liveContext().getTaskHistory();

        var messages = new ArrayList<ChatMessage>();
        var allTaskEntries = taskHistory.stream().map(TaskEntry::toString).collect(Collectors.joining("\n\n"));

        if (!allTaskEntries.isEmpty()) {
            messages.add(new UserMessage("<taskhistory>%s</taskhistory>".formatted(allTaskEntries)));
        }
        return messages;
    }

    /** Shutdown all executors */
    @Override
    public void close() {
        // we're not in a hurry when calling close(), this indicates a single window shutting down
        try {
            closeAsync(5_000).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.debug("Error while waiting to close ContextManager: {}", e.getMessage());
        }
    }

    public CompletableFuture<Void> closeAsync(long awaitMillis) {
        // Cancel BuildAgent task if still running
        if (buildAgentFuture != null && !buildAgentFuture.isDone()) {
            logger.debug("Cancelling BuildAgent task due to ContextManager shutdown");
            buildAgentFuture.cancel(true);
        }

        // Close watchers before shutting down executors that may be used by them
        if (analyzerWrapper != null) {
            analyzerWrapper.close();
        }
        lowMemoryWatcherManager.close();

        submitSessionSyncIfActive(); // Queue a final sync before shutting down the sync executor

        var syncExecutorFuture = syncExecutor.shutdownAndAwait(awaitMillis, "syncExecutor");
        var contextActionFuture = contextActionExecutor.shutdownAndAwait(awaitMillis, "contextActionExecutor");
        var backgroundFuture = backgroundTasks.shutdownAndAwait(awaitMillis, "backgroundTasks");
        var userActionsFuture = userActions.shutdownAndAwait(awaitMillis);

        return CompletableFuture.allOf(contextActionFuture, backgroundFuture, userActionsFuture, syncExecutorFuture)
                .whenComplete((v, t) -> project.close());
    }

    private CompletableFuture<Void> submitSessionSyncIfActive() {
        if (sessionsSyncActive) {
            return syncExecutor.submit(() -> {
                new SessionSynchronizer(this).synchronize();
                project.getMainProject().sessionsListChanged();
                return null;
            });
        } else {
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Queues a session sync operation and returns a future for tracking completion.
     * Used by dialogs that need to wait for sync before proceeding.
     */
    public CompletableFuture<Void> syncSessionsAsync() {
        return submitSessionSyncIfActive();
    }

    @Override
    public boolean isTaskInProgress() {
        return isLlmTaskInProgress() || isTaskScopeInProgress();
    }

    public boolean isLlmTaskInProgress() {
        return userActions.isLlmTaskInProgress();
    }

    /**
     * Returns true while a TaskScope is active, i.e. between io.setTaskInProgress(true) and io.setTaskInProgress(false).
     */
    public boolean isTaskScopeInProgress() {
        return taskScopeInProgress.get();
    }

    /** Returns current analyzer readiness without blocking. */
    public boolean isAnalyzerReady() {
        return requireNonNull(analyzerWrapper).getNonBlocking() != null;
    }

    /** Returns the current session's domain-model task list. Always non-null. */
    public TaskList.TaskListData getTaskList() {
        return liveContext().getTaskListDataOrEmpty();
    }

    /**
     * Create or replace the task list with an optional big picture explanation.
     */
    @Blocking
    @Override
    public Context createOrReplaceTaskList(
            Context context, @Nullable String bigPicture, List<TaskList.TaskItem> tasks) {
        var newData = new TaskList.TaskListData(bigPicture, List.copyOf(tasks));
        return deriveContextWithTaskList(context, newData);
    }

    /**
     * Replace the current session's task list and persist it via SessionManager. This is the single entry-point UI code
     * should call after modifying the task list.
     */
    public void setTaskListAsync(TaskList.TaskListData data) {
        submitContextTask(() -> {
            pushContext(currentLiveCtx -> currentLiveCtx.withTaskList(data));
        });
    }

    /**
     * Clear the task list for the current session.
     */
    public void clearTaskListAsync() {
        setTaskListAsync(new TaskList.TaskListData(null, List.of()));
    }

    public Context deriveContextWithTaskList(Context context, TaskList.TaskListData data) {
        return context.withTaskList(data);
    }

    private void finalizeSessionActivation(UUID sessionId) {
        // Core: Always migrate legacy Task List for the active session first.
        migrateLegacyTaskLists(sessionId);

        // Notify non-GUI listeners immediately so headless callers see the session as initialized.
        // UI updates that require Swing/EDT are only dispatched when not running in headless mode.
        // Note: notifyContextListeners is safe to call off-EDT as listeners are expected to handle non-blocking access.
        notifyContextListeners(liveContext());
        io.updateContextHistoryTable(liveContext());

        if (isHeadlessMode()) {
            // In headless mode, avoid any Swing/AWT calls. HeadlessConsole/IO should be no-ops or
            // appropriate for non-UI environments.
            return;
        }

        // For GUI mode, perform UI-affecting operations on the EDT.
        SwingUtilities.invokeLater(() -> {
            // Some IO implementations (Chrome) may perform additional UI wiring when enabling actions.
            if (io instanceof Chrome) {
                io.enableActionButtons();
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void migrateLegacyTaskLists(UUID sessionId) {
        try {
            // Prefer fragment-backed Task List if present
            var maybeFragment = liveContext().getTaskListFragment();
            if (maybeFragment.isPresent()) {
                return;
            }

            // Gate migration by session modified time against a fixed release cutoff
            var infoOpt = project.getSessionManager().listSessions().stream()
                    .filter(s -> s.id().equals(sessionId))
                    .findFirst();
            if (infoOpt.isEmpty()) {
                logger.debug("Skipping task list migration: no SessionInfo found for session {}", sessionId);
                return;
            }
            long modified = infoOpt.get().modified();
            if (modified >= TASKLIST_MIGRATION_CUTOFF_MS) {
                logger.debug(
                        "Skipping task list migration for session {} (modified {} >= cutoff {})",
                        sessionId,
                        modified,
                        TASKLIST_MIGRATION_CUTOFF_MS);
                return;
            }

            // if not, migrate from legacy tasklist.json
            var legacy = project.getSessionManager().readTaskList(sessionId).get(10, TimeUnit.SECONDS);
            if (!legacy.tasks().isEmpty()) {
                pushContext(currentLiveCtx -> currentLiveCtx.withTaskList(legacy));
                // Migration succeeded: drop legacy tasklist.json and log
                logger.debug("Migrated task list from legacy storage for session {}", sessionId);
                project.getSessionManager().deleteTaskList(sessionId);
            }
        } catch (Exception e) {
            logger.error("Unable to load task list for session {}", sessionId, e);
        }
    }

    /**
     * Execute a single task using ArchitectAgent with explicit options.
     *
     * @param task Task to execute (non-blank text).
     * @return TaskResult from ArchitectAgent execution.
     */
    public TaskResult executeTask(TaskList.TaskItem task) throws InterruptedException {
        var planningModel = io.getInstructionsPanel().getSelectedModel();
        var codeModel = getCodeModel();
        return executeTask(task, planningModel, codeModel);
    }

    public TaskResult executeTask(
            TaskList.TaskItem task, StreamingChatModel planningModel, StreamingChatModel codeModel)
            throws InterruptedException {
        // IMPORTANT: Use task.text() as the LLM prompt, NOT task.title().
        // The title is UI-only metadata for display/organization; the text is the actual task body.
        var prompt = task.text().strip();
        if (prompt.isEmpty()) {
            throw new IllegalArgumentException("Task text must be non-blank");
        }

        TaskResult result;
        var title = task.title().isBlank() ? task.text() : task.title();
        try (var scope = beginTask(prompt, true, true, "Task: " + title)) {
            var agent = new ArchitectAgent(this, planningModel, codeModel, prompt, scope);
            result = agent.executeWithScan();

            if (result.stopDetails().reason() == TaskResult.StopReason.SUCCESS) {
                new GitWorkflow(this).performAutoCommit(prompt);
                var ctx = markTaskDone(result.context(), task);
                result = result.withContext(ctx);
                scope.append(result);
            }
        } finally {
            // mirror panel behavior
            checkBalanceAndNotify();
        }

        return result;
    }

    /** Replace the given task with its 'done=true' variant. */
    private Context markTaskDone(Context context, TaskList.TaskItem task) {
        var tasks = context.getTaskListDataOrEmpty().tasks();

        // Find index: prefer exact match by ID, fall back to first incomplete task with matching text
        int idx = IntStream.range(0, tasks.size())
                .filter(i -> Objects.equals(tasks.get(i).id(), task.id()))
                .findFirst()
                .orElse(-1);

        if (idx < 0) {
            idx = IntStream.range(0, tasks.size())
                    .filter(i -> !tasks.get(i).done() && tasks.get(i).text().equals(task.text()))
                    .findFirst()
                    .orElse(-1);
        }

        if (idx < 0) {
            logger.error("Task not found !? {}", task.toString());
            return context;
        }

        var updated = new ArrayList<>(tasks);
        var original = tasks.get(idx);
        updated.set(idx, new TaskList.TaskItem(original.id(), original.title(), original.text(), true));
        return deriveContextWithTaskList(
                context,
                new TaskList.TaskListData(context.getTaskListDataOrEmpty().bigPicture(), List.copyOf(updated)));
    }

    private void captureGitState(Context ctx) {
        if (!project.hasGit()) {
            return;
        }

        try {
            var repo = project.getRepo();
            String commitHash = repo.getCurrentCommitId();
            String diff = repo.diff();

            var gitState = new ContextHistory.GitState(commitHash, diff.isEmpty() ? null : diff);
            logger.trace("Current git HEAD is {}", ((GitRepo) repo).shortHash(commitHash));
            contextHistory.addGitState(ctx.id(), gitState);
        } catch (GitAPIException e) {
            logger.error("Failed to capture git state", e);
        }
    }

    /**
     * Processes external file changes by refreshing fragments that reference the given files.
     * Returns true if a new context was pushed or replaced.
     */
    private boolean processExternalFileChangesIfNeeded(Set<ProjectFile> changedFiles) {
        var ctx = contextHistory.processExternalFileChangesIfNeeded(changedFiles);
        if (ctx != null) {
            contextPushed(ctx);
            return true;
        }
        return false;
    }

    /**
     * Convenience overload used when we don't have an explicit changed-files set (e.g., after analyzer rebuilds).
     * Refreshes any fragment that references any file in the current context.
     */
    @Blocking
    private void processExternalFileChangesIfNeeded() {
        // Avoid indefinite blocking on fragment computations. Time-bound each files() retrieval.
        var fragments = liveContext().allFragments().toList();
        Set<ProjectFile> allReferenced = new HashSet<>();
        for (var f : fragments) {
            try {
                var files = f.files().await(SNAPSHOT_AWAIT_TIMEOUT).orElseThrow(TimeoutException::new);
                allReferenced.addAll(files);
            } catch (TimeoutException te) {
                logger.warn("Timed out waiting for files() of fragment {}", f.id());
            }
        }
        processExternalFileChangesIfNeeded(allReferenced);
    }

    /**
     * Pushes context changes using a generator function. The generator is applied to the current `liveContext`. The
     * resulting context becomes the new `liveContext`. A frozen snapshot of this new `liveContext` is added to
     * `ContextHistory`.
     *
     * @param contextGenerator A function that takes the current live context and returns an updated context.
     * @return The new `liveContext`, or the existing `liveContext` if no changes were made by the generator.
     */
    @SuppressWarnings("RedundantNullCheck") // called during Chrome init while instructionsPanel is null
    @Override
    public Context pushContext(Function<Context, Context> contextGenerator) {
        var oldLiveContext = liveContext();
        var newLiveContext = contextHistory.push(contextGenerator);
        if (oldLiveContext.equals(newLiveContext)) {
            // No change occurred
            return newLiveContext;
        }

        contextPushed(contextHistory.liveContext());
        return newLiveContext;
    }

    private void contextPushed(Context context) {
        captureGitState(context);
        // Ensure listeners are notified on the EDT
        SwingUtilities.invokeLater(() -> notifyContextListeners(context));

        // Defer save until TaskScope closes to ensure group mappings are captured
        if (!taskScopeInProgress.get()) {
            project.getSessionManager().saveHistory(contextHistory, currentSessionId);
        }
    }

    /**
     * Updates the selected context in history from the UI. Called by Chrome when the user selects a row in the
     * history table.
     *
     * @param contextFromHistory The context selected in the UI.
     */
    public void setSelectedContext(Context contextFromHistory) {
        if (contextHistory.setSelectedContext(contextFromHistory)) {
            notifyContextListeners(contextFromHistory);
        }
    }

    /**
     * Notifies all registered context listeners of a context change.
     *
     * <p>Contexts passed here may be live (containing dynamic fragments with ComputedValue futures)
     * or previously frozen. Listeners should use non-blocking access methods (ComputedValue.tryGet()
     * or ComputedValue.await()) to retrieve fragment values without blocking the EDT.
     */
    private void notifyContextListeners(Context ctx) {
        for (var listener : contextListeners) {
            listener.contextChanged(ctx);
        }
    }

    private final ConcurrentMap<Callable<?>, String> taskDescriptions = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<String> summarizeTaskForConversation(String input) {
        return summarize(input, SummarizerPrompts.WORD_BUDGET_5);
    }

    public CompletableFuture<String> summarize(String input, int words) {
        var future = new CompletableFuture<String>();

        var worker = new SummarizeWorker(this, input, words) {
            @Override
            protected void done() {
                try {
                    future.complete(get()); // complete successfully
                } catch (Exception ex) {
                    future.completeExceptionally(ex);
                }
            }
        };

        worker.execute();
        return future;
    }

    /**
     * Submits a background task to summarize a pasted image. This uses the quickest model to generate
     * a short description.
     *
     * @param pastedImage The java.awt.Image that was pasted.
     * @return A CompletableFuture that will return the description string.
     */
    public CompletableFuture<String> submitSummarizePastedImage(Image pastedImage) {
        return submitBackgroundTask("Summarizing pasted image", () -> {
            try {
                // Convert AWT Image to LangChain4j Image (requires Base64 encoding)
                var l4jImage = ImageUtil.toL4JImage(pastedImage);
                var imageContent = ImageContent.from(l4jImage);

                // Create prompt messages for the LLM
                var textContent = TextContent.from(
                        "Briefly describe this image in a few words (e.g., 'screenshot of code', 'diagram of system').");
                var userMessage = UserMessage.from(textContent, imageContent);
                List<ChatMessage> messages = List.of(userMessage);

                Llm.StreamingResult result = getLlm(
                                serviceProvider.get().summarizeModel(),
                                "Summarize pasted image",
                                TaskResult.Type.SUMMARIZE)
                        .sendRequest(messages);

                if (result.error() != null) {
                    logger.warn("Image summarization failed or was cancelled.");
                    return "(Image summarization failed)";
                }
                var description = result.text();
                return description.isBlank() ? "(Image description empty)" : description.trim();
            } catch (IOException e) {
                logger.error("Failed to convert pasted image for summarization", e);
                return "(Error processing image)";
            } finally {
                SwingUtilities.invokeLater(io::postSummarize);
            }
        });
    }
    /** Submits a background task to the internal background executor (non-user actions). */
    @Override
    public <T> CompletableFuture<T> submitBackgroundTask(String taskDescription, Callable<T> task) {
        taskDescriptions.put(task, taskDescription);
        return backgroundTasks.submit(() -> {
            try {
                io.backgroundOutput(taskDescription);
                return task.call();
            } finally {
                // Remove this task from the map
                taskDescriptions.remove(task);
                int remaining = taskDescriptions.size();
                SwingUtilities.invokeLater(() -> {
                    if (remaining <= 0) {
                        io.backgroundOutput("");
                    } else if (remaining == 1) {
                        // Find the last remaining task description. If there's a race just end the spin
                        var lastTaskDescription =
                                taskDescriptions.values().stream().findFirst().orElse("");
                        io.backgroundOutput(lastTaskDescription);
                    } else {
                        io.backgroundOutput(
                                "Tasks running: " + remaining, String.join("\n", taskDescriptions.values()));
                    }
                });
            }
        });
    }

    /**
     * Ensures build details are loaded or inferred using BuildAgent if necessary. Runs asynchronously in the
     * background.
     */
    private synchronized void ensureBuildDetailsAsync() {
        if (project.hasBuildDetails()) {
            logger.debug("Using existing build details");
            return;
        }

        if (project.isEmptyProject()) {
            logger.debug("Project has no analyzable source files, skipping build details inference");
            if (!project.hasBuildDetails()) {
                project.saveBuildDetails(BuildDetails.EMPTY);
            }
            return;
        }

        // Check if a BuildAgent task is already in progress
        if (buildAgentFuture != null && !buildAgentFuture.isDone()) {
            logger.debug("BuildAgent task already in progress, skipping");
            return;
        }

        // No details found, run the BuildAgent asynchronously
        buildAgentFuture = submitBackgroundTask("Inferring build details", () -> {
            io.showNotification(IConsoleIO.NotificationRole.INFO, "Inferring project build details");

            // Check if task was cancelled before starting
            if (Thread.currentThread().isInterrupted()) {
                logger.debug("BuildAgent task cancelled before execution");
                return BuildDetails.EMPTY;
            }

            BuildAgent agent = new BuildAgent(
                    project,
                    getLlm(serviceProvider.get().getScanModel(), "Infer build details", TaskResult.Type.NONE),
                    toolRegistry);
            BuildDetails inferredDetails;
            try {
                inferredDetails = agent.execute();
            } catch (InterruptedException e) {
                logger.debug("BuildAgent execution interrupted");
                Thread.currentThread().interrupt();
                return BuildDetails.EMPTY;
            } catch (Exception e) {
                var msg =
                        "Build Information Agent did not complete successfully (aborted or errored). Build details not saved. Error: "
                                + e.getMessage();
                logger.error(msg, e);
                io.toolError(msg, "Build Information Agent failed");
                inferredDetails = BuildDetails.EMPTY;
            }

            // Check if task was cancelled after execution
            if (Thread.currentThread().isInterrupted()) {
                logger.debug("BuildAgent task cancelled after execution, not saving results");
                return BuildDetails.EMPTY;
            }

            project.saveBuildDetails(inferredDetails);

            // Show appropriate notification based on whether build details were found
            if (BuildDetails.EMPTY.equals(inferredDetails)) {
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "Could not determine build configuration - project structure may be unsupported or incomplete");
            } else {
                io.showNotification(IConsoleIO.NotificationRole.INFO, "Build details inferred and saved");
            }

            return inferredDetails;
        });
    }

    public void reloadService() {
        if (isReloadingService.compareAndSet(false, true)) {
            // Run reinit in the background so callers don't block; notify UI listeners when finished.
            submitBackgroundTask("Reloading service", () -> {
                try {
                    serviceProvider.reinit(project);
                    // Notify registered listeners on the EDT so they can safely update Swing UI.
                    SwingUtilities.invokeLater(() -> {
                        for (var l : serviceReloadListeners) {
                            try {
                                l.run();
                            } catch (Exception e) {
                                logger.warn("Service reload listener threw exception", e);
                            }
                        }
                    });
                } finally {
                    isReloadingService.set(false);
                }
                return null;
            });
        } else {
            logger.debug("Service reload already in progress, skipping request.");
        }
    }

    public <T> T withFileChangeNotificationsPaused(Callable<T> callable) {
        requireNonNull(analyzerWrapper).pause();
        try {
            return callable.call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            requireNonNull(analyzerWrapper).resume();
        }
    }

    @Blocking
    public <T> T withContextResolvedAndWatcherPaused(Callable<T> callable) throws InterruptedException {
        requireNonNull(analyzerWrapper).pause();
        liveContext().awaitContentsAreComputed(SNAPSHOT_AWAIT_TIMEOUT);
        try {
            return callable.call();
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            requireNonNull(analyzerWrapper).resume();
        }
    }

    @FunctionalInterface
    public interface TaskRunner {
        /**
         * Submits a background task with the given description.
         *
         * @param taskDescription a description of the task
         * @param task the task to execute
         * @param <T> the result type of the task
         * @return a {@link Future} representing pending completion of the task
         */
        <T> Future<T> submit(String taskDescription, Callable<T> task);
    }

    // Removed BuildCommand record

    /**
     * Ensures style guide exists, reading from disk off EDT.
     * Style guide is generated via LLM if missing.
     * Returns a CompletableFuture for the style guide content.
     */
    public CompletableFuture<String> ensureGuidesAsync() {
        return submitBackgroundTask("Loading project guides", () -> {
            // Handle style guide off EDT
            String existingStyleGuide = project.getStyleGuide();
            if (!existingStyleGuide.isEmpty()) {
                logger.info("Style guide already exists; skipping generation");
                return existingStyleGuide;
            }

            if (!project.hasGit()) {
                logger.info("No Git repository found, skipping style guide generation.");
                styleGenerationSkipped = true;
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO, "No Git repository found, skipping style guide generation.");
                return "";
            }

            // Generate style guide via LLM
            return generateStyleGuide();
        });
    }

    /** Generate style guide via LLM. Must be called off EDT. */
    private String generateStyleGuide() {
        try {
            io.showNotification(IConsoleIO.NotificationRole.INFO, "Generating project style guide...");
            // Use a reasonable limit for style guide generation context
            if (!(project.getRepo() instanceof GitRepo gitRepo)) {
                logger.warn(
                        "Expected GitRepo but found {}",
                        project.getRepo().getClass().getName());
                return "";
            }
            var topClasses = GitDistance.getMostImportantFiles(gitRepo, 10);

            if (topClasses.isEmpty()) {
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO, "No classes found via PageRank for style guide generation.");
                String fallbackContent =
                        "# Style Guide\n\n(Could not be generated automatically - no relevant classes found)\n";
                project.saveStyleGuide(fallbackContent);
                return fallbackContent;
            }

            var codeForLLM = new StringBuilder();
            var tokens = 0;
            int MAX_STYLE_TOKENS = 30000; // Limit context size for style guide
            for (var file : topClasses) {
                if (file.isBinary()) {
                    continue;
                }
                var contentOpt = file.read();
                // Use project root for relative path display if possible
                var relativePath = project.getRoot().relativize(file.absPath()).toString();
                if (contentOpt.isEmpty()) {
                    logger.warn("Skipping unreadable file {} for style guide", relativePath);
                    continue;
                }
                var chunk = "<file path=\"%s\">\n%s\n</file>\n".formatted(relativePath, contentOpt.get());
                // Calculate tokens and check limits
                var chunkTokens = Messages.getApproximateTokens(chunk);
                if (tokens > 0 && tokens + chunkTokens > MAX_STYLE_TOKENS) {
                    logger.debug("Style guide context limit ({}) reached after {} tokens.", MAX_STYLE_TOKENS, tokens);
                    break;
                }
                if (chunkTokens > MAX_STYLE_TOKENS) {
                    logger.debug(
                            "Skipping large file {} ({} tokens) for style guide context.", relativePath, chunkTokens);
                    continue;
                }
                codeForLLM.append(chunk);
                tokens += chunkTokens;
                logger.trace(
                        "Added {} ({} tokens, total {}) to style guide context", relativePath, chunkTokens, tokens);
            }

            if (codeForLLM.isEmpty()) {
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO, "No relevant code found for style guide generation");
                String fallbackContent = "# Style Guide\n\n(No relevant code found for generation)\n";
                project.saveStyleGuide(fallbackContent);
                return fallbackContent;
            }

            var messages = List.of(
                    new SystemMessage(
                            "You are an expert software engineer. Your task is to extract a concise coding style guide from the provided code examples."),
                    new UserMessage(
                            """
                            Based on these code examples, create a concise, clear coding style guide in Markdown format
                            that captures the conventions used in this codebase, particularly the ones that leverage new or uncommon features.
                            DO NOT repeat what are simply common best practices.

                            %s
                            """
                                    .formatted(codeForLLM)));

            var result = getLlm(serviceProvider.get().getScanModel(), "Generate style guide", TaskResult.Type.NONE)
                    .sendRequest(messages);
            if (result.error() != null) {
                String message =
                        "Failed to generate style guide: " + result.error().getMessage();
                io.showNotification(IConsoleIO.NotificationRole.INFO, message);
                String fallbackContent = "# Style Guide\n\n(Generation failed)\n";
                project.saveStyleGuide(fallbackContent);
                return fallbackContent;
            }
            var styleGuide = result.text();
            if (styleGuide.isBlank()) {
                io.showNotification(IConsoleIO.NotificationRole.INFO, "LLM returned empty style guide.");
                String fallbackContent = "# Style Guide\n\n(LLM returned empty result)\n";
                project.saveStyleGuide(fallbackContent);
                return fallbackContent;
            }
            project.saveStyleGuide(styleGuide);
            styleGenerationSkipped = false;

            String savedFileName;
            Path agentsPath = project.getMasterRootPathForConfig().resolve(AbstractProject.STYLE_GUIDE_FILE);
            if (Files.exists(agentsPath)) {
                savedFileName = "AGENTS.md";
            } else {
                savedFileName = ".brokk/style.md";
            }
            io.showNotification(
                    IConsoleIO.NotificationRole.INFO, "Style guide generated and saved to " + savedFileName);
            return styleGuide;
        } catch (Exception e) {
            logger.error("Error generating style guide", e);
            String fallbackContent = "# Style Guide\n\n(Error during generation)\n";
            project.saveStyleGuide(fallbackContent);
            return fallbackContent;
        }
    }

    /**
     * Returns the CompletableFuture tracking style guide generation completion.
     * Never returns null; always initialized to a completed future.
     */
    public CompletableFuture<String> getStyleGuideFuture() {
        return styleGuideFuture;
    }

    /**
     * Checks if style guide generation was skipped due to missing Git repository.
     * Used by onboarding to offer regeneration after Git is configured.
     */
    public boolean wasStyleGenerationSkipped() {
        return styleGenerationSkipped;
    }

    /**
     * Force regenerate style guide via LLM. Used after Git is configured.
     * Returns a CompletableFuture for the regenerated style guide content.
     */
    public CompletableFuture<String> regenerateStyleGuideAsync() {
        return submitBackgroundTask("Regenerating style guide", () -> {
            if (!project.hasGit()) {
                logger.info("No Git repository found, skipping style guide regeneration.");
                styleGenerationSkipped = true;
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "No Git repository found, skipping style guide regeneration.");
                return "";
            }
            return generateStyleGuide();
        });
    }

    // Reduced retry count for compression - it's non-critical and shouldn't block session navigation
    private static final int COMPRESSION_MAX_ATTEMPTS = 2;

    /**
     * Compresses a single TaskEntry into a summary string using the quickest model.
     * Preserves the original log and attaches the summary, so the AI uses the summary
     * while the UI can still display the full messages.
     *
     * @param entry The TaskEntry to compress.
     * @return A new TaskEntry with both log and summary, or the original entry if compression fails.
     * @throws InterruptedException if the operation is cancelled
     */
    @Blocking
    public TaskEntry compressHistory(TaskEntry entry) throws InterruptedException {
        if (entry.isCompressed()) {
            return entry;
        }

        // Check for interruption before starting LLM call
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Compression cancelled");
        }

        // Must have a log to compress
        if (!entry.hasLog() || entry.toString().isBlank()) {
            logger.warn("Cannot compress entry without a log: {}", entry);
            return entry;
        }

        // Compress the log into a summary
        var msgs = SummarizerPrompts.instance.compressHistory(entry.toString());
        Llm.StreamingResult result = getLlm(
                        serviceProvider.get().summarizeModel(), "Compress history entry", TaskResult.Type.SUMMARIZE)
                .sendRequest(msgs, COMPRESSION_MAX_ATTEMPTS);

        if (result.error() != null) {
            logger.warn("History compression failed for entry: {}", entry, result.error());
            return entry;
        }

        String summary = result.text();
        if (summary.isBlank()) {
            logger.warn("History compression resulted in empty summary for entry: {}", entry);
            return entry;
        }

        logger.debug("Compressed summary:\n{}", summary);
        // Create new entry with both original log and new summary
        return entry.withSummary(summary);
    }

    /** Begin a new aggregating scope with explicit compress-at-commit semantics and optional task description. */
    @Override
    public TaskScope beginTask(String input, boolean group, boolean compress, @Nullable String taskDescription) {
        // prepare MOP
        var history = liveContext().getTaskHistory();
        var messages = List.<ChatMessage>of(new UserMessage(input));
        var taskFragment = new ContextFragments.TaskFragment(this, messages, input);
        io.setLlmAndHistoryOutput(history, new TaskEntry(-1, taskFragment, null));

        // rename the session if needed
        var sessionManager = project.getSessionManager();
        var sessions = sessionManager.listSessions();
        var currentSession =
                sessions.stream().filter(s -> s.id().equals(currentSessionId)).findFirst();

        if (currentSession.isPresent()
                && DEFAULT_SESSION_NAME.equals(currentSession.get().name())) {
            var actionFuture = summarizeTaskForConversation(input);
            renameSessionAsync(currentSessionId, actionFuture).thenRun(() -> {
                if (io instanceof Chrome) {
                    project.sessionsListChanged();
                }
            });
        }

        return new TaskScope(group, compress, taskDescription);
    }

    /**
     * Aggregating scope that collects messages/files and commits once.
     * By design, this keeps only the Context from the final TaskResult in the Scope.
     * This means it is the agent's responsibility to propagate any sub-agents' Contexts
     * without losing important history.
     */
    public class TaskScope implements AutoCloseable {
        private final boolean group;
        private final boolean compress;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final UUID groupId = UUID.randomUUID();
        private final String groupLabel;

        private TaskScope(boolean group, boolean compress, @Nullable String taskDescription) {
            this.group = group;
            this.compress = compress;
            this.groupLabel = taskDescription == null ? "Task" : taskDescription;
            io.setTaskInProgress(true);
            taskScopeInProgress.set(true);

            requireNonNull(analyzerWrapper).pause();
        }

        /**
         * Appends a TaskResult to the context history and returns updated local context, optionally attaching metadata.
         * If meta is provided and the TaskResult does not already carry metadata, the metadata is attached before
         * creating the TaskEntry to ensure persistence in history.
         *
         * @param result   The TaskResult to append.
         */
        @Blocking
        public Context append(TaskResult result) throws InterruptedException {
            assert !closed.get() : "TaskScope already closed";

            // If interrupted before any LLM output, skip
            if (result.stopDetails().reason() == TaskResult.StopReason.INTERRUPTED
                    && result.output().messages().stream().noneMatch(m -> m instanceof AiMessage)) {
                logger.debug("Command cancelled before LLM responded — skipping publish");
                return result.context();
            }

            // If there is literally nothing to record (no messages and no content changes), skip
            if (result.output().messages().isEmpty()) {
                // Treat result.context() as new (right) and current topContext() as old (left)
                Context other = liveContext();
                var delta = ContextDelta.between(result.context(), other).join();
                if (delta.isEmpty()) {
                    logger.debug("Empty TaskResult delta, skipping publish");
                    return result.context();
                }
            }

            logger.debug("Adding session result to history. Reason: {}", result.stopDetails());

            // optionally compress
            var updated = result.context();
            TaskEntry entry = updated.createTaskEntry(result);

            // push context
            var updatedContext = pushContext(currentLiveCtx -> {
                return updated.addHistoryEntry(entry, result.output());
            });

            if (group) {
                UUID contextId = updatedContext.id();
                contextHistory.addContextToGroup(contextId, groupId, groupLabel);
            }

            // prepare MOP to display new history with the next streamed message
            // needed because after the last append (before close) the MOP should not update
            io.prepareOutputForNextStream(updatedContext.getTaskHistory());

            return updatedContext;
        }

        /**
         * Publishes an intermediate Context snapshot to history without finalizing a TaskResult.
         * This allows capturing checkpoints during long-running tasks.
         */
        public void publish(Context context) {
            assert !closed.get() : "TaskScope already closed";
            var newId = pushContext(currentLiveCtx -> context).id();
            contextHistory.addContextToGroup(newId, groupId, groupLabel);
        }

        @Override
        public void close() throws InterruptedException {
            if (!closed.compareAndSet(false, true)) return;

            closeInternal();

            requireNonNull(analyzerWrapper).resume();
        }

        protected void closeInternal() {
            // Save once now that all group mappings are in place
            project.getSessionManager().saveHistory(contextHistory, currentSessionId);

            SwingUtilities.invokeLater(() -> {
                // deferred cleanup
                taskScopeInProgress.set(false);
                io.setTaskInProgress(false);
            });

            if (compress) {
                var finalCtx = contextHistory.replaceTop(ctx -> {
                    try {
                        return compressHistory(ctx);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return ctx;
                    }
                });
                captureGitState(finalCtx);
            }
        }
    }

    public class AnonymousScope extends TaskScope {
        private AnonymousScope() {
            super(false, false, "");
        }

        @Override
        public Context append(TaskResult result) throws InterruptedException {
            return result.context();
        }

        @Override
        public void publish(Context context) {}

        @Override
        public void closeInternal() {}
    }

    @Override
    public AnonymousScope anonymousScope() {
        return new AnonymousScope();
    }

    public List<Context> getContextHistoryList() {
        return contextHistory.getHistory();
    }

    public ContextHistory getContextHistory() {
        return contextHistory;
    }

    /**
     * @return true if the currently selected context is live/top context.
     */
    public boolean isLive() {
        return Objects.equals(liveContext(), selectedContext());
    }

    @Override
    public UUID getCurrentSessionId() {
        return currentSessionId;
    }

    @Override
    public void reloadCurrentSessionAsync() {
        switchSessionAsync(getCurrentSessionId());
    }

    /**
     * Loads the ContextHistory for a specific session without switching to it. This allows viewing/inspecting session
     * history without changing the current session.
     *
     * @param sessionId The UUID of the session whose history to load
     * @return A CompletableFuture that resolves to the ContextHistory for the specified session
     */
    public CompletableFuture<ContextHistory> loadSessionHistoryAsync(UUID sessionId) {
        return LoggingFuture.supplyAsync(
                () -> project.getSessionManager().loadHistory(sessionId, this), backgroundTasks);
    }

    /**
     * Creates a new session with the given name and switches to it asynchronously. First attempts to reuse an existing
     * empty session before creating a new one.
     *
     * @param name The name for the new session
     * @return A CompletableFuture representing the completion of the session creation task
     */
    @Override
    public CompletableFuture<Void> createSessionAsync(String name) {
        // No explicit exclusivity check for new session, as it gets a new unique ID.
        return submitExclusiveAction(() -> {
            createOrReuseSession(name);
        });
    }

    private void createOrReuseSession(String name) {
        Optional<SessionInfo> existingSessionInfo = getEmptySessionToReuseInsteadOfCreatingNew(name);
        if (existingSessionInfo.isPresent()) {
            SessionInfo sessionInfo = existingSessionInfo.get();
            logger.info("Reused existing empty session {} with name '{}'", sessionInfo.id(), name);
            switchToSession(sessionInfo.id());
        } else {
            // No empty session found, create a new one
            SessionInfo sessionInfo = project.getSessionManager().newSession(name);
            logger.info("Created new session: {} ({})", sessionInfo.name(), sessionInfo.id());

            updateActiveSession(sessionInfo.id()); // Mark as active for this project

            // initialize history for the session
            contextHistory = new ContextHistory(new Context(this));
            project.getSessionManager()
                    .saveHistory(contextHistory, currentSessionId); // Save the initial empty/welcome state

            // notifications
            notifyContextListeners(liveContext());
            io.updateContextHistoryTable(liveContext());
        }
        submitSessionSyncIfActive();
    }

    private Optional<SessionInfo> getEmptySessionToReuseInsteadOfCreatingNew(String name) {
        var potentialEmptySessions = project.getSessionManager().listSessions().stream()
                .filter(session -> session.name().equals(name))
                .filter(session -> !session.isSessionModified())
                .filter(session ->
                        !project.getSessionRegistry().isSessionActiveElsewhere(project.getRoot(), session.id()))
                .sorted(Comparator.comparingLong(SessionInfo::created).reversed()) // Newest first
                .toList();

        return potentialEmptySessions.stream()
                .filter(session -> {
                    try {
                        var history = project.getSessionManager().loadHistory(session.id(), this);
                        return SessionManager.isSessionEmpty(session, history);
                    } catch (Exception e) {
                        logger.warn(
                                "Error checking if session {} is empty, skipping: {}", session.id(), e.getMessage());
                        return false;
                    }
                })
                .findFirst();
    }

    public void updateActiveSession(UUID sessionId) {
        currentSessionId = sessionId;
        project.getSessionRegistry().update(project.getRoot(), sessionId);
        ((AbstractProject) project).setLastActiveSession(sessionId);
    }

    public void createSessionWithoutGui(Context sourceContext, String newSessionName) {
        var sessionManager = project.getSessionManager();
        var newSessionInfo = sessionManager.newSession(newSessionName);
        updateActiveSession(newSessionInfo.id());
        var ctx = newContextFrom(sourceContext).copyAndRefresh();

        // the intent is that we save a history to the new session that initializeCurrentSessionAndHistory will pull in
        // later
        var ch = new ContextHistory(ctx);
        sessionManager.saveHistory(ch, newSessionInfo.id());
    }

    /**
     * Creates a new session with the given name, copies the workspace from the sourceFrozenContext, and switches to it
     * asynchronously.
     *
     * @param sourceContext The context whose workspace items will be copied.
     * @param newSessionName The name for the new session.
     * @return A CompletableFuture representing the completion of the session creation task.
     */
    public CompletableFuture<Void> createSessionFromContextAsync(Context sourceContext, String newSessionName) {
        return submitExclusiveAction(() -> {
                    logger.debug(
                            "Attempting to create and switch to new session '{}' from workspace of context '{}'",
                            newSessionName,
                            sourceContext.id());

                    var sessionManager = project.getSessionManager();
                    // 1. Create new session info
                    var newSessionInfo = sessionManager.newSession(newSessionName);
                    updateActiveSession(newSessionInfo.id());
                    logger.debug("Switched to new session: {} ({})", newSessionInfo.name(), newSessionInfo.id());

                    // 2. Create the initial context for the new session.
                    // Only its top-level action/parsedOutput will be changed to reflect it's a new session.
                    var initialContextForNewSession =
                            newContextFrom(sourceContext).copyAndRefresh();

                    // 3. Initialize the ContextManager's history for the new session with this single context.
                    // Context should already be live from migration logic
                    var newCh = new ContextHistory(initialContextForNewSession);
                    newCh.addResetEdge(sourceContext, initialContextForNewSession);
                    this.contextHistory = newCh;

                    // 4. Save the new session's history (which now contains one entry).
                    // ensureFilesSnapshot() is called internally by ContextHistory.pushLive()
                    sessionManager.saveHistory(this.contextHistory, this.currentSessionId);

                    notifyContextListeners(liveContext());
                })
                .exceptionally(e -> {
                    logger.error("Failed to create new session from workspace", e);
                    throw new RuntimeException("Failed to create new session from workspace", e);
                });
    }

    /** returns a new Context based on the source one */
    private Context newContextFrom(Context sourceContext) {
        var newActionDescription = "New Session";
        var newParsedOutputFragment = new ContextFragments.TaskFragment(
                this, List.of(SystemMessage.from(newActionDescription)), newActionDescription);
        return sourceContext.withParsedOutput(newParsedOutputFragment);
    }

    /**
     * Switches to an existing session asynchronously. Checks if the session is active elsewhere before switching.
     *
     * @param sessionId The UUID of the session to switch to
     * @return A CompletableFuture representing the completion of the session switch task
     */
    public CompletableFuture<Void> switchSessionAsync(UUID sessionId) {
        var sessionManager = project.getSessionManager();
        var otherWorktreeOpt =
                project.getSessionRegistry().findAnotherWorktreeWithActiveSession(project.getRoot(), sessionId);
        if (otherWorktreeOpt.isPresent()) {
            var otherWorktree = otherWorktreeOpt.get();
            String sessionName = sessionManager.listSessions().stream()
                    .filter(s -> s.id().equals(sessionId))
                    .findFirst()
                    .map(SessionInfo::name)
                    .orElse("Unknown session");
            io.systemNotify(
                    "Session '" + sessionName + "' (" + sessionId.toString().substring(0, 8) + ")"
                            + " is currently active in worktree:\n"
                            + otherWorktree + "\n\n"
                            + "Please close it there or choose a different session.",
                    "Session In Use",
                    JOptionPane.WARNING_MESSAGE);
            project.sessionsListChanged(); // to make sure sessions combo box switches back to the old session
            return CompletableFuture.failedFuture(new IllegalStateException("Session is active elsewhere."));
        }

        io.showSessionSwitchSpinner();
        return submitExclusiveAction(() -> {
                    try {
                        switchToSession(sessionId);
                    } finally {
                        io.hideSessionSwitchSpinner();
                    }
                })
                .exceptionally(e -> {
                    logger.error("Failed to switch to session {}", sessionId, e);
                    throw new RuntimeException("Failed to switch session", e);
                });
    }

    private void switchToSession(UUID sessionId) {
        var sessionManager = project.getSessionManager();

        String sessionName = sessionManager.listSessions().stream()
                .filter(s -> s.id().equals(sessionId))
                .findFirst()
                .map(SessionInfo::name)
                .orElse("(Unknown Name)");
        logger.debug("Switched to session: {} ({})", sessionName, sessionId);

        ContextHistory loadedCh = sessionManager.loadHistoryAndRefresh(sessionId, this);

        if (loadedCh == null) {
            io.toolError("Error while loading history for session '%s'.".formatted(sessionName));
        } else {
            updateActiveSession(sessionId); // Mark as active
            contextHistory = loadedCh;

            // Activate session: migrate legacy tasks then notify UI on EDT
            finalizeSessionActivation(sessionId);
        }

        submitSessionSyncIfActive();
    }

    /**
     * Renames an existing session asynchronously.
     *
     * @param sessionId The UUID of the session to rename
     * @param newNameFuture A Future that will provide the new name for the session
     * @return A CompletableFuture representing the completion of the session rename task
     */
    public CompletableFuture<Void> renameSessionAsync(UUID sessionId, Future<String> newNameFuture) {
        return submitBackgroundTask("Renaming session", () -> {
            try {
                String newName = newNameFuture.get(Context.CONTEXT_ACTION_SUMMARY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                project.getSessionManager().renameSession(sessionId, newName);
                submitSessionSyncIfActive();
                logger.debug("Renamed session {} to {}", sessionId, newName);
            } catch (Exception e) {
                logger.warn("Error renaming Session {}", sessionId, e);
            }
        });
    }

    /**
     * Deletes an existing session asynchronously.
     *
     * @param sessionIdToDelete The UUID of the session to delete
     * @return A CompletableFuture representing the completion of the session delete task
     */
    public CompletableFuture<Void> deleteSessionAsync(UUID sessionIdToDelete) {
        return submitExclusiveAction(() -> {
                    try {
                        project.getSessionManager().deleteSession(sessionIdToDelete);
                    } catch (Exception e) {
                        logger.error("Failed to delete session {}", sessionIdToDelete, e);
                        throw new RuntimeException(e);
                    }
                    logger.info("Deleted session {}", sessionIdToDelete);
                    if (sessionIdToDelete.equals(currentSessionId)) {
                        var sessionToSwitchTo = project.getSessionManager().listSessions().stream()
                                .max(Comparator.comparingLong(SessionInfo::created))
                                .map(SessionInfo::id)
                                .orElse(null);

                        if (sessionToSwitchTo != null
                                && project.getSessionManager().loadHistory(sessionToSwitchTo, this) != null) {
                            switchToSession(sessionToSwitchTo);
                        } else {
                            createOrReuseSession(DEFAULT_SESSION_NAME);
                        }
                    }
                })
                .exceptionally(e -> {
                    logger.error("Failed to delete session {}", sessionIdToDelete, e);
                    throw new RuntimeException(e);
                });
    }

    public CompletableFuture<Void> copySessionAsync(UUID originalSessionId, String originalSessionName) {
        return submitExclusiveAction(() -> {
                    var sessionManager = project.getSessionManager();
                    String newSessionName = "Copy of " + originalSessionName;
                    SessionInfo copiedSessionInfo;
                    try {
                        copiedSessionInfo = sessionManager.copySession(originalSessionId, newSessionName);
                    } catch (Exception e) {
                        logger.error(e);
                        io.toolError("Failed to copy session " + originalSessionName);
                        return;
                    }

                    logger.info(
                            "Copied session {} ({}) to {} ({})",
                            originalSessionName,
                            originalSessionId,
                            copiedSessionInfo.name(),
                            copiedSessionInfo.id());
                    var loadedCh = sessionManager.loadHistoryAndRefresh(copiedSessionInfo.id(), this);
                    assert loadedCh != null && !loadedCh.getHistory().isEmpty()
                            : "Copied session history should not be null or empty";
                    contextHistory = requireNonNull(
                            loadedCh, "Copied session history (loadedCh) should not be null after assertion");

                    updateActiveSession(copiedSessionInfo.id());
                    finalizeSessionActivation(copiedSessionInfo.id());
                })
                .exceptionally(e -> {
                    logger.error("Failed to copy session {}", originalSessionId, e);
                    throw new RuntimeException(e);
                });
    }

    @SuppressWarnings("unused")
    public void restoreGitProjectState(UUID sessionId, UUID contextId) {
        if (!project.hasGit()) {
            return;
        }
        var ch = project.getSessionManager().loadHistory(sessionId, this);
        if (ch == null) {
            io.toolError("Could not load session " + sessionId, "Error");
            return;
        }

        var gitState = ch.getGitState(contextId).orElse(null);
        if (gitState == null) {
            io.toolError("Could not find git state for context " + contextId, "Error");
            return;
        }

        var restorer = new GitProjectStateRestorer(project, io);
        restorer.restore(gitState);
    }

    /**
     * Override the active {@link IConsoleIO}. Intended for headless execution: callers should install
     * their console implementation before starting a job and restore the previous console around the job run.
     *
     * <p>The provided console must be non-null; it will be wired into {@link UserActionManager} so that
     * user-action callbacks and background tasks emit events through the replacement console.
     */
    public void setIo(IConsoleIO io) {
        this.io = io;
        this.userActions.setIo(this.io);
    }

    @Override
    public IConsoleIO getIo() {
        return io;
    }

    public void createHeadless() {
        createHeadless(BuildDetails.EMPTY);
    }

    /**
     * This should be invoked immediately after constructing the {@code ContextManager} but before any tasks are
     * submitted, so that all logging and UI callbacks are routed to the desired sink.
     */
    public void createHeadless(BuildDetails buildDetails) {
        // Mark this ContextManager as running headless so internal code paths can avoid EDT/Swing logic.
        this.headlessMode = true;

        this.io = new HeadlessConsole();
        this.watchService = new NoopWatchService();
        this.userActions.setIo(this.io);

        initializeCurrentSessionAndHistory(true);

        cleanupOldHistoryAsync();
        // we deliberately don't infer style guide or build details here -- if they already exist, great;
        // otherwise we leave them empty
        var mp = project.getMainProject();
        if (mp.loadBuildDetails().isEmpty()) {
            mp.setBuildDetails(buildDetails);
        }

        // no AnalyzerListener, instead we will block for it to be ready
        // Headless mode doesn't need file watching, so pass null for both analyzerListener and watchService
        this.analyzerWrapper = new AnalyzerWrapper(project, new NullAnalyzerListener(), new NoopWatchService());
        try {
            analyzerWrapper.get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        checkBalanceAndNotify();
    }

    @Override
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * Checks the user’s account balance (only when using the Brokk proxy) and notifies via
     * {@link IConsoleIO#systemNotify} if the balance is low or exhausted. The expensive work is executed on the
     * background executor so callers may invoke this from any thread without blocking.
     */
    public void checkBalanceAndNotify() {
        if (MainProject.getProxySetting() != MainProject.LlmProxySetting.BROKK) {
            return; // Only relevant when using the Brokk proxy
        }

        submitBackgroundTask("Balance Check", () -> {
            try {
                float balance = serviceProvider.get().getUserBalance();
                logger.debug("Checked balance: ${}", String.format("%.2f", balance));

                if (balance < Service.MINIMUM_PAID_BALANCE) {
                    // Free-tier: reload models and warn once
                    reloadService();
                    if (!freeTierNotified) {
                        freeTierNotified = true;
                        lowBalanceNotified = false; // reset low-balance flag
                        var msg =
                                """
                        Brokk is running in the free tier. Only low-cost models are available.

                        To enable smarter models, subscribe or top-up at
                        %s
                        """
                                        .formatted(Service.TOP_UP_URL);
                        SwingUtilities.invokeLater(
                                () -> io.systemNotify(msg, "Balance Exhausted", JOptionPane.WARNING_MESSAGE));
                    }
                } else if (balance < Service.LOW_BALANCE_WARN_AT) {
                    // Low balance warning
                    freeTierNotified = false; // recovered from exhausted state
                    if (!lowBalanceNotified) {
                        lowBalanceNotified = true;
                        var msg = "Low account balance: $%.2f.\nTop-up at %s to avoid interruptions."
                                .formatted(balance, Service.TOP_UP_URL);
                        SwingUtilities.invokeLater(
                                () -> io.systemNotify(msg, "Low Balance Warning", JOptionPane.WARNING_MESSAGE));
                    }
                } else {
                    // Healthy balance – clear flags
                    lowBalanceNotified = false;
                    freeTierNotified = false;
                }
            } catch (IOException e) {
                logger.error("Failed to check user balance", e);
            }
        });
    }

    /**
     * Asynchronously compresses the entire conversation history of the currently selected context. Replaces the history
     * with summarized versions of each task entry. This runs as a cancellable LLM action, so it should NOT be
     * called from other exclusive-tasks (or it will deadlock).
     */
    public CompletableFuture<?> compressHistoryAsync() {
        return submitLlmAction(() -> {
            try {
                compressGlobalHistory();
            } catch (InterruptedException ie) {
                io.showNotification(IConsoleIO.NotificationRole.INFO, "History compression canceled");
            }
        });
    }

    @Override
    @Blocking
    public Context compressHistory(Context ctx) throws InterruptedException {
        io.disableHistoryPanel();
        try {
            // Use bounded-concurrency executor to avoid overwhelming the LLM provider
            List<Future<TaskEntry>> futures =
                    new ArrayList<>(ctx.getTaskHistory().size());
            try (var exec = ExecutorsUtil.newFixedThreadExecutor(5, "HistoryCompress-")) {
                // Submit all compression tasks
                for (TaskEntry entry : ctx.getTaskHistory()) {
                    futures.add(exec.submit(() -> compressHistory(entry)));
                }

                // Collect results in order, with fallback to original on failure
                List<TaskEntry> compressedTaskEntries =
                        new ArrayList<>(ctx.getTaskHistory().size());
                for (int i = 0; i < futures.size(); i++) {
                    try {
                        compressedTaskEntries.add(futures.get(i).get());
                    } catch (InterruptedException ie) {
                        // Cancellation requested - cancel remaining futures and re-throw
                        logger.debug("History compression interrupted");
                        for (int j = i + 1; j < futures.size(); j++) {
                            futures.get(j).cancel(true);
                        }
                        throw ie;
                    } catch (ExecutionException ee) {
                        // Individual task failed - use original entry and continue
                        logger.warn("History compression task failed", ee);
                        compressedTaskEntries.add(ctx.getTaskHistory().get(i));
                    }
                }

                // Check if any entries were actually modified (got a summary attached)
                boolean changed = IntStream.range(0, ctx.getTaskHistory().size())
                        .anyMatch(i -> {
                            TaskEntry original = ctx.getTaskHistory().get(i);
                            TaskEntry compressed = compressedTaskEntries.get(i);
                            // Entry changed if it now has a summary when it didn't before
                            return compressed.isCompressed() && !original.isCompressed();
                        });

                if (!changed) {
                    return ctx;
                }

                return ctx.withHistory(compressedTaskEntries);
            }
        } finally {
            SwingUtilities.invokeLater(io::enableHistoryPanel);
        }
    }

    public static class SummarizeWorker extends ExceptionAwareSwingWorker<String, String> {
        private final IContextManager cm;
        private final String content;
        private final int words;

        public SummarizeWorker(IContextManager cm, String content, int words) {
            super(cm.getIo());
            this.cm = cm;
            this.content = content;
            this.words = words;
        }

        @Override
        protected String doInBackground() throws Exception {
            var msgs = SummarizerPrompts.instance.collectMessages(content, words);
            // Use quickModel for summarization
            Llm.StreamingResult result = cm.getLlm(
                            cm.getService().quickestModel(), "Summarize: " + content, TaskResult.Type.SUMMARIZE)
                    .sendRequest(msgs);
            if (result.error() != null) {
                logger.warn("Summarization failed or was cancelled.");
                return "Summarization failed.";
            }
            var summary = result.text().trim();
            if (summary.endsWith(".")) {
                return summary.substring(0, summary.length() - 1);
            }
            return summary;
        }
    }

    @TestOnly
    public void setAnalyzerWrapper(IAnalyzerWrapper analyzerWrapper) {
        this.analyzerWrapper = analyzerWrapper;
    }
}
