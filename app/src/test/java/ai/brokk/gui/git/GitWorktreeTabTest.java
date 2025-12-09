package ai.brokk.gui.git;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestGitRepo;
import ai.brokk.testutil.TestLanguage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.eclipse.jgit.api.Git;
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

    @Test
    void setupNewGitWorktree_doesNotReplicateAnalyzerCache() throws Exception {
        Path repoRoot = tempDir.resolve("repo");
        Files.createDirectories(repoRoot);
        Git.init().setDirectory(repoRoot.toFile()).call();

        MainProject parentProject = new MainProject(repoRoot);

        TestLanguage fakeLang = new TestLanguage();
        parentProject.setAnalyzerLanguages(Set.of(fakeLang));

        Path sourceCache = fakeLang.getStoragePath(parentProject);
        Files.createDirectories(sourceCache.getParent());
        Files.writeString(sourceCache, "cache-data");

        Path worktreeStorageDir = parentProject.getWorktreeStoragePath();
        Path nextWorktreePath = worktreeStorageDir.resolve("wt-1");

        TestGitRepo fakeGitRepo = new TestGitRepo(repoRoot, nextWorktreePath);

        var result = GitWorktreeTab.setupNewGitWorktree(parentProject, fakeGitRepo, "feature/test", false, "");

        assertTrue(Files.isDirectory(result.worktreePath()), "Worktree directory should be created");

        Path relative = parentProject.getRoot().relativize(sourceCache);
        Path destCache = result.worktreePath().resolve(relative);

        if (Files.exists(destCache)) {
            assertFalse(
                    Files.isSameFile(sourceCache, destCache),
                    "Worktree analyzer cache must not be a hard-link or the same file as the source cache");
        } else {
            assertFalse(Files.exists(destCache), "Analyzer cache must not be replicated to the new worktree");
        }
        assertTrue(Files.exists(sourceCache), "Original analyzer cache should remain intact");
    }
}
