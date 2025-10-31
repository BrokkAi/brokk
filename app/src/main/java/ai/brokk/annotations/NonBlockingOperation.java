package ai.brokk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a method as a non-blocking alternative for a corresponding @BlockingOperation.
 * Example: ContextFragment.ComputedFragment.computedFiles(), computedSources().
 */
@Documented
@Retention(java.lang.annotation.RetentionPolicy.CLASS)
@Target({java.lang.annotation.ElementType.METHOD})
public @interface NonBlockingOperation {}
