package io.github.jbellis.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.testutil.TestProject;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class JavaAnalyzerTest {

    @Test
    @Timeout(5) // Prevent test from hanging indefinitely if StackOverflow occurs
    void testLintFilesWithoutJdtAnalyzer_ShouldNotCauseStackOverflow() {
        // Create a test project with a temporary directory
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "brokk-test");
        TestProject testProject = new TestProject(tempDir, Languages.JAVA);

        // Create JavaAnalyzer with a failing JDT future to ensure jdtAnalyzer is null
        CompletableFuture<JdtClient> failingFuture = CompletableFuture.supplyAsync(() -> {
            throw new RuntimeException("JDT not available");
        });

        // Use the protected constructor directly (no reflection needed!)
        JavaAnalyzer analyzer = new JavaAnalyzer(testProject, failingFuture);

        // This should not cause StackOverflowError
        List<ProjectFile> files = Collections.emptyList();

        // After the fix, this should return empty results gracefully without StackOverflowError
        LintResult result = analyzer.lintFiles(files);

        // Should return empty results when JDT analyzer is not available
        assertNotNull(result);
        assertTrue(result.diagnostics().isEmpty());
    }
}
