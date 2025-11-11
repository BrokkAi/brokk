package ai.brokk;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.analyzer.BrokkFile;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.git.IGitRepo;
import ai.brokk.tools.ToolRegistry;
import com.google.common.collect.Streams;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Interface for context manager functionality */
public interface IContextManager {
    Logger logger = LogManager.getLogger(IContextManager.class);

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
    }

    default ExecutorService getBackgroundTasks() {
        throw new UnsupportedOperationException();
    }

    default Collection<? extends ChatMessage> getHistoryMessages() {
        return List.of();
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

    default Set<ProjectFile> getFilesInContext() {
        throw new UnsupportedOperationException();
    }

    default void appendTasksToTaskList(List<String> tasks) {
        throw new UnsupportedOperationException();
    }

    default Context pushContext(Function<Context, Context> contextGenerator) {
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

    default IAnalyzerWrapper getAnalyzerWrapper() {
        throw new UnsupportedOperationException();
    }

    default IAnalyzer getAnalyzer() throws InterruptedException {
        throw new UnsupportedOperationException();
    }

    default IAnalyzer getAnalyzerUninterrupted() {
        throw new UnsupportedOperationException();
    }

    default List<? extends ContextFragment.PathFragment> toPathFragments(Collection<ProjectFile> files) {
        var filesByType = files.stream().collect(Collectors.partitioningBy(BrokkFile::isText));

        var textFiles = castNonNull(filesByType.get(true));
        var binaryFiles = castNonNull(filesByType.get(false));

        return Streams.concat(
                        textFiles.stream().map(pf -> new ContextFragment.ProjectPathFragment(pf, this)),
                        binaryFiles.stream().map(pf -> new ContextFragment.ImageFileFragment(pf, this)))
                .toList();
    }

    default void requestRebuild() {}

    default IGitRepo getRepo() {
        return getProject().getRepo();
    }

    default AbstractService getService() {
        throw new UnsupportedOperationException();
    }

    default void reportException(Throwable th) {}

    default StreamingChatModel getModelOrDefault(Service.ModelConfig config, String modelTypeName) {
        var service = getService();
        StreamingChatModel model = service.getModel(config);
        if (model != null) {
            return model;
        }

        model = service.getModel(new Service.ModelConfig(Service.GPT_5_MINI, Service.ReasoningLevel.DEFAULT));
        if (model != null) {
            getIo().showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            String.format(
                                    "Configured model '%s' for %s tasks is unavailable. Using fallback '%s'.",
                                    config.name(), modelTypeName, Service.GPT_5_MINI));
            return model;
        }

        var quickModel = service.quickModel();
        String quickModelName = service.nameOf(quickModel);
        getIo().showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        String.format(
                                "Configured model '%s' for %s tasks is unavailable. Preferred fallbacks also failed. Using system model '%s'.",
                                config.name(), modelTypeName, quickModelName));
        return quickModel;
    }

    /** Returns the configured Code model, falling back to the system model if unavailable. */
    default StreamingChatModel getCodeModel() {
        var config = getProject().getCodeModelConfig();
        return getModelOrDefault(config, "Code");
    }

    default void addFiles(Collection<ProjectFile> path) {}

    default IProject getProject() {
        throw new UnsupportedOperationException();
    }

    default IConsoleIO getIo() {
        throw new UnsupportedOperationException();
    }

    default ToolRegistry getToolRegistry() {
        throw new UnsupportedOperationException();
    }

    /** Adds any virtual fragment directly to the live context. */
    default void addVirtualFragments(Collection<? extends ContextFragment.VirtualFragment> fragments) {
        if (fragments.isEmpty()) {
            return;
        }
        pushContext(currentLiveCtx -> currentLiveCtx.addVirtualFragments(fragments));
    }

    /** Adds any virtual fragment directly to the live context. */
    default void addVirtualFragment(ContextFragment.VirtualFragment fragment) {
        addVirtualFragments(List.of(fragment));
    }

    /** Create a new LLM instance for the given model and description */
    default Llm getLlm(StreamingChatModel model, String taskDescription) {
        return getLlm(new Llm.Options(model, taskDescription));
    }

    /** Create a new LLM instance for the given model and description */
    default Llm getLlm(StreamingChatModel model, String taskDescription, boolean allowPartialResponses) {
        var options = new Llm.Options(model, taskDescription);
        if (allowPartialResponses) {
            options.withPartialResponses();
        }
        return getLlm(options);
    }

    /** Create a new LLM instance using options */
    default Llm getLlm(Llm.Options options) {
        return Llm.create(
                options, this, getProject().getDataRetentionPolicy() == MainProject.DataRetentionPolicy.IMPROVE_BROKK);
    }
}
