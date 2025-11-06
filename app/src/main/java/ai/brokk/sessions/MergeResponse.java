package ai.brokk.sessions;

/**
 * Response for merge-to-default operation.
 */
public record MergeResponse(
        String status,
        String mode,
        String defaultBranch,
        String sessionBranch,
        boolean fastForward,
        boolean conflicts,
        String message) {}
