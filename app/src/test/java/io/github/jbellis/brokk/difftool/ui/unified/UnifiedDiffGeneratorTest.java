package io.github.jbellis.brokk.difftool.ui.unified;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.difftool.ui.BufferSource;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Headless tests for UnifiedDiffGenerator focusing on pure functions and edge cases. Tests parsing logic and plain text
 * generation without requiring GUI components.
 */
class UnifiedDiffGeneratorTest {

    @Nested
    @DisplayName("Hunk Header Parsing")
    class HunkHeaderParsing {

        @Test
        @DisplayName("Parse normal hunk header")
        void parseNormalHunkHeader() {
            // Use reflection to access private method
            var result = parseHunkHeaderWithCounts("@@ -10,5 +20,7 @@");

            assertNotNull(result);
            assertEquals(10, result[0], "Left start should be 10");
            assertEquals(20, result[1], "Right start should be 20");
            assertEquals(5, result[2], "Left count should be 5");
            assertEquals(7, result[3], "Right count should be 7");
            assertEquals(0, result[4], "Should not be a new file");
        }

        @Test
        @DisplayName("Parse new file hunk header")
        void parseNewFileHunkHeader() {
            var result = parseHunkHeaderWithCounts("@@ -0,0 +1,10 @@");

            assertNotNull(result);
            assertEquals(0, result[0], "Left start should be 0");
            assertEquals(1, result[1], "Right start should be 1");
            assertEquals(0, result[2], "Left count should be 0");
            assertEquals(10, result[3], "Right count should be 10");
            assertEquals(1, result[4], "Should be marked as new file");
        }

        @Test
        @DisplayName("Parse deleted file hunk header")
        void parseDeletedFileHunkHeader() {
            var result = parseHunkHeaderWithCounts("@@ -1,10 +0,0 @@");

            assertNotNull(result);
            assertEquals(1, result[0], "Left start should be 1");
            assertEquals(0, result[1], "Right start should be 0");
            assertEquals(10, result[2], "Left count should be 10");
            assertEquals(0, result[3], "Right count should be 0");
            assertEquals(0, result[4], "Should not be marked as new file (it's deleted)");
        }

        @Test
        @DisplayName("Parse hunk header without counts")
        void parseHunkHeaderWithoutCounts() {
            var result = parseHunkHeaderWithCounts("@@ -5 +10 @@");

            assertNotNull(result);
            assertEquals(5, result[0], "Left start should be 5");
            assertEquals(10, result[1], "Right start should be 10");
            assertEquals(1, result[2], "Default count should be 1");
            assertEquals(1, result[3], "Default count should be 1");
        }

        @ParameterizedTest
        @CsvSource({
            "'@@ -1,5 +1,6 @@', 1, 1, 5, 6, 0", // Standard
            "'@@ -100,20 +150,30 @@', 100, 150, 20, 30, 0", // Large numbers
            "'@@ -1,1 +1,1 @@', 1, 1, 1, 1, 0", // Single line
            "'@@ -0,0 +1,100 @@', 0, 1, 0, 100, 1", // New file large
        })
        @DisplayName("Parse various valid hunk headers")
        void parseVariousValidHunkHeaders(
                String header, int leftStart, int rightStart, int leftCount, int rightCount, int isNewFile) {
            var result = parseHunkHeaderWithCounts(header);

            assertEquals(leftStart, result[0]);
            assertEquals(rightStart, result[1]);
            assertEquals(leftCount, result[2]);
            assertEquals(rightCount, result[3]);
            assertEquals(isNewFile, result[4]);
        }

        @ParameterizedTest
        @ValueSource(
                strings = {"invalid header", "@@ malformed @@", "@@", "", "@@ -abc +def @@", "not a header at all"})
        @DisplayName("Parse malformed headers returns fallback")
        void parseMalformedHeadersReturnsFallback(String malformedHeader) {
            var result = parseHunkHeaderWithCounts(malformedHeader);

            assertNotNull(result, "Should return fallback, not null");
            assertEquals(1, result[0], "Fallback left start should be 1");
            assertEquals(1, result[1], "Fallback right start should be 1");
            assertEquals(1, result[2], "Fallback left count should be 1");
            assertEquals(1, result[3], "Fallback right count should be 1");
            assertEquals(0, result[4], "Fallback isNewFile should be 0");
        }

        @Test
        @DisplayName("Parse malformed header does not throw exception")
        void parseMalformedHeaderDoesNotThrow() {
            assertDoesNotThrow(() -> parseHunkHeaderWithCounts("completely invalid @@ stuff"));
        }

        /** Helper method to access the private parseHunkHeaderWithCounts method via reflection. */
        private int[] parseHunkHeaderWithCounts(String hunkHeader) {
            try {
                var method = UnifiedDiffGenerator.class.getDeclaredMethod("parseHunkHeaderWithCounts", String.class);
                method.setAccessible(true);
                return (int[]) method.invoke(null, hunkHeader);
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke parseHunkHeaderWithCounts", e);
            }
        }
    }

    @Nested
    @DisplayName("Plain Text Generation")
    class PlainTextGeneration {

        @Test
        @DisplayName("Generate unified diff with LF line endings")
        void generateUnifiedDiffWithLF() {
            List<String> leftLines = Arrays.asList("line 1", "line 2", "line 3");
            List<String> rightLines = Arrays.asList("line 1", "modified line 2", "line 3");

            var leftSource = new BufferSource.StringSource(String.join("\n", leftLines), "left.txt");
            var rightSource = new BufferSource.StringSource(String.join("\n", rightLines), "right.txt");

            var document = UnifiedDiffGenerator.generateUnifiedDiff(
                    leftSource, rightSource, UnifiedDiffDocument.ContextMode.FULL_CONTEXT);

            assertNotNull(document);
            var diffLines = document.getFilteredLines();

            // Verify diff lines contain expected changes
            boolean foundDeletion = diffLines.stream()
                    .anyMatch(line -> line.getType() == UnifiedDiffDocument.LineType.DELETION
                            && line.getContent().contains("line 2"));
            boolean foundAddition = diffLines.stream()
                    .anyMatch(line -> line.getType() == UnifiedDiffDocument.LineType.ADDITION
                            && line.getContent().contains("modified line 2"));

            assertTrue(foundDeletion, "Should contain deleted line");
            assertTrue(foundAddition, "Should contain added line");
        }

        @Test
        @DisplayName("Generate unified diff with standard context")
        void generateUnifiedDiffWithStandardContext() {
            List<String> leftLines = Arrays.asList("line 1", "line 2", "line 3", "line 4", "line 5");
            List<String> rightLines = Arrays.asList("line 1", "modified", "line 3", "line 4", "line 5");

            var leftSource = new BufferSource.StringSource(String.join("\n", leftLines), "left.txt");
            var rightSource = new BufferSource.StringSource(String.join("\n", rightLines), "right.txt");

            var document = UnifiedDiffGenerator.generateUnifiedDiff(
                    leftSource, rightSource, UnifiedDiffDocument.ContextMode.STANDARD_3_LINES);

            assertNotNull(document);
            var diffLines = document.getFilteredLines();

            // Should have hunk header
            boolean hasHeader =
                    diffLines.stream().anyMatch(line -> line.getType() == UnifiedDiffDocument.LineType.HEADER);
            assertTrue(hasHeader, "Should contain hunk header");
        }

        @Test
        @DisplayName("Generate unified diff with no differences")
        void generateUnifiedDiffWithNoDifferences() {
            List<String> sameLines = Arrays.asList("line 1", "line 2", "line 3");

            var leftSource = new BufferSource.StringSource(String.join("\n", sameLines), "left.txt");
            var rightSource = new BufferSource.StringSource(String.join("\n", sameLines), "right.txt");

            var document = UnifiedDiffGenerator.generateUnifiedDiff(
                    leftSource, rightSource, UnifiedDiffDocument.ContextMode.FULL_CONTEXT);

            assertNotNull(document);
        }

        @Test
        @DisplayName("Generate unified diff with empty files")
        void generateUnifiedDiffWithEmptyFiles() {
            List<String> rightLines = Arrays.asList("new line 1", "new line 2");

            var leftSource = new BufferSource.StringSource("", "left.txt");
            var rightSource = new BufferSource.StringSource(String.join("\n", rightLines), "right.txt");

            var document = UnifiedDiffGenerator.generateUnifiedDiff(
                    leftSource, rightSource, UnifiedDiffDocument.ContextMode.FULL_CONTEXT);

            assertNotNull(document);

            // Should have additions
            boolean hasAdditions = document.getFilteredLines().stream()
                    .anyMatch(line -> line.getType() == UnifiedDiffDocument.LineType.ADDITION);
            assertTrue(hasAdditions, "Should contain added lines");
        }
    }

    @Nested
    @DisplayName("Edge Cases and Robustness")
    class EdgeCasesAndRobustness {

        @Test
        @DisplayName("Empty hunk header")
        void emptyHunkHeader() {
            assertDoesNotThrow(() -> {
                var result = parseHunkHeaderWithCountsReflection("");
                assertNotNull(result);
                assertEquals(5, result.length);
            });
        }

        @Test
        @DisplayName("Null safety - empty buffer sources")
        void nullSafetyEmptyBufferSources() {
            var emptyLeft = new BufferSource.StringSource("", "empty.txt");
            var emptyRight = new BufferSource.StringSource("", "empty.txt");

            assertDoesNotThrow(() -> {
                var document = UnifiedDiffGenerator.generateUnifiedDiff(
                        emptyLeft, emptyRight, UnifiedDiffDocument.ContextMode.FULL_CONTEXT);
                assertNotNull(document);
            });
        }

        private int[] parseHunkHeaderWithCountsReflection(String header) {
            try {
                var method = UnifiedDiffGenerator.class.getDeclaredMethod("parseHunkHeaderWithCounts", String.class);
                method.setAccessible(true);
                return (int[]) method.invoke(null, header);
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke parseHunkHeaderWithCounts", e);
            }
        }
    }
}
