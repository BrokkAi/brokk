package io.github.jbellis.brokk.cli;

import io.github.jbellis.brokk.TaskResult;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
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
            return String.format(
                    "{\"query\": \"%s\", \"found_file\": \"%s\", \"turns\": %d, \"elapsed_ms\": %d, \"success\": %s}",
                    escapeJson(query), escapeJson(foundFile), turns, elapsedMs, success);
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
        var json = new StringBuilder();
        json.append("{");

        // Original fields first (backward compatibility)
        json.append("\"query\": ").append(escapeJson(query)).append(", ");
        json.append("\"found_file\": ").append(escapeJson(foundFile)).append(", ");
        json.append("\"turns\": ").append(turns).append(", ");
        json.append("\"elapsed_ms\": ").append(elapsedMs).append(", ");
        json.append("\"success\": ").append(success);

        // Context scan metrics
        json.append(", \"context_scan\": {");
        json.append("\"files_added\": ").append(contextScanFilesAdded).append(", ");
        json.append("\"scan_time_ms\": ").append(contextScanTimeMs).append(", ");
        json.append("\"skipped\": ").append(contextScanSkipped);
        json.append("}");

        // Per-turn details
        json.append(", \"turns_detail\": [");
        json.append(this.turns.stream().map(TurnMetrics::toJson).collect(Collectors.joining(", ")));
        json.append("]");

        // Failure classification
        json.append(", \"failure_type\": ");
        if (failureType == null) {
            json.append("null");
        } else {
            json.append("\"").append(failureType).append("\"");
        }
        json.append(", \"stop_reason\": \"").append(stopReason).append("\"");
        json.append(", \"final_workspace_size\": ").append(finalWorkspaceSize);

        json.append("}");
        return json.toString();
    }

    private static String escapeJson(String str) {
        return "\""
                + str.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t")
                + "\"";
    }

    /**
     * Metrics for a single search turn.
     */
    public static class TurnMetrics {
        private final int turnNumber;
        private final List<String> toolCalls = new ArrayList<>();
        private int filesAdded = 0;
        private long timeMs = 0;

        public TurnMetrics(int turnNumber) {
            this.turnNumber = turnNumber;
        }

        public void addToolCall(String toolName) {
            toolCalls.add(toolName);
        }

        public void addFiles(int count) {
            filesAdded += count;
        }

        public void setTimeMs(long timeMs) {
            this.timeMs = timeMs;
        }

        public String toJson() {
            var json = new StringBuilder();
            json.append("{");
            json.append("\"turn\": ").append(turnNumber).append(", ");
            json.append("\"tool_calls\": [");
            json.append(toolCalls.stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(", ")));
            json.append("], ");
            json.append("\"files_added\": ").append(filesAdded).append(", ");
            json.append("\"time_ms\": ").append(timeMs);
            json.append("}");
            return json.toString();
        }
    }
}
