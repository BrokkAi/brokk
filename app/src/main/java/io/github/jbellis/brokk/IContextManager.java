package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.tools.ToolRegistry;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/** Interface for context manager functionality */
public interface IContextManager {

    /** Callback interface for analyzer update events. */
    interface AnalyzerCallback {
        /** Called when the analyzer transitions from not-ready to ready state. */
        default void onAnalyzerReady() {}
    }

    default ExecutorService getBackgroundTasks() {
        throw new UnsupportedOperationException();
    }

    default Collection<? extends ChatMessage> getHistoryMessages() {
        return List.of();
    }

    default String getEditableSummary() {
        return "";
    }

    default String getReadOnlySummary() {
        return "";
    }

    /**
     * Returns the live, unfrozen context that we can edit.
     *
     * @return the live, unfrozen context that we can edit
     */
    default Context liveContext() {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns the frozen counterpart of liveContext.
     *
     * @return the frozen counterpart of liveContext
     */
    default Context topContext() {
        throw new UnsupportedOperationException();
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

    default Set<ProjectFile> getEditableFiles() {
        throw new UnsupportedOperationException();
    }

    default Set<BrokkFile> getReadonlyProjectFiles() {
        throw new UnsupportedOperationException();
    }

    default <T> Future<T> submitBackgroundTask(String taskDescription, Callable<T> task) {
        try {
            return CompletableFuture.completedFuture(task.call());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    default List<ProjectFile> getTestFiles() {
        Set<ProjectFile> allFiles = getRepo().getTrackedFiles();
        return allFiles.stream().filter(ContextManager::isTestFile).toList();
    }

    default AnalyzerWrapper getAnalyzerWrapper() {
        throw new UnsupportedOperationException();
    }

    default IAnalyzer getAnalyzer() throws InterruptedException {
        throw new UnsupportedOperationException();
    }

    default IAnalyzer getAnalyzerUninterrupted() {
        throw new UnsupportedOperationException();
    }

    default void requestRebuild() {}

    default IGitRepo getRepo() {
        return getProject().getRepo();
    }

    default Service getService() {
        throw new UnsupportedOperationException();
    }

    default void editFiles(Collection<ProjectFile> path) {}

    default IProject getProject() {
        throw new UnsupportedOperationException();
    }

    default IConsoleIO getIo() {
        throw new UnsupportedOperationException();
    }

    default ToolRegistry getToolRegistry() {
        throw new UnsupportedOperationException();
    }

    /** Create a new LLM instance for the given model and description */
    default Llm getLlm(StreamingChatModel model, String taskDescription) {
        return getLlm(model, taskDescription, false);
    }

    /** Create a new LLM instance for the given model and description */
    default Llm getLlm(StreamingChatModel model, String taskDescription, boolean allowPartialResponses) {
        return new Llm(
                model,
                taskDescription,
                this,
                allowPartialResponses,
                getProject().getDataRetentionPolicy() == MainProject.DataRetentionPolicy.IMPROVE_BROKK);
    }
}
