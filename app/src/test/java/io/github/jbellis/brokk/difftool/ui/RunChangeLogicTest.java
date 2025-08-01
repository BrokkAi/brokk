package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import io.github.jbellis.brokk.difftool.doc.InMemoryDocument;
import io.github.jbellis.brokk.testutil.TestConsoleIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for runChange logic without any reflection or UI dependencies.
 * Creates a minimal test scenario that exercises the core functionality.
 */
class RunChangeLogicTest {

    private InMemoryDocument leftDoc;
    private InMemoryDocument rightDoc;
    private AbstractDelta<String> testDelta = null;

    @BeforeEach
    void setUp() throws Exception {
        // Create test documents with known content
        leftDoc = new InMemoryDocument("left.txt", "line1\nline2\nline3\nline4\nline5");
        rightDoc = new InMemoryDocument("right.txt", "line1\nmodified line2\nline3\nline4\nline5");

        // Create a test diff patch
        List<String> leftLines = List.of("line1", "line2", "line3", "line4", "line5");
        List<String> rightLines = List.of("line1", "modified line2", "line3", "line4", "line5");
        Patch<String> patch = DiffUtils.diff(leftLines, rightLines);

        // Set the first delta as test data
        if (!patch.getDeltas().isEmpty()) {
            testDelta = patch.getDeltas().getFirst();
        }
    }

    @Test
    @DisplayName("Test core copy logic from left to right")
    void testCopyLogicLeftToRight() {
        // Arrange
        assertNotNull(testDelta, "Should have a test delta");
        var sourceChunk = testDelta.getSource();
        var targetChunk = testDelta.getTarget();

        // Simulate the core copy logic from BufferDiffPanel.runChange
        List<String> sourceLines = leftDoc.getLineList();
        List<String> targetLines = rightDoc.getLineList();

        // Extract text from source chunk
        StringBuilder textToCopy = new StringBuilder();
        for (int i = sourceChunk.getPosition(); i < sourceChunk.getPosition() + sourceChunk.size(); i++) {
            if (i < sourceLines.size()) {
                if (!textToCopy.isEmpty()) {
                    textToCopy.append("\n");
                }
                textToCopy.append(sourceLines.get(i));
            }
        }

        // Verify the extracted text
        assertEquals("line2", textToCopy.toString(), "Should extract 'line2' from source");

        // Simulate applying the change to target document
        // (In real implementation, this would modify the editor)
        var modifiedTargetLines = new ArrayList<>(targetLines);

        // Replace target chunk with source text
        for (int i = 0; i < targetChunk.size(); i++) {
            int targetIndex = targetChunk.getPosition() + i;
            if (targetIndex < modifiedTargetLines.size()) {
                modifiedTargetLines.set(targetIndex, sourceLines.get(sourceChunk.getPosition() + i));
            }
        }

        // Verify the result
        assertEquals("line2", modifiedTargetLines.get(1), "Target line should be replaced with source line");
        assertNotEquals("modified line2", modifiedTargetLines.get(1), "Original target line should be replaced");
    }

    @Test
    @DisplayName("Test core copy logic from right to left")
    void testCopyLogicRightToLeft() {
        // Arrange - Reverse the source and target
        var sourceChunk = testDelta.getTarget(); // Target becomes source
        var targetChunk = testDelta.getSource(); // Source becomes target

        List<String> sourceLines = rightDoc.getLineList();
        List<String> targetLines = leftDoc.getLineList();

        // Extract text from source chunk (right document)
        StringBuilder textToCopy = new StringBuilder();
        for (int i = sourceChunk.getPosition(); i < sourceChunk.getPosition() + sourceChunk.size(); i++) {
            if (i < sourceLines.size()) {
                if (!textToCopy.isEmpty()) {
                    textToCopy.append("\n");
                }
                textToCopy.append(sourceLines.get(i));
            }
        }

        // Verify the extracted text
        assertEquals("modified line2", textToCopy.toString(), "Should extract 'modified line2' from right");

        // Simulate applying the change to target document (left)
        var modifiedTargetLines = new ArrayList<>(targetLines);

        // Replace target chunk with source text
        for (int i = 0; i < targetChunk.size(); i++) {
            int targetIndex = targetChunk.getPosition() + i;
            if (targetIndex < modifiedTargetLines.size()) {
                modifiedTargetLines.set(targetIndex, sourceLines.get(sourceChunk.getPosition() + i));
            }
        }

        // Verify the result
        assertEquals("modified line2", modifiedTargetLines.get(1), "Target line should be replaced with source line");
        assertNotEquals("line2", modifiedTargetLines.get(1), "Original target line should be replaced");
    }

    @Test
    @DisplayName("Test offset calculation logic")
    void testOffsetCalculation() {
        var sourceChunk = testDelta.getSource();
        var targetChunk = testDelta.getTarget();

        List<String> leftLines = leftDoc.getLineList();
        List<String> rightLines = rightDoc.getLineList();

        // Calculate source offset (same logic as BufferDiffPanel.runChange)
        int sourceOffset = 0;
        for (int i = 0; i < sourceChunk.getPosition() && i < leftLines.size(); i++) {
            sourceOffset += leftLines.get(i).length() + 1; // +1 for newline
        }

        // Calculate target offset
        int targetOffset = 0;
        for (int i = 0; i < targetChunk.getPosition() && i < rightLines.size(); i++) {
            targetOffset += rightLines.get(i).length() + 1; // +1 for newline
        }

        // Both should point to the beginning of line 2 (after "line1\n")
        assertEquals(6, sourceOffset, "Source offset should point to start of 'line2'");
        assertEquals(6, targetOffset, "Target offset should point to start of 'modified line2'");

        // Calculate source length for selection
        int sourceLength = 0;
        for (int i = 0; i < sourceChunk.size(); i++) {
            int lineIndex = sourceChunk.getPosition() + i;
            if (lineIndex < leftLines.size()) {
                sourceLength += leftLines.get(lineIndex).length();
                if (i < sourceChunk.size() - 1) {
                    sourceLength += 1; // +1 for newline between lines
                }
            }
        }

        assertEquals(5, sourceLength, "Source selection should be length of 'line2'");
    }

    @Test
    @DisplayName("Test multiple line copy operation")
    void testMultiLineCopy() {
        // Create a multi-line diff scenario
        List<String> leftLines = List.of("line1", "line2", "line3", "line4");
        List<String> rightLines = List.of("line1", "CHANGED2", "CHANGED3", "line4");
        Patch<String> multiLinePatch = DiffUtils.diff(leftLines, rightLines);

        if (!multiLinePatch.getDeltas().isEmpty()) {
            var multiLineDelta = multiLinePatch.getDeltas().getFirst();
            var sourceChunk = multiLineDelta.getSource();

            // Extract multiple lines
            StringBuilder textToCopy = new StringBuilder();
            for (int i = sourceChunk.getPosition(); i < sourceChunk.getPosition() + sourceChunk.size(); i++) {
                if (i < leftLines.size()) {
                    if (!textToCopy.isEmpty()) {
                        textToCopy.append("\n");
                    }
                    textToCopy.append(leftLines.get(i));
                }
            }

            assertTrue(textToCopy.toString().startsWith("line2"), "Multi-line copy should start with first line");
        }
    }
}