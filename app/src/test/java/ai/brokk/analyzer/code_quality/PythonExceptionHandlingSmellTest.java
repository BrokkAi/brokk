package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.List;
import org.junit.jupiter.api.Test;

public class PythonExceptionHandlingSmellTest {

    @Test
    void flagsBareExceptWithTinyBody() {
        String code =
                """
                def run():
                    try:
                        work()
                    except:
                        metrics()

                def work():
                    return 1

                def metrics():
                    return 0
                """;
        var findings = analyze(code);
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().anyMatch(f -> f.catchType().equals("<bare>")));
    }

    @Test
    void substantialHandlingCanAvoidFlag() {
        String code =
                """
                def run():
                    try:
                        work()
                    except Exception as e:
                        code = 500
                        msg = f"failed: {code}"
                        audit(msg)
                        notify(msg)
                        raise RuntimeError(msg) from e

                def work():
                    return 1

                def audit(msg):
                    return None

                def notify(msg):
                    return None
                """;
        var findings = analyze(code);
        assertTrue(findings.isEmpty());
    }

    @Test
    void reportsNestedExceptHandlers() {
        String code =
                """
                def run():
                    try:
                        outer()
                    except Exception as e:
                        try:
                            inner()
                        except:
                            logger.error("inner")
                        metrics()

                def outer():
                    return 1

                def inner():
                    return 2

                def metrics():
                    return 0
                """;
        var findings = analyze(code);
        long catches = findings.stream()
                .filter(f -> !f.catchType().equals("<unknown>"))
                .count();
        assertEquals(2, catches, "Expected both outer and inner except handlers to be reported");
    }

    private List<IAnalyzer.ExceptionHandlingSmell> analyze(String source) {
        try (var testProject =
                InlineTestProjectCreator.code(source, "pkg/test_mod.py").build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            ProjectFile file = new ProjectFile(testProject.getRoot(), "pkg/test_mod.py");
            return analyzer.findExceptionHandlingSmells(file, IAnalyzer.ExceptionSmellWeights.defaults());
        }
    }
}
