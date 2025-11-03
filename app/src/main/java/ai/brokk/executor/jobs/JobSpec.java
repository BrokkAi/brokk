package ai.brokk.executor.jobs;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Represents the input specification for a job.
 * This is the request payload and is persisted to meta.json for audit/replay.
 */
public record JobSpec(
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("taskInput") String taskInput,
        @JsonProperty("autoCommit") boolean autoCommit,
        @JsonProperty("autoCompress") boolean autoCompress,
        @JsonProperty("tags") Map<String, String> tags) {

    /**
     * Creates a JobSpec with minimal required fields.
     */
    public static JobSpec of(String sessionId, String taskInput) {
        return new JobSpec(sessionId, taskInput, true, true, Map.of());
    }

    /**
     * Creates a JobSpec with all fields.
     */
    public static JobSpec of(
            String sessionId, String taskInput, boolean autoCommit, boolean autoCompress, Map<String, String> tags) {
        return new JobSpec(sessionId, taskInput, autoCommit, autoCompress, tags);
    }
}
