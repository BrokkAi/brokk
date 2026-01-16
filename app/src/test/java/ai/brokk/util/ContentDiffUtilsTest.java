package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void testParseUnifiedDiffSmoke() {
        String diffTxt =
                """
                diff --git a/file.txt b/file.txt
                index 1234567..89abcde 100644
                --- a/file.txt
                +++ b/file.txt
                @@ -1,3 +1,3 @@
                 line1
                -line2
                +line2 modified
                 line3
                """;

        var result = ContentDiffUtils.parseUnifiedDiff(diffTxt);

        assertTrue(result.isPresent());
        var unifiedDiff = result.get();
        assertFalse(unifiedDiff.getFiles().isEmpty());

        var file = unifiedDiff.getFiles().getFirst();
        // The parser usually strips the a/ and b/ prefixes
        assertTrue(file.getFromFile().endsWith("file.txt"));
        assertTrue(file.getToFile().endsWith("file.txt"));

        // Check that at least one delta exists and contains our change
        boolean foundChange = file.getPatch().getDeltas().stream()
                .anyMatch(d -> d.getSource().getLines().contains("line2")
                        && d.getTarget().getLines().contains("line2 modified"));
        assertTrue(foundChange, "Could not find the expected change in parsed deltas");
    }

    @Test
    void testParseUnifiedDiffEmptyFileCreationIsFiltered() {
        // This is the specific case filterEmptyFileCreations is designed to handle
        String diffTxt =
                """
                diff --git a/empty.txt b/empty.txt
                new file mode 100644
                index 0000000..e69de29
                --- /dev/null
                +++ b/empty.txt
                """;

        var result = ContentDiffUtils.parseUnifiedDiff(diffTxt);
        assertFalse(result.isPresent(), "Should filter out empty file creations with no hunks");
    }
}
