package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jgit.api.errors.TransportException;
import org.junit.jupiter.api.Test;

public class GitRepoPermissionDeniedTest {

    @Test
    public void testIsGitHubPermissionDenied_HttpsReceivePackNotPermitted() {
        var ex = new TransportException("git-receive-pack not permitted");
        assertTrue(GitRepo.isGitHubPermissionDenied(ex));
    }

    @Test
    public void testIsGitHubPermissionDenied_Https403() {
        var ex = new TransportException("403 Forbidden");
        assertTrue(GitRepo.isGitHubPermissionDenied(ex));
    }

    @Test
    public void testIsGitHubPermissionDenied_Https401() {
        var ex = new TransportException("401 Unauthorized");
        assertTrue(GitRepo.isGitHubPermissionDenied(ex));
    }

    @Test
    public void testIsGitHubPermissionDenied_SshPermissionDenied() {
        var ex = new TransportException("Permission to user/repo denied");
        assertTrue(GitRepo.isGitHubPermissionDenied(ex));
    }

    @Test
    public void testIsGitHubPermissionDenied_InCauseChain() {
        var cause = new TransportException("403 Forbidden");
        var ex = new TransportException("network error", cause);
        assertTrue(GitRepo.isGitHubPermissionDenied(ex));
    }

    @Test
    public void testIsGitHubPermissionDenied_NegativeCases() {
        var ex = new TransportException("temporary failure in name resolution");
        assertFalse(GitRepo.isGitHubPermissionDenied(ex));
    }
}
