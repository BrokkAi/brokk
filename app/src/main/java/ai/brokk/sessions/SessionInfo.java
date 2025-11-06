package ai.brokk.sessions;

import java.nio.file.Path;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Information about an active headless executor session.
 */
public record SessionInfo(
        UUID id,
        String name,
        Path worktreePath,
        String branch,
        int port,
        String authToken,
        Process process,
        long created,
        long lastUpdated) {

    public SessionSummary toSummary() {
        return new SessionSummary(id, name, branch, worktreePath.toString(), port);
    }

    public CreateSessionResponse toResponse() {
        return new CreateSessionResponse(id, name, branch, worktreePath.toString(), port, authToken);
    }
}
