package io.github.jbellis.brokk.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.RefSpec;
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
 * Tests for GitRepo remote merge functionality via fast-forward, squash, and rebase modes.
 */
public class GitRepoRemoteMergeTest {

    @TempDir
    Path tempDir;

    @TempDir
    Path remoteDir;

    private GitRepo localRepo;
    private Git localGit;
    private Git remoteGit;
    private final String n = System.lineSeparator();

    @BeforeEach
    void setUp() throws Exception {
        // Initialize remote repository
        remoteGit = Git.init().setDirectory(remoteDir.toFile()).setBare(true).call();

        // Clone remote to create local repository
        localGit = Git.cloneRepository()
                .setURI(remoteDir.toUri().toString())
                .setDirectory(tempDir.toFile())
                .call();
        localRepo = new GitRepo(tempDir);

        // Configure user for commits
        localGit.getRepository().getConfig().setString("user", null, "name", "Test User");
        localGit.getRepository().getConfig().setString("user", null, "email", "test@example.com");
        localGit.getRepository().getConfig().save();

        // Create initial commit
        Path initialFile = tempDir.resolve("initial.txt");
        Files.writeString(initialFile, "initial content\n", StandardCharsets.UTF_8);
        localGit.add().addFilepattern("initial.txt").call();
        localGit.commit().setMessage("Initial commit").call();
        localGit.push().call();
    }

    @AfterEach
    void tearDown() {
        // Close GitRepo first which calls git.close() and repository.close()
        if (localRepo != null) {
            try {
                localRepo.close();
            } catch (Exception e) {
                // Log but don't fail test cleanup
                System.err.println("Error closing localRepo: " + e.getMessage());
            }
        }

        // Close Git instances - redundant but ensures cleanup on Windows
        if (localGit != null) {
            try {
                localGit.close();
            } catch (Exception e) {
                System.err.println("Error closing localGit: " + e.getMessage());
            }
        }
        if (remoteGit != null) {
            try {
                remoteGit.close();
            } catch (Exception e) {
                System.err.println("Error closing remoteGit: " + e.getMessage());
            }
        }

        // Force garbage collection to help with Windows file handle cleanup
        System.gc();

        // Small delay to allow file handles to be released on Windows
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void testRemoteMergeViaFastForward() throws Exception {
        // Create and push a feature branch
        localGit.checkout().setCreateBranch(true).setName("feature").call();
        Path featureFile = tempDir.resolve("feature.txt");
        Files.writeString(featureFile, "feature content\n", StandardCharsets.UTF_8);
        localGit.add().addFilepattern("feature.txt").call();
        localGit.commit().setMessage("Add feature").call();
        localGit.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/feature:refs/heads/feature")).call();

        // Switch back to master
        localGit.checkout().setName("master").call();

        // Fetch remote changes
        localGit.fetch().call();

        // Perform merge via fast-forward
        MergeResult result = localRepo.performMerge("origin/feature", GitRepo.MergeMode.MERGE_COMMIT);

        // Verify merge was successful
        assertTrue(GitRepo.isMergeSuccessful(result, GitRepo.MergeMode.MERGE_COMMIT));
        assertEquals(MergeResult.MergeStatus.FAST_FORWARD, result.getMergeStatus());

        // Verify the feature file exists
        assertTrue(Files.exists(featureFile));
        assertEquals(String.format("feature content%s", n), Files.readString(featureFile, StandardCharsets.UTF_8));
    }

    @Test
    void testRemoteMergeViaSquash() throws Exception {
        // Create and push a feature branch with multiple commits
        localGit.checkout().setCreateBranch(true).setName("feature").call();

        // First commit
        Path featureFile = tempDir.resolve("feature.txt");
        Files.writeString(featureFile, "feature content\n", StandardCharsets.UTF_8);
        localGit.add().addFilepattern("feature.txt").call();
        localGit.commit().setMessage("Add feature file").call();

        // Second commit
        Files.writeString(featureFile, "feature content\nupdated\n", StandardCharsets.UTF_8);
        localGit.add().addFilepattern("feature.txt").call();
        localGit.commit().setMessage("Update feature file").call();

        localGit.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/feature:refs/heads/feature")).call();

        // Switch back to master and create a divergent commit
        localGit.checkout().setName("master").call();
        Path masterFile = tempDir.resolve("master.txt");
        Files.writeString(masterFile, "master content\n", StandardCharsets.UTF_8);
        localGit.add().addFilepattern("master.txt").call();
        localGit.commit().setMessage("Add master file").call();

        // Fetch remote changes
        localGit.fetch().call();

        // Perform squash merge
        MergeResult result = localRepo.performMerge("origin/feature", GitRepo.MergeMode.SQUASH_COMMIT);

        // Verify merge was successful
        assertTrue(GitRepo.isMergeSuccessful(result, GitRepo.MergeMode.SQUASH_COMMIT));
        // Squash merge can result in either FAST_FORWARD_SQUASHED or MERGED_SQUASHED depending on the situation
        assertTrue(result.getMergeStatus() == MergeResult.MergeStatus.FAST_FORWARD_SQUASHED ||
                   result.getMergeStatus() == MergeResult.MergeStatus.MERGED_SQUASHED);

        // Verify both files exist
        assertTrue(Files.exists(featureFile));
        assertTrue(Files.exists(masterFile));
        assertEquals(String.format("feature content%supdated%s", n, n), Files.readString(featureFile, StandardCharsets.UTF_8));
    }

    @Test
    void testRemoteMergeViaRebase() throws Exception {
        // Create and push a feature branch
        localGit.checkout().setCreateBranch(true).setName("feature").call();
        Path featureFile = tempDir.resolve("feature.txt");
        Files.writeString(featureFile, "feature content\n", StandardCharsets.UTF_8);
        localGit.add().addFilepattern("feature.txt").call();
        localGit.commit().setMessage("Add feature file").call();
        localGit.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/feature:refs/heads/feature")).call();

        // Switch back to master and create a divergent commit
        localGit.checkout().setName("master").call();
        Path masterFile = tempDir.resolve("master.txt");
        Files.writeString(masterFile, "master content\n", StandardCharsets.UTF_8);
        localGit.add().addFilepattern("master.txt").call();
        localGit.commit().setMessage("Add master file").call();

        // Fetch remote changes
        localGit.fetch().call();

        // Perform rebase merge
        MergeResult result = localRepo.performMerge("origin/feature", GitRepo.MergeMode.REBASE_MERGE);

        // Verify merge was successful
        assertTrue(GitRepo.isMergeSuccessful(result, GitRepo.MergeMode.REBASE_MERGE));

        // Verify both files exist
        assertTrue(Files.exists(featureFile));
        assertTrue(Files.exists(masterFile));
        assertEquals(String.format("feature content%s", n), Files.readString(featureFile, StandardCharsets.UTF_8));
        assertEquals(String.format("master content%s", n), Files.readString(masterFile, StandardCharsets.UTF_8));
    }

    @Test
    void testRemoteMergeConflictDetection() throws Exception {
        // Create non-conflicting branches for this simpler test
        // We verify that the conflict detection method can be called without error
        localGit.checkout().setCreateBranch(true).setName("feature").call();
        Path featureFile = tempDir.resolve("feature-only.txt");
        Files.writeString(featureFile, "feature content\n", StandardCharsets.UTF_8);
        localGit.add().addFilepattern("feature-only.txt").call();
        localGit.commit().setMessage("Add feature only file").call();
        localGit.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/feature:refs/heads/feature")).call();

        // Switch back to master and add different file
        localGit.checkout().setName("master").call();
        Path masterFile = tempDir.resolve("master-only.txt");
        Files.writeString(masterFile, "master content\n", StandardCharsets.UTF_8);
        localGit.add().addFilepattern("master-only.txt").call();
        localGit.commit().setMessage("Add master only file").call();

        // Fetch remote changes
        localGit.fetch().call();

        // Test conflict detection methods work (should return null for no conflicts)
        String conflictCheck = localRepo.checkMergeConflicts("origin/feature", "master", GitRepo.MergeMode.MERGE_COMMIT);
        assertNull(conflictCheck, "No conflicts should be detected for non-overlapping files");

        conflictCheck = localRepo.checkMergeConflicts("origin/feature", "master", GitRepo.MergeMode.SQUASH_COMMIT);
        assertNull(conflictCheck, "No conflicts should be detected for non-overlapping files");

        conflictCheck = localRepo.checkMergeConflicts("origin/feature", "master", GitRepo.MergeMode.REBASE_MERGE);
        assertNull(conflictCheck, "No conflicts should be detected for non-overlapping files");
    }

    @Test
    void testRemoteBranchValidation() throws Exception {
        // Create and push a feature branch
        localGit.checkout().setCreateBranch(true).setName("feature").call();
        Path featureFile = tempDir.resolve("feature.txt");
        Files.writeString(featureFile, "feature content\n", StandardCharsets.UTF_8);
        localGit.add().addFilepattern("feature.txt").call();
        localGit.commit().setMessage("Add feature").call();
        localGit.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/feature:refs/heads/feature")).call();

        // Switch back to master
        localGit.checkout().setName("master").call();

        // Fetch remote changes
        localGit.fetch().call();

        // Verify remote branch exists
        assertTrue(localRepo.listRemoteBranches().contains("origin/feature"));

        // Test that merge with non-existent remote branch fails
        assertThrows(GitAPIException.class, () -> {
            localRepo.performMerge("origin/nonexistent", GitRepo.MergeMode.MERGE_COMMIT);
        });
    }
}
