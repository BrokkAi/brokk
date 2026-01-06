package ai.brokk.agents;

import ai.brokk.ICodeReview;
import ai.brokk.ICodeReview.CodeExcerpt;
import ai.brokk.IContextManager;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void testValidateFiles() throws IOException {
        Files.writeString(tempDir.resolve("exists.java"), "public class Exists {}");
        
        TestProject project = new TestProject(tempDir);
        IContextManager cm = new TestContextManager(project);

        Map<Integer, CodeExcerpt> excerpts = Map.of(
            0, new CodeExcerpt("exists.java", 0, ICodeReview.DiffSide.NEW, "code"),
            1, new CodeExcerpt("missing.java", 0, ICodeReview.DiffSide.NEW, "code")
        );

        Map<Integer, String> errors = ReviewAgent.validateFiles(excerpts, cm);

        assertEquals(1, errors.size());
        assertTrue(errors.containsKey(1));
        assertTrue(errors.get(1).contains("File does not exist"));
    }
}
