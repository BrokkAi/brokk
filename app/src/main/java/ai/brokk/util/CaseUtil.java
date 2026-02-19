package ai.brokk.util;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * Utility for string case conversions, leveraging Jackson's naming strategies.
 */
public final class CaseUtil {

    private static final ExposedSnakeCaseStrategy SNAKE_CASE_STRATEGY = new ExposedSnakeCaseStrategy();

    private CaseUtil() {}

    /**
     * Converts a string to snake_case using Jackson's translation logic.
     *
     * @param input The string to convert.
     * @return The snake_case version of the input.
     */
    public static String toSnakeCase(String input) {
        return SNAKE_CASE_STRATEGY.translate(input);
    }

    /**
     * Subclass to expose the protected translate method.
     */
    private static final class ExposedSnakeCaseStrategy extends PropertyNamingStrategies.SnakeCaseStrategy {
        @Override
        public String translate(String input) {
            return super.translate(input);
        }
    }
}
