package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.tools.ToolRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Interface for context manager functionality
 */
public interface IContextManager {
    default EditBlockParser getParserForWorkspace() {
        throw new UnsupportedOperationException();
    }

    default ExecutorService getBackgroundTasks() {
        throw new UnsupportedOperationException();
    }

    default Collection<? extends ChatMessage> getHistoryMessages() {
        return List.of();
    }

    default Collection<? extends ChatMessage> getWorkspaceContentsMessages() throws InterruptedException {
        return List.of();
    }

    default Collection<ChatMessage> getWorkspaceReadOnlyMessages() throws InterruptedException {
        return List.of();
    }

    default Collection<ChatMessage> getWorkspaceEditableMessages() throws InterruptedException {
        return List.of();
    }

    default String getEditableSummary() {
        return "";
    }

    default String getReadOnlySummary() {
        return "";
    }

    /**
     * @return the live, unfrozen context that we can edit
     */
    default Context liveContext() {
        throw new UnsupportedOperationException();
    }

    /**
     * @return the frozen counterpart of liveContext
     */
    default Context topContext() {
        throw new UnsupportedOperationException();
    }

    /**
     * Listener interface for context change events.
     */
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
    default void addContextListener(@NotNull ContextListener listener) {
    }

    default void removeContextListener(@NotNull ContextListener listener) {
    }

    default ProjectFile toFile(String relName) {
        throw new UnsupportedOperationException();
    }

    default Set<ProjectFile> getEditableFiles() {
        throw new UnsupportedOperationException();
    }

    default Set<BrokkFile> getReadonlyFiles() {
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
        return allFiles.stream()
                .filter(ContextManager::isTestFile)
                .toList();
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

    default void editFiles(Collection<ProjectFile> path) {
    }

    default IProject getProject() {
        return new IProject() {};
    }

    default IConsoleIO getIo() {
        throw new UnsupportedOperationException();
    }

    default ToolRegistry getToolRegistry() {
        throw new UnsupportedOperationException();
    }

    /**
     * Create a new LLM instance for the given model and description
     */
    default Llm getLlm(StreamingChatLanguageModel model, String taskDescription) {
        return getLlm(model, taskDescription, false);
    }

    /**
     * Create a new LLM instance for the given model and description
     */
    default Llm getLlm(StreamingChatLanguageModel model, String taskDescription, boolean allowPartialResponses) {
        return new Llm(model, taskDescription, this, allowPartialResponses, getProject().getDataRetentionPolicy() == MainProject.DataRetentionPolicy.IMPROVE_BROKK);
    }
}
