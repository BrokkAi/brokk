package ai.brokk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a method as potentially blocking or expensive (e.g., uses IAnalyzer, heavy I/O).
 * Static analysis rules should prefer the non-blocking alternative provided.
 *
 * Typical usage:
 * - On ContextFragment.files() and ContextFragment.sources()
 * - On overrides that still perform analyzer work
 */
@Documented
@Retention(java.lang.annotation.RetentionPolicy.CLASS)
@Target({java.lang.annotation.ElementType.METHOD})
public @interface BlockingOperation {
    /**
     * Name of the non-blocking/computed alternative to suggest, e.g. "computedFiles" or "computedSources".
     */
    String nonBlocking();
}
