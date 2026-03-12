package ai.brokk;

import java.util.concurrent.CompletableFuture;

/**
 * Encapsulates submission of tasks to the analyzer/session-local executor.
 * Only code that receives this object can submit analyzer tasks,
 * preventing accidental misuse where submitBackgroundTask should be used instead.
 */
@FunctionalInterface
public interface AnalyzerTaskSubmitter {
    CompletableFuture<Void> submit(String taskDescription, Runnable task);
}
