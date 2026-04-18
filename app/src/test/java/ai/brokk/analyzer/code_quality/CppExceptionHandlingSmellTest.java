package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.List;
import org.junit.jupiter.api.Test;

public class CppExceptionHandlingSmellTest {

    @Test
    void flagsEmptyCatchBody() {
        String code =
                """
                #include <stdexcept>

                void run() {
                    try {
                        throw std::runtime_error("bad");
                    } catch (...) {
                    }
                }
                """;
        var findings = analyze(code);
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("empty-body")));
        assertTrue(findings.stream().anyMatch(f -> f.reasons().stream().anyMatch(r -> r.startsWith("generic-catch:"))));
    }

    @Test
    void flagsCommentOnlyCatchBody() {
        String code =
                """
                #include <exception>

                void run() {
                    try {
                        throw 1;
                    } catch (const std::exception& e) {
                        /* ignore */
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("comment-only-body")));
    }

    @Test
    void flagsLogOnlyCatchBody() {
        String code =
                """
                #include <exception>
                #include <iostream>

                void run() {
                    try {
                        throw 1;
                    } catch (const std::exception& e) {
                        std::cerr << "bad";
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("log-only-body")));
    }

    @Test
    void meaningfulRethrowDoesNotFlagWithDefaultWeights() {
        String code =
                """
                #include <stdexcept>

                int status() { return 500; }
                void audit(int code) {}
                void notifyOps(int code) {}

                void run() {
                    try {
                        throw std::runtime_error("bad");
                    } catch (const std::exception& e) {
                        int code = status();
                        audit(code);
                        notifyOps(code);
                        throw;
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(
                findings.isEmpty(), "Expected meaningful handler to be mitigated by body credit; findings=" + findings);
    }

    private List<IAnalyzer.ExceptionHandlingSmell> analyze(String source) {
        try (var testProject =
                InlineTestProjectCreator.code(source, "src/test.cpp").build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            ProjectFile file = new ProjectFile(testProject.getRoot(), "src/test.cpp");
            return analyzer.findExceptionHandlingSmells(file, IAnalyzer.ExceptionSmellWeights.defaults());
        }
    }
}
