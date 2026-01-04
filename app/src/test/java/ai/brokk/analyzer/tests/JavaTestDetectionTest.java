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

        var project = InlineTestProjectCreator.code(testContent, testFileName)
                .addFileContents(nonTestContent, nonTestFileName)
                .build();

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
