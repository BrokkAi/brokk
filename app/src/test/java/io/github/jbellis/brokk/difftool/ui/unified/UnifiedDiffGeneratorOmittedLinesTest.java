package io.github.jbellis.brokk.difftool.ui.unified;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.difftool.ui.BufferSource;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test that UnifiedDiffGenerator properly creates OMITTED_LINES indicators when there are gaps between hunks in
 * STANDARD_3_LINES mode.
 */
class UnifiedDiffGeneratorOmittedLinesTest {

    @Test
    @DisplayName("Test OMITTED_LINES generation for gaps between hunks")
    void testOmittedLinesGeneration() {
        // Create a mock BufferSource with gaps that should generate OMITTED_LINES
        var leftLines = createLinesWithGaps();
        var rightLines = createModifiedLinesWithGaps();

        // Create BufferSources using the sealed record implementations
        var leftSource = new BufferSource.StringSource(String.join("\n", leftLines), "left.txt");
        var rightSource = new BufferSource.StringSource(String.join("\n", rightLines), "right.txt");

        // Generate unified diff in STANDARD_3_LINES mode
        var unifiedDocument = UnifiedDiffGenerator.generateUnifiedDiff(
                leftSource, rightSource, UnifiedDiffDocument.ContextMode.STANDARD_3_LINES);

        var diffLines = unifiedDocument.getFilteredLines();

        // Verify that OMITTED_LINES are present
        boolean hasOmittedLines =
                diffLines.stream().anyMatch(line -> line.getType() == UnifiedDiffDocument.LineType.OMITTED_LINES);

        assertTrue(hasOmittedLines, "STANDARD_3_LINES mode should generate OMITTED_LINES for gaps between hunks");

        // Print the diff for debugging
        System.out.println("Generated diff lines:");
        for (int i = 0; i < diffLines.size(); i++) {
            var line = diffLines.get(i);
            System.out.printf(
                    "%d: %s (left=%d, right=%d) - %s%n",
                    i,
                    line.getType(),
                    line.getLeftLineNumber(),
                    line.getRightLineNumber(),
                    line.getContent()
                            .substring(0, Math.min(30, line.getContent().length())));
        }
    }

    @Test
    @DisplayName("Test FULL_CONTEXT mode does not show OMITTED_LINES")
    void testFullContextNoOmittedLines() {
        var leftLines = createLinesWithGaps();
        var rightLines = createModifiedLinesWithGaps();

        var leftSource = new BufferSource.StringSource(String.join("\n", leftLines), "left.txt");
        var rightSource = new BufferSource.StringSource(String.join("\n", rightLines), "right.txt");

        // Generate unified diff in FULL_CONTEXT mode
        var unifiedDocument = UnifiedDiffGenerator.generateUnifiedDiff(
                leftSource, rightSource, UnifiedDiffDocument.ContextMode.FULL_CONTEXT);

        var diffLines = unifiedDocument.getFilteredLines();

        // Verify that OMITTED_LINES are NOT present in FULL_CONTEXT mode
        boolean hasOmittedLines =
                diffLines.stream().anyMatch(line -> line.getType() == UnifiedDiffDocument.LineType.OMITTED_LINES);

        assertFalse(hasOmittedLines, "FULL_CONTEXT mode should not generate OMITTED_LINES");
    }

    /**
     * Create test content with lines that will have gaps when using 3-line context. We need enough lines between
     * changes to create gaps in 3-line context mode.
     */
    private List<String> createLinesWithGaps() {
        var lines = new java.util.ArrayList<String>();

        // First change area (lines 1-10)
        for (int i = 1; i <= 10; i++) {
            if (i == 5) {
                lines.add("line " + i + " - will be changed");
            } else {
                lines.add("line " + i);
            }
        }

        // Gap of many unchanged lines (lines 11-89)
        for (int i = 11; i <= 89; i++) {
            lines.add("line " + i + " - unchanged");
        }

        // Second change area (lines 90-100)
        for (int i = 90; i <= 100; i++) {
            if (i == 95) {
                lines.add("line " + i + " - will be changed");
            } else {
                lines.add("line " + i);
            }
        }

        return lines;
    }

    /** Create modified content that will generate a diff with gaps. */
    private List<String> createModifiedLinesWithGaps() {
        var lines = new java.util.ArrayList<String>();

        // First change area (lines 1-10)
        for (int i = 1; i <= 10; i++) {
            if (i == 5) {
                lines.add("line " + i + " - MODIFIED"); // Changed
            } else {
                lines.add("line " + i);
            }
        }

        // Gap of many unchanged lines (lines 11-89) - exactly the same
        for (int i = 11; i <= 89; i++) {
            lines.add("line " + i + " - unchanged");
        }

        // Second change area (lines 90-100)
        for (int i = 90; i <= 100; i++) {
            if (i == 95) {
                lines.add("line " + i + " - ALSO MODIFIED"); // Changed
            } else {
                lines.add("line " + i);
            }
        }

        return lines;
    }

    @Test
    @DisplayName("Test new file line number display shows empty left column")
    void testNewFileLineNumbers() {
        // Create a new file scenario: empty left source, content in right source
        var leftLines = List.<String>of(); // Empty file (new file case)
        var rightLines = List.of(
                "package io.github.jbellis.brokk;",
                "",
                "import java.util.List;",
                "import org.junit.jupiter.api.Test;",
                "",
                "public class NewClass {",
                "    public void newMethod() {",
                "        System.out.println(\"Hello World\");",
                "    }",
                "}");

        var leftSource = new BufferSource.StringSource(String.join("\n", leftLines), "NewClass.java");
        var rightSource = new BufferSource.StringSource(String.join("\n", rightLines), "NewClass.java");

        // Generate unified diff
        var unifiedDocument = UnifiedDiffGenerator.generateUnifiedDiff(
                leftSource, rightSource, UnifiedDiffDocument.ContextMode.STANDARD_3_LINES);

        var diffLines = unifiedDocument.getFilteredLines();

        // Print the diff for debugging
        System.out.println("New file diff lines:");
        for (int i = 0; i < diffLines.size(); i++) {
            var line = diffLines.get(i);
            System.out.printf(
                    "%d: %s (left=%d, right=%d) - %s%n",
                    i,
                    line.getType(),
                    line.getLeftLineNumber(),
                    line.getRightLineNumber(),
                    line.getContent()
                            .substring(0, Math.min(30, line.getContent().length())));
        }

        // Verify that addition lines have proper right line numbers
        var additionLines = diffLines.stream()
                .filter(line -> line.getType() == UnifiedDiffDocument.LineType.ADDITION)
                .toList();

        assertFalse(additionLines.isEmpty(), "New file should have addition lines");

        // Verify that for new files, deletion lines (if any) have leftLineNumber = 0
        var deletionLines = diffLines.stream()
                .filter(line -> line.getType() == UnifiedDiffDocument.LineType.DELETION)
                .toList();

        for (var deletionLine : deletionLines) {
            assertEquals(
                    0,
                    deletionLine.getLeftLineNumber(),
                    "New file deletion lines should have leftLineNumber = 0 to indicate no left column display");
        }

        // Verify that addition lines have proper sequential right line numbers
        int expectedRightLine = 1;
        for (var additionLine : additionLines) {
            assertEquals(
                    expectedRightLine,
                    additionLine.getRightLineNumber(),
                    "Addition line should have correct right line number");
            assertEquals(-1, additionLine.getLeftLineNumber(), "Addition lines should have leftLineNumber = -1");
            expectedRightLine++;
        }
    }

    @Test
    @DisplayName("Test context mode switching actually changes displayed content")
    void testContextModeSwitching() {
        // Create content with gaps that will generate OMITTED_LINES
        var leftLines = createLinesWithGaps();
        var rightLines = createModifiedLinesWithGaps();

        var leftSource = new BufferSource.StringSource(String.join("\n", leftLines), "test.txt");
        var rightSource = new BufferSource.StringSource(String.join("\n", rightLines), "test.txt");

        // Generate unified diff document with STANDARD_3_LINES mode
        var document = UnifiedDiffGenerator.generateUnifiedDiff(
                leftSource, rightSource, UnifiedDiffDocument.ContextMode.STANDARD_3_LINES);

        // Verify initial state - should have OMITTED_LINES
        var initialFilteredLines = document.getFilteredLines();
        boolean hasOmittedLinesInitially = initialFilteredLines.stream()
                .anyMatch(line -> line.getType() == UnifiedDiffDocument.LineType.OMITTED_LINES);
        assertTrue(hasOmittedLinesInitially, "STANDARD_3_LINES mode should have OMITTED_LINES");

        int initialLineCount = initialFilteredLines.size();
        System.out.println("Initial filtered lines count (STANDARD_3_LINES): " + initialLineCount);

        // Switch to FULL_CONTEXT mode
        document.switchContextMode(UnifiedDiffDocument.ContextMode.FULL_CONTEXT);

        // Verify context mode changed
        assertEquals(
                UnifiedDiffDocument.ContextMode.FULL_CONTEXT,
                document.getContextMode(),
                "Document context mode should be FULL_CONTEXT");

        // Verify filtered content changed - should NOT have OMITTED_LINES
        var fullContextFilteredLines = document.getFilteredLines();
        boolean hasOmittedLinesAfter = fullContextFilteredLines.stream()
                .anyMatch(line -> line.getType() == UnifiedDiffDocument.LineType.OMITTED_LINES);
        assertFalse(hasOmittedLinesAfter, "FULL_CONTEXT mode should not have OMITTED_LINES");

        int fullContextLineCount = fullContextFilteredLines.size();
        System.out.println("Full context filtered lines count (FULL_CONTEXT): " + fullContextLineCount);

        // Full context should have different line count (likely more lines since gaps are filled)
        assertNotEquals(
                initialLineCount,
                fullContextLineCount,
                "Context mode switch should change the number of displayed lines");

        // Switch back to STANDARD_3_LINES mode
        document.switchContextMode(UnifiedDiffDocument.ContextMode.STANDARD_3_LINES);

        // Verify we're back to original state
        assertEquals(
                UnifiedDiffDocument.ContextMode.STANDARD_3_LINES,
                document.getContextMode(),
                "Document context mode should be back to STANDARD_3_LINES");

        var finalFilteredLines = document.getFilteredLines();
        boolean hasOmittedLinesFinal = finalFilteredLines.stream()
                .anyMatch(line -> line.getType() == UnifiedDiffDocument.LineType.OMITTED_LINES);
        assertTrue(hasOmittedLinesFinal, "Switched back STANDARD_3_LINES mode should have OMITTED_LINES again");

        int finalLineCount = finalFilteredLines.size();
        assertEquals(initialLineCount, finalLineCount, "Switching back should restore original line count");

        System.out.println("Context mode switching test completed successfully");
    }
}
