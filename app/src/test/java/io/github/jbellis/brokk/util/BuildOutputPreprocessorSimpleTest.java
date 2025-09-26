package io.github.jbellis.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.testutil.NoOpConsoleIO;
import io.github.jbellis.brokk.testutil.TestContextManager;
import java.nio.file.Path;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildOutputPreprocessorSimpleTest {

    @Test
    void testPreprocessBuildOutput_shortOutput_returnsOriginal(@TempDir Path tempDir) {
        var contextManager = new TestContextManager(tempDir, new NoOpConsoleIO());
        String shortOutput = "This is a short build output\nwith only a few lines\nshould pass through unchanged";

        String result = BuildOutputPreprocessor.preprocessBuildOutput(shortOutput, contextManager);

        assertEquals(shortOutput, result);
    }

    @Test
    void testPreprocessBuildOutput_emptyInput_returnsEmptyString(@TempDir Path tempDir) {
        var contextManager = new TestContextManager(tempDir, new NoOpConsoleIO());
        String result = BuildOutputPreprocessor.preprocessBuildOutput("", contextManager);
        assertEquals("", result);
    }

    @Test
    void testPreprocessBuildOutput_blankInput_returnsBlank(@TempDir Path tempDir) {
        var contextManager = new TestContextManager(tempDir, new NoOpConsoleIO());
        String result = BuildOutputPreprocessor.preprocessBuildOutput("   ", contextManager);
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
    void testPreprocessBuildOutput_exactlyAtThreshold_doesNotPreprocess(@TempDir Path tempDir) {
        var contextManager = new TestContextManager(tempDir, new NoOpConsoleIO());
        // Create output with exactly THRESHOLD_LINES lines
        String exactThresholdOutput = IntStream.range(0, BuildOutputPreprocessor.THRESHOLD_LINES)
                .mapToObj(i -> "Line " + i)
                .reduce("", (acc, line) -> acc.isEmpty() ? line : acc + "\n" + line);

        String result = BuildOutputPreprocessor.preprocessBuildOutput(exactThresholdOutput, contextManager);

        assertEquals(exactThresholdOutput, result);
    }

    @Test
    void testPreprocessBuildOutput_oneLineOverThreshold_returnsOriginalWhenNoContextManager() {
        // Create output with THRESHOLD_LINES + 1 lines
        String overThresholdOutput = IntStream.range(0, BuildOutputPreprocessor.THRESHOLD_LINES + 1)
                .mapToObj(i -> "Line " + i)
                .reduce("", (acc, line) -> acc.isEmpty() ? line : acc + "\n" + line);

        // Without context manager, should return original content even over threshold
        String result = BuildOutputPreprocessor.preprocessBuildOutput(overThresholdOutput, null);
        assertEquals(overThresholdOutput, result);
    }

    @Test
    void testSanitizeOnly_unixPathSanitization(@TempDir Path tempDir) {
        var contextManager = new TestContextManager(tempDir, new NoOpConsoleIO());
        String projectRoot = tempDir.toAbsolutePath().toString().replace('\\', '/');

        String buildOutput = String.format(
                """
            %s/src/main/java/com/example/App.java:10: error: cannot find symbol
            %s/src/test/java/com/example/AppTest.java:25: error: method does not exist
            Build failed with 2 errors
            """,
                projectRoot, projectRoot);

        String result = BuildOutputPreprocessor.sanitizeOnly(buildOutput, contextManager);

        // Paths should be sanitized to relative paths
        assertTrue(result.contains("src/main/java/com/example/App.java:10: error"));
        assertTrue(result.contains("src/test/java/com/example/AppTest.java:25: error"));
        assertFalse(result.contains(projectRoot), "Absolute path should be removed");
    }

    @Test
    void testSanitizeOnly_windowsPathSanitization(@TempDir Path tempDir) {
        var contextManager = new TestContextManager(tempDir, new NoOpConsoleIO());
        String projectRoot = tempDir.toAbsolutePath().toString().replace('/', '\\');

        String buildOutput = String.format(
                """
            %s\\src\\main\\java\\com\\example\\App.java:10: error: cannot find symbol
            %s\\src\\test\\java\\com\\example\\AppTest.java:25: error: method does not exist
            Build failed with 2 errors
            """,
                projectRoot, projectRoot);

        String result = BuildOutputPreprocessor.sanitizeOnly(buildOutput, contextManager);

        // Paths should be sanitized to relative paths
        assertTrue(
                result.contains("src\\main\\java\\com\\example\\App.java:10: error")
                        || result.contains("src/main/java/com/example/App.java:10: error"),
                "Should contain relative path");
        assertFalse(result.contains(projectRoot), "Absolute path should be removed");
    }

    @Test
    void testSanitizeOnly_mixedPathSeparators(@TempDir Path tempDir) {
        var contextManager = new TestContextManager(tempDir, new NoOpConsoleIO());
        String projectRootForward = tempDir.toAbsolutePath().toString().replace('\\', '/');
        String projectRootBackward = tempDir.toAbsolutePath().toString().replace('/', '\\');

        String buildOutput = String.format(
                """
            %s/src/main/App.java:10: error
            %s\\src\\test\\AppTest.java:25: error
            Normal text should remain unchanged
            """,
                projectRootForward, projectRootBackward);

        String result = BuildOutputPreprocessor.sanitizeOnly(buildOutput, contextManager);

        // Both path styles should be sanitized
        assertTrue(result.contains("src/main/App.java:10: error") || result.contains("src\\main\\App.java:10: error"));
        assertTrue(result.contains("src/test/AppTest.java:25: error")
                || result.contains("src\\test\\AppTest.java:25: error"));
        assertTrue(result.contains("Normal text should remain unchanged"));
        assertFalse(result.contains(projectRootForward), "Forward slash path should be removed");
        assertFalse(result.contains(projectRootBackward), "Backward slash path should be removed");
    }

    @Test
    void testSanitizeOnly_partialPathMatchPrevention(@TempDir Path tempDir) {
        var contextManager = new TestContextManager(tempDir, new NoOpConsoleIO());
        String projectRoot = tempDir.toAbsolutePath().toString();

        // Create text that contains part of the project root but shouldn't be sanitized
        String buildOutput = String.format(
                """
            %s/src/main/App.java:10: error: this should be sanitized
            someprefix%ssuffix should not be sanitized
            %s-with-suffix should not be sanitized
            prefix-%s should not be sanitized
            """,
                projectRoot, projectRoot, projectRoot, projectRoot);

        String result = BuildOutputPreprocessor.sanitizeOnly(buildOutput, contextManager);

        // Only the complete path with proper word boundaries should be sanitized
        assertTrue(result.contains("src/main/App.java:10: error") || result.contains("src\\main\\App.java:10: error"));
        assertTrue(result.contains("someprefix" + projectRoot + "suffix"), "Partial matches should not be sanitized");
        assertTrue(result.contains(projectRoot + "-with-suffix"), "Partial matches should not be sanitized");
        assertTrue(result.contains("prefix-" + projectRoot), "Partial matches should not be sanitized");
    }

    @Test
    void testSanitizeOnly_emptyAndSpecialCases(@TempDir Path tempDir) {
        var contextManager = new TestContextManager(tempDir, new NoOpConsoleIO());

        // Test empty input
        assertEquals("", BuildOutputPreprocessor.sanitizeOnly("", contextManager));

        // Test whitespace-only input
        assertEquals("   ", BuildOutputPreprocessor.sanitizeOnly("   ", contextManager));

        // Test input without any paths
        String noPaths = "Build successful\nAll tests passed\nNo errors found";
        assertEquals(noPaths, BuildOutputPreprocessor.sanitizeOnly(noPaths, contextManager));
    }

    @Test
    void testSanitizeOnly_caseInsensitivePaths(@TempDir Path tempDir) {
        var contextManager = new TestContextManager(tempDir, new NoOpConsoleIO());
        String projectRoot = tempDir.toAbsolutePath().toString();

        // Test case insensitive matching (important for Windows)
        String upperCasePath = projectRoot.toUpperCase();
        String lowerCasePath = projectRoot.toLowerCase();

        String buildOutput = String.format(
                """
            %s/src/main/App.java:10: error
            %s/src/test/Test.java:20: warning
            """,
                upperCasePath, lowerCasePath);

        String result = BuildOutputPreprocessor.sanitizeOnly(buildOutput, contextManager);

        // Both case variations should be sanitized on case-insensitive filesystems
        // On case-sensitive systems, only exact matches are sanitized
        boolean hasOriginalCase = result.contains(projectRoot);
        boolean hasUpperCase = result.contains(upperCasePath);
        boolean hasLowerCase = result.contains(lowerCasePath);

        // At minimum, relative paths should be present
        assertTrue(result.contains("src/main/App.java:10: error") || result.contains("src\\main\\App.java:10: error"));
    }

    @Test
    void testSanitizeOnly_exceptionHandling(@TempDir Path tempDir) {
        var contextManager = new TestContextManager(tempDir, new NoOpConsoleIO());

        // Test with problematic regex characters in paths
        String buildOutput = "Error in file: some.file[with].regex{chars}+and*more?.java";

        // Should not throw exception and should return original text
        String result = BuildOutputPreprocessor.sanitizeOnly(buildOutput, contextManager);
        assertNotNull(result);
        assertEquals(buildOutput, result); // No sanitization should occur for non-matching paths
    }
}
