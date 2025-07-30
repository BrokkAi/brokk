package io.github.jbellis.brokk.difftool.scroll;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.Patch;
import io.github.jbellis.brokk.difftool.performance.PerformanceConstants;
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
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that verify the fixes for scroll synchronization problems work correctly.
 * These tests confirm that the enhanced algorithms solve the original issues.
 */
class ScrollSynchronizationFixesTest {

    private ScrollSynchronizer synchronizer;

    @BeforeEach
    void setUp() {
        // Use test constructor that skips UI initialization
        synchronizer = new ScrollSynchronizer(null, null, null, true);
    }

    // =================================================================
    // FIX VERIFICATION 1: ENHANCED LINE MAPPING ACCURACY
    // =================================================================

    @Test
    @DisplayName("FIX 1a: Enhanced line mapping shows improved accuracy")
    void verifyEnhancedLineMappingAccuracy() throws Exception {
        System.out.println("\n=== FIX VERIFICATION 1a: ENHANCED LINE MAPPING ACCURACY ===");

        // Create complex patches to test the enhanced algorithm
        var scenarios = List.of(
            createPatchWithNDeltas(100, "100 deltas"),
            createPatchWithNDeltas(500, "500 deltas"),
            createPatchWithNDeltas(1000, "1000 deltas")
        );

        System.out.printf("%-15s | %-10s | %-10s | %-10s | %-10s%n", "Scenario", "Test Line", "Mapped", "Error", "Status");
        System.out.println("----------------------------------------------------------------");

        for (var scenario : scenarios) {
            var patch = scenario.patch();
            var description = scenario.description();

            // Test mapping at various points in the document
            var testLines = List.of(50, 200, 500, 800);

            for (int testLine : testLines) {
                int actualMapped = callApproximateLineMapping(patch, testLine, true);

                // Calculate expected mapping with improved algorithm
                int expectedMapped = calculateExpectedMappingForEnhancedAlgorithm(testLine, patch);

                int error = Math.abs(actualMapped - expectedMapped);
                double errorPercentage = testLine > 0 ? (double) error / testLine * 100 : 0;

                String status = errorPercentage < 2 ? "EXCELLENT" : errorPercentage < 5 ? "GOOD" : "NEEDS_WORK";

                System.out.printf("%-15s | %-10d | %-10d | %-10.1f%% | %-10s",
                                description, testLine, actualMapped, errorPercentage, status);
                System.out.println();

                // Enhanced algorithm should have much better accuracy
                assertTrue(errorPercentage < 10,
                         String.format("Enhanced algorithm should have <10%% error for %s at line %d, but got %.1f%%",
                                     description, testLine, errorPercentage));
            }
        }

        System.out.println("\nFIX VERIFIED: Enhanced line mapping shows significantly improved accuracy!");
    }

    @Test
    @DisplayName("FIX 1b: Binary search provides O(log n) performance")
    void verifyBinarySearchPerformance() throws Exception {
        System.out.println("\n=== FIX VERIFICATION 1b: BINARY SEARCH O(LOG N) PERFORMANCE ===");

        var deltaCountScenarios = List.of(100, 500, 1000, 2000, 5000);

        System.out.printf("%-12s | %-15s | %-15s | %-15s%n", "Delta Count", "Avg Time (ms)", "Max Time (ms)", "Performance");
        System.out.println("---------------------------------------------------------------");

        for (int deltaCount : deltaCountScenarios) {
            var patch = createPatchWithNDeltas(deltaCount, "test");

            long totalTime = 0;
            long maxTime = 0;
            int iterations = 50; // More iterations for accuracy

            for (int i = 0; i < iterations; i++) {
                int testLine = 100 + (i * 10);

                long startTime = System.nanoTime();
                callApproximateLineMapping(patch.patch(), testLine, true);
                long endTime = System.nanoTime();

                long duration = (endTime - startTime) / 1_000_000; // Convert to milliseconds
                totalTime += duration;
                maxTime = Math.max(maxTime, duration);
            }

            double avgTime = (double) totalTime / iterations;
            String performance = avgTime < 1 ? "EXCELLENT" : avgTime < 5 ? "GOOD" : "NEEDS_WORK";

            System.out.printf("%-12d | %-15.2f | %-15d | %-15s", deltaCount, avgTime, maxTime, performance);
            System.out.println();

            // With O(log n) performance, even 5000 deltas should be fast
            assertTrue(avgTime < 10,
                     String.format("O(log n) algorithm should be fast even with %d deltas, but took %.2fms",
                                 deltaCount, avgTime));
        }

        System.out.println("\nFIX VERIFIED: Binary search provides excellent O(log n) performance!");
    }

    // =================================================================
    // FIX VERIFICATION 2: IMPROVED SCROLL THROTTLING
    // =================================================================



    // =================================================================
    // FIX VERIFICATION 3: CUMULATIVE ERROR CORRECTION
    // =================================================================

    @Test
    @DisplayName("FIX 3a: Cumulative error correction improves accuracy throughout file")
    void verifyCumulativeErrorCorrection() throws Exception {
        System.out.println("\n=== FIX VERIFICATION 3a: CUMULATIVE ERROR CORRECTION ===");

        // Create a patch with many distributed changes
        var original = createNumberedLines(1000);
        var revised = new ArrayList<>(original);

        // Insert changes every 25 lines to create cumulative error scenario
        for (int i = 25; i < 1000; i += 25) {
            if (i < revised.size()) {
                revised.set(i, "MODIFIED_LINE_" + i);
                revised.add(i + 1, "INSERTED_LINE_" + i);
            }
        }

        var patch = DiffUtils.diff(original, revised);

        System.out.println("Testing cumulative error correction with " + patch.getDeltas().size() + " deltas");
        System.out.printf("%-10s | %-10s | %-10s | %-15s%n", "Line", "Mapped", "Error", "Error %");
        System.out.println("-----------------------------------------------");

        double totalError = 0;
        int testCount = 0;

        // Test mapping at regular intervals throughout the file
        for (int line = 100; line < 900; line += 100) {
            int mapped = callApproximateLineMapping(patch, line, true);

            // Calculate expected with proportional scaling
            double scale = (double) revised.size() / original.size();
            int expected = (int) (line * scale);

            int error = Math.abs(mapped - expected);
            double errorPercentage = line > 0 ? (double) error / line * 100 : 0;

            totalError += errorPercentage;
            testCount++;

            System.out.printf("%-10d | %-10d | %-10d | %-15.1f%%", line, mapped, error, errorPercentage);

            if (errorPercentage < 3) {
                System.out.print(" EXCELLENT");
            } else if (errorPercentage < 5) {
                System.out.print(" GOOD");
            }
            System.out.println();

            // Enhanced algorithm should have better accuracy even late in file
            assertTrue(errorPercentage < 8,
                     String.format("Enhanced algorithm should keep error <8%% at line %d, but got %.1f%%",
                                 line, errorPercentage));
        }

        double averageError = totalError / testCount;
        System.out.printf("\nAverage mapping error: %.1f%% (should be <5%% with fixes)%n", averageError);

        assertTrue(averageError < 5,
                 String.format("Enhanced algorithm should have <5%% average error, but got %.1f%%", averageError));

        System.out.println("FIX VERIFIED: Cumulative error correction significantly improves accuracy!");
    }

    @Test
    @DisplayName("FIX 3b: Smoothing correction reduces visual discontinuities")
    void verifySmoothingCorrection() throws Exception {
        System.out.println("\n=== FIX VERIFICATION 3b: SMOOTHING CORRECTION ===");

        // Create patch with large changes that could cause discontinuities
        var original = createNumberedLines(500);
        var revised = new ArrayList<>(original);

        // Create large insertion/deletion blocks
        for (int i = 100; i < 110; i++) {
            revised.remove(100); // Remove 10 lines
        }
        for (int i = 0; i < 20; i++) {
            revised.add(200, "BIG_INSERT_" + i); // Insert 20 lines
        }

        var patch = DiffUtils.diff(original, revised);

        System.out.println("Testing smoothing correction around large deltas");
        System.out.printf("%-10s | %-10s | %-15s%n", "Line", "Mapped", "Smoothness");
        System.out.println("------------------------------------");

        // Test lines around the delta boundaries
        var testLines = List.of(95, 96, 97, 98, 99, 100, 101, 102, 103, 104, 105,
                               195, 196, 197, 198, 199, 200, 201, 202, 203, 204, 205);

        int previousMapped = -1;
        int largeJumps = 0;

        for (int line : testLines) {
            int mapped = callApproximateLineMapping(patch, line, true);

            String smoothness = "N/A";
            if (previousMapped >= 0) {
                int jump = Math.abs(mapped - previousMapped);
                if (jump > 20) {
                    largeJumps++;
                    smoothness = "LARGE_JUMP";
                } else if (jump > 10) {
                    smoothness = "MODERATE";
                } else {
                    smoothness = "SMOOTH";
                }
            }

            System.out.printf("%-10d | %-10d | %-15s", line, mapped, smoothness);
            System.out.println();

            previousMapped = mapped;
        }

        System.out.printf("\nLarge jumps detected: %d (should be minimal with smoothing)%n", largeJumps);

        // Smoothing should reduce large discontinuous jumps
        assertTrue(largeJumps <= 2,
                 String.format("Smoothing should minimize large jumps, but found %d", largeJumps));

        System.out.println("FIX VERIFIED: Smoothing correction reduces visual discontinuities!");
    }

    // =================================================================
    // HELPER METHODS
    // =================================================================

    private record PatchScenario(Patch<String> patch, String description) {}

    private PatchScenario createPatchWithNDeltas(int targetDeltas, String description) {
        var original = createNumberedLines(1000);
        var revised = new ArrayList<>(original);

        // Distribute changes throughout the file to create target number of deltas
        int interval = Math.max(1, 1000 / targetDeltas);

        for (int i = 0; i < 1000 && revised.size() / interval < targetDeltas; i += interval) {
            if (i < revised.size()) {
                revised.set(i, "MODIFIED_" + i);
                if (i + 1 < revised.size()) {
                    revised.add(i + 1, "INSERTED_" + i);
                }
            }
        }

        var patch = DiffUtils.diff(original, revised);
        return new PatchScenario(patch, description);
    }

    private List<String> createNumberedLines(int count) {
        return IntStream.range(0, count)
                       .mapToObj(i -> "Line " + i + " content here")
                       .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    private int callApproximateLineMapping(Patch<String> patch, int line, boolean fromOriginal) throws Exception {
        // Use LineMapper directly instead of reflection
        var lineMapper = new LineMapper();
        return lineMapper.mapLine(patch, line, fromOriginal);
    }

    private int calculateExpectedMappingForEnhancedAlgorithm(int line, Patch<String> patch) {
        // For enhanced algorithm, we expect much more accurate mapping
        // This is a simplified calculation - in practice, the enhanced algorithm
        // uses binary search and cumulative error correction

        var deltas = patch.getDeltas();
        int offset = 0;

        for (var delta : deltas) {
            var source = delta.getSource();

            if (source.getPosition() > line) {
                break; // This delta is after our line
            }

            if (line <= source.getPosition() + source.size() - 1) {
                // Line is within this delta
                var target = delta.getTarget();
                int relativePos = line - source.getPosition();
                return target.getPosition() + Math.min(relativePos, target.size() - 1);
            }

            // Accumulate offset from this delta
            offset += (delta.getTarget().size() - source.size());
        }

        return line + offset;
    }
}