package io.github.jbellis.brokk.analyzer;

import java.util.concurrent.CompletableFuture;

/**
 * Implemented by analyzers that can operate in a lightweight mode, and provide more advanced analysis capabilities once
 * some more expensive analysis or initialization process is completed.
 */
public interface HasDelayedCapabilities {

    /**
     * This is tied to a more advanced analysis process, and will return true if the process is ready, or false if it is
     * not available due to some error.
     *
     * @return a completable future that waits until the underlying analysis is ready, or completed exceptionally. This
     *     will return true for the former, and false for the latter.
     */
    CompletableFuture<Boolean> isAdvancedAnalysisReady();
}
