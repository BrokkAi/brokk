package io.github.jbellis.brokk.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GitRepo merge conflict detection functionality.
 */
public class GitRepoMergeConflictTest {

    @TempDir
    Path tempDir;
    
    private GitRepo gitRepo;
    private Git git;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize git repository
        git = Git.init().setDirectory(tempDir.toFile()).call();
        gitRepo = new GitRepo(tempDir);
        
        // Configure user for commits
        git.getRepository().getConfig().setString("user", null, "name", "Test User");
        git.getRepository().getConfig().setString("user", null, "email", "test@example.com");
        git.getRepository().getConfig().save();
        
        // Create initial commit
        Path initialFile = tempDir.resolve("initial.txt");
        Files.writeString(initialFile, "initial content\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("initial.txt").call();
        git.commit().setMessage("Initial commit").call();
    }

    @AfterEach
    void tearDown() {
        GitTestCleanupUtil.cleanupGitResources(gitRepo, git);
    }

    @Test
    void testNoConflictMergeCommit() throws Exception {
        // Create branch1 with non-conflicting changes
        git.checkout().setCreateBranch(true).setName("branch1").call();
        Path file1 = tempDir.resolve("file1.txt");
        Files.writeString(file1, "branch1 content\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("file1.txt").call();
        git.commit().setMessage("Add file1").call();
        
        // Create branch2 with non-conflicting changes
        git.checkout().setName("master").call();
        git.checkout().setCreateBranch(true).setName("branch2").call();
        Path file2 = tempDir.resolve("file2.txt");
        Files.writeString(file2, "branch2 content\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("file2.txt").call();
        git.commit().setMessage("Add file2").call();
        
        // Test merge conflict check - should be null (no conflicts)
        String result = gitRepo.checkMergeConflicts("branch1", "branch2", GitRepo.MergeMode.MERGE_COMMIT);
        assertNull(result, "No conflicts should be detected for non-conflicting branches");
    }

    @Test
    void testConflictMergeCommit() throws Exception {
        // Create branch1 with changes to shared file
        git.checkout().setCreateBranch(true).setName("branch1").call();
        Path sharedFile = tempDir.resolve("shared.txt");
        Files.writeString(sharedFile, "branch1 version\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("shared.txt").call();
        git.commit().setMessage("Branch1 changes shared file").call();
        
        // Create branch2 with conflicting changes to same file
        git.checkout().setName("master").call();
        git.checkout().setCreateBranch(true).setName("branch2").call();
        Files.writeString(sharedFile, "branch2 version\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("shared.txt").call();
        git.commit().setMessage("Branch2 changes shared file").call();
        
        // Test merge conflict check - should detect conflicts
        String result = gitRepo.checkMergeConflicts("branch1", "branch2", GitRepo.MergeMode.MERGE_COMMIT);
        assertNotNull(result, "Conflicts should be detected");
        assertTrue(result.contains("shared.txt"), "Conflict message should mention the conflicting file");
        assertTrue(result.contains("Merge conflicts detected"), "Should indicate merge conflicts");
    }

    @Test
    void testNoConflictSquashCommit() throws Exception {
        // Create branch1 with non-conflicting changes
        git.checkout().setCreateBranch(true).setName("branch1").call();
        Path file1 = tempDir.resolve("file1.txt");
        Files.writeString(file1, "branch1 content\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("file1.txt").call();
        git.commit().setMessage("Add file1").call();
        
        // Create branch2 with non-conflicting changes
        git.checkout().setName("master").call();
        git.checkout().setCreateBranch(true).setName("branch2").call();
        Path file2 = tempDir.resolve("file2.txt");
        Files.writeString(file2, "branch2 content\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("file2.txt").call();
        git.commit().setMessage("Add file2").call();
        
        // Test squash merge conflict check - should be null (no conflicts)
        String result = gitRepo.checkMergeConflicts("branch1", "branch2", GitRepo.MergeMode.SQUASH_COMMIT);
        assertNull(result, "No conflicts should be detected for non-conflicting branches in squash mode");
    }

    @Test
    void testConflictSquashCommit() throws Exception {
        // Create branch1 with changes to shared file
        git.checkout().setCreateBranch(true).setName("branch1").call();
        Path sharedFile = tempDir.resolve("shared.txt");
        Files.writeString(sharedFile, "branch1 version\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("shared.txt").call();
        git.commit().setMessage("Branch1 changes shared file").call();
        
        // Create branch2 with conflicting changes to same file
        git.checkout().setName("master").call();
        git.checkout().setCreateBranch(true).setName("branch2").call();
        Files.writeString(sharedFile, "branch2 version\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("shared.txt").call();
        git.commit().setMessage("Branch2 changes shared file").call();
        
        // Test squash merge conflict check - should detect conflicts
        String result = gitRepo.checkMergeConflicts("branch1", "branch2", GitRepo.MergeMode.SQUASH_COMMIT);
        assertNotNull(result, "Conflicts should be detected in squash mode");
        assertTrue(result.contains("shared.txt"), "Conflict message should mention the conflicting file");
        assertTrue(result.contains("Merge conflicts detected"), "Should indicate merge conflicts");
    }

    @Test
    void testNoConflictRebaseMerge() throws Exception {
        // Create branch1 with changes
        git.checkout().setCreateBranch(true).setName("branch1").call();
        Path file1 = tempDir.resolve("file1.txt");
        Files.writeString(file1, "branch1 content\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("file1.txt").call();
        git.commit().setMessage("Add file1").call();
        
        // Create branch2 with non-conflicting changes
        git.checkout().setName("master").call();
        git.checkout().setCreateBranch(true).setName("branch2").call();
        Path file2 = tempDir.resolve("file2.txt");
        Files.writeString(file2, "branch2 content\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("file2.txt").call();
        git.commit().setMessage("Add file2").call();
        
        // Test rebase conflict check - should be null (no conflicts)
        String result = gitRepo.checkMergeConflicts("branch1", "branch2", GitRepo.MergeMode.REBASE_MERGE);
        assertNull(result, "No conflicts should be detected for non-conflicting rebase");
    }

    @Test
    void testConflictRebaseMerge() throws Exception {
        // Create branch1 with changes to shared file
        git.checkout().setCreateBranch(true).setName("branch1").call();
        Path sharedFile = tempDir.resolve("shared.txt");
        Files.writeString(sharedFile, "branch1 version\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("shared.txt").call();
        git.commit().setMessage("Branch1 changes shared file").call();
        
        // Create branch2 with conflicting changes to same file
        git.checkout().setName("master").call();
        git.checkout().setCreateBranch(true).setName("branch2").call();
        Files.writeString(sharedFile, "branch2 version\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("shared.txt").call();
        git.commit().setMessage("Branch2 changes shared file").call();
        
        // Test rebase conflict check - should detect conflicts
        String result = gitRepo.checkMergeConflicts("branch1", "branch2", GitRepo.MergeMode.REBASE_MERGE);
        assertNotNull(result, "Conflicts should be detected in rebase mode");
        assertTrue(result.contains("Rebase conflicts detected") || result.contains("Rebase stopped"), 
                  "Should indicate rebase conflicts or stoppage");
    }

    @Test
    void testRepositoryStateRestored() throws Exception {
        // Create conflicting branches
        git.checkout().setCreateBranch(true).setName("branch1").call();
        Path sharedFile = tempDir.resolve("shared.txt");
        Files.writeString(sharedFile, "branch1 version\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("shared.txt").call();
        git.commit().setMessage("Branch1 changes").call();
        
        git.checkout().setName("master").call();
        git.checkout().setCreateBranch(true).setName("branch2").call();
        Files.writeString(sharedFile, "branch2 version\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("shared.txt").call();
        git.commit().setMessage("Branch2 changes").call();
        
        // Store original branch
        String originalBranch = gitRepo.getCurrentBranch();
        
        // Test conflict check (should detect conflicts but restore state)
        gitRepo.checkMergeConflicts("branch1", "branch2", GitRepo.MergeMode.MERGE_COMMIT);
        
        // Verify we're back on the original branch
        assertEquals(originalBranch, gitRepo.getCurrentBranch(), 
                    "Should be back on original branch after conflict check");
        
        // Verify repository is in clean state (not merging/rebasing)
        assertFalse(git.getRepository().getRepositoryState().isRebasing(), 
                   "Repository should not be in rebasing state");
        assertNotEquals(org.eclipse.jgit.lib.RepositoryState.MERGING, 
                       git.getRepository().getRepositoryState(),
                       "Repository should not be in merging state");
    }

    @Test
    void testFastForwardMergeCommit() throws Exception {
        // Create a fast-forward scenario
        git.checkout().setCreateBranch(true).setName("feature").call();
        Path file1 = tempDir.resolve("feature.txt");
        Files.writeString(file1, "feature content\n", StandardCharsets.UTF_8);
        git.add().addFilepattern("feature.txt").call();
        git.commit().setMessage("Add feature").call();
        
        // Test merge conflict check for fast-forward case
        String result = gitRepo.checkMergeConflicts("feature", "master", GitRepo.MergeMode.MERGE_COMMIT);
        assertNull(result, "Fast-forward merge should not have conflicts");
        
        // Verify the merge would actually create a merge commit (NO_FF mode)
        // This is tested by ensuring the logic sets NO_FF mode in the implementation
    }
}