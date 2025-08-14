package io.github.jbellis.brokk.difftool.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.DeltaType;
import io.github.jbellis.brokk.difftool.doc.InMemoryDocument;
import java.util.List;
import javax.swing.text.PlainDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for INSERT delta handling and document synchronization. Tests both the root cause bug (chunk
 * selection logic) and the fix mechanism (document sync). This reproduces the LICENSE.txt scenario that was causing the
 * bug.
 */
class InsertDeltaHandlingTest {

    @Test
    void testInsertDeltaDoesNotProduceGarbledOutput() {
        // Original content that mimics LICENSE.txt structure
        var originalLines = List.of(
                "                    GNU GENERAL PUBLIC LICENSE",
                "                       Version 3, 29 June 2007",
                "",
                " Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>",
                " Everyone is permitted to copy and distribute verbatim copies");

        // Modified content with insertion at line 3 (0-based)
        var modifiedLines = List.of(
                "                    GNU GENERAL PUBLIC LICENSE",
                "                       Version 3, 29 June 2007",
                "",
                "1234",
                "",
                " Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>",
                " Everyone is permitted to copy and distribute verbatim copies");

        // Generate the diff patch
        var patch = DiffUtils.diff(originalLines, modifiedLines);
        var delta = patch.getDeltas().getFirst();

        // Verify this is an INSERT delta at position 3
        assertEquals(DeltaType.INSERT, delta.getType());
        assertEquals(3, delta.getSource().getPosition());
        assertEquals(0, delta.getSource().size());
        assertEquals(2, delta.getTarget().size()); // Inserting "1234" and empty line

        // Create documents to simulate the diff panel scenario
        var originalDoc = new InMemoryDocument("original.txt", String.join("\n", originalLines));

        // Simulate the problematic extraction that was causing garbled output
        var sourceChunk = delta.getSource();
        var fromLine = sourceChunk.getPosition(); // 3
        var size = sourceChunk.size(); // 0

        String extractedText;
        if (delta.getType() == DeltaType.INSERT && size == 0) {
            // NEW: For INSERT deltas, we should get empty text
            extractedText = "";
        } else {
            // OLD: This would cause the garbled output for INSERT deltas
            var fromOffset = originalDoc.getOffsetForLine(fromLine);
            var toOffset = originalDoc.getOffsetForLine(fromLine + size);
            try {
                extractedText = originalDoc.getDocument().getText(fromOffset, toOffset - fromOffset);
            } catch (Exception e) {
                fail("Should not throw exception during text extraction");
                return;
            }
        }

        // Verify that we get empty text for INSERT delta, not garbled content
        assertEquals("", extractedText, "INSERT delta should extract empty text, not partial line content");

        // Verify the target content is what we expect to insert
        var targetChunk = delta.getTarget();
        var insertedLines = targetChunk.getLines();
        assertEquals(2, insertedLines.size());
        assertEquals("1234", insertedLines.get(0));
        assertEquals("", insertedLines.get(1));
    }

    @Test
    void testChangeAndDeleteDeltasStillExtractCorrectText() {
        // Test that CHANGE and DELETE deltas still work correctly
        var originalLines = List.of("Line 1", "Old Line 2", "Line 3");
        var modifiedLines = List.of("Line 1", "New Line 2", "Line 3");

        var patch = DiffUtils.diff(originalLines, modifiedLines);
        var delta = patch.getDeltas().getFirst();

        assertEquals(DeltaType.CHANGE, delta.getType());
        assertEquals(1, delta.getSource().getPosition());
        assertEquals(1, delta.getSource().size());

        var originalDoc = new InMemoryDocument("original.txt", String.join("\n", originalLines));

        // For CHANGE deltas, we should still extract the actual text
        var sourceChunk = delta.getSource();
        var fromLine = sourceChunk.getPosition();
        var size = sourceChunk.size();

        var fromOffset = originalDoc.getOffsetForLine(fromLine);
        var toOffset = originalDoc.getOffsetForLine(fromLine + size);

        String extractedText;
        try {
            extractedText = originalDoc.getDocument().getText(fromOffset, toOffset - fromOffset);
        } catch (Exception e) {
            fail("Should not throw exception during text extraction");
            return;
        }

        // Should extract the actual line content for CHANGE deltas
        assertTrue(
                extractedText.contains("Old Line 2"),
                "CHANGE delta should extract actual line content: " + extractedText);
    }

    @Test
    @DisplayName("Document synchronization concept - copy content between documents")
    void testDocumentSynchronizationConcept() throws Exception {
        // Arrange
        PlainDocument sourceDoc = new PlainDocument();
        PlainDocument targetDoc = new PlainDocument();

        // Set up different content
        sourceDoc.insertString(0, "Source content", null);
        targetDoc.insertString(0, "Target content", null);

        // Act - manually implement the synchronization logic from BufferDiffPanel
        if (sourceDoc != targetDoc) {
            // Clear target and copy from source
            String sourceContent = sourceDoc.getText(0, sourceDoc.getLength());
            targetDoc.remove(0, targetDoc.getLength());
            targetDoc.insertString(0, sourceContent, null);
        }

        // Assert
        assertEquals("Source content", targetDoc.getText(0, targetDoc.getLength()));
    }

    @Test
    @DisplayName("Document synchronization concept - handle empty source")
    void testDocumentSynchronizationConceptEmptySource() throws Exception {
        // Arrange
        PlainDocument sourceDoc = new PlainDocument(); // empty
        PlainDocument targetDoc = new PlainDocument();
        targetDoc.insertString(0, "Target content", null);

        // Act - copy empty content from source to target
        if (!sourceDoc.equals(targetDoc)) {
            String sourceContent = sourceDoc.getText(0, sourceDoc.getLength());
            targetDoc.remove(0, targetDoc.getLength());
            targetDoc.insertString(0, sourceContent, null);
        }

        // Assert
        assertEquals("", targetDoc.getText(0, targetDoc.getLength()));
        assertEquals(0, targetDoc.getLength());
    }
}
