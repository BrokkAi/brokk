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

public class ScalaBuildTest {

    @Test
    void testScalaClassTemplateInterpolation() throws Exception {
        String code = """
            package com.example
            import org.scalatest.funsuite.AnyFunSuite
            
            class MyTest extends AnyFunSuite {
              test("addition") {
                assert(1 + 1 == 2)
              }
            }
            """;

        try (ITestProject project = InlineTestProjectCreator.code(code, "src/test/scala/MyTest.scala").build()) {
            ProjectFile testFile = project.getAllFiles().stream()
                    .filter(f -> f.getFileName().equals("MyTest.scala"))
                    .findFirst()
                    .orElseThrow();

            TestContextManager cm = new TestContextManager(
                    project, new NoOpConsoleIO(), Set.of(), project.getAnalyzer());

            // Verify the analyzer sees the class
            var testCus = project.getAnalyzer().testFilesToCodeUnits(List.of(testFile));
            assertEquals(1, testCus.size(), "Should have found exactly one test class");
            assertEquals("MyTest", testCus.iterator().next().identifier());

            BuildDetails details = new BuildDetails(
                    "sbt compile",
                    "sbt test",
                    "sbt \"testOnly {{#classes}}{{value}}{{/classes}}\"",
                    Set.of());

            String command = BuildTools.getBuildLintSomeCommand(cm, details, List.of(testFile));

            // We expect the class 'MyTest' to be substituted for {{classes}}
            assertEquals("sbt \"testOnly MyTest\"", command.trim());
        }
    }
}
