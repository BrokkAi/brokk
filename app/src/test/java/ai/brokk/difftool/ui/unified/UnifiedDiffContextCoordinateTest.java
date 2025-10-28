package ai.brokk.difftool.ui.unified;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for coordinate mapping in different context modes, focusing on OMITTED_LINES handling. These tests
 * validate that line number calculations work correctly in both STANDARD_3_LINES and FULL_CONTEXT modes.
 */
class UnifiedDiffContextCoordinateTest {

    private List<UnifiedDiffDocument.DiffLine> createSampleDiffLines() {
        var lines = new ArrayList<UnifiedDiffDocument.DiffLine>();

        // Header
        lines.add(new UnifiedDiffDocument.DiffLine(
                UnifiedDiffDocument.LineType.HEADER, "@@ -10,5 +100,6 @@", -1, -1, false));

        // Context line
        lines.add(new UnifiedDiffDocument.DiffLine(
                UnifiedDiffDocument.LineType.CONTEXT, " context line 10", 10, 100, true));

        // Deletion
        lines.add(new UnifiedDiffDocument.DiffLine(
                UnifiedDiffDocument.LineType.DELETION, "-deleted line 11", 11, -1, false));

        // Addition
        lines.add(new UnifiedDiffDocument.DiffLine(
                UnifiedDiffDocument.LineType.ADDITION, "+added line 101", -1, 101, true));

        // OMITTED_LINES (only present in STANDARD_3_LINES mode)
        lines.add(new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.OMITTED_LINES, "...", -1, -1, false));

        // Context after gap
        lines.add(new UnifiedDiffDocument.DiffLine(
                UnifiedDiffDocument.LineType.CONTEXT, " context line 50", 50, 140, true));

        // Another addition
        lines.add(new UnifiedDiffDocument.DiffLine(
                UnifiedDiffDocument.LineType.ADDITION, "+added line 141", -1, 141, true));

        return lines;
    }

    @Test
    @DisplayName("Test coordinate mapping with STANDARD_3_LINES mode including OMITTED_LINES")
    void testStandard3LinesCoordinateMapping() {
        var diffLines = createSampleDiffLines();
        var document = new UnifiedDiffDocument(diffLines, UnifiedDiffDocument.ContextMode.STANDARD_3_LINES);

        // Verify all lines are present in STANDARD_3_LINES mode
        var filteredLines = document.getFilteredLines();
        assertEquals(7, filteredLines.size(), "All lines should be present in STANDARD_3_LINES mode");

        // Verify OMITTED_LINES is present
        boolean hasOmittedLines =
                filteredLines.stream().anyMatch(line -> line.getType() == UnifiedDiffDocument.LineType.OMITTED_LINES);
        assertTrue(hasOmittedLines, "STANDARD_3_LINES mode should include OMITTED_LINES");

        // Test line number retrieval for each document line
        assertEquals(
                UnifiedDiffDocument.LineType.HEADER, document.getDiffLine(0).getType());
        assertEquals(
                UnifiedDiffDocument.LineType.CONTEXT, document.getDiffLine(1).getType());
        assertEquals(10, document.getDiffLine(1).getLeftLineNumber());
        assertEquals(100, document.getDiffLine(1).getRightLineNumber());

        assertEquals(
                UnifiedDiffDocument.LineType.DELETION, document.getDiffLine(2).getType());
        assertEquals(11, document.getDiffLine(2).getLeftLineNumber());
        assertEquals(-1, document.getDiffLine(2).getRightLineNumber());

        assertEquals(
                UnifiedDiffDocument.LineType.ADDITION, document.getDiffLine(3).getType());
        assertEquals(-1, document.getDiffLine(3).getLeftLineNumber());
        assertEquals(101, document.getDiffLine(3).getRightLineNumber());

        assertEquals(
                UnifiedDiffDocument.LineType.OMITTED_LINES,
                document.getDiffLine(4).getType());
        assertEquals(-1, document.getDiffLine(4).getLeftLineNumber());
        assertEquals(-1, document.getDiffLine(4).getRightLineNumber());

        assertEquals(
                UnifiedDiffDocument.LineType.CONTEXT, document.getDiffLine(5).getType());
        assertEquals(50, document.getDiffLine(5).getLeftLineNumber());
        assertEquals(140, document.getDiffLine(5).getRightLineNumber());
    }

    @Test
    @DisplayName("Test coordinate mapping with FULL_CONTEXT mode excluding OMITTED_LINES")
    void testFullContextCoordinateMapping() {
        var diffLines = createSampleDiffLines();
        var document = new UnifiedDiffDocument(diffLines, UnifiedDiffDocument.ContextMode.FULL_CONTEXT);

        // Verify OMITTED_LINES is filtered out in FULL_CONTEXT mode
        var filteredLines = document.getFilteredLines();
        assertEquals(6, filteredLines.size(), "OMITTED_LINES should be filtered out in FULL_CONTEXT mode");

        // Verify OMITTED_LINES is not present
        boolean hasOmittedLines =
                filteredLines.stream().anyMatch(line -> line.getType() == UnifiedDiffDocument.LineType.OMITTED_LINES);
        assertFalse(hasOmittedLines, "FULL_CONTEXT mode should not include OMITTED_LINES");

        // Test that line indices shift when OMITTED_LINES is removed
        assertEquals(
                UnifiedDiffDocument.LineType.HEADER, document.getDiffLine(0).getType());
        assertEquals(
                UnifiedDiffDocument.LineType.CONTEXT, document.getDiffLine(1).getType());
        assertEquals(
                UnifiedDiffDocument.LineType.DELETION, document.getDiffLine(2).getType());
        assertEquals(
                UnifiedDiffDocument.LineType.ADDITION, document.getDiffLine(3).getType());

        // Line 4 should be the context line after the gap (no OMITTED_LINES)
        assertEquals(
                UnifiedDiffDocument.LineType.CONTEXT, document.getDiffLine(4).getType());
        assertEquals(50, document.getDiffLine(4).getLeftLineNumber());
        assertEquals(140, document.getDiffLine(4).getRightLineNumber());

        // Line 5 should be the final addition
        assertEquals(
                UnifiedDiffDocument.LineType.ADDITION, document.getDiffLine(5).getType());
        assertEquals(141, document.getDiffLine(5).getRightLineNumber());
    }

    @Test
    @DisplayName("Test context mode switching and coordinate consistency")
    void testContextModeSwitching() {
        var diffLines = createSampleDiffLines();
        var document = new UnifiedDiffDocument(diffLines, UnifiedDiffDocument.ContextMode.STANDARD_3_LINES);

        // Start with STANDARD_3_LINES
        assertEquals(7, document.getFilteredLines().size());
        assertTrue(document.getFilteredLines().stream()
                .anyMatch(line -> line.getType() == UnifiedDiffDocument.LineType.OMITTED_LINES));

        // Switch to FULL_CONTEXT
        document.switchContextMode(UnifiedDiffDocument.ContextMode.FULL_CONTEXT);
        assertEquals(6, document.getFilteredLines().size());
        assertFalse(document.getFilteredLines().stream()
                .anyMatch(line -> line.getType() == UnifiedDiffDocument.LineType.OMITTED_LINES));

        // Switch back to STANDARD_3_LINES
        document.switchContextMode(UnifiedDiffDocument.ContextMode.STANDARD_3_LINES);
        assertEquals(7, document.getFilteredLines().size());
        assertTrue(document.getFilteredLines().stream()
                .anyMatch(line -> line.getType() == UnifiedDiffDocument.LineType.OMITTED_LINES));
    }

    @Test
    @DisplayName("Test line number format generation for different line types with context awareness")
    void testLineNumberFormatGeneration() {
        var diffLines = createSampleDiffLines();

        // Test CONTEXT line formatting
        var contextLine = diffLines.get(1); // " context line 10"
        var contextFormat = formatLineNumbersForTesting(contextLine);
        assertNotNull(contextFormat);
        assertEquals("  10", contextFormat[0]); // Left column
        assertEquals(" 100", contextFormat[1]); // Right column

        // Test DELETION line formatting
        var deletionLine = diffLines.get(2); // "-deleted line 11"
        var deletionFormat = formatLineNumbersForTesting(deletionLine);
        assertNotNull(deletionFormat);
        assertEquals("  11", deletionFormat[0]); // Left column shows line number
        assertEquals("    ", deletionFormat[1]); // Right column empty

        // Test ADDITION line formatting
        var additionLine = diffLines.get(3); // "+added line 101"
        var additionFormat = formatLineNumbersForTesting(additionLine);
        assertNotNull(additionFormat);
        assertEquals("    ", additionFormat[0]); // Left column empty
        assertEquals(" 101", additionFormat[1]); // Right column shows line number

        // Test OMITTED_LINES formatting
        var omittedLine = diffLines.get(4); // "..."
        var omittedFormat = formatLineNumbersForTesting(omittedLine);
        assertNotNull(omittedFormat);
        assertEquals(" ...", omittedFormat[0]); // Left column shows dots
        assertEquals(" ...", omittedFormat[1]); // Right column shows dots

        // Test HEADER line formatting
        var headerLine = diffLines.get(0); // "@@ -10,5 +100,6 @@"
        var headerFormat = formatLineNumbersForTesting(headerLine);
        assertNotNull(headerFormat);
        assertEquals("    ", headerFormat[0]); // Both columns empty for headers
        assertEquals("    ", headerFormat[1]);
    }

    @Test
    @DisplayName("Test coordinate drift detection for high line numbers in STANDARD_3_LINES mode")
    void testCoordinateDriftDetection() {
        // Create a diff with high line numbers to test drift detection
        var lines = new ArrayList<UnifiedDiffDocument.DiffLine>();

        // Add lines with high line numbers (similar to the "1 439" issue)
        lines.add(new UnifiedDiffDocument.DiffLine(
                UnifiedDiffDocument.LineType.HEADER, "@@ -1800,5 +1800,6 @@", -1, -1, false));

        lines.add(new UnifiedDiffDocument.DiffLine(
                UnifiedDiffDocument.LineType.CONTEXT, " context line 1800", 1800, 1800, true));

        lines.add(new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.OMITTED_LINES, "...", -1, -1, false));

        lines.add(new UnifiedDiffDocument.DiffLine(
                UnifiedDiffDocument.LineType.CONTEXT, " context line 1900", 1900, 1900, true));

        var document = new UnifiedDiffDocument(lines, UnifiedDiffDocument.ContextMode.STANDARD_3_LINES);

        // Verify high line numbers are preserved correctly
        assertEquals(1800, document.getDiffLine(1).getLeftLineNumber());
        assertEquals(1800, document.getDiffLine(1).getRightLineNumber());

        assertEquals(
                UnifiedDiffDocument.LineType.OMITTED_LINES,
                document.getDiffLine(2).getType());

        assertEquals(1900, document.getDiffLine(3).getLeftLineNumber());
        assertEquals(1900, document.getDiffLine(3).getRightLineNumber());

        // Test that document bounds are respected
        assertNull(document.getDiffLine(4), "Should return null for line beyond document bounds");
        assertNull(document.getDiffLine(-1), "Should return null for negative line numbers");
    }

    @Test
    @DisplayName("Test coordinate calculation with mixed line types and gaps")
    void testMixedLineTypeCoordinates() {
        var lines = new ArrayList<UnifiedDiffDocument.DiffLine>();

        // Create a more complex diff pattern
        lines.add(new UnifiedDiffDocument.DiffLine(
                UnifiedDiffDocument.LineType.HEADER, "@@ -10,8 +10,9 @@", -1, -1, false));
        lines.add(
                new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.CONTEXT, " unchanged 10", 10, 10, true));
        lines.add(
                new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.DELETION, "-removed 11", 11, -1, false));
        lines.add(
                new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.DELETION, "-removed 12", 12, -1, false));
        lines.add(new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.ADDITION, "+added 11", -1, 11, true));
        lines.add(new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.ADDITION, "+added 12", -1, 12, true));
        lines.add(new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.ADDITION, "+added 13", -1, 13, true));
        lines.add(
                new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.CONTEXT, " unchanged 13", 13, 14, true));

        var document = new UnifiedDiffDocument(lines, UnifiedDiffDocument.ContextMode.FULL_CONTEXT);

        // Verify the complex line sequence
        assertEquals(8, document.getFilteredLines().size());

        // Check each line's coordinates
        assertEquals(
                UnifiedDiffDocument.LineType.HEADER, document.getDiffLine(0).getType());
        assertEquals(
                UnifiedDiffDocument.LineType.CONTEXT, document.getDiffLine(1).getType());
        assertEquals(10, document.getDiffLine(1).getLeftLineNumber());
        assertEquals(10, document.getDiffLine(1).getRightLineNumber());

        assertEquals(
                UnifiedDiffDocument.LineType.DELETION, document.getDiffLine(2).getType());
        assertEquals(11, document.getDiffLine(2).getLeftLineNumber());
        assertEquals(-1, document.getDiffLine(2).getRightLineNumber());

        assertEquals(
                UnifiedDiffDocument.LineType.DELETION, document.getDiffLine(3).getType());
        assertEquals(12, document.getDiffLine(3).getLeftLineNumber());

        assertEquals(
                UnifiedDiffDocument.LineType.ADDITION, document.getDiffLine(4).getType());
        assertEquals(-1, document.getDiffLine(4).getLeftLineNumber());
        assertEquals(11, document.getDiffLine(4).getRightLineNumber());

        assertEquals(
                UnifiedDiffDocument.LineType.ADDITION, document.getDiffLine(5).getType());
        assertEquals(12, document.getDiffLine(5).getRightLineNumber());

        assertEquals(
                UnifiedDiffDocument.LineType.ADDITION, document.getDiffLine(6).getType());
        assertEquals(13, document.getDiffLine(6).getRightLineNumber());

        assertEquals(
                UnifiedDiffDocument.LineType.CONTEXT, document.getDiffLine(7).getType());
        assertEquals(13, document.getDiffLine(7).getLeftLineNumber());
        assertEquals(14, document.getDiffLine(7).getRightLineNumber());
    }

    /**
     * Helper method to simulate the line number formatting logic from UnifiedDiffLineNumberList. This allows us to test
     * the formatting in a headless environment.
     */
    private String[] formatLineNumbersForTesting(UnifiedDiffDocument.DiffLine diffLine) {
        int leftLine = diffLine.getLeftLineNumber();
        int rightLine = diffLine.getRightLineNumber();

        return switch (diffLine.getType()) {
            case CONTEXT -> {
                // Context lines (unchanged): show both line numbers
                String leftText = leftLine > 0 ? String.format("%4d", leftLine) : "    ";
                String rightText = rightLine > 0 ? String.format("%4d", rightLine) : "    ";
                yield new String[] {leftText, rightText};
            }
            case ADDITION -> {
                // Addition lines: show only right line number
                String leftText = "    "; // Empty left column
                String rightText = rightLine > 0 ? String.format("%4d", rightLine) : "    ";
                yield new String[] {leftText, rightText};
            }
            case DELETION -> {
                // Deletion lines: show only left line number
                String leftText = leftLine > 0 ? String.format("%4d", leftLine) : "    ";
                String rightText = "    "; // Empty right column
                yield new String[] {leftText, rightText};
            }
            case HEADER -> {
                // Header lines: show empty line numbers but still paint the component
                yield new String[] {"    ", "    "};
            }
            case OMITTED_LINES -> {
                // Omitted lines indicator: show dots or similar
                yield new String[] {" ...", " ..."};
            }
        };
    }
}
