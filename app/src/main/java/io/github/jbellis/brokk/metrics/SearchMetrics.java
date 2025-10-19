package io.github.jbellis.brokk.metrics;

import io.github.jbellis.brokk.TaskResult;
import java.util.List;
import java.util.Set;

/**
 * Interface for collecting SearchAgent metrics.
 *
 * Implementations must be thread-safe if concurrently accessed.
 *
 * This interface lives in a neutral package so agent code can depend on it
 * without importing CLI-specific classes or implementations.
 */
public interface SearchMetrics {

    void recordContextScan(int filesAdded, long timeMs, boolean skipped);

    void startTurn();

    void recordToolCall(String toolName);

    void recordFilesAdded(int count);

    /**
     * Record the concrete project-relative file paths that were added to the Workspace during the current turn.
     * The set should include only repo-backed files (project-relative paths). Implementations may ignore
     * virtual/summary fragments or include them as empty/placeholder entries.
     *
     * Example: Set.of("src/main/java/com/acme/Foo.java", "src/test/java/com/acme/FooTest.java")
     */
    void recordFilesAddedPaths(Set<String> paths);

    void endTurn();

    void recordFailure(TaskResult.StopReason reason, int workspaceSize);

    /**
     * Record the final snapshot of repo-backed files present in the Workspace at task completion.
     * This allows external harnesses to cross-check and select a primary file from the actual final Workspace.
     */
    void recordFinalWorkspaceFiles(Set<String> finalFiles);

    /**
     * Serialize metrics along with the basic result fields into JSON.
     *
     * @param query the original query
     * @param foundFiles list of files found (from last non-empty turn, may be empty)
     * @param turns number of turns (AI messages)
     * @param elapsedMs elapsed time in ms
     * @param success whether the search succeeded
     */
    String toJson(String query, List<String> foundFiles, int turns, long elapsedMs, boolean success);

    /**
     * Convenience factory for a no-op metrics instance.
     */
    static SearchMetrics noOp() {
        return NoOpSearchMetrics.INSTANCE;
    }
}
