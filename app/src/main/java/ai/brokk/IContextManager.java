package ai.brokk;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.analyzer.BrokkFile;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.git.IGitRepo;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.project.ModelProperties;
import ai.brokk.tasks.TaskList.TaskItem;
import ai.brokk.tools.ToolRegistry;
import com.google.common.collect.Streams;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/** Interface for context manager functionality */
public interface IContextManager {
    Logger logger = LogManager.getLogger(IContextManager.class);

    default boolean undoContext() throws InterruptedException {
        throw new UnsupportedOperationException();
    }

    @Blocking
    default void copyToClipboard(String textToCopy) {
        var selection = new StringSelection(textToCopy);
        var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        int maxAttempts = 3;
        int delayMs = 50;

        for (int i = 0; i < maxAttempts; i++) {
            try {
                clipboard.setContents(selection, selection);
                return;
            } catch (IllegalStateException e) {
                if (i == maxAttempts - 1) {
                    logger.warn("Failed to copy to clipboard after {} attempts", maxAttempts, e);
                    getIo().showNotification(IConsoleIO.NotificationRole.ERROR, "Failed to access system clipboard");
                    break;
                }
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Safely reads string data from the system clipboard, handling potential exceptions
     * when the clipboard is temporarily unavailable or doesn't contain string data.
     * <p>
     * <b>Background:</b> On Windows, clipboard access methods like
     * {@link Clipboard#isDataFlavorAvailable(DataFlavor)} and {@link Clipboard#getData(DataFlavor)}
     * can throw {@link IllegalStateException} when the clipboard is locked by another process.
     * This is particularly problematic during rapid focus change events on the EDT.
     * <p>
     * <b>Solution:</b> This wrapper catches all clipboard-related exceptions and returns {@code null}
     * to indicate unavailability, allowing the UI to gracefully handle temporary clipboard locks
     * without propagating exceptions to users.
     * <p>
     * <b>Related JDK Issue:</b> <a href="https://bugs.openjdk.org/browse/JDK-8353950">JDK-8353950</a>
     * - Windows clipboard interaction instability
     *
     * @return The string data from clipboard, or null if unavailable or not a string
     */
    @Blocking
    default @Nullable String getStringFromClipboard() {
        var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        int maxAttempts = 3;
        int delayMs = 50;

        for (int i = 0; i < maxAttempts; i++) {
            try {
                if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                    return null;
                }
                return (String) clipboard.getData(DataFlavor.stringFlavor);
            } catch (IllegalStateException e) {
                if (i == maxAttempts - 1) {
                    logger.warn("Failed to read from clipboard after {} attempts", maxAttempts, e);
                    getIo().showNotification(IConsoleIO.NotificationRole.ERROR, "Failed to access system clipboard");
                    break;
                }
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            } catch (UnsupportedFlavorException | IOException e) {
                logger.debug("Clipboard does not contain string data", e);
                return null;
            }
        }
        return null;
    }

    /** Callback interface for analyzer update events. */
    interface AnalyzerCallback {
        /** Called before each analyzer build begins. */
        default void beforeEachBuild() {}

        /** Called when the analyzer transitions from not-ready to ready state. */
        default void onAnalyzerReady() {}

        /**
         * Called after each analyzer build completes.
         *
         * @param externalRequest whether the build was externally requested
         */
        default void afterEachBuild(boolean externalRequest) {}

        /** Called when the underlying repo changed (e.g., branch switch). */
        default void onRepoChange() {}

        /** Called when tracked files change in the working tree. */
        default void onTrackedFileChange() {}

        /** Called when live dependencies are added or removed. */
        default void onLiveDependenciesChanged() {}
    }

    default ExecutorService getBackgroundTasks() {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns the live, unfrozen context that we can edit.
     *
     * @return the live, unfrozen context that we can edit
     */
    default Context liveContext() {
        throw new UnsupportedOperationException();
    }

    /** Returns whether successful task execution should auto-commit repository changes. */
    default boolean isAutoCommit() {
        return true;
    }

    /** Listener interface for context change events. */
    interface ContextListener {
        /**
         * Called when the context has changed.
         *
         * @param newCtx The new context state.
         */
        void contextChanged(Context newCtx);
    }

    /**
     * Adds a listener that will be notified when the context changes.
     *
     * @param listener The listener to add. Must not be null.
     */
    default void addContextListener(ContextListener listener) {}

    default void removeContextListener(ContextListener listener) {}

    /**
     * Adds a callback that will be notified when the analyzer is updated.
     *
     * @param callback The callback to add. Must not be null.
     */
    default void addAnalyzerCallback(AnalyzerCallback callback) {}

    default void removeAnalyzerCallback(AnalyzerCallback callback) {}

    /**
     * Given a relative path, uses the current project root to construct a valid {@link ProjectFile}. If the path is
     * suffixed by a leading '/', this is stripped and attempted to be interpreted as a relative path.
     *
     * @param relName a relative path.
     * @return a {@link ProjectFile} instance, if valid.
     * @throws IllegalArgumentException if the path is not relative or normalized.
     */
    default ProjectFile toFile(String relName) {
        var trimmed = relName.trim();
        var project = getProject();

        // If an absolute-like path is provided (leading '/' or '\'), attempt to interpret it as a
        // project-relative path by stripping the leading slash. If that file exists, return it.
        if (trimmed.startsWith(File.separator)) {
            var candidateRel = trimmed.substring(File.separator.length()).trim();
            var candidate = new ProjectFile(project.getRoot(), candidateRel);
            if (candidate.exists()) {
                return candidate;
            }
            // The path looked absolute (or root-anchored) but does not exist relative to the project.
            // Treat this as invalid to avoid resolving to a location outside the project root.
            throw new IllegalArgumentException(
                    "Filename '%s' is absolute-like and does not exist relative to the project root"
                            .formatted(relName));
        }

        return new ProjectFile(project.getRoot(), trimmed);
    }

    @Blocking
    default Set<ProjectFile> getFilesInContext() {
        return liveContext()
                .allFragments()
                .filter(f -> f.getType().isPath())
                .flatMap(cf -> cf.referencedFiles().join().stream())
                .collect(Collectors.toSet());
    }

    @Blocking
    default Context createOrReplaceTaskList(Context context, @Nullable String bigPicture, List<TaskItem> tasks) {
        throw new UnsupportedOperationException();
    }

    default Context pushContext(Function<Context, Context> contextGenerator) {
        throw new UnsupportedOperationException();
    }

    default <T> CompletableFuture<T> submitBackgroundTask(String taskDescription, Callable<T> task) {
        try {
            return CompletableFuture.completedFuture(task.call());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Submits a background task that doesn't return a result.
     *
     * @param taskDescription a description of the task
     * @param task the task to execute
     * @return a {@link Future} representing pending completion of the task
     */
    default CompletableFuture<Void> submitBackgroundTask(String taskDescription, Runnable task) {
        return submitBackgroundTask(taskDescription, () -> {
            task.run();
            return null;
        });
    }

    default <T> CompletableFuture<T> submitMaintenanceTask(String taskDescription, Callable<T> task) {
        try {
            return CompletableFuture.completedFuture(task.call());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    default CompletableFuture<Void> submitMaintenanceTask(String taskDescription, Runnable task) {
        return submitMaintenanceTask(taskDescription, () -> {
            task.run();
            return null;
        });
    }

    default Set<ProjectFile> getTestFiles() {
        Set<ProjectFile> allFiles = getRepo().getTrackedFiles();
        var analyzer = getAnalyzerWrapper().getNonBlocking();
        return allFiles.stream()
                .filter(f -> ContextManager.isTestFile(f, analyzer))
                .collect(Collectors.toSet());
    }

    default IAnalyzerWrapper getAnalyzerWrapper() {
        throw new UnsupportedOperationException();
    }

    default IAnalyzer getAnalyzer() throws InterruptedException {
        throw new UnsupportedOperationException();
    }

    default IAnalyzer getAnalyzerUninterrupted() {
        throw new UnsupportedOperationException();
    }

    default List<? extends ContextFragments.PathFragment> toPathFragments(Collection<ProjectFile> files) {
        var filesByType = files.stream().collect(Collectors.partitioningBy(BrokkFile::isText));

        var textFiles = castNonNull(filesByType.get(true));
        var binaryFiles = castNonNull(filesByType.get(false));

        return Streams.concat(
                        textFiles.stream().map(pf -> new ContextFragments.ProjectPathFragment(pf, this)),
                        binaryFiles.stream().map(pf -> new ContextFragments.ImageFileFragment(pf, this)))
                .toList();
    }

    default void requestRebuild() {}

    /** Notifies all registered analyzer callbacks that live dependencies have changed. */
    default void notifyLiveDependenciesChanged() {}

    default IGitRepo getRepo() {
        return getProject().getRepo();
    }

    default AbstractService getService() {
        return new OfflineService(getProject());
    }

    default void reportException(Throwable th) {}

    default void reportException(Throwable th, Map<String, String> optionalFields) {}

    /** Returns the configured Code model, falling back to the system model if unavailable. */
    default StreamingChatModel getCodeModel() {
        return getService().getModel(ModelProperties.ModelType.CODE);
    }

    default void addFiles(Collection<ProjectFile> path) {}

    default IProject getProject() {
        throw new UnsupportedOperationException();
    }

    default ContextManager.TaskScope beginTask(String input, boolean group, @Nullable String taskDescription) {
        throw new UnsupportedOperationException();
    }

    /** Begin a new aggregating scope with explicit compress-at-commit semantics and non-text resolution mode. */
    default ContextManager.TaskScope beginTaskUngrouped(String input) {
        return beginTask(input, false, null);
    }

    default ContextManager.TaskScope anonymousScope() {
        throw new UnsupportedOperationException();
    }

    default IConsoleIO getIo() {
        throw new UnsupportedOperationException();
    }

    default ToolRegistry getToolRegistry() {
        throw new UnsupportedOperationException();
    }

    /** Adds any virtual fragment directly to the live context. */
    default void addFragments(Collection<? extends ContextFragment> fragments) {
        if (fragments.isEmpty()) {
            return;
        }
        pushContext(currentLiveCtx -> currentLiveCtx.addFragments(fragments));
    }

    /** Adds any virtual fragment directly to the live context. */
    default void addFragments(ContextFragment fragment) {
        addFragments(List.of(fragment));
    }

    default Llm getLlm(StreamingChatModel model, String taskDescription, TaskResult.Type type) {
        var options = new Llm.Options(model, taskDescription, type);
        return getLlm(options);
    }

    /** Create a new LLM instance using options */
    default Llm getLlm(Llm.Options options) {
        return Llm.create(
                options, this, getProject().getDataRetentionPolicy() == MainProject.DataRetentionPolicy.IMPROVE_BROKK);
    }

    @Blocking
    default void compressGlobalHistory() throws InterruptedException {
        if (liveContext().getTaskHistory().isEmpty()) {
            getIo().showNotification(IConsoleIO.NotificationRole.INFO, "No history to compress.");
            return;
        }

        var interrupted = new AtomicReference<InterruptedException>();
        pushContext(ctx -> {
            try {
                return compressHistory(ctx);
            } catch (InterruptedException e) {
                // user may interrupt
                interrupted.set(e);
                return ctx;
            }
        });
        if (interrupted.get() != null) {
            throw interrupted.get();
        }
        getIo().showNotification(IConsoleIO.NotificationRole.INFO, "Task history compressed successfully.");
    }

    @Blocking
    default Context compressHistory(Context ctx) throws InterruptedException {
        try {
            List<TaskEntry> compressedTaskHistory = compressHistoryAsync(ctx).join();
            return mergeCompressedHistory(ctx, compressedTaskHistory);
        } catch (CompletionException e) {
            var cause = e.getCause();
            if (cause instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            throw e;
        }
    }

    static Context mergeCompressedHistory(Context currentContext, List<TaskEntry> compressedEntries) {
        if (compressedEntries.isEmpty()) {
            return currentContext;
        }

        var bySequence = new HashMap<Integer, TaskEntry>(compressedEntries.size());
        for (var entry : compressedEntries) {
            bySequence.putIfAbsent(entry.sequence(), entry);
        }

        var mergedTaskHistory = currentContext.getTaskHistory().stream()
                .map(entry -> bySequence.getOrDefault(entry.sequence(), entry))
                .toList();
        return currentContext.withTaskHistory(mergedTaskHistory);
    }

    @Blocking
    default CompletableFuture<List<TaskEntry>> compressHistoryAsync(Context ctx) {
        return CompletableFuture.completedFuture(ctx.getTaskHistory());
    }

    @Blocking
    default String compressHistory(String history) throws InterruptedException {
        return history;
    }

    @Blocking
    default CompletableFuture<String> summarizeTaskForConversation(String taskText) {
        return CompletableFuture.completedFuture(taskText);
    }

    default UUID getCurrentSessionId() {
        throw new UnsupportedOperationException();
    }

    default void reloadCurrentSessionAsync() {
        throw new UnsupportedOperationException();
    }

    default CompletableFuture<Void> createSessionAsync(String name) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns true if a task (such as an LLM action or an aggregating task scope) is currently in progress.
     * <p>
     * The default implementation is conservative and returns {@code true} to prevent concurrent mutations
     * by unknown implementations. Implementations that can safely accept context mutations concurrently
     * should override this method appropriately.
     *
     * @return true if a task is in progress, false otherwise.
     */
    default boolean isTaskInProgress() {
        return true;
    }
}
