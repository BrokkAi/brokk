package ai.brokk.analyzer.build;

import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.util.BuildTools;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Disabled;
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
                    true,
                    "mvn test",
                    true,
                    "mvn test -Dtest={{#classes}}{{value}}{{^last}},{{/last}}{{/classes}}",
                    true,
                    Set.of(),
                    java.util.Collections.emptyMap(),
                    null,
                    "",
                    List.of());

            // Assert interpolation
            String command = BuildTools.getBuildLintSomeCommand(cm, details, List.of(testFile));
            assertEquals("mvn test -Dtest=MyTest", command);
        }
    }

    @Test
    void testGradleTestSelectionInterpolation() throws Exception {
        String code1 = "package com.example; public class Test1 { @org.junit.jupiter.api.Test void t() {} }";
        String code2 = "package com.example; public class Test2 { @org.junit.jupiter.api.Test void t() {} }";

        try (var project = InlineTestProjectCreator.empty()
                .addFileContents(code1, "src/test/java/com/example/Test1.java")
                .addFileContents(code2, "src/test/java/com/example/Test2.java")
                .build()) {
            TestContextManager cm = new TestContextManager(project.getRoot(), new NoOpConsoleIO(), project.getAnalyzer());
            // This is the standard Gradle template recommended in BuildAgent system prompt
            BuildDetails details = new BuildDetails(
                    "",
                    true,
                    "",
                    true,
                    "gradle test{{#classes}} --tests {{value}}{{/classes}}",
                    true,
                    Set.of(),
                    java.util.Collections.emptyMap(),
                    null,
                    "",
                    List.of());

            String command = BuildTools.getBuildLintSomeCommand(cm, details, List.copyOf(project.getAllFiles()));
            // Ensure there is a space between the flags
            assertEquals("gradle test --tests Test1 --tests Test2", command);
        }
    }

    @Test
    void testMultipleClassesTemplateInterpolation() throws Exception {
        String code1 = "package com.example; public class Test1 { @org.junit.jupiter.api.Test void t() {} }";
        String code2 = "package com.example; public class Test2 { @org.junit.jupiter.api.Test void t() {} }";

        try (var project = InlineTestProjectCreator.empty()
                .addFileContents(code1, "src/test/java/com/example/Test1.java")
                .addFileContents(code2, "src/test/java/com/example/Test2.java")
                .build()) {
            TestContextManager cm = new TestContextManager(project.getRoot(), new NoOpConsoleIO(), project.getAnalyzer());
            BuildDetails details = new BuildDetails(
                    "",
                    true,
                    "",
                    true,
                    "mvn test -Dtest={{#classes}}{{value}}{{^last}},{{/last}}{{/classes}}",
                    true,
                    Set.of(),
                    java.util.Collections.emptyMap(),
                    null,
                    "",
                    List.of());

            String command = BuildTools.getBuildLintSomeCommand(cm, details, List.copyOf(project.getAllFiles()));
            assertEquals("mvn test -Dtest=Test1,Test2", command);
        }
    }
}
