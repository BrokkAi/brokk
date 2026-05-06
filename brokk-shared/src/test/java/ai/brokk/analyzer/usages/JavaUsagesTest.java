package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.List;
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

        try (var project = InlineTestProjectCreator.code(targetCode, "com/example/Target.java")
                .addFileContents(consumerCode, "com/example/Consumer.java")
                .build()) {

            JavaAnalyzer analyzer = new JavaAnalyzer(project);
            var finder = new UsageFinder(project, analyzer, new RegexUsageAnalyzer(analyzer));

            // 1. Test usages of the class 'com.example.Target'
            List<CodeUnit> classOverloads = List.copyOf(analyzer.getDefinitions("com.example.Target"));
            assertTrue(!classOverloads.isEmpty(), "Expected definitions for Target class");

            FuzzyResult classResult = finder.findUsages(classOverloads);
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
            List<CodeUnit> methodOverloads = List.copyOf(analyzer.getDefinitions("com.example.Target.doSomething"));
            assertTrue(!methodOverloads.isEmpty(), "Expected definitions for doSomething method");

            FuzzyResult methodResult = finder.findUsages(methodOverloads);
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

    @Test
    public void testUsageAfterNonAsciiPrefixResolvesEnclosingCodeUnit() throws Exception {
        String targetCode =
                """
                package com.example;
                public class Target {}
                """;

        String consumerCode =
                """
                package com.example;
                public class Consumer {
                    String label = "caf\u00e9 \uD83D\uDE80";
                    private Target target;
                }
                """;

        try (var project = InlineTestProjectCreator.code(targetCode, "com/example/Target.java")
                .addFileContents(consumerCode, "com/example/Consumer.java")
                .build()) {
            JavaAnalyzer analyzer = new JavaAnalyzer(project);
            var finder = new UsageFinder(project, analyzer, new RegexUsageAnalyzer(analyzer));
            List<CodeUnit> classOverloads = List.copyOf(analyzer.getDefinitions("com.example.Target"));

            FuzzyResult result = finder.findUsages(classOverloads);
            assertTrue(result instanceof FuzzyResult.Success);
            Set<UsageHit> hits = ((FuzzyResult.Success) result).hits();

            assertEquals(1, hits.size());
            UsageHit hit = hits.iterator().next();
            assertTrue(hit.snippet().contains("private Target target"));
            assertEquals("target", hit.enclosing().identifier());
        }
    }

    @Test
    public void testNoTextualMatchProducesNoUsages() throws Exception {
        String targetCode =
                """
                package com.example;
                public class Missing {}
                """;

        String consumerCode =
                """
                package com.example;
                public class Consumer {
                    private String value;
                }
                """;

        try (var project = InlineTestProjectCreator.code(targetCode, "com/example/Missing.java")
                .addFileContents(consumerCode, "com/example/Consumer.java")
                .build()) {
            JavaAnalyzer analyzer = new JavaAnalyzer(project);
            var finder = new UsageFinder(project, analyzer, new RegexUsageAnalyzer(analyzer));
            List<CodeUnit> classOverloads = List.copyOf(analyzer.getDefinitions("com.example.Missing"));

            FuzzyResult result = finder.findUsages(classOverloads);
            assertTrue(result instanceof FuzzyResult.Success);

            assertEquals(0, ((FuzzyResult.Success) result).hits().size());
        }
    }
}
