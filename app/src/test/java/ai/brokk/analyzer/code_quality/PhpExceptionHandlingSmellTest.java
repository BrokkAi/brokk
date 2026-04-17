package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.List;
import org.junit.jupiter.api.Test;

public class PhpExceptionHandlingSmellTest {

    @Test
    void flagsEmptyCatchBody() {
        String code =
                """
                <?php
                function run(): void {
                    try {
                        work();
                    } catch (\\Exception $e) {
                    }
                }

                function work(): void {}
                """;
        var findings = analyze(code);
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("empty-body")));
    }

    @Test
    void flagsCommentOnlyCatchBody() {
        String code =
                """
                <?php
                function run(): void {
                    try {
                        work();
                    } catch (\\Exception $e) {
                        /* ignore during cleanup */
                    }
                }

                function work(): void {}
                """;
        var findings = analyze(code);
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("comment-only-body")));
    }

    @Test
    void flagsGenericTinyCatchBody() {
        String code =
                """
                <?php
                function run(): void {
                    try {
                        work();
                    } catch (\\Throwable $e) {
                        metrics();
                    }
                }

                function work(): void {}
                function metrics(): void {}
                """;
        var findings = analyze(code);
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("generic-catch:Throwable")));
        assertTrue(findings.stream().anyMatch(f -> f.reasons().stream().anyMatch(r -> r.startsWith("small-body:"))));
    }

    @Test
    void substantialRethrowHandlingDoesNotFlagWithDefaultWeights() {
        String code =
                """
                <?php
                function run(): void {
                    try {
                        work();
                    } catch (\\Exception $e) {
                        $code = status_code($e);
                        $msg = "failure " . $code;
                        audit($msg);
                        notify_ops($msg);
                        throw new \\RuntimeException($msg, 0, $e);
                    }
                }

                function status_code(\\Exception $e): int { return 500; }
                function audit(string $msg): void {}
                function notify_ops(string $msg): void {}
                function work(): void {}
                """;
        var findings = analyze(code);
        assertTrue(findings.isEmpty(), "Expected meaningful handler to be mitigated by body credit");
    }

    @Test
    void weightTuningCanPromoteOrSuppressFindings() {
        String code =
                """
                <?php
                function run($logger): void {
                    try {
                        work();
                    } catch (\\RuntimeException $e) {
                        $logger->warn("bad", $e);
                    }
                }

                function work(): void {}
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
                InlineTestProjectCreator.code(source, "pkg/Test.php").build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            ProjectFile file = new ProjectFile(testProject.getRoot(), "pkg/Test.php");
            return analyzer.findExceptionHandlingSmells(file, weights);
        }
    }
}
