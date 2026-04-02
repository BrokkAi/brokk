import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A comprehensive test class with various annotations and documentation.
 * This class demonstrates how the analyzer handles:
 * <ul>
 *   <li>Class-level Javadoc</li>
 *   <li>Multiple annotations</li>
 *   <li>Method documentation</li>
 *   <li>Field documentation</li>
 * </ul>
 *
 * @author Test Author
 * @version 1.0
 * @since Java 8
 */
@Deprecated(since = "1.2", forRemoval = true)
@SuppressWarnings({"unchecked", "rawtypes"})
@CustomAnnotation(value = "class-level", priority = 1)
public class AnnotatedClass {

    /**
     * A documented field with annotation.
     * This field stores configuration data.
     *
     * @see #getConfigValue()
     */
    @CustomAnnotation("field-level")
    @Deprecated
    public static final String CONFIG_VALUE = "default";

    /**
     * Private field with minimal documentation.
     */
    private int counter;

    /**
     * Default constructor with comprehensive documentation.
     * Initializes the counter to zero.
     *
     * @throws IllegalStateException if system is not ready
     */
    @CustomAnnotation("constructor")
    public AnnotatedClass() {
        this.counter = 0;
    }

    /**
     * Parameterized constructor.
     *
     * @param initialCounter the initial counter value
     * @throws IllegalArgumentException if counter is negative
     */
    public AnnotatedClass(int initialCounter) {
        if (initialCounter < 0) {
            throw new IllegalArgumentException("Counter cannot be negative");
        }
        this.counter = initialCounter;
    }

    /**
     * Gets the current configuration value.
     * This method returns the static configuration value.
     *
     * @return the configuration value, never null
     * @see #CONFIG_VALUE
     * @deprecated Use {@link #getConfigValueSafe()} instead
     */
    @Deprecated(since = "1.1")
    @CustomAnnotation(value = "method", priority = 2)
    @Override
    public String toString() {
        return "AnnotatedClass{counter=" + counter + ", config=" + CONFIG_VALUE + "}";
    }

    /**
     * A generic method with complex documentation.
     *
     * @param <T> the type parameter
     * @param input the input value
     * @param processor the processing function
     * @return the processed result
     * @throws RuntimeException if processing fails
     */
    @SuppressWarnings("unchecked")
    public <T> T processValue(T input, java.util.function.Function<T, T> processor) {
        return processor.apply(input);
    }

    /**
     * Inner class with its own documentation and annotations.
     * This demonstrates nested class handling.
     */
    @CustomAnnotation("inner-class")
    public static class InnerHelper {

        /**
         * Helper method documentation.
         * @param message the message to process
         * @return processed message
         */
        @CustomAnnotation("inner-method")
        public String help(String message) {
            return "Helper: " + message;
        }
    }
}

/**
 * Custom annotation for testing.
 * Used throughout the test class to verify annotation capture.
 *
 * @author Test Framework
 */
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR})
@Retention(RetentionPolicy.RUNTIME)
@interface CustomAnnotation {
    /**
     * The annotation value.
     * @return the value string
     */
    String value() default "";

    /**
     * Priority level.
     * @return the priority
     */
    int priority() default 0;
}