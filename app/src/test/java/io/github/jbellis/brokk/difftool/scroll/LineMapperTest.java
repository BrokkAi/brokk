package io.github.jbellis.brokk.difftool.scroll;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.Patch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for the LineMapper class.
 * Tests verify that line mapping algorithms work correctly with different delta types
 * and provide accurate results for scroll synchronization.
 */
class LineMapperTest {

    private LineMapper lineMapper;

    @BeforeEach
    void setUp() {
        lineMapper = new LineMapper();
    }

    // =================================================================
    // BASIC LINE MAPPING TESTS
    // =================================================================

    @Test
    @DisplayName("Empty patch should return lines unchanged")
    void testEmptyPatch() {
        var emptyPatch = DiffUtils.diff(createNumberedLines(5), createNumberedLines(5));

        assertEquals(10, lineMapper.mapLine(emptyPatch, 10, true), "Empty patch should return line unchanged");
        assertEquals(10, lineMapper.mapLine(emptyPatch, 10, false), "Empty patch should return line unchanged in reverse");
    }

    @Test
    @DisplayName("Lines before any changes should map directly")
    void testLinesBeforeChanges() {
        var insertPatch = createInsertPatch(10, "new_line");

        assertEquals(5, lineMapper.mapLine(insertPatch, 5, true), "Lines before changes should map directly");
        assertEquals(5, lineMapper.mapLine(insertPatch, 5, false), "Lines before changes should map directly in reverse");
    }

    @Test
    @DisplayName("Negative lines should be handled gracefully")
    void testNegativeLines() {
        var insertPatch = createInsertPatch(5, "new_line");

        assertEquals(-1, lineMapper.mapLine(insertPatch, -1, true), "Negative lines should be handled gracefully");
        assertEquals(-1, lineMapper.mapLine(insertPatch, -1, false), "Negative lines should be handled gracefully in reverse");
    }

    // =================================================================
    // INSERT DELTA TESTS
    // =================================================================

    @Test
    @DisplayName("Simple insert delta mapping")
    void testInsertDeltaMapping() {
        // Create patch with insert at line 5 (3 lines inserted)
        var patch = createInsertPatch(5, "inserted_line1", "inserted_line2", "inserted_line3");

        // Test mapping from original side (original to revised)
        assertEquals(3, lineMapper.mapLine(patch, 3, true), "Line before insert should map directly");
        assertEquals(8, lineMapper.mapLine(patch, 5, true), "Line at insert should map to position after all inserts");
        assertEquals(10, lineMapper.mapLine(patch, 7, true), "Line after insert should be offset by insert size");

        // Test mapping from revised side (revised to original)
        assertEquals(3, lineMapper.mapLine(patch, 3, false), "Line before insert maps directly");
        assertEquals(5, lineMapper.mapLine(patch, 5, false), "Line at target start maps to source position");
        assertEquals(5, lineMapper.mapLine(patch, 7, false), "Line in inserted region maps to source position");
        assertEquals(5, lineMapper.mapLine(patch, 8, false), "Line after insert should map to source position");
    }

    @Test
    @DisplayName("Multiple insert deltas")
    void testMultipleInserts() {
        var original = List.of("line1", "line2", "line3", "line4", "line5");
        var revised = List.of("line1", "line2", "INSERT_A", "line3", "INSERT_B", "INSERT_C", "line4", "line5");
        var patch = DiffUtils.diff(original, revised);

        // Test mapping across multiple inserts
        assertEquals(0, lineMapper.mapLine(patch, 0, true), "Line 0 should map directly");
        assertEquals(1, lineMapper.mapLine(patch, 1, true), "Line 1 should map directly");
        assertEquals(3, lineMapper.mapLine(patch, 2, true), "Line 2 should skip first insert");
        assertEquals(6, lineMapper.mapLine(patch, 3, true), "Line 3 should skip both inserts");
        assertEquals(7, lineMapper.mapLine(patch, 4, true), "Line 4 should be offset by all inserts");
    }

    // =================================================================
    // DELETE DELTA TESTS
    // =================================================================

    @Test
    @DisplayName("Simple delete delta mapping")
    void testDeleteDeltaMapping() {
        var patch = createDeletePatch(3, 2); // Delete 2 lines starting at line 3

        // Test mapping from original side (original to revised)
        assertEquals(2, lineMapper.mapLine(patch, 2, true), "Line before delete should map directly");
        assertEquals(3, lineMapper.mapLine(patch, 3, true), "First deleted line should map to delete position");
        assertEquals(3, lineMapper.mapLine(patch, 4, true), "Second deleted line should map to delete position");
        assertEquals(3, lineMapper.mapLine(patch, 5, true), "Line after delete should be offset");

        // Test mapping from revised side (revised to original)
        assertEquals(2, lineMapper.mapLine(patch, 2, false), "Line before delete maps directly");
        assertEquals(5, lineMapper.mapLine(patch, 3, false), "Line at delete position maps to after deleted region");
    }

    @Test
    @DisplayName("Multiple delete deltas")
    void testMultipleDeletes() {
        var original = List.of("line1", "line2", "DELETE_A", "line3", "DELETE_B", "DELETE_C", "line4", "line5");
        var revised = List.of("line1", "line2", "line3", "line4", "line5");
        var patch = DiffUtils.diff(original, revised);

        // Test mapping with multiple deletes
        assertEquals(0, lineMapper.mapLine(patch, 0, true), "Line 0 should map directly");
        assertEquals(1, lineMapper.mapLine(patch, 1, true), "Line 1 should map directly");
        assertEquals(2, lineMapper.mapLine(patch, 2, true), "First deleted line maps to position");
        assertEquals(2, lineMapper.mapLine(patch, 3, true), "Line after first delete adjusts for deletes");
        assertEquals(3, lineMapper.mapLine(patch, 4, true), "Second deleted line maps to position");
        assertEquals(3, lineMapper.mapLine(patch, 5, true), "Third deleted line maps to position");
        assertEquals(3, lineMapper.mapLine(patch, 6, true), "Line after all deletes adjusts for both deletions");
    }

    // =================================================================
    // CHANGE DELTA TESTS
    // =================================================================

    @Test
    @DisplayName("Simple change delta mapping")
    void testChangeDeltaMapping() {
        var patch = createChangePatch(3, 1, "changed_line"); // Change 1 line at position 3

        // Test mapping from original side
        assertEquals(2, lineMapper.mapLine(patch, 2, true), "Line before change should map directly");
        assertEquals(3, lineMapper.mapLine(patch, 3, true), "Changed line should map to itself");
        assertEquals(4, lineMapper.mapLine(patch, 4, true), "Line after change should map directly");

        // Test mapping from revised side
        assertEquals(2, lineMapper.mapLine(patch, 2, false), "Line before change maps directly");
        assertEquals(3, lineMapper.mapLine(patch, 3, false), "Changed line maps to original position");
        assertEquals(4, lineMapper.mapLine(patch, 4, false), "Line after change maps directly");
    }

    @Test
    @DisplayName("Change delta with size difference")
    void testChangeDeltaSizeDifference() {
        // Change 1 line to 3 lines
        var original = List.of("line1", "line2", "old_line", "line4", "line5");
        var revised = List.of("line1", "line2", "new_line1", "new_line2", "new_line3", "line4", "line5");
        var patch = DiffUtils.diff(original, revised);

        // Test size expansion in change
        assertEquals(1, lineMapper.mapLine(patch, 1, true), "Line before change maps directly");
        assertEquals(2, lineMapper.mapLine(patch, 2, true), "Line at change start position");
        assertEquals(5, lineMapper.mapLine(patch, 3, true), "Line after change offset by expansion");
        assertEquals(5, lineMapper.mapLine(patch, 4, true), "Subsequent lines maintain offset");

        // Test reverse mapping
        assertEquals(1, lineMapper.mapLine(patch, 1, false), "Reverse: line before change");
        assertEquals(2, lineMapper.mapLine(patch, 2, false), "Reverse: first new line maps to original");
        assertEquals(2, lineMapper.mapLine(patch, 3, false), "Reverse: second new line maps to original");
        assertEquals(2, lineMapper.mapLine(patch, 4, false), "Reverse: third new line maps to original");
        assertEquals(3, lineMapper.mapLine(patch, 5, false), "Reverse: line after change");
    }

    // =================================================================
    // COMPLEX MULTI-DELTA TESTS
    // =================================================================

    @Test
    @DisplayName("Complex multi-delta patch")
    void testComplexMultiDelta() {
        var patch = createMultiDeltaPatch();

        // Test that no exceptions are thrown and results are reasonable
        for (int line = 0; line < 50; line++) {
            int forwardResult = lineMapper.mapLine(patch, line, true);
            int reverseResult = lineMapper.mapLine(patch, line, false);
            assertTrue(forwardResult >= 0, "Forward mapping should produce valid result for line " + line);
            assertTrue(reverseResult >= 0, "Reverse mapping should produce valid result for line " + line);
        }
    }

    @Test
    @DisplayName("Binary search algorithm verification")
    void testBinarySearchAlgorithm() {
        var patch = createMultiDeltaPatch();
        var deltas = patch.getDeltas();

        // Test findRelevantDeltaIndex directly
        for (int line = 0; line < 30; line++) {
            int index = lineMapper.findRelevantDeltaIndex(deltas, line, true);
            assertTrue(index >= -1 && index < deltas.size(),
                      "Delta index should be valid for line " + line);
        }
    }

    // =================================================================
    // PERFORMANCE AND EDGE CASE TESTS
    // =================================================================

    @Test
    @DisplayName("Performance metrics tracking")
    void testPerformanceMetrics() {
        lineMapper.resetMetrics();

        var patch = createInsertPatch(5, "test");

        // Perform some mappings
        for (int i = 0; i < 10; i++) {
            lineMapper.mapLine(patch, i, true);
        }

        var metrics = lineMapper.getPerformanceMetrics();
        assertEquals(10, metrics.totalMappings(), "Should track total mappings");
        assertTrue(metrics.totalMappingTimeNs() > 0, "Should track timing");
        assertTrue(metrics.getAverageMappingTimeNs() > 0, "Should calculate average time");

        var summary = metrics.getSummary();
        assertNotNull(summary, "Should provide summary");
        assertTrue(summary.contains("Mappings: 10"), "Summary should include mapping count");
    }

    @Test
    @DisplayName("Large patch performance")
    void testLargePatchPerformance() {
        // Create a large patch with many deltas
        var original = createNumberedLines(1000);
        var revised = createNumberedLines(1000);

        // Add inserts every 50 lines
        for (int i = 50; i < 1000; i += 50) {
            revised.add(i, "inserted_at_" + i);
        }

        var patch = DiffUtils.diff(original, revised);

        var startTime = System.nanoTime();

        // Test performance of many mappings
        for (int line = 0; line < 500; line++) {
            lineMapper.mapLine(patch, line, true);
        }

        var duration = System.nanoTime() - startTime;

        // Should complete in reasonable time (less than 10ms for 500 mappings)
        assertTrue(duration < 10_000_000, "Large patch mapping should be performant: " + duration + "ns");
    }

    @Test
    @DisplayName("Smoothing correction behavior")
    void testSmoothingCorrection() {
        // Create a patch where smoothing would apply
        var original = List.of("line1", "line2", "old_content_a", "old_content_b", "line5", "line6", "line7");
        var revised = List.of("line1", "line2", "new_content_x", "new_content_y", "new_content_z", "line5", "line6", "line7");
        var patch = DiffUtils.diff(original, revised);

        // Test that smoothing doesn't crash and produces reasonable results
        for (int line = 0; line < 10; line++) {
            int result = lineMapper.mapLine(patch, line, true);
            assertTrue(result >= 0, "Smoothing should produce valid results");
        }
    }

    // =================================================================
    // HELPER METHODS
    // =================================================================

    private List<String> createNumberedLines(int count) {
        return IntStream.range(0, count)
                       .mapToObj(i -> "line_" + i)
                       .collect(java.util.stream.Collectors.toList());
    }

    private Patch<String> createInsertPatch(int position, String... linesToInsert) {
        var original = createNumberedLines(10);
        var revised = createNumberedLines(10);

        for (int i = 0; i < linesToInsert.length; i++) {
            revised.add(position + i, linesToInsert[i]);
        }

        return DiffUtils.diff(original, revised);
    }

    private Patch<String> createDeletePatch(int position, int count) {
        var original = createNumberedLines(10);
        var revised = createNumberedLines(10);

        for (int i = 0; i < count; i++) {
            revised.remove(position);
        }

        return DiffUtils.diff(original, revised);
    }

    private Patch<String> createChangePatch(int position, int count, String... newLines) {
        var original = createNumberedLines(10);
        var revised = createNumberedLines(10);

        // Remove original lines
        for (int i = 0; i < count; i++) {
            revised.remove(position);
        }

        // Add new lines
        for (int i = 0; i < newLines.length; i++) {
            revised.add(position + i, newLines[i]);
        }

        return DiffUtils.diff(original, revised);
    }

    private Patch<String> createMultiDeltaPatch() {
        var original = List.of(
            "line1", "line2", "line3", "line4", "line5",
            "old_a", "old_b", "line8", "line9", "line10",
            "delete_me", "line12", "line13", "line14", "line15"
        );

        var revised = List.of(
            "line1", "line2", "INSERT", "line3", "line4", "line5",
            "new_a", "new_b", "new_c", "line8", "line9", "line10",
            "line12", "line13", "ANOTHER_INSERT", "line14", "line15"
        );

        return DiffUtils.diff(original, revised);
    }
}