package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.io.IOException;
import java.util.List;
import java.util.Set;
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
            var contextManager = new TestContextManager(project, new TestConsoleIO(), Set.of(), project.getAnalyzer());
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
            var contextManager = new TestContextManager(project, new TestConsoleIO(), Set.of(), project.getAnalyzer());
            var tools = new CodeQualityTools(contextManager);

            String report = tools.reportCommentDensityForCodeUnit("src.typed", 120);
            assertTrue(report.contains("## Comment density"), report);
            assertTrue(report.contains("`src.typed`"), report);
            assertTrue(report.contains("Own: header 1, inline 1"), report);
            assertFalse(report.contains("unavailable"), report);
        }
    }

    @Test
    void reportCommentDensityForCodeUnit_supportsPython() throws IOException {
        try (var project = InlineTestProjectCreator.code(
                        """
                # header docs
                def documented(value):
                    return value + 1  # inline docs
                """,
                        "src/sample.py")
                .build()) {
            var contextManager = new TestContextManager(project, new TestConsoleIO(), Set.of(), project.getAnalyzer());
            var tools = new CodeQualityTools(contextManager);

            String report = tools.reportCommentDensityForCodeUnit("src.sample.documented", 120);
            assertTrue(report.contains("## Comment density"), report);
            assertTrue(report.contains("`src.sample.documented`"), report);
            assertTrue(report.contains("Own: header 1, inline 1"), report);
            assertFalse(report.contains("unavailable"), report);
        }
    }

    @Test
    void reportCommentDensityForFiles_usesAnalyzerCapabilityForJavaScript() throws IOException {
        try (var project = InlineTestProjectCreator.code(
                        """
                // header docs
                function documented(value) {
                    return value + 1; // inline docs
                }
                """,
                        "src/sample.js")
                .build()) {
            var contextManager = new TestContextManager(project, new TestConsoleIO(), Set.of(), project.getAnalyzer());
            var tools = new CodeQualityTools(contextManager);

            String report = tools.reportCommentDensityForFiles(List.of("src/sample.js"), 60, 25);
            assertTrue(report.contains("## Comment density by file"), report);
            assertTrue(report.contains("### `src/sample.js`"), report);
            assertTrue(report.contains("| `src.documented` | 1 | 1 |"), report);
            assertFalse(report.contains("not a Java file"), report);
            assertFalse(report.contains("unavailable"), report);
        }
    }

    @Test
    void reportCommentDensityForFiles_usesAnalyzerCapabilityForTypeScript() throws IOException {
        try (var project = InlineTestProjectCreator.code(
                        """
                // header docs
                function typed(value: number): number {
                    return value + 1; // inline docs
                }
                """,
                        "src/sample.ts")
                .build()) {
            var contextManager = new TestContextManager(project, new TestConsoleIO(), Set.of(), project.getAnalyzer());
            var tools = new CodeQualityTools(contextManager);

            String report = tools.reportCommentDensityForFiles(List.of("src/sample.ts"), 60, 25);
            assertTrue(report.contains("## Comment density by file"), report);
            assertTrue(report.contains("### `src/sample.ts`"), report);
            assertTrue(report.contains("| `src.typed` | 1 | 1 |"), report);
            assertFalse(report.contains("not a Java file"), report);
            assertFalse(report.contains("unavailable"), report);
        }
    }

    @Test
    void reportCommentDensityForFiles_usesAnalyzerCapabilityForPython() throws IOException {
        try (var project = InlineTestProjectCreator.code(
                        """
                # header docs
                def documented(value):
                    return value + 1  # inline docs
                """,
                        "src/sample.py")
                .build()) {
            var contextManager = new TestContextManager(project, new TestConsoleIO(), Set.of(), project.getAnalyzer());
            var tools = new CodeQualityTools(contextManager);

            String report = tools.reportCommentDensityForFiles(List.of("src/sample.py"), 60, 25);
            assertTrue(report.contains("## Comment density by file"), report);
            assertTrue(report.contains("### `src/sample.py`"), report);
            assertTrue(report.contains("| `src.sample.documented` | 1 | 1 |"), report);
            assertFalse(report.contains("not a Java file"), report);
            assertFalse(report.contains("unavailable"), report);
        }
    }

    @Test
    void reportCommentDensityForFiles_reportsUnavailableForUnsupportedAnalyzerResult() throws IOException {
        try (var project = InlineTestProjectCreator.code(
                        """
                Plain text.
                """, "notes/readme.txt")
                .build()) {
            var contextManager = new TestContextManager(project, new TestConsoleIO(), Set.of(), project.getAnalyzer());
            var tools = new CodeQualityTools(contextManager);

            String report = tools.reportCommentDensityForFiles(List.of("notes/readme.txt"), 60, 25);
            assertTrue(report.contains("### `notes/readme.txt`"), report);
            assertTrue(report.contains("Comment density is unavailable"), report);
            assertFalse(report.contains("not a Java file"), report);
        }
    }
}
