package ai.brokk.testutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * A class to help provide Brokk-specific assertions.
 */
public final class AssertionHelperUtil {

    private AssertionHelperUtil() {}

    /**
     * Provides a line-ending agnostic string equality assertion.
     */
    public static void assertCodeEquals(String expected, String actual) {
        assertCodeEquals(expected, actual, null);
    }

    /**
     * Provides a line-ending agnostic string equality assertion.
     */
    public static void assertCodeEquals(String expected, String actual, @Nullable String message) {
        var cleanExpected = normalizeLineEndings(expected);
        var cleanActual = normalizeLineEndings(actual);
        if (message == null) {
            assertEquals(cleanExpected, cleanActual);
        } else {
            assertEquals(cleanExpected, cleanActual, message);
        }
    }

    /**
     * Provides a line-ending agnostic string starts-with assertion.
     */
    public static void assertCodeStartsWith(String fullContent, String expectedPrefix) {
        assertCodeStartsWith(fullContent, expectedPrefix, null);
    }

    /**
     * Provides a line-ending agnostic string starts-with assertion.
     */
    public static void assertCodeStartsWith(String fullContent, String expectedPrefix, @Nullable String message) {
        var cleanFullContent = normalizeLineEndings(fullContent);
        var cleanExpectedPrefix = normalizeLineEndings(expectedPrefix);
        assertTrue(
                cleanFullContent.startsWith(cleanExpectedPrefix),
                Objects.requireNonNullElseGet(message, () -> "Expected code starting with " + expectedPrefix));
    }

    /**
     * Provides a line-ending agnostic string ends-with assertion.
     */
    public static void assertCodeEndsWith(String fullContent, String expectedSuffix) {
        assertCodeEndsWith(fullContent, expectedSuffix, null);
    }

    /**
     * Provides a line-ending agnostic string ends-wit assertion.
     */
    public static void assertCodeEndsWith(String fullContent, String expectedSuffix, @Nullable String message) {
        var cleanFullContent = normalizeLineEndings(fullContent);
        var cleanSuffix = normalizeLineEndings(expectedSuffix);
        assertTrue(
                cleanFullContent.endsWith(cleanSuffix),
                Objects.requireNonNullElseGet(message, () -> "Expected code ending with " + expectedSuffix));
    }

    /**
     * Provides a line-ending agnostic string substring assertion.
     */
    public static void assertCodeContains(String fullContent, String substring) {
        assertCodeContains(fullContent, substring, null);
    }

    /**
     * Provides a line-ending agnostic string substring assertion.
     */
    public static void assertCodeContains(String fullContent, String substring, @Nullable String message) {
        var cleanFullContent = normalizeLineEndings(fullContent);
        var cleanSubstring = normalizeLineEndings(substring);
        assertTrue(
                cleanFullContent.contains(cleanSubstring),
                Objects.requireNonNullElseGet(message, () -> "Expected code containing " + substring));
    }

    private static String normalizeLineEndings(String content) {
        return content.replaceAll("\\R", "\n").strip();
    }
}
