package io.github.jbellis.brokk.cli;

import io.github.jbellis.brokk.TaskResult;
import org.jetbrains.annotations.Nullable;

/**
 * Interface for collecting SearchAgent metrics.
 *
 * Implementations must be thread-safe if concurrently accessed.
 */
public interface SearchMetrics {

    void recordContextScan(int filesAdded, long timeMs, boolean skipped);

    void startTurn(int turnNumber);

    void recordToolCall(String toolName);

    void recordFilesAdded(int count);

    void endTurn(long turnTimeMs);

    void recordFailure(TaskResult.StopReason reason, int workspaceSize);

    void recordFoundFile(String file);

    @Nullable
    String getFoundFile();

    /**
     * Serialize metrics along with the basic result fields into JSON.
     *
     * @param query the original query
     * @param foundFile the primary file (may be empty)
     * @param turns number of turns (AI messages)
     * @param elapsedMs elapsed time in ms
     * @param success whether the search succeeded
     */
    String toJson(String query, String foundFile, int turns, long elapsedMs, boolean success);

    /**
     * Convenience factory for a no-op metrics instance.
     *
     * This replaces a previously exposed public NO_OP field. Callers should use
     * SearchMetrics.noOp() when they need a metrics implementation that does nothing.
     */
    static SearchMetrics noOp() {
        return NoOpSearchMetrics.INSTANCE;
    }
}
