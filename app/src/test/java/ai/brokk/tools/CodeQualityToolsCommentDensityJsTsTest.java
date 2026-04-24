package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class CodeQualityToolsCommentDensityJsTsTest {

    @Test
    void reportCommentDensityForCodeUnit_supportsJavaScript() throws IOException {
        try (var project = InlineTestProjectCreator.code(
                        """
                // header docs
                function documented(value) {
                    return value + 1; // inline docs
                }

                function bare(value) {
                    return value * 2;
                }
                """,
                        "src/sample.js")
                .build()) {
            var contextManager =
                    new TestContextManager(project, new TestConsoleIO(), java.util.Set.of(), project.getAnalyzer());
            var tools = new CodeQualityTools(contextManager);

            String documentedReport = tools.reportCommentDensityForCodeUnit("src.documented", 120);
            assertTrue(documentedReport.contains("## Comment density"), documentedReport);
            assertTrue(documentedReport.contains("`src.documented`"), documentedReport);
            assertTrue(documentedReport.contains("Own: header 1, inline 1"), documentedReport);
            assertFalse(documentedReport.contains("unavailable"), documentedReport);

            String bareReport = tools.reportCommentDensityForCodeUnit("src.bare", 120);
            assertTrue(bareReport.contains("Own: header 0, inline 0"), bareReport);
        }
    }

    @Test
    void reportCommentDensityForCodeUnit_supportsTypeScript() throws IOException {
        try (var project = InlineTestProjectCreator.code(
                        """
                // header docs
                function typed(value: number): number {
                    return value + 1; // inline docs
                }
                """,
                        "src/sample.ts")
                .build()) {
            var contextManager =
                    new TestContextManager(project, new TestConsoleIO(), java.util.Set.of(), project.getAnalyzer());
            var tools = new CodeQualityTools(contextManager);

            String report = tools.reportCommentDensityForCodeUnit("src.typed", 120);
            assertTrue(report.contains("## Comment density"), report);
            assertTrue(report.contains("`src.typed`"), report);
            assertTrue(report.contains("Own: header 1, inline 1"), report);
            assertFalse(report.contains("unavailable"), report);
        }
    }
}
