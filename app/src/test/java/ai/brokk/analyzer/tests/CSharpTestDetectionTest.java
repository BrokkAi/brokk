package ai.brokk.analyzer.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.CSharpAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@NullMarked
public class CSharpTestDetectionTest {

    @Test
    void testContainsTestsDetection() throws Exception {
        String testCode =
                """
                using NUnit.Framework;

                public class MyTests {
                    [Test]
                    public void MyTestMethod() {
                        Assert.Pass();
                    }

                    [Fact]
                    public void XunitTest() {}

                    [Theory]
                    public void XunitTheory(int x) {}
                }
                """;

        String nonTestCode =
                """
                public class Calculator {
                    public int Add(int a, int b) => a + b;
                }
                """;

        // Path does NOT match TEST_FILE_PATTERN (which looks for 'test' in name)
        String testPath = "Services/Logic.cs";
        String nonTestPath = "Models/Data.cs";

        IProject project = InlineTestProjectCreator.code(testCode, testPath)
                .addFileContents(nonTestCode, nonTestPath)
                .build();

        CSharpAnalyzer analyzer = new CSharpAnalyzer(project);
        analyzer = (CSharpAnalyzer) analyzer.update();

        ProjectFile testFile = new ProjectFile(project.getRoot(), testPath);
        ProjectFile nonTestFile = new ProjectFile(project.getRoot(), nonTestPath);

        // 1. Verify analyzer's semantic detection
        assertTrue(analyzer.containsTests(testFile), "File with [Test] attribute should be marked as containing tests");
        assertFalse(analyzer.containsTests(nonTestFile), "File without test attributes should not be marked");

        // 2. Verify ContextManager's integration (which uses the analyzer)
        assertTrue(
                ContextManager.isTestFile(testFile, analyzer),
                "ContextManager should identify file as test file via analyzer");
        assertFalse(
                ContextManager.isTestFile(nonTestFile, analyzer),
                "ContextManager should not identify plain file as test file");
    }
}
