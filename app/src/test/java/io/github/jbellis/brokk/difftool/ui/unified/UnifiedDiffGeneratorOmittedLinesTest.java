package io.github.jbellis.brokk.difftool.ui.unified;

import io.github.jbellis.brokk.difftool.ui.BufferSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that UnifiedDiffGenerator properly creates OMITTED_LINES indicators
 * when there are gaps between hunks in STANDARD_3_LINES mode.
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
        boolean hasOmittedLines = diffLines.stream()
            .anyMatch(line -> line.getType() == UnifiedDiffDocument.LineType.OMITTED_LINES);

        assertTrue(hasOmittedLines, "STANDARD_3_LINES mode should generate OMITTED_LINES for gaps between hunks");

        // Print the diff for debugging
        System.out.println("Generated diff lines:");
        for (int i = 0; i < diffLines.size(); i++) {
            var line = diffLines.get(i);
            System.out.printf("%d: %s (left=%d, right=%d) - %s%n",
                i, line.getType(), line.getLeftLineNumber(), line.getRightLineNumber(),
                line.getContent().substring(0, Math.min(30, line.getContent().length())));
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
        boolean hasOmittedLines = diffLines.stream()
            .anyMatch(line -> line.getType() == UnifiedDiffDocument.LineType.OMITTED_LINES);

        assertFalse(hasOmittedLines, "FULL_CONTEXT mode should not generate OMITTED_LINES");
    }

    /**
     * Create test content with lines that will have gaps when using 3-line context.
     * We need enough lines between changes to create gaps in 3-line context mode.
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

    /**
     * Create modified content that will generate a diff with gaps.
     */
    private List<String> createModifiedLinesWithGaps() {
        var lines = new java.util.ArrayList<String>();

        // First change area (lines 1-10)
        for (int i = 1; i <= 10; i++) {
            if (i == 5) {
                lines.add("line " + i + " - MODIFIED");  // Changed
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
                lines.add("line " + i + " - ALSO MODIFIED");  // Changed
            } else {
                lines.add("line " + i);
            }
        }

        return lines;
    }

}