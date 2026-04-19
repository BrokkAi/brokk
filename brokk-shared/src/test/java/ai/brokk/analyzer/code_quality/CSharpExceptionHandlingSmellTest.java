package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineCoreProject;
import java.util.List;
import org.junit.jupiter.api.Test;

public class CSharpExceptionHandlingSmellTest {

    @Test
    void emptyCatchExceptionIsFlagged() {
        var findings = analyze(
                """
                using System;

                namespace Example;

                class Test {
                    void Run() {
                        try {
                            Console.WriteLine("hi");
                        } catch (Exception e) {
                        }
                    }
                }
                """,
                "com/example/Test.cs");
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("generic-catch:Exception")));
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("empty-body")));
    }

    @Test
    void catchAllIsFlagged() {
        var findings = analyze(
                """
                using System;

                class Test {
                    void Run() {
                        try {
                            Console.WriteLine("hi");
                        } catch {
                        }
                    }
                }
                """,
                "com/example/Test.cs");
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("generic-catch:Exception")));
    }

    @Test
    void logOnlyCatchIsFlagged() {
        var findings = analyze(
                """
                using System;

                class Logger {
                    public void LogError(Exception e) { }
                }

                class Test {
                    private readonly Logger logger = new Logger();

                    void Run() {
                        try {
                            Console.WriteLine("hi");
                        } catch (Exception e) {
                            logger.LogError(e);
                        }
                    }
                }
                """,
                "com/example/Test.cs");
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("log-only-body")));
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("small-body:1")));
    }

    @Test
    void bodyCreditCanSuppressTinyHandler() {
        var code =
                """
                using System;

                class Logger {
                    public void LogError(Exception e) { }
                }

                class Test {
                    private readonly Logger logger = new Logger();

                    void Run() {
                        try {
                            Console.WriteLine("hi");
                        } catch (Exception e) {
                            logger.LogError(e);
                        }
                    }
                }
                """;
        var defaults = analyze(code, "com/example/Test.cs", IAnalyzer.ExceptionSmellWeights.defaults());
        var tunedWeights = new IAnalyzer.ExceptionSmellWeights(0, 0, 0, 0, 0, 0, 0, 5, 6, 2);
        var tuned = analyze(code, "com/example/Test.cs", tunedWeights);
        assertFalse(defaults.isEmpty(), "Default heuristics should flag log-only tiny handler");
        assertEquals(0, tuned.size(), "Higher body credit should suppress the same finding");
    }

    private List<IAnalyzer.ExceptionHandlingSmell> analyze(String source, String relPath) {
        return analyze(source, relPath, IAnalyzer.ExceptionSmellWeights.defaults());
    }

    private List<IAnalyzer.ExceptionHandlingSmell> analyze(
            String source, String relPath, IAnalyzer.ExceptionSmellWeights weights) {
        try (var testProject = InlineCoreProject.code(source, relPath).build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            ProjectFile file = new ProjectFile(testProject.getRoot(), relPath);
            return analyzer.findExceptionHandlingSmells(file, weights);
        }
    }
}
