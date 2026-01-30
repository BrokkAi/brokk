package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

public class ContentDiffUtilsTest {

    public static ContentDiffUtils.DiffComputationResult computeReviewDiffResult(
            String oldContent, String newContent, @Nullable String oldName, @Nullable String newName) {
        return ContentDiffUtils.computeReviewDiffResult(null, null, oldContent, newContent, oldName, newName);
    }

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
    void testComputeReviewDiffResultDynamicContext() {
        // We infer context lines from the first hunk header counts.
        // For a pure insertion (deleted=0), if the change is not near file boundaries:
        // oldCount == 2 * contextLines
        // newCount == 2 * contextLines + added
        Pattern hunkHeader = Pattern.compile("(?m)^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");

        java.util.function.BiFunction<String, Integer, Integer> inferredContextLinesForPureInsert = (diff, added) -> {
            Matcher m = hunkHeader.matcher(diff);
            assertTrue(m.find(), "Expected at least one hunk header in diff:\n" + diff);

            int oldCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            int newCount = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));

            assertEquals(added, newCount - oldCount, "Unexpected hunk size delta in header: " + m.group(0));
            assertEquals(0, oldCount % 2, "Expected even oldCount for symmetric context: " + m.group(0));
            return oldCount / 2;
        };

        // 1) Small hunk: 1 line inserted => context = min(10, ceil(1/20)) = 1 (not 3).
        var smallOldLines =
                IntStream.rangeClosed(1, 10).mapToObj(String::valueOf).collect(Collectors.toCollection(ArrayList::new));
        String oldContent = String.join("\n", smallOldLines) + "\n";

        var smallNewLines = new ArrayList<>(smallOldLines);
        smallNewLines.add(5, "NEW"); // insert in the middle so context is not truncated by file boundaries
        String newContent = String.join("\n", smallNewLines) + "\n";

        var res1 = computeReviewDiffResult(oldContent, newContent, "file", "file");
        assertEquals(1, inferredContextLinesForPureInsert.apply(res1.diff(), 1));
        assertTrue(ContentDiffUtils.parseUnifiedDiff(res1.diff()).isPresent(), "Diff should be parseable");

        // 2) Larger hunk: 21 lines inserted => context = min(10, ceil(21/20)) = 2
        var baseOldLines =
                IntStream.rangeClosed(1, 50).mapToObj(String::valueOf).collect(Collectors.toCollection(ArrayList::new));
        String baseOld = String.join("\n", baseOldLines) + "\n";

        var baseNewLines = new ArrayList<>(baseOldLines);
        for (int i = 1; i <= 21; i++) {
            baseNewLines.add(25, "NEW" + i); // keep inserting at same index to preserve ordering around the hunk
        }
        String baseNew = String.join("\n", baseNewLines) + "\n";

        var res2 = computeReviewDiffResult(baseOld, baseNew, "file", "file");
        assertEquals(2, inferredContextLinesForPureInsert.apply(res2.diff(), 21));
        assertTrue(ContentDiffUtils.parseUnifiedDiff(res2.diff()).isPresent(), "Diff should be parseable");

        // 3) Very large hunk: 200 lines inserted => context = min(10, ceil(200/20)) = 10
        var hugeOldLines = IntStream.rangeClosed(1, 300)
                .mapToObj(String::valueOf)
                .collect(Collectors.toCollection(ArrayList::new));
        String hugeOld = String.join("\n", hugeOldLines) + "\n";

        var hugeNewLines = new ArrayList<>(hugeOldLines);
        for (int i = 1; i <= 200; i++) {
            hugeNewLines.add(150, "NEW" + i);
        }
        String hugeNew = String.join("\n", hugeNewLines) + "\n";

        var res3 = computeReviewDiffResult(hugeOld, hugeNew, "file", "file");
        assertEquals(10, inferredContextLinesForPureInsert.apply(res3.diff(), 200));
        assertTrue(ContentDiffUtils.parseUnifiedDiff(res3.diff()).isPresent(), "Diff should be parseable");
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

    @Test
    void testStandardDiffUsesStandardContext() {
        // Small change in middle of file
        var lines = IntStream.rangeClosed(1, 20).mapToObj(String::valueOf).toList();
        String oldContent = String.join("\n", lines) + "\n";
        var newLines = new ArrayList<>(lines);
        newLines.set(10, "CHANGED");
        String newContent = String.join("\n", newLines) + "\n";

        // Standard diff should have 3 lines of context
        var standardResult = ContentDiffUtils.computeDiffResult(oldContent, newContent, "file", "file");
        assertTrue(standardResult.diff().contains("@@ -8,7 +8,7 @@")); // 3 before, 1 change, 3 after = 7 lines total

        // Review diff should have 1 line of context for this small change (ceil(2/20) = 1)
        var reviewResult = computeReviewDiffResult(oldContent, newContent, "file", "file");
        assertTrue(reviewResult.diff().contains("@@ -10,3 +10,3 @@")); // 1 before, 1 change, 1 after = 3 lines total
    }

    @Test
    void testComputeReviewDiffResultMultipleHunksWithDifferentContext() {
        // Create a file with enough lines to support large context
        var lines = IntStream.rangeClosed(1, 500)
                .mapToObj(String::valueOf)
                .collect(Collectors.toCollection(ArrayList::new));
        String oldContent = String.join("\n", lines) + "\n";

        var newLines = new ArrayList<>(lines);

        // Hunk 1: Small change (1 line modified) -> chunkLines = 2 -> context = ceil(2/20) = 1
        newLines.set(50, "SMALL_CHANGE");

        // Hunk 2: Large change (200 lines inserted) -> chunkLines = 200 -> context = ceil(200/20) = 10
        // Insert far enough away to avoid hunk merging
        for (int i = 0; i < 200; i++) {
            newLines.add(300, "LARGE_INSERT_" + i);
        }

        String newContent = String.join("\n", newLines) + "\n";
        var result = computeReviewDiffResult(oldContent, newContent, "file", "file");
        String diff = result.diff();

        // Pattern to find hunk headers
        Pattern hunkHeader = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");
        Matcher m = hunkHeader.matcher(diff);

        // First hunk (Small)
        assertTrue(m.find(), "Should find first hunk header");
        int firstHunkOldCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        // For a modification of 1 line, we expect context_before + 1_change + context_after
        // With 1 line of context: 1 + 1 + 1 = 3
        assertEquals(3, firstHunkOldCount, "Small hunk should have 1 line of context (total count 3)");

        // Second hunk (Large)
        assertTrue(m.find(), "Should find second hunk header");
        int secondHunkOldCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        // For a pure insertion of 200 lines, old count is just the context lines
        // We expect 10 lines before and 10 lines after: 10 + 10 = 20
        assertEquals(20, secondHunkOldCount, "Large hunk should have 10 lines of context (total count 20)");

        assertTrue(ContentDiffUtils.parseUnifiedDiff(diff).isPresent(), "Combined diff should be parseable");
    }

    @Test
    void testComputeReviewDiffResultHunkMerging() {
        // Test that close changes are merged and far changes are split
        var lines = IntStream.rangeClosed(1, 100)
                .mapToObj(String::valueOf)
                .collect(Collectors.toCollection(ArrayList::new));
        String oldContent = String.join("\n", lines) + "\n";

        var newLines = new ArrayList<>(lines);
        // Change 1 at line 10
        newLines.set(10, "CH1");
        // Change 2 at line 13 (Gap of 2 lines: 11, 12)
        // Split cost for small change (context 1) is ~1.0 + 2 = 3.0 lines.
        // Since gap (2) < 3.0, these should merge.
        newLines.set(13, "CH2");

        // Change 3 at line 50 (Gap of ~35 lines). Should definitely be separate.
        newLines.set(50, "CH3");

        String newContent = String.join("\n", newLines) + "\n";
        var result = computeReviewDiffResult(oldContent, newContent, "file", "file");
        String diff = result.diff();

        // Count hunk headers
        long hunkCount = diff.lines().filter(l -> l.startsWith("@@")).count();
        assertEquals(2, hunkCount, "Expected 2 hunks (one merged, one separate)");

        // Verify the merged hunk contains both CH1 and CH2
        assertTrue(diff.contains("-11") && diff.contains("+CH1"), "Diff should contain CH1");
        assertTrue(diff.contains("-14") && diff.contains("+CH2"), "Diff should contain CH2 in same hunk");
        assertTrue(diff.contains("-51") && diff.contains("+CH3"), "Diff should contain CH3");
    }
}
