package io.github.jbellis.brokk.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.jbellis.brokk.AbstractProject;
import io.github.jbellis.brokk.TaskResult;
import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Lightweight no-op SearchMetrics singleton.
 */
public enum NoOpSearchMetrics implements SearchMetrics {
    INSTANCE;

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
    public void recordFoundFile(String file) {}

    @Override
    public @Nullable String getFoundFile() {
        return null;
    }

    @Override
    public String toJson(String query, String foundFile, int turns, long elapsedMs, boolean success) {
        // Produce the same minimal structure used previously by NO_OP
        Map<String, Object> result = new HashMap<>();
        result.put("query", query);
        result.put("found_file", foundFile);
        result.put("turns", turns);
        result.put("elapsed_ms", elapsedMs);
        result.put("success", success);
        try {
            return AbstractProject.objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            // Fallback to a tiny JSON string if serialization fails
            return "{\"query\":\"" + (query == null ? "" : query) + "\",\"found_file\":\""
                    + (foundFile == null ? "" : foundFile) + "\",\"turns\":" + turns + ",\"elapsed_ms\":" + elapsedMs
                    + ",\"success\":" + success + "}";
        }
    }
}
