package ai.brokk.agents;

import ai.brokk.ICodeReview;
import ai.brokk.ICodeReview.CodeExcerpt;
import ai.brokk.IContextManager;
import ai.brokk.difftool.ui.BufferSource;
import ai.brokk.difftool.ui.FileComparisonInfo;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import ai.brokk.util.WhitespaceMatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void testFindFileComparison() {
        var left = new BufferSource.StringSource("old", "OLD", "old_path.java");
        var right = new BufferSource.StringSource("new", "NEW", "new_path.java");
        var info = new FileComparisonInfo(null, left, right);
        var comparisons = List.of(info);

        assertEquals(info, ReviewAgent.findFileComparison("old_path.java", comparisons));
        assertEquals(info, ReviewAgent.findFileComparison("new_path.java", comparisons));
        assertNull(ReviewAgent.findFileComparison("other.java", comparisons));
    }

    @Test
    void testFindBestMatch() {
        var matches = List.of(
            new WhitespaceMatch(10, "line 11"),
            new WhitespaceMatch(20, "line 21"),
            new WhitespaceMatch(30, "line 31")
        );

        // Exact match
        assertEquals(10, ReviewAgent.findBestMatch(matches, 11).startLine());
        
        // Closer to middle
        assertEquals(20, ReviewAgent.findBestMatch(matches, 22).startLine());
        
        // Closer to end
        assertEquals(30, ReviewAgent.findBestMatch(matches, 28).startLine());

        // Tie-breaker (first one wins)
        assertEquals(10, ReviewAgent.findBestMatch(matches, 16).startLine());
    }

    @Test
    void testMatchExcerptInFile() {
        var left = new BufferSource.StringSource("line1\nline2\nline3", "OLD", "test.java");
        var right = new BufferSource.StringSource("line1\nline2-new\nline3", "NEW", "test.java");
        var info = new FileComparisonInfo(null, left, right);

        // Match in NEW
        var excerptNew = new CodeExcerpt("test.java", 2, ICodeReview.DiffSide.NEW, "line2-new");
        var matchNew = ReviewAgent.matchExcerptInFile(excerptNew, info);
        assertNotNull(matchNew);
        assertEquals(2, matchNew.line());
        assertEquals(ICodeReview.DiffSide.NEW, matchNew.side());

        // Match in OLD (not in new)
        var excerptOld = new CodeExcerpt("test.java", 2, ICodeReview.DiffSide.NEW, "line2");
        var matchOld = ReviewAgent.matchExcerptInFile(excerptOld, info);
        assertNotNull(matchOld);
        assertEquals(2, matchOld.line());
        assertEquals(ICodeReview.DiffSide.OLD, matchOld.side());

        // Whitespace insensitive
        var excerptWS = new CodeExcerpt("test.java", 1, ICodeReview.DiffSide.NEW, "  line1  ");
        var matchWS = ReviewAgent.matchExcerptInFile(excerptWS, info);
        assertNotNull(matchWS);
        assertEquals(1, matchWS.line());

        // No match
        var excerptNone = new CodeExcerpt("test.java", 1, ICodeReview.DiffSide.NEW, "garbage");
        assertNull(ReviewAgent.matchExcerptInFile(excerptNone, info));
    }
}
