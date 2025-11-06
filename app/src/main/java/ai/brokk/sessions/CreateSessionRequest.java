package ai.brokk.sessions;

import org.jspecify.annotations.Nullable;

/**
 * Request body for creating a new session.
 */
public record CreateSessionRequest(@Nullable String name) {}
