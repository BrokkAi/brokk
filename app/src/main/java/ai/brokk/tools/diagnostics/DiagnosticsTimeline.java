package ai.brokk.tools.diagnostics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Top-level container for diagnostic timelines. Contains a list of sessions.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DiagnosticsTimeline(List<SessionTimeline> sessions) {}
