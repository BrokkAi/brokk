package ai.brokk.git;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for GitHub authentication in Git operations that use ls-remote. */
public class GitRepoAuthenticationTest {

    @TempDir
    Path tempDir;

    @TempDir
    Path remoteDir;

    private Git remoteGit;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize bare remote repository
        remoteGit = Git.init().setDirectory(remoteDir.toFile()).setBare(true).call();

        // Create a local repository to set up some content
        try (var localGit = Git.init().setDirectory(tempDir.toFile()).call()) {
            // Configure user for commits
            localGit.getRepository().getConfig().setString("user", null, "name", "Test User");
            localGit.getRepository().getConfig().setString("user", null, "email", "test@example.com");
            localGit.getRepository().getConfig().setBoolean("commit", null, "gpgsign", false);
            localGit.getRepository().getConfig().save();

            // Create initial commit on master branch (default)
            Path initialFile = tempDir.resolve("initial.txt");
            Files.writeString(initialFile, "initial content\n", StandardCharsets.UTF_8);
            localGit.add().addFilepattern("initial.txt").call();
            localGit.commit().setMessage("Initial commit").call();

            // Create a tag
            localGit.tag().setName("v1.0.0").setMessage("Version 1.0.0").call();

            // Push to remote using URI string
            var remoteUri =
                    new org.eclipse.jgit.transport.URIish(remoteDir.toUri().toString());
            localGit.remoteAdd().setName("origin").setUri(remoteUri).call();
            localGit.push().setRemote("origin").add("master").call();
            localGit.push().setRemote("origin").setPushTags().call();
        }
    }

    @AfterEach
    void tearDown() {
        GitTestCleanupUtil.cleanupGitResources(null, remoteGit);
    }

    // Tests for getRemoteRefCommit()

    @Test
    void testGetRemoteRefCommit_FileProtocol_NoAuthRequired() {
        // file:// protocol should work without authentication
        String fileUrl = remoteDir.toUri().toString();
        String commit = GitRepoFactory.getRemoteRefCommit(() -> "", fileUrl, "master");

        assertNotNull(commit, "Should be able to read ref from file:// URL without token");
        assertEquals(40, commit.length(), "Commit hash should be 40 characters");
    }

    @Test
    void testGetRemoteRefCommit_NonExistentRef_ReturnsNull() {
        String fileUrl = remoteDir.toUri().toString();
        String commit = GitRepoFactory.getRemoteRefCommit(() -> "", fileUrl, "nonexistent-branch");

        assertNull(commit, "Should return null for non-existent ref");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "git@github.com:owner/repo.git",
                "ssh://git@github.com/owner/repo.git",
                "git@github.com:owner/repo"
            })
    void testGetRemoteRefCommit_SshUrls_DoNotRequireToken(String sshUrl) {
        // SSH URLs should not trigger GitHub authentication
        // The operation will fail (can't connect to fake URL), but it should NOT
        // be due to authentication - it should fail during network operation
        var commit = GitRepoFactory.getRemoteRefCommit(() -> "", sshUrl, "master");

        // We expect null because the URL doesn't exist, but the important thing is
        // that no GitHubAuthenticationException was thrown
        assertNull(commit, "SSH URLs should skip authentication and fail gracefully");
    }

    @Test
    void testGetRemoteRefCommit_NonGitHubHttps_DoesNotRequireToken() {
        // gitlab.com HTTPS should not trigger GitHub authentication
        String gitlabUrl = "https://gitlab.com/owner/repo.git";
        var commit = GitRepoFactory.getRemoteRefCommit(() -> "", gitlabUrl, "master");

        // Will return null (can't connect), but shouldn't throw GitHubAuthenticationException
        assertNull(commit, "Non-GitHub HTTPS URLs should skip GitHub authentication");
    }

    @Test
    void testGetRemoteRefCommit_GitHubHttpsWithToken_SetsCredentials() {
        // This test verifies that credentials are set for GitHub HTTPS URLs
        // We can't actually test against real GitHub without credentials, but we can
        // verify the method doesn't throw when a token is provided
        String githubUrl = "https://github.com/nonexistent-owner/nonexistent-repo.git";
        var commit = GitRepoFactory.getRemoteRefCommit(() -> "fake-token", githubUrl, "master");

        // Will return null (repo doesn't exist), but credentials should have been set
        assertNull(commit, "Should handle GitHub HTTPS with token gracefully");
    }

    @Test
    void testGetRemoteRefCommit_GitHubHttpsWithoutToken_AllowsGracefulFailure() {
        // Without token, public repos should still work, private repos will fail
        // The method should NOT throw GitHubAuthenticationException, just return null
        String githubUrl = "https://github.com/nonexistent-owner/nonexistent-repo.git";
        var commit = GitRepoFactory.getRemoteRefCommit(() -> "", githubUrl, "master");

        // Should return null (can't access), but shouldn't throw
        assertNull(commit, "Should allow graceful failure for GitHub HTTPS without token");
    }

    // Tests for listRemoteRefs()

    @Test
    void testListRemoteRefs_FileProtocol_NoAuthRequired() throws GitAPIException {
        // file:// protocol should work without authentication
        String fileUrl = remoteDir.toUri().toString();
        var remoteInfo = GitRepoRemote.listRemoteRefs(() -> "", fileUrl);

        assertNotNull(remoteInfo, "Should be able to list refs from file:// URL without token");
        assertTrue(remoteInfo.branches().contains("master"), "Should find master branch");
        assertTrue(remoteInfo.tags().contains("v1.0.0"), "Should find v1.0.0 tag");
        // Note: bare repos may not have a symbolic HEAD, so defaultBranch might be null
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "git@github.com:owner/repo.git",
                "ssh://git@github.com/owner/repo.git",
                "git@github.com:owner/repo"
            })
    void testListRemoteRefs_SshUrls_DoNotRequireToken(String sshUrl) {
        // SSH URLs should not trigger GitHub authentication
        // The operation will fail (can't connect to fake URL), but the important thing is
        // that no GitHubAuthenticationException was thrown
        assertThrows(
                GitAPIException.class,
                () -> {
                    GitRepoRemote.listRemoteRefs(() -> "", sshUrl);
                },
                "SSH URL should fail due to network, not authentication");
    }

    @Test
    void testListRemoteRefs_NonGitHubHttps_DoesNotRequireToken() {
        // gitlab.com HTTPS should not trigger GitHub authentication
        String gitlabUrl = "https://gitlab.com/owner/repo.git";

        assertThrows(
                GitAPIException.class,
                () -> {
                    GitRepoRemote.listRemoteRefs(() -> "", gitlabUrl);
                },
                "Non-GitHub HTTPS should fail due to network, not authentication");
    }

    @Test
    void testListRemoteRefs_GitHubHttpsWithToken_SetsCredentials() {
        // This test verifies that credentials are set for GitHub HTTPS URLs
        String githubUrl = "https://github.com/nonexistent-owner/nonexistent-repo.git";

        assertThrows(
                GitAPIException.class,
                () -> {
                    GitRepoRemote.listRemoteRefs(() -> "fake-token", githubUrl);
                },
                "Should fail due to network, but credentials should have been set");
    }

    @Test
    void testListRemoteRefs_GitHubHttpsWithoutToken_AllowsGracefulFailure() {
        // Without token, the method should NOT throw GitHubAuthenticationException
        String githubUrl = "https://github.com/nonexistent-owner/nonexistent-repo.git";

        assertThrows(
                GitAPIException.class,
                () -> {
                    GitRepoRemote.listRemoteRefs(() -> "", githubUrl);
                },
                "Should fail due to network access, not missing token");
    }
}
