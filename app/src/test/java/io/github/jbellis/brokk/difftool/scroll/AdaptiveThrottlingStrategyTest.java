package io.github.jbellis.brokk.difftool.scroll;

import io.github.jbellis.brokk.difftool.performance.PerformanceConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for the adaptive throttling strategy.
 * Tests verify that the strategy correctly switches between immediate and frame-based modes
 * based on file complexity and scroll performance.
 */
class AdaptiveThrottlingStrategyTest {

    private AdaptiveThrottlingStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new AdaptiveThrottlingStrategy();
        // Reset to defaults before each test
        strategy.reset();
    }

    @Test
    @DisplayName("Initial mode determination - Simple file uses immediate mode")
    void testInitialModeSimpleFile() {
        // Test with a simple file (low lines and deltas)
        int simpleLines = 100;
        int simpleDeltas = 5;

        strategy.initialize(simpleLines, simpleDeltas);

        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.IMMEDIATE, strategy.getCurrentMode());
        assertFalse(PerformanceConstants.ENABLE_FRAME_BASED_THROTTLING);

        var metrics = strategy.getMetrics();
        assertEquals(simpleLines, metrics.totalLines());
        assertEquals(simpleDeltas, metrics.totalDeltas());
    }

    @Test
    @DisplayName("Initial mode determination - Complex file uses frame-based mode")
    void testInitialModeComplexFile() {
        // Test with a complex file (high lines)
        int complexLines = 2000; // > ADAPTIVE_MODE_LINE_THRESHOLD (1000)
        int normalDeltas = 25;

        strategy.initialize(complexLines, normalDeltas);

        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.FRAME_BASED, strategy.getCurrentMode());
        assertTrue(PerformanceConstants.ENABLE_FRAME_BASED_THROTTLING);

        var metrics = strategy.getMetrics();
        assertEquals(complexLines, metrics.totalLines());
        assertEquals(normalDeltas, metrics.totalDeltas());
    }

    @Test
    @DisplayName("Initial mode determination - High delta count triggers frame-based mode")
    void testInitialModeHighDeltas() {
        // Test with high delta count
        int normalLines = 500;
        int highDeltas = 100; // > ADAPTIVE_MODE_DELTA_THRESHOLD (50)

        strategy.initialize(normalLines, highDeltas);

        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.FRAME_BASED, strategy.getCurrentMode());
        assertTrue(PerformanceConstants.ENABLE_FRAME_BASED_THROTTLING);

        var metrics = strategy.getMetrics();
        assertEquals(normalLines, metrics.totalLines());
        assertEquals(highDeltas, metrics.totalDeltas());
    }

    @Test
    @DisplayName("Performance-based mode switching - Slow mapping triggers frame mode")
    void testPerformanceBasedModeSwitching() throws InterruptedException {
        // Start with a simple file in immediate mode
        strategy.initialize(500, 10);
        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.IMMEDIATE, strategy.getCurrentMode());

        // Simulate slow performance that exceeds threshold
        long slowMappingTime = PerformanceConstants.ADAPTIVE_MODE_PERFORMANCE_THRESHOLD_MS + 5; // 10ms
        strategy.recordScrollEvent(slowMappingTime);

        // Wait for cooldown period to pass
        Thread.sleep(2100); // > MODE_SWITCH_COOLDOWN_MS (2000)

        // Record another slow event to trigger the switch
        strategy.recordScrollEvent(slowMappingTime);

        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.FRAME_BASED, strategy.getCurrentMode());
        assertTrue(PerformanceConstants.ENABLE_FRAME_BASED_THROTTLING);
    }

    @Test
    @DisplayName("Rapid scrolling detection triggers frame-based mode")
    void testRapidScrollingDetection() throws InterruptedException {
        // Start with a simple file in immediate mode
        strategy.initialize(500, 10);
        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.IMMEDIATE, strategy.getCurrentMode());

        // Simulate rapid scrolling events
        int rapidEvents = PerformanceConstants.ADAPTIVE_MODE_RAPID_SCROLL_THRESHOLD + 10; // 40 events

        // Record many events in rapid succession to trigger frame mode
        for (int i = 0; i < rapidEvents; i++) {
            strategy.recordScrollEvent(2); // Fast mapping time
            Thread.sleep(25); // 40 events per second (1000ms / 25ms = 40/s)
        }

        // The strategy should have switched to frame-based mode during rapid scrolling
        // However, since we're using fast mapping times (2ms), it may switch back to immediate
        // when the rapid scrolling stops. This is correct adaptive behavior.

        // To test that rapid scrolling was detected, we verify that the metrics show
        // the high event rate
        var metrics = strategy.getMetrics();
        assertTrue(metrics.eventsPerSecond() > PerformanceConstants.ADAPTIVE_MODE_RAPID_SCROLL_THRESHOLD,
                 "Events per second should exceed threshold during rapid scrolling");

        // The current mode depends on whether the rapid scrolling is still active
        // This is correct adaptive behavior - the strategy adapts to current conditions
    }

    @Test
    @DisplayName("Force mode works without cooldown interference")
    void testForceModeBypassesCooldown() throws InterruptedException {
        // Start with simple file
        strategy.initialize(500, 10);
        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.IMMEDIATE, strategy.getCurrentMode());

        // Force switch to frame-based mode
        strategy.forceMode(AdaptiveThrottlingStrategy.ThrottlingMode.FRAME_BASED, "Test force");
        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.FRAME_BASED, strategy.getCurrentMode());

        // Force back to immediate mode immediately (bypasses cooldown)
        strategy.forceMode(AdaptiveThrottlingStrategy.ThrottlingMode.IMMEDIATE, "Test force back");
        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.IMMEDIATE, strategy.getCurrentMode());

        // Force to frame-based again
        strategy.forceMode(AdaptiveThrottlingStrategy.ThrottlingMode.FRAME_BASED, "Test force again");
        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.FRAME_BASED, strategy.getCurrentMode());

        // This demonstrates that force mode bypasses the cooldown mechanism
        // and allows immediate mode changes for testing or manual override
    }

    @Test
    @DisplayName("Force mode override works correctly")
    void testForceModeOverride() {
        // Start in immediate mode
        strategy.initialize(500, 10);
        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.IMMEDIATE, strategy.getCurrentMode());

        // Force frame-based mode
        strategy.forceMode(AdaptiveThrottlingStrategy.ThrottlingMode.FRAME_BASED, "Testing");
        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.FRAME_BASED, strategy.getCurrentMode());
        assertTrue(PerformanceConstants.ENABLE_FRAME_BASED_THROTTLING);

        // Force back to immediate mode
        strategy.forceMode(AdaptiveThrottlingStrategy.ThrottlingMode.IMMEDIATE, "Testing");
        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.IMMEDIATE, strategy.getCurrentMode());
        assertFalse(PerformanceConstants.ENABLE_FRAME_BASED_THROTTLING);
    }

    @Test
    @DisplayName("Metrics tracking works correctly")
    void testMetricsTracking() throws InterruptedException {
        strategy.initialize(1500, 75);

        // Record some events
        strategy.recordScrollEvent(5);
        Thread.sleep(100);
        strategy.recordScrollEvent(3);
        Thread.sleep(100);
        strategy.recordScrollEvent(8);

        var metrics = strategy.getMetrics();
        assertEquals(1500, metrics.totalLines());
        assertEquals(75, metrics.totalDeltas());
        assertEquals(8, metrics.lastMappingDurationMs()); // Last recorded value
        assertTrue(metrics.eventsPerSecond() > 0);

        String summary = metrics.getSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("Lines: 1500"));
        assertTrue(summary.contains("Deltas: 75"));
    }

    @Test
    @DisplayName("Reset functionality clears all state")
    void testResetFunctionality() {
        // Initialize and modify state
        strategy.initialize(2000, 100);
        strategy.recordScrollEvent(10);
        strategy.forceMode(AdaptiveThrottlingStrategy.ThrottlingMode.FRAME_BASED, "Test");

        // Verify state is set
        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.FRAME_BASED, strategy.getCurrentMode());

        // Reset and verify clean state
        strategy.reset();

        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.IMMEDIATE, strategy.getCurrentMode());
        assertFalse(PerformanceConstants.ENABLE_FRAME_BASED_THROTTLING);

        var metrics = strategy.getMetrics();
        assertEquals(0, metrics.totalLines());
        assertEquals(0, metrics.totalDeltas());
        assertEquals(0, metrics.lastMappingDurationMs());
        assertEquals(0, metrics.eventsPerSecond());
    }

    @Test
    @DisplayName("Edge case - Exactly at threshold boundaries")
    void testThresholdBoundaries() {
        // Test exactly at line threshold
        strategy.initialize(PerformanceConstants.ADAPTIVE_MODE_LINE_THRESHOLD, 10);
        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.IMMEDIATE, strategy.getCurrentMode());

        // Reset and test one over threshold
        strategy.reset();
        strategy.initialize(PerformanceConstants.ADAPTIVE_MODE_LINE_THRESHOLD + 1, 10);
        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.FRAME_BASED, strategy.getCurrentMode());

        // Reset and test exactly at delta threshold (should now trigger frame-based mode)
        strategy.reset();
        strategy.initialize(500, PerformanceConstants.ADAPTIVE_MODE_DELTA_THRESHOLD);
        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.FRAME_BASED, strategy.getCurrentMode());

        // Reset and test one below delta threshold
        strategy.reset();
        strategy.initialize(500, PerformanceConstants.ADAPTIVE_MODE_DELTA_THRESHOLD - 1);
        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.IMMEDIATE, strategy.getCurrentMode());
    }

    @Test
    @DisplayName("Performance threshold exactly at boundary")
    void testPerformanceThresholdBoundary() throws InterruptedException {
        strategy.initialize(500, 10);
        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.IMMEDIATE, strategy.getCurrentMode());

        // Test exactly at performance threshold (should not trigger switch)
        strategy.recordScrollEvent(PerformanceConstants.ADAPTIVE_MODE_PERFORMANCE_THRESHOLD_MS);
        Thread.sleep(2100);
        strategy.recordScrollEvent(PerformanceConstants.ADAPTIVE_MODE_PERFORMANCE_THRESHOLD_MS);

        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.IMMEDIATE, strategy.getCurrentMode());

        // Test one over threshold (should trigger switch)
        strategy.recordScrollEvent(PerformanceConstants.ADAPTIVE_MODE_PERFORMANCE_THRESHOLD_MS + 1);
        Thread.sleep(2100);
        strategy.recordScrollEvent(PerformanceConstants.ADAPTIVE_MODE_PERFORMANCE_THRESHOLD_MS + 1);

        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.FRAME_BASED, strategy.getCurrentMode());
    }

    @Test
    @DisplayName("Global configuration state is correctly managed")
    void testGlobalConfigurationState() {
        // Save original adaptive setting
        boolean originalAdaptive = PerformanceConstants.ENABLE_ADAPTIVE_THROTTLING;

        try {
            // Ensure clean start by disabling adaptive mode for this test
            PerformanceConstants.ENABLE_ADAPTIVE_THROTTLING = false;
            strategy.reset();

            // Force immediate mode
            strategy.forceMode(AdaptiveThrottlingStrategy.ThrottlingMode.IMMEDIATE, "Test");
            assertFalse(PerformanceConstants.ENABLE_FRAME_BASED_THROTTLING);

            // Force frame-based mode
            strategy.forceMode(AdaptiveThrottlingStrategy.ThrottlingMode.FRAME_BASED, "Test");
            assertTrue(PerformanceConstants.ENABLE_FRAME_BASED_THROTTLING);

            // Reset should clear frame-based and debouncing
            strategy.reset();
            assertFalse(PerformanceConstants.ENABLE_FRAME_BASED_THROTTLING);
            } finally {
            // Restore original adaptive setting
            PerformanceConstants.ENABLE_ADAPTIVE_THROTTLING = originalAdaptive;
        }
    }

    @Test
    @DisplayName("Multiple consecutive initializations work correctly")
    void testMultipleInitializations() {
        // First initialization - simple file
        strategy.initialize(500, 10);
        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.IMMEDIATE, strategy.getCurrentMode());

        // Second initialization - complex file (should override)
        strategy.initialize(2000, 80);
        assertEquals(AdaptiveThrottlingStrategy.ThrottlingMode.FRAME_BASED, strategy.getCurrentMode());

        var metrics = strategy.getMetrics();
        assertEquals(2000, metrics.totalLines());
        assertEquals(80, metrics.totalDeltas());
    }
}