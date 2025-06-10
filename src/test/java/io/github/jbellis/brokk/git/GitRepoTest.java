package io.github.jbellis.brokk.git;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class GitRepoTest {
    private Path projectRoot;
    private GitRepo repo;
    
    @TempDir
    Path tempDir;
    
    @BeforeEach
    void setUp() throws Exception {
        projectRoot = tempDir.resolve("testRepo");
        Files.createDirectories(projectRoot);
        
        try (Git git = Git.init().setDirectory(projectRoot.toFile()).call()) {
            // Create an initial commit
            Path readme = projectRoot.resolve("README.md");
            Files.writeString(readme, "Initial commit file.");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("Initial commit").setAuthor("Test User", "test@example.com").call();
        }
        
        repo = new GitRepo(projectRoot);
    }
    
    @AfterEach
    void tearDown() throws Exception {
        if (repo != null) {
            repo.close();
        }
    }
    
    @Test
    void testRepositoryInitialization() throws Exception {
        Assertions.assertNotNull(repo);
        Assertions.assertTrue(Files.exists(projectRoot.resolve(".git")));
        Assertions.assertEquals(projectRoot.toAbsolutePath().normalize(), repo.getGitTopLevel());
    }
    
    @Test
    void testSupportsWorktrees_gitAvailable() {
        // This test assumes that the 'git' command-line executable is available 
        // in the environment where the tests are run.
        Assertions.assertTrue(repo.supportsWorktrees(), 
            "supportsWorktrees() should return true if git executable is available. " +
            "If this test fails, ensure 'git' is in the PATH or the method logic is broken.");
    }
    
    @Test
    void testListWorktrees_afterInitialCommit() throws Exception {
        if (!repo.supportsWorktrees()) {
            System.out.println("Skipping testListWorktrees_afterInitialCommit as worktrees not supported.");
            return;
        }
        
        List<IGitRepo.WorktreeInfo> worktrees = repo.listWorktrees();
        Assertions.assertEquals(1, worktrees.size());
        
        var worktreeInfo = worktrees.get(0);
        Assertions.assertEquals(projectRoot.toRealPath(), worktreeInfo.path());
        
        String expectedBranch = repo.getCurrentBranch();
        Assertions.assertEquals(expectedBranch, worktreeInfo.branch());
        
        String expectedCommitId = repo.getCurrentCommitId();
        Assertions.assertNotNull(expectedCommitId);
        Assertions.assertFalse(expectedCommitId.isEmpty());
        Assertions.assertEquals(expectedCommitId, worktreeInfo.commitId());
    }
    
    @Test
    void testAddAndListWorktree() throws Exception {
        if (!repo.supportsWorktrees()) {
            System.out.println("Skipping testAddAndListWorktree as worktrees not supported.");
            return;
        }

        String newBranchName = "feature-branch";
        Path newWorktreePath = tempDir.resolve("feature-worktree");

        // Ensure the parent directory for the new worktree exists if git worktree add doesn't create it.
        // Files.createDirectories(newWorktreePath.getParent()); // git worktree add creates the directory itself.

        repo.addWorktree(newBranchName, newWorktreePath);

        List<IGitRepo.WorktreeInfo> worktrees = repo.listWorktrees();
        Assertions.assertEquals(2, worktrees.size(), "Should be two worktrees after adding one.");

        Path newWorktreePathReal = newWorktreePath.toRealPath();
        Optional<IGitRepo.WorktreeInfo> addedWorktreeOpt = worktrees.stream()
                .filter(wt -> wt.path().equals(newWorktreePathReal))
                .findFirst();

        Assertions.assertTrue(addedWorktreeOpt.isPresent(), "Newly added worktree not found in list.");
        var addedWorktree = addedWorktreeOpt.get();

        Assertions.assertEquals(newBranchName, addedWorktree.branch(), "Branch name of the new worktree is incorrect.");

        // The new branch is created from HEAD, so commit ID should match current repo's HEAD
        String expectedCommitId = repo.getCurrentCommitId();
        Assertions.assertEquals(expectedCommitId, addedWorktree.commitId(), "Commit ID of the new worktree is incorrect.");
    }
    
    @Test
    void testRemoveWorktree() throws Exception {
        if (!repo.supportsWorktrees()) {
            System.out.println("Skipping testRemoveWorktree as worktrees not supported.");
            return;
        }

        String branchToWorktree = "worktree-branch-to-remove";
        Path worktreePathToRemove = tempDir.resolve("worktree-to-remove");

        // Add a worktree
        repo.addWorktree(branchToWorktree, worktreePathToRemove);

        // Verify it was added
        List<IGitRepo.WorktreeInfo> worktreesAfterAdd = repo.listWorktrees();
        Assertions.assertEquals(2, worktreesAfterAdd.size(), "Should be two worktrees after adding one for removal.");
        Path worktreePathToRemoveReal = worktreePathToRemove.toRealPath();
        Assertions.assertTrue(
            worktreesAfterAdd.stream().anyMatch(wt -> wt.path().equals(worktreePathToRemoveReal)),
            "Newly added worktree (for removal) not found in list."
        );
        Assertions.assertTrue(Files.exists(worktreePathToRemove), "Worktree directory should exist after add.");
        Assertions.assertTrue(Files.exists(worktreePathToRemove.resolve(".git")), "Worktree .git file should exist after add.");

        // Remove the worktree
        repo.removeWorktree(worktreePathToRemove);

        // Verify it was removed
        List<IGitRepo.WorktreeInfo> worktreesAfterRemove = repo.listWorktrees();
        Assertions.assertEquals(1, worktreesAfterRemove.size(), "Should be one worktree after removing one.");
        Assertions.assertFalse(
            worktreesAfterRemove.stream().anyMatch(wt -> wt.path().equals(worktreePathToRemoveReal)),
            "Removed worktree should not be found in list."
        );
        // The `git worktree remove` command should remove the directory if it's clean.
        // If the worktree had a .git file (typical for linked worktrees), that specific file is removed,
        // and then the directory structure if it becomes empty or if git decides to clean it fully.
        // For a robust check, we primarily care that it's not listed by git and its .git marker is gone.
        // Depending on the git version and exact state, the top-level directory might linger if it had other files.
        // However, for a freshly added worktree, it should be removed.
        Assertions.assertFalse(Files.exists(worktreePathToRemove), "Worktree directory should not exist after clean removal.");
    }
}
