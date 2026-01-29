package ai.brokk.tools.diagnostics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a session-level timeline which contains zero or more jobs.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SessionTimeline(
        @Nullable String sessionId,
        @Nullable String label,
        List<JobTimeline> jobs) {
    public SessionTimeline {
        if (jobs == null) {
            jobs = List.of();
        }
    }
}
