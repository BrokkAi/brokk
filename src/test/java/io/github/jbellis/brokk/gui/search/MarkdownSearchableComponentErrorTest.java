package io.github.jbellis.brokk.gui.search;

import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import javax.swing.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for error handling in MarkdownSearchableComponent.
 */
@Execution(ExecutionMode.SAME_THREAD)
public class MarkdownSearchableComponentErrorTest {

    private MarkdownOutputPanel panel;
    private MarkdownSearchableComponent searchComponent;

    @BeforeEach
    void setUp() {
        panel = new MarkdownOutputPanel();
    }

    @Test
    void testSearchWithNullCallback() {
        searchComponent = new MarkdownSearchableComponent(List.of(panel), null);

        // Should not throw when callback is null
        assertDoesNotThrow(() -> {
            searchComponent.setSearchCompleteCallback(null);
            searchComponent.highlightAll("test", false);
        });
    }

    @Test
    void testNullAndSpecialCharacterSearch() throws Exception {
        searchComponent = new MarkdownSearchableComponent(List.of(panel), null);

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
}