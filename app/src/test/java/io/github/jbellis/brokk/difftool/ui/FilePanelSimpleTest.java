package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.difftool.doc.InMemoryDocument;
import io.github.jbellis.brokk.difftool.doc.BufferDocumentChangeListenerIF;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple tests for FilePanel-related functionality that can be tested without complex UI setup.
 * These tests focus on the InMemoryDocument and performance constants that support FilePanel.
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
}
