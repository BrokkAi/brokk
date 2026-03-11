package ai.brokk.analyzer.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.ITestProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.util.BuildTools;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class RustBuildTest {

    @Test
    void testRustTestCommandInterpolation() throws Exception {
        String code = """
                #[cfg(test)]
                mod tests {
                    #[test]
                    fn test_my_logic() {
                        assert_eq!(2 + 2, 4);
                    }
                }
                """;

        try (ITestProject project = InlineTestProjectCreator.code(code, "src/lib.rs").build()) {
            // Extract the ProjectFile
            ProjectFile testFile = project.getAllFiles().stream()
                    .filter(f -> f.toString().endsWith("lib.rs"))
                    .findFirst()
                    .orElseThrow();

            // Setup ContextManager with the real analyzer from the project
            TestContextManager cm = new TestContextManager(project, new NoOpConsoleIO(), Set.of(), project.getAnalyzer());

            // Define the Rust build details with mustache templates.
            // We use a separator to handle multiple code units (module + function) identified by the analyzer.
            BuildDetails details = new BuildDetails(
                    "cargo build",
                    "cargo test",
                    "cargo test {{#classes}}{{value}} {{/classes}}",
                    Set.of());

            // Act
            String command = BuildTools.getBuildLintSomeCommand(cm, details, List.of(testFile)).trim();

            // Assert
            // RustAnalyzer identifies both 'test_my_logic' and 'tests'.
            assertEquals("cargo test test_my_logic tests", command);
        }
    }
}
