package ai.brokk.analyzer.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.GoAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import org.jspecify.annotations.NullMarked;
import java.io.IOException;
import org.junit.jupiter.api.Test;

@NullMarked
public class GoTestDetectionTest {

    @Test
    void testContainsTestsDetection() throws IOException {
        String testFileContent = """
                package foo
                import "testing"
                func TestFeature(t *testing.T) {
                    t.Log("running test")
                }
                """;

        String nonTestFileContent = """
                package foo
                func Feature(x int) int {
                    return x + 1
                }
                """;

        // Path does NOT match TEST_FILE_PATTERN but content contains markers
        String testFilePath = "pkg/feature.go";
        String nonTestFilePath = "pkg/lib.go";

        IProject project = InlineTestProjectCreator.code(testFileContent, testFilePath)
                .addFileContents(nonTestFileContent, nonTestFilePath)
                .build();

        GoAnalyzer analyzer = new GoAnalyzer(project);
        analyzer.update();

        ProjectFile testFile = new ProjectFile(project.getRoot(), testFilePath);
        ProjectFile nonTestFile = new ProjectFile(project.getRoot(), nonTestFilePath);

        // Verify analyzer detection
        assertTrue(analyzer.containsTests(testFile), "Analyzer should detect tests in " + testFilePath);
        assertFalse(analyzer.containsTests(nonTestFile), "Analyzer should NOT detect tests in " + nonTestFilePath);

        // Verify ContextManager heuristic fallback/override
        // ContextManager.isTestFile(file, analyzer) should return true because the analyzer found markers,
        // even though the filename doesn't match the regex.
        assertTrue(ContextManager.isTestFile(testFile, analyzer), 
                "ContextManager should identify file as test via analyzer despite path");
    }
}
