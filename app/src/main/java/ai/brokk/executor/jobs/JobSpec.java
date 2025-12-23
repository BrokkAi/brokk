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
        @JsonProperty("scanModel") @Nullable String scanModel,
        @JsonProperty("codeModel") @Nullable String codeModel,
        @JsonProperty("preScan") boolean preScan,
        @JsonProperty("tags") Map<String, String> tags) {

    /**
     * Creates a JobSpec with minimal required fields.
     *
     * <p>This convenience factory uses sensible defaults for optional flags and sets {@code preScan} to {@code false}.</p>
     */
    public static JobSpec of(String taskInput, String plannerModel) {
        return new JobSpec(taskInput, true, true, plannerModel, null, null, false, Map.of());
    }

    /**
     * Creates a JobSpec with all fields, including scanModel and preScan flag.
     */
    public static JobSpec of(
            String taskInput,
            boolean autoCommit,
            boolean autoCompress,
            String plannerModel,
            @Nullable String scanModel,
            @Nullable String codeModel,
            boolean preScan,
            Map<String, String> tags) {
        return new JobSpec(taskInput, autoCommit, autoCompress, plannerModel, scanModel, codeModel, preScan, tags);
    }
}
