package io.github.jbellis.brokk.gui.search;

import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Core basic tests for MarkdownSearchableComponent.
 */
public class MarkdownSearchableComponentBasicTest {

    private MarkdownOutputPanel panel1;
    private MarkdownOutputPanel panel2;
    private MarkdownSearchableComponent searchComponent;

    @BeforeEach
    void setUp() {
        panel1 = new MarkdownOutputPanel();
        panel2 = new MarkdownOutputPanel();
    }

    @Test
    void testEmptyPanelListHandling() {
        var emptySearchComponent = new MarkdownSearchableComponent(List.of());

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

        searchComponent = new MarkdownSearchableComponent(List.of(panel1));
        searchComponent.setSearchCompleteCallback(callback);

        // Trigger a search
        searchComponent.highlightAll("test", false);

        // Callback should be called (though with 0 matches due to empty panels)
        assertTrue(callbackCalled[0]);
        assertEquals(0, totalMatches[0]);
        assertEquals(0, currentMatch[0]);
    }
}