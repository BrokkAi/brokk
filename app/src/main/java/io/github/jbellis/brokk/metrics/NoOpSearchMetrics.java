package io.github.jbellis.brokk.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.jbellis.brokk.AbstractProject;
import io.github.jbellis.brokk.TaskResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight no-op SearchMetrics singleton in a neutral package.
 */
public enum NoOpSearchMetrics implements SearchMetrics {
    INSTANCE;

    @Override
    public void recordContextScan(int filesAdded, long timeMs, boolean skipped) {}

    @Override
    public void startTurn() {}

    @Override
    public void recordToolCall(String toolName) {}

    @Override
    public void recordFilesAdded(int count) {}

    @Override
    public void recordFilesAddedPaths(Set<String> paths) {}

    @Override
    public void endTurn() {}

    @Override
    public void recordFailure(TaskResult.StopReason reason, int workspaceSize) {}

    @Override
    public void recordFinalWorkspaceFiles(Set<String> finalFiles) {}

    @Override
    public String toJson(String query, List<String> foundFiles, int turns, long elapsedMs, boolean success) {
        // Produce the same minimal structure used previously by NO_OP
        Map<String, Object> result = new HashMap<>();
        result.put("query", query);
        result.put("found_files", foundFiles);
        result.put("turns", turns);
        result.put("elapsed_ms", elapsedMs);
        result.put("success", success);
        try {
            return AbstractProject.objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            // Fallback to a tiny JSON string if serialization fails
            var filesJson =
                    foundFiles.stream().map(f -> "\"" + f + "\"").collect(java.util.stream.Collectors.joining(","));
            return "{\"query\":\"" + query + "\",\"found_files\":["
                    + filesJson + "],\"turns\":" + turns + ",\"elapsed_ms\":" + elapsedMs
                    + ",\"success\":" + success + "}";
        }
    }
}
