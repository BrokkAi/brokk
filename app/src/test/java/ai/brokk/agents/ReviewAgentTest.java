package ai.brokk.agents;

import ai.brokk.ICodeReview.CodeExcerpt;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.ProjectFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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
            0, new CodeExcerpt("exists.java", "code"),
            1, new CodeExcerpt("missing.java", "code")
        );

        Map<Integer, String> errors = ReviewAgent.validateFileExists(excerpts, cm);

        assertEquals(1, errors.size());
        assertTrue(errors.get(1).contains("File does not exist"));
    }

    @Test
    void testValidateExcerptInDiff() {
        String diff = "line 1\n+ new code\nline 2";
        
        Map<Integer, CodeExcerpt> excerpts = Map.of(
            0, new CodeExcerpt("file.java", "new code"),
            1, new CodeExcerpt("file.java", "missing code")
        );

        Map<Integer, String> errors = ReviewAgent.validateExcerptInDiff(excerpts, diff);

        assertEquals(1, errors.size());
        assertEquals("Excerpt not found in diff", errors.get(1));
    }
}
