package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.difftool.performance.PerformanceConstants;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommitPromptsTest {

    private static String invokePreprocess(String diffTxt) {
        return CommitPrompts.instance.preprocessUnifiedDiff(
                diffTxt, CommitPrompts.FILE_LIMIT, CommitPrompts.LINES_PER_FILE);
    }

    private static String genFileDiff(String filename, List<int[]> hunksOldNewCounts) {
        // Build minimal valid unified diff for a file
        var lines = new ArrayList<String>();
        String aPath = "a/" + filename;
        String bPath = "b/" + filename;
        lines.add("diff --git " + aPath + " " + bPath);
        lines.add("--- " + aPath);
        lines.add("+++ " + bPath);

        int oldStart = 1;
        int newStart = 1;
        for (int[] counts : hunksOldNewCounts) {
            int oldCount = counts[0];
            int newCount = counts[1];
            lines.add(String.format("@@ -%d,%d +%d,%d @@", oldStart, oldCount, newStart, newCount));
            for (int i = 0; i < oldCount; i++) lines.add("-old" + (i + 1));
            for (int i = 0; i < newCount; i++) lines.add("+new" + (i + 1));
            oldStart += Math.max(oldCount, 1);
            newStart += Math.max(newCount, 1);
        }
        return String.join("\n", lines) + "\n";
    }

    private static String createEmptyFileDiff(String filename) {
        return String.format(
                """
            diff --git a/%s b/%s
            new file mode 100644
            index 0000000..e69de29
            --- /dev/null
            +++ b/%s
            """,
                filename, filename, filename);
    }

    private static String createNewFileWithContentDiff(String filename, String... contentLines) {
        var content = String.join("\n+", contentLines);
        return String.format(
                """
            diff --git a/%s b/%s
            new file mode 100644
            index 0000000..abc123
            --- /dev/null
            +++ b/%s
            @@ -0,0 +1,%d @@
            +%s
            """,
                filename, filename, filename, contentLines.length, content);
    }

    private static String createMixedDiff(String emptyFile, String contentFile, String... contentLines) {
        return createEmptyFileDiff(emptyFile) + createNewFileWithContentDiff(contentFile, contentLines);
    }

    private static String createTwoStageDiff(String filename, String... contentLines) {
        var content = String.join("\n+", contentLines);
        return String.format(
                """
            diff --git a/%s b/%s
            new file mode 100644
            index 0000000..e69de29
            --- /dev/null
            +++ b/%s
            diff --git a/%s b/%s
            index e69de29..abc123 100644
            --- a/%s
            +++ b/%s
            @@ -0,0 +1,%d @@
            +%s
            """,
                filename, filename, filename, filename, filename, filename, filename, contentLines.length, content);
    }

    @Test
    void singleSmallHunk_isIncluded() {
        String diff = genFileDiff("hello.txt", List.of(new int[] {2, 3}));
        String trimmed = invokePreprocess(diff);

        assertFalse(trimmed.isBlank(), "Expected non-empty trimmed diff");
        assertTrue(trimmed.contains("diff --git a/hello.txt b/hello.txt"));
        assertTrue(trimmed.contains("--- a/hello.txt"));
        assertTrue(trimmed.contains("+++ b/hello.txt"));
        assertTrue(trimmed.contains("@@ -1,2 +1,3 @@"));
        assertTrue(trimmed.contains("-old1"));
        assertTrue(trimmed.contains("-old2"));
        assertTrue(trimmed.contains("+new1"));
        assertTrue(trimmed.contains("+new2"));
        assertTrue(trimmed.contains("+new3"));
    }

    @Test
    void overlongLine_isExcluded() {
        int max = (int) PerformanceConstants.MAX_DIFF_LINE_LENGTH_BYTES;
        // A single inserted line exceeding MAX bytes once prefixed with '+'
        int len = max + 1;
        String big = "a".repeat(len);
        // Ensure with '+' prefix this line exceeds the byte budget
        assertTrue(("+" + big).getBytes(StandardCharsets.UTF_8).length > max);

        var lines = new ArrayList<String>();
        lines.add("diff --git a/big.txt b/big.txt");
        lines.add("--- a/big.txt");
        lines.add("+++ b/big.txt");
        lines.add("@@ -1,0 +1,1 @@");
        lines.add("+" + big);
        String diff = String.join("\n", lines) + "\n";

        String trimmed = invokePreprocess(diff);
        assertTrue(trimmed.isBlank(), "Expected trimmed diff to be empty due to overlong line exclusion");
    }

    @Test
    void linesPerFileLimit_isEnforced() {
        // Create two hunks in the same file:
        // hunk1 size = 1 + 34 + 35 = 70
        // hunk2 size = 1 + 20 + 19 = 40
        // Policy: pick largest first (70), next (40) would exceed 100 -> stop. Only 1 hunk included.
        String diff = genFileDiff("limit.txt", List.of(new int[] {34, 35}, new int[] {20, 19}));
        String trimmed = invokePreprocess(diff);

        assertFalse(trimmed.isBlank());
        long hunkHeaders = trimmed.lines().filter(l -> l.startsWith("@@ ")).count();
        assertEquals(1, hunkHeaders, "Expected only the largest hunk to be included under the per-file limit");
        assertTrue(trimmed.contains("@@ -1,34 +1,35 @@"));
    }

    @Test
    void largestHunk_isIncludedEvenIfExceedsLimit() {
        // One very large hunk: 1 + 60 + 60 = 121 (>100), must still be included
        String diff = genFileDiff("big-hunk.txt", List.of(new int[] {60, 60}));
        String trimmed = invokePreprocess(diff);

        assertFalse(trimmed.isBlank());
        long hunkHeaders = trimmed.lines().filter(l -> l.startsWith("@@ ")).count();
        assertEquals(1, hunkHeaders, "Expected the single large hunk to be included even if it exceeds the limit");
        assertTrue(trimmed.contains("@@ -1,60 +1,60 @@"));
    }

    @Test
    void filesAreOrdered_byHunkCountThenTotalLines() {
        // fileB has 2 hunks, fileA has 1 hunk. Both total lines similar but hunk count should dominate.
        String fileA = genFileDiff("a.txt", List.of(new int[] {5, 5}));
        String fileB = genFileDiff("b.txt", List.of(new int[] {3, 3}, new int[] {2, 2}));
        String diff = fileA + fileB;

        String trimmed = invokePreprocess(diff);

        int idxA = trimmed.indexOf("diff --git a/a.txt b/a.txt");
        int idxB = trimmed.indexOf("diff --git a/b.txt b/b.txt");
        assertTrue(idxB >= 0 && idxA >= 0, "Both file headers should be present");
        assertTrue(idxB < idxA, "Files should be ordered by hunk count (b.txt before a.txt)");
    }

    @Test
    void malformedDiff_isHandledGracefully() {
        // Test case 1: Empty/whitespace diff
        String emptyDiff = "";
        String whitespaceDiff = "   \n  \n\t\n";

        String result1 = invokePreprocess(emptyDiff);
        assertTrue(result1.isBlank(), "Empty diff should return blank result");

        String result2 = invokePreprocess(whitespaceDiff);
        assertTrue(result2.isBlank(), "Whitespace-only diff should return blank result");

        // Test case 2: Diff with no markers (should be rejected by pre-validation)
        String noMarkersDiff =
                """
            Some random text
            that doesn't look like a diff
            at all
            """;
        String result3 = invokePreprocess(noMarkersDiff);
        assertTrue(result3.isBlank(), "Non-diff text should return blank result");

        // Test case 3: Hunk without file header (might cause parse errors)
        String orphanHunk =
                """
            @@ -1,2 +1,3 @@
            -old line
            +new line
            +another line
            """;
        String result4 = invokePreprocess(orphanHunk);
        assertTrue(result4.isBlank(), "Orphan hunk should return blank result gracefully");

        // Test case 4: Malformed diff that might trigger parser exception
        String malformedDiff =
                """
            diff --git a/test.txt b/test.txt
            index abc123..def456 100644
            this line doesn't belong here
            @@ invalid hunk header
            +some content
            """;
        String result5 = invokePreprocess(malformedDiff);
        // Should handle gracefully without throwing exceptions
        // Result might be empty or might parse partially - both are acceptable
        assertNotNull(result5, "Malformed diff should not cause null result");
    }

    @Test
    void emptyFileCreation_isFilteredOut() {
        // Test case: Empty file creation that would cause UnifiedDiffReader to fail
        String emptyFileCreation = createEmptyFileDiff("app/src/main/java/Empty.java");

        String result1 = invokePreprocess(emptyFileCreation);
        assertTrue(result1.isBlank(), "Empty file creation should be filtered out");

        // Test case: Mixed diff with empty file + real changes
        String mixedDiff = createMixedDiff("empty.txt", "real.txt", "line 1", "line 2");

        String result2 = invokePreprocess(mixedDiff);
        assertFalse(result2.isBlank(), "Mixed diff should not be completely empty");
        assertFalse(result2.contains("empty.txt"), "Empty file should be filtered out");
        assertTrue(result2.contains("real.txt"), "Real file should remain");
        assertTrue(result2.contains("@@"), "Should contain hunk header");
        assertTrue(result2.contains("+line 1"), "Should contain added line 1");
        assertTrue(result2.contains("+line 2"), "Should contain added line 2");
    }

    @Test
    void newFileWithContent_isNotFiltered() {
        // Test case: New file that starts empty but has content added (like AppQuitHandler.java scenario)
        // This represents the real-world case where a file is created empty and then content is added
        String newFileWithContentDiff =
                createTwoStageDiff("app/src/main/java/NewClass.java", "public class NewClass {", "    // content", "}");

        String result = invokePreprocess(newFileWithContentDiff);
        assertFalse(result.isBlank(), "New file with content should not be completely filtered out");
        assertTrue(result.contains("NewClass"), "File name should remain in result");
        assertTrue(result.contains("@@"), "Should contain hunk header");
        assertTrue(result.contains("+public class NewClass"), "Actual content should be preserved");
        assertTrue(result.contains("+    // content"), "Actual content should be preserved");
        assertTrue(result.contains("+}"), "Actual content should be preserved");

        // The empty file creation section should be filtered out, but content section should remain
        // We should only see the content diff, not the initial empty creation
    }
}
