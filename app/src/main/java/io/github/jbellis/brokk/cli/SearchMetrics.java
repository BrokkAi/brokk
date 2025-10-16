package io.github.jbellis.brokk.cli;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.jbellis.brokk.AbstractProject;
import io.github.jbellis.brokk.TaskResult;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Tracks metrics for SearchAgent execution in benchmark mode.
 * Collects context scan metrics, per-turn details, and failure classification.
 */
public class SearchMetrics {
    /**
     * No-op implementation for use when metrics tracking is not needed.
     */
    public static final SearchMetrics NO_OP = new SearchMetrics() {
        @Override
        public void recordContextScan(int filesAdded, long timeMs, boolean skipped) {}

        @Override
        public void startTurn(int turnNumber) {}

        @Override
        public void recordToolCall(String toolName) {}

        @Override
        public void recordFilesAdded(int count) {}

        @Override
        public void endTurn(long turnTimeMs) {}

        @Override
        public void recordFailure(TaskResult.StopReason reason, int workspaceSize) {}

        @Override
        public String toJson(String query, String foundFile, int turns, long elapsedMs, boolean success) {
            var result = new SearchResult(
                    query,
                    foundFile,
                    turns,
                    elapsedMs,
                    success,
                    new ContextScanInfo(0, 0, false),
                    List.of(),
                    null,
                    null,
                    0);
            try {
                return AbstractProject.objectMapper.writeValueAsString(result);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize search result", e);
            }
        }
    };

    // Context scan metrics
    private int contextScanFilesAdded = 0;
    private long contextScanTimeMs = 0;
    private boolean contextScanSkipped = false;

    // Per-turn metrics
    private final List<TurnMetrics> turns = new ArrayList<>();
    private @Nullable TurnMetrics currentTurn = null;

    // Failure classification
    private @Nullable String failureType = null;
    private @Nullable String stopReason = null;
    private int finalWorkspaceSize = 0;

    public void recordContextScan(int filesAdded, long timeMs, boolean skipped) {
        this.contextScanFilesAdded = filesAdded;
        this.contextScanTimeMs = timeMs;
        this.contextScanSkipped = skipped;
    }

    public void startTurn(int turnNumber) {
        if (currentTurn != null) {
            turns.add(currentTurn);
        }
        currentTurn = new TurnMetrics(turnNumber);
    }

    public void recordToolCall(String toolName) {
        if (currentTurn != null) {
            currentTurn.addToolCall(toolName);
        }
    }

    public void recordFilesAdded(int count) {
        if (currentTurn != null) {
            currentTurn.addFiles(count);
        }
    }

    public void endTurn(long turnTimeMs) {
        if (currentTurn != null) {
            currentTurn.setTimeMs(turnTimeMs);
            turns.add(currentTurn);
            currentTurn = null;
        }
    }

    public void recordFailure(TaskResult.StopReason reason, int workspaceSize) {
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

    /**
     * Generate enhanced JSON output with metrics.
     * Maintains backward compatibility by keeping original fields in same order.
     */
    public String toJson(String query, String foundFile, int turns, long elapsedMs, boolean success) {
        var contextScan = new ContextScanInfo(contextScanFilesAdded, contextScanTimeMs, contextScanSkipped);
        var result = new SearchResult(
                query,
                foundFile,
                turns,
                elapsedMs,
                success,
                contextScan,
                this.turns,
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
     * Field order matches backward compatibility requirements.
     */
    @JsonPropertyOrder({
        "query",
        "found_file",
        "turns",
        "elapsed_ms",
        "success",
        "context_scan",
        "turns_detail",
        "failure_type",
        "stop_reason",
        "final_workspace_size"
    })
    public record SearchResult(
            String query,
            String found_file,
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
    @JsonPropertyOrder({"turn", "tool_calls", "files_added", "time_ms"})
    public static class TurnMetrics {
        private final int turn;
        private final List<String> tool_calls = new ArrayList<>();
        private int files_added = 0;
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

        public long getTime_ms() {
            return time_ms;
        }
    }
}
