package io.github.jbellis.brokk.prompts;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.difftool.performance.PerformanceConstants;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommitPromptsTest {

    private static String invokePreprocess(String diffTxt) {
        try {
            Method m = CommitPrompts.class.getDeclaredMethod("preprocessUnifiedDiff", String.class);
            m.setAccessible(true);
            return (String) m.invoke(CommitPrompts.instance, diffTxt);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
}
