package io.github.jbellis.brokk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
<<<<<<< HEAD
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ToolChoice;
=======
import dev.langchain4j.model.chat.StreamingChatModel;
>>>>>>> 5e5045ad (enh: Upgrade Langchain4j to 1.1.0)
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.agents.BuildAgent.BuildDetails;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.cli.HeadlessConsole;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.context.ContextFragment.PathFragment;
import io.github.jbellis.brokk.context.ContextFragment.VirtualFragment;
import io.github.jbellis.brokk.context.ContextHistory;
import io.github.jbellis.brokk.context.ContextHistory.UndoResult;
import io.github.jbellis.brokk.context.FrozenFragment;
import io.github.jbellis.brokk.exception.OomShutdownHandler;
import io.github.jbellis.brokk.gui.Chrome;
import org.jetbrains.annotations.Nullable;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.prompts.SummarizerPrompts;
import io.github.jbellis.brokk.gui.dialogs.SettingsDialog;
import static io.github.jbellis.brokk.SessionManager.SessionInfo;
import io.github.jbellis.brokk.tools.SearchTools;
import io.github.jbellis.brokk.tools.ToolRegistry;
import io.github.jbellis.brokk.tools.WorkspaceTools;
import io.github.jbellis.brokk.util.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.Math.max;
import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

/**
 * Manages the current and previous context, along with other state like prompts and message history.
 * <p>
 * Updated to:
 * - Remove OperationResult,
 * - Move UI business logic from Chrome to here as asynchronous tasks,
 * - Directly call into Chrome’s UI methods from background tasks (via invokeLater),
 * - Provide separate async methods for “Go”, “Ask”, “Search”, context additions, etc.
 */
public class ContextManager implements IContextManager, AutoCloseable {
    private static final Logger logger = LogManager.getLogger(ContextManager.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private IConsoleIO io; // for UI feedback - Initialized in createGui
    private final AnalyzerWrapper analyzerWrapper;

    // Run main user-driven tasks in background (Code/Ask/Search/Run)
    // Only one of these can run at a time
    private final LoggingExecutorService userActionExecutor = createLoggingExecutorService(Executors.newSingleThreadExecutor());
    private final AtomicReference<Thread> userActionThread = new AtomicReference<>(); //_FIX_

    // Regex to identify test files. Looks for "test" or "tests" surrounded by separators or camelCase boundaries.
    private static final Pattern TEST_FILE_PATTERN = Pattern.compile( // Javadoc for TEST_FILE_PATTERN not needed here
                                                                      "(?i).*(?:[/\\\\.]|\\b|_|(?<=[a-z])(?=[A-Z]))tests?(?:[/\\\\.]|\\b|_|(?=[A-Z][a-z])|$).*"
    );

    public static final String DEFAULT_SESSION_NAME = "New Session";

    public static boolean isTestFile(ProjectFile file) {
        return TEST_FILE_PATTERN.matcher(file.toString()).matches();
    }

    private LoggingExecutorService createLoggingExecutorService(ExecutorService toWrap) {
        return createLoggingExecutorService(toWrap, Set.of());
    }

    private LoggingExecutorService createLoggingExecutorService(ExecutorService toWrap, Set<Class<? extends Throwable>> ignoredExceptions) {
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
            io.systemOutput("Uncaught exception in thread %s. This shouldn't happen, please report a bug!\n%s"
                                    .formatted(thread.getName(), getStackTraceAsString(th)));
        });
    }

    // Context modification tasks (Edit/Read/Summarize/Drop/etc)
    // Multiple of these can run concurrently
    private final LoggingExecutorService contextActionExecutor = createLoggingExecutorService(
            new ThreadPoolExecutor(4, 4, // Core and Max are same due to unbounded queue behavior
                                   60L, TimeUnit.SECONDS,
                                   new LinkedBlockingQueue<>(), // Unbounded queue
                                   Executors.defaultThreadFactory()));

    // Internal background tasks (unrelated to user actions)
    // Lots of threads allowed since AutoContext updates get dropped here
    // Use unbounded queue to prevent task rejection
    private final LoggingExecutorService backgroundTasks = createLoggingExecutorService(
            new ThreadPoolExecutor(max(8, Runtime.getRuntime().availableProcessors()), // Core and Max are same
                                   max(8, Runtime.getRuntime().availableProcessors()),
                                   60L, TimeUnit.SECONDS,
                                   new LinkedBlockingQueue<>(), // Unbounded queue to prevent rejection
                                   Executors.defaultThreadFactory()),
            Set.of(InterruptedException.class));

    private final ServiceWrapper service;
    @SuppressWarnings(" vaikka project on final, sen sisältö voi muuttua ") 
    private final AbstractProject project;
    private final ToolRegistry toolRegistry;

    // Current session tracking
    private UUID currentSessionId;

    // Context history for undo/redo functionality (stores frozen contexts)
    private ContextHistory contextHistory;
    // The current, mutable, live context that the user interacts with
    private volatile Context liveContext = Context.EMPTY; // Initialize to a non-null default
    private final List<ContextListener> contextListeners = new CopyOnWriteArrayList<>();
    private final List<FileSystemEventListener> fileSystemEventListeners = new CopyOnWriteArrayList<>();

    // balance-notification state
    private boolean lowBalanceNotified = false;
    private boolean freeTierNotified = false;

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

    public void addFileSystemEventListener(FileSystemEventListener listener) {
        fileSystemEventListeners.add(listener);
    }

    public void removeFileSystemEventListener(FileSystemEventListener listener) {
        fileSystemEventListeners.remove(listener);
    }

    /**
     * Minimal constructor called from Brokk
     */
    public ContextManager(AbstractProject project) {
        this.project = project;

        this.contextHistory = new ContextHistory(Context.EMPTY);
        this.service = new ServiceWrapper();
        this.service.reinit(project);

        // set up global tools
        this.toolRegistry = new ToolRegistry(this);
        this.toolRegistry.register(new SearchTools(this));
        this.toolRegistry.register(new WorkspaceTools(this));

        // grab the user action thread so we can interrupt it on Stop
        userActionExecutor.submit(() -> {
            userActionThread.set(Thread.currentThread());
        });

        // dummy ConsoleIO until Chrome is constructed; necessary because Chrome starts submitting background tasks
        // immediately during construction, which means our own reference to it will still be null
        this.io = new IConsoleIO() {
            @Override
            public void actionOutput(String msg) {
                logger.info(msg);
            }

            @Override
            public void toolError(String msg, String title) {
                logger.info(msg);
            }

            @Override
            public void llmOutput(String token, ChatMessageType type, boolean isNewMessage) {
                // pass
            }
        };

        var analyzerListener = new AnalyzerListener() {
            @Override
            public void onBlocked() {
                if (Thread.currentThread() == userActionThread.get()) {
                    io.systemNotify(AnalyzerWrapper.ANALYZER_BUSY_MESSAGE,
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
                    SwingUtilities.invokeLater(() -> {
                        io.systemNotify("Code Intelligence is empty. Probably this means your language is not yet supported. File-based tools will continue to work.", "Code Intelligence Warning", JOptionPane.WARNING_MESSAGE);
                    });
                } else {
                    io.systemOutput(msg);
                }
            }

            @Override
            public void onRepoChange() {
                project.getRepo().invalidateCaches();
                io.updateGitRepo();
            }

            @Override
            public void onTrackedFileChange() {
                // we don't need the full onRepoChange but we do need these parts
                project.getRepo().invalidateCaches();
                io.updateCommitPanel();

                // update Workspace
                var fr = liveContext.freezeAndCleanup();
                // we can't rely on pushContext's change detection because here we care about the contents and not the fragment identity
                if (!topContext().workspaceContentEquals(fr.frozenContext())) {
                    processExternalFileChanges(fr);
                    // analyzer refresh will call this too, but it will be delayed
                    io.updateWorkspace();
                }

                // ProjectTree
                for (var fsListener : fileSystemEventListeners) {
                    fsListener.onTrackedFilesChanged();
                }
            }

            @Override
            public void beforeEachBuild() {
                if (io instanceof Chrome chrome) {
                    chrome.getContextPanel().showAnalyzerRebuildSpinner();
                }
            }

            @Override
            public void afterEachBuild(boolean successful, boolean externalRebuildRequested) {
                if (io instanceof Chrome chrome) {
                    chrome.getContextPanel().hideAnalyzerRebuildSpinner();
                }
                if (successful) {
                    // possible for analyzer build to finish before context load does
                    var fr = liveContext.freezeAndCleanup();
                    // we can't rely on pushContext's change detection because here we care about the contents and not the fragment identity
                    if (!topContext().workspaceContentEquals(fr.frozenContext())) {
                        processExternalFileChanges(fr);
                    }
                    io.updateWorkspace();
                }
                if (externalRebuildRequested && io instanceof Chrome chrome) {
                    if (successful) {
                        chrome.notifyActionComplete("Analyzer rebuild completed");
                    } else {
                        chrome.notifyActionComplete("Analyzer rebuild failed");
                    }
                }
            }
        };
        this.analyzerWrapper = new AnalyzerWrapper(project, this::submitBackgroundTask, analyzerListener);

        this.currentSessionId = UUID.randomUUID(); // Initialize currentSessionId
    }

    /**
     * Initializes the current session by loading its history or creating a new one.
     * This is typically called for standard project openings.
     * This method is synchronous but intended to be called from a background task.
     */
    private void initializeCurrentSessionAndHistory(boolean forceNew) {
        // load last active session, if present
        var lastActiveSessionId = project.getLastActiveSession();
        var sessionManager = project.getSessionManager();
        var sessions = sessionManager.listSessions();
        UUID sessionIdToLoad;
        if (forceNew || lastActiveSessionId.isEmpty() || sessions.stream().noneMatch(s -> s.id().equals(lastActiveSessionId.get()))) {
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
            liveContext = new Context(this, buildWelcomeMessage());
            contextHistory = new ContextHistory(liveContext);
        } else {
            contextHistory = loadedCH;
            liveContext = Context.unfreeze(contextHistory.topContext());
        }

        // make it official
        updateActiveSession(currentSessionId);

        // Notify listeners and UI on EDT
        SwingUtilities.invokeLater(() -> {
            var tc = topContext();
            notifyContextListeners(tc);
            if (io instanceof Chrome) { // Check if UI is ready
                io.enableActionButtons();
            }
        });
    }

    /**
     * Called from Brokk to finish wiring up references to Chrome and Coder
     * <p>
     * Returns the future doing off-EDT context loading
     */
    public CompletableFuture<Void> createGui() {
        assert SwingUtilities.isEventDispatchThread();

        this.io = new Chrome(this);

        // Load saved context history or create a new one
        var contextTask = submitBackgroundTask("Loading saved context", () -> initializeCurrentSessionAndHistory(false));

        // Ensure style guide and build details are loaded/generated asynchronously
        ensureStyleGuide();
        ensureReviewGuide();
        ensureBuildDetailsAsync();
        cleanupOldHistoryAsync();

        checkBalanceAndNotify();

        return contextTask;
    }

    /**
     * Submits a background task to clean up old LLM session history directories.
     */
    private void cleanupOldHistoryAsync() {
        submitBackgroundTask("Cleaning up LLM history", this::cleanupOldHistory);
    }

    /**
     * Scans the LLM history directory (located within the master project's .brokk/sessions)
     * and deletes subdirectories (individual session zips) whose last modified time
     * is older than one week. This method runs synchronously but is intended to be
     * called from a background task.
     * Note: This currently cleans up based on Llm.getHistoryBaseDir which might point to a different location
     * than the new Project.sessionsDir. This should be unified. For now, using Llm.getHistoryBaseDir.
     * If Llm.getHistoryBaseDir needs project.getMasterRootPathForConfig(), it should be updated there.
     * Assuming Llm.getHistoryBaseDir correctly points to the shared LLM log location.
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
                        try {
                            var lastModifiedTime = Files.getLastModifiedTime(entry).toInstant();
                            if (lastModifiedTime.isBefore(cutoff)) {
                                logger.trace("Attempting to delete old history directory (modified {}): {}", lastModifiedTime, entry);
                                if (deleteDirectoryRecursively(entry)) {
                                    deletedCount.incrementAndGet();
                                } else {
                                    logger.error("Failed to fully delete old history directory: {}", entry);
                                }
                            }
                        } catch (IOException e) {
                            // Log error getting last modified time for a specific entry, but continue with others
                            logger.error("Error checking last modified time for history entry: {}", entry, e);
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

    /**
     * Recursively deletes a directory and its contents.
     * Logs errors encountered during deletion.
     *
     * @param path The directory path to delete.
     * @return true if the directory was successfully deleted (or didn't exist), false otherwise.
     */
    private boolean deleteDirectoryRecursively(Path path) {
        assert Files.exists(path);
        try (var stream = Files.walk(path)) {
            stream
                    .sorted(Comparator.reverseOrder()) // Ensure contents are deleted before directories
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            // Log the specific error but allow the walk to continue trying other files/dirs
                            logger.error("Failed to delete path {} during recursive cleanup of {}", p, path, e);
                        }
                    });
            // Final check after attempting deletion
            return !Files.exists(path);
        } catch (IOException e) {
            logger.error("Failed to walk or initiate deletion for directory: {}", path, e);
            return false;
        }
    }

    @Override
    public AbstractProject getProject() {
        return project;
    }

    @Override
    public ProjectFile toFile(String relName)
    {
        return new ProjectFile(project.getRoot(), relName);
    }

    @Override
    public AnalyzerWrapper getAnalyzerWrapper() {
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
     * Return the currently selected FROZEN context from history in the UI.
     * For operations, use topContext() to get the live context.
     */
    public @Nullable Context selectedContext() {
        return contextHistory.getSelectedContext();
    }

    /**
     * Returns the current live, dynamic Context. Besides being dynamic (they load their
     * text() content on demand, based on the current files and Analyzer),
     * live Fragments have a working sources() implementation.
     */
    @Override
    public Context liveContext() {
        return liveContext;
    }

    public Path getRoot()
    {
        return project.getRoot();
    }

    /**
     * Returns the Models instance associated with this context manager.
     */
    @Override
    public Service getService() {
        return service.get();
    }

    /**
     * Returns the configured Architect model, falling back to the system model if unavailable.
     */
    public StreamingChatModel getArchitectModel() {
        var config = project.getArchitectModelConfig();
        return getModelOrDefault(config, "Architect");
    }

    /**
     * Returns the configured Code model, falling back to the system model if unavailable.
     */
    public StreamingChatModel getCodeModel() {
        var config = project.getCodeModelConfig();
        return getModelOrDefault(config, "Code");
    }

    /**
     * Returns the configured Ask model, falling back to the system model if unavailable.
     */
    public StreamingChatModel getAskModel() {
        var config = project.getAskModelConfig();
        return getModelOrDefault(config, "Ask");
    }

    /**
     * Returns the configured Search model, falling back to the system model if unavailable.
     */
    public StreamingChatModel getSearchModel() {
        var config = project.getSearchModelConfig();
        return getModelOrDefault(config, "Search");
    }

    private StreamingChatModel getModelOrDefault(Service.ModelConfig config, String modelTypeName) {
        StreamingChatModel model = service.getModel(config.name(), config.reasoning());
        if (model != null) {
            return model;
        }

        // Configured model is not available. Attempt fallbacks.
        String chosenFallbackName = Service.GEMINI_2_5_PRO; // For the io.toolError message
        model = service.getModel(Service.GEMINI_2_5_PRO, Service.ReasoningLevel.DEFAULT);
        if (model != null) {
            io.systemOutput(String.format("Configured model '%s' for %s tasks is unavailable. Using fallback '%s'.",
                                          config.name(), modelTypeName, chosenFallbackName));
            return model;
        }

        chosenFallbackName = Service.GROK_3_MINI;
        model = service.getModel(Service.GROK_3_MINI, Service.ReasoningLevel.HIGH);
        if (model != null) {
            io.systemOutput(String.format("Configured model '%s' for %s tasks is unavailable. Using fallback '%s'.",
                                          config.name(), modelTypeName, chosenFallbackName));
            return model;
        }

        var quickModel = service.get().quickModel();
        String quickModelName = service.get().nameOf(quickModel);
        io.systemOutput(String.format("Configured model '%s' for %s tasks is unavailable. Preferred fallbacks also failed. Using system model '%s'.",
                                      config.name(), modelTypeName, quickModelName));
        return quickModel;
    }

    public CompletableFuture<Void> submitUserTask(String description, Runnable task) {
        return submitUserTask(description, false, task);
    }

    public CompletableFuture<Void> submitUserTask(String description, boolean isLlmTask, Runnable task) {
        return userActionExecutor.submit(() -> {
            userActionThread.set(Thread.currentThread());
            io.disableActionButtons();

            try {
                if (isLlmTask) {
                    io.blockLlmOutput(true);
                }
                task.run();
            } catch (CancellationException cex) {
                if (isLlmTask) {
                    io.llmOutput(description + " canceled", ChatMessageType.CUSTOM);
                } else {
                    io.systemOutput(description + " canceled");
                }
            } catch (Exception e) {
                logger.error("Error while executing {}", description, e);
                io.toolError("Error while executing " + description + ": " + e.getMessage());
            } finally {
                io.actionComplete();
                io.enableActionButtons();
                // Unblock LLM output if this was an LLM task
                if (isLlmTask) {
                    io.blockLlmOutput(false);
                }
            }
        });
    }

    public <T> Future<T> submitUserTask(String description, Callable<T> task) {
        return userActionExecutor.submit(() -> {
            userActionThread.set(Thread.currentThread());
            io.disableActionButtons();

            try {
                return task.call();
            } catch (CancellationException cex) {
                io.systemOutput(description + " canceled.");
                throw cex;
            } catch (Exception e) {
                logger.error("Error while executing {}", description, e);
                io.toolError("Error while executing " + description + ": " + e.getMessage());
                throw e;
            } finally {
                io.actionComplete();
                io.enableActionButtons();
            }
        });
    }

    public Future<?> submitContextTask(String description, Runnable task) {
        return contextActionExecutor.submit(() -> {
            try {
                task.run();
            } catch (CancellationException cex) {
                io.systemOutput(description + " canceled.");
            } catch (Exception e) {
                logger.error("Error while executing {}", description, e);
                io.toolError("Error while executing " + description + ": " + e.getMessage());
            }
        });
    }

    /**
     * Attempts to re‑interrupt the thread currently executing a user‑action
     * task.  Safe to call repeatedly.
     */
    public void interruptUserActionThread() {
        var runner = requireNonNull(userActionThread.get());
        if (runner.isAlive()) {
            logger.debug("Interrupting user action thread " + runner.getName());
            runner.interrupt();
        }
    }

    /**
     * Add the given files to editable.
     */
    @Override
    public void editFiles(Collection<ProjectFile> files)
    {
        var filesByType = files.stream()
                .collect(Collectors.partitioningBy(BrokkFile::isText));

        var textFiles = castNonNull(filesByType.get(true));
        var binaryFiles = castNonNull(filesByType.get(false));

        if (!textFiles.isEmpty()) {
            var proposedEditableFragments = textFiles.stream()
                    .map(pf -> new ContextFragment.ProjectPathFragment(pf, this))
                    .toList();
            this.editFiles(proposedEditableFragments);
        }

        if (!binaryFiles.isEmpty()) {
            addReadOnlyFiles(binaryFiles);
        }
    }

    private Context applyEditableFileChanges(Context currentLiveCtx, List<ContextFragment.ProjectPathFragment> fragmentsToAdd) {
        var filesToEditSet = fragmentsToAdd.stream()
                .map(ContextFragment.ProjectPathFragment::file)
                .collect(Collectors.toSet());

        var existingReadOnlyFragmentsToRemove = currentLiveCtx.readonlyFiles()
                .filter(pf -> pf instanceof PathFragment pathFrag && filesToEditSet.contains(pathFrag.file()))
                .toList();

        var currentEditableFileSet = currentLiveCtx.editableFiles()
                .filter(PathFragment.class::isInstance).map(PathFragment.class::cast)
                .map(PathFragment::file).collect(Collectors.toSet());
        var uniqueNewEditableFragments = fragmentsToAdd.stream()
                .filter(frag -> !currentEditableFileSet.contains(frag.file()))
                .toList();

        return currentLiveCtx.removeReadonlyFiles(existingReadOnlyFragmentsToRemove)
                .addEditableFiles(uniqueNewEditableFragments);
    }

    /**
     * Add the given files to editable.
     */
    public void editFiles(List<ContextFragment.ProjectPathFragment> fragments) {
        assert fragments.stream().allMatch(ContextFragment.PathFragment::isText) : "Only text files can be made editable";
        pushContext(currentLiveCtx -> applyEditableFileChanges(currentLiveCtx, fragments));
        io.systemOutput("Edited " + joinForOutput(fragments));
    }

    private Context applyReadOnlyPathFragmentChanges(Context currentLiveCtx, List<PathFragment> fragmentsToAdd) {
        var filesToMakeReadOnlySet = fragmentsToAdd.stream()
                .map(PathFragment::file)
                .collect(Collectors.toSet());

        var existingEditableFragmentsToRemove = currentLiveCtx.editableFiles()
                .filter(pf -> pf instanceof PathFragment pathFrag && filesToMakeReadOnlySet.contains(pathFrag.file()))
                .map(PathFragment.class::cast) // Ensure they are PathFragments to be removed
                .toList();

        var currentReadOnlyFileSet = currentLiveCtx.readonlyFiles()
                .filter(PathFragment.class::isInstance).map(PathFragment.class::cast)
                .map(PathFragment::file).collect(Collectors.toSet());
        var uniqueNewReadOnlyFragments = fragmentsToAdd.stream()
                .filter(frag -> !currentReadOnlyFileSet.contains(frag.file()))
                .toList();

        return currentLiveCtx.removeEditableFiles(existingEditableFragmentsToRemove)
                .addReadonlyFiles(uniqueNewReadOnlyFragments);
    }

    /**
     * Add read-only path fragments.
     */
    public void addReadOnlyFragments(List<PathFragment> fragments) {
        pushContext(currentLiveCtx -> applyReadOnlyPathFragmentChanges(currentLiveCtx, fragments));
        io.systemOutput("Read " + joinForOutput(fragments));
    }

    /**
     * Add read-only files.
     */
    public void addReadOnlyFiles(Collection<? extends BrokkFile> files)
    {
        var proposedReadOnlyFragments = files.stream()
                .map(bf -> ContextFragment.toPathFragment(bf, this))
                .toList();
        addReadOnlyFragments(proposedReadOnlyFragments);
        // io.systemOutput is handled by addReadOnlyFragments
    }

    /**
     * Drop all context.
     */
    public void dropAll()
    {
        pushContext(Context::removeAll);
    }

    /**
     * Drop fragments by their IDs.
     */
    public void drop(Collection<? extends ContextFragment> fragments) {
        var ids = fragments.stream().map(f -> mapToLiveFragment(f).id()).toList();
        pushContext(currentLiveCtx -> currentLiveCtx.removeFragmentsByIds(ids));
        io.systemOutput("Dropped " + fragments.stream().map(ContextFragment::shortDescription).collect(Collectors.joining(", ")));
    }

    /**
     * Occasionally you will need to determine which live fragment a frozen fragment came from.
     * This does that by assuming that the live and frozen Contexts have their fragments in the same order.
     */
    private ContextFragment mapToLiveFragment(ContextFragment f) {
        if (!(f instanceof FrozenFragment)) {
            return f;
        }

        var ctx = topContext();
        int idx = ctx.getAllFragmentsInDisplayOrder().indexOf(f);
        assert idx >= 0 : "Fragment %s not found in top context %s".formatted(f, ctx.getAllFragmentsInDisplayOrder());
        return liveContext.getAllFragmentsInDisplayOrder().get(idx);
    }

    /**
     * Clear conversation history.
     */
    public void clearHistory()
    {
        pushContext(Context::clearHistory);
    }

    /**
     * request code-intel rebuild
     */
    @Override
    public void requestRebuild()
    {
        project.getRepo().invalidateCaches();
        analyzerWrapper.requestRebuild();
    }

    /**
     * undo last context change
     */
    public Future<?> undoContextAsync() {
        return submitUserTask("Undo", () -> {
            if (undoContext()) {
                io.systemOutput("Undid most recent step");
            } else {
                io.systemOutput("Nothing to undo");
            }
        });
    }

    public boolean undoContext() {
        UndoResult result = contextHistory.undo(1, io);
        if (result.wasUndone()) {
            liveContext = Context.unfreeze(topContext());
            notifyContextListeners(topContext());
            project.getSessionManager().saveHistory(contextHistory, currentSessionId); // Save history of frozen contexts
            return true;
        }

        return false;
    }

    /**
     * undo changes until we reach the target FROZEN context
     */
    public Future<?> undoContextUntilAsync(Context targetFrozenContext) {
        return submitUserTask("Undoing", () -> {
            UndoResult result = contextHistory.undoUntil(targetFrozenContext, io);
            if (result.wasUndone()) {
                liveContext = Context.unfreeze(topContext());
                notifyContextListeners(topContext());
                project.getSessionManager().saveHistory(contextHistory, currentSessionId);
                io.systemOutput("Undid " + result.steps() + " step" + (result.steps() > 1 ? "s" : "") + "!");
            } else {
                io.systemOutput("Context not found or already at that point");
            }
        });
    }

    /**
     * redo last undone context
     */
    public Future<?> redoContextAsync() {
        return submitUserTask("Redoing", () -> {
            boolean wasRedone = contextHistory.redo(io);
            if (wasRedone) {
                liveContext = Context.unfreeze(topContext());
                notifyContextListeners(topContext());
                project.getSessionManager().saveHistory(contextHistory, currentSessionId);
                io.systemOutput("Redo!");
            } else {
                io.systemOutput("no redo state available");
            }
        });
    }

    /**
     * Reset the live context to match the files and fragments from a historical (frozen) context.
     * A new state representing this reset is pushed to history.
     */
    public Future<?> resetContextToAsync(Context targetFrozenContext) {
        return submitUserTask("Resetting context", () -> {
            try {
                var newLive = Context.createFrom(targetFrozenContext, liveContext, liveContext.getTaskHistory());
                var fr = newLive.freezeAndCleanup();
                liveContext = fr.liveContext();
                var frozen = fr.frozenContext();
                contextHistory.addFrozenContextAndClearRedo(frozen);
                contextHistory.addResetEdge(targetFrozenContext, frozen);
                SwingUtilities.invokeLater(() -> notifyContextListeners(frozen));
                project.getSessionManager().saveHistory(contextHistory, currentSessionId);
                io.systemOutput("Reset workspace to historical state");
            } catch (CancellationException cex) {
                io.systemOutput("Reset workspace canceled.");
            }
        });
    }

    /**
     * Reset the live context and its history to match a historical (frozen) context.
     * A new state representing this reset is pushed to history.
     */
    public Future<?> resetContextToIncludingHistoryAsync(Context targetFrozenContext) {
        return submitUserTask("Resetting context and history", () -> {
            try {
                var newLive = Context.createFrom(targetFrozenContext, liveContext, targetFrozenContext.getTaskHistory());
                var fr = newLive.freezeAndCleanup();
                liveContext = fr.liveContext();
                var frozen = fr.frozenContext();
                contextHistory.addFrozenContextAndClearRedo(frozen);
                contextHistory.addResetEdge(targetFrozenContext, frozen);
                SwingUtilities.invokeLater(() -> notifyContextListeners(frozen));
                project.getSessionManager().saveHistory(contextHistory, currentSessionId);
                io.systemOutput("Reset workspace and history to historical state");
            } catch (CancellationException cex) {
                io.systemOutput("Reset workspace and history canceled.");
            }
        });
    }

    /**
     * Appends selected fragments from a historical (frozen) context to the current live context.
     * If a {@link ContextFragment.HistoryFragment} is among {@code fragmentsToKeep}, its task entries are also
     * appended to the current live context's history. A new state representing this action is pushed to the context history.
     *
     * @param sourceFrozenContext The historical context to source fragments and history from.
     * @param fragmentsToKeep     A list of fragments from {@code sourceFrozenContext} to append. These are matched by ID.
     * @return A Future representing the completion of the task.
     */
    public Future<?> addFilteredToContextAsync(Context sourceFrozenContext, List<ContextFragment> fragmentsToKeep) {
        return submitUserTask("Copying workspace items from historical state", () -> {
            try {
                String actionMessage = "Copied workspace items from historical state";

                // Calculate new history
                List<TaskEntry> finalHistory = new ArrayList<>(liveContext().getTaskHistory());
                Set<TaskEntry> existingEntries = new HashSet<>(finalHistory);

                Optional<ContextFragment.HistoryFragment> selectedHistoryFragmentOpt = fragmentsToKeep.stream()
                        .filter(ContextFragment.HistoryFragment.class::isInstance)
                        .map(ContextFragment.HistoryFragment.class::cast)
                        .findFirst();

                if (selectedHistoryFragmentOpt.isPresent()) {
                    List<TaskEntry> entriesToAppend = selectedHistoryFragmentOpt.get().entries();
                    for (TaskEntry entry : entriesToAppend) {
                        if (existingEntries.add(entry)) {
                            finalHistory.add(entry);
                        }
                    }
                    finalHistory.sort(Comparator.comparingInt(TaskEntry::sequence));
                }
                List<TaskEntry> newHistory = List.copyOf(finalHistory);

                // Categorize fragments to add after unfreezing
                List<ContextFragment.ProjectPathFragment> editablePathsToAdd = new ArrayList<>();
                List<PathFragment> readonlyPathsToAdd = new ArrayList<>();
                List<VirtualFragment> virtualFragmentsToAdd = new ArrayList<>();

                Set<String> sourceEditableIds = sourceFrozenContext.editableFiles().map(ContextFragment::id).collect(Collectors.toSet());
                Set<String> sourceReadonlyIds = sourceFrozenContext.readonlyFiles().map(ContextFragment::id).collect(Collectors.toSet());
                Set<String> sourceVirtualIds = sourceFrozenContext.virtualFragments().map(ContextFragment::id).collect(Collectors.toSet());

                for (ContextFragment fragmentFromKeeperList : fragmentsToKeep) {
                    ContextFragment unfrozen = Context.unfreezeFragmentIfNeeded(fragmentFromKeeperList, this);

                    if (sourceEditableIds.contains(fragmentFromKeeperList.id()) && unfrozen instanceof ContextFragment.ProjectPathFragment ppf) {
                        editablePathsToAdd.add(ppf);
                    } else if (sourceReadonlyIds.contains(fragmentFromKeeperList.id()) && unfrozen instanceof PathFragment pf) {
                        readonlyPathsToAdd.add(pf);
                    } else if (sourceVirtualIds.contains(fragmentFromKeeperList.id()) && unfrozen instanceof VirtualFragment vf) {
                        if (!(vf instanceof ContextFragment.HistoryFragment)) {
                            virtualFragmentsToAdd.add(vf);
                        }
                    } else if (unfrozen instanceof ContextFragment.HistoryFragment) {
                        // Handled by selectedHistoryFragmentOpt
                    } else {
                        logger.warn("Fragment '{}' (ID: {}) from fragmentsToKeep could not be categorized. Original type: {}, Unfrozen type: {}",
                                    fragmentFromKeeperList.description(), fragmentFromKeeperList.id(),
                                    fragmentFromKeeperList.getClass().getSimpleName(), unfrozen.getClass().getSimpleName());
                    }
                }

                pushContext(currentLiveCtx -> {
                    Context modifiedCtx = currentLiveCtx;
                    if (!readonlyPathsToAdd.isEmpty()) {
                        modifiedCtx = applyReadOnlyPathFragmentChanges(modifiedCtx, readonlyPathsToAdd);
                    }
                    if (!editablePathsToAdd.isEmpty()) {
                        modifiedCtx = applyEditableFileChanges(modifiedCtx, editablePathsToAdd);
                    }
                    for (VirtualFragment vfToAdd : virtualFragmentsToAdd) {
                        modifiedCtx = modifiedCtx.addVirtualFragment(vfToAdd);
                    }
                    return new Context(this,
                                       modifiedCtx.editableFiles().toList(),
                                       modifiedCtx.readonlyFiles().toList(),
                                       modifiedCtx.virtualFragments().toList(),
                                       newHistory,
                                       null,
                                       CompletableFuture.completedFuture(actionMessage));
                });

                io.systemOutput(actionMessage);
            } catch (CancellationException cex) {
                io.systemOutput("Copying context items from historical state canceled.");
            }
        });
    }

    /**
     * Adds any virtual fragment directly to the live context.
     */
    public void addVirtualFragment(VirtualFragment fragment) {
        pushContext(currentLiveCtx -> currentLiveCtx.addVirtualFragment(fragment));
    }

    /**
     * Handles pasting an image from the clipboard.
     * Submits a task to summarize the image and adds a PasteImageFragment to the context.
     *
     * @param image The java.awt.Image pasted from the clipboard.
     */
    public ContextFragment.AnonymousImageFragment addPastedImageFragment(java.awt.Image image, @Nullable String descriptionOverride) {
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
     * Handles pasting an image from the clipboard without a predefined description.
     * This will trigger asynchronous summarization of the image.
     *
     * @param image The java.awt.Image pasted from the clipboard.
     */
    public void addPastedImageFragment(java.awt.Image image) {
        addPastedImageFragment(image, null);
    }

    /**
     * Adds a specific PathFragment (like GitHistoryFragment) to the read-only part of the live context.
     *
     * @param fragment The PathFragment to add.
     */
    public void addReadOnlyFragmentAsync(PathFragment fragment) {
        submitContextTask("Capture file revision", () -> {
            pushContext(currentLiveCtx -> currentLiveCtx.addReadonlyFiles(List.of(fragment)));
        });
    }

    /**
     * Captures text from the LLM output area and adds it to the context.
     * Called from Chrome's capture button.
     */
    public void captureTextFromContextAsync() {
        submitContextTask("Capture output", () -> {
            // Capture from the selected *frozen* context in history view
            var selectedFrozenCtx = requireNonNull(selectedContext()); // This is from history, frozen
            if (selectedFrozenCtx.getParsedOutput() != null) {
                // Add the captured (TaskFragment, which is Virtual) to the *live* context
                addVirtualFragment(selectedFrozenCtx.getParsedOutput());
                io.systemOutput("Content captured from output");
            } else {
                io.systemOutput("No content to capture");
            }
        });
    }

    /**
     * usage for identifier
     */
    public void usageForIdentifier(String identifier) {
        var fragment = new ContextFragment.UsageFragment(this, identifier);
        pushContext(currentLiveCtx -> currentLiveCtx.addVirtualFragment(fragment));
        io.systemOutput("Added uses of " + identifier);
    }

    public void addCallersForMethod(String methodName, int depth, Map<String, List<CallSite>> callgraph) {
        if (callgraph.isEmpty()) {
            io.systemOutput("No callers found for " + methodName + " (pre-check).");
            return;
        }
        var fragment = new ContextFragment.CallGraphFragment(this, methodName, depth, false);
        pushContext(currentLiveCtx -> currentLiveCtx.addVirtualFragment(fragment));
        io.systemOutput("Added call graph for callers of " + methodName + " with depth " + depth);
    }

    /**
     * callees for method
     */
    public void calleesForMethod(String methodName, int depth, Map<String, List<CallSite>> callgraph) {
        if (callgraph.isEmpty()) {
            io.systemOutput("No callees found for " + methodName + " (pre-check).");
            return;
        }
        var fragment = new ContextFragment.CallGraphFragment(this, methodName, depth, true);
        pushContext(currentLiveCtx -> currentLiveCtx.addVirtualFragment(fragment));
        io.systemOutput("Added call graph for methods called by " + methodName + " with depth " + depth);
    }

    /**
     * parse stacktrace
     */
    public boolean addStacktraceFragment(StackTrace stacktrace) {
        var exception = requireNonNull(stacktrace.getExceptionType());
        var sources = new HashSet<CodeUnit>();
        var content = new StringBuilder();
        IAnalyzer localAnalyzer = getAnalyzerUninterrupted();

        for (var element : stacktrace.getFrames()) {
            var methodFullName = element.getClassName() + "." + element.getMethodName();
            var methodSource = localAnalyzer.getMethodSource(methodFullName);
            if (methodSource.isPresent()) {
                String className = ContextFragment.toClassname(methodFullName);
                localAnalyzer.getDefinition(className)
                        .filter(CodeUnit::isClass)
                        .ifPresent(sources::add);
                content.append(methodFullName).append(":\n");
                content.append(methodSource.get()).append("\n\n");
            }
        }

        if (content.isEmpty()) {
            logger.debug("No relevant methods found in stacktrace -- adding as text");
            return false;
        }
        var fragment = new ContextFragment.StacktraceFragment(this, sources, stacktrace.getOriginalText(), exception, content.toString());
        pushContext(currentLiveCtx -> currentLiveCtx.addVirtualFragment(fragment));
        return true;
    }

    /**
     * Summarize files and classes, adding skeleton fragments to the context.
     *
     * @param files   A set of ProjectFiles to summarize (extracts all classes within them).
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
            List<String> filePaths = files.stream()
                    .map(ProjectFile::toString)
                    .collect(Collectors.toList());
            var fileSummaryFragment = new ContextFragment.SkeletonFragment(this, filePaths, ContextFragment.SummaryType.FILE_SKELETONS); // Pass IContextManager
            addVirtualFragment(fileSummaryFragment);
            io.systemOutput("Summarized " + joinFilesForOutput(files));
            summariesAdded = true;
        }

        if (!classes.isEmpty()) {
            List<String> classFqns = classes.stream()
                    .map(CodeUnit::fqName)
                    .collect(Collectors.toList());
            var classSummaryFragment = new ContextFragment.SkeletonFragment(this, classFqns, ContextFragment.SummaryType.CLASS_SKELETON); // Pass IContextManager
            addVirtualFragment(classSummaryFragment);
            io.systemOutput("Summarized " + String.join(", ", classFqns));
            summariesAdded = true;
        }
        if (!summariesAdded) {
            io.toolError("No files or classes provided to summarize.");
            return false;
        }
        return true;
    }

    private String joinForOutput(Collection<? extends ContextFragment> fragments) {
        return joinFilesForOutput(fragments.stream().flatMap(f -> f.files().stream()).collect(Collectors.toSet()));
    }

    private static String joinFilesForOutput(Collection<? extends BrokkFile> files) {
        var toJoin = files.stream()
                .map(BrokkFile::getFileName)
                .sorted()
                .toList();
        if (files.size() <= 3) {
            return String.join(", ", toJoin);
        }
        return String.join(", ", toJoin.subList(0, 3)) + " ...";
    }

    /**
     * @return A list containing two messages: a UserMessage with the string representation of the task history,
     * and an AiMessage acknowledging it. Returns an empty list if there is no history.
     */
    @Override
    public List<ChatMessage> getHistoryMessages() {
        return CodePrompts.instance.getHistoryMessages(topContext());
    }

    public List<ChatMessage> getHistoryMessagesForCopy() {
        var taskHistory = topContext().getTaskHistory();

        var messages = new ArrayList<ChatMessage>();
        var allTaskEntries = taskHistory.stream()
                .map(TaskEntry::toString)
                .collect(Collectors.joining("\n\n"));


        if (!allTaskEntries.isEmpty()) {
            messages.add(new UserMessage("<taskhistory>%s</taskhistory>".formatted(allTaskEntries)));
        }
        return messages;
    }

    /**
     * Build a welcome message with environment information.
     * Uses statically available model info.
     */
    private String buildWelcomeMessage() {
        String welcomeMarkdown;
        var mdPath = "/WELCOME.md";
        try (var welcomeStream = Brokk.class.getResourceAsStream(mdPath)) {
            if (welcomeStream != null) {
                welcomeMarkdown = new String(welcomeStream.readAllBytes(), StandardCharsets.UTF_8);
            } else {
                logger.warn("WELCOME.md resource not found.");
                welcomeMarkdown = "Welcome to Brokk!";
            }
        } catch (IOException e1) {
            throw new UncheckedIOException(e1);
        }

        var version = BuildInfo.version;

        return """
               %s
               
               ## Environment
               - Brokk version: %s
               - Project: %s (%d native files, %d total including dependencies)
               - Analyzer language: %s
               """.stripIndent().formatted(welcomeMarkdown,
                                           version,
                                           project.getRoot().getFileName(), // Show just the folder name
                                           project.getRepo().getTrackedFiles().size(),
                                           project.getAllFiles().size(),
                                           project.getAnalyzerLanguages());
    }

    /**
     * Shutdown all executors
     */
    @Override
    public void close() {
        userActionExecutor.shutdown();
        contextActionExecutor.shutdown();
        backgroundTasks.shutdown();
        project.close();
        analyzerWrapper.close();
    }

    /**
     * @return a summary of each fragment in the workspace; for most fragment types this is just the description,
     * but for some (SearchFragment) it's the full text and for others (files, skeletons) it's the class summaries.
     */
    private String readOnlySummaryDescription(ContextFragment cf) {
        if (cf.getType().isPathFragment()) {
            return cf.files().stream().findFirst().map(BrokkFile::toString).orElseGet(() -> {
                logger.warn("PathFragment type {} with no files: {}", cf.getType(), cf.description());
                return "Error: PathFragment with no file";
            });
        }
        // If not a PathFragment, it's a VirtualFragment
        return "\"%s\"".formatted(cf.description());
    }

    private String editableSummaryDescription(ContextFragment cf) {
        if (cf.getType().isPathFragment()) {
            // This PathFragment is editable.
            return cf.files().stream().findFirst().map(BrokkFile::toString).orElseGet(() -> {
                logger.warn("Editable PathFragment type {} with no files: {}", cf.getType(), cf.description());
                return "Error: Editable PathFragment with no file";
            });
        }

        // Handle UsageFragment specially.
        if (cf.getType() == ContextFragment.FragmentType.USAGE) {
            var files = cf.files().stream().map(ProjectFile::toString).sorted().collect(Collectors.joining(", "));
            return "[%s] (%s)".formatted(files, cf.description());
        }

        // Default for other editable VirtualFragments
        return "\"%s\"".formatted(cf.description());
    }

    @Override
    public String getReadOnlySummary() {
        return topContext().getReadOnlyFragments()
                .map(this::readOnlySummaryDescription)
                .filter(st -> !st.isBlank())
                .collect(Collectors.joining(", "));
    }

    @Override
    public String getEditableSummary() {
        return topContext().getEditableFragments()
                .map(this::editableSummaryDescription)
                .collect(Collectors.joining(", "));
    }

    @Override
    public Set<ProjectFile> getEditableFiles() {
        return topContext().editableFiles()
                .filter(ContextFragment.ProjectPathFragment.class::isInstance)
                .map(ContextFragment.ProjectPathFragment.class::cast)
                .map(ContextFragment.ProjectPathFragment::file)
                .collect(Collectors.toSet());
    }

    @Override
    public Set<BrokkFile> getReadonlyProjectFiles() {
        return topContext().readonlyFiles()
                .filter(pf -> pf instanceof ContextFragment.ProjectPathFragment)
                .map(pf -> ((ContextFragment.ProjectPathFragment) pf).file())
                .collect(Collectors.toSet());
    }

    /**
     * Processes external file changes by deciding whether to replace the top context or push a new one.
     * If the current top context's action starts with "Loaded external changes", it updates the count and replaces it.
     * Otherwise, it pushes a new context entry.
     *
     * @param fr The FreezeResult containing the updated live and frozen contexts reflecting the external changes.
     */
    private void processExternalFileChanges(Context.FreezeResult fr) {
        var topCtx = topContext();
        var previousAction = topCtx.getAction();
        if (!previousAction.startsWith("Loaded external changes")) {
            // If the previous action is not about external changes, push a new context
            pushContext(currentLiveCtx -> fr.liveContext().withParsedOutput(null, CompletableFuture.completedFuture("Loaded external changes")));
            return;
        }

        // Parse the existing action to extract the count if present
        var pattern = Pattern.compile("Loaded external changes(?: \\((\\d+)\\))?");
        var matcher = pattern.matcher(previousAction);
        int newCount;
        if (matcher.matches() && matcher.group(1) != null) {
            var countGroup = matcher.group(1);
            try {
                newCount = Integer.parseInt(countGroup) + 1;
            } catch (NumberFormatException e) {
                newCount = 2;
            }
        } else {
            newCount = 2;
        }

        // Form the new action string with the updated count
        var newAction = newCount > 1 ? "Loaded external changes (%d)".formatted(newCount) : "Loaded external changes";
        var newLiveContext = fr.liveContext().withParsedOutput(null, CompletableFuture.completedFuture(newAction));
        var cleaned = newLiveContext.freezeAndCleanup();
        liveContext = cleaned.liveContext();
        contextHistory.replaceTopContext(cleaned.frozenContext());
        SwingUtilities.invokeLater(() -> notifyContextListeners(cleaned.frozenContext()));
        project.getSessionManager().saveHistory(contextHistory, currentSessionId);
    }

    /**
     * Pushes context changes using a generator function.
     * The generator is applied to the current `liveContext`.
     * The resulting context becomes the new `liveContext`.
     * A frozen snapshot of this new `liveContext` is added to `ContextHistory`.
     *
     * @param contextGenerator A function that takes the current live context and returns an updated context.
     * @return The new `liveContext`, or the existing `liveContext` if no changes were made by the generator.
     */
    public Context pushContext(Function<Context, Context> contextGenerator) {
        var updatedLiveContext = contextGenerator.apply(liveContext);
        assert !updatedLiveContext.containsFrozenFragments() : updatedLiveContext;
        if (liveContext.equals(updatedLiveContext)) {
            // No change occurred
            return liveContext;
        }

        liveContext = updatedLiveContext; // Update to the new live state

        var fr = liveContext.freezeAndCleanup();
        liveContext = fr.liveContext();
        var frozen = fr.frozenContext();
        contextHistory.addFrozenContextAndClearRedo(frozen); // Add frozen version to history

        // Ensure listeners are notified on the EDT
        SwingUtilities.invokeLater(() -> notifyContextListeners(frozen));

        project.getSessionManager().saveHistory(contextHistory, currentSessionId);    // Persist the history of frozen contexts

        // Check conversation history length on the new live context
        if (!liveContext.getTaskHistory().isEmpty()) {
            var cf = new ContextFragment.HistoryFragment(this, liveContext.getTaskHistory());
            int tokenCount = Messages.getApproximateTokens(cf.format());
            if (tokenCount > 32 * 1024) {
                SwingUtilities.invokeLater(() -> {
                    int choice = io.showConfirmDialog("""
                                                      The conversation history is getting long (%,d lines or about %,d tokens).
                                                      Compressing it can improve performance and reduce cost.
                                                      
                                                      Compress history now?
                                                      """.formatted(cf.format().split("\n").length, tokenCount),
                                                      "Compress History?",
                                                      JOptionPane.YES_NO_OPTION,
                                                      JOptionPane.QUESTION_MESSAGE);

                    if (choice == JOptionPane.YES_OPTION) {
                        compressHistoryAsync();
                    }
                });
            }
        }
        return liveContext;
    }

    /**
     * Updates the selected FROZEN context in history from the UI.
     * Called by Chrome when the user selects a row in the history table.
     *
     * @param frozenContextFromHistory The FROZEN context selected in the UI.
     */
    public void setSelectedContext(Context frozenContextFromHistory) {
        contextHistory.setSelectedContext(frozenContextFromHistory);
    }

    /**
     * should only be called with Frozen contexts, so that calling its methods doesn't cause an expensive Analyzer operation on the EDT
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

    /**
     * A record to hold the result of analyzing pasted text.
     */
    public record PasteAnalysis(String summary, String syntaxStyle) {}

    /**
     * Analyzes pasted text content to determine its syntax style and generate a summary.
     * It uses an LLM with a tool-calling mechanism and will retry if the LLM provides an invalid syntax style.
     *
     * @param pastedContent The text content that was pasted.
     * @return A CompletableFuture that will resolve to a PasteAnalysis record.
     */
    public CompletableFuture<PasteAnalysis> analyzePastedText(String pastedContent) {
        return CompletableFuture.supplyAsync(() -> {
            // Get all public static final String fields from SyntaxConstants
            List<String> validSyntaxStyles = Arrays.stream(SyntaxConstants.class.getFields())
                    .filter(field -> Modifier.isPublic(field.getModifiers()) &&
                                     Modifier.isStatic(field.getModifiers()) &&
                                     Modifier.isFinal(field.getModifiers()) &&
                                     field.getType().equals(String.class))
                    .map(field -> {
                        try {
                            return (String) field.get(null);
                        } catch (IllegalAccessException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            var pdt = new PasteDescriptionTool();
            List<ToolSpecification> toolSpecs = ToolSpecifications.toolSpecificationsFrom(pdt);

            var modelConfig = project.getCodeModelConfig();
            var service = getService();
            StreamingChatLanguageModel model = service.getModel(modelConfig.name(), modelConfig.reasoning());
            if (model == null) {
                logger.error("Could not get model for paste analysis. Falling back to defaults.");
                return new PasteAnalysis("Pasted text (model unavailable)", "text/plain");
            }
            var llm = getLlm(model, "Analyze pasted text");

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from("""
                    You are an expert at analyzing text snippets. Your task is to determine the syntax type of the provided text and write a concise, one-line summary.
                    You MUST choose a syntax style from the provided list.
                    Finally, you MUST call the 'describePasteContents' tool with your analysis.
                    """));
            messages.add(UserMessage.from(String.format("""
                    Analyze the following text and select the best syntax style from this list:
                    %s
                    
                    --- TEXT TO ANALYZE ---
                    %s
                    """, String.join(", ", validSyntaxStyles), pastedContent)));

            for (int i = 0; i < 3; i++) { // Allow up to 3 attempts
                try {
                    Llm.StreamingResult result;
                    try {
                        // Use the Llm wrapper to send the request, which handles tool emulation correctly
                        result = llm.sendRequest(messages, toolSpecs, ToolChoice.AUTO, false);
                    } catch (InterruptedException e) {
                        throw new CancellationException(e.getMessage());
                    }

                    if (result.error() != null) {
                        logger.error("Error during LLM call for paste analysis", result.error());
                        break;
                    }

                    AiMessage response = result.aiMessage();
                    messages.add(response);

                    if (response.hasToolExecutionRequests()) {
                        ToolExecutionRequest request = response.toolExecutionRequests().get(0);
                        if ("describePasteContents".equals(request.name())) {
                            Map<String, Object> args;
                            try {
                                args = OBJECT_MAPPER.readValue(request.arguments(), new TypeReference<>() {});
                            } catch (JsonProcessingException e) {
                                logger.error("Failed to parse tool arguments", e);
                                messages.add(new ToolExecutionResultMessage(request.id(), request.name(), "Error: Invalid JSON in arguments. " + e.getMessage()));
                                continue;
                            }

                            String syntaxStyle = (String) args.get("syntaxStyle");
                            String summary = (String) args.get("summary");

                            if (summary == null || syntaxStyle == null) {
                                String errorMsg = "Tool call must include both 'summary' and 'syntaxStyle' arguments. Please try again.";
                                messages.add(new ToolExecutionResultMessage(request.id(), request.name(), errorMsg));
                                logger.warn("LLM tool call was missing arguments. Retrying.");
                                continue;
                            }

                            if (validSyntaxStyles.contains(syntaxStyle)) {
                                logger.debug("LLM successfully analyzed pasted text. Style: {}, Summary: {}", syntaxStyle, summary);
                                return new PasteAnalysis(summary, syntaxStyle);
                            } else {
                                String errorMsg = String.format("Invalid syntax style '%s' provided. You must choose one of the styles from the original list. Please try again.", syntaxStyle);
                                messages.add(new ToolExecutionResultMessage(request.id(), request.name(), errorMsg));
                                logger.warn("LLM provided invalid syntax style: {}. Retrying.", syntaxStyle);
                            }
                        }
                    } else {
                        messages.add(UserMessage.from("You did not call the 'describePasteContents' tool. Please analyze the text and call the tool with the syntax style and summary."));
                        logger.warn("LLM did not call the required tool. Retrying.");
                    }
                } catch (Throwable e) {
                     logger.error("Error during LLM call for paste analysis", e);
                     break; // Exit loop on error
                }
            }

            logger.error("Failed to get valid analysis from LLM after multiple retries. Falling back to defaults.");
            return new PasteAnalysis("Pasted text (analysis failed)", "text/plain");

        }, backgroundTasks);
    }

    @SuppressWarnings({"UnusedMethod", "UnusedVariable"})
    private static class PasteDescriptionTool {
        @Tool("Records the analysis of the pasted text, including its syntax style and a summary.")
        public void describePasteContents(
                @P("A concise, one-line summary of the text content.") String summary,
                @P("The syntax style from the provided list (e.g., 'text/java').") String syntaxStyle
        ) {
            // This method is a schema definition for the LLM; it doesn't need to be called.
        }
    }

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
     * Submits a background task using SwingWorker to summarize a pasted image.
     * This uses the quickest model to generate a short description.
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
                    var textContent = TextContent.from("Briefly describe this image in a few words (e.g., 'screenshot of code', 'diagram of system').");
                    var userMessage = UserMessage.from(textContent, imageContent);
                    List<ChatMessage> messages = List.of(userMessage);
                    Llm.StreamingResult result;
                    try {
                        result = getLlm(service.quickModel(), "Summarize pasted image").sendRequest(messages);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    if (result.error() != null || result.originalResponse() == null) {
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

    /**
     * Submits a background task to the internal background executor (non-user actions).
     */
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
                        var lastTaskDescription = taskDescriptions.values().stream().findFirst().orElse("");
                        io.backgroundOutput(lastTaskDescription);
                    } else {
                        io.backgroundOutput("Tasks running: " + remaining, String.join("\n", taskDescriptions.values()));
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
     * @param task            the task to execute
     * @return a {@link Future} representing pending completion of the task
     */
    public CompletableFuture<Void> submitBackgroundTask(String taskDescription, Runnable task) {
        return submitBackgroundTask(taskDescription, () -> {
            task.run();
            return null;
        });
    }

    /**
     * Ensures build details are loaded or inferred using BuildAgent if necessary.
     * Runs asynchronously in the background.
     */
    private void ensureBuildDetailsAsync() {
        if (project.hasBuildDetails()) {
            logger.trace("Using existing build details");
            return;
        }

        // No details found, run the BuildAgent asynchronously
        submitBackgroundTask("Inferring build details", () -> {
            io.systemOutput("Inferring project build details");
            BuildAgent agent = new BuildAgent(project, getLlm(getSearchModel(), "Infer build details"), toolRegistry);
            BuildDetails inferredDetails;
            try {
                inferredDetails = agent.execute();
            } catch (Exception e) {
                var msg = "Build Information Agent did not complete successfully (aborted or errored). Build details not saved. Error: " + e.getMessage();
                logger.error(msg, e);
                io.toolError(msg, "Build Information Agent failed");
                inferredDetails = BuildDetails.EMPTY;
            }

            project.saveBuildDetails(inferredDetails);

            if (io instanceof Chrome chrome) {
                SwingUtilities.invokeLater(() -> {
                    var dlg = SettingsDialog.showSettingsDialog(chrome, "Build");
                    dlg.getProjectPanel().showBuildBanner();
                });
            }

            io.systemOutput("Build details inferred and saved");
            return inferredDetails;
        });
    }

    @Override
    public EditBlockParser getParserForWorkspace() {
        return CodePrompts.instance.getParser(topContext());
    }

    public void reloadModelsAsync() {
        service.reinit(project);
    }

    @FunctionalInterface
    public interface TaskRunner {
        /**
         * Submits a background task with the given description.
         *
         * @param taskDescription a description of the task
         * @param task            the task to execute
         * @param <T>             the result type of the task
         * @return a {@link Future} representing pending completion of the task
         */
        <T> Future<T> submit(String taskDescription, Callable<T> task);
    }

    // Removed BuildCommand record

    /**
     * Ensure style guide exists, generating if needed
     */
    private void ensureStyleGuide()
    {
        if (!project.getStyleGuide().isEmpty() || !analyzerWrapper.isCpg()) {
            return;
        }

        submitBackgroundTask("Generating style guide", () -> {
            try {
                io.systemOutput("Generating project style guide...");
                var analyzer = getAnalyzerUninterrupted();
                // Use a reasonable limit for style guide generation context
                var topClasses = AnalyzerUtil.combinedPagerankFor(analyzer, Map.of()).stream().limit(10).toList();

                if (topClasses.isEmpty()) {
                    io.systemOutput("No classes found via PageRank for style guide generation.");
                    project.saveStyleGuide("# Style Guide\n\n(Could not be generated automatically - no relevant classes found)\n");
                    return null;
                }

                var codeForLLM = new StringBuilder();
                var tokens = 0;
                int MAX_STYLE_TOKENS = 30000; // Limit context size for style guide
                for (var fqcnUnit : topClasses) {
                    var fileOption = analyzer.getFileFor(fqcnUnit.fqName()); // Use fqName() here
                    if (fileOption.isEmpty()) continue;
                    var file = fileOption.get();
                    String chunk; // Declare chunk once outside the try-catch
                    // Use project root for relative path display if possible
                    var relativePath = project.getRoot().relativize(file.absPath()).toString();
                    try {
                        chunk = "<file path=\"%s\">\n%s\n</file>\n".formatted(relativePath, file.read());
                        // Calculate tokens and check limits *inside* the try block, only if read succeeds
                        var chunkTokens = Messages.getApproximateTokens(chunk);
                        if (tokens > 0 && tokens + chunkTokens > MAX_STYLE_TOKENS) { // Check if adding exceeds limit
                            logger.debug("Style guide context limit ({}) reached after {} tokens.", MAX_STYLE_TOKENS, tokens);
                            break; // Exit the loop if limit reached
                        }
                        if (chunkTokens > MAX_STYLE_TOKENS) { // Skip single large files
                            logger.debug("Skipping large file {} ({} tokens) for style guide context.", relativePath, chunkTokens);
                            continue; // Skip to next file
                        }
                        // Append chunk if within limits
                        codeForLLM.append(chunk);
                        tokens += chunkTokens;
                        logger.trace("Added {} ({} tokens, total {}) to style guide context", relativePath, chunkTokens, tokens);
                    } catch (IOException e) {
                        logger.error("Failed to read {}: {}", relativePath, e.getMessage());
                        // Skip this file on error
                        // continue; // This continue is redundant
                    }
                }

                if (codeForLLM.isEmpty()) {
                    io.systemOutput("No relevant code found for style guide generation");
                    return null;
                }

                var messages = List.of(
                        new SystemMessage("You are an expert software engineer. Your task is to extract a concise coding style guide from the provided code examples."),
                        new UserMessage("""
                                        Based on these code examples, create a concise, clear coding style guide in Markdown format
                                        that captures the conventions used in this codebase, particularly the ones that leverage new or uncommon features.
                                        DO NOT repeat what are simply common best practices.
                                        
                                        %s
                                        """.stripIndent().formatted(codeForLLM))
                );

                var result = getLlm(getAskModel(), "Generate style guide").sendRequest(messages);
                if (result.error() != null || result.originalResponse() == null) {
                    io.systemOutput("Failed to generate style guide: " + (result.error() != null ? result.error().getMessage() : "LLM unavailable or cancelled"));
                    project.saveStyleGuide("# Style Guide\n\n(Generation failed)\n");
                    return null;
                }
                var styleGuide = result.text();
                if (styleGuide.isBlank()) {
                    io.systemOutput("LLM returned empty style guide.");
                    project.saveStyleGuide("# Style Guide\n\n(LLM returned empty result)\n");
                    return null;
                }
                project.saveStyleGuide(styleGuide);
                io.systemOutput("Style guide generated and saved to .brokk/style.md");
            } catch (Exception e) {
                logger.error("Error generating style guide", e);
            }
            return null;
        });
    }

    /**
     * Ensure review guide exists, generating if needed
     */
    private void ensureReviewGuide() {
        if (!project.getReviewGuide().isEmpty()) {
            return;
        }
        
        project.saveReviewGuide(MainProject.DEFAULT_REVIEW_GUIDE);
        io.systemOutput("Review guide created at .brokk/review.md");
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

        if (result.error() != null || result.originalResponse() == null) {
            logger.warn("History compression failed ({}) for entry: {}",
                        result.error() != null ? result.error().getMessage() : "LLM unavailable or cancelled",
                        entry);
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

    /**
     * Adds a completed CodeAgent session result to the context history.
     * This is the primary method for adding history after a CodeAgent run.
     * <p>
     * returns null if the session is empty, otherwise returns the new TaskEntry
     */
    public TaskEntry addToHistory(TaskResult result, boolean compress) {
        if (result.output().messages().isEmpty() && result.changedFiles().isEmpty()) {
            throw new IllegalStateException();
        }

        var action = result.actionDescription();
        logger.debug("Adding session result to history. Action: '{}', Changed files: {}, Reason: {}", action, result.changedFiles(), result.stopDetails());

        // Create TaskEntry based on the current liveContext
        TaskEntry newEntry = liveContext.createTaskEntry(result);
        var finalEntry = compress ? compressHistory(newEntry) : newEntry;
        Future<String> actionFuture = submitSummarizeTaskForConversation(action);

        // pushContext will apply addHistoryEntry to the current liveContext,
        // then liveContext will be updated, and a frozen version added to history.
        var newLiveContext = pushContext(currentLiveCtx -> currentLiveCtx.addHistoryEntry(finalEntry, result.output(), actionFuture));

        // Auto-rename session if session has default name
        var sessionManager = project.getSessionManager();
        var sessions = sessionManager.listSessions();
        var currentSession = sessions.stream()
                .filter(s -> s.id().equals(currentSessionId))
                .findFirst();

        if (currentSession.isPresent() && DEFAULT_SESSION_NAME.equals(currentSession.get().name())) {
            renameSessionAsync(currentSessionId, actionFuture).thenRun(() -> {
                SwingUtilities.invokeLater(() -> {
                    if (io instanceof Chrome chrome) {
                        chrome.getHistoryOutputPanel().updateSessionComboBox();
                    }
                });
            });
        }

        return castNonNull(newLiveContext.getTaskHistory().getLast());
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
     * Loads the ContextHistory for a specific session without switching to it.
     * This allows viewing/inspecting session history without changing the current session.
     *
     * @param sessionId The UUID of the session whose history to load
     * @return A CompletableFuture that resolves to the ContextHistory for the specified session
     */
    public CompletableFuture<ContextHistory> loadSessionHistoryAsync(UUID sessionId) {
        return CompletableFuture.supplyAsync(() -> project.getSessionManager().loadHistory(sessionId, this), backgroundTasks);
    }

    /**
     * Creates a new session with the given name and switches to it asynchronously.
     * First attempts to reuse an existing empty session before creating a new one.
     *
     * @param name The name for the new session
     * @return A CompletableFuture representing the completion of the session creation task
     */
    public CompletableFuture<Void> createSessionAsync(String name) {
        // No explicit exclusivity check for new session, as it gets a new unique ID.
        return submitUserTask("Creating new session: " + name, () -> {
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
            liveContext = new Context(this, "Welcome to the new session!");
            contextHistory.setInitialContext(liveContext.freezeAndCleanup().frozenContext());
            project.getSessionManager().saveHistory(contextHistory, currentSessionId); // Save the initial empty/welcome state

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
                        logger.warn("Error checking if session {} is empty, skipping: {}", session.id(), e.getMessage());
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
        var ctx = newContextFrom(sourceFrozenContext);
        // the intent is that we save a history to the new session that initializeCurrentSessionAndHistory will pull in later
        var ch = new ContextHistory(ctx);
        sessionManager.saveHistory(ch, newSessionInfo.id());
    }

    /**
     * Creates a new session with the given name, copies the workspace from the sourceFrozenContext,
     * and switches to it asynchronously.
     *
     * @param sourceFrozenContext The context whose workspace items will be copied.
     * @param newSessionName      The name for the new session.
     * @return A CompletableFuture representing the completion of the session creation task.
     */
    public CompletableFuture<Void> createSessionFromContextAsync(Context sourceFrozenContext, String newSessionName) {
        var future = submitUserTask("Creating new session '" + newSessionName + "' from workspace", () -> {
            logger.debug("Attempting to create and switch to new session '{}' from workspace of context '{}'",
                         newSessionName, sourceFrozenContext.getAction());

            var sessionManager = project.getSessionManager();
            // 1. Create new session info
            var newSessionInfo = sessionManager.newSession(newSessionName);
            updateActiveSession(newSessionInfo.id());
            logger.debug("Switched to new session: {} ({})", newSessionInfo.name(), newSessionInfo.id());

            // 2. Create the initial context for the new session.
            // Only its top-level action/parsedOutput will be changed to reflect it's a new session.
            var initialContextForNewSession = newContextFrom(sourceFrozenContext);

            // 3. Initialize the ContextManager's history for the new session with this single context.
            this.contextHistory.setInitialContext(initialContextForNewSession);
            contextHistory.addResetEdge(sourceFrozenContext, initialContextForNewSession);

            // 4. Update the ContextManager's liveContext by unfreezing this initial context.
            this.liveContext = Context.unfreeze(initialContextForNewSession);

            // 5. Save the new session's history (which now contains one entry).
            sessionManager.saveHistory(this.contextHistory, this.currentSessionId);

            // 6. Notify UI about the context change.
            notifyContextListeners(topContext());
        });
        return CompletableFuture.runAsync(() -> {
            try {
                future.get();
            } catch (Exception e) {
                logger.error("Failed to create new session from workspace", e);
                throw new RuntimeException("Failed to create new session from workspace", e);
            }
        });
    }

    /**
     * returns a frozen Context based on the source one
     */
    private Context newContextFrom(Context sourceFrozenContext) {
        var newActionDescription = "New session (from: " + sourceFrozenContext.getAction() + ")";
        var newActionFuture = CompletableFuture.completedFuture(newActionDescription);
        var newParsedOutputFragment = new ContextFragment.TaskFragment(this,
                                                                       List.of(SystemMessage.from(newActionDescription)),
                                                                       newActionDescription);
        return sourceFrozenContext.withParsedOutput(newParsedOutputFragment, newActionFuture);
    }

    /**
     * Switches to an existing session asynchronously.
     * Checks if the session is active elsewhere before switching.
     *
     * @param sessionId The UUID of the session to switch to
     * @return A CompletableFuture representing the completion of the session switch task
     */
    public CompletableFuture<Void> switchSessionAsync(UUID sessionId) {
        var sessionManager = project.getSessionManager();
        if (SessionRegistry.isSessionActiveElsewhere(project.getRoot(), sessionId)) {
            String sessionName = sessionManager.listSessions().stream()
                    .filter(s -> s.id().equals(sessionId))
                    .findFirst()
                    .map(SessionInfo::name).orElse("Unknown session");
            io.systemNotify("Session '" + sessionName + "' (" + sessionId.toString().substring(0, 8) + ")" +
                                    " is currently active in another Brokk window.\n" +
                                    "Please close it there or choose a different session.", "Session In Use", JOptionPane.WARNING_MESSAGE);
            return CompletableFuture.failedFuture(new IllegalStateException("Session is active elsewhere."));
        }

        var future = submitUserTask("Switching session", () -> {
            switchToSession(sessionId);
        });
        return CompletableFuture.runAsync(() -> {
            try {
                future.get();
            } catch (Exception e) {
                throw new RuntimeException("Failed to switch session", e);
            }
        });
    }

    private void switchToSession(UUID sessionId) {
        var sessionManager = project.getSessionManager();
        updateActiveSession(sessionId); // Mark as active

        String sessionName = sessionManager.listSessions().stream()
                .filter(s -> s.id().equals(sessionId))
                .findFirst()
                .map(SessionInfo::name)
                .orElse("(Unknown Name)");
        logger.debug("Switched to session: {} ({})", sessionName, sessionId);

        ContextHistory loadedCh = sessionManager.loadHistory(currentSessionId, this);

        if (loadedCh != null) {
            final ContextHistory nnLoadedCh = loadedCh; // Introduce nnLoadedCh for the non-null scope
            if (nnLoadedCh.getHistory().isEmpty()) {
                // Case: loadedCh exists but its history is empty
                liveContext = new Context(this, "Welcome to session: " + sessionName);
                contextHistory.setInitialContext(liveContext.freezeAndCleanup().frozenContext());
                sessionManager.saveHistory(contextHistory, currentSessionId);
            } else {
                // Case: loadedCh exists and has history
                contextHistory.setInitialContext(nnLoadedCh.getHistory().getFirst());
                for (int i = 1; i < nnLoadedCh.getHistory().size(); i++) {
                    contextHistory.addFrozenContextAndClearRedo(nnLoadedCh.getHistory().get(i));
                }
                liveContext = Context.unfreeze(topContext());
            }
        } else {
            // Case: loadedCh is null
            liveContext = new Context(this, "Welcome to session: " + sessionName);
            contextHistory.setInitialContext(liveContext.freezeAndCleanup().frozenContext());
            sessionManager.saveHistory(contextHistory, currentSessionId);
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
        var future = submitBackgroundTask("Renaming session", () -> {
            try {
                String newName = newNameFuture.get(Context.CONTEXT_ACTION_SUMMARY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                project.getSessionManager().renameSession(sessionId, newName);
                logger.debug("Renamed session {} to {}", sessionId, newName);
            } catch (Exception e) {
                logger.warn("Error renaming Session", e);
                throw new RuntimeException(e);
            }
        });
        return CompletableFuture.runAsync(() -> {
            try {
                future.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
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
        var future = submitUserTask("Deleting session " + sessionIdToDelete, () -> {
            project.getSessionManager().deleteSession(sessionIdToDelete);
            logger.info("Deleted session {}", sessionIdToDelete);
            if (sessionIdToDelete.equals(currentSessionId)) {
                createOrReuseSession(DEFAULT_SESSION_NAME);
            }
        });

        return CompletableFuture.runAsync(() -> {
            try {
                future.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Copies an existing session with a new name and switches to it asynchronously.
     *
     * @param originalSessionId   The UUID of the session to copy
     * @param originalSessionName The name of the session to copy
     * @return A CompletableFuture representing the completion of the session copy task
     */
    public CompletableFuture<Void> copySessionAsync(UUID originalSessionId, String originalSessionName) {
        var future = submitUserTask("Copying session " + originalSessionName, () -> {
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

            logger.info("Copied session {} ({}) to {} ({})", originalSessionName, originalSessionId, copiedSessionInfo.name(), copiedSessionInfo.id());
            var loadedCh = sessionManager.loadHistory(copiedSessionInfo.id(), this);
            assert loadedCh != null && !loadedCh.getHistory().isEmpty() : "Copied session history should not be null or empty";
            final ContextHistory nnLoadedCh = requireNonNull(loadedCh, "Copied session history (loadedCh) should not be null after assertion");
            contextHistory.setInitialContext(nnLoadedCh.getHistory().getFirst());
                    for (int i = 1; i < nnLoadedCh.getHistory().size(); i++) {
                        contextHistory.addFrozenContextAndClearRedo(nnLoadedCh.getHistory().get(i));
                    }
                    liveContext = Context.unfreeze(topContext());
            updateActiveSession(copiedSessionInfo.id());

            notifyContextListeners(topContext());
            io.updateContextHistoryTable(topContext());
        });
        return CompletableFuture.runAsync(() -> {
            try {
                future.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
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
     * Allows injection of a custom {@link IConsoleIO} implementation, enabling
     * head-less (CLI) operation where a GUI is not available.
     *
     * This should be invoked immediately after constructing the
     * {@code ContextManager} but before any tasks are submitted, so that all
     * logging and UI callbacks are routed to the desired sink.
     */
    public void createHeadless() {
        this.io = new HeadlessConsole();
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
     * Checks the user’s account balance (only when using the Brokk proxy) and notifies
     * via {@link IConsoleIO#systemNotify} if the balance is low or exhausted.
     * The expensive work is executed on the background executor so callers may invoke
     * this from any thread without blocking.
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
                    reloadModelsAsync();
                    if (!freeTierNotified) {
                        freeTierNotified = true;
                        lowBalanceNotified = false;  // reset low-balance flag
                        var msg = """
                                  Brokk is running in the free tier. Only low-cost models are available.

                                  To enable smarter models, subscribe or top-up at
                                  %s
                                  """.stripIndent().formatted(Service.TOP_UP_URL);
                        SwingUtilities.invokeLater(() ->
                                io.systemNotify(msg, "Balance Exhausted", JOptionPane.WARNING_MESSAGE));
                    }
                } else if (balance < Service.LOW_BALANCE_WARN_AT) {
                    // Low balance warning
                    freeTierNotified = false; // recovered from exhausted state
                    if (!lowBalanceNotified) {
                        lowBalanceNotified = true;
                        var msg = "Low account balance: $%.2f.\nTop-up at %s to avoid interruptions."
                                .formatted(balance, Service.TOP_UP_URL);
                        SwingUtilities.invokeLater(() ->
                                io.systemNotify(msg, "Low Balance Warning", JOptionPane.WARNING_MESSAGE));
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
     * Asynchronously compresses the entire conversation history of the currently selected context.
     * Replaces the history with summarized versions of each task entry.
     * This runs as a user action because it visibly modifies the context history.
     */
    public Future<?> compressHistoryAsync() {
        return submitUserTask("Compressing History", () -> {
            io.disableHistoryPanel();
            try {
                // Operate on the task history
                var taskHistoryToCompress = topContext().getTaskHistory();
                if (taskHistoryToCompress.isEmpty()) {
                    io.systemOutput("No history to compress.");
                    return;
                }

                io.systemOutput("Compressing conversation history...");

                List<TaskEntry> compressedTaskEntries = taskHistoryToCompress
                        .parallelStream()
                        .map(this::compressHistory)
                        .collect(Collectors.toCollection(() -> new ArrayList<>(taskHistoryToCompress.size())));

                boolean changed = IntStream.range(0, taskHistoryToCompress.size())
                        .anyMatch(i -> !taskHistoryToCompress.get(i).equals(compressedTaskEntries.get(i)));

                if (!changed) {
                    io.systemOutput("History is already compressed.");
                    return;
                }

                // pushContext will update liveContext with the compressed history
                // and add a frozen version to contextHistory.
                pushContext(currentLiveCtx -> currentLiveCtx.withCompressedHistory(List.copyOf(compressedTaskEntries)));
                io.systemOutput("Task history compressed successfully.");
            } finally {
                SwingUtilities.invokeLater(io::enableHistoryPanel);
            }
        });
    }

    public static class SummarizeWorker extends SwingWorker<String, String> {
        private final ContextManager cm;
        private final String content;
        private final int words;

        public SummarizeWorker(ContextManager cm, String content, int words) {
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
                result = cm.getLlm(cm.getService().quickestModel(), "Summarize: " + content).sendRequest(msgs);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (result.error() != null || result.originalResponse() == null) {
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
