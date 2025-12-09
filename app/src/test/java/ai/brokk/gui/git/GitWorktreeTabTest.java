package ai.brokk.gui.git;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for GitWorktreeTab helper methods.
 */
public class GitWorktreeTabTest {

    @TempDir
    Path tempDir;

    /**
     * Test that resolveWorktreeOpenPath returns the worktree root when relativeSubdir is null.
     */
    @Test
    void testResolveWorktreeOpenPath_NullSubdir() {
        Path worktreePath = tempDir.resolve("worktree");

        Path result = GitWorktreeTab.resolveWorktreeOpenPath(worktreePath, null);

        assertEquals(worktreePath, result, "Should return worktree root when relativeSubdir is null");
    }

    /**
     * Test that resolveWorktreeOpenPath returns the subdirectory path when it exists.
     */
    @Test
    void testResolveWorktreeOpenPath_SubdirExists() throws IOException {
        Path worktreePath = tempDir.resolve("worktree");
        Path subdir = Path.of("subproject");

        // Create the subdirectory
        Files.createDirectories(worktreePath.resolve(subdir));

        Path result = GitWorktreeTab.resolveWorktreeOpenPath(worktreePath, subdir);

        assertEquals(worktreePath.resolve(subdir), result, "Should return subdirectory path when it exists");
    }

    /**
     * Test that resolveWorktreeOpenPath falls back to worktree root when subdirectory doesn't exist.
     * Also verifies that a warning is logged (though we don't explicitly check logs in this simple test).
     */
    @Test
    void testResolveWorktreeOpenPath_SubdirDoesNotExist() {
        Path worktreePath = tempDir.resolve("worktree");
        Path nonExistentSubdir = Path.of("nonexistent-subdir");

        // Subdirectory does not exist
        assertFalse(Files.exists(worktreePath.resolve(nonExistentSubdir)));

        Path result = GitWorktreeTab.resolveWorktreeOpenPath(worktreePath, nonExistentSubdir);

        assertEquals(worktreePath, result, "Should fall back to worktree root when subdirectory doesn't exist");
    }
}
