package ai.brokk.testutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.IAnalyzer;
import java.util.Collection;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * A class to help provide Brokk-specific assertions.
 */
public final class AssertionHelperUtil {

    private AssertionHelperUtil() {}

    public static void assertCodeEquals(String expected, String actual) {
        assertCodeEquals(expected, actual, null);
    }

    public static void assertCodeEquals(String expected, String actual, @Nullable String message) {
        var cleanExpected = normalizeLineEndings(expected);
        var cleanActual = normalizeLineEndings(actual);
        if (message == null) {
            assertEquals(cleanExpected, cleanActual);
        } else {
            assertEquals(cleanExpected, cleanActual, message);
        }
    }

    public static void assertCodeStartsWith(String fullContent, String expectedPrefix) {
        assertCodeStartsWith(fullContent, expectedPrefix, null);
    }

    public static void assertCodeStartsWith(String fullContent, String expectedPrefix, @Nullable String message) {
        var cleanFullContent = normalizeLineEndings(fullContent);
        var cleanExpectedPrefix = normalizeLineEndings(expectedPrefix);
        assertTrue(
                cleanFullContent.startsWith(cleanExpectedPrefix),
                Objects.requireNonNullElseGet(message, () -> "Expected code starting with " + expectedPrefix));
    }

    public static void assertCodeEndsWith(String fullContent, String expectedSuffix) {
        assertCodeEndsWith(fullContent, expectedSuffix, null);
    }

    public static void assertCodeEndsWith(String fullContent, String expectedSuffix, @Nullable String message) {
        var cleanFullContent = normalizeLineEndings(fullContent);
        var cleanSuffix = normalizeLineEndings(expectedSuffix);
        assertTrue(
                cleanFullContent.endsWith(cleanSuffix),
                Objects.requireNonNullElseGet(message, () -> "Expected code ending with " + expectedSuffix));
    }

    public static void assertCodeContains(String fullContent, String substring) {
        assertCodeContains(fullContent, substring, null);
    }

    public static void assertCodeContains(String fullContent, String substring, @Nullable String message) {
        var cleanFullContent = normalizeLineEndings(fullContent);
        var cleanSubstring = normalizeLineEndings(substring);
        assertTrue(
                cleanFullContent.contains(cleanSubstring),
                Objects.requireNonNullElseGet(message, () -> "Expected code containing " + substring));
    }

    public static int indentOfFirstLine(String code) {
        var firstLine = code.replaceAll("\\R", "\n").split("\n", -1)[0];
        return countLeadingWhitespace(firstLine);
    }

    public static int indentOfSecondNonBlankLine(String code) {
        var lines = code.replaceAll("\\R", "\n").split("\n", -1);
        if (lines.length == 0) {
            return 0;
        }
        for (int i = 1; i < lines.length; i++) {
            var ln = lines[i];
            if (!ln.trim().isEmpty()) {
                return countLeadingWhitespace(ln);
            }
        }
        return countLeadingWhitespace(lines[0]);
    }

    public static int findIndentOfLineIgnoringLeadingWhitespace(String fullContent, String targetLine) {
        var normalized = fullContent.replaceAll("\\R", "\n");
        var lines = normalized.split("\n", -1);
        var target = targetLine.stripLeading();
        for (var ln : lines) {
            if (ln.stripLeading().equals(target)) {
                return countLeadingWhitespace(ln);
            }
        }
        return -1;
    }

    public static void assertLineIndentEqualsIgnoringLeadingWhitespace(
            String fullContent, String targetLine, int expectedIndent, @Nullable String message) {
        int actual = findIndentOfLineIgnoringLeadingWhitespace(fullContent, targetLine);
        String baseMsg = "Expected indent of " + expectedIndent + " for line: [" + targetLine + "] but was " + actual;
        assertTrue(actual >= 0, Objects.requireNonNullElse(message, "Target line not found: " + targetLine));
        assertEquals(expectedIndent, actual, Objects.requireNonNullElse(message, baseMsg));
    }

    public static void assertCodeUnitType(IAnalyzer analyzer, String fqName, CodeUnitType codeUnitType) {
        Collection<CodeUnit> units = analyzer.getDefinitions(fqName);
        assertFalse(units.isEmpty(), "Should find code unit for: " + fqName);

        CodeUnit unit = units.iterator().next();
        assertEquals(codeUnitType, unit.kind(), fqName + " should be of type " + codeUnitType);
    }

    private static String normalizeLineEndings(String content) {
        return content.replaceAll("\\R", "\n").strip();
    }

    private static int countLeadingWhitespace(String st) {
        int i = 0;
        while (i < st.length()) {
            char c = st.charAt(i);
            if (c != ' ' && c != '\t') {
                break;
            }
            i++;
        }
        return i;
    }
}
