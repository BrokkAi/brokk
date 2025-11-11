package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the LFS detection helper in GitRepoWorktrees.
 *
 * The helper method under test is exposed for tests via
 *   public/static (package-visible) boolean isLfsMissingForTest(String output)
 *
 * This test no longer uses reflection; it calls the helper directly.
 */
public class GitRepoWorktreesLfsDetectionTest {

    private static boolean invokeIsLfsMissing(String output) {
        return GitRepoWorktrees.isLfsMissingForTest(output);
    }

    @Test
    void testIsLfsMissing_PositiveExamples() {
        // Example 1: POSIX/git common message
        String msg1 = "git: 'lfs' is not a git command. See 'git --help'.";
        assertTrue(invokeIsLfsMissing(msg1), "Should detect missing git-lfs from 'git: \"lfs\" is not a git command'");

        // Example 2: Windows CLI style
        String msg2 =
                "'git-lfs' is not recognized as an internal or external command,\noperable program or batch file.";
        assertTrue(
                invokeIsLfsMissing(msg2),
                "Should detect missing git-lfs from Windows-style 'is not recognized' message");

        // Example 3: filter-process / smudge failures
        String msg3 = "error: external filter 'git-lfs filter-process' failed\nfatal: smudge filter lfs failed";
        assertTrue(
                invokeIsLfsMissing(msg3),
                "Should detect missing git-lfs from 'external filter' / 'smudge filter' failure");

        // Example 4: explicit 'not found' mention
        String msg4 = "sh: git-lfs: command not found\nfilter-process failed";
        assertTrue(
                invokeIsLfsMissing(msg4),
                "Should detect missing git-lfs when output contains 'git-lfs: command not found'");

        // Example 5: filter-process generic failure mentioning lfs
        String msg5 =
                "error: external filter 'git-lfs filter-process' failed with exit code 127\nfilter-process.*failed";
        assertTrue(
                invokeIsLfsMissing(msg5), "Should detect missing git-lfs on various filter-process failure messages");
    }

    @Test
    void testIsLfsMissing_NegativeExamples() {
        // Unrelated git error: should NOT be considered LFS-missing
        String unrelated1 = "fatal: refusing to merge unrelated histories";
        assertFalse(invokeIsLfsMissing(unrelated1), "Unrelated fatal message should not be detected as LFS missing");

        // Another unrelated git error
        String unrelated2 = "error: pathspec 'nonexistent-file' did not match any file(s) known to git";
        assertFalse(invokeIsLfsMissing(unrelated2), "Pathspec error should not be detected as LFS missing");

        // Generic 'command not found' not referencing git-lfs explicitly should not match
        String unrelated3 = "bash: some-other-tool: command not found";
        assertFalse(
                invokeIsLfsMissing(unrelated3),
                "A generic 'command not found' message without 'git-lfs' should not be detected as LFS missing");
    }
}
