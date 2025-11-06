package ai.brokk.sessions;

import java.util.UUID;

/**
 * Response for session creation and detail retrieval.
 */
public record CreateSessionResponse(
        UUID id, String name, String branch, String worktreePath, int port, String authToken) {}
