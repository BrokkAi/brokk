package ai.brokk.executor.jobs;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.jetbrains.annotations.Nullable;

/**
 * Durable status record for one schema-aware custom-agent child run.
 */
public record ChildAgentArtifact(
        @JsonProperty("parentJobId") String parentJobId,
        @JsonProperty("childRunId") String childRunId,
        @JsonProperty("toolCallId") @Nullable String toolCallId,
        @JsonProperty("agentName") String agentName,
        @JsonProperty("responseSchemaName") String responseSchemaName,
        @JsonProperty("status") String status,
        @JsonProperty("validatedResponse") @Nullable JsonNode validatedResponse,
        @JsonProperty("validationError") @Nullable String validationError,
        @JsonProperty("invalidOutputExcerpt") @Nullable String invalidOutputExcerpt,
        @JsonProperty("errorMessage") @Nullable String errorMessage,
        @JsonProperty("elapsedMs") @Nullable Long elapsedMs,
        @JsonProperty("model") @Nullable String model,
        @JsonProperty("cost") @Nullable Double cost,
        @JsonProperty("inputTokens") @Nullable Integer inputTokens,
        @JsonProperty("outputTokens") @Nullable Integer outputTokens) {
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_SCHEMA_INVALID = "schema_invalid";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_TIMEOUT = "timeout";
}
