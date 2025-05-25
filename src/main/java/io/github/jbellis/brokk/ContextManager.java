package io.github.jbellis.brokk;

import com.google.common.collect.Streams;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.ContextFragment.PathFragment;
import io.github.jbellis.brokk.ContextFragment.VirtualFragment;
import io.github.jbellis.brokk.ContextHistory.UndoResult;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.agents.BuildAgent.BuildDetails;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.prompts.SummarizerPrompts;
import io.github.jbellis.brokk.tools.SearchTools;
import io.github.jbellis.brokk.tools.ToolRegistry;
import io.github.jbellis.brokk.tools.WorkspaceTools;
import io.github.jbellis.brokk.util.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
    private final Logger logger = LogManager.getLogger(ContextManager.class);

    private IConsoleIO io; // for UI feedback - Initialized in createGui
    private AnalyzerWrapper analyzerWrapper;

    // Run main user-driven tasks in background (Code/Ask/Search/Run)
    // Only one of these can run at a time
    private final ExecutorService userActionExecutor = createLoggingExecutorService(Executors.newSingleThreadExecutor());
    private final AtomicReference<Thread> userActionThread = new AtomicReference<>();

    // Regex to identify test files. Looks for "test" or "tests" surrounded by separators or camelCase boundaries.
    private static final Pattern TEST_FILE_PATTERN = Pattern.compile(
            "(?i).*(?:[/\\\\.]|\\b|_|(?<=[a-z])(?=[A-Z]))tests?(?:[/\\\\.]|\\b|_|(?=[A-Z][a-z])|$).*"
    );
    
    public static boolean isTestFile(ProjectFile file) {
        return TEST_FILE_PATTERN.matcher(file.toString()).matches();
    }

    @NotNull
    private LoggingExecutorService createLoggingExecutorService(ExecutorService toWrap) {
        return createLoggingExecutorService(toWrap, Set.of());
    }

    @NotNull
    private LoggingExecutorService createLoggingExecutorService(ExecutorService toWrap, Set<Class<? extends Throwable>> ignoredExceptions) {
        return new LoggingExecutorService(toWrap, th -> {
            var thread = Thread.currentThread();
            if (ignoredExceptions.stream().anyMatch(cls -> cls.isInstance(th))) {
                logger.debug("Uncaught exception (ignorable) in executor", th);
                return;
            }

            logger.error("Uncaught exception in executor", th);
            if (io != null) {
                io.systemOutput("Uncaught exception in thread %s. This shouldn't happen, please report a bug!\n%s"
                                        .formatted(thread.getName(), getStackTraceAsString(th)));
            }
        });
    }

    // Context modification tasks (Edit/Read/Summarize/Drop/etc)
    // Multiple of these can run concurrently
    private final ExecutorService contextActionExecutor = createLoggingExecutorService(
            new ThreadPoolExecutor(2, 2,
                                   60L, TimeUnit.SECONDS,
                                   new LinkedBlockingQueue<>(), // Unbounded queue
                                   Executors.defaultThreadFactory()));

    // Internal background tasks (unrelated to user actions)
    // Lots of threads allowed since AutoContext updates get dropped here
    // Use unbounded queue to prevent task rejection
    private final ExecutorService backgroundTasks = createLoggingExecutorService(
            new ThreadPoolExecutor(3, 12,
                                   60L, TimeUnit.SECONDS,
                                   new LinkedBlockingQueue<>(), // Unbounded queue to prevent rejection
                                   Executors.defaultThreadFactory()),
            Set.of(InterruptedException.class));

    private final ModelsWrapper service;
    private final Project project;
    private final ToolRegistry toolRegistry;

    // Context history for undo/redo functionality
    private final ContextHistory contextHistory;
    private final List<ContextListener> contextListeners = new CopyOnWriteArrayList<>();

    public ExecutorService getBackgroundTasks() {
        return backgroundTasks;
    }

    @Override
    public void addContextListener(@NotNull ContextListener listener) {
        contextListeners.add(listener);
    }

    @Override
    public void removeContextListener(@NotNull ContextListener listener) {
        contextListeners.remove(listener);
    }

    /**
     * Minimal constructor called from Brokk
     */
    public ContextManager(Project project)
    {
        this.project = project;
        this.contextHistory = new ContextHistory();
        this.service = new ModelsWrapper();
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
            public void toolErrorRaw(String msg) {
                logger.info(msg);
            }

            @Override
            public void showMessageDialog(String message, String title, int messageType) {
                logger.info(message);
            }

            @Override
            public void llmOutput(String token, ChatMessageType type) {
                // pass
            }
        };
    }

    /**
     * Called from Brokk to finish wiring up references to Chrome and Coder
     */
    public Chrome createGui() {
        assert SwingUtilities.isEventDispatchThread();

        this.io = new Chrome(this);

        // Set up the listener for analyzer events
        var analyzerListener = new AnalyzerListener() {
            @Override
            public void onBlocked() {
                if (Thread.currentThread() == userActionThread.get()) {
                    io.actionOutput("Waiting for Code Intelligence");
                }
            }

            @Override
            public void afterFirstBuild(String msg) {
                if (msg.isEmpty()) {
                    SwingUtilities.invokeLater(() -> {
                        io.showMessageDialog(
                                "Code Intelligence is empty. Probably this means your language is not yet supported. File-based tools will continue to work.",
                                "Code Intelligence Warning",
                                JOptionPane.WARNING_MESSAGE
                        );
                    });
                } else {
                    io.systemOutput(msg);
                }
            }

            @Override
            public void onRepoChange() {
                project.getRepo().refresh();
                io.updateGitRepo();
            }

            @Override
            public void onTrackedFileChange() {
                io.updateCommitPanel();
            }
        };

        this.analyzerWrapper = new AnalyzerWrapper(project, this::submitBackgroundTask, analyzerListener);

        // Load saved context or create a new one
        submitBackgroundTask("Loading saved context", () -> {
            var welcomeMessage = buildWelcomeMessage(); // welcome message might change if git status changed
            var initialContext = project.loadContext(this, welcomeMessage);
            if (initialContext == null) {
                initialContext = new Context(this, welcomeMessage);
            }
            contextHistory.setInitialContext(initialContext);
            // If git was just initialized, Chrome components like GitPanel will be updated
            // by their own construction logic based on the new project.hasGit() state.
            // Explicit io.updateGitRepo() might not be needed here if Chrome rebuilds relevant parts.
            io.updateContextHistoryTable(initialContext); // Update UI with loaded/new context
        });

        // Ensure style guide and build details are loaded/generated asynchronously
        ensureStyleGuide();
        ensureBuildDetailsAsync(); // Changed from ensureBuildCommand
        cleanupOldHistoryAsync(); // Clean up old LLM history logs

        io.getInstructionsPanel().checkBalanceAndNotify();

        return (Chrome) this.io;
    }

    /**
     * Submits a background task to clean up old LLM session history directories.
     */
    private void cleanupOldHistoryAsync() {
        submitBackgroundTask("Cleaning up LLM history", this::cleanupOldHistory);
    }

    /**
     * Scans the LLM history directory and deletes subdirectories whose last modified time
     * is older than one week. This method runs synchronously but is intended to be
     * called from a background task.
     */
    private void cleanupOldHistory() {
        var historyBaseDir = Llm.getHistoryBaseDir(project.getRoot());
        if (!Files.isDirectory(historyBaseDir)) {
            logger.debug("LLM history directory {} does not exist, skipping cleanup.", historyBaseDir);
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
    public void replaceContext(Context context, Context replacement) {
        contextHistory.replaceContext(context, replacement);
        io.updateContextHistoryTable();
        io.updateContextTable();
    }

    public Project getProject() {
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
            throw new RuntimeException(e);
        }
    }

    /**
     * Return the top, active context in the history stack
     */
    @Override
    public Context topContext()
    {
        return contextHistory.topContext();
    }

    /**
     * Return the currently selected context in the UI. *Usually* you should call topContext() instead.
     */
    public Context selectedContext()
    {
        return contextHistory.getSelectedContext();
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
    public StreamingChatLanguageModel getArchitectModel() {
        var config = project.getArchitectModelConfig();
        return service.get(config.name(), config.reasoning());
    }

    /**
     * Returns the configured Code model, falling back to the system model if unavailable.
     */
    public StreamingChatLanguageModel getCodeModel() {
        var config = project.getCodeModelConfig();
        return service.get(config.name(), config.reasoning());
    }

    /**
     * Returns the configured Ask model, falling back to the system model if unavailable.
     */
    public StreamingChatLanguageModel getAskModel() {
        var config = project.getAskModelConfig();
        return service.get(config.name(), config.reasoning());
    }


    /**
     * Returns the configured Edit model, falling back to the system model if unavailable.
     */
    public StreamingChatLanguageModel getEditModel() {
        var config = project.getEditModelConfig();
        return service.get(config.name(), config.reasoning());
    }

    /**
     * Returns the configured Search model, falling back to the system model if unavailable.
     */
    public StreamingChatLanguageModel getSearchModel() {
        var config = project.getSearchModelConfig();
        return service.get(config.name(), config.reasoning());
    }

    public Future<?> submitUserTask(String description, Runnable task) {
        return submitUserTask(description, false, task);
    }

    public Future<?> submitUserTask(String description, boolean isLlmTask, Runnable task) {
        return userActionExecutor.submit(() -> {
            userActionThread.set(Thread.currentThread());
            io.disableActionButtons();

            try {
                if (isLlmTask) {
                    io.blockLlmOutput(true);
                }
                task.run();
            } catch (CancellationException cex) {
                io.systemOutput(description + " canceled.");
            } catch (Exception e) {
                io.toolErrorRaw("Error while " + description + ": " + e.getMessage());
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
                io.toolErrorRaw("Error while " + description + ": " + e.getMessage());
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
                io.toolErrorRaw("Error while " + description + ": " + e.getMessage());
            }
        });
    }

    /**
     * Attempts to re‑interrupt the thread currently executing a user‑action
     * task.  Safe to call repeatedly.
     */
    public void interruptUserActionThread() {
        var runner = userActionThread.get();
        if (runner != null && runner.isAlive()) {
            logger.debug("Interrupting user action thread " + runner.getName());
            runner.interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Asynchronous context actions: add/read/copy/edit/summarize/drop
    // ------------------------------------------------------------------
    // Core context manipulation logic called by ContextPanel / Chrome
    // ------------------------------------------------------------------

    /**
     * Add the given files to editable.
     */
    @Override
    public void editFiles(Collection<ProjectFile> files)
    {
        var proposedEditableFragments = files.stream().map(ContextFragment.ProjectPathFragment::new).toList();
        editFiles(proposedEditableFragments);
    }

    /**
     * Add the given files to editable.
     */
    public void editFiles(List<ContextFragment.ProjectPathFragment> fragments) {
        // Find existing read-only fragments that correspond to these files
        var currentReadOnlyFiles = topContext().readonlyFiles().collect(Collectors.toSet());
        var filesToEditSet = fragments.stream().map(ContextFragment.ProjectPathFragment::file).collect(Collectors.toSet());
        var existingReadOnlyFragmentsToRemove = currentReadOnlyFiles.stream()
                .filter(pf -> filesToEditSet.contains(pf.file()))
                .toList();
        // Find existing editable fragments that correspond to these files to avoid duplicates
        var currentEditableFileSet = topContext().editableFiles().map(PathFragment::file).collect(Collectors.toSet());
        var uniqueNewEditableFragments = fragments.stream()
                .filter(frag -> !currentEditableFileSet.contains(frag.file()))
                .toList();

        // Push the context update: remove the *existing* read-only fragments and add the *new, unique* editable ones
        pushContext(ctx -> ctx.removeReadonlyFiles(existingReadOnlyFragmentsToRemove)
                .addEditableFiles(uniqueNewEditableFragments));

        io.systemOutput("Edited " + joinForOutput(fragments));
    }

    /**
     * Add read-only files.
     */
    public void addReadOnlyFiles(Collection<? extends BrokkFile> files)
    {
        // Create the new fragments to be added as read-only
        var proposedReadOnlyFragments = files.stream().map(ContextFragment::toPathFragment).toList();
        // Find existing editable fragments that correspond to these files
        var currentEditableFiles = topContext().editableFiles().collect(Collectors.toSet());
        var filesToReadSet = files.stream().map(ContextFragment::toPathFragment).map(PathFragment::file).collect(Collectors.toSet());
        var existingEditableFragmentsToRemove = currentEditableFiles.stream()
                .filter(pf -> filesToReadSet.contains(pf.file()))
                .map(PathFragment.class::cast) // Cast to the required supertype
                .toList();
        // Find existing read-only fragments that correspond to these files to avoid duplicates
        var currentReadOnlyFileSet = topContext().readonlyFiles().map(PathFragment::file).collect(Collectors.toSet());
        var uniqueNewReadOnlyFragments = proposedReadOnlyFragments.stream()
                .filter(frag -> !currentReadOnlyFileSet.contains(frag.file()))
                .toList();

        // Push the context update: remove the *existing* editable fragments and add the *new, unique* read-only ones
        pushContext(ctx -> ctx.removeEditableFiles(existingEditableFragmentsToRemove)
                .addReadonlyFiles(uniqueNewReadOnlyFragments));

        io.systemOutput("Read " + joinFilesForOutput(files));
    }

    /**
     * Drop all context.
     */
    public void dropAll()
    {
        pushContext(Context::removeAll);
    }

    /**
     * Drop the given fragments.
     */
    public void drop(List<PathFragment> pathFragsToRemove, List<VirtualFragment> virtualToRemove)
    {
        pushContext(ctx -> ctx
                .removeEditableFiles(pathFragsToRemove)
                .removeReadonlyFiles(pathFragsToRemove)
                .removeVirtualFragments(virtualToRemove));
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
        project.getRepo().refresh();
        analyzerWrapper.requestRebuild();
    }

    /**
     * undo last context change
     */
    public Future<?> undoContextAsync()
    {
        return submitUserTask("Undo", () -> {
            UndoResult result = contextHistory.undo(1, io);
            if (result.wasUndone()) {
                var currentContext = contextHistory.topContext();
                notifyContextListeners(currentContext);
                project.saveContext(currentContext);
                io.systemOutput("Undid " + result.steps() + " step" + (result.steps() > 1 ? "s" : "") + "!");
            } else {
                io.toolErrorRaw("no undo state available");
            }
        });
    }

    /**
     * undo changes until we reach the target context
     */
    public Future<?> undoContextUntilAsync(Context targetContext)
    {
        return submitUserTask("Undoing", () -> {
            UndoResult result = contextHistory.undoUntil(targetContext, io);
            if (result.wasUndone()) {
                var currentContext = contextHistory.topContext();
                notifyContextListeners(currentContext);
                project.saveContext(currentContext);
                io.systemOutput("Undid " + result.steps() + " step" + (result.steps() > 1 ? "s" : "") + "!");
            } else {
                io.toolErrorRaw("Context not found or already at that point");
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
                var currentContext = contextHistory.topContext();
                notifyContextListeners(currentContext);
                project.saveContext(currentContext);
                io.systemOutput("Redo!");
            } else {
                io.toolErrorRaw("no redo state available");
            }
        });
    }

    /**
     * Reset the context to match the files and fragments from a historical context
     */
    public Future<?> resetContextToAsync(Context targetContext) {
        return submitUserTask("Resetting context", () -> {
            try {
                pushContext(ctx -> Context.createFrom(targetContext, ctx));
                io.systemOutput("Reset workspace to historical state");
            } catch (CancellationException cex) {
                io.systemOutput("Reset workspace canceled.");
            }
        });
    }

    /**
     * Reset the context and history to match the files, fragments, and history from a historical context
     */
    public Future<?> resetContextToIncludingHistoryAsync(Context targetContext) {
        return submitUserTask("Resetting context and history", () -> {
            try {
                pushContext(ctx -> Context.createFromIncludingHistory(targetContext, ctx));
                io.systemOutput("Reset workspace and history to historical state");
            } catch (CancellationException cex) {
                io.systemOutput("Reset workspace and history canceled.");
            }
        });
    }


    /**
     * Adds any virtual fragment directly
     */
    public void addVirtualFragment(VirtualFragment fragment)
    {
        pushContext(ctx -> ctx.addVirtualFragment(fragment));
    }

    /**
     * Handles pasting an image from the clipboard.
     * Submits a task to summarize the image and adds a PasteImageFragment to the context.
     *
     * @param image The java.awt.Image pasted from the clipboard.
     */
    public void addPastedImageFragment(java.awt.Image image) {
        // Submit task to get image description asynchronously
        Future<String> descriptionFuture = submitSummarizePastedImage(image); // Note: submitSummarizePastedImage needs to be defined

        // Add the PasteImageFragment immediately, the description will update when the future completes
        pushContext(ctx -> {
            var fragment = new ContextFragment.PasteImageFragment(image, descriptionFuture);
            // While PasteImageFragment itself inherits from VirtualFragment, let's use the specific addVirtualFragment
            // method for consistency, as it handles adding to the correct internal list.
            return ctx.addVirtualFragment(fragment);
        });
        // User feedback is handled in the calling method (ContextPanel.doPasteAction)
    }

    /**
     * Adds a specific PathFragment (like GitHistoryFragment) to the read-only context.
     *
     * @param fragment The PathFragment to add.
     */
    public void addReadOnlyFragment(PathFragment fragment)
    {
        // Use the existing addReadonlyFiles method in Context, which takes a collection
        pushContext(ctx -> ctx.addReadonlyFiles(List.of(fragment)));
    }


    /**
     * Captures text from the LLM output area and adds it to the context.
     * Called from Chrome's capture button.
     */
    public void captureTextFromContextAsync()
    {
        contextActionExecutor.submit(() -> {
            try {
                var selectedCtx = selectedContext();
                if (selectedCtx != null && selectedCtx.getParsedOutput() != null) {
                    addVirtualFragment(selectedCtx.getParsedOutput());
                    io.systemOutput("Content captured from output");
                } else {
                    io.toolErrorRaw("No content to capture");
                }
            } catch (CancellationException cex) {
                io.systemOutput("Capture canceled.");
            }
        });
    }

    /**
     * usage for identifier
     */
    public void usageForIdentifier(String identifier)
    {
        IAnalyzer analyzer;
        analyzer = getAnalyzerUninterrupted();
        var uses = analyzer.getUses(identifier);
        if (uses.isEmpty()) {
            io.systemOutput("No uses found for " + identifier);
            return;
        }
        var result = AnalyzerUtil.processUsages(analyzer, uses);
        if (result.code().isEmpty()) {
            io.systemOutput("No relevant uses found for " + identifier);
            return;
        }
        var combined = result.code();
        var fragment = new ContextFragment.UsageFragment(identifier, result.sources(), combined);
        pushContext(ctx -> ctx.addVirtualFragment(fragment));
    }

    /**
     * callers for method
     */
    public void callersForMethod(String methodName, int depth, Map<String, List<CallSite>> callgraph)
    {
        if (callgraph == null || callgraph.isEmpty()) {
            io.systemOutput("No callers found for " + methodName);
            return;
        }

        String formattedCallGraph = AnalyzerUtil.formatCallGraph(callgraph, methodName, true);
        if (formattedCallGraph.isEmpty()) {
            io.systemOutput("No callers found for " + methodName);
            return;
        }

        // Extract the class from the method name for sources
        Set<CodeUnit> sources = new HashSet<>();
        String className = ContextFragment.toClassname(methodName);
        // Use getDefinition to find the class CodeUnit and add it to sources
        getAnalyzerUninterrupted().getDefinition(className)
                .filter(CodeUnit::isClass)
                .ifPresent(sources::add);

        var fragment = new ContextFragment.CallGraphFragment("Callers (depth " + depth + ")", methodName, sources, formattedCallGraph);
        pushContext(ctx -> ctx.addVirtualFragment(fragment));

        int totalCallSites = callgraph.values().stream().mapToInt(List::size).sum();
        io.systemOutput("Added call graph with " + totalCallSites + " call sites for callers of " + methodName + " with depth " + depth);
    }

    /**
     * callees for method
     */
    public void calleesForMethod(String methodName, int depth, Map<String, List<CallSite>> callgraph)
    {
        if (callgraph == null || callgraph.isEmpty()) {
            io.systemOutput("No callees found for " + methodName);
            return;
        }

        String formattedCallGraph = AnalyzerUtil.formatCallGraph(callgraph, methodName, false);
        if (formattedCallGraph.isEmpty()) {
            io.systemOutput("No callees found for " + methodName);
            return;
        }

        // Extract the class from the method name for sources
        Set<CodeUnit> sources = new HashSet<>();
        String className = ContextFragment.toClassname(methodName);
        // Use getDefinition to find the class CodeUnit and add it to sources
        getAnalyzerUninterrupted().getDefinition(className)
                .filter(CodeUnit::isClass)
                .ifPresent(sources::add);

        // The output is similar to UsageFragment, so we'll use that
        var fragment = new ContextFragment.CallGraphFragment("Callees (depth " + depth + ")", methodName, sources, formattedCallGraph);
        pushContext(ctx -> ctx.addVirtualFragment(fragment));

        int totalCallSites = callgraph.values().stream().mapToInt(List::size).sum();
        io.systemOutput("Added call graph with " + totalCallSites + " call sites for methods called by " + methodName + " with depth " + depth);
    }

    /**
     * parse stacktrace
     */
    public boolean addStacktraceFragment(StackTrace stacktrace)
    {
        assert stacktrace != null;

        var exception = stacktrace.getExceptionType();
        var content = new StringBuilder();
        var sources = new HashSet<CodeUnit>();

        IAnalyzer analyzer;
        analyzer = getAnalyzerUninterrupted();

        for (var element : stacktrace.getFrames()) {
            var methodFullName = element.getClassName() + "." + element.getMethodName();
            var methodSource = analyzer.getMethodSource(methodFullName);
            if (methodSource.isPresent()) {
                String className = ContextFragment.toClassname(methodFullName);
                // Use getDefinition to find the class CodeUnit and add it to sources
                analyzer.getDefinition(className)
                        .filter(CodeUnit::isClass) // Ensure it's a class
                        .ifPresent(sources::add); // Add the CodeUnit directly

                content.append(methodFullName).append(":\n");
                content.append(methodSource.get()).append("\n\n");
            }
        }
        if (content.isEmpty()) {
            io.toolErrorRaw("No relevant methods found in stacktrace -- adding as text");
            return false;
        }
        pushContext(ctx -> {
            var fragment = new ContextFragment.StacktraceFragment(sources, stacktrace.getOriginalText(), exception, content.toString());
            return ctx.addVirtualFragment(fragment);
        });
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
            io.toolErrorRaw("Code Intelligence is empty; nothing to add");
            return false;
        }

        // Combine skeletons from both files and specific classes

        // Process files: get skeletons for all classes within each file in parallel
        Map<CodeUnit, String> skeletonsFromFiles = files.parallelStream()
            .map(file -> {
                try {
                    return analyzer.getSkeletons(file);
                } catch (Exception e) {
                    logger.warn("Failed to get skeletons for file {}: {}", file, e.getMessage());
                    return Collections.<CodeUnit, String>emptyMap();
                }
            })
            .flatMap(map -> map.entrySet().stream())
            .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (v1, v2) -> v1 // In case of duplicate keys (shouldn't happen)
            ));
        var allSkeletons = new HashMap<>(skeletonsFromFiles);

        // Process specific classes/symbols
        if (!classes.isEmpty()) {
            var classSkeletons = AnalyzerUtil.getSkeletonStrings(analyzer, classes);
            allSkeletons.putAll(classSkeletons);
        }

        if (allSkeletons.isEmpty()) {
            // No skeletons could be generated from the provided files or classes
            return false;
        }

        // Create and add the fragment
        var skeletonFragment = new ContextFragment.SkeletonFragment(allSkeletons);
        addVirtualFragment(skeletonFragment);

        io.systemOutput("Summarized " + joinForOutput(List.of(skeletonFragment)));
        return true;
    }

    private String joinForOutput(Collection<? extends ContextFragment> fragments) {
        return joinFilesForOutput(fragments.stream().flatMap(f -> f.files(project).stream()).collect(Collectors.toSet()));
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
    public List<ChatMessage> getHistoryMessages()
    {
        var taskHistory = topContext().getTaskHistory();
        var messages = new ArrayList<ChatMessage>();
        EditBlockParser parser = getParserForWorkspace(); // Parser for redacting S/R blocks

        // Merge compressed messages into a single taskhistory message
        var compressed = taskHistory.stream()
                .filter(TaskEntry::isCompressed)
                .map(TaskEntry::toString) // This will use raw messages if TaskEntry was created with them
                .collect(Collectors.joining("\n\n"));
        if (!compressed.isEmpty()) {
            messages.add(new UserMessage("<taskhistory>%s</taskhistory>".formatted(compressed)));
            messages.add(new AiMessage("Ok, I see the history."));
        }

        // Uncompressed messages: process for S/R block redaction
        taskHistory.stream()
                .filter(e -> !e.isCompressed())
                .forEach(e -> {
                    var entryRawMessages = e.log().messages();
                    // Determine the messages to include from the entry
                    var relevantEntryMessages = entryRawMessages.getLast() instanceof AiMessage
                                           ? entryRawMessages
                                           : entryRawMessages.subList(0, entryRawMessages.size() - 1);

                    List<ChatMessage> processedMessages = new ArrayList<>();
                    for (var chatMessage : relevantEntryMessages) {
                        if (chatMessage instanceof AiMessage aiMessage) {
                            redactAiMessage(aiMessage, parser).ifPresent(processedMessages::add);
                        } else {
                            // Not an AiMessage (e.g., UserMessage, CustomMessage), add as is
                            processedMessages.add(chatMessage);
                        }
                    }
                    messages.addAll(processedMessages);
                });

        return messages;
    }

    /**
     * Redacts SEARCH/REPLACE blocks from an AiMessage.
     * If the message contains S/R blocks, they are replaced with "[elided SEARCH/REPLACE block]".
     * If the message does not contain S/R blocks, or if the redacted text is blank, Optional.empty() is returned.
     *
     * @param aiMessage The AiMessage to process.
     * @param parser    The EditBlockParser to use for parsing.
     * @return An Optional containing the redacted AiMessage, or Optional.empty() if no message should be added.
     */
    static Optional<AiMessage> redactAiMessage(AiMessage aiMessage, EditBlockParser parser) {
        // Pass an empty set for trackedFiles as it's not needed for redaction.
        var parsedResult = parser.parse(aiMessage.text(), Collections.emptySet());
        // Check if there are actual S/R block objects, not just text parts
        boolean hasSrBlocks = parsedResult.blocks().stream().anyMatch(b -> b.block() != null);

        if (!hasSrBlocks) {
            // No S/R blocks, return message as is (if not blank)
            return aiMessage.text().isBlank() ? Optional.empty() : Optional.of(aiMessage);
        } else {
            // Contains S/R blocks, needs redaction
            var blocks = parsedResult.blocks();
            var sb = new StringBuilder();
            for (int i = 0; i < blocks.size(); i++) {
                var ob = blocks.get(i);
                if (ob.block() == null) { // Plain text part
                    sb.append(ob.text());
                } else { // An S/R block
                    sb.append("[elided SEARCH/REPLACE block]");
                    // If the next output block is also an S/R block, add a newline
                    if (i + 1 < blocks.size() && blocks.get(i + 1).block() != null) {
                        sb.append('\n');
                    }
                }
            }
            String redactedText = sb.toString();
            return redactedText.isBlank() ? Optional.empty() : Optional.of(new AiMessage(redactedText));
        }
    }

    public List<ChatMessage> getHistoryMessagesForCopy()
    {
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

        var version = BuildInfo.version();

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
    public void close() {
        userActionExecutor.shutdown();
        contextActionExecutor.shutdown();
        backgroundTasks.shutdown();
        project.close();
        analyzerWrapper.close();
    }

    /**
     * Constructs the ChatMessage(s) representing the current workspace context (read-only and editable files/fragments).
     * Handles both text and image fragments, creating a multimodal UserMessage if necessary.
     *
     * @return A collection containing one UserMessage (potentially multimodal) and one AiMessage acknowledgment, or empty if no content.
     */
    public Collection<ChatMessage> getWorkspaceContentsMessages(boolean includeRelatedClasses) throws InterruptedException {
        var c = topContext();
        var allContents = new ArrayList<Content>(); // Will hold TextContent and ImageContent

        // --- Process Read-Only Fragments (Files, Virtual, AutoContext) ---
        var readOnlyTextFragments = new StringBuilder();
        var readOnlyImageFragments = new ArrayList<ImageContent>();
        c.getReadOnlyFragments()
                .forEach(fragment -> {
                    try {
                        if (fragment.isText()) {
                            // Handle text-based fragments
                            String formatted = fragment.format();
                            if (formatted != null && !formatted.isBlank()) {
                                readOnlyTextFragments.append(formatted).append("\n\n");
                            }
                        } else if (fragment instanceof ContextFragment.ImageFileFragment ||
                                fragment instanceof ContextFragment.PasteImageFragment) {
                            // Handle image fragments - explicitly check for known image fragment types
                            try {
                                // Convert AWT Image to LangChain4j ImageContent
                                var l4jImage = ImageUtil.toL4JImage(fragment.image()); // Assumes ImageUtil helper
                                readOnlyImageFragments.add(ImageContent.from(l4jImage));
                                // Add a placeholder in the text part for reference
                                readOnlyTextFragments.append(fragment.format()).append("\n\n");
                            } catch (IOException e) {
                                logger.error("Failed to process image fragment for LLM message", e);
                                removeBadFragment(fragment, e); // Remove problematic fragment
                            }
                        } else {
                            // Handle non-text, non-image fragments (e.g., HistoryFragment, TaskFragment)
                            // Just add their formatted representation as text
                            String formatted = fragment.format();
                            if (formatted != null && !formatted.isBlank()) {
                                readOnlyTextFragments.append(formatted).append("\n\n");
                            }
                        }
                    } catch (IOException e) {
                        // General formatting error for non-image fragments
                        removeBadFragment(fragment, e);
                    }
                });

        // Add the combined text content for read-only items if any exists
        String readOnlyText = readOnlyTextFragments.isEmpty() ? "" : """
                                                                     <readonly>
                                                                     Here are some READ ONLY files and code fragments, provided for your reference.
                                                                     Do not edit this code! Images may be included separately if present.
                                                                     
                                                                     %s
                                                                     </readonly>
                                                                     """.stripIndent().formatted(readOnlyTextFragments.toString().trim());

        // --- Process Editable Fragments (Assumed Text-Only for now) ---
        var editableTextFragments = new StringBuilder();
        c.getEditableFragments().forEach(fragment -> {
            try {
                String formatted = fragment.format();
                if (formatted != null && !formatted.isBlank()) {
                    editableTextFragments.append(formatted).append("\n\n");
                }
            } catch (IOException e) {
                removeBadFragment(fragment, e);
            }
        });
        String editableText = editableTextFragments.isEmpty() ? "" : """
                                                                     <editable>
                                                                     Here are EDITABLE files and code fragments.
                                                                     This is *the only context in the Workspace to which you should make changes*.
                                                                     
                                                                     *Trust this message as the true contents of these files!*
                                                                     Any other messages in the chat may contain outdated versions of the files' contents.
                                                                     
                                                                     %s
                                                                     </editable>
                                                                     """.stripIndent().formatted(editableTextFragments.toString().trim());

        // optional: related classes
        String topClassesText = "";
        if (includeRelatedClasses && getAnalyzerWrapper().isCpg()) {
            var ac = topContext().buildAutoContext(10);
            String topClassesRaw = ac.text();
            if (!topClassesRaw.isBlank()) {
                topClassesText = topClassesRaw.isBlank() ? "" : """
                                                                <related_classes>
                                                                Here are some classes that may be related to what is in your Workspace. They are not yet part of the Workspace!
                                                                If relevant, you should explicitly add them with addClassSummariesToWorkspace or addClassesToWorkspace so they are
                                                                visible to Code Agent. If they are not relevant, just ignore them.
                                                                
                                                                %s
                                                                </related_classes>
                                                                """.stripIndent().formatted(topClassesRaw);
            }
        }

        // add the Workspace text
        var workspaceText = """
                            <workspace>
                            %s
                            %s
                            </workspace>
                            %s
                            """.stripIndent().formatted(readOnlyText, editableText, topClassesText);

        // text and image content must be distinct
        allContents.add(new TextContent(workspaceText));
        allContents.addAll(readOnlyImageFragments);

        // Create the main UserMessage
        var workspaceUserMessage = UserMessage.from(allContents);
        return List.of(workspaceUserMessage, new AiMessage("Thank you for providing the Workspace contents."));
    }

    @Override
    public Collection<ChatMessage> getWorkspaceContentsMessages() throws InterruptedException {
        return getWorkspaceContentsMessages(false);
    }

    /**
     * @return a summary of each fragment in the workspace; for most fragment types this is just the description,
     * but for some (SearchFragment) it's the full text and for others (files, skeletons) it's the class summaries.
     */
    public Collection<ChatMessage> getWorkspaceSummaryMessages() {
        var c = topContext();
        IAnalyzer analyzer = getAnalyzerUninterrupted(); // Might block

        // --- Process Read-Only Fragments ---
        var summaries = Streams.concat(c.getReadOnlyFragments(), c.getEditableFragments())
                .map(fragment -> fragment.formatSummary(analyzer))
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n"));

        if (summaries.isEmpty()) {
            return List.of();
        }

        String summaryText = """
                             <workspace-summary>
                             %s
                             </workspace-summary>
                             """.stripIndent().formatted(summaries).trim();

        var summaryUserMessage = new UserMessage(summaryText);
        return List.of(summaryUserMessage, new AiMessage("Okay, I have the workspace summary."));
    }

    private String readOnlySummaryDescription(ContextFragment cf) {
        if (cf instanceof PathFragment pf) {
            return pf.file().toString();
        }

        return "\"%s\"".formatted(cf.description());
    }

    private String editableSummaryDescription(ContextFragment cf) {
        if (cf instanceof PathFragment pf) {
            return pf.file().toString();
        }

        ContextFragment.UsageFragment uf = (ContextFragment.UsageFragment) cf;
        var files = uf.files(project).stream().map(ProjectFile::toString).sorted().collect(Collectors.joining(", "));
        return "[%s] (%s)".formatted(files, uf.description());
    }

    public String getReadOnlySummary()
    {
        var c = topContext();
        return c.getReadOnlyFragments()
                .map(this::readOnlySummaryDescription)
                .filter(st -> !st.isBlank())
                .collect(Collectors.joining(", "));
    }

    public String getEditableSummary()
    {
        return topContext().getEditableFragments()
                .map(this::editableSummaryDescription)
                .collect(Collectors.joining(", "));
    }

    public Set<ProjectFile> getEditableFiles()
    {
        return topContext().editableFiles()
                .map(ContextFragment.ProjectPathFragment::file)
                .collect(Collectors.toSet());
    }

    @Override
    public Set<BrokkFile> getReadonlyFiles() {
        return topContext().readonlyFiles()
                .map(ContextFragment.PathFragment::file)
                .collect(Collectors.toSet());
    }

    /**
     * Push context changes with a function that modifies the current context.
     * Returns the new context, or null if no changes were made by the generator.
     */
    public Context pushContext(Function<Context, Context> contextGenerator) {
        Context newContext = contextHistory.pushContext(contextGenerator);
        if (newContext == null) {
            return null;
        }

        notifyContextListeners(newContext);
        project.saveContext(newContext);
        if (newContext.getTaskHistory().isEmpty()) {
            return newContext;
        }

        var cf = new ContextFragment.HistoryFragment(newContext.getTaskHistory());
        int tokenCount = Messages.getApproximateTokens(cf.format());
        if (tokenCount > 32 * 1024) {
            // Show a dialog asking if we should compress the history
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
                    // Call the async compression method if user agrees
                    compressHistoryAsync();
                }
            });
        }

        return newContext;
    }

    /**
     * Updates the selected context in history from the UI
     * Called by Chrome when the user selects a row in the history table
     */
    public void setSelectedContext(Context context) {
        contextHistory.setSelectedContext(context);
    }

    private void notifyContextListeners(Context context) {
        for (var listener : contextListeners) {
            listener.contextChanged(context);
        }
    }

    private String formattedOrNull(ContextFragment fragment)
    {
        try {
            return fragment.format();
        } catch (IOException e) {
            removeBadFragment(fragment, e);
            return null;
        }
    }

    public void removeBadFragment(ContextFragment f, IOException th)
    {
        io.toolErrorRaw("Removing unreadable fragment " + f.description());
        // removeBadFragment takes IOException, but we caught Exception. Wrap it.
        // Ideally removeBadFragment would take Exception or Throwable.
        IOException wrapper = (th instanceof IOException ioe) ? ioe : new IOException("Error processing fragment: " + th.getMessage(), th);
        pushContext(c -> c.removeBadFragment(f));
    }

    private final ConcurrentMap<Callable<?>, String> taskDescriptions = new ConcurrentHashMap<>();

    public SummarizeWorker submitSummarizePastedText(String pastedContent) {
        var worker = new SummarizeWorker(pastedContent, 12) {
            @Override
            protected void done() {
                io.postSummarize();
            }
        };

        worker.execute();
        return worker;
    }

    public SummarizeWorker submitSummarizeTaskForConversation(String input) {
        var worker = new SummarizeWorker(input, 5) {
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
                    if (result.error() != null || result.chatResponse() == null || result.chatResponse().aiMessage() == null) {
                        logger.warn("Image summarization failed or was cancelled.");
                        return "(Image summarization failed)";
                    }
                    var description = result.chatResponse().aiMessage().text();
                    return (description == null || description.isBlank()) ? "(Image description empty)" : description.trim();
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
     * Asynchronously determines the best verification command based on the user goal,
     * workspace summary, and stored BuildDetails. Uses the quickest model.
     * <p>
     * /**
     * Submits a background task to the internal background executor (non-user actions).
     */
    @Override
    public <T> Future<T> submitBackgroundTask(String taskDescription, Callable<T> task) {
        assert taskDescription != null;
        Future<T> future = backgroundTasks.submit(() -> {
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
    public Future<?> submitBackgroundTask(String taskDescription, Runnable task) {
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
            var model = getSearchModel();
            BuildAgent agent = new BuildAgent(project, getLlm(model, "Infer build details"), toolRegistry);
            BuildDetails inferredDetails;
            try {
                inferredDetails = agent.execute();
            } catch (Exception e) {
                logger.error("BuildAgent did not complete successfully (aborted or errored). Build details not saved.", e);
                io.toolError("Build Information Agent failed: " + e.getMessage());
                inferredDetails = BuildDetails.EMPTY;
            }

            project.saveBuildDetails(inferredDetails);
            io.systemOutput("Build details inferred and saved");
            return inferredDetails;
        });
    }

    public EditBlockParser getParserForWorkspace() {
        var allText = topContext().allFragments()
                .filter(ContextFragment::isText)
                .map(f -> {
                    try {
                        return f.text();
                    } catch (IOException e) {
                        return "";
                    }
                })
                .collect(Collectors.joining("\n"));
        return EditBlockParser.getParserFor(allText);
    }

    public Service reloadModels() {
        service.reinit(project);
        return service.get();
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
        if (project.getStyleGuide() != null) {
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
                        continue; // Ensure we continue to the next file even on error
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
                if (result.error() != null || result.chatResponse() == null) {
                    io.systemOutput("Failed to generate style guide: " + (result.error() != null ? result.error().getMessage() : "LLM unavailable or cancelled"));
                    project.saveStyleGuide("# Style Guide\n\n(Generation failed)\n");
                    return null;
                }
                var styleGuide = result.chatResponse().aiMessage().text();
                if (styleGuide == null || styleGuide.isBlank()) {
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

        if (result.error() != null || result.chatResponse() == null || result.chatResponse().aiMessage() == null) {
            logger.warn("History compression failed ({}) for entry: {}",
                        result.error() != null ? result.error().getMessage() : "LLM unavailable or cancelled",
                        entry);
            return entry;
        }

        String summary = result.chatResponse().aiMessage().text();
        if (summary == null || summary.isBlank()) {
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
    public TaskEntry addToHistory(SessionResult result, boolean compress) {
        assert result != null;
        if (result.output().messages().isEmpty() && result.originalContents().isEmpty()) {
            logger.warn("Skipping adding empty session result to history");
            return null;
        }

        var originalContents = result.originalContents();
        var action = result.actionDescription(); // This already includes the stop reason if not SUCCESS

        logger.debug("Adding session result to history. Action: '{}', Changed files: {}, Reason: {}", action, originalContents.size(), result.stopDetails());

        // Push the new context state with the added history entry
        TaskEntry newEntry = topContext().createTaskEntry(result);
        var finalEntry = compress ? compressHistory(newEntry) : newEntry;
        Future<String> actionFuture = submitSummarizeTaskForConversation(action);
        var newContext = pushContext(ctx -> ctx.addHistoryEntry(finalEntry, result.output(), actionFuture, originalContents));
        return newContext.getTaskHistory().getLast();
    }

    public List<Context> getContextHistoryList() {
        return contextHistory.getHistory();
    }

    public ContextHistory getContextHistory() {
        return contextHistory;
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

    @Override
    public ToolRegistry getToolRegistry() {
        assert toolRegistry != null : "ToolRegistry accessed before initialization";
        return toolRegistry;
    }

    /**
     * Asynchronously compresses the entire conversation history of the currently selected context.
     * Replaces the history with summarized versions of each task entry.
     * This runs as a user action because it visibly modifies the context history.
     */
    public Future<?> compressHistoryAsync() {
        return submitUserTask("Compressing History", () -> {
            // Disable history navigation during compression
            io.disableHistoryPanel();
            try {
                var currentContext = topContext();
                var history = currentContext.getTaskHistory();

                io.systemOutput("Compressing conversation history...");

                List<TaskEntry> compressedHistory = history
                        .parallelStream()                       // run each compression in parallel
                        .map(this::compressHistory)             // same logic, now concurrent
                        .collect(Collectors                      // keep original order & capacity
                                         .toCollection(() -> new ArrayList<>(history.size())));

                boolean changed = IntStream.range(0, history.size())
                        .anyMatch(i -> !history.get(i).equals(compressedHistory.get(i)));
                if (!changed) {
                    io.systemOutput("History is already compressed");
                    return;
                }

                // Push new context containing the compressed history
                pushContext(ctx -> ctx.withCompressedHistory(List.copyOf(compressedHistory)));
                io.systemOutput("Task history compressed successfully.");
            } finally {
                // Re-enable history navigation *after* the UI context is updated
                SwingUtilities.invokeLater(io::enableHistoryPanel);
            }
        });
    }

    public class SummarizeWorker extends SwingWorker<String, String> {
        private final String content;
        private final int words;

        public SummarizeWorker(String content, int words) {
            this.content = content;
            this.words = words;
        }

        @Override
        protected String doInBackground() {
            var msgs = SummarizerPrompts.instance.collectMessages(content, words);
            // Use quickModel for summarization
            Llm.StreamingResult result;
            try {
                result = getLlm(service.quickestModel(), "Summarize: " + content).sendRequest(msgs);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (result.error() != null || result.chatResponse() == null) {
                logger.warn("Summarization failed or was cancelled.");
                return "Summarization failed.";
            }
            return result.chatResponse().aiMessage().text().trim();
        }
    }
}
