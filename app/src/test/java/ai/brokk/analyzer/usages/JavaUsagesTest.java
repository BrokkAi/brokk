package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.UsageFinderTestUtil;
import ai.brokk.usages.UsageFinder;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class JavaUsagesTest {

    @Test
    public void testUsagesFilteringForJava() throws Exception {
        String targetCode =
                """
                package com.example;
                public class Target {
                    public void doSomething() {}
                }
                """;

        String consumerCode =
                """
                package com.example;
                /**
                 * This is a comment mentioning Target.
                 * It should not be a usage.
                 */
                public class Consumer {
                    // Field usage: SHOULD be found
                    private Target myTarget;

                    // Method parameter: 'Target' here is a name, NOT a usage of the class
                    public void process(int Target) {
                        // Local variable: 'target' here is a name
                        int target = 10;

                        // Object creation: SHOULD be found
                        this.myTarget = new Target();

                        // Method call: SHOULD be found as a usage of doSomething
                        this.myTarget.doSomething();
                    }
                }
                """;

        try (IProject project = InlineTestProjectCreator.code(targetCode, "com/example/Target.java")
                .addFileContents(consumerCode, "com/example/Consumer.java")
                .build()) {

            JavaAnalyzer analyzer = new JavaAnalyzer(project);
            UsageFinder finder = UsageFinderTestUtil.createForTest(project, analyzer);

            // 1. Test usages of the class 'com.example.Target'
            FuzzyResult classResult = finder.findUsages("com.example.Target");
            assertTrue(classResult instanceof FuzzyResult.Success, "Expected Success for Target class search");
            Set<UsageHit> classHits = ((FuzzyResult.Success) classResult).hits();

            // Expected hits:
            // - Field declaration: private Target myTarget;
            // - Object creation: new Target();
            assertEquals(2, classHits.size(), "Expected exactly 2 usages of Target class (field and new)");

            for (UsageHit hit : classHits) {
                String snippet = hit.snippet();
                // Ensure we didn't match the parameter name or comment
                assertTrue(
                        snippet.contains("private Target myTarget") || snippet.contains("new Target()"),
                        "Unexpected hit snippet: " + snippet);
            }

            // 2. Test usages of the method 'com.example.Target.doSomething'
            FuzzyResult methodResult = finder.findUsages("com.example.Target.doSomething");
            assertTrue(methodResult instanceof FuzzyResult.Success, "Expected Success for doSomething search");
            Set<UsageHit> methodHits = ((FuzzyResult.Success) methodResult).hits();

            // Expected hits:
            // - Method call: this.myTarget.doSomething();
            assertEquals(1, methodHits.size(), "Expected exactly 1 usage of doSomething method");
            assertTrue(
                    methodHits.iterator().next().snippet().contains("doSomething()"),
                    "Snippet should contain the method call");
        }
    }
}
