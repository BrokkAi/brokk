package io.github.jbellis.brokk.gui.search;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** Core basic tests for MarkdownSearchableComponent. */
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
    var emptySearchComponent = new MarkdownSearchableComponent(List.of());

    // Should handle empty panel list gracefully
    assertDoesNotThrow(() -> emptySearchComponent.highlightAll("test", false));
    assertDoesNotThrow(() -> emptySearchComponent.clearHighlights());
    assertDoesNotThrow(() -> emptySearchComponent.findNext("test", false, true));

    assertEquals("", emptySearchComponent.getText());
    assertEquals("", emptySearchComponent.getSelectedText());
  }

  @Test
  void testCallbackNotification() throws Exception {
    searchComponent = new MarkdownSearchableComponent(List.of(panel1));

    CountDownLatch searchComplete = new CountDownLatch(1);
    AtomicBoolean callbackCalled = new AtomicBoolean(false);
    AtomicInteger totalMatches = new AtomicInteger(-1);
    AtomicInteger currentMatch = new AtomicInteger(-1);

    SearchableComponent.SearchCompleteCallback callback =
        (total, current) -> {
          callbackCalled.set(true);
          totalMatches.set(total);
          currentMatch.set(current);
          searchComplete.countDown();
        };

    searchComponent.setSearchCompleteCallback(callback);

    // Trigger a search asynchronously
    SwingUtilities.invokeLater(() -> searchComponent.highlightAll("test", false));

    // Wait for callback
    assertTrue(
        searchComplete.await(10, TimeUnit.SECONDS), "Callback should be called within timeout");
    assertTrue(callbackCalled.get(), "Callback should be called");
    assertEquals(0, totalMatches.get(), "Should have 0 matches for empty panels");
    assertEquals(0, currentMatch.get(), "Should have current match index 0 for no matches");
  }
}
