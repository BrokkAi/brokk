package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineCoreProject;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ScalaExceptionHandlingSmellTest {

    @Test
    void flagsNoOpCatchBody() {
        String code =
                """
                package com.example
                class Test {
                  def run(): Unit = {
                    try {
                      work()
                    } catch {
                      case e: Exception => ()
                    }
                  }
                  def work(): Unit = {}
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
                package com.example
                class Test {
                  def run(): Unit = {
                    try {
                      work()
                    } catch {
                      case _: Exception =>
                        // ignore during cleanup
                        ()
                    }
                  }
                  def work(): Unit = {}
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("comment-only-body")));
    }

    @Test
    void flagsGenericTinyCatchBody() {
        String code =
                """
                package com.example
                class Test {
                  def run(): Unit = {
                    try {
                      work()
                    } catch {
                      case t: Throwable => metrics()
                    }
                  }
                  def work(): Unit = {}
                  def metrics(): Unit = {}
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
                package com.example
                class Test {
                  def run(): Unit = {
                    try {
                      work()
                    } catch {
                      case e: Exception =>
                        val code = statusCode(e)
                        val message = "failure " + code
                        audit(message)
                        notifyOps(message)
                        throw new IllegalStateException(message, e)
                    }
                  }
                  def statusCode(e: Exception): Int = 500
                  def audit(msg: String): Unit = {}
                  def notifyOps(msg: String): Unit = {}
                  def work(): Unit = {}
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.isEmpty(), "Expected meaningful handler to be mitigated by body credit");
    }

    private List<IAnalyzer.ExceptionHandlingSmell> analyze(String source) {
        try (var testProject =
                InlineCoreProject.code(source, "com/example/Test.scala").build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            ProjectFile file = new ProjectFile(testProject.getRoot(), "com/example/Test.scala");
            return analyzer.findExceptionHandlingSmells(file, IAnalyzer.ExceptionSmellWeights.defaults());
        }
    }
}
