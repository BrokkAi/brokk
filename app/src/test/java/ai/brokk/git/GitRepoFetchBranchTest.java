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
}
