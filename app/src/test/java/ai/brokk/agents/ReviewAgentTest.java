package ai.brokk.agents;

import ai.brokk.ICodeReview;
import ai.brokk.ICodeReview.CodeExcerpt;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.ProjectFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewAgentTest {

    @Test
    void testValidateFileExists() {
        // Simple manual mock for IContextManager to avoid Mockito dependency
        IContextManager cm = new IContextManager() {
            @Override
            public ProjectFile toFile(String relName) {
                if ("exists.java".equals(relName)) {
                    // Return a non-null dummy ProjectFile
                    return new ProjectFile(Path.of("/tmp"), "exists.java");
                }
                return null;
            }
        };

        Map<Integer, CodeExcerpt> excerpts = Map.of(
            0, new CodeExcerpt("exists.java", 0, ICodeReview.DiffSide.NEW, "code"),
            1, new CodeExcerpt("missing.java", 0, ICodeReview.DiffSide.NEW, "code")
        );

        Map<Integer, String> errors = ReviewAgent.validateFileExists(excerpts, cm);

        assertEquals(1, errors.size());
        assertTrue(errors.get(1).contains("File does not exist"));
    }


    @Test
    void testResolveExcerptsDisambiguation() {
        String content = """
            void method() {
                System.out.println("first");
            }
            // ... middle ...
            void method() {
                System.out.println("second");
            }
            """;

        IContextManager cm = new IContextManager() {
            @Override
            public ProjectFile toFile(String relName) {
                return new ProjectFile(Path.of("/tmp"), "file.java") {
                    @Override
                    public boolean exists() { return true; }
                    @Override
                    public java.util.Optional<String> read() { return java.util.Optional.of(content); }
                };
            }
        };

        var fileInfo = new ai.brokk.difftool.ui.FileComparisonInfo(
            cm.toFile("file.java"),
            new ai.brokk.difftool.ui.BufferSource.StringSource("", "", "file.java", null),
            new ai.brokk.difftool.ui.BufferSource.StringSource(content, "", "file.java", null)
        );

        ReviewAgent agent = new ReviewAgent("", cm, new ai.brokk.testutil.TestConsoleIO(), List.of(fileInfo));

        // LLM suggests the second occurrence (near line 6)
        Map<Integer, CodeExcerpt> excerpts = Map.of(
            0, new CodeExcerpt("file.java", 6, ICodeReview.DiffSide.NEW, "void method() {")
        );

        Map<Integer, CodeExcerpt> resolved = agent.resolveExcerpts(excerpts);

        assertEquals(1, resolved.size());
        // Matches are at 0 and 4 (0-based). LLM suggests 6.
        // Index 4 is closer to 6 than index 0 is.
        // The result should be 1-based line number: 4 + 1 = 5.
        assertEquals(5, resolved.get(0).line());
    }
}
