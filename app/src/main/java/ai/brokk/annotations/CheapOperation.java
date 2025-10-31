package ai.brokk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a method override as "cheap" (non-blocking), typically performing only
 * in-memory work without invoking I/O or analyzer computations.
 *
 * Use on overrides to communicate that the base method's @BlockingOperation does not
 * apply for this implementation.
 */
@Documented
@Retention(java.lang.annotation.RetentionPolicy.CLASS)
@Target({java.lang.annotation.ElementType.METHOD})
public @interface CheapOperation {}
