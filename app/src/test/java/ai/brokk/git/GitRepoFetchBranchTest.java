package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for GitRepoRemote.fetchBranch functionality. */
public class GitRepoFetchBranchTest {

    @TempDir
    Path tempDir;

    @TempDir
    Path remoteDir;

    private GitRepo localRepo;
    private Git localGit;
    private Git remoteGit;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize remote repository (non-bare to allow commits)
        remoteGit = Git.init().setDirectory(remoteDir.toFile()).call();

        // Configure user for commits in remote
        remoteGit.getRepository().getConfig().setString("user", null, "name", "Remote User");
        remoteGit.getRepository().getConfig().setString("user", null, "email", "remote@example.com");
        remoteGit.getRepository().getConfig().setBoolean("commit", null, "gpgsign", false);
        remoteGit.getRepository().getConfig().save();

        // Create initial commit in remote
        Path initialFile = remoteDir.resolve("initial.txt");
        Files.writeString(initialFile, "initial content\n", StandardCharsets.UTF_8);
        remoteGit.add().addFilepattern("initial.txt").call();
        remoteGit.commit().setMessage("Initial commit").call();

        // Clone remote to create local repository
        localGit = Git.cloneRepository()
                .setURI(remoteDir.toUri().toString())
                .setDirectory(tempDir.toFile())
                .call();
        localRepo = new GitRepo(tempDir);

        // Configure user for commits in local
        localGit.getRepository().getConfig().setString("user", null, "name", "Test User");
        localGit.getRepository().getConfig().setString("user", null, "email", "test@example.com");
        localGit.getRepository().getConfig().setBoolean("commit", null, "gpgsign", false);
        localGit.getRepository().getConfig().save();
    }

    @AfterEach
    void tearDown() {
        GitTestCleanupUtil.cleanupGitResources(localRepo, localGit, remoteGit);
    }

    @Test
    void testFetchBranch_Success() throws Exception {
        // Create a new branch with commit in remote
        remoteGit.checkout().setCreateBranch(true).setName("feature-branch").call();
        Path featureFile = remoteDir.resolve("feature.txt");
        Files.writeString(featureFile, "feature content\n", StandardCharsets.UTF_8);
        remoteGit.add().addFilepattern("feature.txt").call();
        remoteGit.commit().setMessage("Add feature").call();

        // Switch remote back to master to avoid issues
        remoteGit.checkout().setName("master").call();

        // Verify the branch doesn't exist locally yet
        var localRef = localGit.getRepository().findRef("refs/remotes/origin/feature-branch");
        assertNull(localRef, "Branch should not exist locally before fetch");

        // Fetch the branch
        localRepo.remote().fetchBranch("origin", "feature-branch");

        // Verify the branch now exists locally
        localRef = localGit.getRepository().findRef("refs/remotes/origin/feature-branch");
        assertNotNull(localRef, "Branch should exist locally after fetch");

        // Verify we can checkout the fetched branch
        localRepo.checkoutRemoteBranch("origin/feature-branch", "feature-branch");
        assertEquals("feature-branch", localRepo.getCurrentBranch());

        // Verify the file from the feature branch exists
        assertTrue(Files.exists(tempDir.resolve("feature.txt")));
    }

    @Test
    void testFetchBranch_NonExistentBranch() throws Exception {
        // Attempt to fetch a branch that doesn't exist should fail
        assertThrows(Exception.class, () -> {
            localRepo.remote().fetchBranch("origin", "non-existent-branch");
        });
    }

    @Test
    void testFetchBranch_GitHubHttpsRequiresToken() throws Exception {
        // Create repo with empty token supplier to simulate missing token
        localRepo = new GitRepo(tempDir, () -> "");

        // Change remote to GitHub HTTPS
        var config = localGit.getRepository().getConfig();
        config.setString("remote", "origin", "url", "https://github.com/test/repo.git");
        config.save();

        // fetchBranch() should fail with GitHubAuthenticationException
        assertThrows(GitHubAuthenticationException.class, () -> {
            localRepo.remote().fetchBranch("origin", "some-branch");
        });
    }

    @Test
    void testFetchBranch_UpdatesExistingBranch() throws Exception {
        // Create a branch in remote
        remoteGit.checkout().setCreateBranch(true).setName("update-branch").call();
        Path file = remoteDir.resolve("update.txt");
        Files.writeString(file, "initial\n", StandardCharsets.UTF_8);
        remoteGit.add().addFilepattern("update.txt").call();
        remoteGit.commit().setMessage("Initial update").call();
        remoteGit.checkout().setName("master").call();

        // Fetch it once
        localRepo.remote().fetchBranch("origin", "update-branch");

        // Add another commit to the remote branch
        remoteGit.checkout().setName("update-branch").call();
        Files.writeString(file, "updated\n", StandardCharsets.UTF_8);
        remoteGit.add().addFilepattern("update.txt").call();
        var newCommit = remoteGit.commit().setMessage("Update content").call();
        remoteGit.checkout().setName("master").call();

        // Fetch again - should update
        localRepo.remote().fetchBranch("origin", "update-branch");

        // Verify the local ref points to the new commit
        var localRef = localGit.getRepository().findRef("refs/remotes/origin/update-branch");
        assertNotNull(localRef);
        assertEquals(newCommit.getId(), localRef.getObjectId());
    }

    @Test
    void testBranchNeedsFetch_NoLocalRef_ReturnsTrue() throws Exception {
        // Create a new branch in remote that local doesn't have
        remoteGit.checkout().setCreateBranch(true).setName("new-branch").call();
        Path file = remoteDir.resolve("new.txt");
        Files.writeString(file, "new content\n", StandardCharsets.UTF_8);
        remoteGit.add().addFilepattern("new.txt").call();
        remoteGit.commit().setMessage("New branch commit").call();
        remoteGit.checkout().setName("master").call();

        // Local doesn't have this branch yet - should need fetch
        assertTrue(localRepo.remote().branchNeedsFetch("origin", "new-branch"),
                "Should need fetch when local ref doesn't exist");
    }

    @Test
    void testBranchNeedsFetch_UpToDate_ReturnsFalse() throws Exception {
        // Create and fetch a branch
        remoteGit.checkout().setCreateBranch(true).setName("synced-branch").call();
        Path file = remoteDir.resolve("synced.txt");
        Files.writeString(file, "synced content\n", StandardCharsets.UTF_8);
        remoteGit.add().addFilepattern("synced.txt").call();
        remoteGit.commit().setMessage("Synced commit").call();
        remoteGit.checkout().setName("master").call();

        // Fetch the branch
        localRepo.remote().fetchBranch("origin", "synced-branch");

        // Now local and remote are in sync - should not need fetch
        assertFalse(localRepo.remote().branchNeedsFetch("origin", "synced-branch"),
                "Should not need fetch when local ref matches remote");
    }

    @Test
    void testBranchNeedsFetch_RemoteHasUpdates_ReturnsTrue() throws Exception {
        // Create and fetch a branch
        remoteGit.checkout().setCreateBranch(true).setName("outdated-branch").call();
        Path file = remoteDir.resolve("outdated.txt");
        Files.writeString(file, "initial\n", StandardCharsets.UTF_8);
        remoteGit.add().addFilepattern("outdated.txt").call();
        remoteGit.commit().setMessage("Initial").call();
        remoteGit.checkout().setName("master").call();

        localRepo.remote().fetchBranch("origin", "outdated-branch");

        // Add new commit to remote
        remoteGit.checkout().setName("outdated-branch").call();
        Files.writeString(file, "updated\n", StandardCharsets.UTF_8);
        remoteGit.add().addFilepattern("outdated.txt").call();
        remoteGit.commit().setMessage("Update").call();
        remoteGit.checkout().setName("master").call();

        // Local is now behind - should need fetch
        assertTrue(localRepo.remote().branchNeedsFetch("origin", "outdated-branch"),
                "Should need fetch when remote has new commits");
    }

    @Test
    void testBranchNeedsFetch_NonExistentBranch_ReturnsFalse() throws Exception {
        // Branch doesn't exist on remote - should return false (nothing to fetch)
        assertFalse(localRepo.remote().branchNeedsFetch("origin", "non-existent-branch"),
                "Should return false for non-existent remote branch");
    }
}
