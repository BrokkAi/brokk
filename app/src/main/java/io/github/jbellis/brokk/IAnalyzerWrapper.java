package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
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

    default boolean providesInterproceduralAnalysis() {
        return false;
    }

    default boolean providesSummaries() {
        return false;
    }

    default boolean providesSourceCode() {
        return false;
    }

    default boolean isReady() {
        return getNonBlocking() != null;
    }

    default void requestRebuild() {}

    default void pause() {}

    default void resume() {}

    @Override
    default void close() {}
}
