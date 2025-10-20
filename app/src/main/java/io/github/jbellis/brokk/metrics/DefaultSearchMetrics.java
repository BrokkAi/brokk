package io.github.jbellis.brokk.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.jbellis.brokk.AbstractProject;
import io.github.jbellis.brokk.TaskResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Full featured metrics implementation for SearchAgent.
 *
 * Methods are synchronized to be safe if accessed from multiple threads.
 */
public class DefaultSearchMetrics implements SearchMetrics {

    // Context scan metrics
    private int contextScanFilesAdded = 0;
    private long contextScanTimeMs = 0;
    private boolean contextScanSkipped = false;

    // Per-turn metrics
    private int turnCounter = 0;
    private final List<TurnMetrics> turns = new ArrayList<>();
    private @Nullable TurnMetrics currentTurn = null;
    private long turnStartTimeMs = 0;

    // Failure classification
    private @Nullable String failureType = null;
    private @Nullable String stopReason = null;
    private int finalWorkspaceSize = 0;

    // Final workspace files snapshot (project-relative paths)
    private @Nullable Set<String> finalWorkspaceFiles = null;

    @Override
    public synchronized void recordContextScan(int filesAdded, long timeMs, boolean skipped) {
        this.contextScanFilesAdded = filesAdded;
        this.contextScanTimeMs = timeMs;
        this.contextScanSkipped = skipped;
    }

    @Override
    public synchronized void startTurn() {
        if (currentTurn != null) {
            turns.add(currentTurn);
        }
        currentTurn = new TurnMetrics(++turnCounter);
        turnStartTimeMs = System.currentTimeMillis();
    }

    @Override
    public synchronized void recordToolCall(String toolName) {
        if (currentTurn != null) {
            currentTurn.addToolCall(toolName);
        }
    }

    @Override
    public synchronized void recordFilesAdded(int count) {
        if (currentTurn != null) {
            currentTurn.addFiles(count);
        }
    }

    @Override
    public synchronized void recordFilesAddedPaths(Set<String> paths) {
        if (currentTurn != null && !paths.isEmpty()) {
            currentTurn.addFilePaths(paths);
        }
    }

    @Override
    public synchronized void endTurn() {
        if (currentTurn != null) {
            long turnTimeMs = System.currentTimeMillis() - turnStartTimeMs;
            currentTurn.setTimeMs(turnTimeMs);
            turns.add(currentTurn);
            currentTurn = null;
        }
    }

    @Override
    public synchronized void recordFailure(TaskResult.StopReason reason, int workspaceSize) {
        this.stopReason = reason.toString();
        this.finalWorkspaceSize = workspaceSize;

        // Classify failure type
        this.failureType = switch (reason) {
            case SUCCESS -> null;
            case INTERRUPTED -> "interrupted";
            case LLM_ERROR -> "llm_error";
            case PARSE_ERROR -> "parse_error";
            case APPLY_ERROR -> "apply_error";
            case BUILD_ERROR -> "build_error";
            case LINT_ERROR -> "lint_error";
            case READ_ONLY_EDIT -> "read_only_edit";
            case IO_ERROR -> "io_error";
            case SEARCH_INVALID_ANSWER -> "search_invalid_answer";
            case LLM_ABORTED -> "llm_aborted";
            case TOOL_ERROR -> "tool_error";
        };
    }

    @Override
    public synchronized void recordFinalWorkspaceFiles(Set<String> finalFiles) {
        this.finalWorkspaceFiles = new HashSet<>(finalFiles);
    }

    /**
     * Get the final workspace files that were recorded.
     * Used by BrokkCli to infer the found file.
     */
    public synchronized @Nullable Set<String> getFinalWorkspaceFiles() {
        return finalWorkspaceFiles;
    }

    /**
     * Get the turn history with files added per turn.
     * Used by BrokkCli to determine the last file added.
     */
    public synchronized List<TurnMetrics> getTurns() {
        return new ArrayList<>(turns);
    }

    /**
     * Generate enhanced JSON output with metrics.
     * Maintains backward compatibility by keeping original fields in same order.
     */
    @Override
    public synchronized String toJson(String query, int turns, long elapsedMs, boolean success) {
        // Derive found_files from finalWorkspaceFiles
        List<String> foundFiles = finalWorkspaceFiles != null && !finalWorkspaceFiles.isEmpty()
                ? finalWorkspaceFiles.stream().sorted().toList()
                : List.of();

        var contextScan = new ContextScanInfo(contextScanFilesAdded, contextScanTimeMs, contextScanSkipped);
        var result = new SearchResult(
                query,
                foundFiles,
                turns,
                elapsedMs,
                success,
                contextScan,
                new ArrayList<>(this.turns),
                failureType,
                stopReason,
                finalWorkspaceSize);

        try {
            return AbstractProject.objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize search result", e);
        }
    }

    /**
     * Complete search result with all metrics.
     */
    public record SearchResult(
            String query,
            List<String> found_files,
            int turns,
            long elapsed_ms,
            boolean success,
            ContextScanInfo context_scan,
            List<TurnMetrics> turns_detail,
            @Nullable String failure_type,
            @Nullable String stop_reason,
            int final_workspace_size) {}

    /**
     * Context scan metrics.
     */
    public record ContextScanInfo(int files_added, long scan_time_ms, boolean skipped) {}

    /**
     * Metrics for a single search turn.
     */
    public static class TurnMetrics {
        private final int turn;
        private final List<String> tool_calls = new ArrayList<>();
        private int files_added = 0;
        private final Set<String> files_added_paths = new HashSet<>();
        private long time_ms = 0;

        public TurnMetrics(int turnNumber) {
            this.turn = turnNumber;
        }

        public void addToolCall(String toolName) {
            tool_calls.add(toolName);
        }

        public void addFiles(int count) {
            files_added += count;
        }

        public void addFilePaths(Set<String> paths) {
            files_added_paths.addAll(paths);
        }

        public void setTimeMs(long timeMs) {
            this.time_ms = timeMs;
        }

        // Jackson getters (required for serialization)
        public int getTurn() {
            return turn;
        }

        public List<String> getTool_calls() {
            return tool_calls;
        }

        public int getFiles_added() {
            return files_added;
        }

        public Set<String> getFiles_added_paths() {
            return files_added_paths;
        }

        public long getTime_ms() {
            return time_ms;
        }
    }
}
