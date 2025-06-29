package io.github.jbellis.brokk.gui.search;

import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import org.assertj.swing.junit.testcase.AssertJSwingJUnitTestCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import org.assertj.swing.fixture.FrameFixture;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.swing.fixture.Containers.showInFrame;

/**
 * Core basic tests for MarkdownSearchableComponent.
 */
public class MarkdownSearchableComponentBasicTest extends AssertJSwingJUnitTestCase {

    private MarkdownOutputPanel panel1;
    private MarkdownOutputPanel panel2;
    private MarkdownSearchableComponent searchComponent;
    private FrameFixture window;

    @BeforeEach
    public void beforeEach() {
        panel1 = new MarkdownOutputPanel();
        panel2 = new MarkdownOutputPanel();
    }

    @Override
    public void onSetUp() {
        JPanel container = new JPanel();
        container.setLayout(new java.awt.BorderLayout());
        container.add(panel1, java.awt.BorderLayout.CENTER);
        container.add(panel2, java.awt.BorderLayout.SOUTH);
        window = showInFrame(container);
    }

    @Test
    void testEmptyPanelListHandling() {
        var emptySearchComponent = new MarkdownSearchableComponent(List.of());

        // Should handle empty panel list gracefully
        assertThatCode(() -> emptySearchComponent.highlightAll("test", false)).doesNotThrowAnyException();
        assertThatCode(emptySearchComponent::clearHighlights).doesNotThrowAnyException();
        assertThatCode(() -> emptySearchComponent.findNext("test", false, true)).doesNotThrowAnyException();

        assertThat(emptySearchComponent.getText()).isEqualTo("");
        assertThat(emptySearchComponent.getSelectedText()).isEqualTo("");
    }

    @Test
    void testSearchWithNullCallback() {
        searchComponent = new MarkdownSearchableComponent(List.of(panel1));

        // Should not throw when callback is null
        assertThatCode(() -> {
            searchComponent.setSearchCompleteCallback(null);
            searchComponent.highlightAll("test", false);
        }).doesNotThrowAnyException();
    }

    @Test
    void testCallbackNotification() throws Exception {
        searchComponent = new MarkdownSearchableComponent(List.of(panel1));

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
        assertThat(searchComplete.await(10, TimeUnit.SECONDS))
                .as("Callback should be called within timeout").isTrue();
        assertThat(callbackCalled.get()).as("Callback should be called").isTrue();
        assertThat(totalMatches.get()).as("Should have 0 matches for empty panels").isEqualTo(0);
        assertThat(currentMatch.get()).as("Should have current match index 0 for no matches").isEqualTo(0);
    }

    @Test
    void testNullAndSpecialCharacterSearch() throws Exception {
        searchComponent = new MarkdownSearchableComponent(List.of(panel1));

        // Test null search
        assertThatCode(() -> searchComponent.highlightAll(null, false))
                .doesNotThrowAnyException();

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

            assertThat(searchDone.await(10, TimeUnit.SECONDS))
                    .as("Search for '" + searchTerm + "' should complete")
                    .isTrue();
        }
    }

    @Test
    void testSearchWithNoMatches() throws Exception {
        searchComponent = new MarkdownSearchableComponent(List.of(panel1));

        CountDownLatch searchComplete = new CountDownLatch(1);
        AtomicInteger totalMatches = new AtomicInteger(-1);

        searchComponent.setSearchCompleteCallback((total, current) -> {
            totalMatches.set(total);
            searchComplete.countDown();
        });

        // Search for non-existent term
        SwingUtilities.invokeLater(() -> searchComponent.highlightAll("nonexistentterm123", false));

        assertThat(searchComplete.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(totalMatches.get()).as("Should have no matches").isEqualTo(0);

        // Navigation should fail
        assertThat(searchComponent.findNext("nonexistentterm123", false, true))
                .as("Navigation should fail with no matches")
                .isFalse();
    }
}

