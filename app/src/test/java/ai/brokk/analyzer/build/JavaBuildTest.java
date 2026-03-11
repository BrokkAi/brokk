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

public class JavaBuildTest {

    @Test
    void testJavaTestClassNameInterpolation() throws Exception {
        String testCode = """
            package com.example;
            import org.junit.jupiter.api.Test;
            public class MyTest {
                @Test
                void someTest() {}
            }
            """;

        try (var project = InlineTestProjectCreator.code(testCode, "src/test/java/com/example/MyTest.java").build()) {
            // Extract the ProjectFile
            ProjectFile testFile = project.getAllFiles().stream()
                    .filter(f -> f.toString().endsWith("MyTest.java"))
                    .findFirst()
                    .orElseThrow();

            // Create TestContextManager with the project and its analyzer
            TestContextManager cm = new TestContextManager(project.getRoot(), new NoOpConsoleIO(), project.getAnalyzer());

            // Create BuildDetails with a template using {{#classes}}
            BuildDetails details = new BuildDetails(
                    "mvn compile",
                    "mvn test",
                    "mvn test -Dtest={{#classes}}{{value}}{{^last}},{{/last}}{{/classes}}",
                    Set.of()
            );

            // Assert interpolation
            String command = BuildTools.getBuildLintSomeCommand(cm, details, List.of(testFile));
            assertEquals("mvn test -Dtest=MyTest", command);
        }
    }
}
