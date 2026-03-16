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
                    true,
                    "sbt test",
                    true,
                    "sbt \"testOnly {{#classes}}{{value}}{{/classes}}\"",
                    true,
                    Set.of(),
                    java.util.Collections.emptyMap(),
                    null,
                    "",
                    List.of());

            String command = BuildTools.getBuildLintSomeCommand(cm, details, List.of(testFile));

            // We expect the class 'MyTest' to be substituted for {{classes}}
            assertEquals("sbt \"testOnly MyTest\"", command.trim());
        }
    }

    @Test
    void testMultipleClassesTemplateInterpolation() throws Exception {
        String code1 = "package c; class T1 extends org.scalatest.funsuite.AnyFunSuite { test(\"a\"){} }";
        String code2 = "package c; class T2 extends org.scalatest.funsuite.AnyFunSuite { test(\"a\"){} }";

        try (var project = InlineTestProjectCreator.empty()
                .addFileContents(code1, "T1.scala")
                .addFileContents(code2, "T2.scala")
                .build()) {
            TestContextManager cm = new TestContextManager(project, new NoOpConsoleIO(), Set.of(), project.getAnalyzer());
            // Remove trailing space inside {{#classes}} loop to match expected output
            BuildDetails details = new BuildDetails(
                    "",
                    true,
                    "",
                    true,
                    "sbt \"testOnly {{#classes}}{{value}}{{^last}} {{/last}}{{/classes}}\"",
                    true,
                    Set.of(),
                    java.util.Collections.emptyMap(),
                    null,
                    "",
                    List.of());

            String command = BuildTools.getBuildLintSomeCommand(cm, details, List.copyOf(project.getAllFiles()));
            assertEquals("sbt \"testOnly T1 T2\"", command.trim());
        }
    }
}
