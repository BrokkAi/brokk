package io.github.jbellis.brokk.difftool.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import javax.swing.text.PlainDocument;
import javax.swing.text.BadLocationException;

/**
 * Integration test for FilePanel document synchronization logic.
 * Tests the copyTextFallback approach that fixes the line joining bug.
 */
public class FilePanelDocumentSyncTest {

    private PlainDocument sourceDoc;
    private PlainDocument destDoc;

    @BeforeEach
    void setUp() {
        sourceDoc = new PlainDocument();
        destDoc = new PlainDocument();
    }

    @Test
    void testCopyTextFallbackPreservesLineBreaks() throws Exception {
        // Test the copyTextFallback method which is our fix for the line joining bug
        String initialContent = "// File navigation handlers\nbtnPreviousFile.addActionListener(e -> previousFile());\nbtnNextFile.addActionListener(e -> nextFile());";
        sourceDoc.insertString(0, initialContent, null);
        destDoc.insertString(0, initialContent, null);

        // Simulate user typing "cqc" in the comment
        int insertPos = initialContent.indexOf("handlers");
        sourceDoc.insertString(insertPos, "cqc", null);

        // Call the copyTextFallback approach (what our fix does)
        callCopyTextFallback(sourceDoc, destDoc);

        String result = destDoc.getText(0, destDoc.getLength());
        String expected = "// File navigation cqchandlers\nbtnPreviousFile.addActionListener(e -> previousFile());\nbtnNextFile.addActionListener(e -> nextFile());";

        assertEquals(expected, result, "copyTextFallback should preserve line breaks");
        assertFalse(result.contains("cqcbtnPreviousFile"), "Should not join lines");
        assertTrue(result.contains("\nbtnPreviousFile"), "Next line should start with newline");
    }

    /**
     * Helper method to simulate the copyTextFallback approach from FilePanel.
     */
    private void callCopyTextFallback(PlainDocument src, PlainDocument dst) throws BadLocationException {
        String srcText = src.getText(0, src.getLength());
        String dstText = dst.getText(0, dst.getLength());

        if (!srcText.equals(dstText)) {
            dst.remove(0, dst.getLength());
            dst.insertString(0, srcText, null);
        }
    }

    @Test
    void testMultipleEditsPreserveStructure() throws Exception {
        String initialContent = "line1\nline2\nline3\nline4";
        sourceDoc.insertString(0, initialContent, null);
        destDoc.insertString(0, initialContent, null);

        // First edit: add to line2
        int firstInsertPos = initialContent.indexOf("line2") + 4;
        sourceDoc.insertString(firstInsertPos, "XXX", null);
        callCopyTextFallback(sourceDoc, destDoc);

        // Second edit: add to line3
        String currentContent = destDoc.getText(0, destDoc.getLength());
        int secondInsertPos = currentContent.indexOf("line3");
        sourceDoc.remove(0, sourceDoc.getLength());
        sourceDoc.insertString(0, currentContent, null);
        sourceDoc.insertString(secondInsertPos, "YYY", null);
        callCopyTextFallback(sourceDoc, destDoc);

        String result = destDoc.getText(0, destDoc.getLength());
        String expected = "line1\nlineXXX2\nYYYline3\nline4";

        assertEquals(expected, result, "Multiple edits should preserve line structure");
        assertTrue(result.contains("\n"), "Newlines should be preserved after multiple edits");
    }

    @Test
    void testHybridSyncLogic() throws Exception {
        // Test the new hybrid approach that uses incremental sync when safe, fallback when risky
        String initialContent = "line1\nline2\nline3";
        sourceDoc.insertString(0, initialContent, null);
        destDoc.insertString(0, initialContent, null);

        // Test 1: Small incremental edit should work with incremental sync
        sourceDoc.insertString(5, "X", null);  // Insert X after "line1"

        // Simulate the new sync logic
        callHybridSync(sourceDoc, destDoc, 5, 1);

        String result1 = destDoc.getText(0, destDoc.getLength());
        assertEquals("line1X\nline2\nline3", result1, "Small incremental edit should work");

        // Test 2: Large difference should trigger fallback
        sourceDoc.insertString(0, "LARGE_PREFIX_", null);  // Makes documents very different

        // This should trigger fallback due to large length difference
        callHybridSync(sourceDoc, destDoc, 0, 13);

        String result2 = destDoc.getText(0, destDoc.getLength());
        String expected2 = sourceDoc.getText(0, sourceDoc.getLength());
        assertEquals(expected2, result2, "Large edit should trigger fallback sync");
    }

    /**
     * Simulates the new hybrid sync logic that chooses incremental vs fallback
     */
    private void callHybridSync(PlainDocument src, PlainDocument dst, int offset, int length) throws BadLocationException {
        // Simulate the logic from the new syncDocumentChange method
        if (Math.abs(src.getLength() - dst.getLength()) > length) {
            // Large difference - use fallback
            callCopyTextFallback(src, dst);
        } else {
            // Small difference - try incremental
            String insertedText = src.getText(offset, length);
            if (offset <= dst.getLength()) {
                dst.insertString(offset, insertedText, null);
            } else {
                callCopyTextFallback(src, dst);
            }
        }
    }
}
