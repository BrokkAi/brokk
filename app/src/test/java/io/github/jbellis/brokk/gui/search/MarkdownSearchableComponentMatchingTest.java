package io.github.jbellis.brokk.gui.search;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** Tests for search matching functionality in MarkdownSearchableComponent. */
@Execution(ExecutionMode.SAME_THREAD)
public class MarkdownSearchableComponentMatchingTest {

    private MarkdownOutputPanel panel;
    private MarkdownSearchableComponent searchComponent;

    @BeforeEach
    void setUp() {
        panel = new MarkdownOutputPanel();
    }

    @Test
    void testSearchWithNoMatches() throws Exception {
        searchComponent = new MarkdownSearchableComponent(List.of(panel));

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
        assertFalse(
                searchComponent.findNext("nonexistentterm123", false, true), "Navigation should fail with no matches");
    }
}
