package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.List;
import org.junit.jupiter.api.Test;

public class JsTsExceptionHandlingSmellTest {

    @Test
    void flagsUntypedCatchWithSmallBody() {
        String code = """
                export function run() {
                  try {
                    work();
                  } catch (err) {
                    metrics();
                  }
                }

                function work() {}
                function metrics() {}
                """;
        var findings = analyze(code, "src/test.ts");
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().anyMatch(f -> f.catchType().equals("<untyped>")));
    }

    @Test
    void substantialHandlingCanAvoidFlag() {
        String code = """
                export function run() {
                  try {
                    work();
                  } catch (err: Error) {
                    const code = 500;
                    const msg = `failed ${code}`;
                    audit(msg);
                    notify(msg);
                    throw new Error(msg);
                  }
                }

                function work() {}
                function audit(_msg: string) {}
                function notify(_msg: string) {}
                """;
        var findings = analyze(code, "src/test.ts");
        assertTrue(findings.isEmpty());
    }

    @Test
    void reportsNestedCatchHandlers() {
        String code = """
                export function run() {
                  try {
                    outer();
                  } catch (err) {
                    try {
                      inner();
                    } catch (innerErr) {
                      log.error(innerErr);
                    }
                    metrics();
                  }
                }

                function outer() {}
                function inner() {}
                function metrics() {}
                """;
        var findings = analyze(code, "src/test.ts");
        long untypedCount = findings.stream().filter(f -> f.catchType().equals("<untyped>")).count();
        assertEquals(2, untypedCount, "Expected both outer and inner catch handlers to be reported");
    }

    private List<IAnalyzer.ExceptionHandlingSmell> analyze(String source, String relPath) {
        try (var testProject = InlineTestProjectCreator.code(source, relPath).build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            ProjectFile file = new ProjectFile(testProject.getRoot(), relPath);
            return analyzer.findExceptionHandlingSmells(file, IAnalyzer.ExceptionSmellWeights.defaults());
        }
    }
}
