package io.github.jbellis.brokk.difftool.performance;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.jbellis.brokk.difftool.performance.SimplePerformanceTest.TestConstants.*;

/**
 * Simple performance tests to verify optimizations work correctly.
 * These tests don't require external dependencies and verify core performance improvements.
 */
class SimplePerformanceTest {

    private PerformanceTestHelper helper;

    @BeforeEach
    void setUp() {
        helper = new PerformanceTestHelper();
    }

    @Test
    void testViewportFiltering_Performance() {
        // Create a large list of deltas
        List<TestDelta> allDeltas = new ArrayList<>();
        for (int i = 0; i < TEST_LARGE_DELTA_COUNT; i++) {
            allDeltas.add(new TestDelta(i * TEST_DELTA_LINE_SPACING, TEST_DELTA_SIZE));
        }

        // Test viewport filtering
        long startTime = System.nanoTime();
        List<TestDelta> visibleDeltas = helper.filterDeltasForViewport(allDeltas, TEST_VIEWPORT_START_LINE, TEST_VIEWPORT_END_LINE);
        long duration = System.nanoTime() - startTime;

        // Should be very fast (sub-millisecond)
        long durationMs = TimeUnit.NANOSECONDS.toMillis(duration);
        assertTrue(durationMs < FAST_OPERATION_THRESHOLD_MS, "Viewport filtering should be very fast: " + durationMs + "ms");

        // Should dramatically reduce number of deltas
        assertTrue(visibleDeltas.size() < TEST_MAX_VISIBLE_DELTAS, "Should filter to small number of deltas: " + visibleDeltas.size());
        assertTrue(visibleDeltas.size() > 0, "Should find some deltas in viewport");

        // Verify correct deltas are included
        for (TestDelta delta : visibleDeltas) {
            assertTrue(delta.intersectsRange(500, 600), "All returned deltas should intersect viewport");
        }
    }

    @Test
    void testViewportFiltering_Accuracy() {
        List<TestDelta> allDeltas = List.of(
            new TestDelta(10, 5),   // Lines 10-14
            new TestDelta(50, 3),   // Lines 50-52
            new TestDelta(100, 2),  // Lines 100-101
            new TestDelta(200, 1)   // Line 200
        );

        // Viewport covering lines 45-55 should only include delta at line 50
        List<TestDelta> visibleDeltas = helper.filterDeltasForViewport(allDeltas, 45, 55);

        assertEquals(1, visibleDeltas.size(), "Should find exactly one delta in viewport");
        assertEquals(50, visibleDeltas.get(0).startLine, "Should find the delta at line 50");
    }

    @Test
    void testFileSizeDetection() {
        // Test file size threshold detection
        assertFalse(helper.isLargeFile(TEST_NORMAL_FILE_SIZE_BYTES));
        assertTrue(helper.isLargeFile(TEST_LARGE_FILE_SIZE_BYTES));
        assertTrue(helper.isLargeFile(TEST_VERY_LARGE_FILE_SIZE_BYTES));
    }

    @Test
    void testAdaptiveTimerDelays() {
        // Test timer delay calculation based on file size

        // Small file
        int delay1 = helper.calculateTimerDelay(TEST_SMALL_FILE_SIZE_BYTES);
        assertEquals(PerformanceConstants.DEFAULT_UPDATE_TIMER_DELAY_MS, delay1, "Small file should use fast timer");

        // Large file
        int delay2 = helper.calculateTimerDelay(TEST_VERY_LARGE_FILE_SIZE_BYTES);
        assertEquals(PerformanceConstants.LARGE_FILE_UPDATE_TIMER_DELAY_MS, delay2, "Large file should use slower timer");
    }

    @Test
    void testViewportCaching() {
        ViewportCache cache = new ViewportCache(TEST_CACHE_VALIDITY_MS);

        // First calculation
        long startTime1 = System.nanoTime();
        ViewportRange range1 = cache.getOrCalculate("test-key", () -> {
            try {
                Thread.sleep(TEST_SIMULATED_CALCULATION_TIME_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ViewportRange(100, 200);
        });
        long duration1 = System.nanoTime() - startTime1;

        // Second calculation (should use cache)
        long startTime2 = System.nanoTime();
        ViewportRange range2 = cache.getOrCalculate("test-key", () -> {
            fail("Should not recalculate when cache is valid");
            return new ViewportRange(0, 0);
        });
        long duration2 = System.nanoTime() - startTime2;

        // Results should be identical
        assertEquals(range1.startLine, range2.startLine);
        assertEquals(range1.endLine, range2.endLine);

        // Cached access should be much faster
        assertTrue(duration2 < duration1 / TEST_EXPECTED_CACHE_SPEED_FACTOR,
                  "Cached access should be much faster: " +
                  TimeUnit.NANOSECONDS.toMillis(duration1) + "ms vs " +
                  TimeUnit.NANOSECONDS.toMillis(duration2) + "ms");
    }

    @Test
    void testLargeDiffPerformance() {
        // Create large file content
        List<String> original = new ArrayList<>();
        List<String> modified = new ArrayList<>();

        // Generate large file
        for (int i = 0; i < TEST_LARGE_FILE_LINE_COUNT; i++) {
            original.add("Original line " + i);
            if (i % TEST_LINE_MODIFICATION_INTERVAL == 0) {
                modified.add("MODIFIED line " + i);
            } else {
                modified.add("Original line " + i);
            }
        }

        // Time the diff operation
        long startTime = System.nanoTime();
        var patch = DiffUtils.diff(original, modified);
        long duration = System.nanoTime() - startTime;

        long durationMs = TimeUnit.NANOSECONDS.toMillis(duration);

        // Should complete in reasonable time
        assertTrue(durationMs < LARGE_DIFF_OPERATION_TIMEOUT_MS, "Large diff should complete in <1s: " + durationMs + "ms");

        // Should detect the correct number of changes
        assertEquals(TEST_EXPECTED_CHANGES_COUNT, patch.getDeltas().size(), "Should detect expected number of changes");

        // Test viewport filtering on the result
        long filterStart = System.nanoTime();
        var visibleDeltas = patch.getDeltas().stream()
            .filter(delta -> {
                var chunk = delta.getSource();
                int deltaStart = chunk.getPosition();
                int deltaEnd = deltaStart + Math.max(1, chunk.size()) - 1;
                return !(deltaEnd < 1000 || deltaStart > 1100); // Viewport 1000-1100
            })
            .toList();
        long filterDuration = System.nanoTime() - filterStart;

        // Filtering should be very fast
        assertTrue(TimeUnit.NANOSECONDS.toMillis(filterDuration) < VIEWPORT_FILTER_OPERATION_THRESHOLD_MS,
                  "Viewport filtering should be very fast");

        // Should dramatically reduce deltas
        assertTrue(visibleDeltas.size() < 10,
                  "Viewport should reduce deltas: " + visibleDeltas.size() + " vs " + patch.getDeltas().size());
    }

    @Test
    void testPerformanceOptimizationStrategy() {
        // Test the strategy selection logic based on file size

        // Small file scenario
        var strategy1 = helper.determineOptimizationStrategy(TEST_SMALL_FILE_SIZE_BYTES);
        assertEquals(OPTIMIZATION_STRATEGY_MINIMAL, strategy1);

        // Large file scenario
        var strategy2 = helper.determineOptimizationStrategy(TEST_LARGE_FILE_SIZE_BYTES);
        assertEquals(OPTIMIZATION_STRATEGY_MODERATE, strategy2);

        // Very large file scenario
        var strategy3 = helper.determineOptimizationStrategy(TEST_HUGE_FILE_SIZE_BYTES);
        assertEquals(OPTIMIZATION_STRATEGY_MODERATE, strategy3);
    }

    // Helper classes and records
    private static class PerformanceTestHelper {

        List<TestDelta> filterDeltasForViewport(List<TestDelta> deltas, int startLine, int endLine) {
            return deltas.stream()
                .filter(delta -> delta.intersectsRange(startLine, endLine))
                .toList();
        }

        int calculateTimerDelay(long fileSize) {
            if (fileSize > PerformanceConstants.LARGE_FILE_THRESHOLD_BYTES) {
                return PerformanceConstants.LARGE_FILE_UPDATE_TIMER_DELAY_MS;
            } else {
                return PerformanceConstants.DEFAULT_UPDATE_TIMER_DELAY_MS;
            }
        }

        String determineOptimizationStrategy(long fileSize) {
            boolean isLargeFile = fileSize > PerformanceConstants.LARGE_FILE_THRESHOLD_BYTES;

            if (isLargeFile) {
                return OPTIMIZATION_STRATEGY_MODERATE;
            } else {
                return OPTIMIZATION_STRATEGY_MINIMAL;
            }
        }

        boolean isLargeFile(long fileSize) {
            return fileSize >= PerformanceConstants.LARGE_FILE_THRESHOLD_BYTES;
        }
    }

    private static class TestDelta {
        final int startLine;
        final int size;

        TestDelta(int startLine, int size) {
            this.startLine = startLine;
            this.size = size;
        }

        boolean intersectsRange(int rangeStart, int rangeEnd) {
            int deltaEnd = startLine + size - 1;
            return !(deltaEnd < rangeStart || startLine > rangeEnd);
        }
    }

    private static class ViewportCache {
        private final long cacheValidityMs;
        private ViewportRange cachedRange;
        private String cachedKey;
        private long lastUpdate;

        ViewportCache(long cacheValidityMs) {
            this.cacheValidityMs = cacheValidityMs;
        }

        ViewportRange getOrCalculate(String key, java.util.function.Supplier<ViewportRange> calculator) {
            long now = System.currentTimeMillis();

            if (cachedRange != null && key.equals(cachedKey) && now - lastUpdate < cacheValidityMs) {
                return cachedRange;
            }

            cachedRange = calculator.get();
            cachedKey = key;
            lastUpdate = now;
            return cachedRange;
        }
    }

    record ViewportRange(int startLine, int endLine) {}

    // Test constants moved from PerformanceConstants to keep production code clean
    public static final class TestConstants {
        // Performance thresholds for testing
        public static final long FAST_OPERATION_THRESHOLD_MS = 10;
        public static final long LARGE_DIFF_OPERATION_TIMEOUT_MS = 1000;
        public static final long VIEWPORT_FILTER_OPERATION_THRESHOLD_MS = 10;

        // Test file sizes
        public static final long TEST_SMALL_FILE_SIZE_BYTES = 1024 * 1024; // 1MB
        public static final long TEST_NORMAL_FILE_SIZE_BYTES = 5 * 1024 * 1024; // 5MB
        public static final long TEST_LARGE_FILE_SIZE_BYTES = 15 * 1024 * 1024; // 15MB
        public static final long TEST_VERY_LARGE_FILE_SIZE_BYTES = 50 * 1024 * 1024; // 50MB
        public static final long TEST_HUGE_FILE_SIZE_BYTES = 100 * 1024 * 1024; // 100MB

        // Test data sizes
        public static final int TEST_LARGE_DELTA_COUNT = 1000;
        public static final int TEST_DELTA_LINE_SPACING = 10;
        public static final int TEST_DELTA_SIZE = 5;
        public static final int TEST_MAX_VISIBLE_DELTAS = 20;
        public static final int TEST_LARGE_FILE_LINE_COUNT = 10000;
        public static final int TEST_LINE_MODIFICATION_INTERVAL = 100;
        public static final int TEST_EXPECTED_CHANGES_COUNT = 100;

        // Test viewport ranges
        public static final int TEST_VIEWPORT_START_LINE = 500;
        public static final int TEST_VIEWPORT_END_LINE = 600;

        // Cache testing
        public static final long TEST_CACHE_VALIDITY_MS = 100;
        public static final long TEST_SIMULATED_CALCULATION_TIME_MS = 150;
        public static final int TEST_EXPECTED_CACHE_SPEED_FACTOR = 5;

        // Optimization strategies
        public static final String OPTIMIZATION_STRATEGY_MINIMAL = "MINIMAL";
        public static final String OPTIMIZATION_STRATEGY_MODERATE = "MODERATE";

        private TestConstants() {} // Prevent instantiation
    }
}
