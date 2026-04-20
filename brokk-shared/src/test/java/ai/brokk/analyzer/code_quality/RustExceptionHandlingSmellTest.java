package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineCoreProject;
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

    @Test
    void doesNotMisattributeNestedIfLetErrToOuterIf() {
        String code =
                """
                pub fn run() {
                    let res: Result<(), ()> = Err(());
                    if true {
                        let x = 1;
                        if let Err(_) = res {
                        }
                        let y = x + 1;
                        let _ = y;
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.stream().anyMatch(f -> f.reasons().contains("empty-body")));
        assertTrue(findings.size() == 1, "Expected only the nested if-let to be flagged");
        assertTrue(
                findings.getFirst().bodyStatementCount() == 0,
                "Expected the flagged handler to be the empty nested if-let body, not the outer if body");
    }

    @Test
    void doesNotAnalyzeNestedMatchArmsUnderOuterMatchContext() {
        String code =
                """
                pub fn run() {
                    let outer: Result<(), ()> = Ok(());
                    let inner: Result<(), ()> = Err(());
                    match outer {
                        Ok(_) => {
                            match inner {
                                Ok(_) => (),
                                Err(_) => {
                                }
                            }
                        }
                        Err(_) => {
                            let a = 1;
                            let b = 2;
                            let c = a + b;
                            let d = c + 1;
                            let e = d + 1;
                            let _ = e;
                        }
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.size() == 1, "Expected only the inner match Err arm to be flagged");
        assertTrue(findings.getFirst().reasons().contains("empty-body"));
    }

    private List<IAnalyzer.ExceptionHandlingSmell> analyze(String source) {
        try (var testProject = InlineCoreProject.code(source, "src/lib.rs").build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            ProjectFile file = new ProjectFile(testProject.getRoot(), "src/lib.rs");
            return analyzer.findExceptionHandlingSmells(file, IAnalyzer.ExceptionSmellWeights.defaults());
        }
    }
}
