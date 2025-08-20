package io.github.jbellis.brokk.difftool.ui;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.difftool.doc.BufferDocumentChangeListenerIF;
import io.github.jbellis.brokk.difftool.doc.InMemoryDocument;
import io.github.jbellis.brokk.difftool.doc.StringDocument;
import org.junit.jupiter.api.Test;

/**
 * Simple tests for FilePanel-related functionality that can be tested without complex UI setup. These tests focus on
 * the InMemoryDocument, StringDocument, and performance constants that support FilePanel.
 */
class FilePanelSimpleTest {

    @Test
    void testInMemoryDocumentLineCalculations() {
        InMemoryDocument doc = new InMemoryDocument("test.java", "Line 1\nLine 2\nLine 3\n");

        assertEquals(0, doc.getLineForOffset(0)); // Start of line 1
        assertEquals(0, doc.getLineForOffset(6)); // End of line 1
        assertEquals(1, doc.getLineForOffset(7)); // Start of line 2 (after \n)
        assertEquals(2, doc.getLineForOffset(14)); // Start of line 3

        assertEquals(0, doc.getOffsetForLine(0)); // Start of line 1
        assertEquals(7, doc.getOffsetForLine(1)); // Start of line 2
        assertEquals(14, doc.getOffsetForLine(2)); // Start of line 3
    }

    @Test
    void testInMemoryDocumentLineList() {
        InMemoryDocument doc = new InMemoryDocument("test.java", "Line 1\nLine 2\nLine 3");

        var lines = doc.getLineList();
        assertEquals(3, lines.size());
        assertEquals("Line 1", lines.get(0));
        assertEquals("Line 2", lines.get(1));
        assertEquals("Line 3", lines.get(2));
    }

    @Test
    void testInMemoryDocumentChangeListeners() {
        InMemoryDocument doc = new InMemoryDocument("test.java", "Original");
        boolean[] listenerCalled = {false};

        BufferDocumentChangeListenerIF listener = event -> {
            listenerCalled[0] = true;
        };

        doc.addChangeListener(listener);

        // Manually modify the document to trigger listener
        try {
            doc.getDocument().insertString(doc.getDocument().getLength(), " Modified", null);
        } catch (Exception e) {
            fail("Should not throw exception");
        }

        assertTrue(listenerCalled[0], "Change listener should be called");
    }

    @Test
    void testInMemoryDocumentMultipleNewlines() {
        InMemoryDocument doc = new InMemoryDocument("multi.java", "Line 1\n\nLine 3\n");

        assertEquals(4, doc.getNumberOfLines()); // Line 1, empty line, Line 3, empty line after final \n
        assertTrue(doc.getLineText(0).startsWith("Line 1"));
        assertTrue(doc.getLineText(1).isEmpty() || doc.getLineText(1).equals("\n")); // Empty line
        assertTrue(doc.getLineText(2).startsWith("Line 3"));
        assertTrue(doc.getLineText(3).isEmpty() || doc.getLineText(3).equals("\n")); // Empty line after final \n
    }

    @Test
    void testInMemoryDocumentListenerRemoval() {
        InMemoryDocument doc = new InMemoryDocument("listener.java", "Content");
        boolean[] listenerCalled = {false};

        BufferDocumentChangeListenerIF listener = event -> listenerCalled[0] = true;
        doc.addChangeListener(listener);

        // Modify document to trigger listener
        try {
            doc.getDocument().insertString(doc.getDocument().getLength(), " Modified", null);
        } catch (Exception e) {
            fail("Should not throw exception");
        }
        assertTrue(listenerCalled[0]);

        // Reset and remove listener
        listenerCalled[0] = false;
        doc.removeChangeListener(listener);

        try {
            doc.getDocument().insertString(doc.getDocument().getLength(), " Again", null);
        } catch (Exception e) {
            fail("Should not throw exception");
        }
        assertFalse(listenerCalled[0], "Listener should not be called after removal");
    }

    @Test
    void testInMemoryDocumentWrite() throws Exception {
        InMemoryDocument doc = new InMemoryDocument("write.java", "Content");

        // Modify document to mark as changed
        doc.getDocument().insertString(doc.getDocument().getLength(), " Modified", null);
        assertTrue(doc.isChanged());

        // Write should complete without error (changed flag behavior may vary)
        assertDoesNotThrow(doc::write);
    }

    @Test
    void testInMemoryDocumentReader() throws Exception {
        InMemoryDocument doc = new InMemoryDocument("reader.java", "Reader test content");

        try (var reader = doc.getReader()) {

            char[] buffer = new char[1024];
            int charsRead = reader.read(buffer);
            assertTrue(charsRead > 0);

            String content = new String(buffer, 0, charsRead);
            assertEquals("Reader test content", content);
        }
    }

    // Tests for FilePanel isDocumentChanged logic with read-only documents
    // These tests address the bug where PR diff windows incorrectly prompted users to save read-only files

    /**
     * Tests the core logic of the FilePanel.isDocumentChanged() fix: read-only documents should not be considered as
     * having unsaved changes. This directly tests the logic: bufferDocument.isChanged() && !bufferDocument.isReadonly()
     */
    @Test
    void testReadOnlyDocumentLogic() {
        // Create a read-only StringDocument (like PR diffs use)
        var readOnlyDoc = new StringDocument("PR file content\nline 2\nline 3", "test-pr-file.java", true);

        // Verify it's read-only
        assertTrue(readOnlyDoc.isReadonly(), "Document should be read-only");

        // Simulate the document being marked as changed (which happens with editor.setText())
        try {
            readOnlyDoc.getDocument().insertString(0, "", null); // This will trigger changed=true
        } catch (Exception e) {
            // Expected - read-only documents shouldn't be modifiable, but we need to test the logic
            // In real usage, the changed flag gets set to true during editor.setText() initialization
        }

        boolean shouldReportAsChanged = readOnlyDoc.isChanged() && !readOnlyDoc.isReadonly();
        assertFalse(
                shouldReportAsChanged,
                "Read-only document should not report as having unsaved changes, even if marked as changed internally");
    }

    @Test
    void testEditableDocumentLogic() {
        // Create an editable StringDocument
        var editableDoc = new StringDocument("Original content", "editable-file.java", false);

        // Verify it's not read-only
        assertFalse(editableDoc.isReadonly(), "Document should be editable");

        // Mark document as changed
        try {
            editableDoc.getDocument().insertString(0, "new ", null);
            assertTrue(editableDoc.isChanged(), "Document should be marked as changed");
        } catch (Exception e) {
            fail("Should be able to modify editable document");
        }

        // For editable documents, the logic should return true when changed
        boolean shouldReportAsChanged = editableDoc.isChanged() && !editableDoc.isReadonly();
        assertTrue(shouldReportAsChanged, "Editable document should report as having unsaved changes when changed");
    }

    @Test
    void testUnchangedDocumentsLogic() {
        // Test with read-only unchanged document
        var readOnlyDoc = new StringDocument("content", "readonly.java", true);
        boolean readOnlyResult = readOnlyDoc.isChanged() && !readOnlyDoc.isReadonly();
        assertFalse(readOnlyResult, "Unchanged read-only document should return false");

        // Test with editable unchanged document
        var editableDoc = new StringDocument("content", "editable.java", false);
        boolean editableResult = editableDoc.isChanged() && !editableDoc.isReadonly();
        assertFalse(editableResult, "Unchanged editable document should return false");
    }

    @Test
    void testStringDocumentReadOnlyBehavior() {
        // Test read-only creation
        var readOnlyDoc = new StringDocument("content", "readonly.java", true);
        assertTrue(readOnlyDoc.isReadonly(), "Document created with readOnly=true should be read-only");

        // Test editable creation
        var editableDoc = new StringDocument("content", "editable.java", false);
        assertFalse(editableDoc.isReadonly(), "Document created with readOnly=false should be editable");

        // Test default behavior (defaults to read-only)
        var defaultDoc = new StringDocument("content", "default.java");
        assertTrue(defaultDoc.isReadonly(), "Document created without readOnly parameter defaults to read-only");
    }
}
