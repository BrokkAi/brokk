package ai.brokk.analyzer.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import org.junit.jupiter.api.Test;

public class JavaTestDetectionTest {

    @Test
    void testContainsTestsDetection() throws Exception {
        String testContent =
                """
            package com.example;
            import org.junit.jupiter.api.Test;
            public class MyServiceProbe {
                @Test
                void someBehavior() {}
            }
            """;

        String nonTestContent =
                """
            package com.example;
            public class MyService {
                public void doWork() {}
            }
            """;

        // File path does NOT match TEST_FILE_PATTERN (no "Test" or "test" in name)
        String testFileName = "src/com/example/MyServiceProbe.java";
        String nonTestFileName = "src/com/example/MyService.java";

        try (var project = InlineTestProjectCreator.code(testContent, testFileName)
                .addFileContents(nonTestContent, nonTestFileName)
                .build()) {

            ProjectFile testFile = new ProjectFile(project.getRoot(), testFileName);
            ProjectFile nonTestFile = new ProjectFile(project.getRoot(), nonTestFileName);

            JavaAnalyzer analyzer = new JavaAnalyzer(project);
            analyzer = (JavaAnalyzer) analyzer.update();

            // 1. Semantic check via analyzer directly
            assertTrue(analyzer.containsTests(testFile), "Should detect @Test in MyServiceProbe.java");
            assertFalse(analyzer.containsTests(nonTestFile), "Should NOT detect tests in MyService.java");

            // 2. Integration check via ContextManager (which uses both pattern and analyzer)
            assertTrue(
                    ContextManager.isTestFile(testFile, analyzer),
                    "ContextManager should classify file as test based on analyzer result");
        }
    }

    @Test
    void testNullableAnnotationNotDetectedAsTest() throws Exception {
        String nullableContent =
                """
            package com.example;
            import org.jspecify.annotations.Nullable;
            public class MyService {
                public @Nullable String getValue() {
                    return null;
                }
            }
            """;

        String fileName = "src/com/example/MyService.java";

        try (var project =
                InlineTestProjectCreator.code(nullableContent, fileName).build()) {

            ProjectFile file = new ProjectFile(project.getRoot(), fileName);

            JavaAnalyzer analyzer = new JavaAnalyzer(project);
            analyzer = (JavaAnalyzer) analyzer.update();

            // @Nullable should NOT be detected as a test marker
            assertFalse(analyzer.containsTests(file), "@Nullable annotation should NOT be detected as test marker");
        }
    }

    @Test
    void testTestCaseInheritanceDetectedAsTest() throws Exception {
        String testContent =
                """
            package com.example;
            import junit.framework.TestCase;
            public class LegacyTest extends TestCase {
                public void testLegacy() {}
            }
            """;

        String qualifiedTestContent =
                """
            package com.example;
            public class QualifiedLegacyTest extends junit.framework.TestCase {
                public void testLegacy() {}
            }
            """;

        try (var project = InlineTestProjectCreator.code(testContent, "src/com/example/LegacyTest.java")
                .addFileContents(qualifiedTestContent, "src/com/example/QualifiedLegacyTest.java")
                .build()) {
            JavaAnalyzer analyzer = new JavaAnalyzer(project);
            analyzer = (JavaAnalyzer) analyzer.update();

            assertTrue(
                    analyzer.containsTests(new ProjectFile(project.getRoot(), "src/com/example/LegacyTest.java")),
                    "Should detect tests in class extending TestCase");
            assertTrue(
                    analyzer.containsTests(
                            new ProjectFile(project.getRoot(), "src/com/example/QualifiedLegacyTest.java")),
                    "Should detect tests in class extending qualified junit.framework.TestCase");
        }
    }

    @Test
    void testTestWithParametersDetectedAsTest() throws Exception {
        String code =
                """
            package com.example;
            import org.junit.Test;
            public class ParamTests {
                @Test(expected = RuntimeException.class)
                public void errorTest() {}
            }
            """;

        String fileName = "src/com/example/ParamTests.java";

        try (var project = InlineTestProjectCreator.code(code, fileName).build()) {
            ProjectFile file = new ProjectFile(project.getRoot(), fileName);
            JavaAnalyzer analyzer = new JavaAnalyzer(project);
            analyzer = (JavaAnalyzer) analyzer.update();

            assertTrue(analyzer.containsTests(file), "Should detect @Test with parameters (e.g. expected)");
        }
    }

    @Test
    void testJUnitRulesAndIgnoreDetectedAsTest() throws Exception {
        String ruleCode =
                """
            package com.example;
            import org.junit.Rule;
            import org.junit.rules.TemporaryFolder;
            public class RuleOnlyTest {
                @Rule
                public TemporaryFolder folder = new TemporaryFolder();
            }
            """;

        String ignoreCode =
                """
            package com.example;
            import org.junit.Ignore;
            @Ignore
            public class IgnoredTest {}
            """;

        try (var project = InlineTestProjectCreator.code(ruleCode, "src/com/example/RuleOnlyTest.java")
                .addFileContents(ignoreCode, "src/com/example/IgnoredTest.java")
                .build()) {
            JavaAnalyzer analyzer = new JavaAnalyzer(project);
            analyzer = (JavaAnalyzer) analyzer.update();

            assertTrue(
                    analyzer.containsTests(new ProjectFile(project.getRoot(), "src/com/example/RuleOnlyTest.java")),
                    "Should detect @Rule as a test marker");
            assertTrue(
                    analyzer.containsTests(new ProjectFile(project.getRoot(), "src/com/example/IgnoredTest.java")),
                    "Should detect @Ignore as a test marker");
        }
    }

    @Test
    void testQualifiedTestAnnotations() throws Exception {
        String code =
                """
            package com.example;
            public class QualifiedTests {
                @org.junit.jupiter.api.Test
                void qualifiedTest() {}
            }
            """;

        String fileName = "src/com/example/QualifiedTests.java";

        try (var project = InlineTestProjectCreator.code(code, fileName).build()) {
            ProjectFile file = new ProjectFile(project.getRoot(), fileName);
            JavaAnalyzer analyzer = new JavaAnalyzer(project);
            analyzer = (JavaAnalyzer) analyzer.update();

            assertTrue(analyzer.containsTests(file), "Should detect fully qualified test annotations");
        }
    }

    @Test
    void testWhitespaceInTestAnnotations() throws Exception {
        String code =
                """
            package com.example;
            public class WhitespaceTests {
                @  RepeatedTest(5)
                void whitespaceTest() {}
            }
            """;

        String fileName = "src/com/example/WhitespaceTests.java";

        try (var project = InlineTestProjectCreator.code(code, fileName).build()) {
            ProjectFile file = new ProjectFile(project.getRoot(), fileName);
            JavaAnalyzer analyzer = new JavaAnalyzer(project);
            analyzer = (JavaAnalyzer) analyzer.update();

            assertTrue(
                    analyzer.containsTests(file), "Should detect test annotations with whitespace between @ and name");
        }
    }
}
