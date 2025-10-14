package io.github.jbellis.brokk;

import static io.github.jbellis.brokk.SessionManager.SessionInfo;
import static java.lang.Math.max;
import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.agents.ArchitectAgent;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.agents.BuildAgent.BuildDetails;
import io.github.jbellis.brokk.agents.MergeAgent;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.cli.HeadlessConsole;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.context.ContextFragment.PathFragment;
import io.github.jbellis.brokk.context.ContextFragment.VirtualFragment;
import io.github.jbellis.brokk.context.ContextHistory;
import io.github.jbellis.brokk.context.ContextHistory.UndoResult;
import io.github.jbellis.brokk.exception.OomShutdownHandler;
import io.github.jbellis.brokk.git.GitDistance;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.GitWorkflow;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.dialogs.SettingsDialog;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.prompts.SummarizerPrompts;
import io.github.jbellis.brokk.tasks.TaskList;
import io.github.jbellis.brokk.tools.SearchTools;
import io.github.jbellis.brokk.tools.ToolRegistry;
import io.github.jbellis.brokk.tools.UiTools;
import io.github.jbellis.brokk.tools.WorkspaceTools;
import io.github.jbellis.brokk.util.*;
import io.github.jbellis.brokk.util.UserActionManager.ThrowingRunnable;
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
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

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

    @SuppressWarnings("NullAway.Init")
    private IAnalyzerWrapper analyzerWrapper; // also initialized in createGui/createHeadless

    // Run main user-driven tasks in background (Code/Ask/Search/Run)
    // Only one of these can run at a time
    private final LoggingExecutorService userActionExecutor =
            createLoggingExecutorService(Executors.newSingleThreadExecutor());
    private final UserActionManager userActions;

    // Regex to identify test files. Matches the word "test"/"tests" (case-insensitive)
    // when it appears as its own path segment or at a camel-case boundary.
    static final Pattern TEST_FILE_PATTERN = Pattern.compile(".*" + // anything before
            "(?:[/\\\\.]|\\b|_|(?<=[a-z])(?=[A-Z])|(?<=[A-Z]))"
            + // valid prefix boundary
            "(?i:tests?)"
            + // the word test/tests (case-insensitive only here)
            "(?:[/\\\\.]|\\b|_|(?=[A-Z][^a-z])|(?=[A-Z][a-z])|$)"
            + // suffix: separator, word-boundary, underscore,
            //         UC not followed by lc  OR UC followed by lc, or EOS
            ".*");

    public static final String DEFAULT_SESSION_NAME = "New Session";

    public static boolean isTestFile(ProjectFile file) {
        return TEST_FILE_PATTERN.matcher(file.toString()).matches();
    }

    public void runTests(Set<ProjectFile> testFiles) {
        String cmd = BuildAgent.getBuildLintSomeCommand(this, getProject().loadBuildDetails(), testFiles);
        if (cmd.isEmpty()) {
            getIo().toolError("Run in Shell: build commands are unknown; run Build Setup first");
            return;
        }
        var io = getIo();
        if (io instanceof Chrome chrome) {
            SwingUtilities.invokeLater(() -> chrome.getTerminalDrawer().openTerminalAndPasteText(cmd));
        }
    }

    private LoggingExecutorService createLoggingExecutorService(ExecutorService toWrap) {
        return createLoggingExecutorService(toWrap, Set.of());
    }

    private LoggingExecutorService createLoggingExecutorService(
            ExecutorService toWrap, Set<Class<? extends Throwable>> ignoredExceptions) {
        return new LoggingExecutorService(toWrap, th -> {
            var thread = Thread.currentThread();
            if (ignoredExceptions.stream().anyMatch(cls -> cls.isInstance(th))) {
                logger.debug("Uncaught exception (ignorable) in executor", th);
                return;
            }

            // Sometimes the shutdown handler fails to pick this up, but it may occur here and be "caught"
            if (OomShutdownHandler.isOomError(th)) {
                OomShutdownHandler.shutdownWithRecovery();
            }

            logger.error("Uncaught exception in executor", th);
            String message = "Uncaught exception in thread %s. This shouldn't happen, please report a bug!\n%s"
                    .formatted(thread.getName(), getStackTraceAsString(th));
            io.showNotification(IConsoleIO.NotificationRole.INFO, message);
        });
    }

    // Context modification tasks (Edit/Read/Summarize/Drop/etc)
    // Multiple of these can run concurrently
    private final LoggingExecutorService contextActionExecutor = createLoggingExecutorService(new ThreadPoolExecutor(
            4,
            4, // Core and Max are same due to unbounded queue behavior
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), // Unbounded queue
            Executors.defaultThreadFactory()));

    // Internal background tasks (unrelated to user actions)
    // Lots of threads allowed since AutoContext updates get dropped here
    // Use unbounded queue to prevent task rejection
    private final LoggingExecutorService backgroundTasks = createLoggingExecutorService(
            new ThreadPoolExecutor(
                    max(8, Runtime.getRuntime().availableProcessors()), // Core and Max are same
                    max(8, Runtime.getRuntime().availableProcessors()),
                    60L,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(), // Unbounded queue to prevent rejection
                    Executors.defaultThreadFactory()),
            Set.of(InterruptedException.class));

    private final ServiceWrapper service;

    @SuppressWarnings(" vaikka project on final, sen sisältö voi muuttua ")
    private final AbstractProject project;

    private final ToolRegistry toolRegistry;

    // Current session tracking
    private UUID currentSessionId;

    // Domain model task list for the current session (non-null)
    private volatile TaskList.TaskListData taskList = new TaskList.TaskListData(List.of());

    // Context history for undo/redo functionality (stores frozen contexts)
    private ContextHistory contextHistory;
    private final List<ContextListener> contextListeners = new CopyOnWriteArrayList<>();
    private final List<AnalyzerCallback> analyzerCallbacks = new CopyOnWriteArrayList<>();
    private final List<FileSystemEventListener> fileSystemEventListeners = new CopyOnWriteArrayList<>();
    // Listeners that want to be notified when the Service (models/stt) is reinitialized.
    private final List<Runnable> serviceReloadListeners = new CopyOnWriteArrayList<>();
    private final LowMemoryWatcherManager lowMemoryWatcherManager;

    // balance-notification state
    private boolean lowBalanceNotified = false;
    private boolean freeTierNotified = false;

    // BuildAgent task tracking for cancellation
    private volatile @Nullable CompletableFuture<BuildAgent.BuildDetails> buildAgentFuture;

    // Service reload state to prevent concurrent reloads
    private final AtomicBoolean isReloadingService = new AtomicBoolean(false);

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

    public void addFileSystemEventListener(FileSystemEventListener listener) {
        fileSystemEventListeners.add(listener);
    }

    public void removeFileSystemEventListener(FileSystemEventListener listener) {
        fileSystemEventListeners.remove(listener);
    }

    /** Minimal constructor called from Brokk */
    public ContextManager(AbstractProject project) {
        this.project = project;

        this.contextHistory = new ContextHistory(new Context(this, null));
        this.service = new ServiceWrapper();
        this.service.reinit(project);

        // set up global tools
        this.toolRegistry = new ToolRegistry(this);
        this.toolRegistry.register(new SearchTools(this));
        this.toolRegistry.register(new WorkspaceTools(this));

        // dummy ConsoleIO until Chrome is constructed; necessary because Chrome starts submitting background tasks
        // immediately during construction, which means our own reference to it will still be null
        this.io = new IConsoleIO() {
            @Override
            public void toolError(String msg, String title) {
                logger.info(msg);
            }

            @Override
            public void llmOutput(String token, ChatMessageType type, boolean isNewMessage, boolean isReasoning) {
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

    /**
     * Initializes the current session by loading its history or creating a new one. This is typically called for
     * standard project openings. This method is synchronous but intended to be called from a background task.
     */
    private void initializeCurrentSessionAndHistory(boolean forceNew) {
        // load last active session, if present
        var lastActiveSessionId = project.getLastActiveSession();
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
        this.currentSessionId = sessionIdToLoad; // Set currentSessionId here

        // load session contents
        var loadedCH = sessionManager.loadHistory(currentSessionId, this);
        if (loadedCH == null) {
            if (forceNew) {
                contextHistory = new ContextHistory(new Context(this, null));
            } else {
                initializeCurrentSessionAndHistory(true);
                return;
            }
        } else {
            contextHistory = loadedCH;
        }

        // make it official
        updateActiveSession(currentSessionId);

        // Load task list for the current session
        loadTaskListForSession(currentSessionId);

        // Notify listeners and UI on EDT
        SwingUtilities.invokeLater(() -> {
            var tc = topContext();
            notifyContextListeners(tc);
            if (io instanceof Chrome) { // Check if UI is ready
                io.enableActionButtons();
            }
        });

        migrateToSessionsV3IfNeeded();
    }

    private void migrateToSessionsV3IfNeeded() {
        if (project instanceof MainProject mainProject && !mainProject.isMigrationsToSessionsV3Complete()) {
            submitBackgroundTask("Quarantine unreadable sessions", () -> {
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
                    SwingUtilities.invokeLater(() -> io.systemNotify(
                            "Your previously active session was unreadable and has been moved to the 'unreadable' folder. A new session has been created.",
                            "Session Quarantined",
                            JOptionPane.WARNING_MESSAGE));
                }
            });
        }
    }

    /**
     * Called from Brokk to finish wiring up references to Chrome and Coder
     *
     * <p>Returns the future doing off-EDT context loading
     */
    public CompletableFuture<Void> createGui() {
        assert SwingUtilities.isEventDispatchThread();

        this.io = new Chrome(this);
        this.toolRegistry.register(new UiTools((Chrome) this.io));
        this.userActions.setIo(this.io);

        var analyzerListener = createAnalyzerListener();
        this.analyzerWrapper = new AnalyzerWrapper(project, analyzerListener);

        // Load saved context history or create a new one
        var contextTask =
                submitBackgroundTask("Loading saved context", () -> initializeCurrentSessionAndHistory(false));

        // Ensure style guide and build details are loaded/generated asynchronously
        ensureStyleGuide();
        ensureReviewGuide();
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
            public void afterFirstBuild(String msg) {
                if (io instanceof Chrome chrome) {
                    chrome.notifyActionComplete("Analyzer build completed");
                }
                if (msg.isEmpty()) {
                    io.systemNotify(
                            "Code Intelligence is empty. Probably this means your language is not yet supported. File-based tools will continue to work.",
                            "Code Intelligence Warning",
                            JOptionPane.WARNING_MESSAGE);
                } else {
                    io.showNotification(IConsoleIO.NotificationRole.INFO, msg);
                }
            }

            @Override
            public void onRepoChange() {
                logger.debug("AnalyzerListener.onRepoChange fired");
                try {
                    var branch = project.getRepo().getCurrentBranch();
                    logger.debug("AnalyzerListener.onRepoChange current branch: {}", branch);
                } catch (Exception e) {
                    logger.debug("AnalyzerListener.onRepoChange: unable to get current branch", e);
                }
                project.getRepo().invalidateCaches();
                io.updateGitRepo();

                // Notify analyzer callbacks
                for (var callback : analyzerCallbacks) {
                    submitBackgroundTask("Update for Git changes", callback::onRepoChange);
                }
            }

            @Override
            public void onTrackedFileChange() {
                submitBackgroundTask("Update for FS changes", () -> {
                    // we don't need the full onRepoChange but we do need these parts
                    project.getRepo().invalidateCaches();
                    project.invalidateAllFiles();
                    io.updateCommitPanel();

                    // update Workspace
                    // we can't rely on pushContext's change detection because here we care about the contents and not
                    // the
                    // fragment identity
                    if (processExternalFileChangesIfNeeded()) {
                        // analyzer refresh will call this too, but it will be delayed
                        io.updateWorkspace();
                    }

                    // ProjectTree
                    for (var fsListener : fileSystemEventListeners) {
                        fsListener.onTrackedFilesChanged();
                    }
                });

                // Notify analyzer callbacks
                for (var callback : analyzerCallbacks) {
                    submitBackgroundTask("Update for FS changes", callback::onTrackedFileChange);
                }
            }

            @Override
            public void beforeEachBuild() {
                if (io instanceof Chrome chrome) {
                    chrome.getContextPanel().showAnalyzerRebuildSpinner();
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
                        chrome.getContextPanel().hideAnalyzerRebuildSpinner();
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

                    // re-freeze context w/ new analyzer
                    processExternalFileChangesIfNeeded();
                    io.updateWorkspace();

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
        };
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
        var deletedCount = new java.util.concurrent.atomic.AtomicInteger(0);

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
    public AbstractProject getProject() {
        return project;
    }

    @Override
    public IAnalyzerWrapper getAnalyzerWrapper() {
        return analyzerWrapper;
    }

    @Override
    public IAnalyzer getAnalyzer() throws InterruptedException {
        return analyzerWrapper.get();
    }

    @Override
    public IAnalyzer getAnalyzerUninterrupted() {
        try {
            return analyzerWrapper.get();
        } catch (InterruptedException e) {
            throw new CancellationException(e.getMessage());
        }
    }

    @Override
    public Context topContext() {
        return contextHistory.topContext();
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
        return contextHistory.getLiveContext();
    }

    public Path getRoot() {
        return project.getRoot();
    }

    /** Returns the Models instance associated with this context manager. */
    @Override
    public Service getService() {
        return service.get();
    }

    /** Returns the configured Architect model, falling back to the system model if unavailable. */
    public StreamingChatModel getArchitectModel() {
        var config = project.getArchitectModelConfig();
        return getModelOrDefault(config, "Architect");
    }

    /** Returns the configured Code model, falling back to the system model if unavailable. */
    public StreamingChatModel getCodeModel() {
        var config = project.getCodeModelConfig();
        return getModelOrDefault(config, "Code");
    }

    /** Returns the configured Search model, falling back to the system model if unavailable. */
    public StreamingChatModel getSearchModel() {
        var config = project.getSearchModelConfig();
        return getModelOrDefault(config, "Search");
    }

    private StreamingChatModel getModelOrDefault(Service.ModelConfig config, String modelTypeName) {
        StreamingChatModel model = service.getModel(config);
        if (model != null) {
            return model;
        }

        model = service.getModel(new Service.ModelConfig(Service.GPT_5_MINI, Service.ReasoningLevel.HIGH));
        if (model != null) {
            io.showNotification(
                    IConsoleIO.NotificationRole.INFO,
                    String.format(
                            "Configured model '%s' for %s tasks is unavailable. Using fallback '%s'.",
                            config.name(), modelTypeName, Service.GPT_5_MINI));
            return model;
        }

        var quickModel = service.get().quickModel();
        String quickModelName = service.get().nameOf(quickModel);
        io.showNotification(
                IConsoleIO.NotificationRole.INFO,
                String.format(
                        "Configured model '%s' for %s tasks is unavailable. Preferred fallbacks also failed. Using system model '%s'.",
                        config.name(), modelTypeName, quickModelName));
        return quickModel;
    }

    /**
     * "Exclusive actions" are short-lived, local actions that prevent new LLM actions from being started while they
     * run; only one will run at a time. These will NOT be wired up to cancellation mechanics; InterruptedException will
     * be thrown as CancellationException (an unchecked IllegalStateException).
     */
    public CompletableFuture<Void> submitExclusiveAction(Runnable task) {
        return userActions.submitExclusiveAction(task);
    }

    public <T> CompletableFuture<T> submitExclusiveAction(Callable<T> task) {
        return userActions.submitExclusiveAction(task);
    }

    public CompletableFuture<Void> submitLlmAction(ThrowingRunnable task) {
        return userActions.submitLlmAction(task);
    }

    // TODO should we just merge ContextTask w/ BackgroundTask?
    public Future<?> submitContextTask(Runnable task) {
        return contextActionExecutor.submit(task);
    }

    /** Attempts to re‑interrupt the thread currently executing a user‑action task. Safe to call repeatedly. */
    public void interruptLlmAction() {
        userActions.cancelActiveAction();
    }

    /** Add the given files to editable. */
    @Override
    public void addFiles(Collection<ProjectFile> files) {
        var filesByType = files.stream().collect(Collectors.partitioningBy(BrokkFile::isText));

        var textFiles = castNonNull(filesByType.get(true));
        var binaryFiles = castNonNull(filesByType.get(false));

        var textFragments = textFiles.stream()
                .map(pf -> new ContextFragment.ProjectPathFragment(pf, this))
                .toList();
        if (!textFragments.isEmpty()) {
            addPathFragments(textFragments);
        }

        var binaryFragments = binaryFiles.stream()
                .map(pf -> new ContextFragment.ImageFileFragment(pf, this))
                .toList();
        if (!binaryFragments.isEmpty()) {
            addPathFragments(binaryFragments);
        }
    }

    /** Add the given files to editable. */
    public void addPathFragments(List<? extends PathFragment> fragments) {
        if (fragments.isEmpty()) {
            return;
        }
        pushContext(currentLiveCtx -> currentLiveCtx.addPathFragments(fragments));
        String message = "Edit " + contextDescription(fragments);
        io.showNotification(IConsoleIO.NotificationRole.INFO, message);
    }

    /** Drop all context. */
    public void dropAll() {
        pushContext(Context::removeAll);
    }

    /** Drop fragments by their IDs. */
    public void drop(Collection<? extends ContextFragment> fragments) {
        var ids = fragments.stream()
                .map(f -> contextHistory.mapToLiveFragment(f).id())
                .toList();
        pushContext(currentLiveCtx -> currentLiveCtx.removeFragmentsByIds(ids));
        String message = "Remove " + contextDescription(fragments);
        io.showNotification(IConsoleIO.NotificationRole.INFO, message);
    }

    /** Clear conversation history. */
    public void clearHistory() {
        pushContext(Context::clearHistory);
    }

    /**
     * Drops fragments with HISTORY-aware semantics: - If selection is empty: drop all and reset selected context to the
     * latest (top) context. - If selection includes HISTORY: clear history, then drop only non-HISTORY fragments. -
     * Else: drop the selected fragments as-is.
     */
    public void dropWithHistorySemantics(java.util.Collection<? extends ContextFragment> selectedFragments) {
        if (selectedFragments.isEmpty()) {
            if (topContext().isEmpty()) {
                return;
            }
            dropAll();
            setSelectedContext(topContext());
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
     * <p>Creates a new context state with: - updated task history (with the entry removed), - null parsedOutput, - and
     * action set to "Dropped message".
     *
     * <p>Special behavior: - sequence == -1 means "drop the last item of the history"
     *
     * @param sequence the TaskEntry.sequence() to remove, or -1 to remove the last entry
     */
    public void dropHistoryEntryBySequence(int sequence) {
        var currentHistory = topContext().getTaskHistory();

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

        // Push an updated context with the modified history and a "Delete message" action
        pushContext(currentLiveCtx ->
                currentLiveCtx.withCompressedHistory(newHistory).withParsedOutput(null, "Delete task from history"));

        io.showNotification(IConsoleIO.NotificationRole.INFO, "Remove history entry " + seqToDrop);
    }

    /** request code-intel rebuild */
    @Override
    public void requestRebuild() {
        project.getRepo().invalidateCaches();
        analyzerWrapper.requestRebuild();
    }

    /** undo last context change */
    public Future<?> undoContextAsync() {
        return submitExclusiveAction(() -> {
            if (undoContext()) {
                io.showNotification(IConsoleIO.NotificationRole.INFO, "Undo most recent step");
            } else {
                io.showNotification(IConsoleIO.NotificationRole.INFO, "Nothing to undo");
            }
        });
    }

    public boolean undoContext() {
        UndoResult result = contextHistory.undo(1, io, project);
        if (result.wasUndone()) {
            notifyContextListeners(topContext());
            project.getSessionManager()
                    .saveHistory(contextHistory, currentSessionId); // Save history of frozen contexts
            return true;
        }

        return false;
    }

    /** undo changes until we reach the target FROZEN context */
    public Future<?> undoContextUntilAsync(Context targetFrozenContext) {
        return submitExclusiveAction(() -> {
            UndoResult result = contextHistory.undoUntil(targetFrozenContext, io, project);
            if (result.wasUndone()) {
                notifyContextListeners(topContext());
                project.getSessionManager().saveHistory(contextHistory, currentSessionId);
                String message = "Undid " + result.steps() + " step" + (result.steps() > 1 ? "s" : "") + "!";
                io.showNotification(IConsoleIO.NotificationRole.INFO, message);
            } else {
                io.showNotification(IConsoleIO.NotificationRole.INFO, "Context not found or already at that point");
            }
        });
    }

    /** redo last undone context */
    public Future<?> redoContextAsync() {
        return submitExclusiveAction(() -> {
            boolean wasRedone = contextHistory.redo(io, project);
            if (wasRedone) {
                notifyContextListeners(topContext());
                project.getSessionManager().saveHistory(contextHistory, currentSessionId);
                io.showNotification(IConsoleIO.NotificationRole.INFO, "Redo!");
            } else {
                io.showNotification(IConsoleIO.NotificationRole.INFO, "no redo state available");
            }
        });
    }

    /**
     * Reset the live context to match the files and fragments from a historical (frozen) context. A new state
     * representing this reset is pushed to history.
     */
    public Future<?> resetContextToAsync(Context targetFrozenContext) {
        return submitExclusiveAction(() -> {
            try {
                var newLive = Context.createFrom(
                        targetFrozenContext, liveContext(), liveContext().getTaskHistory());
                var fr = newLive.freezeAndCleanup();
                contextHistory.pushLiveAndFrozen(fr.liveContext(), fr.frozenContext());
                contextHistory.addResetEdge(targetFrozenContext, fr.frozenContext());
                SwingUtilities.invokeLater(() -> notifyContextListeners(fr.frozenContext()));
                project.getSessionManager().saveHistory(contextHistory, currentSessionId);
                io.showNotification(IConsoleIO.NotificationRole.INFO, "Reset workspace to historical state");
            } catch (CancellationException cex) {
                io.showNotification(IConsoleIO.NotificationRole.INFO, "Reset workspace canceled.");
            }
        });
    }

    /**
     * Reset the live context and its history to match a historical (frozen) context. A new state representing this
     * reset is pushed to history.
     */
    public Future<?> resetContextToIncludingHistoryAsync(Context targetFrozenContext) {
        return submitExclusiveAction(() -> {
            try {
                var newLive =
                        Context.createFrom(targetFrozenContext, liveContext(), targetFrozenContext.getTaskHistory());
                var fr = newLive.freezeAndCleanup();
                contextHistory.pushLiveAndFrozen(fr.liveContext(), fr.frozenContext());
                contextHistory.addResetEdge(targetFrozenContext, fr.frozenContext());
                SwingUtilities.invokeLater(() -> notifyContextListeners(fr.frozenContext()));
                project.getSessionManager().saveHistory(contextHistory, currentSessionId);
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO, "Reset workspace and history to historical state");
            } catch (CancellationException cex) {
                io.showNotification(IConsoleIO.NotificationRole.INFO, "Reset workspace and history canceled.");
            }
        });
    }

    /**
     * Appends selected fragments from a historical (frozen) context to the current live context. If a
     * {@link ContextFragment.HistoryFragment} is among {@code fragmentsToKeep}, its task entries are also appended to
     * the current live context's history. A new state representing this action is pushed to the context history.
     *
     * @param sourceFrozenContext The historical context to source fragments and history from.
     * @param fragmentsToKeep A list of fragments from {@code sourceFrozenContext} to append. These are matched by ID.
     * @return A Future representing the completion of the task.
     */
    public Future<?> addFilteredToContextAsync(Context sourceFrozenContext, List<ContextFragment> fragmentsToKeep) {
        return submitExclusiveAction(() -> {
            try {
                String actionMessage =
                        "Copy workspace items from historical state: " + contextDescription(fragmentsToKeep);

                // Calculate new history
                List<TaskEntry> finalHistory = new ArrayList<>(liveContext().getTaskHistory());
                Set<TaskEntry> existingEntries = new HashSet<>(finalHistory);

                Optional<ContextFragment.HistoryFragment> selectedHistoryFragmentOpt = fragmentsToKeep.stream()
                        .filter(ContextFragment.HistoryFragment.class::isInstance)
                        .map(ContextFragment.HistoryFragment.class::cast)
                        .findFirst();

                if (selectedHistoryFragmentOpt.isPresent()) {
                    List<TaskEntry> entriesToAppend =
                            selectedHistoryFragmentOpt.get().entries();
                    for (TaskEntry entry : entriesToAppend) {
                        if (existingEntries.add(entry)) {
                            finalHistory.add(entry);
                        }
                    }
                    finalHistory.sort(Comparator.comparingInt(TaskEntry::sequence));
                }
                List<TaskEntry> newHistory = List.copyOf(finalHistory);

                // Categorize fragments to add after unfreezing
                List<ContextFragment.ProjectPathFragment> pathsToAdd = new ArrayList<>();
                List<VirtualFragment> virtualFragmentsToAdd = new ArrayList<>();

                Set<String> sourceEditableIds = sourceFrozenContext
                        .fileFragments()
                        .map(ContextFragment::id)
                        .collect(Collectors.toSet());
                Set<String> sourceVirtualIds = sourceFrozenContext
                        .virtualFragments()
                        .map(ContextFragment::id)
                        .collect(Collectors.toSet());

                for (ContextFragment fragmentFromKeeperList : fragmentsToKeep) {
                    ContextFragment unfrozen = Context.unfreezeFragmentIfNeeded(fragmentFromKeeperList, this);

                    if (sourceEditableIds.contains(fragmentFromKeeperList.id())
                            && unfrozen instanceof ContextFragment.ProjectPathFragment ppf) {
                        pathsToAdd.add(ppf);
                    } else if (sourceVirtualIds.contains(fragmentFromKeeperList.id())
                            && unfrozen instanceof VirtualFragment vf) {
                        if (!(vf instanceof ContextFragment.HistoryFragment)) {
                            virtualFragmentsToAdd.add(vf);
                        }
                    } else if (unfrozen instanceof ContextFragment.HistoryFragment) {
                        // Handled by selectedHistoryFragmentOpt
                    } else {
                        logger.warn(
                                "Fragment '{}' (ID: {}) from fragmentsToKeep could not be categorized. Original type: {}, Unfrozen type: {}",
                                fragmentFromKeeperList.description(),
                                fragmentFromKeeperList.id(),
                                fragmentFromKeeperList.getClass().getSimpleName(),
                                unfrozen.getClass().getSimpleName());
                    }
                }

                pushContext(currentLiveCtx -> {
                    Context modifiedCtx = currentLiveCtx;
                    if (!pathsToAdd.isEmpty()) {
                        modifiedCtx = modifiedCtx.addPathFragments(pathsToAdd);
                    }
                    for (VirtualFragment vfToAdd : virtualFragmentsToAdd) {
                        modifiedCtx = modifiedCtx.addVirtualFragment(vfToAdd);
                    }
                    return new Context(
                            this,
                            modifiedCtx.allFragments().toList(),
                            newHistory,
                            null,
                            CompletableFuture.completedFuture(actionMessage));
                });

                io.showNotification(IConsoleIO.NotificationRole.INFO, actionMessage);
            } catch (CancellationException cex) {
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO, "Copying context items from historical state canceled.");
            }
        });
    }

    /** Adds any virtual fragment directly to the live context. */
    public void addVirtualFragment(VirtualFragment fragment) {
        pushContext(currentLiveCtx -> currentLiveCtx.addVirtualFragment(fragment));
    }

    /**
     * Update the Build fragment based on structured success/failure. Always clears previous BUILD_LOG fragments. Only
     * adds a new fragment when the build failed.
     */
    @Override
    public void updateBuildFragment(boolean success, String buildOutput) {
        var desc = ContextFragment.BUILD_RESULTS.description();
        pushContextQuietly(currentTopCtx -> {
            // Collect build-related fragments to drop:
            //  - Legacy: BuildFragment (BUILD_LOG)
            //  - New: StringFragment with description "Latest Build Results"
            var idsToDrop = currentTopCtx
                    .virtualFragments()
                    .filter(f -> f.getType() == ContextFragment.FragmentType.BUILD_LOG
                            || (f.getType() == ContextFragment.FragmentType.STRING
                                    && f instanceof ContextFragment.StringFragment sf
                                    && desc.equals(sf.description())))
                    .map(ContextFragment::id)
                    .toList();

            var modified = idsToDrop.isEmpty() ? currentTopCtx : currentTopCtx.removeFragmentsByIds(idsToDrop);

            if (success) {
                logger.debug(
                        "Cleared {} previous build fragment(s); build succeeded so not adding new results.",
                        idsToDrop.size());
                return modified;
            }

            var sf = new ContextFragment.StringFragment(
                    this, buildOutput, desc, ContextFragment.BUILD_RESULTS.syntaxStyle());

            logger.debug(
                    "Cleared {} previous build fragment(s); added new build results StringFragment {}",
                    idsToDrop.size(),
                    sf.id());
            return modified.addVirtualFragment(sf);
        });
    }

    @Override
    public String getProcessedBuildOutput() {
        // Prefer new StringFragment with the BUILD_RESULTS description
        var latestString = liveContext()
                .virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.STRING)
                .filter(f -> f instanceof ContextFragment.StringFragment)
                .map(f -> (ContextFragment.StringFragment) f)
                .filter(sf -> sf.description().equals(ContextFragment.BUILD_RESULTS.description()))
                .findFirst();

        if (latestString.isPresent()) {
            return latestString.get().text();
        }
        return "";
    }

    /**
     * Handles pasting an image from the clipboard. Submits a task to summarize the image and adds a PasteImageFragment
     * to the context.
     *
     * @param image The java.awt.Image pasted from the clipboard.
     */
    public ContextFragment.AnonymousImageFragment addPastedImageFragment(
            java.awt.Image image, @Nullable String descriptionOverride) {
        Future<String> descriptionFuture;
        if (descriptionOverride != null && !descriptionOverride.isBlank()) {
            descriptionFuture = CompletableFuture.completedFuture(descriptionOverride);
        } else {
            descriptionFuture = submitSummarizePastedImage(image);
        }

        // Must be final for lambda capture in pushContext
        final var fragment = new ContextFragment.AnonymousImageFragment(this, image, descriptionFuture);
        pushContext(currentLiveCtx -> currentLiveCtx.addVirtualFragment(fragment));
        return fragment;
    }

    /**
     * Handles pasting an image from the clipboard without a predefined description. This will trigger asynchronous
     * summarization of the image.
     *
     * @param image The java.awt.Image pasted from the clipboard.
     */
    public void addPastedImageFragment(java.awt.Image image) {
        addPastedImageFragment(image, null);
    }

    /**
     * Handles capturing text, e.g. from a code block in the MOP. Submits a task to summarize the text and adds a
     * PasteTextFragment to the context.
     *
     * @param text The text to capture.
     */
    public void addPastedTextFragment(String text) {
        var pasteInfoFuture = new DescribePasteWorker(this, text);
        pasteInfoFuture.execute();

        var descriptionFuture = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return pasteInfoFuture.get().description();
                    } catch (InterruptedException | ExecutionException e) {
                        logger.warn("Could not get description for pasted text", e);
                        return "pasted text";
                    }
                },
                contextActionExecutor);
        var syntaxStyleFuture = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return pasteInfoFuture.get().syntaxStyle();
                    } catch (InterruptedException | ExecutionException e) {
                        logger.warn("Could not get syntax style for pasted text", e);
                        return SyntaxConstants.SYNTAX_STYLE_NONE;
                    }
                },
                contextActionExecutor);

        var fragment = new ContextFragment.PasteTextFragment(this, text, descriptionFuture, syntaxStyleFuture);
        addVirtualFragment(fragment);
    }

    /**
     * Adds a specific PathFragment (like GitHistoryFragment) to the read-only part of the live context.
     *
     * @param fragment The PathFragment to add.
     */
    public void addPathFragmentAsync(PathFragment fragment) {
        submitContextTask(() -> {
            pushContext(currentLiveCtx -> currentLiveCtx.addPathFragments(List.of(fragment)));
        });
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
                addVirtualFragment(log);
                return;
            }
            io.systemNotify("No content to capture", "Capture failed", JOptionPane.WARNING_MESSAGE);
        });
    }

    /** usage for identifier with control over including test files */
    public void usageForIdentifier(String identifier, boolean includeTestFiles) {
        var fragment = new ContextFragment.UsageFragment(this, identifier, includeTestFiles);
        pushContext(currentLiveCtx -> currentLiveCtx.addVirtualFragment(fragment));
        String message = "Added uses of " + identifier + (includeTestFiles ? " (including tests)" : "");
        io.showNotification(IConsoleIO.NotificationRole.INFO, message);
    }

    public void sourceCodeForCodeUnit(CodeUnit codeUnit) {
        String sourceCode = null;
        try {
            sourceCode = getAnalyzer()
                    .as(SourceCodeProvider.class)
                    .flatMap(provider -> provider.getSourceForCodeUnit(codeUnit, true))
                    .orElse(null);
        } catch (InterruptedException e) {
            logger.error("Interrupted while trying to get analyzer while attempting to obtain source code");
        }

        if (sourceCode != null) {
            var fragment = new ContextFragment.StringFragment(
                    this,
                    sourceCode,
                    "Source code for " + codeUnit.fqName(),
                    codeUnit.source().getSyntaxStyle());
            pushContext(currentLiveCtx -> currentLiveCtx.addVirtualFragment(fragment));
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
        var fragment = new ContextFragment.CallGraphFragment(this, methodName, depth, false);
        pushContext(currentLiveCtx -> currentLiveCtx.addVirtualFragment(fragment));
        io.showNotification(
                IConsoleIO.NotificationRole.INFO,
                "Add call graph for callers of " + methodName + " with depth " + depth);
    }

    /** callees for method */
    public void calleesForMethod(String methodName, int depth, Map<String, List<CallSite>> callgraph) {
        if (callgraph.isEmpty()) {
            io.showNotification(
                    IConsoleIO.NotificationRole.INFO, "No callees found for " + methodName + " (pre-check).");
            return;
        }
        var fragment = new ContextFragment.CallGraphFragment(this, methodName, depth, true);
        pushContext(currentLiveCtx -> currentLiveCtx.addVirtualFragment(fragment));
        io.showNotification(
                IConsoleIO.NotificationRole.INFO,
                "Add call graph for methods called by " + methodName + " with depth " + depth);
    }

    /** parse stacktrace */
    public boolean addStacktraceFragment(StackTrace stacktrace) {
        var exception = requireNonNull(stacktrace.getExceptionType());
        var sources = new HashSet<CodeUnit>();
        var content = new StringBuilder();
        IAnalyzer localAnalyzer = getAnalyzerUninterrupted();

        localAnalyzer.as(SourceCodeProvider.class).ifPresent(sourceCodeProvider -> {
            for (var element : stacktrace.getFrames()) {
                var methodFullName = element.getClassName() + "." + element.getMethodName();
                var methodSource = sourceCodeProvider.getMethodSource(methodFullName, true);
                if (methodSource.isPresent()) {
                    String className = CodeUnit.toClassname(methodFullName);
                    localAnalyzer
                            .getDefinition(className)
                            .filter(CodeUnit::isClass)
                            .ifPresent(sources::add);
                    content.append(methodFullName).append(":\n");
                    content.append(methodSource.get()).append("\n\n");
                }
            }
        });

        if (content.isEmpty()) {
            logger.debug("No relevant methods found in stacktrace -- adding as text");
            return false;
        }
        var fragment = new ContextFragment.StacktraceFragment(
                this, sources, stacktrace.getOriginalText(), exception, content.toString());
        pushContext(currentLiveCtx -> currentLiveCtx.addVirtualFragment(fragment));
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
        IAnalyzer analyzer;
        analyzer = getAnalyzerUninterrupted();
        if (analyzer.isEmpty()) {
            io.toolError("Code Intelligence is empty; nothing to add");
            return false;
        }

        // Create SkeletonFragments based on input type (files or classes)
        // The fragments will dynamically fetch content.

        boolean summariesAdded = false;
        if (!files.isEmpty()) {
            List<String> filePaths = files.stream().map(ProjectFile::toString).collect(Collectors.toList());
            var fileSummaryFragment = new ContextFragment.SkeletonFragment(
                    this, filePaths, ContextFragment.SummaryType.FILE_SKELETONS); // Pass IContextManager
            addVirtualFragment(fileSummaryFragment);
            String message = "Summarize " + joinFilesForOutput(files);
            io.showNotification(IConsoleIO.NotificationRole.INFO, message);
            summariesAdded = true;
        }

        if (!classes.isEmpty()) {
            List<String> classFqns = classes.stream().map(CodeUnit::fqName).collect(Collectors.toList());
            var classSummaryFragment = new ContextFragment.SkeletonFragment(
                    this, classFqns, ContextFragment.SummaryType.CODEUNIT_SKELETON); // Pass IContextManager
            addVirtualFragment(classSummaryFragment);
            String message = "Summarize " + joinClassesForOutput(classFqns);
            io.showNotification(IConsoleIO.NotificationRole.INFO, message);
            summariesAdded = true;
        }
        if (!summariesAdded) {
            io.toolError("No files or classes provided to summarize.");
            return false;
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
            return fragments.stream().map(ContextFragment::shortDescription).collect(Collectors.joining(", "));
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

    /**
     * @return A list containing two messages: a UserMessage with the string representation of the task history, and an
     *     AiMessage acknowledging it. Returns an empty list if there is no history.
     */
    @Override
    public List<ChatMessage> getHistoryMessages() {
        return CodePrompts.instance.getHistoryMessages(topContext());
    }

    public List<ChatMessage> getHistoryMessagesForCopy() {
        var taskHistory = topContext().getTaskHistory();

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
        closeAsync(5_000).join();
    }

    public CompletableFuture<Void> closeAsync(long awaitMillis) {
        // Cancel BuildAgent task if still running
        if (buildAgentFuture != null && !buildAgentFuture.isDone()) {
            logger.debug("Cancelling BuildAgent task due to ContextManager shutdown");
            buildAgentFuture.cancel(true);
        }

        // Close watchers before shutting down executors that may be used by them
        analyzerWrapper.close();
        lowMemoryWatcherManager.close();

        var userActionFuture = userActionExecutor.shutdownAndAwait(awaitMillis, "userActionExecutor");
        var contextActionFuture = contextActionExecutor.shutdownAndAwait(awaitMillis, "contextActionExecutor");
        var backgroundFuture = backgroundTasks.shutdownAndAwait(awaitMillis, "backgroundTasks");
        var userActionsFuture = userActions.shutdownAndAwait(awaitMillis);

        return CompletableFuture.allOf(userActionFuture, contextActionFuture, backgroundFuture, userActionsFuture)
                .whenComplete((v, t) -> project.close());
    }

    public boolean isLlmTaskInProgress() {
        return userActions.isLlmTaskInProgress();
    }

    /** Returns current analyzer readiness without blocking. */
    public boolean isAnalyzerReady() {
        return analyzerWrapper.getNonBlocking() != null;
    }

    @Override
    public Set<ProjectFile> getFilesInContext() {
        return topContext().fileFragments().flatMap(cf -> cf.files().stream()).collect(Collectors.toSet());
    }

    /** Returns the current session's domain-model task list. Always non-null. */
    public TaskList.TaskListData getTaskList() {
        return taskList;
    }

    /**
     * Appends the given tasks (non-blank lines) to the current session's task list and persists it. Each appended task
     * is created with done=false.
     */
    @Override
    public void appendTasksToTaskList(List<String> tasks) {
        var additions = tasks.stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> new TaskList.TaskItem(s, false))
                .toList();
        if (additions.isEmpty()) {
            return;
        }

        var combined = new java.util.ArrayList<TaskList.TaskItem>();
        combined.addAll(taskList.tasks());
        combined.addAll(additions);

        var newData = new TaskList.TaskListData(List.copyOf(combined));
        this.taskList = newData;

        // Persist via existing SessionManager API (UI DTO)
        project.getSessionManager().writeTaskList(currentSessionId, newData);
        if (io instanceof Chrome chrome) {
            chrome.refreshTaskListUI();
        }

        io.showNotification(
                IConsoleIO.NotificationRole.INFO,
                "Added " + tasks.size() + " task" + (tasks.size() == 1 ? "" : "s") + " to Task List");
    }

    /**
     * Replace the current session's task list and persist it via SessionManager. This is the single entry-point UI code
     * should call after modifying the task list.
     */
    public void setTaskList(TaskList.TaskListData data) {
        this.taskList = data;
        project.getSessionManager().writeTaskList(currentSessionId, data).exceptionally(ex -> {
            logger.warn("Failed to persist updated task list for session {}: {}", currentSessionId, ex.getMessage());
            return null;
        });
    }

    // Load and cache the task list for a specific session ID; on error, set to empty
    private void loadTaskListForSession(UUID sessionId) {
        try {
            this.taskList = project.getSessionManager().readTaskList(sessionId).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("Unable to load task list for session {}", sessionId, e);
            this.taskList = new TaskList.TaskListData(List.of());
        }
    }

    /**
     * Execute a single task using ArchitectAgent with explicit options.
     *
     * @param task Task to execute (non-blank text).
     * @param autoCommit whether to commit any modified files after a successful run
     * @param autoCompress whether to compress conversation history after a successful run
     * @return TaskResult from ArchitectAgent execution.
     */
    public TaskResult executeTask(TaskList.TaskItem task, boolean autoCommit, boolean autoCompress)
            throws InterruptedException {
        var prompt = task.text().strip();
        if (prompt.isEmpty()) {
            throw new IllegalArgumentException("Task text must be non-blank");
        }

        TaskResult result;
        try (var scope = beginTask(prompt, false)) {
            var planningModel = requireNonNull(getService().getModel(Service.GEMINI_2_5_PRO));
            var codeModel = getCodeModel();
            var agent = new ArchitectAgent(this, planningModel, codeModel, prompt, scope);
            result = agent.executeWithSearch(scope);
        } finally {
            // mirror panel behavior
            checkBalanceAndNotify();
        }

        if (result.stopDetails().reason() == TaskResult.StopReason.SUCCESS) {
            if (autoCommit) {
                new GitWorkflow(this).performAutoCommit(prompt);
            }
            if (autoCompress) {
                compressHistory(); // synchronous
            }
            // Mark the task as done and persist the updated list
            markTaskDoneAndPersist(task);
        }

        return result;
    }

    /** Replace the given task with its 'done=true' variant and persist the task list for the current session. */
    private void markTaskDoneAndPersist(TaskList.TaskItem task) {
        var existing = new ArrayList<>(taskList.tasks());
        int idx = existing.indexOf(task);
        if (idx < 0) {
            // Fallback: find first matching by text (not done) if equals() does not match
            for (int i = 0; i < existing.size(); i++) {
                var it = existing.get(i);
                if (!it.done() && it.text().equals(task.text())) {
                    idx = i;
                    break;
                }
            }
        }
        if (idx >= 0) {
            existing.set(idx, new TaskList.TaskItem(task.text(), true));
            this.taskList = new TaskList.TaskListData(List.copyOf(existing));
            project.getSessionManager()
                    .writeTaskList(currentSessionId, this.taskList)
                    .exceptionally(ex -> {
                        logger.warn(
                                "Failed to persist updated task list for session {}: {}",
                                currentSessionId,
                                ex.getMessage());
                        return null;
                    });
        }
    }

    private void captureGitState(Context frozenContext) {
        if (!project.hasGit()) {
            return;
        }

        try {
            var repo = project.getRepo();
            String commitHash = repo.getCurrentCommitId();
            String diff = repo.diff();

            var gitState = new ContextHistory.GitState(commitHash, diff.isEmpty() ? null : diff);
            contextHistory.addGitState(frozenContext.id(), gitState);
        } catch (Exception e) {
            logger.error("Failed to capture git state", e);
        }
    }

    /**
     * Processes external file changes by checking for workspace content changes and then deciding whether to replace
     * the top context or push a new one.
     *
     * @return true if context has changed, false otherwise
     */
    private boolean processExternalFileChangesIfNeeded() {
        var newFrozenContext = contextHistory.processExternalFileChangesIfNeeded();
        if (newFrozenContext != null) {
            contextPushed(newFrozenContext);
            return true;
        }
        return false;
    }

    /**
     * Pushes context changes using a generator function. The generator is applied to the current `liveContext`. The
     * resulting context becomes the new `liveContext`. A frozen snapshot of this new `liveContext` is added to
     * `ContextHistory`.
     *
     * @param contextGenerator A function that takes the current live context and returns an updated context.
     * @return The new `liveContext`, or the existing `liveContext` if no changes were made by the generator.
     */
    @Override
    public Context pushContext(Function<Context, Context> contextGenerator) {
        var oldLiveContext = liveContext();
        var newLiveContext = contextHistory.push(contextGenerator);
        if (oldLiveContext.equals(newLiveContext)) {
            // No change occurred
            return newLiveContext;
        }

        contextPushed(contextHistory.topContext());

        // Auto-compress conversation history if enabled and exceeds configured threshold of the context window
        if (MainProject.getHistoryAutoCompress()
                && !newLiveContext.getTaskHistory().isEmpty()) {
            var cf = new ContextFragment.HistoryFragment(this, newLiveContext.getTaskHistory());
            int tokenCount = Messages.getApproximateTokens(cf.format());

            try {
                var svc = getService();
                var model = getCodeModel();
                int maxInputTokens = svc.getMaxInputTokens(model);
                double thresholdPct = MainProject.getHistoryAutoCompressThresholdPercent() / 100.0;
                if (tokenCount > (int) Math.ceil(maxInputTokens * thresholdPct)) {
                    compressHistoryAsync();
                }
            } catch (ServiceWrapper.ServiceInitializationException e) {
                // FIXME CI does not have a working Service so this errors out
            }
        }
        return newLiveContext;
    }

    /**
     * Pushes context changes silently using a generator function. The generator is applied to the current
     * `topContext()` (frozen context) instead of `liveContext()`. This creates a new context state without triggering
     * history compression or other side effects.
     *
     * @param contextGenerator A function that takes the current top context and returns an updated context.
     * @return The new top context, or the existing top context if no changes were made by the generator.
     */
    public Context pushContextQuietly(Function<Context, Context> contextGenerator) {
        var newTopContext = contextHistory.pushQuietly(contextGenerator);
        if (!topContext().equals(newTopContext)) {
            contextPushed(newTopContext);
        }
        return newTopContext;
    }

    private void contextPushed(Context frozen) {
        captureGitState(frozen);
        // Ensure listeners are notified on the EDT
        SwingUtilities.invokeLater(() -> notifyContextListeners(frozen));

        project.getSessionManager()
                .saveHistory(contextHistory, currentSessionId); // Persist the history of frozen contexts
    }

    /**
     * Updates the selected FROZEN context in history from the UI. Called by Chrome when the user selects a row in the
     * history table.
     *
     * @param frozenContextFromHistory The FROZEN context selected in the UI.
     */
    public void setSelectedContext(Context frozenContextFromHistory) {
        contextHistory.setSelectedContext(frozenContextFromHistory);
    }

    /**
     * should only be called with Frozen contexts, so that calling its methods doesn't cause an expensive Analyzer
     * operation on the EDT
     */
    private void notifyContextListeners(@Nullable Context ctx) {
        if (ctx == null) {
            logger.warn("notifyContextListeners called with null context");
            return;
        }
        assert !ctx.containsDynamicFragments();
        for (var listener : contextListeners) {
            listener.contextChanged(ctx);
        }
    }

    private final ConcurrentMap<Callable<?>, String> taskDescriptions = new ConcurrentHashMap<>();

    public SummarizeWorker submitSummarizeTaskForConversation(String input) {
        var worker = new SummarizeWorker(this, input, 5) {
            @Override
            protected void done() {
                io.postSummarize();
            }
        };

        worker.execute();
        return worker;
    }

    /**
     * Submits a background task using SwingWorker to summarize a pasted image. This uses the quickest model to generate
     * a short description.
     *
     * @param pastedImage The java.awt.Image that was pasted.
     * @return A SwingWorker whose `get()` method will return the description string.
     */
    public SwingWorker<String, Void> submitSummarizePastedImage(java.awt.Image pastedImage) {
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                try {
                    // Convert AWT Image to LangChain4j Image (requires Base64 encoding)
                    var l4jImage = ImageUtil.toL4JImage(pastedImage); // Assumes ImageUtil helper exists
                    var imageContent = ImageContent.from(l4jImage);

                    // Create prompt messages for the LLM
                    var textContent = TextContent.from(
                            "Briefly describe this image in a few words (e.g., 'screenshot of code', 'diagram of system').");
                    var userMessage = UserMessage.from(textContent, imageContent);
                    List<ChatMessage> messages = List.of(userMessage);
                    Llm.StreamingResult result;
                    try {
                        result = getLlm(service.quickModel(), "Summarize pasted image")
                                .sendRequest(messages);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    if (result.error() != null) {
                        logger.warn("Image summarization failed or was cancelled.");
                        return "(Image summarization failed)";
                    }
                    var description = result.text();
                    return description.isBlank() ? "(Image description empty)" : description.trim();
                } catch (IOException e) {
                    logger.error("Failed to convert pasted image for summarization", e);
                    return "(Error processing image)";
                }
            }

            @Override
            protected void done() {
                io.postSummarize();
            }
        };

        worker.execute();
        return worker;
    }

    /** Submits a background task to the internal background executor (non-user actions). */
    @Override
    public <T> CompletableFuture<T> submitBackgroundTask(String taskDescription, Callable<T> task) {
        var future = backgroundTasks.submit(() -> {
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

        // Track the future with its description
        taskDescriptions.put(task, taskDescription);
        return future;
    }

    /**
     * Submits a background task that doesn't return a result.
     *
     * @param taskDescription a description of the task
     * @param task the task to execute
     * @return a {@link Future} representing pending completion of the task
     */
    public CompletableFuture<Void> submitBackgroundTask(String taskDescription, Runnable task) {
        return submitBackgroundTask(taskDescription, () -> {
            task.run();
            return null;
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

            BuildAgent agent = new BuildAgent(project, getLlm(getSearchModel(), "Infer build details"), toolRegistry);
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

            if (io instanceof Chrome chrome) {
                SwingUtilities.invokeLater(() -> {
                    var dlg = SettingsDialog.showSettingsDialog(chrome, "Build");
                    dlg.getProjectPanel().showBuildBanner();
                });
            }

            io.showNotification(IConsoleIO.NotificationRole.INFO, "Build details inferred and saved");
            return inferredDetails;
        });
    }

    public void reloadService() {
        if (isReloadingService.compareAndSet(false, true)) {
            // Run reinit in the background so callers don't block; notify UI listeners when finished.
            submitBackgroundTask("Reloading service", () -> {
                try {
                    service.reinit(project);
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
        analyzerWrapper.pause();
        try {
            return callable.call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            analyzerWrapper.resume();
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

    /** Ensure style guide exists, generating if needed */
    private void ensureStyleGuide() {
        if (!project.getStyleGuide().isEmpty()) {
            return;
        }

        if (!project.hasGit()) {
            io.showNotification(
                    IConsoleIO.NotificationRole.INFO, "No Git repository found, skipping style guide generation.");
            return;
        }

        submitBackgroundTask("Generating style guide", () -> {
            try {
                io.showNotification(IConsoleIO.NotificationRole.INFO, "Generating project style guide...");
                // Use a reasonable limit for style guide generation context
                var topClasses =
                        GitDistance.getMostImportantFiles((GitRepo) project.getRepo(), Context.MAX_AUTO_CONTEXT_FILES)
                                .stream()
                                .limit(10)
                                .toList();

                if (topClasses.isEmpty()) {
                    io.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            "No classes found via PageRank for style guide generation.");
                    project.saveStyleGuide(
                            "# Style Guide\n\n(Could not be generated automatically - no relevant classes found)\n");
                    return null;
                }

                var codeForLLM = new StringBuilder();
                var tokens = 0;
                int MAX_STYLE_TOKENS = 30000; // Limit context size for style guide
                for (var file : topClasses) {
                    String chunk; // Declare chunk once outside the try-catch
                    // Use project root for relative path display if possible
                    var relativePath =
                            project.getRoot().relativize(file.absPath()).toString();
                    var contentOpt = file.read();
                    if (contentOpt.isEmpty()) {
                        logger.debug("Skipping unreadable file {} for style guide", relativePath);
                        continue;
                    }
                    chunk = "<file path=\"%s\">\n%s\n</file>\n".formatted(relativePath, contentOpt.get());
                    // Calculate tokens and check limits
                    var chunkTokens = Messages.getApproximateTokens(chunk);
                    if (tokens > 0 && tokens + chunkTokens > MAX_STYLE_TOKENS) { // Check if adding exceeds limit
                        logger.debug(
                                "Style guide context limit ({}) reached after {} tokens.", MAX_STYLE_TOKENS, tokens);
                        break; // Exit the loop if limit reached
                    }
                    if (chunkTokens > MAX_STYLE_TOKENS) { // Skip single large files
                        logger.debug(
                                "Skipping large file {} ({} tokens) for style guide context.",
                                relativePath,
                                chunkTokens);
                        continue; // Skip to next file
                    }
                    // Append chunk if within limits
                    codeForLLM.append(chunk);
                    tokens += chunkTokens;
                    logger.trace(
                            "Added {} ({} tokens, total {}) to style guide context", relativePath, chunkTokens, tokens);
                }

                if (codeForLLM.isEmpty()) {
                    io.showNotification(
                            IConsoleIO.NotificationRole.INFO, "No relevant code found for style guide generation");
                    return null;
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
                                        .stripIndent()
                                        .formatted(codeForLLM)));

                var result = getLlm(getSearchModel(), "Generate style guide").sendRequest(messages);
                if (result.error() != null) {
                    String message =
                            "Failed to generate style guide: " + result.error().getMessage();
                    io.showNotification(IConsoleIO.NotificationRole.INFO, message);
                    project.saveStyleGuide("# Style Guide\n\n(Generation failed)\n");
                    return null;
                }
                var styleGuide = result.text();
                if (styleGuide.isBlank()) {
                    io.showNotification(IConsoleIO.NotificationRole.INFO, "LLM returned empty style guide.");
                    project.saveStyleGuide("# Style Guide\n\n(LLM returned empty result)\n");
                    return null;
                }
                project.saveStyleGuide(styleGuide);
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO, "Style guide generated and saved to .brokk/style.md");
            } catch (Exception e) {
                logger.error("Error generating style guide", e);
            }
            return null;
        });
    }

    /** Ensure review guide exists, generating if needed */
    private void ensureReviewGuide() {
        if (!project.getReviewGuide().isEmpty()) {
            return;
        }

        project.saveReviewGuide(MainProject.DEFAULT_REVIEW_GUIDE);
        io.showNotification(IConsoleIO.NotificationRole.INFO, "Review guide created at .brokk/review.md");
    }

    /**
     * Compresses a single TaskEntry into a summary string using the quickest model.
     *
     * @param entry The TaskEntry to compress.
     * @return A new compressed TaskEntry, or the original entry (with updated sequence) if compression fails.
     */
    public TaskEntry compressHistory(TaskEntry entry) {
        // If already compressed, return as is
        if (entry.isCompressed()) {
            return entry;
        }

        // Compress
        var historyString = entry.toString();
        var msgs = SummarizerPrompts.instance.compressHistory(historyString);
        Llm.StreamingResult result;
        try {
            result = getLlm(service.quickModel(), "Compress history entry").sendRequest(msgs);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        if (result.error() != null) {
            logger.warn("History compression failed for entry: {}", entry, result.error());
            return entry;
        }

        String summary = result.text();
        if (summary.isBlank()) {
            logger.warn("History compression resulted in empty summary for entry: {}", entry);
            return entry;
        }

        logger.debug("Compressed summary '{}' from entry: {}", summary, entry);
        return TaskEntry.fromCompressed(entry.sequence(), summary);
    }

    /** Begin a new aggregating scope with explicit compress-at-commit semantics and non-text resolution mode. */
    public TaskScope beginTask(String input, boolean compressAtCommit, MergeAgent.NonTextResolutionMode nonTextMode) {
        // Kick off UI transcript (streaming) immediately and seed MOP with a mode marker as the first message.
        var messages = List.<ChatMessage>of(new UserMessage(input));
        var currentTaskFragment = new ContextFragment.TaskFragment(this, messages, input);
        var history = topContext().getTaskHistory();
        io.setLlmAndHistoryOutput(history, new TaskEntry(-1, currentTaskFragment, null));

        return new TaskScope(compressAtCommit, nonTextMode);
    }

    /** Backwards-compatible overload: defaults non-text handling to OFF. */
    public TaskScope beginTask(String input, boolean compressAtCommit) {
        return beginTask(input, compressAtCommit, MergeAgent.NonTextResolutionMode.OFF);
    }

    /** Aggregating scope that collects messages/files and commits once. */
    public final class TaskScope implements AutoCloseable {
        private final boolean compressAtCommit;
        private final MergeAgent.NonTextResolutionMode nonTextMode;
        private final ArrayList<TaskResult> results;
        private boolean closed = false;

        private TaskScope(boolean compressAtCommit, MergeAgent.NonTextResolutionMode nonTextMode) {
            io.blockLlmOutput(true);
            this.compressAtCommit = compressAtCommit;
            this.nonTextMode = nonTextMode;
            this.results = new ArrayList<>();
        }

        public void append(TaskResult result) {
            assert !closed : "TaskScope already closed";
            results.add(result);
        }

        public MergeAgent.NonTextResolutionMode nonTextMode() {
            return nonTextMode;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                if (results.isEmpty()) {
                    return;
                }

                if (results.size() == 1) {
                    var only = results.getFirst();
                    if (!only.changedFiles().isEmpty()) {
                        addFiles(only.changedFiles());
                    }
                    // Use the exact unchanged TaskResult if only one was appended
                    pushFinalHistory(only, compressAtCommit);
                    return;
                }

                // Don't aggregate stop details (presumably all success except possibly the last)
                var lastStop = results.getLast().stopDetails();
                // Aggregate changed files
                var aggregatedFiles =
                        results.stream().flatMap(r -> r.changedFiles().stream()).collect(Collectors.toSet());
                // Aggregate all messages across results (input are expected to be the first message)
                var aggregatedMessages = results.stream()
                        .flatMap(r -> r.output().messages().stream())
                        .toList();
                // Action description
                String actionDescription;
                if (results.size() == 1) {
                    actionDescription = results.getFirst().actionDescription();
                } else {
                    // Construct synthetic description from first UserMessage and the last AiMessage
                    var firstUserOpt = aggregatedMessages.stream()
                            .filter(m -> m instanceof UserMessage)
                            .findFirst();
                    var lastAiOpt = IntStream.iterate(aggregatedMessages.size() - 1, i -> i - 1)
                            .limit(aggregatedMessages.size())
                            .mapToObj(aggregatedMessages::get)
                            .filter(m -> m instanceof AiMessage)
                            .findFirst();
                    if (firstUserOpt.isPresent() && lastAiOpt.isPresent()) {
                        var selected = List.of(firstUserOpt.get(), lastAiOpt.get());
                        actionDescription =
                                selected.stream().map(Messages::getText).collect(Collectors.joining("\n\n"));
                    } else {
                        actionDescription = results.getFirst().actionDescription();
                    }
                }

                var finalResult = new TaskResult(
                        ContextManager.this, actionDescription, aggregatedMessages, aggregatedFiles, lastStop);
                pushFinalHistory(finalResult, compressAtCommit);
            } finally {
                io.blockLlmOutput(false);
            }
        }
    }

    /** Single entry-point to actually push a TaskResult to history (used by TaskScope). */
    private void pushFinalHistory(TaskResult result, boolean compress) {
        if (result.stopDetails().reason() == TaskResult.StopReason.INTERRUPTED
                && result.output().messages().stream().noneMatch(m -> m instanceof AiMessage)) {
            logger.debug("Command cancelled before LLM responded");
            return;
        }
        if (result.output().messages().isEmpty() && result.changedFiles().isEmpty()) {
            logger.debug("Empty TaskResult");
            return;
        }

        var action = result.actionDescription();
        logger.debug(
                "Adding session result to history. Action: '{}', Changed files: {}, Reason: {}",
                action,
                result.changedFiles(),
                result.stopDetails());

        Future<String> actionFuture = submitSummarizeTaskForConversation(action);

        /*
         * Perform ALL mutations to the context in a single pushContext call:
         *   1.  Make every changed file editable (if not already).
         *   2.  Create and append the TaskEntry.
         * This guarantees the changed files are present in the frozen snapshot
         * created by pushContext, so undo/redo can restore them correctly.
         */
        pushContext(currentLiveCtx -> {
            Context updated = currentLiveCtx;

            // Step 1: ensure changed files are tracked as editable
            if (!result.changedFiles().isEmpty()) {
                // Capture current editable files once to keep the lambda valid
                var existingEditableFiles = updated.fileFragments()
                        .filter(cf -> cf.getType().isEditable())
                        .flatMap(cf -> cf.files().stream())
                        .collect(Collectors.toSet());

                var fragmentsToAdd = result.changedFiles().stream()
                        // avoid duplicates – only add if not already editable
                        .filter(pf -> !existingEditableFiles.contains(pf))
                        .map(pf -> new ContextFragment.ProjectPathFragment(pf, this))
                        .toList();

                if (!fragmentsToAdd.isEmpty()) {
                    updated = updated.addPathFragments(fragmentsToAdd);
                }
            }

            // Step 2: build TaskEntry *after* editable-file update
            TaskEntry entry = updated.createTaskEntry(result);
            TaskEntry finalEntry = compress ? compressHistory(entry) : entry;

            return updated.addHistoryEntry(finalEntry, result.output(), actionFuture);
        });

        // Auto-rename session if it still has the default name
        var sessionManager = project.getSessionManager();
        var sessions = sessionManager.listSessions();
        var currentSession =
                sessions.stream().filter(s -> s.id().equals(currentSessionId)).findFirst();

        if (currentSession.isPresent()
                && DEFAULT_SESSION_NAME.equals(currentSession.get().name())) {
            renameSessionAsync(currentSessionId, actionFuture).thenRun(() -> {
                if (io instanceof Chrome) {
                    project.sessionsListChanged();
                }
            });
        }
    }

    public List<Context> getContextHistoryList() {
        return contextHistory.getHistory();
    }

    public ContextHistory getContextHistory() {
        return contextHistory;
    }

    public UUID getCurrentSessionId() {
        return currentSessionId;
    }

    /**
     * Loads the ContextHistory for a specific session without switching to it. This allows viewing/inspecting session
     * history without changing the current session.
     *
     * @param sessionId The UUID of the session whose history to load
     * @return A CompletableFuture that resolves to the ContextHistory for the specified session
     */
    public CompletableFuture<ContextHistory> loadSessionHistoryAsync(UUID sessionId) {
        return CompletableFuture.supplyAsync(
                () -> project.getSessionManager().loadHistory(sessionId, this), backgroundTasks);
    }

    /**
     * Creates a new session with the given name and switches to it asynchronously. First attempts to reuse an existing
     * empty session before creating a new one.
     *
     * @param name The name for the new session
     * @return A CompletableFuture representing the completion of the session creation task
     */
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
            contextHistory = new ContextHistory(new Context(this, null));
            project.getSessionManager()
                    .saveHistory(contextHistory, currentSessionId); // Save the initial empty/welcome state

            // initialize empty task list and persist
            this.taskList = new TaskList.TaskListData(List.of());
            project.getSessionManager().writeTaskList(currentSessionId, this.taskList);

            // notifications
            notifyContextListeners(topContext());
            io.updateContextHistoryTable(topContext());
        }
    }

    private Optional<SessionInfo> getEmptySessionToReuseInsteadOfCreatingNew(String name) {
        var potentialEmptySessions = project.getSessionManager().listSessions().stream()
                .filter(session -> session.name().equals(name))
                .filter(session -> !session.isSessionModified())
                .filter(session -> !SessionRegistry.isSessionActiveElsewhere(project.getRoot(), session.id()))
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
        SessionRegistry.update(project.getRoot(), sessionId);
        project.setLastActiveSession(sessionId);
    }

    public void createSessionWithoutGui(Context sourceFrozenContext, String newSessionName) {
        var sessionManager = project.getSessionManager();
        var newSessionInfo = sessionManager.newSession(newSessionName);
        updateActiveSession(newSessionInfo.id());
        var ctx = Context.unfreeze(newContextFrom(sourceFrozenContext));
        // the intent is that we save a history to the new session that initializeCurrentSessionAndHistory will pull in
        // later
        var ch = new ContextHistory(ctx);
        sessionManager.saveHistory(ch, newSessionInfo.id());
        // Initialize empty task list for the new session and persist
        this.taskList = new TaskList.TaskListData(List.of());
        sessionManager.writeTaskList(newSessionInfo.id(), this.taskList);
    }

    /**
     * Creates a new session with the given name, copies the workspace from the sourceFrozenContext, and switches to it
     * asynchronously.
     *
     * @param sourceFrozenContext The context whose workspace items will be copied.
     * @param newSessionName The name for the new session.
     * @return A CompletableFuture representing the completion of the session creation task.
     */
    public CompletableFuture<Void> createSessionFromContextAsync(Context sourceFrozenContext, String newSessionName) {
        return submitExclusiveAction(() -> {
                    logger.debug(
                            "Attempting to create and switch to new session '{}' from workspace of context '{}'",
                            newSessionName,
                            sourceFrozenContext.getAction());

                    var sessionManager = project.getSessionManager();
                    // 1. Create new session info
                    var newSessionInfo = sessionManager.newSession(newSessionName);
                    updateActiveSession(newSessionInfo.id());
                    logger.debug("Switched to new session: {} ({})", newSessionInfo.name(), newSessionInfo.id());

                    // 2. Create the initial context for the new session.
                    // Only its top-level action/parsedOutput will be changed to reflect it's a new session.
                    var initialContextForNewSession = newContextFrom(sourceFrozenContext);

                    // 3. Initialize the ContextManager's history for the new session with this single context.
                    var newCh = new ContextHistory(Context.unfreeze(initialContextForNewSession));
                    newCh.addResetEdge(sourceFrozenContext, initialContextForNewSession);
                    this.contextHistory = newCh;

                    // 4. This is now handled by the ContextHistory constructor.

                    // 5. Save the new session's history (which now contains one entry).
                    sessionManager.saveHistory(this.contextHistory, this.currentSessionId);

                    // Initialize empty task list for the new session and persist
                    this.taskList = new TaskList.TaskListData(List.of());
                    sessionManager.writeTaskList(this.currentSessionId, this.taskList);

                    // 6. Notify UI about the context change.
                    notifyContextListeners(topContext());
                })
                .exceptionally(e -> {
                    logger.error("Failed to create new session from workspace", e);
                    throw new RuntimeException("Failed to create new session from workspace", e);
                });
    }

    /** returns a frozen Context based on the source one */
    private Context newContextFrom(Context sourceFrozenContext) {
        var newActionDescription = "New session (from: " + sourceFrozenContext.getAction() + ")";
        var newActionFuture = CompletableFuture.completedFuture(newActionDescription);
        var newParsedOutputFragment = new ContextFragment.TaskFragment(
                this, List.of(SystemMessage.from(newActionDescription)), newActionDescription);
        return sourceFrozenContext.withParsedOutput(newParsedOutputFragment, newActionFuture);
    }

    /**
     * Switches to an existing session asynchronously. Checks if the session is active elsewhere before switching.
     *
     * @param sessionId The UUID of the session to switch to
     * @return A CompletableFuture representing the completion of the session switch task
     */
    public CompletableFuture<Void> switchSessionAsync(UUID sessionId) {
        var sessionManager = project.getSessionManager();
        var otherWorktreeOpt = SessionRegistry.findAnotherWorktreeWithActiveSession(project.getRoot(), sessionId);
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

        ContextHistory loadedCh = sessionManager.loadHistory(sessionId, this);

        if (loadedCh == null) {
            io.toolError("Error while loading history for session '%s'.".formatted(sessionName));
        } else {
            updateActiveSession(sessionId); // Mark as active
            contextHistory = loadedCh;

            // Load task list for the switched session
            loadTaskListForSession(sessionId);
        }
        notifyContextListeners(topContext());
        io.updateContextHistoryTable(topContext());
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

    /**
     * Copies an existing session with a new name and switches to it asynchronously.
     *
     * @param originalSessionId The UUID of the session to copy
     * @param originalSessionName The name of the session to copy
     * @return A CompletableFuture representing the completion of the session copy task
     */
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
                    var loadedCh = sessionManager.loadHistory(copiedSessionInfo.id(), this);
                    assert loadedCh != null && !loadedCh.getHistory().isEmpty()
                            : "Copied session history should not be null or empty";
                    final ContextHistory nnLoadedCh = requireNonNull(
                            loadedCh, "Copied session history (loadedCh) should not be null after assertion");
                    this.contextHistory = nnLoadedCh;
                    updateActiveSession(copiedSessionInfo.id());

                    notifyContextListeners(topContext());
                    io.updateContextHistoryTable(topContext());
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

    // Convert a throwable to a string with full stack trace
    private String getStackTraceAsString(Throwable throwable) {
        var sw = new java.io.StringWriter();
        var pw = new java.io.PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }

    @Override
    public IConsoleIO getIo() {
        return io;
    }

    /**
     * Allows injection of a custom {@link IConsoleIO} implementation, enabling head-less (CLI) operation where a GUI is
     * not available.
     *
     * <p>This should be invoked immediately after constructing the {@code ContextManager} but before any tasks are
     * submitted, so that all logging and UI callbacks are routed to the desired sink.
     */
    public void createHeadless() {
        this.io = new HeadlessConsole();
        this.userActions.setIo(this.io);

        // no AnalyzerListener, instead we will block for it to be ready
        this.analyzerWrapper = new AnalyzerWrapper(project, null);
        try {
            analyzerWrapper.get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        initializeCurrentSessionAndHistory(true);

        ensureStyleGuide();
        ensureReviewGuide();
        ensureBuildDetailsAsync();
        cleanupOldHistoryAsync();

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
                float balance = service.get().getUserBalance();
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
                                        .stripIndent()
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
            } catch (java.io.IOException e) {
                logger.error("Failed to check user balance", e);
            }
        });
    }

    /**
     * Asynchronously compresses the entire conversation history of the currently selected context. Replaces the history
     * with summarized versions of each task entry. This runs as a user action because it visibly modifies the context
     * history.
     */
    public CompletableFuture<?> compressHistoryAsync() {
        return submitExclusiveAction(() -> {
            compressHistory();
        });
    }

    public void compressHistory() {
        io.disableHistoryPanel();
        try {
            // Operate on the task history
            var taskHistoryToCompress = topContext().getTaskHistory();
            if (taskHistoryToCompress.isEmpty()) {
                io.showNotification(IConsoleIO.NotificationRole.INFO, "No history to compress.");
                return;
            }

            io.showNotification(IConsoleIO.NotificationRole.INFO, "Compressing conversation history...");

            List<TaskEntry> compressedTaskEntries = taskHistoryToCompress.parallelStream()
                    .map(this::compressHistory)
                    .collect(Collectors.toCollection(() -> new ArrayList<>(taskHistoryToCompress.size())));

            boolean changed = IntStream.range(0, taskHistoryToCompress.size())
                    .anyMatch(i -> !taskHistoryToCompress.get(i).equals(compressedTaskEntries.get(i)));

            if (!changed) {
                io.showNotification(IConsoleIO.NotificationRole.INFO, "History is already compressed.");
                return;
            }

            // pushContext will update liveContext with the compressed history
            // and add a frozen version to contextHistory.
            pushContext(currentLiveCtx -> currentLiveCtx.withCompressedHistory(List.copyOf(compressedTaskEntries)));
            io.showNotification(IConsoleIO.NotificationRole.INFO, "Task history compressed successfully.");
        } finally {
            SwingUtilities.invokeLater(io::enableHistoryPanel);
        }
    }

    public static class SummarizeWorker extends SwingWorker<String, String> {
        private final IContextManager cm;
        private final String content;
        private final int words;

        public SummarizeWorker(IContextManager cm, String content, int words) {
            this.cm = cm;
            this.content = content;
            this.words = words;
        }

        @Override
        protected String doInBackground() {
            var msgs = SummarizerPrompts.instance.collectMessages(content, words);
            // Use quickModel for summarization
            Llm.StreamingResult result;
            try {
                result = cm.getLlm(cm.getService().quickestModel(), "Summarize: " + content)
                        .sendRequest(msgs);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
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
}
