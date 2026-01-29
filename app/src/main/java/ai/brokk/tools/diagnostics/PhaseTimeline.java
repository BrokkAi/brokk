package ai.brokk.tools.diagnostics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a named/typed phase within a job (e.g., planning, pre-scan, execution).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PhaseTimeline(
        String phaseId,
        @Nullable String type,
        @Nullable String label,
        @Nullable Long startTime,
        @Nullable Long endTime,
        @Nullable Long durationMs,
        List<String> callIds,
        @Nullable Map<String, Object> metrics) {

    public PhaseTimeline {
        if (callIds == null) callIds = List.of();
    }
}
