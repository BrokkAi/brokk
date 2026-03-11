package ai.brokk.analyzer.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.ITestProject;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.util.BuildTools;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class PythonBuildTest {

    @Test
    void testPythonFunctionTemplateInterpolation() throws Exception {
        String code = """
            def test_my_logic():
                pass
            """;

        try (ITestProject project = InlineTestProjectCreator.code(code, "tests/test_logic.py").build()) {
            ProjectFile testFile = project.getAllFiles().stream()
                    .filter(f -> f.getFileName().equals("test_logic.py"))
                    .findFirst()
                    .orElseThrow();

            TestContextManager cm = new TestContextManager(
                    project, new NoOpConsoleIO(), Set.of(), project.getAnalyzer());

            // Verify the analyzer sees the function specifically as a test code unit
            var testCus = project.getAnalyzer().testFilesToCodeUnits(List.of(testFile));
            assertEquals(1, testCus.size(), "Should have found exactly one test function");
            assertEquals("test_my_logic", testCus.iterator().next().identifier());

            BuildDetails details = new BuildDetails(
                    "build-cmd",
                    "pytest",
                    "pytest {{#classes}}{{value}} {{/classes}}",
                    Set.of());

            String command = BuildTools.getBuildLintSomeCommand(cm, details, List.of(testFile));

            // We expect the function 'test_my_logic' to be substituted for {{classes}}
            assertEquals("pytest test_my_logic", command.trim());
        }
    }
}
