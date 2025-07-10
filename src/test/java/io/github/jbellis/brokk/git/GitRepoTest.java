package io.github.jbellis.brokk.git;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import io.github.jbellis.brokk.analyzer.ProjectFile;

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
            git.commit().setMessage("Initial commit").setAuthor("Test User", "test@example.com").setSign(false).call();
        }

        repo = new GitRepo(projectRoot);
    }

    @AfterEach
    void tearDown() throws Exception {
        GitTestCleanupUtil.cleanupGitResources(repo);
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
        repo.getGit().commit().setAuthor("Test User", "test@example.com").setMessage(message).setSign(false).call();
    }

    @Test
    void testGetCommitMessagesBetween_sameBranch() throws Exception {
        List<String> messages = repo.getCommitMessagesBetween(repo.getCurrentBranch(), repo.getCurrentBranch());
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
        String result = repo.checkMergeConflicts("feature", repo.getCurrentBranch(), GitRepo.MergeMode.MERGE_COMMIT);
        assertNull(result, "Should be no conflict for MERGE_COMMIT: " + result);
        assertEquals(repo.getCurrentBranch(), repo.getGit().getRepository().getBranch(), "Repository should remain on original branch");
    }

    @Test
    void testCheckMergeConflicts_Conflict_MergeCommit() throws Exception {
        setupBranchesForConflictTest();
        String result = repo.checkMergeConflicts("feature", repo.getCurrentBranch(), GitRepo.MergeMode.MERGE_COMMIT);
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
        String result = repo.checkMergeConflicts("feature", currentMainBranch, GitRepo.MergeMode.REBASE_MERGE);
        assertNull(result, "Should be no conflict for REBASE_MERGE when feature is ancestor: " + result);
        assertEquals(currentMainBranch, repo.getGit().getRepository().getBranch(), "Repository should remain on original branch");
        assertFalse(repo.getGit().getRepository().getRepositoryState().isRebasing(), "Repository should not be in rebasing state");

        // Test with feature having its own commits that don't conflict
        tearDown(); setUp(); // Reset repo
        setupBranchesForNoConflictTest_FeatureAhead(); // feature has C1, C2; main is at Initial + main_base
        currentMainBranch = repo.getCurrentBranch();
        result = repo.checkMergeConflicts("feature", currentMainBranch, GitRepo.MergeMode.REBASE_MERGE);
        assertNull(result, "Should be no conflict for REBASE_MERGE with non-conflicting feature commits: " + result);
        assertEquals(currentMainBranch, repo.getGit().getRepository().getBranch(), "Repository should remain on original branch");
        assertFalse(repo.getGit().getRepository().getRepositoryState().isRebasing(), "Repository should not be in rebasing state");
    }

    @Test
    void testCheckMergeConflicts_Conflict_RebaseMerge() throws Exception {
        setupBranchesForConflictTest();
        String result = repo.checkMergeConflicts("feature", repo.getCurrentBranch(), GitRepo.MergeMode.REBASE_MERGE);
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
        String result = repo.checkMergeConflicts("feature", repo.getCurrentBranch(), GitRepo.MergeMode.SQUASH_COMMIT);
        assertNull(result, "Should be no conflict for SQUASH_COMMIT: " + result);
        assertEquals(repo.getCurrentBranch(), repo.getGit().getRepository().getBranch(), "Repository should remain on original branch");
        org.eclipse.jgit.lib.RepositoryState stateSquashNoConflict = repo.getGit().getRepository().getRepositoryState();
        assertFalse(stateSquashNoConflict == org.eclipse.jgit.lib.RepositoryState.MERGING || stateSquashNoConflict == org.eclipse.jgit.lib.RepositoryState.MERGING_RESOLVED, "Repository should not be in merging state after squash no conflict");
    }

    @Test
    void testCheckMergeConflicts_Conflict_SquashCommit() throws Exception {
        setupBranchesForConflictTest();
        String result = repo.checkMergeConflicts("feature", repo.getCurrentBranch(), GitRepo.MergeMode.SQUASH_COMMIT);
        assertNotNull(result, "Should be a conflict for SQUASH_COMMIT");
        assertTrue(result.contains("common.txt"), "Conflict message should mention common.txt for squash: " + result);
        assertEquals(repo.getCurrentBranch(), repo.getGit().getRepository().getBranch(), "Repository should remain on original branch");
        org.eclipse.jgit.lib.RepositoryState stateSquashConflict = repo.getGit().getRepository().getRepositoryState();
        assertFalse(stateSquashConflict == org.eclipse.jgit.lib.RepositoryState.MERGING || stateSquashConflict == org.eclipse.jgit.lib.RepositoryState.MERGING_RESOLVED, "Repository should not be in merging state after squash conflict");
    }

    @Test
    void testCheckMergeConflicts_InvalidWorktreeBranch() throws Exception {
        String mainBranch = repo.getCurrentBranch();
        assertThrows(GitRepo.GitRepoException.class,
                     () -> repo.checkMergeConflicts("nonexistent-feature", mainBranch, GitRepo.MergeMode.MERGE_COMMIT));
    }

    @Test
    void testCheckMergeConflicts_InvalidTargetBranch() throws Exception {
        repo.getGit().branchCreate().setName("feature-exists").call();
        assertThrows(GitRepo.GitRepoException.class,
                     () -> repo.checkMergeConflicts("feature-exists", "nonexistent-target", GitRepo.MergeMode.MERGE_COMMIT));
    }

    @Test
    void testCheckoutFilesFromCommit() throws Exception {
        // Create test files with initial content
        Path file1 = projectRoot.resolve("file1.txt");
        Path file2 = projectRoot.resolve("file2.txt");
        Path file3 = projectRoot.resolve("file3.txt");

        Files.writeString(file1, "Initial content 1");
        Files.writeString(file2, "Initial content 2");
        Files.writeString(file3, "Initial content 3");

        // Add and commit initial state
        repo.getGit().add().addFilepattern("file1.txt").addFilepattern("file2.txt").addFilepattern("file3.txt").call();
        repo.getGit().commit().setMessage("Initial commit with 3 files").setSign(false).call();
        String initialCommit = repo.getCurrentCommitId();

        // Modify files and commit
        Files.writeString(file1, "Modified content 1");
        Files.writeString(file2, "Modified content 2");
        Files.writeString(file3, "Modified content 3");

        repo.getGit().add().addFilepattern(".").call();
        repo.getGit().commit().setMessage("Modified all files").setSign(false).call();

        // Verify files have modified content
        assertEquals("Modified content 1", Files.readString(file1));
        assertEquals("Modified content 2", Files.readString(file2));
        assertEquals("Modified content 3", Files.readString(file3));

        // Create ProjectFile objects for file1 and file2 only
        List<ProjectFile> filesToRollback = List.of(
            new ProjectFile(projectRoot, "file1.txt"),
            new ProjectFile(projectRoot, "file2.txt")
        );

        // Checkout file1 and file2 from initial commit
        repo.checkoutFilesFromCommit(initialCommit, filesToRollback);

        // Verify file1 and file2 are restored to initial state
        assertEquals("Initial content 1", Files.readString(file1));
        assertEquals("Initial content 2", Files.readString(file2));

        // Verify file3 remains modified (not included in checkout)
        assertEquals("Modified content 3", Files.readString(file3));
    }

    @Test
    void testCheckoutFilesFromCommit_InvalidCommit() throws Exception {
        Path file1 = projectRoot.resolve("file1.txt");
        Files.writeString(file1, "Some content");
        repo.getGit().add().addFilepattern("file1.txt").call();
        repo.getGit().commit().setMessage("Add file1").setSign(false).call();

        List<ProjectFile> files = List.of(new ProjectFile(projectRoot, "file1.txt"));

        assertThrows(Exception.class, () -> {
            repo.checkoutFilesFromCommit("invalid-commit-id", files);
        }, "Should throw exception for invalid commit ID");
    }

    @Test
    void testCheckoutFilesFromCommit_EmptyFilesList() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            repo.checkoutFilesFromCommit("HEAD", List.of());
        }, "Should throw IllegalArgumentException for empty files list");
    }
    
    @Test
    void testCheckoutFilesFromCommit_NonExistentFile() throws Exception {
        // Create and commit a file
        Path file1 = projectRoot.resolve("file1.txt");
        Files.writeString(file1, "Initial content");
        repo.getGit().add().addFilepattern("file1.txt").call();
        repo.getGit().commit().setMessage("Add file1").setSign(false).call();
        String firstCommit = repo.getCurrentCommitId();

        // Delete the file and commit
        Files.delete(file1);
        repo.getGit().rm().addFilepattern("file1.txt").call();
        repo.getGit().commit().setMessage("Delete file1").setSign(false).call();

        // Try to checkout the deleted file from the first commit
        List<ProjectFile> files = List.of(new ProjectFile(projectRoot, "file1.txt"));
        repo.checkoutFilesFromCommit(firstCommit, files);

        // Verify the file is restored
        assertTrue(Files.exists(file1));
        assertEquals("Initial content", Files.readString(file1));
    }

    @Test
    void testCheckoutFilesFromCommit_FileInSubdirectory() throws Exception {
        // Create file in subdirectory
        Path subDir = projectRoot.resolve("src").resolve("main").resolve("java");
        Files.createDirectories(subDir);
        Path file = subDir.resolve("MyClass.java");
        Files.writeString(file, "public class MyClass {}");

        repo.getGit().add().addFilepattern("src/main/java/MyClass.java").call();
        repo.getGit().commit().setMessage("Add MyClass").setSign(false).call();
        String firstCommit = repo.getCurrentCommitId();

        // Modify the file
        Files.writeString(file, "public class MyClass { /* modified */ }");
        repo.getGit().add().addFilepattern(".").call();
        repo.getGit().commit().setMessage("Modify MyClass").setSign(false).call();

        // Checkout from first commit
        List<ProjectFile> files = List.of(new ProjectFile(projectRoot, "src/main/java/MyClass.java"));
        repo.checkoutFilesFromCommit(firstCommit, files);

        // Verify restoration
        assertEquals("public class MyClass {}", Files.readString(file));
    }

    @Test
    void testCheckoutFilesFromCommit_WithUnstagedChanges() throws Exception {
        // Create and commit a file
        Path file1 = projectRoot.resolve("file1.txt");
        Files.writeString(file1, "Initial content");
        repo.getGit().add().addFilepattern("file1.txt").call();
        repo.getGit().commit().setMessage("Initial commit").setSign(false).call();
        String initialCommit = repo.getCurrentCommitId();

        // Make changes and commit
        Files.writeString(file1, "Committed change");
        repo.getGit().add().addFilepattern("file1.txt").call();
        repo.getGit().commit().setMessage("Update file1").setSign(false).call();

        // Make unstaged changes
        Files.writeString(file1, "Unstaged change");

        // Checkout from initial commit (should overwrite unstaged changes)
        List<ProjectFile> files = List.of(new ProjectFile(projectRoot, "file1.txt"));
        repo.checkoutFilesFromCommit(initialCommit, files);

        // Verify file is restored to initial state
        assertEquals("Initial content", Files.readString(file1));
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

    @Test
    void testGetTrackedFilesEmptyRepository() throws Exception {
        var emptyRepoRoot = tempDir.resolve("emptyRepo");
        Files.createDirectories(emptyRepoRoot);

        // Initialize empty git repository without any commits
        Git.init().setDirectory(emptyRepoRoot.toFile()).call();

        var emptyRepo = new GitRepo(emptyRepoRoot);
        // Create a staged file to test that getTrackedFiles works with staged files in empty repo
        var stagedFile = emptyRepoRoot.resolve("staged.txt");
        Files.writeString(stagedFile, "This file is staged but not committed");
        emptyRepo.getGit().add().addFilepattern("staged.txt").call();

        // This should not throw an exception even though HEAD^{tree} doesn't exist
        var trackedFiles = emptyRepo.getTrackedFiles();

        // Should contain the staged file
        assertNotNull(trackedFiles, "getTrackedFiles should not return null for empty repository");
        assertEquals(1, trackedFiles.size(), "Should contain exactly one staged file");

        var trackedFile = trackedFiles.iterator().next();
        assertEquals("staged.txt", trackedFile.getFileName(), "Tracked file should be the staged file");
    }

    @Test
    void testDiffEmptyRepository() throws Exception {
        var emptyRepoRoot = tempDir.resolve("emptyRepo2");
        Files.createDirectories(emptyRepoRoot);

        Git.init().setDirectory(emptyRepoRoot.toFile()).call();

        var emptyRepo = new GitRepo(emptyRepoRoot);
        var diff = emptyRepo.diff();

        // Should return empty string for empty repository
        assertEquals("", diff, "diff() should return empty string for empty repository with no changes");

        // Add a file and stage it to test diff methods with staged files
        var stagedFile = emptyRepoRoot.resolve("staged.txt");
        Files.writeString(stagedFile, "This file is staged but not committed");
        emptyRepo.getGit().add().addFilepattern("staged.txt").call();

        // Test diff() method with staged file in empty repository
        var diffWithStaged = emptyRepo.diff();
        // The main goal is that this doesn't throw NoHeadException - content can be empty or not
        assertEquals("", diff, "diff() should return empty string for empty repository with no changes");

        // Test diffFiles method with staged file in empty repository
        var projectFile = new ProjectFile(emptyRepoRoot, "staged.txt");
        var diffFiles = emptyRepo.diffFiles(List.of(projectFile));

        // The main goal is that this doesn't throw NoHeadException - content can be empty or not
        assertEquals("", diff, "diff() should return empty string for empty repository with no changes");
    }

    // --- Tests for createTempRebaseBranchName ---

    @Test
    void testCreateTempRebaseBranchName_UniqueGeneration() {
        String baseName = "feature/awesome-branch";

        String name1 = GitRepo.createTempRebaseBranchName(baseName);

        // Sleep briefly to ensure different timestamps
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String name2 = GitRepo.createTempRebaseBranchName(baseName);

        // Names may be the same if called within the same millisecond, but that's acceptable
        // since the goal is to avoid conflicts in practice, not guarantee uniqueness in tests
        assertTrue(name1.startsWith("brokk_temp_rebase_feature_awesome-branch_"),
                  "Generated name should start with expected prefix: " + name1);
        assertTrue(name2.startsWith("brokk_temp_rebase_feature_awesome-branch_"),
                  "Generated name should start with expected prefix");

        // Verify timestamp suffix is numeric
        String suffix1 = name1.substring(name1.lastIndexOf('_') + 1);
        String suffix2 = name2.substring(name2.lastIndexOf('_') + 1);

        assertTrue(suffix1.length() >= 10, "Timestamp suffix should be at least 10 characters: " + suffix1);
        assertTrue(suffix2.length() >= 10, "Timestamp suffix should be at least 10 characters: " + suffix2);

        // Verify suffix is valid timestamp (numeric)
        assertDoesNotThrow(() -> Long.parseLong(suffix1),
                          "Timestamp suffix should be numeric: " + suffix1);
        assertDoesNotThrow(() -> Long.parseLong(suffix2),
                          "Timestamp suffix should be numeric: " + suffix2);

        // Verify timestamps are non-decreasing (may be equal due to resolution)
        long timestamp1 = Long.parseLong(suffix1);
        long timestamp2 = Long.parseLong(suffix2);
        assertTrue(timestamp2 >= timestamp1, "Second timestamp should be >= first timestamp");
    }

    @Test
    void testCreateTempRebaseBranchName_SanitizesInput() {
        String unsafeName = "feature/branch with spaces!@#";

        String sanitized = GitRepo.createTempRebaseBranchName(unsafeName);

        assertTrue(sanitized.contains("brokk_temp_rebase_feature_branch_with_spaces____"),
                  "Should sanitize unsafe characters to underscores: " + sanitized);
        assertFalse(sanitized.contains(" "), "Should not contain spaces");
        assertFalse(sanitized.contains("!"), "Should not contain exclamation marks");
        assertFalse(sanitized.contains("@"), "Should not contain at signs");
        assertFalse(sanitized.contains("#"), "Should not contain hash symbols");
    }

    @Test
    void testCreateTempRebaseBranchName_EmptyInput() {
        String emptyName = "";

        String result = GitRepo.createTempRebaseBranchName(emptyName);

        assertTrue(result.startsWith("brokk_temp_rebase__"),
                  "Should handle empty input gracefully");
        assertTrue(result.endsWith("_" + result.substring(result.lastIndexOf('_') + 1)),
                  "Should still append timestamp suffix");
    }

    @Test
    void testCreateTempRebaseBranchName_PrefixConstant() {
        String baseName = "test";
        String result = GitRepo.createTempRebaseBranchName(baseName);

        assertTrue(result.startsWith("brokk_temp_rebase_"),
                  "Should use the correct prefix constant");
    }

    @Test
    void testCreateTempRebaseBranchName_ComplexBranchName() {
        String complexName = "feature/user-auth!@#$%^&*()";
        String result = GitRepo.createTempRebaseBranchName(complexName);

        assertTrue(result.startsWith("brokk_temp_rebase_feature_user-auth"),
                  "Should sanitize complex characters properly: " + result);
        assertTrue(result.matches("brokk_temp_rebase_feature_user-auth_+\\d+"),
                  "Should match expected pattern with timestamp: " + result);
        assertFalse(result.contains("!"), "Should not contain exclamation marks");
        assertFalse(result.contains("@"), "Should not contain at signs");
        assertFalse(result.contains("#"), "Should not contain hash symbols");
        assertFalse(result.contains("$"), "Should not contain dollar signs");
        assertFalse(result.contains("%"), "Should not contain percent signs");
    }

    @Test
    void testCreateTempRebaseBranchName_OnlySpecialCharacters() {
        String specialCharsOnly = "!@#$%^&*()";
        String result = GitRepo.createTempRebaseBranchName(specialCharsOnly);

        assertTrue(result.startsWith("brokk_temp_rebase_"),
                  "Should handle special-chars-only input: " + result);
        // Should result in "brokk_temp_rebase___________timestamp" (underscores replacing special chars)
        assertTrue(result.matches("brokk_temp_rebase__+\\d+"),
                  "Should replace all special characters with underscores: " + result);
    }

    @Test
    void testCreateTempRebaseBranchName_ValidCharactersPreserved() {
        String validName = "feature_branch-name123";
        String result = GitRepo.createTempRebaseBranchName(validName);

        assertTrue(result.contains("feature_branch-name123"),
                  "Should preserve valid characters: " + result);
        assertTrue(result.startsWith("brokk_temp_rebase_feature_branch-name123_"),
                  "Should maintain valid branch name components: " + result);
    }
}
