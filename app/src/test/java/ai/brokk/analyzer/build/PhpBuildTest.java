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

public class PhpBuildTest {

    @Test
    void testPhpClassTemplateInterpolation() throws Exception {
        String code = """
            <?php
            namespace Tests;
            use PHPUnit\\Framework\\TestCase;
            
            class MyTest extends TestCase {
                public function test_logic() {
                    $this->assertTrue(true);
                }
            }
            """;

        try (ITestProject project = InlineTestProjectCreator.code(code, "tests/MyTest.php").build()) {
            ProjectFile testFile = project.getAllFiles().stream()
                    .filter(f -> f.getFileName().equals("MyTest.php"))
                    .findFirst()
                    .orElseThrow();

            TestContextManager cm = new TestContextManager(
                    project, new NoOpConsoleIO(), Set.of(), project.getAnalyzer());

            // Verify the analyzer sees the class
            var testCus = project.getAnalyzer().testFilesToCodeUnits(List.of(testFile));
            assertEquals(1, testCus.size(), "Should have found exactly one test class");
            assertEquals("MyTest", testCus.iterator().next().identifier());

            BuildDetails details = new BuildDetails(
                    "vendor/bin/phpunit",
                    true,
                    "vendor/bin/phpunit",
                    true,
                    Set.of(),
                    java.util.Collections.emptyMap(),
                    null,
                    "",
                    List.of(new ai.brokk.agents.BuildAgent.ModuleBuildEntry("root", ".", "vendor/bin/phpunit", "vendor/bin/phpunit", "vendor/bin/phpunit --filter {{#classes}}{{value}}{{/classes}}", "")));

            String command = BuildTools.getBuildLintSomeCommand(cm, details, List.of(testFile));

            // We expect the class 'MyTest' to be substituted for {{classes}}
            assertEquals("vendor/bin/phpunit --filter MyTest", command.trim());
        }
    }

    @Test
    void testMultipleClassesTemplateInterpolation() throws Exception {
        String code1 = "<?php class T1 extends PHPUnit\\Framework\\TestCase { public function test1(){} }";
        String code2 = "<?php class T2 extends PHPUnit\\Framework\\TestCase { public function test1(){} }";

        try (var project = InlineTestProjectCreator.empty()
                .addFileContents(code1, "T1.php")
                .addFileContents(code2, "T2.php")
                .build()) {
            TestContextManager cm = new TestContextManager(project, new NoOpConsoleIO(), Set.of(), project.getAnalyzer());
            BuildDetails details = new BuildDetails(
                    "",
                    true,
                    "",
                    true,
                    Set.of(),
                    java.util.Collections.emptyMap(),
                    null,
                    "",
                    List.of(new ai.brokk.agents.BuildAgent.ModuleBuildEntry("root", ".", "", "", "phpunit --filter {{#classes}}{{value}}{{^last}}|{{/last}}{{/classes}}", "")));

            String command = BuildTools.getBuildLintSomeCommand(cm, details, List.copyOf(project.getAllFiles()));
            assertEquals("phpunit --filter T1|T2", command.trim());
        }
    }
}
