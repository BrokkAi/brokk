package ai.brokk.llm;

import dev.langchain4j.exception.HttpException;

/**
 * Helpers for detecting LLM timeout conditions.
 *
 * <p>Historically the code relied on scanning exception message text (e.g. looking for "timed out"),
 * which is fragile and locale-dependent. This utility centralizes the logic and treats an HTTP 504
 * status code as the canonical indicator of an upstream LLM timeout.
 */
public final class LlmTimeouts {

    private LlmTimeouts() {
        // utility
    }

    /**
     * Returns true when the provided throwable represents an LLM timeout condition.
     *
     * <p>We consider an instance of {@link HttpException} with a 504 status code to be a timeout from
     * the upstream LLM service.
     *
     * @param t throwable to inspect
     * @return true if throwable represents a LLM timeout (HTTP 504); false otherwise
     */
    public static boolean isTimeout(Throwable t) {
        if (t instanceof HttpException he) {
            return he.statusCode() == 504;
        }
        return false;
    }
}
