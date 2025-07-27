package io.github.jbellis.brokk.util;

/**
 * Implemented by tasks that know roughly how many LLM tokens they will consume.
 * {@link AdaptiveExecutor.RateLimitedExecutor} inspects this at scheduling time
 * and blocks until the shared token bucket allows the task to run.
 */
public interface TokenAware {
    int tokens();
}
