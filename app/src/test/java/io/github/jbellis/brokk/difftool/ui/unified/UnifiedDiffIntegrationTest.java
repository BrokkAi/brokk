package io.github.jbellis.brokk.difftool.ui.unified;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.difftool.ui.BufferSource;
import io.github.jbellis.brokk.difftool.ui.DiffGutterComponent;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.List;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Minimal integration test for unified diff system. Tests the interaction between UnifiedDiffDocument, line mapping,
 * and DiffGutterComponent in a headless environment.
 */
class UnifiedDiffIntegrationTest {

    @BeforeEach
    void checkHeadless() {
        // These tests should work in headless mode
        if (!GraphicsEnvironment.isHeadless()) {
            System.setProperty("java.awt.headless", "true");
        }
    }

    @Test
    @DisplayName("UnifiedDiffDocument line mapping with simple diff")
    void unifiedDiffDocumentLineMappingSimpleDiff() {
        // Create a simple diff: line 2 modified
        List<String> leftLines = Arrays.asList("line 1", "old line 2", "line 3", "line 4");
        List<String> rightLines = Arrays.asList("line 1", "new line 2", "line 3", "line 4");

        var leftSource = new BufferSource.StringSource(String.join("\n", leftLines), "left.txt");
        var rightSource = new BufferSource.StringSource(String.join("\n", rightLines), "right.txt");

        var document = UnifiedDiffGenerator.generateUnifiedDiff(
                leftSource, rightSource, UnifiedDiffDocument.ContextMode.FULL_CONTEXT);

        assertNotNull(document, "Document should not be null");
        assertTrue(document.getLineCount() > 0, "Document should have lines");

        // Verify we can get diff lines
        var filteredLines = document.getFilteredLines();
        assertFalse(filteredLines.isEmpty(), "Should have filtered lines");

        // Find the deletion and addition
        boolean foundDeletion = false;
        boolean foundAddition = false;

        for (int i = 0; i < document.getLineCount(); i++) {
            var diffLine = document.getDiffLine(i);
            assertNotNull(diffLine, "Each line should be accessible");

            if (diffLine.getType() == UnifiedDiffDocument.LineType.DELETION) {
                foundDeletion = true;
                assertTrue(diffLine.getContent().contains("old line 2"), "Should contain deleted content");
            } else if (diffLine.getType() == UnifiedDiffDocument.LineType.ADDITION) {
                foundAddition = true;
                assertTrue(diffLine.getContent().contains("new line 2"), "Should contain added content");
            }
        }

        assertTrue(foundDeletion, "Should find deletion line");
        assertTrue(foundAddition, "Should find addition line");
    }

    @Test
    @DisplayName("UnifiedDiffDocument line mapping with addition only")
    void unifiedDiffDocumentLineMappingAdditionOnly() {
        // Create a diff with only additions
        List<String> leftLines = Arrays.asList("line 1", "line 2");
        List<String> rightLines = Arrays.asList("line 1", "line 2", "new line 3", "new line 4");

        var leftSource = new BufferSource.StringSource(String.join("\n", leftLines), "left.txt");
        var rightSource = new BufferSource.StringSource(String.join("\n", rightLines), "right.txt");

        var document = UnifiedDiffGenerator.generateUnifiedDiff(
                leftSource, rightSource, UnifiedDiffDocument.ContextMode.FULL_CONTEXT);

        assertNotNull(document);

        // Count additions
        int additionCount = 0;
        for (int i = 0; i < document.getLineCount(); i++) {
            var diffLine = document.getDiffLine(i);
            if (diffLine.getType() == UnifiedDiffDocument.LineType.ADDITION) {
                additionCount++;
                assertTrue(diffLine.getRightLineNumber() > 0, "Addition should have right line number");
                assertEquals(-1, diffLine.getLeftLineNumber(), "Addition should not have left line number");
            }
        }

        assertEquals(2, additionCount, "Should have 2 addition lines");
    }

    @Test
    @DisplayName("UnifiedDiffDocument line mapping with deletion only")
    void unifiedDiffDocumentLineMappingDeletionOnly() {
        // Create a diff with only deletions
        List<String> leftLines = Arrays.asList("line 1", "line 2", "deleted 3", "deleted 4");
        List<String> rightLines = Arrays.asList("line 1", "line 2");

        var leftSource = new BufferSource.StringSource(String.join("\n", leftLines), "left.txt");
        var rightSource = new BufferSource.StringSource(String.join("\n", rightLines), "right.txt");

        var document = UnifiedDiffGenerator.generateUnifiedDiff(
                leftSource, rightSource, UnifiedDiffDocument.ContextMode.FULL_CONTEXT);

        assertNotNull(document);

        // Count deletions
        int deletionCount = 0;
        for (int i = 0; i < document.getLineCount(); i++) {
            var diffLine = document.getDiffLine(i);
            if (diffLine.getType() == UnifiedDiffDocument.LineType.DELETION) {
                deletionCount++;
                assertTrue(diffLine.getLeftLineNumber() > 0, "Deletion should have left line number");
                assertEquals(-1, diffLine.getRightLineNumber(), "Deletion should not have right line number");
            }
        }

        assertEquals(2, deletionCount, "Should have 2 deletion lines");
    }

    @Test
    @DisplayName("DiffGutterComponent preferred width calculation")
    void diffGutterComponentPreferredWidthCalculation() {
        // Create a text area with some content
        var textArea = new RSyntaxTextArea(10, 40);
        textArea.setText("line 1\nline 2\nline 3");

        // Create gutter component in both modes
        var unifiedGutter = new DiffGutterComponent(textArea, DiffGutterComponent.DisplayMode.UNIFIED_DUAL_COLUMN);
        var sideBySideGutter = new DiffGutterComponent(textArea, DiffGutterComponent.DisplayMode.SIDE_BY_SIDE_SINGLE);

        // Get preferred sizes (these should be calculated even in headless mode)
        var unifiedSize = unifiedGutter.getPreferredSize();
        var sideBySideSize = sideBySideGutter.getPreferredSize();

        assertNotNull(unifiedSize, "Unified gutter should have preferred size");
        assertNotNull(sideBySideSize, "Side-by-side gutter should have preferred size");

        // Unified mode should be wider (dual columns)
        assertTrue(
                unifiedSize.width > sideBySideSize.width,
                String.format(
                        "Unified gutter width (%d) should be wider than side-by-side (%d)",
                        unifiedSize.width, sideBySideSize.width));

        // Both should have positive dimensions
        assertTrue(unifiedSize.width > 0, "Unified gutter should have positive width");
        assertTrue(unifiedSize.height >= 0, "Unified gutter should have non-negative height");
        assertTrue(sideBySideSize.width > 0, "Side-by-side gutter should have positive width");
        assertTrue(sideBySideSize.height >= 0, "Side-by-side gutter should have non-negative height");
    }

    @Test
    @DisplayName("Context mode switching preserves line count integrity")
    void contextModeSwitchingPreservesLineCountIntegrity() {
        // Create a diff with multiple hunks
        List<String> leftLines =
                Arrays.asList("line 1", "line 2", "line 3", "line 4", "line 5", "line 6", "line 7", "line 8");
        List<String> rightLines =
                Arrays.asList("line 1", "modified 2", "line 3", "line 4", "line 5", "line 6", "modified 7", "line 8");

        var leftSource = new BufferSource.StringSource(String.join("\n", leftLines), "left.txt");
        var rightSource = new BufferSource.StringSource(String.join("\n", rightLines), "right.txt");

        // Generate with standard context
        var standardDoc = UnifiedDiffGenerator.generateUnifiedDiff(
                leftSource, rightSource, UnifiedDiffDocument.ContextMode.STANDARD_3_LINES);

        // Generate with full context
        var fullDoc = UnifiedDiffGenerator.generateUnifiedDiff(
                leftSource, rightSource, UnifiedDiffDocument.ContextMode.FULL_CONTEXT);

        assertNotNull(standardDoc);
        assertNotNull(fullDoc);

        // Full context should have more lines than standard (includes all context)
        assertTrue(
                fullDoc.getLineCount() >= standardDoc.getLineCount(),
                String.format(
                        "Full context (%d lines) should have >= lines than standard context (%d lines)",
                        fullDoc.getLineCount(), standardDoc.getLineCount()));

        // Both should have valid line counts
        assertTrue(standardDoc.getLineCount() > 0, "Standard context should have lines");
        assertTrue(fullDoc.getLineCount() > 0, "Full context should have lines");
    }

    @Test
    @DisplayName("Empty diff produces valid document")
    void emptyDiffProducesValidDocument() {
        // Create identical content (no diff)
        List<String> sameLines = Arrays.asList("line 1", "line 2", "line 3");

        var leftSource = new BufferSource.StringSource(String.join("\n", sameLines), "left.txt");
        var rightSource = new BufferSource.StringSource(String.join("\n", sameLines), "right.txt");

        var document = UnifiedDiffGenerator.generateUnifiedDiff(
                leftSource, rightSource, UnifiedDiffDocument.ContextMode.FULL_CONTEXT);

        assertNotNull(document, "Should produce a valid document even with no changes");
        // Document might be empty or have context lines depending on implementation
        assertTrue(document.getLineCount() >= 0, "Line count should be non-negative");
    }
}
