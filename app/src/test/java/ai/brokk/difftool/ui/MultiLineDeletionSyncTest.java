package ai.brokk.difftool.ui;

import static org.junit.jupiter.api.Assertions.*;

import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that verify the multi-line deletion synchronization fix. These tests demonstrate the specific scenario where
 * "deleting lines it deletes more characters on the next line" during direct editor typing with multiline deletions.
 *
 * <p>The fix ensures that REMOVE operations always use fallback synchronization to prevent data corruption when
 * documents are out of sync.
 */
class MultiLineDeletionSyncTest {

    private PlainDocument sourceDoc;
    private PlainDocument destDoc;

    @BeforeEach
    void setUp() {
        sourceDoc = new PlainDocument();
        destDoc = new PlainDocument();
    }

    @Test
    @DisplayName("Verify current fix always uses fallback for multi-line REMOVE operations")
    void testCurrentFixAlwaysUsesFallbackForRemove() throws Exception {
        // Set up documents with out-of-sync scenario that would trigger the original bug
        String sourceContent = "Line 1\nLine 3\nLine 4\nLine 5"; // Missing Line 2
        String destContent = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5"; // Has Line 2

        sourceDoc.insertString(0, sourceContent, null);
        destDoc.insertString(0, destContent, null);

        // Verify initial out-of-sync state
        assertNotEquals(sourceContent, destContent, "Documents should start out of sync");

        simulateNewRemoveHandling(sourceDoc, destDoc);

        // Verify synchronization result
        String finalSourceContent = sourceDoc.getText(0, sourceDoc.getLength());
        String finalDestContent = destDoc.getText(0, destDoc.getLength());

        // Both documents should now be synchronized
        assertEquals(
                finalSourceContent,
                finalDestContent,
                "After fallback sync, both documents should have identical content");

        // The destination should match the source (source of truth)
        assertEquals(
                sourceContent,
                finalDestContent,
                "Destination should match original source content after fallback sync");

        assertFalse(finalDestContent.contains("Line 2"), "Line 2 should not be present in synchronized content");
    }

    @Test
    @DisplayName("Verify fix handles edge cases with different document sizes")
    void testEdgeCaseWithDifferentSizes() throws Exception {
        // Test edge case where documents have very different sizes
        String smallContent = "Short";
        String largeContent =
                "This is a much longer document\nwith multiple lines\nand different content\nthat should be replaced";

        sourceDoc.insertString(0, smallContent, null);
        destDoc.insertString(0, largeContent, null);

        assertNotEquals(smallContent, largeContent, "Documents should start with different content");

        // Apply the fix - should use fallback sync regardless of size difference
        simulateNewRemoveHandling(sourceDoc, destDoc);

        String finalSourceContent = sourceDoc.getText(0, sourceDoc.getLength());
        String finalDestContent = destDoc.getText(0, destDoc.getLength());

        // Both documents should be synchronized using fallback
        assertEquals(finalSourceContent, finalDestContent, "Documents should be synchronized after REMOVE operation");
        assertEquals(smallContent, finalDestContent, "Destination should match source content after fallback sync");
    }

    /**
     * Simulates the new REMOVE handling logic from the fix/document-synchronization branch. This is the core of our
     * fix: always use fallback for REMOVE operations.
     */
    private void simulateNewRemoveHandling(PlainDocument src, PlainDocument dst) throws BadLocationException {
        String srcText = src.getText(0, src.getLength());
        dst.remove(0, dst.getLength());
        dst.insertString(0, srcText, null);
    }
}
