package ai.brokk.sessions;

import org.jspecify.annotations.Nullable;

/**
 * Request body for merging a session branch back into default.
 */
public record MergeRequest(@Nullable String mode, boolean close) {}
