package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.CommentDensityStats;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Optional;
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

    @Test
    void reportCommentDensityForCodeUnit_checksAllResolvedDefinitions() throws IOException {
        try (var project = InlineTestProjectCreator.code(
                        "export function typed(v: number) { return v; }\n", "src/sample.ts")
                .build()) {
            ProjectFile unsupported = new ProjectFile(project.getRoot(), "src/placeholder.txt");
            ProjectFile supported = new ProjectFile(project.getRoot(), "src/sample.ts");
            CodeUnit unsupportedCu = new CodeUnit(unsupported, CodeUnitType.FUNCTION, "", "typed");
            CodeUnit supportedCu = new CodeUnit(supported, CodeUnitType.FUNCTION, "", "typed");
            var analyzer = new TestAnalyzer() {
                @Override
                public java.util.SequencedSet<CodeUnit> getDefinitions(String fqName) {
                    return new LinkedHashSet<>(java.util.List.of(unsupportedCu, supportedCu));
                }

                @Override
                public Optional<CommentDensityStats> commentDensity(CodeUnit cu) {
                    if (cu.source().equals(supported)) {
                        return Optional.of(new CommentDensityStats("typed", "src/sample.ts", 1, 1, 3, 1, 1, 3));
                    }
                    return Optional.empty();
                }
            };
            var contextManager = new TestContextManager(project, new TestConsoleIO(), java.util.Set.of(), analyzer);
            var tools = new CodeQualityTools(contextManager);

            String report = tools.reportCommentDensityForCodeUnit("typed", 120);
            assertTrue(report.contains("`typed`"), report);
            assertTrue(report.contains("Own: header 1, inline 1"), report);
        }
    }
}
