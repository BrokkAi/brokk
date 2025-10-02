package io.github.jbellis.brokk.git;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
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
        // Create repo with empty token supplier to simulate missing token
        localRepo = new GitRepo(tempDir, () -> "");

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

        // Attempt to push without token should fail with GitHubAuthenticationException
        assertThrows(GitHubAuthenticationException.class, () -> {
            localRepo.pushAndSetRemoteTracking("feature", "origin");
        });
    }

    @Test
    void testPushAndSetRemoteTracking_HttpsWithToken() throws Exception {
        // Create repo with fake token supplier to simulate configured token
        localRepo = new GitRepo(tempDir, () -> "fake-token");

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

        // The push will fail (invalid URL/credentials), but we verify it does NOT throw
        // GitHubAuthenticationException - it should be a different error (network/auth)
        var exception = assertThrows(Exception.class, () -> {
            localRepo.pushAndSetRemoteTracking("feature", "origin");
        });

        // Verify it's NOT GitHubAuthenticationException - token was provided
        assertFalse(
                exception instanceof GitHubAuthenticationException,
                "Should not throw GitHubAuthenticationException when token is configured");
    }

    @Test
    void testPushAndSetRemoteTracking_SshUrlDoesNotRequireToken() throws Exception {
        // Create a new branch with a commit
        localGit.checkout().setCreateBranch(true).setName("feature").call();
        Path featureFile = tempDir.resolve("feature.txt");
        Files.writeString(featureFile, "feature content\n", StandardCharsets.UTF_8);
        localGit.add().addFilepattern("feature.txt").call();
        localGit.commit().setMessage("Add feature").call();

        // Change the remote URL to SSH format
        var config = localGit.getRepository().getConfig();
        config.setString("remote", "origin", "url", "git@github.com:test/repo.git");
        config.save();

        // Verify the remote URL was changed
        assertEquals("git@github.com:test/repo.git", localRepo.getRemoteUrl("origin"));

        // The push will fail (no SSH keys/invalid URL), but it should NOT throw
        // GitHubAuthenticationException - SSH URLs use JGit's default handling
        var exception = assertThrows(Exception.class, () -> {
            localRepo.pushAndSetRemoteTracking("feature", "origin");
        });

        // Verify it's NOT GitHubAuthenticationException
        assertFalse(
                exception instanceof GitHubAuthenticationException,
                "SSH URLs should not throw GitHubAuthenticationException");
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

    @Test
    void testPush_GitHubHttpsWithoutToken() throws Exception {
        // Create repo with empty token supplier to simulate missing token
        localRepo = new GitRepo(tempDir, () -> "");

        // Create a commit on master
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "test content\n", StandardCharsets.UTF_8);
        localGit.add().addFilepattern("test.txt").call();
        localGit.commit().setMessage("Add test file").call();

        // Change remote to GitHub HTTPS
        var config = localGit.getRepository().getConfig();
        config.setString("remote", "origin", "url", "https://github.com/test/repo.git");
        config.save();

        // push() should fail with GitHubAuthenticationException
        assertThrows(GitHubAuthenticationException.class, () -> {
            localRepo.push("master");
        });
    }

    @Test
    void testPull_GitHubHttpsRequiresToken() throws Exception {
        // Create repo with empty token supplier to simulate missing token
        localRepo = new GitRepo(tempDir, () -> "");

        // Change remote to GitHub HTTPS
        var config = localGit.getRepository().getConfig();
        config.setString("remote", "origin", "url", "https://github.com/test/repo.git");
        config.save();

        // pull() should fail with GitHubAuthenticationException
        assertThrows(GitHubAuthenticationException.class, () -> {
            localRepo.pull();
        });
    }

    @Test
    void testClone_GitHubHttpsWithoutToken() throws Exception {
        Path cloneDir = tempDir.resolve("cloned");

        // Attempt to clone GitHub HTTPS URL should fail with GitHubAuthenticationException
        assertThrows(GitHubAuthenticationException.class, () -> {
            GitRepo.cloneRepo(() -> "", "https://github.com/test/repo.git", cloneDir, 0);
        });
    }

    @Test
    void testFetchAll_GitHubHttpsRequiresToken() throws Exception {
        // Create repo with empty token supplier to simulate missing token
        localRepo = new GitRepo(tempDir, () -> "");

        // Change remote to GitHub HTTPS
        var config = localGit.getRepository().getConfig();
        config.setString("remote", "origin", "url", "https://github.com/test/repo.git");
        config.save();

        // fetchAll() should fail with GitHubAuthenticationException
        assertThrows(GitHubAuthenticationException.class, () -> {
            localRepo.fetchAll(org.eclipse.jgit.lib.NullProgressMonitor.INSTANCE);
        });
    }
}
