package io.github.jbellis.brokk.gui.search;

import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic unit tests for MarkdownOutputPanelSearchableComponent.
 * Tests the core functionality without complex mocking.
 */
public class MarkdownOutputPanelSearchableComponentBasicTest {

    private MarkdownOutputPanel panel1;
    private MarkdownOutputPanel panel2;
    private MarkdownOutputPanelSearchableComponent searchComponent;

    @BeforeEach
    void setUp() {
        panel1 = new MarkdownOutputPanel();
        panel2 = new MarkdownOutputPanel();
    }

    @Test
    void testWrapSinglePanel() {
        var wrapped = MarkdownOutputPanelSearchableComponent.wrap(panel1);
        
        assertNotNull(wrapped);
        assertInstanceOf(MarkdownOutputPanelSearchableComponent.class, wrapped);
    }

    @Test
    void testGetTextWithEmptyPanels() {
        searchComponent = new MarkdownOutputPanelSearchableComponent(List.of(panel1, panel2));
        
        String result = searchComponent.getText();
        assertEquals("", result);
    }

    @Test
    void testGetSelectedTextWithEmptyPanels() {
        searchComponent = new MarkdownOutputPanelSearchableComponent(List.of(panel1, panel2));
        
        String result = searchComponent.getSelectedText();
        assertNull(result);
    }

    @Test
    void testRequestFocusWithEmptyPanels() {
        searchComponent = new MarkdownOutputPanelSearchableComponent(List.of());
        
        // Should not throw exception
        assertDoesNotThrow(() -> searchComponent.requestFocusInWindow());
    }

    @Test
    void testRequestFocusWithPanels() {
        searchComponent = new MarkdownOutputPanelSearchableComponent(List.of(panel1));
        
        // Should not throw exception
        assertDoesNotThrow(() -> searchComponent.requestFocusInWindow());
    }

    @Test
    void testGetComponentWithEmptyPanels() {
        searchComponent = new MarkdownOutputPanelSearchableComponent(List.of());
        
        JComponent result = searchComponent.getComponent();
        
        assertInstanceOf(JPanel.class, result);
    }

    @Test
    void testGetComponentWithPanels() {
        searchComponent = new MarkdownOutputPanelSearchableComponent(List.of(panel1));
        
        JComponent result = searchComponent.getComponent();
        
        assertEquals(panel1, result);
    }

    @Test
    void testHighlightAllWithEmptySearchText() {
        searchComponent = new MarkdownOutputPanelSearchableComponent(List.of(panel1));
        
        // Should not throw exception with empty search text
        assertDoesNotThrow(() -> searchComponent.highlightAll("", false));
        assertDoesNotThrow(() -> searchComponent.highlightAll("   ", true));
        assertDoesNotThrow(() -> searchComponent.highlightAll(null, false));
    }

    @Test
    void testClearHighlights() {
        searchComponent = new MarkdownOutputPanelSearchableComponent(List.of(panel1));
        
        // Should not throw exception
        assertDoesNotThrow(() -> searchComponent.clearHighlights());
    }

    @Test
    void testFindNextWithInvalidSearchTerm() {
        searchComponent = new MarkdownOutputPanelSearchableComponent(List.of(panel1));
        
        // Should return false for navigation without proper search setup
        boolean result = searchComponent.findNext("test", false, true);
        assertFalse(result);
    }

    @Test
    void testCenterCaretInView() {
        searchComponent = new MarkdownOutputPanelSearchableComponent(List.of(panel1));
        
        // Should not throw exception even with no matches
        assertDoesNotThrow(() -> searchComponent.centerCaretInView());
    }

    @Test
    void testSearchCompleteCallbackSetterGetter() {
        searchComponent = new MarkdownOutputPanelSearchableComponent(List.of(panel1));
        SearchableComponent.SearchCompleteCallback callback = (total, current) -> {};
        
        searchComponent.setSearchCompleteCallback(callback);
        
        assertEquals(callback, searchComponent.getSearchCompleteCallback());
    }

    @Test
    void testCaretPositionWithNoFocusedComponent() {
        searchComponent = new MarkdownOutputPanelSearchableComponent(List.of(panel1));
        
        // Should return 0 when no focused component found
        int position = searchComponent.getCaretPosition();
        assertEquals(0, position);
    }

    @Test
    void testSetCaretPositionWithNoFocusedComponent() {
        searchComponent = new MarkdownOutputPanelSearchableComponent(List.of(panel1));
        
        // Should not throw exception
        assertDoesNotThrow(() -> searchComponent.setCaretPosition(10));
    }

    @Test
    void testPrintDocumentStructureDoesNotThrow() {
        searchComponent = new MarkdownOutputPanelSearchableComponent(List.of(panel1));
        
        // Should not throw exception even with empty panels
        assertDoesNotThrow(() -> searchComponent.printDocumentStructure());
    }

    @Test
    void testEmptyPanelListHandling() {
        var emptySearchComponent = new MarkdownOutputPanelSearchableComponent(List.of());
        
        // Should handle empty panel list gracefully
        assertDoesNotThrow(() -> emptySearchComponent.highlightAll("test", false));
        assertDoesNotThrow(() -> emptySearchComponent.clearHighlights());
        assertDoesNotThrow(() -> emptySearchComponent.findNext("test", false, true));
        
        assertEquals("", emptySearchComponent.getText());
        assertNull(emptySearchComponent.getSelectedText());
    }

    @Test
    void testCallbackNotification() {
        var callbackCalled = new boolean[]{false};
        var totalMatches = new int[]{0};
        var currentMatch = new int[]{0};
        
        SearchableComponent.SearchCompleteCallback callback = (total, current) -> {
            callbackCalled[0] = true;
            totalMatches[0] = total;
            currentMatch[0] = current;
        };
        
        searchComponent = new MarkdownOutputPanelSearchableComponent(List.of(panel1));
        searchComponent.setSearchCompleteCallback(callback);
        
        // Trigger a search
        searchComponent.highlightAll("test", false);
        
        // Callback should be called (though with 0 matches due to empty panels)
        assertTrue(callbackCalled[0]);
        assertEquals(0, totalMatches[0]);
        assertEquals(0, currentMatch[0]);
    }
}