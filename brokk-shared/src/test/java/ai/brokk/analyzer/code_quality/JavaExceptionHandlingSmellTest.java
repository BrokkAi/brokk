package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineCoreProject;
import java.util.List;
import org.junit.jupiter.api.Test;

public class JavaExceptionHandlingSmellTest {

    @Test
    void flagsEmptyCatchBody() {
        String code =
                """
                package com.example;
                public class Test {
                    void run() {
                        try {
                            work();
                        } catch (Exception e) {
                        }
                    }
                    void work() {}
                }
                """;
        var findings = analyze(code);
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("empty-body")));
    }

    @Test
    void flagsCommentOnlyCatchBody() {
        String code =
                """
                package com.example;
                public class Test {
                    void run() {
                        try {
                            work();
                        } catch (Exception e) {
                            // ignore during cleanup
                        }
                    }
                    void work() {}
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("comment-only-body")));
    }

    @Test
    void flagsGenericTinyCatchBody() {
        String code =
                """
                package com.example;
                public class Test {
                    void run() {
                        try {
                            work();
                        } catch (Throwable t) {
                            metrics();
                        }
                    }
                    void work() {}
                    void metrics() {}
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.stream().anyMatch(f -> f.catchType().contains("Throwable")));
        assertTrue(findings.stream().anyMatch(f -> f.reasons().stream().anyMatch(r -> r.startsWith("small-body:"))));
    }

    @Test
    void substantialRethrowHandlingDoesNotFlagWithDefaultWeights() {
        String code =
                """
                package com.example;
                public class Test {
                    void run() {
                        try {
                            work();
                        } catch (Exception e) {
                            int code = statusCode(e);
                            String message = "failure " + code;
                            audit(message);
                            notifyOps(message);
                            throw new IllegalStateException(message, e);
                        }
                    }
                    int statusCode(Exception e) { return 500; }
                    void audit(String msg) {}
                    void notifyOps(String msg) {}
                    void work() {}
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.isEmpty(), "Expected meaningful handler to be mitigated by body credit");
    }

    @Test
    void weightTuningCanPromoteOrSuppressFindings() {
        String code =
                """
                package com.example;
                public class Test {
                    void run() {
                        try {
                            work();
                        } catch (RuntimeException e) {
                            log.warn("bad", e);
                        }
                    }
                    void work() {}
                }
                """;
        var defaults = analyze(code);
        var tunedWeights = new IAnalyzer.ExceptionSmellWeights(0, 0, 0, 0, 0, 0, 0, 5, 6, 2);
        var tuned = analyze(code, tunedWeights);
        assertFalse(defaults.isEmpty(), "Default heuristics should flag log-only tiny handler");
        assertEquals(0, tuned.size(), "Higher body credit should suppress the same finding");
    }

    private List<IAnalyzer.ExceptionHandlingSmell> analyze(String source) {
        return analyze(source, IAnalyzer.ExceptionSmellWeights.defaults());
    }

    private List<IAnalyzer.ExceptionHandlingSmell> analyze(String source, IAnalyzer.ExceptionSmellWeights weights) {
        try (var testProject =
                InlineCoreProject.code(source, "com/example/Test.java").build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            ProjectFile file = new ProjectFile(testProject.getRoot(), "com/example/Test.java");
            return analyzer.findExceptionHandlingSmells(file, weights);
        }
    }
}
