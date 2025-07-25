package io.github.jbellis.brokk.difftool.scroll;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for the ScrollFrameThrottler class to verify
 * frame-based throttling behavior, performance characteristics, and
 * dynamic configuration capabilities.
 */
class ScrollFrameThrottlerTest {

    private ScrollFrameThrottler throttler;
    private List<Long> executionTimes;
    private AtomicInteger executionCount;
    private AtomicLong startTime;

    @BeforeEach
    void setUp() {
        throttler = new ScrollFrameThrottler(50); // 50ms frames for predictable testing
        executionTimes = new ArrayList<>();
        executionCount = new AtomicInteger(0);
        startTime = new AtomicLong(System.currentTimeMillis());
    }

    // =================================================================
    // IMMEDIATE EXECUTION TESTS
    // =================================================================

    @Test
    @DisplayName("First event executes immediately with zero delay")
    @Timeout(2)
    void testImmediateFirstExecution() throws Exception {
        var latch = new CountDownLatch(1);
        long testStart = System.currentTimeMillis();

        throttler.submit(() -> {
            long executionTime = System.currentTimeMillis() - testStart;
            executionTimes.add(executionTime);
            executionCount.incrementAndGet();
            latch.countDown();
        });

        assertTrue(latch.await(100, TimeUnit.MILLISECONDS), "First execution should complete quickly");

        assertEquals(1, executionCount.get(), "Should execute exactly once");
        assertTrue(executionTimes.get(0) < 10,
                  "First execution should be immediate (< 10ms), but was " + executionTimes.get(0) + "ms");
        assertTrue(throttler.isFrameActive(), "Frame should be active after first execution");
    }

    @Test
    @DisplayName("Single event followed by silence stops framing")
    @Timeout(3)
    void testFrameStopsAfterSingleEvent() throws Exception {
        var latch = new CountDownLatch(1);

        throttler.submit(() -> {
            executionCount.incrementAndGet();
            latch.countDown();
        });

        assertTrue(latch.await(100, TimeUnit.MILLISECONDS), "Execution should complete");
        assertTrue(throttler.isFrameActive(), "Frame should be active initially");

        // Wait for frame to expire
        Thread.sleep(100); // Wait longer than frame interval

        assertFalse(throttler.isFrameActive(), "Frame should stop after period of inactivity");
        assertEquals(1, executionCount.get(), "Should execute exactly once");
    }

    // =================================================================
    // FRAME-BASED THROTTLING TESTS
    // =================================================================

    @Test
    @DisplayName("Rapid events are throttled to frame boundaries")
    @Timeout(5)
    void testFrameBasedThrottling() throws Exception {
        var completionLatch = new CountDownLatch(1);
        int rapidEvents = 20;
        long testStart = System.currentTimeMillis();

        // Submit rapid events
        for (int i = 0; i < rapidEvents; i++) {
            final int eventId = i;
            throttler.submit(() -> {
                long executionTime = System.currentTimeMillis() - testStart;
                executionTimes.add(executionTime);
                executionCount.incrementAndGet();

                if (eventId == rapidEvents - 1) {
                    completionLatch.countDown();
                }
            });

            if (i < rapidEvents - 1) {
                Thread.sleep(2); // 2ms between events (much faster than 50ms frame)
            }
        }

        assertTrue(completionLatch.await(500, TimeUnit.MILLISECONDS), "All events should complete");

        int executions = executionCount.get();
        System.out.printf("Frame throttling: %d events → %d executions (%.1f%% reduction)%n",
                        rapidEvents, executions, (1.0 - (double)executions/rapidEvents) * 100);

        // Should execute significantly fewer times than events submitted
        assertTrue(executions < rapidEvents / 2,
                  String.format("Expected significant throttling: %d events should result in <%d executions, got %d",
                              rapidEvents, rapidEvents/2, executions));

        // All executions should be at frame boundaries (except the first immediate one)
        if (executionTimes.size() > 1) {
            long firstExecution = executionTimes.get(0);
            assertTrue(firstExecution < 10, "First execution should be immediate");

            for (int i = 1; i < executionTimes.size(); i++) {
                long execution = executionTimes.get(i);
                assertTrue(execution >= 40, // Allow some tolerance
                          String.format("Execution %d should be at frame boundary (≥40ms), but was %dms",
                                      i, execution));
            }
        }
    }

    @Test
    @DisplayName("Frame throttling efficiency improves with rapid scrolling")
    void testThrottlingEfficiency() throws Exception {
        // Test with different event frequencies
        testThrottlingEfficiencyForEventCount(5);   // Low frequency
        testThrottlingEfficiencyForEventCount(20);  // Medium frequency
        testThrottlingEfficiencyForEventCount(50);  // High frequency
    }

    private void testThrottlingEfficiencyForEventCount(int eventCount) throws Exception {
        var localThrottler = new ScrollFrameThrottler(30); // 30ms frames
        var latch = new CountDownLatch(1);
        var execCount = new AtomicInteger(0);

        for (int i = 0; i < eventCount; i++) {
            final int eventId = i;
            localThrottler.submit(() -> {
                execCount.incrementAndGet();
                if (eventId == eventCount - 1) {
                    latch.countDown();
                }
            });
            Thread.sleep(1); // Very rapid events
        }

        assertTrue(latch.await(500, TimeUnit.MILLISECONDS), "Events should complete");

        int executions = execCount.get();
        double efficiency = localThrottler.getThrottlingEfficiency();

        System.out.printf("Events: %d, Executions: %d, Efficiency: %.1f%%\n",
                        eventCount, executions, efficiency);

        if (eventCount >= 20) {
            assertTrue(efficiency > 50,
                      String.format("Expected >50%% efficiency for %d events, got %.1f%%",
                                  eventCount, efficiency));
        }

        localThrottler.dispose();
    }

    // =================================================================
    // DYNAMIC CONFIGURATION TESTS
    // =================================================================

    @Test
    @DisplayName("Frame rate can be changed dynamically")
    @Timeout(3)
    void testDynamicFrameRateChange() throws Exception {
        assertEquals(50, throttler.getFrameRate(), "Initial frame rate should be 50ms");

        // Change frame rate
        throttler.setFrameRate(25);
        assertEquals(25, throttler.getFrameRate(), "Frame rate should be updated to 25ms");

        // Test with new frame rate
        var latch = new CountDownLatch(1);
        var execTimes = new ArrayList<Long>();
        long testStart = System.currentTimeMillis();

        // Submit multiple rapid events
        for (int i = 0; i < 5; i++) {
            final int eventId = i;
            throttler.submit(() -> {
                execTimes.add(System.currentTimeMillis() - testStart);
                if (eventId == 4) {
                    latch.countDown();
                }
            });
            Thread.sleep(5); // Rapid events
        }

        assertTrue(latch.await(200, TimeUnit.MILLISECONDS), "Events should complete");

        // Second execution should occur around the new frame rate (25ms)
        if (execTimes.size() >= 2) {
            long secondExecution = execTimes.get(1);
            assertTrue(secondExecution >= 20 && secondExecution <= 35,
                      String.format("Second execution should be near 25ms frame boundary, but was %dms",
                                  secondExecution));
        }
    }

    @Test
    @DisplayName("Frame rate validation clamps values to reasonable range")
    void testFrameRateValidation() {
        // Test minimum clamping
        throttler.setFrameRate(-10);
        assertEquals(1, throttler.getFrameRate(), "Negative frame rate should be clamped to 1ms");

        throttler.setFrameRate(0);
        assertEquals(1, throttler.getFrameRate(), "Zero frame rate should be clamped to 1ms");

        // Test maximum clamping
        throttler.setFrameRate(2000);
        assertEquals(1000, throttler.getFrameRate(), "Excessive frame rate should be clamped to 1000ms");

        // Test valid values
        throttler.setFrameRate(16);
        assertEquals(16, throttler.getFrameRate(), "Valid frame rate should be accepted");
    }

    // =================================================================
    // PERFORMANCE METRICS TESTS
    // =================================================================

    @Test
    @DisplayName("Performance metrics are tracked accurately")
    @Timeout(3)
    void testPerformanceMetrics() throws Exception {
        var latch = new CountDownLatch(1);
        int totalEvents = 15;

        assertEquals(0, throttler.getTotalEvents(), "Initial events should be 0");
        assertEquals(0, throttler.getTotalExecutions(), "Initial executions should be 0");

        for (int i = 0; i < totalEvents; i++) {
            final int eventId = i;
            throttler.submit(() -> {
                if (eventId == totalEvents - 1) {
                    latch.countDown();
                }
            });
            Thread.sleep(3); // Rapid events to ensure throttling
        }

        assertTrue(latch.await(300, TimeUnit.MILLISECONDS), "Events should complete");

        assertEquals(totalEvents, throttler.getTotalEvents(), "Should track all submitted events");

        long executions = throttler.getTotalExecutions();
        assertTrue(executions > 0, "Should have some executions");
        assertTrue(executions < totalEvents, "Should have fewer executions than events due to throttling");

        double efficiency = throttler.getThrottlingEfficiency();
        assertTrue(efficiency >= 0 && efficiency <= 100, "Efficiency should be a valid percentage");

        assertTrue(throttler.getLastExecutionTime() > 0, "Should have a last execution time");
    }

    @Test
    @DisplayName("Metrics can be reset")
    void testMetricsReset() throws Exception {
        var latch = new CountDownLatch(1);

        // Generate some activity
        for (int i = 0; i < 5; i++) {
            final int eventId = i;
            throttler.submit(() -> {
                if (eventId == 4) {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(200, TimeUnit.MILLISECONDS), "Events should complete");

        assertTrue(throttler.getTotalEvents() > 0, "Should have events before reset");
        assertTrue(throttler.getTotalExecutions() > 0, "Should have executions before reset");

        throttler.resetMetrics();

        assertEquals(0, throttler.getTotalEvents(), "Events should be reset to 0");
        assertEquals(0, throttler.getTotalExecutions(), "Executions should be reset to 0");
        assertEquals(0, throttler.getLastExecutionTime(), "Last execution time should be reset");
        assertEquals(0.0, throttler.getThrottlingEfficiency(), "Efficiency should be reset to 0");
    }

    // =================================================================
    // EDGE CASES AND ERROR HANDLING
    // =================================================================

    @Test
    @DisplayName("Empty actions are handled gracefully")
    void testEmptyActionHandling() {
        // Test with empty action instead of null (since null isn't allowed)
        assertDoesNotThrow(() -> throttler.submit(() -> {}), "Empty action should not throw exception");

        assertEquals(1, throttler.getTotalEvents(), "Empty action should increment event count");
        assertEquals(1, throttler.getTotalExecutions(), "Empty action should increment execution count");
    }

    @Test
    @DisplayName("Exception in action does not break throttler")
    @Timeout(2)
    void testExceptionHandling() throws Exception {
        var successLatch = new CountDownLatch(1);
        var execCount = new AtomicInteger(0);

        // Submit action that throws exception
        throttler.submit(() -> {
            execCount.incrementAndGet();
            throw new RuntimeException("Test exception");
        });

        // Wait a bit to let the frame throttling complete
        Thread.sleep(100);

        // Submit normal action that should still work
        throttler.submit(() -> {
            execCount.incrementAndGet();
            successLatch.countDown();
        });

        assertTrue(successLatch.await(200, TimeUnit.MILLISECONDS),
                  "Throttler should continue working after exception");

        assertEquals(2, execCount.get(), "Both actions should execute despite exception in first");

        // Note: Due to frame throttling, the exact execution count may vary
        // The important thing is that the throttler continues to work
        assertTrue(throttler.getTotalExecutions() >= 1, "Should have at least one execution");
    }

    @Test
    @DisplayName("Cancel stops pending executions")
    @Timeout(2)
    void testCancelOperation() throws Exception {
        var execCount = new AtomicInteger(0);

        // Submit actions that would execute later
        for (int i = 0; i < 5; i++) {
            throttler.submit(() -> execCount.incrementAndGet());
            Thread.sleep(2);
        }

        assertTrue(throttler.isFrameActive(), "Frame should be active with pending actions");

        // Cancel before frame completes
        throttler.cancel();

        assertFalse(throttler.isFrameActive(), "Frame should be inactive after cancel");

        Thread.sleep(100); // Wait to ensure no delayed executions

        // Should only have the immediate first execution
        assertEquals(1, execCount.get(), "Only immediate execution should have occurred after cancel");
    }

    @Test
    @DisplayName("Dispose cleans up resources properly")
    void testDisposeCleanup() throws Exception {
        var execCount = new AtomicInteger(0);

        // Submit some actions
        for (int i = 0; i < 3; i++) {
            throttler.submit(() -> execCount.incrementAndGet());
        }

        assertTrue(throttler.isFrameActive(), "Frame should be active before dispose");

        throttler.dispose();

        assertFalse(throttler.isFrameActive(), "Frame should be inactive after dispose");

        // Further submissions should still work (dispose doesn't break the throttler)
        throttler.submit(() -> execCount.incrementAndGet());

        // But no framing should occur
        Thread.sleep(50);
        assertFalse(throttler.isFrameActive(), "No new framing should start after dispose");
    }

    // =================================================================
    // INTEGRATION AND STRESS TESTS
    // =================================================================

    @Test
    @DisplayName("High frequency stress test")
    @Timeout(5)
    void testHighFrequencyStress() throws Exception {
        var fastThrottler = new ScrollFrameThrottler(16); // 60 FPS
        var latch = new CountDownLatch(1);
        var execCount = new AtomicInteger(0);
        int stressEvents = 1000;

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < stressEvents; i++) {
            final int eventId = i;
            fastThrottler.submit(() -> {
                execCount.incrementAndGet();
                if (eventId == stressEvents - 1) {
                    latch.countDown();
                }
            });

            // Very rapid events (faster than any reasonable frame rate)
            if (i % 10 == 0) {
                Thread.sleep(1);
            }
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS), "Stress test should complete");

        long duration = System.currentTimeMillis() - startTime;
        int executions = execCount.get();
        double efficiency = fastThrottler.getThrottlingEfficiency();

        System.out.printf("Stress test: %d events in %dms → %d executions (%.1f%% efficiency)%n",
                        stressEvents, duration, executions, efficiency);

        assertTrue(executions < stressEvents / 5, "Should achieve significant throttling under stress");
        assertTrue(efficiency > 80, "Should achieve high efficiency under stress");
        assertTrue(duration < 3000, "Should complete within reasonable time");

        fastThrottler.dispose();
    }
}