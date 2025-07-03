package io.github.jbellis.brokk.gui.search;

import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import io.github.jbellis.brokk.gui.mop.stream.MockContextManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import javax.swing.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Core basic tests for MarkdownSearchableComponent.
 */
@Execution(ExecutionMode.SAME_THREAD)
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
        var emptySearchComponent = new MarkdownSearchableComponent(List.of(), new MockContextManager());

        // Should handle empty panel list gracefully
        assertDoesNotThrow(() -> emptySearchComponent.highlightAll("test", false));
        assertDoesNotThrow(() -> emptySearchComponent.clearHighlights());
        assertDoesNotThrow(() -> emptySearchComponent.findNext("test", false, true));

        assertEquals("", emptySearchComponent.getText());
        assertEquals("", emptySearchComponent.getSelectedText());
    }

    @Test
    void testCallbackNotification() throws Exception {
        searchComponent = new MarkdownSearchableComponent(List.of(panel1), new MockContextManager());

        CountDownLatch searchComplete = new CountDownLatch(1);
        AtomicBoolean callbackCalled = new AtomicBoolean(false);
        AtomicInteger totalMatches = new AtomicInteger(-1);
        AtomicInteger currentMatch = new AtomicInteger(-1);

        SearchableComponent.SearchCompleteCallback callback = (total, current) -> {
            callbackCalled.set(true);
            totalMatches.set(total);
            currentMatch.set(current);
            searchComplete.countDown();
        };

        searchComponent.setSearchCompleteCallback(callback);

        // Trigger a search asynchronously
        SwingUtilities.invokeLater(() -> searchComponent.highlightAll("test", false));

        // Wait for callback
        assertTrue(searchComplete.await(10, TimeUnit.SECONDS), "Callback should be called within timeout");
        assertTrue(callbackCalled.get(), "Callback should be called");
        assertEquals(0, totalMatches.get(), "Should have 0 matches for empty panels");
        assertEquals(0, currentMatch.get(), "Should have current match index 0 for no matches");
    }

    @Test
    void testNullAndSpecialCharacterSearch() throws Exception {
        searchComponent = new MarkdownSearchableComponent(List.of(panel1), new MockContextManager());

        // Test special characters
        String[] specialSearches = {"$", "@#", "^&*", "()", "\\n", "\t"};

        for (String special : specialSearches) {
            final String searchTerm = special;
            CountDownLatch searchDone = new CountDownLatch(1);
            searchComponent.setSearchCompleteCallback((total, current) -> {
                searchDone.countDown();
            });

            SwingUtilities.invokeLater(() ->
                                               searchComponent.highlightAll(searchTerm, false));

            assertTrue(searchDone.await(10, TimeUnit.SECONDS),
                       "Search for '" + searchTerm + "' should complete");
        }
    }

    @Test
    void testSearchWithNoMatches() throws Exception {
        searchComponent = new MarkdownSearchableComponent(List.of(panel1), new MockContextManager());

        CountDownLatch searchComplete = new CountDownLatch(1);
        AtomicInteger totalMatches = new AtomicInteger(-1);

        searchComponent.setSearchCompleteCallback((total, current) -> {
            totalMatches.set(total);
            searchComplete.countDown();
        });

        // Search for non-existent term
        SwingUtilities.invokeLater(() -> searchComponent.highlightAll("nonexistentterm123", false));

        assertTrue(searchComplete.await(10, TimeUnit.SECONDS));
        assertEquals(0, totalMatches.get(), "Should have no matches");

        // Navigation should fail
        assertFalse(searchComponent.findNext("nonexistentterm123", false, true),
                    "Navigation should fail with no matches");
    }
}