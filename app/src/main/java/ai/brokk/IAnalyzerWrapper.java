package ai.brokk;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.watchservice.AbstractWatchService;
import ai.brokk.watchservice.NoopWatchService;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

public interface IAnalyzerWrapper extends AutoCloseable {
    String ANALYZER_BUSY_MESSAGE = "Code Intelligence is still being built. Please wait until completion.";
    String ANALYZER_BUSY_TITLE = "Analyzer Busy";

    CompletableFuture<IAnalyzer> updateFiles(Set<ProjectFile> relevantFiles);

    IAnalyzer get() throws InterruptedException;

    @Nullable
    IAnalyzer getNonBlocking();

    default boolean isReady() {
        return getNonBlocking() != null;
    }

    default void requestRebuild() {}

    default void pause() {}

    default void resume() {}

    default boolean isPause() {
        return false;
    }

    /**
     * Delete persisted analyzer state files for the project's languages.
     * Default implementation does nothing.
     */
    default void deletePersistedAnalyzerStateFiles() {}

    /**
     * Returns the underlying watch service for direct access.
     * Callers can use this to pause/resume file watching or add additional listeners.
     */
    default AbstractWatchService getWatchService() {
        return new NoopWatchService();
    }

    @Override
    default void close() {}
}
