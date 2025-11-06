package ai.brokk.sessions;

import org.jspecify.annotations.Nullable;

/**
 * Response from prompt forwarding containing the created job ID.
 */
public record PromptResponse(String jobId, @Nullable String status) {}
