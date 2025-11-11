package ai.brokk.executor.jobs;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Represents the input specification for a job.
 * This is the request payload and is persisted to meta.json for audit/replay.
 *
 * <p>The {@code plannerModel} field is required by the API and is used for ASK and ARCHITECT jobs.
 * The {@code codeModel} field is optional and, when supplied, is used for CODE and ARCHITECT jobs.</p>
 */
public record JobSpec(
        @JsonProperty("taskInput") String taskInput,
        @JsonProperty("autoCommit") boolean autoCommit,
        @JsonProperty("autoCompress") boolean autoCompress,
        @JsonProperty("plannerModel") String plannerModel,
        @JsonProperty("codeModel") @Nullable String codeModel,
        @JsonProperty("tags") Map<String, String> tags) {

    /**
     * Creates a JobSpec with minimal required fields.
     */
    public static JobSpec of(String taskInput, String plannerModel) {
        return new JobSpec(taskInput, true, true, plannerModel, null, Map.of());
    }

    /**
     * Creates a JobSpec with all fields.
     */
    public static JobSpec of(
            String taskInput,
            boolean autoCommit,
            boolean autoCompress,
            String plannerModel,
            @Nullable String codeModel,
            Map<String, String> tags) {
        return new JobSpec(taskInput, autoCommit, autoCompress, plannerModel, codeModel, tags);
    }
}
