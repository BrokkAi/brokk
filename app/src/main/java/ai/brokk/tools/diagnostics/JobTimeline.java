package ai.brokk.tools.diagnostics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Timeline information for a single job.
 *
 * Primary tree shape: sessions -> jobs -> phases -> calls -> tools
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record JobTimeline(
        String jobId,
        @Nullable String mode,
        @Nullable Map<String, String> tags,
        @Nullable Long startTime,
        @Nullable Long endTime,
        @Nullable Long durationMs,
        @Nullable ModelConfig modelConfig,
        @Nullable ReasoningOverrides reasoningOverrides,
        List<PhaseTimeline> phases,
        List<CallTimeline> calls,
        @Nullable Map<String, Object> aggregates) {

    /**
     * Lightweight DTO describing the resolved model for a call/job.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static record ModelConfig(String name, @Nullable String reasoning, @Nullable String tier) {}

    /**
     * Job-level overrides for reasoning/temperature that may have been supplied.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static record ReasoningOverrides(
            @Nullable String reasoningLevel,
            @Nullable String reasoningLevelCode,
            @Nullable Double temperature,
            @Nullable Double temperatureCode) {}
}
