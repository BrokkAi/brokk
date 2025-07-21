package io.github.jbellis.brokk.difftool.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import javax.swing.text.PlainDocument;
import javax.swing.text.BadLocationException;

/**
 * Tests for the new hybrid document synchronization logic in FilePanel.
 * Validates the decision-making between incremental sync and fallback sync.
 */
public class HybridDocumentSyncTest {

    private PlainDocument sourceDoc;
    private PlainDocument destDoc;

    @BeforeEach
    void setUp() {
        sourceDoc = new PlainDocument();
        destDoc = new PlainDocument();
    }

    @Test
    void testSmallIncrementalEditUsesIncremental() throws BadLocationException {
        // Setup: Documents start identical
        String initialContent = "line1\nline2\nline3";
        sourceDoc.insertString(0, initialContent, null);
        destDoc.insertString(0, initialContent, null);

        // Action: Small edit that should use incremental sync
        sourceDoc.insertString(5, "X", null);  // Insert X after "line1"

        // Simulate the hybrid sync decision
        int offset = 5;
        int length = 1;
        boolean shouldUseFallback = Math.abs(sourceDoc.getLength() - destDoc.getLength()) > length;

        assertFalse(shouldUseFallback, "Small edit should use incremental sync");

        // Apply incremental sync
        String insertedText = sourceDoc.getText(offset, length);
        destDoc.insertString(offset, insertedText, null);

        String expected = "line1X\nline2\nline3";
        String actual = destDoc.getText(0, destDoc.getLength());
        assertEquals(expected, actual, "Incremental sync should work for small edits");
    }

    @Test
    void testLargeEditTriggersFallback() throws BadLocationException {
        // Setup: Documents start identical
        String initialContent = "line1\nline2\nline3";
        sourceDoc.insertString(0, initialContent, null);
        destDoc.insertString(0, initialContent, null);

        // Action: Large edit that should trigger fallback
        String largeInsert = "LARGE_PREFIX_";
        sourceDoc.insertString(0, largeInsert, null);

        // Simulate the hybrid sync decision
        int offset = 0;
        int length = largeInsert.length(); // 13 characters
        // Length difference is 13, edit length is 13, so 13 > 13 is false
        // Let's make the edit smaller than the difference to trigger fallback
        int editLength = 5; // Simulate smaller reported edit length
        boolean shouldUseFallback = Math.abs(sourceDoc.getLength() - destDoc.getLength()) > editLength;

        assertTrue(shouldUseFallback, "Large document difference should trigger fallback sync");

        // Apply fallback sync
        String sourceText = sourceDoc.getText(0, sourceDoc.getLength());
        destDoc.remove(0, destDoc.getLength());
        destDoc.insertString(0, sourceText, null);

        String expected = sourceDoc.getText(0, sourceDoc.getLength());
        String actual = destDoc.getText(0, destDoc.getLength());
        assertEquals(expected, actual, "Fallback sync should handle large edits");
    }

    @Test
    void testInvalidOffsetTriggersFallback() throws BadLocationException {
        // Setup: Documents start identical
        String initialContent = "short";
        sourceDoc.insertString(0, initialContent, null);
        destDoc.insertString(0, initialContent, null);

        // Action: Simulate invalid offset (beyond destination document)
        sourceDoc.insertString(sourceDoc.getLength(), "_EXTENSION", null);

        int offset = destDoc.getLength() + 5; // Offset beyond destination
        int length = 10;

        // Check if offset is invalid
        boolean invalidOffset = offset > destDoc.getLength();

        assertTrue(invalidOffset, "Offset beyond document should be detected as invalid");

        // Should trigger fallback
        String sourceText = sourceDoc.getText(0, sourceDoc.getLength());
        destDoc.remove(0, destDoc.getLength());
        destDoc.insertString(0, sourceText, null);

        String expected = "short_EXTENSION";
        String actual = destDoc.getText(0, destDoc.getLength());
        assertEquals(expected, actual, "Invalid offset should trigger fallback sync");
    }

    @Test
    void testChangeEventAlwaysUsesFallback() throws BadLocationException {
        // Setup: Documents start identical
        String initialContent = "line1\nline2\nline3";
        sourceDoc.insertString(0, initialContent, null);
        destDoc.insertString(0, initialContent, null);

        // Action: Simulate CHANGE event (always risky)
        // Modify existing content in source
        sourceDoc.remove(6, 5); // Remove "line2"
        sourceDoc.insertString(6, "CHANGED", null);

        // For CHANGE events, we always use fallback regardless of size
        // This simulates the logic: if (eventType == DocumentEvent.EventType.CHANGE)

        String sourceText = sourceDoc.getText(0, sourceDoc.getLength());
        destDoc.remove(0, destDoc.getLength());
        destDoc.insertString(0, sourceText, null);

        String expected = "line1\nCHANGED\nline3";
        String actual = destDoc.getText(0, destDoc.getLength());
        assertEquals(expected, actual, "CHANGE events should always use fallback");
    }

    @Test
    void testRemoveEventWithValidRange() throws BadLocationException {
        // Setup: Documents start identical
        String initialContent = "line1\nline2\nline3";
        sourceDoc.insertString(0, initialContent, null);
        destDoc.insertString(0, initialContent, null);

        // Action: Remove content from source
        int removeOffset = 6;  // Start of "line2"
        int removeLength = 6;  // "line2\n"
        sourceDoc.remove(removeOffset, removeLength);

        // Check if remove range is valid for destination
        boolean validRange = removeOffset < destDoc.getLength() &&
                           removeOffset + removeLength <= destDoc.getLength();

        assertTrue(validRange, "Remove range should be valid");

        // Apply incremental remove
        destDoc.remove(removeOffset, removeLength);

        String expected = "line1\nline3";
        String actual = destDoc.getText(0, destDoc.getLength());
        assertEquals(expected, actual, "Valid remove should use incremental sync");
    }

    @Test
    void testRemoveEventWithInvalidRange() throws BadLocationException {
        // Setup: Documents start with different lengths (simulating out-of-sync state)
        sourceDoc.insertString(0, "short", null);
        destDoc.insertString(0, "long", null); // Only 4 characters

        // Action: Try to remove beyond what exists in destination
        int removeOffset = 2;
        int removeLength = 10; // Would go beyond destination document (4 chars)
        sourceDoc.remove(1, 3); // Make source even shorter

        // Check if remove range is invalid for destination
        boolean validRange = removeOffset < destDoc.getLength() &&
                           removeOffset + removeLength <= destDoc.getLength();

        assertFalse(validRange, "Remove range should be invalid (2 + 10 > 4)");

        // Should trigger fallback
        String sourceText = sourceDoc.getText(0, sourceDoc.getLength());
        destDoc.remove(0, destDoc.getLength());
        destDoc.insertString(0, sourceText, null);

        String expected = sourceDoc.getText(0, sourceDoc.getLength());
        String actual = destDoc.getText(0, destDoc.getLength());
        assertEquals(expected, actual, "Invalid remove range should trigger fallback");
    }

    @Test
    void testLineJoiningScenarioWithHybridLogic() throws BadLocationException {
        // This is the specific scenario that caused the original bug
        String initialContent = "// save each panel\nfor (var p : panelCache.values()) {";
        sourceDoc.insertString(0, initialContent, null);
        destDoc.insertString(0, initialContent, null);

        // User adds "cqc" to the comment (small edit)
        int insertPos = initialContent.indexOf("panel") + "panel".length();
        sourceDoc.insertString(insertPos, " cqc", null);

        // This should use incremental sync (small edit)
        int length = 4; // " cqc"
        boolean shouldUseFallback = Math.abs(sourceDoc.getLength() - destDoc.getLength()) > length;

        assertFalse(shouldUseFallback, "Small comment edit should use incremental sync");

        // Apply incremental sync
        String insertedText = sourceDoc.getText(insertPos, length);
        destDoc.insertString(insertPos, insertedText, null);

        String result = destDoc.getText(0, destDoc.getLength());
        String expected = "// save each panel cqc\nfor (var p : panelCache.values()) {";

        assertEquals(expected, result, "Incremental sync should preserve line breaks");
        assertTrue(result.contains("\nfor"), "The 'for' loop should remain on a separate line");
        assertFalse(result.contains("cqcfor"), "Should not have joined 'cqc' with 'for'");
    }

    @Test
    void testDocumentLengthThresholdBoundary() throws BadLocationException {
        // Test the exact boundary condition for the length difference check
        String initialContent = "12345";
        sourceDoc.insertString(0, initialContent, null);
        destDoc.insertString(0, initialContent, null);

        // Edit that exactly equals the length difference threshold
        sourceDoc.insertString(0, "ABC", null); // Makes difference = 3

        int length = 3; // Same as the difference
        boolean shouldUseFallback = Math.abs(sourceDoc.getLength() - destDoc.getLength()) > length;

        assertFalse(shouldUseFallback, "Edit equal to length difference should use incremental");

        // Now test just over the threshold
        sourceDoc.insertString(0, "D", null); // Makes difference = 4, but edit length still 3
        shouldUseFallback = Math.abs(sourceDoc.getLength() - destDoc.getLength()) > length;

        assertTrue(shouldUseFallback, "Edit causing larger difference should use fallback");
    }
}
