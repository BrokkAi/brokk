package ai.brokk.tools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a tool as destructive (e.g., it performs state-altering or potentially dangerous operations).
 * Tools annotated with this will trigger an approval prompt in the GUI before execution.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Destructive {}
