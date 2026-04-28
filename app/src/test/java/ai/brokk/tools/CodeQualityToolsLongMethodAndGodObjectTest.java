package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CodeQualityToolsLongMethodAndGodObjectTest {

    @Test
    void reportLongMethodAndGodObjectSmellsReturnsBoundedMarkdown() throws IOException {
        String source =
                """
                package com.example;
                public class GeneratedController {
                    public void executeWorkflow() {
                %s
                    }
                %s
                }
                """
                        .formatted(statements(85), helpers(16));

        try (var project = InlineTestProjectCreator.code(source, "src/main/java/com/example/GeneratedController.java")
                .build()) {
            var tools = new CodeQualityTools(
                    new TestContextManager(project, new TestConsoleIO(), Set.of(), project.getAnalyzer()));

            String report = tools.reportLongMethodAndGodObjectSmells(
                    List.of("src/main/java/com/example/GeneratedController.java", "missing/Missing.java"), 1);

            assertTrue(report.contains("Long method and god object smells"), report);
            assertTrue(report.contains("`com.example.GeneratedController"), report);
            assertTrue(report.contains("Signals: own"), report);
            assertTrue(report.contains("Rationale:"), report);
            assertFalse(
                    report.contains("executeWorkflow"), "maxFindings should limit output to one finding: " + report);
        }
    }

    @Test
    void reportLongMethodAndGodObjectSmellsReturnsEmptyMessage() throws IOException {
        String source =
                """
                package com.example;
                public class Small {
                    public int add(int left, int right) {
                        return left + right;
                    }
                }
                """;

        try (var project = InlineTestProjectCreator.code(source, "src/main/java/com/example/Small.java")
                .build()) {
            var tools = new CodeQualityTools(
                    new TestContextManager(project, new TestConsoleIO(), Set.of(), project.getAnalyzer()));

            String report = tools.reportLongMethodAndGodObjectSmells(
                    List.of("src/main/java/com/example/Small.java", "missing/Missing.java"), 20);

            assertTrue(report.contains("No long method or god object smells found."), report);
        }
    }

    private static String statements(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> "        int value" + i + " = " + i + ";")
                .collect(Collectors.joining("\n"));
    }

    private static String helpers(int count) {
        return IntStream.range(0, count)
                .mapToObj(i ->
                        """
                            private int helper%s(int value) {
                                return value + %s;
                            }
                        """
                                .formatted(i, i))
                .collect(Collectors.joining("\n"));
    }
}
