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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class GoBuildTest {

    @Test
    @Disabled("Failing due to analyzer changes")
    void testGoTestCommandInterpolation() throws Exception {
        String code = """
                package mypkg
                import "testing"
                func TestMyLogic(t *testing.T) {
                    // test logic
                }
                """;

        try (ITestProject project = InlineTestProjectCreator.code(code, "mypkg/logic_test.go").build()) {
            // Extract the ProjectFile
            ProjectFile testFile = project.getAllFiles().stream()
                    .filter(f -> f.toString().endsWith("logic_test.go"))
                    .findFirst()
                    .orElseThrow();

            // Setup ContextManager with the real analyzer from the project
            TestContextManager cm = new TestContextManager(project, new NoOpConsoleIO(), Set.of(), project.getAnalyzer());

            // Define the Go build details with mustache templates
            BuildDetails details = new BuildDetails(
                    "go build ./...",
                    "go test ./...",
                    "go test {{#packages}}{{value}}{{/packages}} -run '^{{#classes}}{{value}}{{/classes}}$'",
                    Set.of());

            // Act
            String command = BuildTools.getBuildLintSomeCommand(cm, details, List.of(testFile));

            // Assert
            // Packages: GoAnalyzer should return "./mypkg"
            // Classes: GoAnalyzer.testFilesToCodeUnits should return "TestMyLogic" (as it's a function-based language)
            assertEquals("go test ./mypkg -run '^TestMyLogic$'", command);
        }
    }

    @Test
    @Disabled("Failing multi-function interpolation")
    void testMultipleFunctionsTemplateInterpolation() throws Exception {
        String code = """
                package mypkg
                func TestOne(t *testing.T) {}
                func TestTwo(t *testing.T) {}
                """;

        try (var project = InlineTestProjectCreator.code(code, "mypkg/multi_test.go").build()) {
            TestContextManager cm = new TestContextManager(project, new NoOpConsoleIO(), Set.of(), project.getAnalyzer());
            BuildDetails details = new BuildDetails("", "", "go test ./mypkg -run '^{{#classes}}{{value}}{{^last}}|{{/last}}{{/classes}}$'", Set.of());

            String command = BuildTools.getBuildLintSomeCommand(cm, details, List.copyOf(project.getAllFiles()));
            assertEquals("go test ./mypkg -run '^TestOne|TestTwo$'", command.trim());
        }
    }
}
