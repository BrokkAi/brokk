package ai.brokk.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that a method, constructor, or class is safe to call from the Event Dispatch Thread (EDT)
 * despite using synchronization. This suppresses EDT-synchronized violation warnings.
 *
 * <p>Use this annotation when:
 * <ul>
 *   <li>A method/constructor uses synchronization but is guaranteed to be non-blocking (e.g., cached values)</li>
 *   <li>A method/constructor has minimal synchronized sections that won't cause UI freezes</li>
 *   <li>You've verified the synchronization is safe for EDT usage</li>
 * </ul>
 *
 * <p>Example:
 * <pre>
 * {@literal @}EdtSafe("Caches theme on first call, subsequent calls are fast")
 * public synchronized boolean isDarkTheme() {
 *     if (cachedTheme == null) {
 *         cachedTheme = computeTheme();
 *     }
 *     return cachedTheme.isDark();
 * }
 *
 * {@literal @}EdtSafe("Only copies small map, no blocking operations")
 * public Builder(Registry base) {
 *     synchronized (base.map) {
 *         this.entries = new LinkedHashMap<>(base.map);
 *     }
 * }
 * </pre>
 */
@Documented
@Retention(RetentionPolicy.CLASS) // Preserved in bytecode for static analysis
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE})
public @interface EdtSafe {
    /**
     * Optional explanation of why this method/constructor/class is safe for EDT.
     */
    String value() default "";
}
