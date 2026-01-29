package ai.brokk.tools.diagnostics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Top-level container for diagnostic timelines. Contains a list of sessions.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DiagnosticsTimeline(List<SessionTimeline> sessions) {
    public DiagnosticsTimeline {
        // Allow null list to be represented as empty list for convenience in code, but we accept null as valid too.
        if (sessions == null) {
            sessions = List.of();
        }
    }
}
