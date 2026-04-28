package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.testutil.InlineCoreProject;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LongMethodAndGodObjectSmellTest {

    @Test
    void flagsLongMethodWithRangeAndRationale() {
        String source =
                """
                package com.example;
                public class Workflow {
                    public void generatedWorkflow() {
                %s
                    }
                }
                """
                        .formatted(statements(85));

        try (var project =
                InlineCoreProject.code(source, "com/example/Workflow.java").build()) {
            var smells =
                    project.getAnalyzer().findLongMethodAndGodObjectSmells(project.file("com/example/Workflow.java"));

            var longMethod = smells.stream()
                    .filter(smell -> smell.codeUnit().fqName().equals("com.example.Workflow.generatedWorkflow"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(longMethod.ownSpanLines() >= 80, longMethod.toString());
            assertFalse(longMethod.range().isEmpty(), longMethod.toString());
            assertTrue(
                    longMethod.reasons().stream().anyMatch(reason -> reason.contains("long function")),
                    longMethod.toString());
        }
    }

    @Test
    void flagsGodObjectAndHelperSprawl() {
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
                        .formatted(statements(65), helpers(16));

        try (var project = InlineCoreProject.code(source, "com/example/GeneratedController.java")
                .build()) {
            var smells = project.getAnalyzer()
                    .findLongMethodAndGodObjectSmells(project.file("com/example/GeneratedController.java"));

            var godObject = smells.stream()
                    .filter(smell -> smell.codeUnit().fqName().equals("com.example.GeneratedController"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(godObject.functionCount() >= 17, godObject.toString());
            assertTrue(godObject.directChildCount() >= 17, godObject.toString());
            assertTrue(godObject.maxFunctionSpanLines() >= 60, godObject.toString());
            assertTrue(
                    godObject.reasons().stream().anyMatch(reason -> reason.contains("helper sprawl")),
                    godObject.toString());
            assertEquals(godObject, smells.getFirst(), "god object should rank above its workflow helper");
        }
    }

    @Test
    void ignoresSmallCohesiveFile() {
        String source =
                """
                package com.example;
                public class Small {
                    public int add(int left, int right) {
                        return left + right;
                    }
                }
                """;

        try (var project =
                InlineCoreProject.code(source, "com/example/Small.java").build()) {
            var smells = project.getAnalyzer().findLongMethodAndGodObjectSmells(project.file("com/example/Small.java"));

            assertTrue(smells.isEmpty(), smells.toString());
        }
    }

    private static String statements(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> "        int value" + i + " = " + i + ";")
                .collect(java.util.stream.Collectors.joining("\n"));
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
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
