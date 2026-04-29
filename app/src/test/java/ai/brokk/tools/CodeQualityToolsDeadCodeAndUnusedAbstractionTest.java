package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.testutil.ITestProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodeQualityToolsDeadCodeAndUnusedAbstractionTest {

    @Test
    void reportsUnusedHelperWithUsageEvidence() throws IOException {
        String source =
                """
                package com.example;
                public class GeneratedResidue {
                    public int used(int value) {
                        return value + 1;
                    }

                    private int unusedHelper(int value) {
                        return value * 2;
                    }
                }
                """;

        try (var project = InlineTestProjectCreator.code(source, "src/main/java/com/example/GeneratedResidue.java")
                .build()) {
            var tools = tools(project);

            String report = reportWithDefaults(
                    tools,
                    List.of("src/main/java/com/example/GeneratedResidue.java"),
                    List.of("com.example.GeneratedResidue.unusedHelper"));

            assertTrue(report.contains("Dead code and unused abstraction smells"), report);
            assertTrue(report.contains("`com.example.GeneratedResidue.unusedHelper`"), report);
            assertTrue(report.contains("External Usages"), report);
            assertTrue(report.contains("| 0 | `no external usages found`"), report);
            assertTrue(report.contains("may be generated residue"), report);
        }
    }

    @Test
    void reportsOneCallAbstractionLowerThanUnusedCode() throws IOException {
        String target =
                """
                package com.example;
                public class Target {
                    public int oneCallWrapper(int value) {
                        return value + 1;
                    }

                    public int unusedHelper(int value) {
                        return value * 2;
                    }
                }
                """;
        String caller =
                """
                package com.example;
                public class Caller {
                    public int call(Target target) {
                        return target.oneCallWrapper(41);
                    }
                }
                """;

        try (var project = InlineTestProjectCreator.empty()
                .addFileContents(target, "src/main/java/com/example/Target.java")
                .addFileContents(caller, "src/main/java/com/example/Caller.java")
                .build()) {
            var tools = tools(project);

            String report = reportWithDefaults(
                    tools,
                    List.of("src/main/java/com/example/Target.java"),
                    List.of("com.example.Target.oneCallWrapper", "com.example.Target.unusedHelper"));

            assertTrue(report.contains("`com.example.Target.oneCallWrapper`"), report);
            assertTrue(report.contains("only external usage"), report);
            assertTrue(report.contains("`com.example.Target.unusedHelper`"), report);
            assertTrue(
                    scoreFor(report, "com.example.Target.unusedHelper")
                            > scoreFor(report, "com.example.Target.oneCallWrapper"),
                    report);
        }
    }

    @Test
    void suppressesSymbolsWithMultipleExternalCallers() throws IOException {
        String target =
                """
                package com.example;
                public class Target {
                    public int usedByMany(int value) {
                        return value + 1;
                    }
                }
                """;
        String firstCaller =
                """
                package com.example;
                public class FirstCaller {
                    public int call(Target target) {
                        return target.usedByMany(1);
                    }
                }
                """;
        String secondCaller =
                """
                package com.example;
                public class SecondCaller {
                    public int call(Target target) {
                        return target.usedByMany(2);
                    }
                }
                """;

        try (var project = InlineTestProjectCreator.empty()
                .addFileContents(target, "src/main/java/com/example/Target.java")
                .addFileContents(firstCaller, "src/main/java/com/example/FirstCaller.java")
                .addFileContents(secondCaller, "src/main/java/com/example/SecondCaller.java")
                .build()) {
            var tools = tools(project);

            String report = reportWithDefaults(
                    tools, List.of("src/main/java/com/example/Target.java"), List.of("com.example.Target.usedByMany"));

            assertTrue(report.contains("No dead code or unused abstraction smells met minScore"), report);
            assertFalse(report.contains("| `com.example.Target.usedByMany` |"), report);
        }
    }

    @Test
    void fqNamesNarrowAnalysisToRequestedSymbol() throws IOException {
        String source =
                """
                package com.example;
                public class Narrowing {
                    private int requestedUnused(int value) {
                        return value + 1;
                    }

                    private int otherUnused(int value) {
                        return value + 2;
                    }
                }
                """;

        try (var project = InlineTestProjectCreator.code(source, "src/main/java/com/example/Narrowing.java")
                .build()) {
            var tools = tools(project);

            String report = reportWithDefaults(
                    tools,
                    List.of("src/main/java/com/example/Narrowing.java"),
                    List.of("com.example.Narrowing.requestedUnused"));

            assertTrue(report.contains("`com.example.Narrowing.requestedUnused`"), report);
            assertFalse(report.contains("otherUnused"), report);
        }
    }

    @Test
    void maxFindingsAndMaxFilesBoundDiscovery() throws IOException {
        String first =
                """
                package com.example;
                public class First {
                    private int unusedOne(int value) {
                        return value + 1;
                    }
                }
                """;
        String second =
                """
                package com.example;
                public class Second {
                    private int unusedTwo(int value) {
                        return value + 2;
                    }
                }
                """;

        try (var project = InlineTestProjectCreator.empty()
                .addFileContents(first, "src/main/java/com/example/First.java")
                .addFileContents(second, "src/main/java/com/example/Second.java")
                .build()) {
            var tools = tools(project);

            String report = tools.reportDeadCodeAndUnusedAbstractionSmells(
                    List.of("src/main/java/com/example/First.java", "src/main/java/com/example/Second.java"),
                    List.of(),
                    0,
                    1,
                    1,
                    100);

            assertTrue(report.contains("Files analyzed cap: 1"), report);
            assertTrue(report.contains("Findings shown: 1"), report);
            assertTrue(report.contains("First"), report);
            assertFalse(report.contains("Second"), report);
        }
    }

    @Test
    void maxUsagesPerSymbolProducesSkippedEvidence() throws IOException {
        String target =
                """
                package com.example;
                public class Target {
                    public int noisy(int value) {
                        return value + 1;
                    }
                }
                """;
        String firstCaller =
                """
                package com.example;
                public class FirstCaller {
                    public int call(Target target) {
                        return target.noisy(1);
                    }
                }
                """;
        String secondCaller =
                """
                package com.example;
                public class SecondCaller {
                    public int call(Target target) {
                        return target.noisy(2);
                    }
                }
                """;

        try (var project = InlineTestProjectCreator.empty()
                .addFileContents(target, "src/main/java/com/example/Target.java")
                .addFileContents(firstCaller, "src/main/java/com/example/FirstCaller.java")
                .addFileContents(secondCaller, "src/main/java/com/example/SecondCaller.java")
                .build()) {
            var tools = tools(project);

            String report = tools.reportDeadCodeAndUnusedAbstractionSmells(
                    List.of("src/main/java/com/example/Target.java"),
                    List.of("com.example.Target.noisy"),
                    0,
                    40,
                    25,
                    1);

            assertTrue(report.contains("Skipped evidence:"), report);
            assertTrue(report.contains("Too many call sites"), report);
        }
    }

    @Test
    void unresolvedSymbolProducesClearSkippedEvidence() throws IOException {
        String source =
                """
                package com.example;
                public class Empty {
                }
                """;

        try (var project = InlineTestProjectCreator.code(source, "src/main/java/com/example/Empty.java")
                .build()) {
            var tools = tools(project);

            String report = reportWithDefaults(
                    tools, List.of("src/main/java/com/example/Empty.java"), List.of("com.example.DoesNotExist"));

            assertTrue(report.contains("No dead code or unused abstraction smells met minScore"), report);
            assertTrue(report.contains("Skipped evidence:"), report);
            assertTrue(report.contains("`com.example.DoesNotExist`: no definition found"), report);
        }
    }

    private static CodeQualityTools tools(ITestProject project) {
        return new CodeQualityTools(
                new TestContextManager(project, new TestConsoleIO(), Set.of(), project.getAnalyzer()));
    }

    private static String reportWithDefaults(CodeQualityTools tools, List<String> paths, List<String> fqNames) {
        return tools.reportDeadCodeAndUnusedAbstractionSmells(paths, fqNames, 0, 40, 25, 100);
    }

    private static int scoreFor(String report, String symbol) {
        return report.lines()
                .filter(line -> line.contains("`" + symbol + "`"))
                .map(line -> line.split("\\|"))
                .map(parts -> parts[1].strip())
                .mapToInt(Integer::parseInt)
                .findFirst()
                .orElseThrow();
    }
}
