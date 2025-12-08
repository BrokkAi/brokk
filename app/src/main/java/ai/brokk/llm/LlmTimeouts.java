package ai.brokk.llm;

import dev.langchain4j.exception.HttpException;
import org.jetbrains.annotations.Nullable;

/**
 * Helpers for detecting LLM timeout conditions.
 *
 * <p>Historically the code relied on scanning exception message text (e.g. looking for "timed out"),
 * which is fragile and locale-dependent. This utility centralizes the logic and treats an HTTP 504
 * status code as the canonical indicator of an upstream LLM timeout.
 *
 * <p>Rationale:
 * - Checking for a specific HTTP status code (504 Gateway Timeout) is robust and not influenced by
 *   provider-specific or localized error messages.
 * - Centralizing the timeout heuristic here allows callers (e.g., {@code ai.brokk.Llm}) to remove
 *   brittle string-matching logic and to benefit from a single, well-documented place to evolve
 *   timeout semantics in the future (for example, adding provider-specific mappings).
 */
public final class LlmTimeouts {

    private LlmTimeouts() {
        // utility
    }

    /**
     * Returns true when the provided throwable represents an LLM timeout condition.
     *
     * <p>We consider an instance of {@link HttpException} with a 504 status code to be a timeout from
     * the upstream LLM service. This avoids fragile checks of exception message text and keeps the
     * retry/circuit-breaker logic deterministic.
     *
     * @param t throwable to inspect
     * @return true if throwable represents a LLM timeout (HTTP 504); false otherwise
     */
    public static boolean isTimeout(@Nullable Throwable t) {
        if (t instanceof HttpException he) {
            return he.statusCode() == 504;
        }
        return false;
    }
}
