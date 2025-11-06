package ai.brokk.sessions;

import java.util.UUID;

/**
 * Summary information for listing sessions.
 */
public record SessionSummary(UUID id, String name, String branch, String worktreePath, int port) {}
