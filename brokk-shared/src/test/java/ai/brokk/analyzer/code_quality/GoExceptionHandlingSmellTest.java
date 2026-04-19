package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineCoreProject;
import java.util.List;
import org.junit.jupiter.api.Test;

public class GoExceptionHandlingSmellTest {

    @Test
    void flagsEmptyRecoverHandler() {
        String code =
                """
                package main

                func work() {}

                func run() {
                    defer func() {
                        if r := recover(); r != nil {
                        }
                    }()
                    work()
                }
                """;
        var findings = analyze(code);
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("empty-body")));
        assertTrue(findings.stream().anyMatch(f -> f.reasons().stream().anyMatch(r -> r.startsWith("generic-catch:"))));
    }

    @Test
    void flagsCommentOnlyRecoverHandler() {
        String code =
                """
                package main

                func run() {
                    defer func() {
                        if r := recover(); r != nil {
                            // ignore
                        }
                    }()
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("comment-only-body")));
    }

    @Test
    void flagsLogOnlyRecoverHandler() {
        String code =
                """
                package main

                import "log"

                func run() {
                    defer func() {
                        if r := recover(); r != nil {
                            log.Printf("bad: %v", r)
                        }
                    }()
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("log-only-body")));
    }

    @Test
    void meaningfulRecoverHandlerWithRethrowIsMitigated() {
        String code =
                """
                package main

                import "log"

                func run() {
                    defer func() {
                        if r := recover(); r != nil {
                            x := 1
                            y := 2
                            z := x + y
                            log.Printf("bad: %v %d", r, z)
                            panic(r)
                        }
                    }()
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.isEmpty(), "Expected meaningful handler to be mitigated by body credit");
    }

    @Test
    void flagsEmptyErrNotNilHandler() {
        String code =
                """
                package main

                func run() {
                    var err error
                    if err != nil {
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("empty-body")));
    }

    private List<IAnalyzer.ExceptionHandlingSmell> analyze(String source) {
        try (var testProject = InlineCoreProject.code(source, "main.go").build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            ProjectFile file = new ProjectFile(testProject.getRoot(), "main.go");
            return analyzer.findExceptionHandlingSmells(file, IAnalyzer.ExceptionSmellWeights.defaults());
        }
    }
}
