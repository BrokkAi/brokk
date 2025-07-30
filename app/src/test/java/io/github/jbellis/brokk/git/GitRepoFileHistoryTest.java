package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GitRepo file history with rename following functionality.
 */
public class GitRepoFileHistoryTest {
    private Path projectRoot;
    private GitRepo repo;
    private Git git;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = tempDir.resolve("testRepo");
        Files.createDirectories(projectRoot);

        git = Git.init().setDirectory(projectRoot.toFile()).call();

        // Create initial commit with a file
        var initialFile = projectRoot.resolve("original.txt");
        Files.writeString(initialFile, "Initial content");
        git.add().addFilepattern("original.txt").call();
        git.commit()
           .setMessage("Initial commit")
           .setAuthor("Test User", "test@example.com")
           .setSign(false)
           .call();

        repo = new GitRepo(projectRoot);
    }

    @AfterEach
    void tearDown() throws Exception {
        GitTestCleanupUtil.cleanupGitResources(repo);
        if (git != null) {
            git.close();
        }
    }

    @Test
    void testSimpleRename() throws Exception {
        // Modify and rename file
        var originalFile = projectRoot.resolve("original.txt");
        Files.writeString(originalFile, "Modified content");
        git.add().addFilepattern("original.txt").call();
        git.commit()
           .setMessage("Modify file")
           .setAuthor("Test User", "test@example.com")
           .setSign(false)
           .call();

        // Rename the file using git mv to ensure rename detection
        git.rm().addFilepattern("original.txt").call();
        var renamedFile = projectRoot.resolve("renamed.txt");
        Files.writeString(renamedFile, "Modified content");
        git.add().addFilepattern("renamed.txt").call();
        git.commit()
           .setMessage("Rename file")
           .setAuthor("Test User", "test@example.com")
           .setSign(false)
           .call();

        // Test getFileHistoryWithPaths
        var currentFile = new ProjectFile(projectRoot, "renamed.txt");
        var history = repo.getFileHistoryWithPaths(currentFile);

        assertEquals(3, history.size(), "Should have 3 commits in history");

        // Most recent commit should use current name
        assertEquals("renamed.txt", history.get(0).path().toString());
        assertEquals("Rename file", history.get(0).commit().message());

        // Middle commit should use original name (before rename)
        assertEquals("original.txt", history.get(1).path().toString());
        assertEquals("Modify file", history.get(1).commit().message());

        // Initial commit should use original name
        assertEquals("original.txt", history.get(2).path().toString());
        assertEquals("Initial commit", history.get(2).commit().message());
    }

    @Test
    void testMultipleRenames() throws Exception {
        // Create chain of renames: original.txt -> middle.txt -> final.txt
        var originalFile = projectRoot.resolve("original.txt");

        // First rename: original.txt -> middle.txt
        git.rm().addFilepattern("original.txt").call();
        var middleFile = projectRoot.resolve("middle.txt");
        Files.writeString(middleFile, "Initial content");
        git.add().addFilepattern("middle.txt").call();
        git.commit()
           .setMessage("First rename")
           .setAuthor("Test User", "test@example.com")
           .setSign(false)
           .call();

        // Second rename: middle.txt -> final.txt
        git.rm().addFilepattern("middle.txt").call();
        var finalFile = projectRoot.resolve("final.txt");
        Files.writeString(finalFile, "Initial content");
        git.add().addFilepattern("final.txt").call();
        git.commit()
           .setMessage("Second rename")
           .setAuthor("Test User", "test@example.com")
           .setSign(false)
           .call();

        var currentFile = new ProjectFile(projectRoot, "final.txt");
        var history = repo.getFileHistoryWithPaths(currentFile);

        assertEquals(3, history.size(), "Should have 3 commits in history");

        // Most recent: final.txt
        assertEquals("final.txt", history.get(0).path().toString());
        assertEquals("Second rename", history.get(0).commit().message());

        // Middle: middle.txt
        assertEquals("middle.txt", history.get(1).path().toString());
        assertEquals("First rename", history.get(1).commit().message());

        // Oldest: original.txt
        assertEquals("original.txt", history.get(2).path().toString());
        assertEquals("Initial commit", history.get(2).commit().message());
    }

    @Test
    void testRenameWithTwoParents() throws Exception {
        // First modify the original file to create some history
        var originalFile = projectRoot.resolve("original.txt");
        Files.writeString(originalFile, "Modified content");
        git.add().addFilepattern("original.txt").call();
        git.commit()
           .setMessage("Modify original file")
           .setAuthor("Test User", "test@example.com")
           .setSign(false)
           .call();

        // Then rename it
        git.rm().addFilepattern("original.txt").call();
        var renamedFile = projectRoot.resolve("renamed.txt");
        Files.writeString(renamedFile, "Modified content");
        git.add().addFilepattern("renamed.txt").call();
        git.commit()
           .setMessage("Rename file")
           .setAuthor("Test User", "test@example.com")
           .setSign(false)
           .call();

        var currentFile = new ProjectFile(projectRoot, "renamed.txt");
        var history = repo.getFileHistoryWithPaths(currentFile);

        assertEquals(3, history.size(), "Should have 3 commits in history");

        // Verify the rename was detected and paths are correct
        assertEquals("renamed.txt", history.get(0).path().toString());
        assertEquals("Rename file", history.get(0).commit().message());

        assertEquals("original.txt", history.get(1).path().toString());
        assertEquals("Modify original file", history.get(1).commit().message());

        assertEquals("original.txt", history.get(2).path().toString());
        assertEquals("Initial commit", history.get(2).commit().message());
    }

    @Test
    void testNoRenameScenario() throws Exception {
        // Just modify file without renaming
        var file = projectRoot.resolve("original.txt");
        Files.writeString(file, "Modified content");
        git.add().addFilepattern("original.txt").call();
        git.commit()
           .setMessage("Modify file")
           .setAuthor("Test User", "test@example.com")
           .setSign(false)
           .call();

        var currentFile = new ProjectFile(projectRoot, "original.txt");
        var history = repo.getFileHistoryWithPaths(currentFile);

        assertEquals(2, history.size(), "Should have 2 commits in history");

        // All entries should have same path since no rename occurred
        for (var entry : history) {
            assertEquals("original.txt", entry.path().toString(),
                        "All entries should have original path");
        }
    }

    @Test
    void testRootCommitHandling() throws Exception {
        // Test with just the initial commit (no parents)
        var currentFile = new ProjectFile(projectRoot, "original.txt");
        var history = repo.getFileHistoryWithPaths(currentFile);

        assertEquals(1, history.size(), "Should have 1 commit in history");
        assertEquals("original.txt", history.get(0).path().toString());
        assertEquals("Initial commit", history.get(0).commit().message());
    }

    @Test
    void testNonExistentFile() throws Exception {
        var nonExistentFile = new ProjectFile(projectRoot, "nonexistent.txt");
        var history = repo.getFileHistoryWithPaths(nonExistentFile);

        assertTrue(history.isEmpty(), "History should be empty for non-existent file");
    }

    @Test
    void testFileWithSubdirectoryRename() throws Exception {
        // Create subdirectory and move file there
        var subDir = projectRoot.resolve("subdir");
        Files.createDirectories(subDir);

        git.rm().addFilepattern("original.txt").call();
        var movedFile = subDir.resolve("moved.txt");
        Files.writeString(movedFile, "Initial content");
        git.add().addFilepattern("subdir/moved.txt").call();
        git.commit()
           .setMessage("Move to subdirectory")
           .setAuthor("Test User", "test@example.com")
           .setSign(false)
           .call();

        var currentFile = new ProjectFile(projectRoot, "subdir/moved.txt");
        var history = repo.getFileHistoryWithPaths(currentFile);

        assertEquals(2, history.size(), "Should have 2 commits in history");

        // Most recent should be in subdirectory
        assertEquals("subdir/moved.txt", history.get(0).path().toString());
        assertEquals("Move to subdirectory", history.get(0).commit().message());

        // Original should be at root
        assertEquals("original.txt", history.get(1).path().toString());
        assertEquals("Initial commit", history.get(1).commit().message());
    }
}