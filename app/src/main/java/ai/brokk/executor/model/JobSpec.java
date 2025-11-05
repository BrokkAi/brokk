package ai.brokk.executor.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Specifies the details of a job submission.
 *
 * @param type The type of task to execute (ARCHITECT, ASK, or CODE).
 * @param tasks The task specification (either task indices or task text).
 * @param options Execution options like auto-compression and timeout.
 */
public record JobSpec(TaskType type, TaskSpecification tasks, @Nullable JobOptions options) {

    /**
     * Task type enum: ARCHITECT, ASK, or CODE.
     */
    public enum TaskType {
        ARCHITECT,
        ASK,
        CODE
    }

    /**
     * Flexible task specification: either indices or text.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = TaskIndices.class, name = "indices"),
        @JsonSubTypes.Type(value = TaskText.class, name = "text")
    })
    public sealed interface TaskSpecification {}

    /**
     * Task specification by indices.
     * @param indices List of task indices to execute.
     */
    public record TaskIndices(List<Integer> indices) implements TaskSpecification {
        public TaskIndices {
            if (indices.isEmpty()) {
                throw new IllegalArgumentException("indices must not be empty");
            }
        }
    }

    /**
     * Task specification by text.
     * @param text List of task descriptions (user prompts) to execute.
     */
    public record TaskText(List<String> text) implements TaskSpecification {
        public TaskText {
            if (text.isEmpty()) {
                throw new IllegalArgumentException("text must not be empty");
            }
            if (text.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("text entries must not be blank");
            }
        }
    }

    /**
     * Execution options for a job.
     *
     * @param autoCompress Whether to auto-compress history after execution.
     * @param timeoutSec Timeout in seconds; null means no timeout.
     */
    public record JobOptions(boolean autoCompress, @Nullable Integer timeoutSec) {

        /**
         * Validate that timeoutSec is positive if specified.
         */
        public JobOptions {
            if (timeoutSec != null && timeoutSec <= 0) {
                throw new IllegalArgumentException("timeoutSec must be positive, got: " + timeoutSec);
            }
        }

        /**
         * Convenience constructor with defaults.
         * @param autoCompress Whether to auto-compress history.
         * @return JobOptions with no timeout.
         */
        @JsonCreator
        public static JobOptions of(@JsonProperty("autoCompress") boolean autoCompress) {
            return new JobOptions(autoCompress, null);
        }
    }
}
