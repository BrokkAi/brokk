package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class ContentDiffUtilsTest {

    @Test
    void testDiffAndApplyPreservesTrailingNewline() {
        String oldContent = "line1\nline2\n";
        String newContent = "line1\nline3\n";

        String diff = ContentDiffUtils.diff(oldContent, newContent);
        String appliedContent = ContentDiffUtils.applyDiff(diff, oldContent);

        assertEquals(newContent, appliedContent);
    }

    @Test
    void testNoChanges() {
        String content = "line1\nline2\n";
        String diff = ContentDiffUtils.diff(content, content);
        assertTrue(diff.isEmpty());
        String appliedContent = ContentDiffUtils.applyDiff(diff, content);
        assertEquals(content, appliedContent);
    }

    @Test
    void testInsertion() {
        String oldContent = "line1\nline3\n";
        String newContent = "line1\nline2\nline3\n";
        String diff = ContentDiffUtils.diff(oldContent, newContent);
        String appliedContent = ContentDiffUtils.applyDiff(diff, oldContent);
        assertEquals(newContent, appliedContent);
    }

    @Test
    void testDeletion() {
        String oldContent = "line1\nline2\nline3\n";
        String newContent = "line1\nline3\n";
        String diff = ContentDiffUtils.diff(oldContent, newContent);
        String appliedContent = ContentDiffUtils.applyDiff(diff, oldContent);
        assertEquals(newContent, appliedContent);
    }

    @Test
    void testModification() {
        String oldContent = "line1\noriginal\nline3";
        String newContent = "line1\nmodified\nline3";
        String diff = ContentDiffUtils.diff(oldContent, newContent);
        String appliedContent = ContentDiffUtils.applyDiff(diff, oldContent);
        assertEquals(newContent, appliedContent);
    }

    @Test
    void testEmptyStrings() {
        String oldContent = "";
        String newContent = "";
        String diff = ContentDiffUtils.diff(oldContent, newContent);
        assertTrue(diff.isEmpty());
        String appliedContent = ContentDiffUtils.applyDiff(diff, oldContent);
        assertEquals(newContent, appliedContent);

        newContent = "a\nb\n";
        diff = ContentDiffUtils.diff(oldContent, newContent);
        appliedContent = ContentDiffUtils.applyDiff(diff, oldContent);
        assertEquals(newContent, appliedContent);

        oldContent = "a\nb\n";
        newContent = "";
        diff = ContentDiffUtils.diff(oldContent, newContent);
        appliedContent = ContentDiffUtils.applyDiff(diff, oldContent);
        assertEquals(newContent, appliedContent);
    }

    @Test
    void testDiffResultCounts() {
        String oldContent = "a\nb\nc\n";
        String newContent = "a\nd\ne\n";
        var result = ContentDiffUtils.computeDiffResult(oldContent, newContent, "old", "new");
        assertEquals(2, result.added());
        assertEquals(2, result.deleted());

        oldContent = "a\n";
        newContent = "a\nb\nc\n";
        result = ContentDiffUtils.computeDiffResult(oldContent, newContent, "old", "new");
        assertEquals(2, result.added());
        assertEquals(0, result.deleted());

        oldContent = "a\nb\nc\n";
        newContent = "a\n";
        result = ContentDiffUtils.computeDiffResult(oldContent, newContent, "old", "new");
        assertEquals(0, result.added());
        assertEquals(2, result.deleted());

        oldContent = "a\n";
        newContent = "a\n";
        result = ContentDiffUtils.computeDiffResult(oldContent, newContent, "old", "new");
        assertEquals(0, result.added());
        assertEquals(0, result.deleted());
        assertTrue(result.diff().isEmpty());
    }

    @Test
    void testAddingTrailingNewline() {
        String oldContent = "line1\nline2";
        String newContent = "line1\nline2\n";
        String diff = ContentDiffUtils.diff(oldContent, newContent);
        String appliedContent = ContentDiffUtils.applyDiff(diff, oldContent);
        assertEquals(newContent, appliedContent);
    }

    @Test
    void testRemovingTrailingNewline() {
        String oldContent = "line1\nline2\n";
        String newContent = "line1\nline2";
        String diff = ContentDiffUtils.diff(oldContent, newContent);
        String appliedContent = ContentDiffUtils.applyDiff(diff, oldContent);
        assertEquals(newContent, appliedContent);
    }

    @Test
    void testNoTrailingNewline() {
        String oldContent = "line1\nline2";
        String newContent = "line1\nline3";
        String diff = ContentDiffUtils.diff(oldContent, newContent);
        String appliedContent = ContentDiffUtils.applyDiff(diff, oldContent);
        assertEquals(newContent, appliedContent);
    }

    @Test
    void testWindowsLineEndings() {
        String oldContent = "line1\r\nline2\r\n";
        String newContent = "line1\r\nline3\r\n";
        String diff = ContentDiffUtils.diff(oldContent, newContent);
        String appliedContent = ContentDiffUtils.applyDiff(diff, oldContent);
        // applyDiff will normalize to \n
        assertEquals("line1\nline3\n", appliedContent);
    }

    @Test
    void testMixedLineEndings() {
        String oldContent = "line1\r\nline2\n";
        String newContent = "line1\nline3\r\n";
        String diff = ContentDiffUtils.diff(oldContent, newContent);
        String appliedContent = ContentDiffUtils.applyDiff(diff, oldContent);
        // applyDiff will normalize to \n
        assertEquals("line1\nline3\n", appliedContent);
    }
}
