package ai.brokk.analyzer.build;

import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.util.BuildTools;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CSharpBuildTest {

    @Test
    void testCSharpTestClassNameInterpolation() throws Exception {
        String testCode = """
            using Xunit;
            namespace MyProject.Tests
            {
                public class MyTest
                {
                    [Fact]
                    public void SomeTest() {}
                }
            }
            """;

        // Create an inline C# project
        try (var project = InlineTestProjectCreator.code(testCode, "Tests/MyTest.cs").build()) {
            // Extract the ProjectFile representing the test file
            ProjectFile testFile = project.getAllFiles().stream()
                    .filter(f -> f.toString().endsWith("MyTest.cs"))
                    .findFirst()
                    .orElseThrow();

            // Setup TestContextManager with the project and its analyzer
            TestContextManager cm = new TestContextManager(
                    project.getRoot(), 
                    new NoOpConsoleIO(), 
                    project.getAnalyzer());

            // Define BuildDetails with a template that filters by class name
            // C# filters often use fully qualified or simple class names
            BuildDetails details = new BuildDetails(
                    "dotnet build",
                    "dotnet test",
                    "dotnet test --filter {{#classes}}{{value}}{{^last}}|{{/last}}{{/classes}}",
                    Set.of()
            );

            // Interpolate the command using BuildTools
            String command = BuildTools.getBuildLintSomeCommand(cm, details, List.of(testFile));

            // Assert that the class name 'MyTest' was correctly substituted
            assertEquals("dotnet test --filter MyTest", command);
        }
    }
}
