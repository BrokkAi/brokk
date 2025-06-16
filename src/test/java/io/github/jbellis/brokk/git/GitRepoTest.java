package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.gui.GitWorktreeTab;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


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
        repo.removeWorktree(worktreePathToRemove, false);

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
        assertFalse(Files.exists(worktreePathToRemove), "Worktree directory should not exist after clean removal.");
    }

    @Test
    void testRemoveWorktree_ForceRequiredAndUsed() throws Exception {
        if (!repo.supportsWorktrees()) {
            System.out.println("Skipping testRemoveWorktree_ForceRequiredAndUsed as worktrees not supported.");
            return;
        }

        String branchName = "dirty-worktree-branch";
        Path worktreePath = tempDir.resolve("dirty-worktree");

        // 1. Add a worktree
        repo.addWorktree(branchName, worktreePath);
        assertTrue(Files.exists(worktreePath), "Worktree directory should exist after add.");

        // 2. Make the worktree "dirty" by adding an uncommitted file
        // (Simpler than full GitRepo instance for the worktree: just create a file)
        Path dirtyFile = worktreePath.resolve("uncommitted_change.txt");
        Files.writeString(dirtyFile, "This file makes the worktree dirty.");
        assertTrue(Files.exists(dirtyFile), "Dirty file should exist in worktree.");

        // 3. Attempt to remove without force, expect WorktreeNeedsForceException
        GitRepo.WorktreeNeedsForceException e = Assertions.assertThrows(
            GitRepo.WorktreeNeedsForceException.class,
            () -> repo.removeWorktree(worktreePath, false),
            "Expected WorktreeNeedsForceException when removing dirty worktree without force."
        );
        // Check that the exception message indicates that force is required.
        // The message is now more specific like "Worktree at ... requires force for removal: ..."
        assertTrue(e.getMessage().toLowerCase().contains("requires force for removal") &&
                   (e.getMessage().contains("use --force") || e.getMessage().contains("not empty") || e.getMessage().contains("dirty")),
                   "Exception message should indicate force is required and provide Git's reason. Actual: " + e.getMessage());
        assertTrue(Files.exists(worktreePath), "Worktree directory should still exist after failed non-force removal.");

        // Get the real path *before* it's removed by the force operation.
        Path worktreeRealPath = worktreePath.toRealPath();

        // 4. Attempt to remove with force=true, expect success
        Assertions.assertDoesNotThrow(
            () -> repo.removeWorktree(worktreePath, true),
            "Expected no exception when using removeWorktree with force=true on a dirty worktree."
        );

        // 5. Verify worktree is removed
        List<IGitRepo.WorktreeInfo> worktreesAfterForceRemove = repo.listWorktrees();
        Assertions.assertFalse(
            worktreesAfterForceRemove.stream().anyMatch(wt -> wt.path().equals(worktreeRealPath)),
            "Force-removed worktree should not be in the list."
        );
        assertFalse(Files.exists(worktreePath), "Worktree directory should not exist after successful force removal.");
    }

    @Test
    void testRemoveWorktree_ForceRequired_Locked() throws Exception {
        if (!repo.supportsWorktrees()) {
            System.out.println("Skipping testRemoveWorktree_ForceRequired_Locked as worktrees not supported.");
            return;
        }

        String branchName = "locked-worktree-branch";
        Path worktreePath = tempDir.resolve("locked-worktree");

        // 1. Add a worktree
        repo.addWorktree(branchName, worktreePath);
        assertTrue(Files.exists(worktreePath), "Worktree directory should exist after add.");
        Path worktreeRealPath = worktreePath.toRealPath(); // Get real path before potential removal

        // 2. Lock the worktree using the git command line
        try {
            io.github.jbellis.brokk.util.Environment.instance.runShellCommand(
                String.format("git worktree lock %s", worktreePath.toAbsolutePath().normalize()),
                repo.getGitTopLevel(),
                output -> {}
            );
        } catch (io.github.jbellis.brokk.util.Environment.SubprocessException | InterruptedException e) {
            fail("Failed to lock worktree: " + e.getMessage());
        }

        // 3. Attempt to remove without force, expect WorktreeNeedsForceException
        GitRepo.WorktreeNeedsForceException e = Assertions.assertThrows(
            GitRepo.WorktreeNeedsForceException.class,
            () -> repo.removeWorktree(worktreePath, false),
            "Expected WorktreeNeedsForceException when removing a locked worktree without force."
        );
        // Check that the exception message indicates that force is required due to lock.
        // Git's output for locked worktrees typically includes "locked working tree" and "use 'remove -f -f'" or similar.
        String lowerCaseMessage = e.getMessage().toLowerCase();
        assertTrue(lowerCaseMessage.contains("requires force for removal") &&
                   (lowerCaseMessage.contains("locked working tree") || lowerCaseMessage.contains("is locked")) && // Check for "locked working tree" or "is locked"
                   (lowerCaseMessage.contains("use --force") || lowerCaseMessage.contains("use 'remove -f -f'")), // Check for "--force" or "-f -f"
                   "Exception message should indicate force is required for a locked worktree. Actual: " + e.getMessage());
        assertTrue(Files.exists(worktreePath), "Worktree directory should still exist after failed non-force removal of locked worktree.");

        // 4. Attempt to remove with force=true, expect success
        Assertions.assertDoesNotThrow(
            () -> repo.removeWorktree(worktreePath, true),
            "Expected no exception when using removeWorktree with force=true on a locked worktree."
        );

        // 5. Verify worktree is removed
        List<IGitRepo.WorktreeInfo> worktreesAfterForceRemove = repo.listWorktrees();
        Assertions.assertFalse(
            worktreesAfterForceRemove.stream().anyMatch(wt -> wt.path().equals(worktreeRealPath)),
            "Force-removed locked worktree should not be in the list."
        );
        assertFalse(Files.exists(worktreePath), "Worktree directory should not exist after successful force removal of locked worktree.");
    }

    // Helper method to create a commit
    private void createCommit(String fileName, String content, String message) throws Exception {
        Path filePath = projectRoot.resolve(fileName);
        Files.writeString(filePath, content);
        repo.getGit().add().addFilepattern(fileName).call();
        repo.getGit().commit().setAuthor("Test User", "test@example.com").setMessage(message).call();
    }

    @Test
    void testGetCommitMessagesBetween_sameBranch() throws Exception {
        List<String> messages = repo.getCommitMessagesBetween("main", "main");
        assertTrue(messages.isEmpty(), "Should be no messages between a branch and itself.");
    }

    @Test
    void testGetCommitMessagesBetween_newCommitsOnFeature() throws Exception {
        String mainBranch = repo.getCurrentBranch(); // Should be "main" or "master" depending on Git version default
        repo.getGit().branchCreate().setName("feature").call();
        repo.getGit().checkout().setName("feature").call();

        createCommit("feature_file1.txt", "content1", "Commit 1 on feature");
        createCommit("feature_file2.txt", "content2", "Commit 2 on feature");

        List<String> messages = repo.getCommitMessagesBetween("feature", mainBranch);
        List<String> expectedMessages = List.of("Commit 1 on feature", "Commit 2 on feature");
        assertEquals(expectedMessages, messages, "Messages should be those on feature branch, in chronological order.");
    }

    @Test
    void testGetCommitMessagesBetween_divergentBranches() throws Exception {
        // Initial state: main branch with "Initial commit"

        // M1 on main
        createCommit("main_file1.txt", "m1 content", "M1 on main");
        String mainBranch = repo.getCurrentBranch();

        // Create branch-A from main (at M1)
        repo.getGit().branchCreate().setName("branch-A").setStartPoint(mainBranch).call();
        repo.getGit().checkout().setName("branch-A").call();
        createCommit("a_file1.txt", "a1 content", "A1 on branch-A");

        // Create branch-B from main (at M1)
        repo.getGit().checkout().setName(mainBranch).call(); // Back to main
        repo.getGit().branchCreate().setName("branch-B").setStartPoint(mainBranch).call();
        repo.getGit().checkout().setName("branch-B").call();
        createCommit("b_file1.txt", "b1 content", "B1 on branch-B");

        // Add another commit to branch-A
        repo.getGit().checkout().setName("branch-A").call();
        createCommit("a_file2.txt", "a2 content", "A2 on branch-A");

        // Commits on branch-A: Initial, M1, A1, A2
        // Commits on branch-B: Initial, M1, B1
        // Merge base is M1. Commits on branch-A after M1 are A1, A2.
        List<String> messages = repo.getCommitMessagesBetween("branch-A", "branch-B");
        List<String> expectedMessages = List.of("A1 on branch-A", "A2 on branch-A");
        assertEquals(expectedMessages, messages, "Should return commits unique to branch-A after divergence, in order.");
    }

    @Test
    void testSanitizeBranchName_withWhitespaceAndIllegalChars() throws Exception {
        String proposedName = "  feature/new awesome branch!  ";
        String sanitized = repo.sanitizeBranchName(proposedName);
        assertEquals("feature/new-awesome-branch", sanitized);
    }

    @Test
    void testSanitizeBranchName_collision() throws Exception {
        String existingBranch = "existing-branch";
        repo.getGit().branchCreate().setName(existingBranch).call();

        String proposedName = "existing branch"; // sanitizes to "existing-branch"
        String sanitized = repo.sanitizeBranchName(proposedName);
        assertEquals("existing-branch-2", sanitized);
    }

    @Test
    void testSanitizeBranchName_collisionMultiple() throws Exception {
        repo.getGit().branchCreate().setName("another-branch").call();
        repo.getGit().branchCreate().setName("another-branch-2").call();

        String proposedName = "another branch"; // sanitizes to "another-branch"
        String sanitized = repo.sanitizeBranchName(proposedName);
        assertEquals("another-branch-3", sanitized);
    }
    
    @Test
    void testSanitizeBranchName_emptyAfterSanitization() throws Exception {
        String proposedName = "!@#$%^";
        String sanitized = repo.sanitizeBranchName(proposedName);
        assertEquals("branch", sanitized, "Should default to 'branch' if sanitization results in empty string and 'branch' is unique.");

        // Test when "branch" already exists
        repo.getGit().branchCreate().setName("branch").call();
        String sanitized2 = repo.sanitizeBranchName("!@#$%^");
        assertEquals("branch-2", sanitized2, "Should default to 'branch-2' if 'branch' exists.");
    }

    @Test
    void testSanitizeBranchName_cleanAndUnique() throws Exception {
        String proposedName = "clean-unique-branch";
        String sanitized = repo.sanitizeBranchName(proposedName);
        assertEquals("clean-unique-branch", sanitized);
    }

    @Test
    void testGetBranchesInWorktrees() throws Exception {
        assumeTrue(repo.supportsWorktrees(), "Worktrees not supported, skipping testGetBranchesInWorktrees");

        String mainBranchInitial = repo.getCurrentBranch();

        String wtBranch1 = "feature-wt1";
        Path wtPath1 = tempDir.resolve("worktree1");
        repo.addWorktree(wtBranch1, wtPath1);

        String wtBranch2 = "feature-wt2";
        Path wtPath2 = tempDir.resolve("worktree2");
        repo.addWorktree(wtBranch2, wtPath2);

        java.util.Set<String> branchesInWorktrees = repo.getBranchesInWorktrees();

        assertTrue(branchesInWorktrees.contains(mainBranchInitial), "Main project's branch should be in worktree branches list");
        assertTrue(branchesInWorktrees.contains(wtBranch1), "Branch from worktree1 should be listed");
        assertTrue(branchesInWorktrees.contains(wtBranch2), "Branch from worktree2 should be listed");
        // Main project is also a worktree in the context of `git worktree list` output
        assertEquals(3, branchesInWorktrees.size(), "Should be exactly three branches listed (main + 2 new)");
    }

    @Test
    void testIsWorktree() throws Exception {
        assumeTrue(repo.supportsWorktrees(), "Worktrees not supported, skipping testIsWorktree");

        // Main repository (repo instance) should not be considered a linked worktree by this definition
        assertFalse(repo.isWorktree(), "Main repository instance should return false for isWorktree()");

        String wtBranch = "feature-isworktree";
        Path wtPath = tempDir.resolve("isworktree-test");
        repo.addWorktree(wtBranch, wtPath);

        GitRepo worktreeRepo = null;
        try {
            worktreeRepo = new GitRepo(wtPath);
            assertTrue(worktreeRepo.isWorktree(), "GitRepo instance for a worktree path should return true for isWorktree()");
        } finally {
            if (worktreeRepo != null) {
                worktreeRepo.close();
            }
        }
    }

    @Test
    void testGetNextWorktreePath() throws Exception {
        Path worktreeStorageDir = tempDir.resolve("worktree_storage");
        Files.createDirectories(worktreeStorageDir);

        // Simulate existing worktrees
        Files.createDirectories(worktreeStorageDir.resolve("wt1"));
        Files.createDirectories(worktreeStorageDir.resolve("wt2"));
        Files.createDirectories(worktreeStorageDir.resolve("wt4"));

        // First call, should find wt3
        Path nextPath1 = repo.getNextWorktreePath(worktreeStorageDir);
        assertEquals("wt3", nextPath1.getFileName().toString(), "Expected next worktree path to be wt3");

        // Second call without creating wt3, should still be wt3
        Path nextPath2 = repo.getNextWorktreePath(worktreeStorageDir);
        assertEquals("wt3", nextPath2.getFileName().toString(), "Expected next worktree path to still be wt3 as it wasn't created");

        // Manually create wt3
        Files.createDirectories(worktreeStorageDir.resolve("wt3"));

        // Third call, should now find wt5 (as wt1, wt2, wt3, wt4 exist)
        Path nextPath3 = repo.getNextWorktreePath(worktreeStorageDir);
        assertEquals("wt5", nextPath3.getFileName().toString(), "Expected next worktree path to be wt5 after wt3 is created");
    }

    // --- Tests for checkMergeConflicts ---

    // Mock MergeMode for testing purposes if direct instantiation is problematic
    // However, the plan is to use the actual static fields if accessible.
    // The provided snippet for MergeMode `GitWorktreeTab$MergeMode MERGE_COMMIT;` implies static fields.

    private void setupBranchesForConflictTest() throws Exception {
        // main: Initial commit -> C_main (modifies common.txt)
        // feature: Initial commit -> C_feature (modifies common.txt differently)
        createCommit("common.txt", "Original content", "Commit common base");
        String mainBranch = repo.getCurrentBranch();

        // Commit on main
        createCommit("common.txt", "Main modification", "C_main on common.txt");

        // Create feature branch from common base (before C_main)
        repo.getGit().branchCreate().setName("feature").setStartPoint("HEAD~1").call();
        repo.getGit().checkout().setName("feature").call();
        createCommit("common.txt", "Feature modification", "C_feature on common.txt");
        // Add another commit to feature to make it distinct beyond the conflict
        createCommit("feature_only.txt", "feature only", "F2 on feature");


        // Return to main branch for merge/rebase target
        repo.getGit().checkout().setName(mainBranch).call();
    }

    private void setupBranchesForNoConflictTest_FeatureAhead() throws Exception {
        // main: Initial commit
        // feature: Initial commit -> C1_feature (file1.txt) -> C2_feature (file2.txt)
        createCommit("main_base.txt", "Base file on main", "Commit on main");
        String mainBranch = repo.getCurrentBranch(); // e.g. "master"

        // Ensure "feature" doesn't exist from a previous failed run or state
        if (repo.listLocalBranches().contains("feature")) {
            repo.getGit().branchDelete().setBranchNames("feature").setForce(true).call();
        }
        repo.getGit().branchCreate().setName("feature").setStartPoint(mainBranch).call();
        repo.getGit().checkout().setName("feature").call();
        createCommit("file1.txt", "content1", "C1_feature");
        createCommit("file2.txt", "content2", "C2_feature");

        repo.getGit().checkout().setName(mainBranch).call();
    }
     private void setupBranchesForNoConflictTest_MainAhead() throws Exception {
        // main: Initial commit -> C1_main (main_file.txt)
        // feature: Initial commit
        String mainBranch = repo.getCurrentBranch();
        repo.getGit().branchCreate().setName("feature").setStartPoint("HEAD").call(); // feature from initial

        // Commit on main
        createCommit("main_file.txt", "main content", "C1_main");


        repo.getGit().checkout().setName("feature").call(); // Switch to feature, it's behind main
        // (no new commits on feature branch itself for this specific rebase test where feature is rebased onto a more advanced main)
        repo.getGit().checkout().setName(mainBranch).call(); // Back to main
    }


    @Test
    void testCheckMergeConflicts_NoConflict_MergeCommit() throws Exception {
        setupBranchesForNoConflictTest_FeatureAhead();
        String result = repo.checkMergeConflicts("feature", repo.getCurrentBranch(), GitWorktreeTab.MergeMode.MERGE_COMMIT);
        assertNull(result, "Should be no conflict for MERGE_COMMIT: " + result);
        assertEquals(repo.getCurrentBranch(), repo.getGit().getRepository().getBranch(), "Repository should remain on original branch");
    }

    @Test
    void testCheckMergeConflicts_Conflict_MergeCommit() throws Exception {
        setupBranchesForConflictTest();
        String result = repo.checkMergeConflicts("feature", repo.getCurrentBranch(), GitWorktreeTab.MergeMode.MERGE_COMMIT);
        assertNotNull(result, "Should be a conflict for MERGE_COMMIT");
        assertTrue(result.contains("common.txt"), "Conflict message should mention common.txt");
        assertEquals(repo.getCurrentBranch(), repo.getGit().getRepository().getBranch(), "Repository should remain on original branch");
        org.eclipse.jgit.lib.RepositoryState stateMerge = repo.getGit().getRepository().getRepositoryState();
        assertFalse(stateMerge == org.eclipse.jgit.lib.RepositoryState.MERGING || stateMerge == org.eclipse.jgit.lib.RepositoryState.MERGING_RESOLVED, "Repository should not be in merging state");
    }

    @Test
    void testCheckMergeConflicts_NoConflict_RebaseMerge() throws Exception {
        setupBranchesForNoConflictTest_MainAhead(); // main has C1_main, feature is at Initial
        // Rebase feature (at Initial) onto main (at C1_main). No new commits on feature, so it should be fast-forward or up-to-date like.
        String currentMainBranch = repo.getCurrentBranch();
        String result = repo.checkMergeConflicts("feature", currentMainBranch, GitWorktreeTab.MergeMode.REBASE_MERGE);
        assertNull(result, "Should be no conflict for REBASE_MERGE when feature is ancestor: " + result);
        assertEquals(currentMainBranch, repo.getGit().getRepository().getBranch(), "Repository should remain on original branch");
        assertFalse(repo.getGit().getRepository().getRepositoryState().isRebasing(), "Repository should not be in rebasing state");

        // Test with feature having its own commits that don't conflict
        tearDown(); setUp(); // Reset repo
        setupBranchesForNoConflictTest_FeatureAhead(); // feature has C1, C2; main is at Initial + main_base
        currentMainBranch = repo.getCurrentBranch();
        result = repo.checkMergeConflicts("feature", currentMainBranch, GitWorktreeTab.MergeMode.REBASE_MERGE);
        assertNull(result, "Should be no conflict for REBASE_MERGE with non-conflicting feature commits: " + result);
        assertEquals(currentMainBranch, repo.getGit().getRepository().getBranch(), "Repository should remain on original branch");
        assertFalse(repo.getGit().getRepository().getRepositoryState().isRebasing(), "Repository should not be in rebasing state");
    }

    @Test
    void testCheckMergeConflicts_Conflict_RebaseMerge() throws Exception {
        setupBranchesForConflictTest();
        String result = repo.checkMergeConflicts("feature", repo.getCurrentBranch(), GitWorktreeTab.MergeMode.REBASE_MERGE);
        assertNotNull(result, "Should be a conflict for REBASE_MERGE: " + result);
        // JGit's RebaseResult with STOPPED status might not always populate getConflicts().
        // The method returns a generic message in that case.
        // We accept either the specific file mention or the generic "stopped/conflicted" message.
        boolean mentionsSpecificFile = result.contains("common.txt");
        boolean isGenericStoppedMessage = result.contains("Rebase stopped or conflicted, but no specific files reported by JGit");
        assertTrue(mentionsSpecificFile || isGenericStoppedMessage,
                   "Conflict message should either mention 'common.txt' or be the generic 'stopped/conflicted' message. Actual: " + result);
        assertEquals(repo.getCurrentBranch(), repo.getGit().getRepository().getBranch(), "Repository should remain on original branch");
        assertFalse(repo.getGit().getRepository().getRepositoryState().isRebasing(), "Repository should not be in rebasing state");
    }

    @Test
    void testCheckMergeConflicts_NoConflict_SquashCommit() throws Exception {
        setupBranchesForNoConflictTest_FeatureAhead();
        String result = repo.checkMergeConflicts("feature", repo.getCurrentBranch(), GitWorktreeTab.MergeMode.SQUASH_COMMIT);
        assertNull(result, "Should be no conflict for SQUASH_COMMIT: " + result);
        assertEquals(repo.getCurrentBranch(), repo.getGit().getRepository().getBranch(), "Repository should remain on original branch");
        org.eclipse.jgit.lib.RepositoryState stateSquashNoConflict = repo.getGit().getRepository().getRepositoryState();
        assertFalse(stateSquashNoConflict == org.eclipse.jgit.lib.RepositoryState.MERGING || stateSquashNoConflict == org.eclipse.jgit.lib.RepositoryState.MERGING_RESOLVED, "Repository should not be in merging state after squash no conflict");
    }

    @Test
    void testCheckMergeConflicts_Conflict_SquashCommit() throws Exception {
        setupBranchesForConflictTest();
        String result = repo.checkMergeConflicts("feature", repo.getCurrentBranch(), GitWorktreeTab.MergeMode.SQUASH_COMMIT);
        assertNotNull(result, "Should be a conflict for SQUASH_COMMIT");
        assertTrue(result.contains("common.txt"), "Conflict message should mention common.txt for squash: " + result);
        assertEquals(repo.getCurrentBranch(), repo.getGit().getRepository().getBranch(), "Repository should remain on original branch");
        org.eclipse.jgit.lib.RepositoryState stateSquashConflict = repo.getGit().getRepository().getRepositoryState();
        assertFalse(stateSquashConflict == org.eclipse.jgit.lib.RepositoryState.MERGING || stateSquashConflict == org.eclipse.jgit.lib.RepositoryState.MERGING_RESOLVED, "Repository should not be in merging state after squash conflict");
    }

    @Test
    void testCheckMergeConflicts_InvalidWorktreeBranch() throws Exception {
        String mainBranch = repo.getCurrentBranch();
        String result = repo.checkMergeConflicts("nonexistent-feature", mainBranch, GitWorktreeTab.MergeMode.MERGE_COMMIT);
        assertNotNull(result);
        assertTrue(result.contains("Worktree branch 'nonexistent-feature' could not be resolved"));
    }

    @Test
    void testCheckMergeConflicts_InvalidTargetBranch() throws Exception {
        String mainBranch = repo.getCurrentBranch();
        repo.getGit().branchCreate().setName("feature-exists").call();
        String result = repo.checkMergeConflicts("feature-exists", "nonexistent-target", GitWorktreeTab.MergeMode.MERGE_COMMIT);
        assertNotNull(result);
        assertTrue(result.contains("Target branch 'nonexistent-target' could not be resolved"));
    }

    @Test
    void testCreateBranchFromCommit() throws Exception {
        String initialCommitId = repo.getCurrentCommitId();
        String newBranchNameInput = "feature/from-commit-test";
        // The sanitizeBranchName method ensures the name is valid and unique.
        String finalBranchName = repo.sanitizeBranchName(newBranchNameInput);

        repo.createBranchFromCommit(finalBranchName, initialCommitId);

        List<String> localBranches = repo.listLocalBranches();
        assertTrue(localBranches.contains(finalBranchName), "Newly created branch '" + finalBranchName + "' should exist.");

        // Verify the start point of the new branch
        org.eclipse.jgit.lib.Ref branchRef = repo.getGit().getRepository().findRef(finalBranchName);
        assertNotNull(branchRef, "Branch ref should not be null for '" + finalBranchName + "'.");
        assertEquals(initialCommitId, branchRef.getObjectId().getName(), "Branch '" + finalBranchName + "' should be created from commit " + initialCommitId + ".");

        // Test creating another branch with the same initial input, sanitizeBranchName should make it unique
        String duplicateInputName = newBranchNameInput; // "feature/from-commit-test"
        String finalBranchName2 = repo.sanitizeBranchName(duplicateInputName); // Should be "feature/from-commit-test-2"
        assertNotEquals(finalBranchName, finalBranchName2, "Sanitized name for duplicate input should be different.");

        repo.createBranchFromCommit(finalBranchName2, initialCommitId);
        assertTrue(repo.listLocalBranches().contains(finalBranchName2), "Second branch '" + finalBranchName2 + "' with same input (but sanitized uniquely) should exist.");
        org.eclipse.jgit.lib.Ref branchRef2 = repo.getGit().getRepository().findRef(finalBranchName2);
        assertNotNull(branchRef2);
        assertEquals(initialCommitId, branchRef2.getObjectId().getName());
    }
}
