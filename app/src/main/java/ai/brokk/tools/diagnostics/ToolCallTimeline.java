package ai.brokk.tools.diagnostics;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jetbrains.annotations.Nullable;

/**
 * Represents an individual tool execution requested/recorded as part of a call.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ToolCallTimeline(
        String toolName,
        @Nullable String toolType,
        @Nullable String requestedByCallId,
        @Nullable Long startTime,
        @Nullable Long endTime,
        @Nullable Long durationMs,
        @Nullable Boolean success,
        @Nullable String errorMessage,
        @Nullable String inputSummary,
        @Nullable String outputSummary) {}
