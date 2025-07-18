package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.difftool.doc.InMemoryDocument;
import io.github.jbellis.brokk.difftool.doc.BufferDocumentChangeListenerIF;
import io.github.jbellis.brokk.difftool.performance.PerformanceConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple tests for FilePanel-related functionality that can be tested without complex UI setup.
 * These tests focus on the InMemoryDocument and performance constants that support FilePanel.
 */
class FilePanelSimpleTest {

    @Test
    void testInMemoryDocumentBasicFunctionality() {
        InMemoryDocument doc = new InMemoryDocument("test.java", "Line 1\nLine 2\nLine 3");
        
        assertEquals("test.java", doc.getName());
        assertEquals("test.java", doc.getShortName());
        assertFalse(doc.isReadonly());
        assertFalse(doc.isChanged());
        assertEquals(3, doc.getNumberOfLines());
        assertTrue(doc.getLineText(0).startsWith("Line 1"));
        assertTrue(doc.getLineText(1).startsWith("Line 2"));
        assertTrue(doc.getLineText(2).startsWith("Line 3"));
    }

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
    void testInMemoryDocumentEmptyContent() {
        InMemoryDocument doc = new InMemoryDocument("empty.java", "");
        
        assertEquals(1, doc.getNumberOfLines()); // Empty file still has 1 line
        assertEquals("", doc.getLineText(0));
        assertEquals(0, doc.getLineForOffset(0));
        assertEquals(0, doc.getOffsetForLine(0));
    }

    @Test
    void testInMemoryDocumentSingleLine() {
        InMemoryDocument doc = new InMemoryDocument("single.java", "Single line without newline");
        
        assertEquals(1, doc.getNumberOfLines());
        assertEquals("Single line without newline", doc.getLineText(0));
        assertEquals(0, doc.getLineForOffset(0));
        assertEquals(0, doc.getLineForOffset(10));
        assertEquals(0, doc.getLineForOffset(25)); // End of line
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
    void testInMemoryDocumentReadonlyFlag() {
        InMemoryDocument doc = new InMemoryDocument("readonly.java", "Content");
        
        assertFalse(doc.isReadonly());
        doc.setReadonly(true);
        assertTrue(doc.isReadonly());
        doc.setReadonly(false);
        assertFalse(doc.isReadonly());
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
        assertDoesNotThrow(() -> doc.write());
    }

    @Test
    void testPerformanceConstants() {
        // Test that performance constants are available and reasonable
        assertTrue(PerformanceConstants.DEFAULT_UPDATE_TIMER_DELAY_MS > 0);
        assertTrue(PerformanceConstants.LARGE_FILE_UPDATE_TIMER_DELAY_MS > 0);
        assertTrue(PerformanceConstants.TYPING_STATE_TIMEOUT_MS > 0);
        assertTrue(PerformanceConstants.LARGE_FILE_THRESHOLD_BYTES > 0);
        assertTrue(PerformanceConstants.VIEWPORT_CACHE_VALIDITY_MS > 0);
        
        // Large file timer should be slower than default
        assertTrue(PerformanceConstants.LARGE_FILE_UPDATE_TIMER_DELAY_MS >= 
                  PerformanceConstants.DEFAULT_UPDATE_TIMER_DELAY_MS);
    }

    @Test
    void testInMemoryDocumentPlainDocument() {
        InMemoryDocument doc = new InMemoryDocument("plain.java", "Test content");
        
        assertNotNull(doc.getDocument());
        assertTrue(doc.getDocument().getLength() > 0);
        
        try {
            String text = doc.getDocument().getText(0, doc.getDocument().getLength());
            assertEquals("Test content", text);
        } catch (Exception e) {
            fail("Should be able to get text from document");
        }
    }

    @Test
    void testInMemoryDocumentReader() throws Exception {
        InMemoryDocument doc = new InMemoryDocument("reader.java", "Reader test content");
        
        try (var reader = doc.getReader()) {
            assertNotNull(reader);
            
            char[] buffer = new char[1024];
            int charsRead = reader.read(buffer);
            assertTrue(charsRead > 0);
            
            String content = new String(buffer, 0, charsRead);
            assertEquals("Reader test content", content);
        }
    }
}