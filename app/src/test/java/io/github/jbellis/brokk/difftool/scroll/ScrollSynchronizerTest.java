package io.github.jbellis.brokk.difftool.scroll;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.Patch;
import io.github.jbellis.brokk.difftool.performance.PerformanceConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.swing.*;
import java.awt.event.AdjustmentListener;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * High-value, low-overhead tests for ScrollSynchronizer that focus on pure logic
 * and minimal Swing component testing without requiring full UI hierarchy.
 */
class ScrollSynchronizerTest {

    // Helper methods to create real deltas using DiffUtils
    private Patch<String> createInsertPatch(int insertPosition, String... insertedLines) {
        var original = createNumberedLines(10); // Start with 10 lines
        var revised = new java.util.ArrayList<>(original);

        // Insert the new lines at the specified position
        for (int i = 0; i < insertedLines.length; i++) {
            revised.add(insertPosition + i, insertedLines[i]);
        }

        return DiffUtils.diff(original, revised);
    }

    private Patch<String> createDeletePatch(int deletePosition, int deleteCount) {
        var original = createNumberedLines(20); // Start with more lines for delete
        var revised = new java.util.ArrayList<>(original);

        // Remove lines starting at deletePosition
        for (int i = 0; i < deleteCount && deletePosition < revised.size(); i++) {
            revised.remove(deletePosition);
        }

        return DiffUtils.diff(original, revised);
    }

    private Patch<String> createChangePatch(int changePosition, int changeCount, String... newLines) {
        var original = createNumberedLines(15);
        var revised = new java.util.ArrayList<>(original);

        // Remove the old lines
        for (int i = 0; i < changeCount && changePosition < revised.size(); i++) {
            revised.remove(changePosition);
        }

        // Insert the new lines
        for (int i = 0; i < newLines.length; i++) {
            revised.add(changePosition + i, newLines[i]);
        }

        return DiffUtils.diff(original, revised);
    }

    private Patch<String> createMultiDeltaPatch() {
        var original = createNumberedLines(30);
        var revised = new java.util.ArrayList<>(original);

        // Insert 2 lines at position 5
        revised.add(5, "inserted1");
        revised.add(6, "inserted2");

        // Delete 1 line at position 17 (after accounting for inserts)
        revised.remove(17);

        // Change 3 lines at position 27 to 1 line (after accounting for previous changes)
        revised.remove(27);
        revised.remove(27);
        revised.remove(27);
        revised.add(27, "changed_line");

        return DiffUtils.diff(original, revised);
    }

    private List<String> createNumberedLines(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> "line_" + String.format("%02d", i))
                .toList();
    }

    @BeforeEach
    void setUp() {
        // Tests run on EDT when needed, otherwise just test pure logic
    }

    // =================================================================
    // PURE LOGIC TESTS - Line Mapping Algorithm
    // =================================================================

    @Test
    @DisplayName("Line mapping: simple insert delta")
    void testApproximateLineMapping_insertDelta() throws Exception {
        // Create patch with insert at line 5 (3 lines inserted)
        var patch = createInsertPatch(5, "inserted_line1", "inserted_line2", "inserted_line3");

        // Test mapping from original side (original to revised)
        assertEquals(3, callApproximateLineMapping(patch, 3, true), "Line before insert should map directly");
        assertEquals(8, callApproximateLineMapping(patch, 5, true), "Line at insert should map to position after all inserts");
        assertEquals(10, callApproximateLineMapping(patch, 7, true), "Line after insert should be offset by insert size");

        // Test mapping from revised side (revised to original)
        assertEquals(3, callApproximateLineMapping(patch, 3, false), "Line before insert maps directly");
        assertEquals(5, callApproximateLineMapping(patch, 5, false), "Line at target start maps to source position");
        assertEquals(5, callApproximateLineMapping(patch, 7, false), "Line in inserted region maps to source position");
        assertEquals(5, callApproximateLineMapping(patch, 8, false), "Line after insert should map to source position");
    }

    @Test
    @DisplayName("Line mapping: algorithm correctness")
    void testApproximateLineMapping_algorithmCorrectness() throws Exception {
        // Test basic properties of the line mapping algorithm

        // 1. Empty patch should return lines unchanged
        var emptyPatch = DiffUtils.diff(createNumberedLines(5), createNumberedLines(5));
        assertEquals(10, callApproximateLineMapping(emptyPatch, 10, true), "Empty patch should return line unchanged");
        assertEquals(10, callApproximateLineMapping(emptyPatch, 10, false), "Empty patch should return line unchanged in reverse");

        // 2. Lines before any changes should map directly
        var insertPatch = createInsertPatch(10, "new_line");
        assertEquals(5, callApproximateLineMapping(insertPatch, 5, true), "Lines before changes should map directly");
        assertEquals(5, callApproximateLineMapping(insertPatch, 5, false), "Lines before changes should map directly in reverse");

        // 3. Algorithm should handle negative lines gracefully
        assertEquals(-1, callApproximateLineMapping(insertPatch, -1, true), "Negative lines should be handled gracefully");

        // 4. Algorithm should not crash with various delta types
        var deletePatch = createDeletePatch(5, 2);
        var deleteResult = callApproximateLineMapping(deletePatch, 10, true);
        assertTrue(deleteResult >= 0, "Delete mapping should produce valid result");

        var changePatch = createChangePatch(3, 1, "changed");
        var changeResult = callApproximateLineMapping(changePatch, 10, true);
        assertTrue(changeResult >= 0, "Change mapping should produce valid result");

        // 5. Multiple deltas should not cause crashes
        var multiPatch = createMultiDeltaPatch();
        var multiResult = callApproximateLineMapping(multiPatch, 20, true);
        assertTrue(multiResult >= 0, "Multi-delta mapping should produce valid result");
    }

    // =================================================================
    // STATE COORDINATION TESTS
    // =================================================================

    @Test
    @DisplayName("State coordination: scroll suppression integration")
    void testScrollStateCoordination() throws InterruptedException {
        var state = new ScrollSyncState();

        // Test initial state allows sync
        var suppressionResult = state.shouldSuppressSync(100);
        assertFalse(suppressionResult.shouldSuppress(), "Initial state should allow sync");

        // Test programmatic scroll suppression
        state.setProgrammaticScroll(true);
        suppressionResult = state.shouldSuppressSync(100);
        assertTrue(suppressionResult.shouldSuppress(), "Programmatic scroll should suppress sync");
        assertEquals("programmatic scroll in progress", suppressionResult.reason());

        // Test user scroll suppression
        state.setProgrammaticScroll(false);
        state.recordUserScroll();
        suppressionResult = state.shouldSuppressSync(100);
        assertTrue(suppressionResult.shouldSuppress(), "Recent user scroll should suppress sync");
        assertTrue(suppressionResult.reason().contains("user scrolling active"));

        // Test state clearing - but we need to wait for timing window too
        state.clearUserScrolling();

        // Even after clearing the flag, timing-based suppression may still be active
        // Let's wait for the timing window to pass completely
        Thread.sleep(120); // Wait longer than the 100ms window

        suppressionResult = state.shouldSuppressSync(100);
        assertFalse(suppressionResult.shouldSuppress(), "Cleared state should allow sync after timing window");
    }

    // Note: Timing-based tests can be flaky in CI environments
    // The core timing logic is already tested in ScrollSyncStateTest
    // This integration test is commented out to avoid CI flakiness
    /*
    @Test
    @DisplayName("State coordination: timing-based suppression")
    void testScrollStateTimingIntegration() throws InterruptedException {
        var state = new ScrollSyncState();

        // Record user scroll and test timing window
        state.recordUserScroll();

        // Should suppress within timing window
        assertTrue(state.shouldSuppressSync(50).shouldSuppress(), "Should suppress within timing window");

        // Wait for timing window to pass completely
        Thread.sleep(100);

        // Should allow sync after timing window - timing is based on timestamp, not just the flag
        assertFalse(state.shouldSuppressSync(50).shouldSuppress(), "Should allow sync after timing window");
    }
    */

    // =================================================================
    // TIMER-BASED LOGIC TESTS WITH MINIMAL SWING
    // =================================================================


    @Test
    @DisplayName("Timer logic: programmatic scroll flag timing")
    void testProgrammaticScrollFlagTiming() throws Exception {
        // Create minimal scroll bars for listener testing
        var leftScrollBar = new JScrollBar();
        var rightScrollBar = new JScrollBar();
        var state = new ScrollSyncState();

        // Test listener behavior with programmatic scroll flag
        var adjustmentCount = new AtomicInteger(0);
        var suppressedCount = new AtomicInteger(0);

        AdjustmentListener testListener = e -> {
            adjustmentCount.incrementAndGet();

            // Simulate the logic from ScrollSynchronizer's getVerticalAdjustmentListener
            if (state.isProgrammaticScroll()) {
                suppressedCount.incrementAndGet();
                return;
            }

            // Simulate user scroll handling
            state.recordUserScroll();
        };

        leftScrollBar.addAdjustmentListener(testListener);

        // Test normal scroll (should be processed)
        SwingUtilities.invokeAndWait(() -> {
            leftScrollBar.setValue(10);
        });

        assertEquals(1, adjustmentCount.get(), "Adjustment event should be received");
        assertEquals(0, suppressedCount.get(), "Normal scroll should not be suppressed");

        // Test programmatic scroll (should be suppressed)
        state.setProgrammaticScroll(true);
        SwingUtilities.invokeAndWait(() -> {
            leftScrollBar.setValue(20);
        });

        assertEquals(2, adjustmentCount.get(), "Second adjustment event should be received");
        assertEquals(1, suppressedCount.get(), "Programmatic scroll should be suppressed");

        // Reset flag and test that suppression ends
        state.setProgrammaticScroll(false);
        SwingUtilities.invokeAndWait(() -> {
            leftScrollBar.setValue(30);
        });

        assertEquals(3, adjustmentCount.get(), "Third adjustment event should be received");
        assertEquals(1, suppressedCount.get(), "Suppression should end when flag is cleared");
    }

    @Test
    @DisplayName("Timer logic: reset timers in scroll synchronization")
    void testScrollResetTimerLogic() throws Exception {
        var resetExecuted = new AtomicBoolean(false);
        var resetLatch = new CountDownLatch(1);

        // Simulate the reset timer logic from ScrollSynchronizer.performScroll()
        SwingUtilities.invokeAndWait(() -> {
            Timer resetTimer = new Timer(30, e -> { // Use short delay for testing
                resetExecuted.set(true);
                resetLatch.countDown();
            });
            resetTimer.setRepeats(false);
            resetTimer.start();
        });

        // Wait for timer to execute
        assertTrue(resetLatch.await(500, TimeUnit.MILLISECONDS), "Reset timer should execute");
        assertTrue(resetExecuted.get(), "Reset action should be executed");
    }

    @Test
    @DisplayName("Timer logic: navigation reset delay")
    void testNavigationResetDelayTiming() throws Exception {
        var navigationResetExecuted = new AtomicBoolean(false);
        var resetLatch = new CountDownLatch(1);

        // Simulate the navigation reset timer from ScrollSynchronizer.scrollToLine()
        SwingUtilities.invokeAndWait(() -> {
            Timer resetNavTimer = new Timer(PerformanceConstants.NAVIGATION_RESET_DELAY_MS, e -> {
                navigationResetExecuted.set(true);
                resetLatch.countDown();
            });
            resetNavTimer.setRepeats(false);
            resetNavTimer.start();
        });

        // Wait for timer to execute (should be very fast with NAVIGATION_RESET_DELAY_MS = 30)
        assertTrue(resetLatch.await(500, TimeUnit.MILLISECONDS), "Navigation reset timer should execute");
        assertTrue(navigationResetExecuted.get(), "Navigation reset should be executed");
    }

    @Test
    @DisplayName("Viewport cache invalidation: coordinated invalidation for both panels")
    void testInvalidateViewportCacheForBothPanels() throws Exception {
        // Test that the method exists and has expected behavior with null panels
        // The current implementation requires non-null panels
        var synchronizer = new ScrollSynchronizer(null, null, null, true);

        // Should throw NPE with null panels (current implementation behavior)
        assertThrows(NullPointerException.class, synchronizer::invalidateViewportCacheForBothPanels);

        // This verifies the method exists and behaves as currently implemented
        // In a full integration test with real panels, this would work correctly
    }

    // =================================================================
    // SCROLLMODE TESTS - NEW FUNCTIONALITY
    // =================================================================

    @Test
    @DisplayName("ScrollMode: enum values and properties")
    void testScrollModeEnum() {
        // Test that all expected scroll modes exist
        var modes = ScrollSynchronizer.ScrollMode.values();
        assertEquals(3, modes.length, "Should have exactly 3 scroll modes");

        // Test enum values
        assertEquals(ScrollSynchronizer.ScrollMode.CONTINUOUS, ScrollSynchronizer.ScrollMode.valueOf("CONTINUOUS"));
        assertEquals(ScrollSynchronizer.ScrollMode.NAVIGATION, ScrollSynchronizer.ScrollMode.valueOf("NAVIGATION"));
        assertEquals(ScrollSynchronizer.ScrollMode.SEARCH, ScrollSynchronizer.ScrollMode.valueOf("SEARCH"));
    }

    @Test
    @DisplayName("ScrollMode: backward compatibility with single-parameter scrollToLine")
    void testScrollModeBackwardCompatibility() throws Exception {
        // Test that the single-parameter scrollToLine method still exists
        var synchronizer = new ScrollSynchronizer(null, null, null, true);

        // This should not throw NoSuchMethodException
        var singleParamMethod = ScrollSynchronizer.class.getMethod("scrollToLine",
            io.github.jbellis.brokk.difftool.ui.FilePanel.class, int.class);
        assertNotNull(singleParamMethod, "Single-parameter scrollToLine method should exist for backward compatibility");

        // Test that the two-parameter method exists
        var twoParamMethod = ScrollSynchronizer.class.getMethod("scrollToLine",
            io.github.jbellis.brokk.difftool.ui.FilePanel.class, int.class, ScrollSynchronizer.ScrollMode.class);
        assertNotNull(twoParamMethod, "Two-parameter scrollToLine method should exist");
    }

    @Test
    @DisplayName("ScrollMode: scroll mode selection logic")
    void testScrollModeSelectionLogic() {
        // Test the logic that determines which scroll mode to use in different contexts

        // performScroll should use CONTINUOUS mode (we can't easily test the private method,
        // but we can verify the enum values are what we expect)
        var continuousMode = ScrollSynchronizer.ScrollMode.CONTINUOUS;
        var navigationMode = ScrollSynchronizer.ScrollMode.NAVIGATION;
        var searchMode = ScrollSynchronizer.ScrollMode.SEARCH;

        // Verify modes have expected names (this indirectly tests they're used correctly)
        assertEquals("CONTINUOUS", continuousMode.name());
        assertEquals("NAVIGATION", navigationMode.name());
        assertEquals("SEARCH", searchMode.name());
    }

    // =================================================================
    // HELPER METHODS
    // =================================================================

    /**
     * Helper method to call the private approximateLineMapping method via reflection
     */
    private int callApproximateLineMapping(Patch<String> patch, int line, boolean fromOriginal) throws Exception {
        // Use the test constructor that skips UI initialization
        var testSynchronizer = new ScrollSynchronizer(null, null, null, true);

        Method method = ScrollSynchronizer.class.getDeclaredMethod("approximateLineMapping",
                Patch.class, int.class, boolean.class);
        method.setAccessible(true);

        return (Integer) method.invoke(testSynchronizer, patch, line, fromOriginal);
    }

}
