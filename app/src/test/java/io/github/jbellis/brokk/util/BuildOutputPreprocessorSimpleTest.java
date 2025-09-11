package io.github.jbellis.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BuildOutputPreprocessorSimpleTest {

    @Test
    void testPreprocessBuildOutput_shortOutput_returnsOriginal() {
        String shortOutput = "This is a short build output\nwith only a few lines\nshould pass through unchanged";

        String result = BuildOutputPreprocessor.preprocessBuildOutput(shortOutput, null);

        assertEquals(shortOutput, result);
    }

    @Test
    void testPreprocessBuildOutput_nullInput_returnsEmptyString() {
        String result = BuildOutputPreprocessor.preprocessBuildOutput(null, null);
        assertEquals("", result);
    }

    @Test
    void testPreprocessBuildOutput_blankInput_returnsBlank() {
        String result = BuildOutputPreprocessor.preprocessBuildOutput("   ", null);
        assertEquals("   ", result);
    }

    @Test
    void testThresholdConstants() {
        // Verify that our constants are reasonable values
        assertEquals(200, BuildOutputPreprocessor.THRESHOLD_LINES);
        assertEquals(10, BuildOutputPreprocessor.MAX_EXTRACTED_ERRORS);

        // Threshold should be high enough to avoid false positives but low enough to be useful
        assertTrue(BuildOutputPreprocessor.THRESHOLD_LINES > 50, "Threshold should be reasonably high");
        assertTrue(BuildOutputPreprocessor.THRESHOLD_LINES < 1000, "Threshold should not be too high");

        // Max errors should be enough to capture multiple issues but not too verbose
        assertTrue(BuildOutputPreprocessor.MAX_EXTRACTED_ERRORS > 3, "Should extract multiple errors");
        assertTrue(BuildOutputPreprocessor.MAX_EXTRACTED_ERRORS < 50, "Should not extract too many errors");
    }

    @Test
    void testPreprocessBuildOutput_exactlyAtThreshold_doesNotPreprocess() {
        // Create output with exactly THRESHOLD_LINES lines
        String exactThresholdOutput = IntStream.range(0, BuildOutputPreprocessor.THRESHOLD_LINES)
                .mapToObj(i -> "Line " + i)
                .reduce("", (acc, line) -> acc + line + "\n");

        String result = BuildOutputPreprocessor.preprocessBuildOutput(exactThresholdOutput, null);

        assertEquals(exactThresholdOutput, result);
    }

    @Test
    void testPreprocessBuildOutput_oneLineOverThreshold_returnsOriginalWhenNoContextManager() {
        // Create output with THRESHOLD_LINES + 1 lines
        String overThresholdOutput = IntStream.range(0, BuildOutputPreprocessor.THRESHOLD_LINES + 1)
                .mapToObj(i -> "Line " + i)
                .reduce("", (acc, line) -> acc + line + "\n");

        // Without context manager, should return original content even over threshold
        String result = BuildOutputPreprocessor.preprocessBuildOutput(overThresholdOutput, null);
        assertEquals(overThresholdOutput, result);
    }

    @Test
    void testPreprocessBuildOutput_documentsCodeAgentFalseSuccessFix() {
        // This test documents the fix for the CodeAgent false success bug
        // where success/failure was incorrectly determined based on preprocessed output

        String longBuildFailure = IntStream.range(0, BuildOutputPreprocessor.THRESHOLD_LINES + 50)
                .mapToObj(i -> "BUILD FAILED: Verbose build log line " + i)
                .reduce("", (acc, line) -> acc + line + "\n");

        // Even if preprocessing were to return empty (which it shouldn't with null context manager),
        // CodeAgent now correctly bases success/failure decisions on the RAW build result
        String result = BuildOutputPreprocessor.preprocessBuildOutput(longBuildFailure, null);

        // With null context manager, preprocessing returns original content
        assertEquals(longBuildFailure, result);

        // The key insight: CodeAgent verifyPhase() now separates:
        // - rawBuildError: for success/failure determination
        // - processedBuildError: for LLM context optimization only
        // This prevents false success when preprocessing returns empty results

        // Example: if rawBuildError contains "BUILD FAILED" but processedBuildError is empty,
        // CodeAgent correctly reports failure based on rawBuildError, not processedBuildError
        assertTrue(result.contains("BUILD FAILED"), "Original build failure should be preserved");
    }
}
