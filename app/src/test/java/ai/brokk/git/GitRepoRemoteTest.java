package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;

/** Tests for GitRepoRemote utility methods and RefSpec construction. */
public class GitRepoRemoteTest {

    @Test
    void testFetchPrRefSpec_Construction() {
        // Verify the RefSpec format for PR fetching matches GitHub's convention
        int prNumber = 123;
        String remoteName = "origin";

        // Expected format: +refs/pull/{prNumber}/head:refs/remotes/{remoteName}/pr/{prNumber}
        String expectedSource = "refs/pull/" + prNumber + "/head";
        String expectedDestination = "refs/remotes/" + remoteName + "/pr/" + prNumber;
        String expectedRefSpec = "+" + expectedSource + ":" + expectedDestination;

        var refSpec = new RefSpec(expectedRefSpec);

        assertTrue(refSpec.isForceUpdate(), "RefSpec should be a force update (+)");
        assertEquals(expectedSource, refSpec.getSource(), "Source should be refs/pull/{prNumber}/head");
        assertEquals(
                expectedDestination,
                refSpec.getDestination(),
                "Destination should be refs/remotes/{remoteName}/pr/{prNumber}");
    }

    @Test
    void testFetchPrRefSpec_DifferentRemote() {
        // Verify RefSpec construction works with non-origin remotes
        int prNumber = 456;
        String remoteName = "upstream";

        String expectedRefSpec = "+refs/pull/" + prNumber + "/head:refs/remotes/" + remoteName + "/pr/" + prNumber;
        var refSpec = new RefSpec(expectedRefSpec);

        assertTrue(refSpec.isForceUpdate());
        assertEquals("refs/pull/" + prNumber + "/head", refSpec.getSource());
        assertEquals("refs/remotes/" + remoteName + "/pr/" + prNumber, refSpec.getDestination());
    }

    @Test
    void testRemoteBranchRef_Parse() {
        var parsed = GitRepoRemote.RemoteBranchRef.parse("origin/feature-branch");
        assertNotNull(parsed);
        assertEquals("origin", parsed.remoteName());
        assertEquals("feature-branch", parsed.branchName());
    }

    @Test
    void testRemoteBranchRef_ParseNoSlash() {
        var parsed = GitRepoRemote.RemoteBranchRef.parse("invalid");
        assertNull(parsed, "Should return null when no slash present");
    }

    @Test
    void testRemoteBranchRef_ParseEmptyRemote() {
        var parsed = GitRepoRemote.RemoteBranchRef.parse("/branch");
        assertNull(parsed, "Should return null when remote name is empty");
    }
}
