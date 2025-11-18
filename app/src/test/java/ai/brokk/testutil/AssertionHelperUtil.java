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

    // -------------------------
    // Indentation-aware helpers
    // -------------------------

    /**
     * Count the number of leading whitespace characters on a single line.
     */
    private static int leadingWhitespaceCount(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * Returns the indent count (leading whitespace chars) of the first line in the given code string.
     */
    public static int indentOfFirstLine(String code) {
        var firstLine = code.replaceAll("\\R", "\n").split("\n", -1)[0];
        return leadingWhitespaceCount(firstLine);
    }

    /**
     * Returns the indent count (leading whitespace chars) of the first non-blank line after the first line.
     * If none exist, returns the indent of the first line.
     */
    public static int indentOfSecondNonBlankLine(String code) {
        var lines = code.replaceAll("\\R", "\n").split("\n", -1);
        if (lines.length == 0) {
            return 0;
        }
        for (int i = 1; i < lines.length; i++) {
            var ln = lines[i];
            if (!ln.trim().isEmpty()) {
                return leadingWhitespaceCount(ln);
            }
        }
        return leadingWhitespaceCount(lines[0]);
    }

    /**
     * Finds the indent of the first line in fullContent whose text equals targetLine ignoring leading whitespace.
     * Returns -1 if no such line is found.
     */
    public static int findIndentOfLineIgnoringLeadingWhitespace(String fullContent, String targetLine) {
        // Normalize line endings but do NOT strip leading/trailing whitespace of the entire content.
        var normalized = fullContent.replaceAll("\\R", "\n");
        var lines = normalized.split("\n", -1);
        var target = targetLine.stripLeading();
        for (var ln : lines) {
            if (ln.stripLeading().equals(target)) {
                return leadingWhitespaceCount(ln);
            }
        }
        return -1;
    }

    /**
     * Assert that a line equal to targetLine (ignoring leading whitespace) exists in fullContent and
     * that its leading whitespace count equals expectedIndent.
     */
    public static void assertLineIndentEqualsIgnoringLeadingWhitespace(
            String fullContent, String targetLine, int expectedIndent, @Nullable String message) {
        int actual = findIndentOfLineIgnoringLeadingWhitespace(fullContent, targetLine);
        String baseMsg = "Expected indent of " + expectedIndent + " for line: [" + targetLine + "] but was " + actual;
        assertTrue(actual >= 0, Objects.requireNonNullElse(message, "Target line not found: " + targetLine));
        assertEquals(expectedIndent, actual, Objects.requireNonNullElse(message, baseMsg));
    }

    private static String normalizeLineEndings(String content) {
        return content.replaceAll("\\R", "\n").strip();
    }
}
