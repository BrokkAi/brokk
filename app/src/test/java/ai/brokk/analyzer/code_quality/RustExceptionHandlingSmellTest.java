package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.List;
import org.junit.jupiter.api.Test;

public class RustExceptionHandlingSmellTest {

    @Test
    void flagsEmptyCatchUnwindErrArm() {
        String code =
                """
                use std::panic;

                pub fn work() {}

                pub fn run() {
                    let res = panic::catch_unwind(|| {
                        work();
                    });
                    match res {
                        Ok(_) => (),
                        Err(_) => {
                        }
                    }
                }
                """;
        var findings = analyze(code);
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("empty-body")));
    }

    @Test
    void flagsCommentOnlyErrArm() {
        String code =
                """
                pub fn run() {
                    let res: Result<(), ()> = Err(());
                    match res {
                        Ok(_) => (),
                        Err(_) => {
                            /* ignore */
                        }
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("comment-only-body")));
    }

    @Test
    void flagsLogOnlyErrArm() {
        String code =
                """
                pub fn run() {
                    let res: Result<(), ()> = Err(());
                    match res {
                        Ok(_) => (),
                        Err(_) => {
                            eprintln!(\"bad\");
                        }
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("log-only-body")));
    }

    @Test
    void flagsEmptyIfLetErrHandler() {
        String code =
                """
                pub fn run() {
                    let res: Result<(), ()> = Err(());
                    if let Err(_) = res {
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("empty-body")));
    }

    private List<IAnalyzer.ExceptionHandlingSmell> analyze(String source) {
        try (var testProject = InlineTestProjectCreator.code(source, "src/lib.rs").build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            ProjectFile file = new ProjectFile(testProject.getRoot(), "src/lib.rs");
            return analyzer.findExceptionHandlingSmells(file, IAnalyzer.ExceptionSmellWeights.defaults());
        }
    }
}

