package ai.brokk.difftool.ui.unified;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Headless tests for UnifiedDiffHighlighter utility class. These tests verify the highlighting logic without requiring
 * GUI components.
 */
class UnifiedDiffHighlighterTest {

    @Nested
    @DisplayName("Highlightable Line Detection")
    class HighlightableLineDetection {

        @Test
        @DisplayName("Addition lines are highlightable")
        void additionLinesAreHighlightable() {
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine("+added line"));
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine("+"));
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine("+ some content"));
        }

        @Test
        @DisplayName("Deletion lines are highlightable")
        void deletionLinesAreHighlightable() {
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine("-deleted line"));
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine("-"));
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine("- some content"));
        }

        @Test
        @DisplayName("Hunk header lines are highlightable")
        void hunkHeaderLinesAreHighlightable() {
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine("@@ -1,5 +1,6 @@"));
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine("@@ -10,20 +15,25 @"));
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine("@@"));
        }

        @Test
        @DisplayName("Context lines are not highlightable")
        void contextLinesAreNotHighlightable() {
            assertFalse(UnifiedDiffHighlighter.isHighlightableLine(" context line"));
            assertFalse(UnifiedDiffHighlighter.isHighlightableLine(" "));
            assertFalse(UnifiedDiffHighlighter.isHighlightableLine("  indented"));
        }

        @Test
        @DisplayName("Empty lines are not highlightable")
        void emptyLinesAreNotHighlightable() {
            assertFalse(UnifiedDiffHighlighter.isHighlightableLine(""));
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "regular line",
                    "123 numeric",
                    "!exclamation",
                    "#hash",
                    "*asterisk",
                    "=equals",
                    "~tilde",
                    ">greater than",
                    "<less than"
                })
        @DisplayName("Lines with random prefixes are not highlightable")
        void linesWithRandomPrefixesAreNotHighlightable(String line) {
            assertFalse(
                    UnifiedDiffHighlighter.isHighlightableLine(line),
                    String.format("Line starting with '%c' should not be highlightable", line.charAt(0)));
        }

        @Test
        @DisplayName("Single @ is not a hunk header")
        void singleAtIsNotHunkHeader() {
            assertFalse(UnifiedDiffHighlighter.isHighlightableLine("@ single at"));
            assertFalse(UnifiedDiffHighlighter.isHighlightableLine("@author javadoc"));
        }

        @Test
        @DisplayName("Lines starting with @ but not @@ are not highlightable")
        void linesStartingWithAtButNotDoubleAtAreNotHighlightable() {
            assertFalse(UnifiedDiffHighlighter.isHighlightableLine("@param something"));
            assertFalse(UnifiedDiffHighlighter.isHighlightableLine("@return value"));
            assertFalse(UnifiedDiffHighlighter.isHighlightableLine("@ annotation"));
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "+",
                    "+ ",
                    "+line",
                    "+  indented addition",
                    "-",
                    "- ",
                    "-line",
                    "-  indented deletion",
                    "@@",
                    "@@ header",
                    "@@ -1,1 +1,1 @@"
                })
        @DisplayName("Valid diff lines are highlightable")
        void validDiffLinesAreHighlightable(String line) {
            assertTrue(
                    UnifiedDiffHighlighter.isHighlightableLine(line),
                    String.format("Valid diff line '%s' should be highlightable", line));
        }

        @ParameterizedTest
        @ValueSource(strings = {" ", "  ", " context", "", "no prefix", "123", "!", "#", "@ single"})
        @DisplayName("Non-diff lines are not highlightable")
        void nonDiffLinesAreNotHighlightable(String line) {
            assertFalse(
                    UnifiedDiffHighlighter.isHighlightableLine(line),
                    String.format("Non-diff line '%s' should not be highlightable", line));
        }
    }

    @Nested
    @DisplayName("Edge Cases and Robustness")
    class EdgeCasesAndRobustness {

        @Test
        @DisplayName("Very long addition line")
        void veryLongAdditionLine() {
            String longLine = "+" + "x".repeat(10000);
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine(longLine));
        }

        @Test
        @DisplayName("Very long deletion line")
        void veryLongDeletionLine() {
            String longLine = "-" + "x".repeat(10000);
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine(longLine));
        }

        @Test
        @DisplayName("Line with only prefix characters")
        void lineWithOnlyPrefixCharacters() {
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine("+"));
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine("-"));
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine("@@"));
            assertFalse(UnifiedDiffHighlighter.isHighlightableLine(" "));
        }

        @Test
        @DisplayName("Unicode characters in diff lines")
        void unicodeCharactersInDiffLines() {
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine("+\u4E2D\u6587")); // Chinese
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine("-\u0645\u0631\u062D\u0628\u0627")); // Arabic
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine("+\uD83D\uDE00")); // Emoji
            assertFalse(UnifiedDiffHighlighter.isHighlightableLine(" \u4E2D\u6587")); // Context with Chinese
        }

        @Test
        @DisplayName("Special whitespace characters")
        void specialWhitespaceCharacters() {
            // Tab, non-breaking space, etc.
            assertFalse(UnifiedDiffHighlighter.isHighlightableLine("\t")); // Tab
            assertFalse(UnifiedDiffHighlighter.isHighlightableLine("\u00A0")); // Non-breaking space
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine("+\t")); // Addition with tab
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine("-\u00A0")); // Deletion with nbsp
        }

        @Test
        @DisplayName("Mixed valid and invalid patterns")
        void mixedValidAndInvalidPatterns() {
            // These should all be evaluated based on their first character
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine("+-mixed"));
            assertFalse(UnifiedDiffHighlighter.isHighlightableLine(" +not addition"));
            // Note: "@@@ three at" starts with "@@" so it's highlightable
            assertTrue(UnifiedDiffHighlighter.isHighlightableLine("@@@ three at"));
        }
    }
}
