package io.github.jbellis.brokk.git;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for GitRepo push and authentication functionality. */
public class GitRepoPushTest {

    @TempDir
    Path tempDir;

    @TempDir
    Path remoteDir;

    private GitRepo localRepo;
    private Git localGit;
    private Git remoteGit;

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
        localGit.getRepository().getConfig().setBoolean("commit", null, "gpgsign", false);
        localGit.getRepository().getConfig().save();

        // Create initial commit on master
        Path initialFile = tempDir.resolve("initial.txt");
        Files.writeString(initialFile, "initial content\n", StandardCharsets.UTF_8);
        localGit.add().addFilepattern("initial.txt").call();
        localGit.commit().setMessage("Initial commit").call();
        localGit.push().call();
    }

    @AfterEach
    void tearDown() {
        GitTestCleanupUtil.cleanupGitResources(localRepo, localGit, remoteGit);
    }

    @Test
    void testPushAndSetRemoteTracking_HttpsWithoutToken() throws Exception {
        // This test verifies the error when no GitHub token is configured
        // Check if a token is actually configured in the test environment
        var token = io.github.jbellis.brokk.MainProject.getGitHubToken();

        if (!token.trim().isEmpty()) {
            // Skip this test if a token is configured - we can't test the "no token" path
            // when a token exists in the environment
            return;
        }

        // Create a new branch with a commit
        localGit.checkout().setCreateBranch(true).setName("feature").call();
        Path featureFile = tempDir.resolve("feature.txt");
        Files.writeString(featureFile, "feature content\n", StandardCharsets.UTF_8);
        localGit.add().addFilepattern("feature.txt").call();
        localGit.commit().setMessage("Add feature").call();

        // Change the remote URL to HTTPS (simulating GitHub HTTPS remote)
        var config = localGit.getRepository().getConfig();
        config.setString("remote", "origin", "url", "https://github.com/test/repo.git");
        config.save();

        // Verify the remote URL was changed
        assertEquals("https://github.com/test/repo.git", localRepo.getRemoteUrl("origin"));

        // Attempt to push without token should fail with specific error message
        var exception = assertThrows(GitAPIException.class, () -> {
            localRepo.pushAndSetRemoteTracking("feature", "origin");
        });

        // Verify the error message mentions token requirement
        assertTrue(
                exception.getMessage().contains("GitHub token is required"),
                "Exception should mention token requirement, got: " + exception.getMessage());
        assertTrue(
                exception.getMessage().contains("Settings → Global → GitHub"),
                "Exception should guide user to settings");
    }

    @Test
    void testPushAndSetRemoteTracking_HttpsWithToken() throws Exception {
        // This test verifies that HTTPS authentication is attempted when a token is configured
        var token = io.github.jbellis.brokk.MainProject.getGitHubToken();

        if (token.trim().isEmpty()) {
            // Skip this test if no token is configured - we can't test the "with token" path
            return;
        }

        // Create a new branch with a commit
        localGit.checkout().setCreateBranch(true).setName("feature").call();
        Path featureFile = tempDir.resolve("feature.txt");
        Files.writeString(featureFile, "feature content\n", StandardCharsets.UTF_8);
        localGit.add().addFilepattern("feature.txt").call();
        localGit.commit().setMessage("Add feature").call();

        // Change the remote URL to HTTPS
        var config = localGit.getRepository().getConfig();
        config.setString("remote", "origin", "url", "https://github.com/test/repo.git");
        config.save();

        // The push will fail (invalid URL/credentials), but we verify it attempts
        // authentication and doesn't throw the "token required" error
        var exception = assertThrows(Exception.class, () -> {
            localRepo.pushAndSetRemoteTracking("feature", "origin");
        });

        // Verify it's NOT the token required error - it should be a network/auth error
        assertFalse(
                exception.getMessage().contains("GitHub token is required"),
                "Should not get 'token required' error when token is configured. Got: " + exception.getMessage());
    }

    @Test
    void testPushAndSetRemoteTracking_FileProtocolSucceeds() throws Exception {
        // Create a new branch with a commit
        localGit.checkout().setCreateBranch(true).setName("feature").call();
        Path featureFile = tempDir.resolve("feature.txt");
        Files.writeString(featureFile, "feature content\n", StandardCharsets.UTF_8);
        localGit.add().addFilepattern("feature.txt").call();
        localGit.commit().setMessage("Add feature").call();

        // With file:// protocol (default from setUp), push should succeed
        localRepo.pushAndSetRemoteTracking("feature", "origin");

        // Verify upstream tracking was set
        var repoConfig = localGit.getRepository().getConfig();
        assertEquals("origin", repoConfig.getString("branch", "feature", "remote"));
        assertEquals("refs/heads/feature", repoConfig.getString("branch", "feature", "merge"));
    }
}
