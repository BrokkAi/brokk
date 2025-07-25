package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextHistory;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.testutil.NoOpConsoleIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GitProjectStateRestorerTest {

    @TempDir
    Path tempDir;

    private MainProject project;
    private GitRepo repo;

    @BeforeEach
    public void setUp() throws Exception {
        GitRepo.initRepo(tempDir);
        // Add an initial commit so hasGitRepo will be true
        try (var r = new GitRepo(tempDir)) {
            var dummyFile = tempDir.resolve("dummy.txt");
            Files.writeString(dummyFile, "dummy");
            r.add(List.of(new ProjectFile(tempDir, Path.of("dummy.txt"))));
            r.getGit().commit().setMessage("Initial commit").call();
        }

        project = new MainProject(tempDir);
        repo = (GitRepo) project.getRepo();
    }

    @AfterEach
    public void tearDown() {
        project.close();
    }

    @Test
    public void testRestore() throws Exception {
        // Setup: create a git repo with a couple of commits
        var file = tempDir.resolve("test.txt");
        var projectFile = new ProjectFile(tempDir, Path.of("test.txt"));

        Files.writeString(file, "version 1");
        repo.add(List.of(projectFile));
        var commit1 = repo.getGit().commit().setMessage("Initial commit").call();
        var commit1Id = commit1.getId().getName();

        Files.writeString(file, "version 2");
        repo.add(List.of(projectFile));
        var commit2 = repo.getGit().commit().setMessage("Second commit").call();
        var commit2Id = commit2.getId().getName();

        // now we are at commit 2. create a state for commit 1
        var stateAtCommit1 = new ContextHistory.GitState(commit1Id, null);

        // create a state for commit 1 + a diff
        repo.checkout(commit1Id);
        Files.writeString(file, "version 1 with uncommitted changes");
        var diff = repo.diff();
        var stateAtCommit1WithDiff = new ContextHistory.GitState(commit1Id, diff);

        // Clean the working directory before switching branches
        repo.checkoutFilesFromCommit(commit1Id, List.of(projectFile));

        // Go back to commit2 to have a different starting point
        repo.checkout(commit2Id);
        assertEquals("version 2", Files.readString(file));

        var restorer = new GitProjectStateRestorer(project, new NoOpConsoleIO());

        // Test restoring to a commit
        restorer.restore(stateAtCommit1);
        assertEquals(commit1Id, repo.getCurrentCommitId());
        assertEquals("version 1", Files.readString(file));
        assertTrue(repo.diff().isEmpty(), "Diff should be empty after restoring to a clean commit");

        // Test restoring to a commit with a diff
        restorer.restore(stateAtCommit1WithDiff);
        assertEquals(commit1Id, repo.getCurrentCommitId());
        assertEquals("version 1 with uncommitted changes", Files.readString(file));
        assertFalse(repo.diff().isEmpty(), "Diff should not be empty after restoring with a diff");
        assertEquals(diff, repo.diff());
    }
}
