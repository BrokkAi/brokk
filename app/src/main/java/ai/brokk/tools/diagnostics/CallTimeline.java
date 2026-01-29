package ai.brokk.tools.diagnostics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a single LLM call or tool-invoking call within a job's timeline.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CallTimeline(
        String callId,
        @Nullable String jobId,
        @Nullable String sessionId,
        @Nullable String phaseId,
        @Nullable Long startTime,
        @Nullable Long endTime,
        @Nullable Long durationMs,
        @Nullable JobTimeline.ModelConfig model,
        @Nullable String modelRole,
        @Nullable String promptRaw,
        @Nullable Boolean promptTruncated,
        @Nullable Integer promptTokenCount,
        @Nullable String completionRaw,
        @Nullable Boolean completionTruncated,
        @Nullable Integer completionTokenCount,
        @Nullable String reasoningContent,
        @Nullable String thoughtSignature,
        List<ToolCallTimeline> tools,
        @Nullable Integer attemptIndex,
        @Nullable String status) {

    public CallTimeline {
        if (tools == null) tools = List.of();
    }
}
