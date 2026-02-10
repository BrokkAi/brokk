package ai.brokk.analyzer.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@NullMarked
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
    void testQualifiedAndWhitespaceTestAnnotations() throws Exception {
        String code =
                """
            package com.example;
            public class MixedTests {
                @org.junit.jupiter.api.Test
                void qualifiedTest() {}

                @  RepeatedTest(5)
                void whitespaceTest() {}
            }
            """;

        String fileName = "src/com/example/MixedTests.java";

        try (var project = InlineTestProjectCreator.code(code, fileName).build()) {
            ProjectFile file = new ProjectFile(project.getRoot(), fileName);
            JavaAnalyzer analyzer = new JavaAnalyzer(project);
            analyzer = (JavaAnalyzer) analyzer.update();

            assertTrue(analyzer.containsTests(file), "Should detect qualified and whitespace-padded test annotations");
        }
    }
}
