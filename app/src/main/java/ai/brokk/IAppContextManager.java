package ai.brokk;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.executor.agents.AgentStore;
import ai.brokk.git.IGitRepo;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.project.ModelProperties;
import ai.brokk.tasks.TaskList.TaskItem;
import ai.brokk.tools.ToolRegistry;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/** App-owned interface for context manager functionality (UI/session/tool wiring). */
public interface IAppContextManager extends IContextManager {
    Logger logger = LogManager.getLogger(IAppContextManager.class);

    @Override
    IProject getProject();

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
        default void beforeEachBuild() {}

        default void onAnalyzerReady() {}

        default void afterEachBuild(boolean externalRequest) {}

        default void onRepoChange() {}

        default void onTrackedFileChange() {}

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
        void contextChanged(Context newCtx);
    }

    default void addContextListener(ContextListener listener) {}

    default void removeContextListener(ContextListener listener) {}

    default void addAnalyzerCallback(AnalyzerCallback callback) {}

    default void removeAnalyzerCallback(AnalyzerCallback callback) {}

    static Context mergeCompressedHistory(Context context, List<TaskEntry> compressedTaskHistory) {
        if (compressedTaskHistory.isEmpty()) {
            return context;
        }
        return context.withHistory(compressedTaskHistory);
    }

    @Blocking
    default Set<ProjectFile> getFilesInContext() {
        return liveContext()
                .allFragments()
                .filter(f -> f.getType().isPath())
                .flatMap(cf -> cf.referencedFiles().join().stream())
                .collect(Collectors.toSet());
    }

    // ---- Remaining app-specific API surface (unchanged) ----
    default IGitRepo getRepo() {
        throw new UnsupportedOperationException();
    }

    default IAnalyzerWrapper getAnalyzerWrapper() {
        throw new UnsupportedOperationException();
    }

    @Override
    default IAnalyzer getAnalyzer() {
        return getAnalyzerUninterrupted();
    }

    default IConsoleIO getIo() {
        throw new UnsupportedOperationException();
    }

    default AbstractService getService() {
        throw new UnsupportedOperationException();
    }

    default ToolRegistry getToolRegistry() {
        throw new UnsupportedOperationException();
    }

    default StreamingChatModel getCodeModel() {
        throw new UnsupportedOperationException();
    }

    default StreamingChatModel getPlanningModel() {
        throw new UnsupportedOperationException();
    }

    default Llm getLlm(StreamingChatModel model, String taskDescription, TaskResult.Type type) {
        throw new UnsupportedOperationException();
    }

    default Llm getLlm(Llm.Options options) {
        return getLlm(options.model(), options.taskDescription(), options.type());
    }

    default <T> CompletableFuture<T> submitBackgroundTask(String description, Callable<T> callable) {
        throw new UnsupportedOperationException();
    }

    default CompletableFuture<Void> submitBackgroundTask(String description, Runnable runnable) {
        return submitBackgroundTask(description, () -> {
            runnable.run();
            return null;
        });
    }

    default Context pushContext(Function<Context, Context> fn) {
        throw new UnsupportedOperationException();
    }

    default void reportException(RuntimeException e) {
        reportException((Throwable) e, Map.of());
    }

    default void reportException(Throwable th) {
        reportException(th, Map.of());
    }

    default void reportException(Throwable th, Map<String, String> optionalFields) {
        throw new UnsupportedOperationException();
    }

    default void notifyLiveDependenciesChanged() {}

    default void addFiles(Collection<ProjectFile> files) {
        throw new UnsupportedOperationException();
    }

    default void requestRebuild() {
        throw new UnsupportedOperationException();
    }

    default boolean isTaskInProgress() {
        return false;
    }

    default AgentStore getAgentStore() {
        throw new UnsupportedOperationException();
    }

    default MainProject getMainProject() {
        throw new UnsupportedOperationException();
    }

    default ModelProperties getModelProperties() {
        throw new UnsupportedOperationException();
    }

    default TaskResult executeTask(TaskItem task, StreamingChatModel planningModel, StreamingChatModel codeModel)
            throws InterruptedException {
        throw new UnsupportedOperationException();
    }

    default List<ContextFragments.ProjectPathFragment> toPathFragments(Collection<ProjectFile> files) {
        return files.stream()
                .map(pf -> new ContextFragments.ProjectPathFragment(pf, this))
                .toList();
    }

    default void addFragments(Collection<? extends ContextFragment> fragments) {
        throw new UnsupportedOperationException();
    }

    default void addFragments(ContextFragment fragment) {
        addFragments(List.of(fragment));
    }

    default ContextManager.TaskScope beginTask(String input, boolean group, @Nullable String taskDescription) {
        throw new UnsupportedOperationException();
    }

    default ContextManager.TaskScope beginTaskUngrouped(String input) {
        return beginTask(input, false, null);
    }

    default ContextManager.AnonymousScope anonymousScope() {
        throw new UnsupportedOperationException();
    }

    default Context createOrReplaceTaskList(Context context, @Nullable String bigPicture, List<TaskItem> tasks) {
        throw new UnsupportedOperationException();
    }

    default @Nullable UUID getCurrentSessionId() {
        return null;
    }

    default CompletableFuture<String> summarizeTaskForConversation(String input) {
        throw new UnsupportedOperationException();
    }

    @Blocking
    default String compressHistory(String history) throws InterruptedException {
        throw new UnsupportedOperationException();
    }

    default CompletableFuture<List<TaskEntry>> compressHistoryAsync(Context ctx) {
        throw new UnsupportedOperationException();
    }

    default void reloadCurrentSessionAsync() {
        throw new UnsupportedOperationException();
    }

    default CompletableFuture<Void> createSessionAsync(String name) {
        throw new UnsupportedOperationException();
    }

    // Many more methods exist on concrete ContextManager; keep additions here as needed.
}
