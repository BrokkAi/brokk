package ai.brokk.analyzer.comments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CommentDensityStats;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

public class PolyglotCommentDensityTests {

    @Test
    void pythonCommentDensityCapturesHeaderAndInlineComments() {
        String code =
                """
                class A:
                    # header for method
                    def f(self):
                        # inline in body
                        return 1
                """;
        try (var testProject = InlineTestProjectCreator.code(code, "sample.py").build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            var file = testProject.getFileByRelPath(Path.of("sample.py")).orElseThrow();

            List<CommentDensityStats> rows = analyzer.commentDensityByTopLevel(file);
            assertEquals(analyzer.getTopLevelDeclarations(file).size(), rows.size());
            assertTrue(rows.stream()
                    .mapToInt(r -> r.rolledUpHeaderCommentLines() + r.rolledUpInlineCommentLines())
                    .sum() > 0);
            assertTrue(rows.stream().anyMatch(r -> r.rolledUpInlineCommentLines() > 0));

            CodeUnit function = analyzer.getDeclarations(file).stream()
                    .filter(CodeUnit::isFunction)
                    .findFirst()
                    .orElseThrow();
            CommentDensityStats fnStats = analyzer.commentDensity(function).orElseThrow();
            assertTrue(fnStats.inlineCommentLines() > 0);
        }
    }

    @Test
    void javascriptCommentDensityCapturesHeaderAndInlineComments() {
        String code =
                """
                class A {
                  // header for method
                  f() {
                    // inline in body
                    return 1;
                  }
                }
                """;
        try (var testProject = InlineTestProjectCreator.code(code, "sample.js").build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            var file = testProject.getFileByRelPath(Path.of("sample.js")).orElseThrow();

            List<CommentDensityStats> rows = analyzer.commentDensityByTopLevel(file);
            assertEquals(analyzer.getTopLevelDeclarations(file).size(), rows.size());
            assertTrue(rows.stream().anyMatch(r -> r.rolledUpHeaderCommentLines() > 0));
            assertTrue(rows.stream().anyMatch(r -> r.rolledUpInlineCommentLines() > 0));

            CodeUnit function = analyzer.getDeclarations(file).stream()
                    .filter(CodeUnit::isFunction)
                    .findFirst()
                    .orElseThrow();
            CommentDensityStats fnStats = analyzer.commentDensity(function).orElseThrow();
            assertTrue(fnStats.inlineCommentLines() > 0);
        }
    }
}
