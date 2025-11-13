package ai.brokk.testutil;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A class to help provide Brokk-specific assertions.
 */
public final class AssertionHelperUtil {

    private AssertionHelperUtil() {}

    /**
     * Provides a line-ending agnostic string equality assertion.
     */
    public static void assertCodeEquals(String expected, String actual) {
        var cleanExpected = normalizeLineEndings(expected);
        var cleanActual = normalizeLineEndings(actual);
        assertEquals(cleanExpected, cleanActual);
    }

    /**
     * Provides a line-ending agnostic string equality assertion.
     */
    public static void assertCodeEquals(String expected, String actual, String message) {
        var cleanExpected = normalizeLineEndings(expected);
        var cleanActual = normalizeLineEndings(actual);
        assertEquals(cleanExpected, cleanActual, message);
    }

    private static String normalizeLineEndings(String content) {
        return content.replaceAll("\\R", "\n").strip();
    }
}
