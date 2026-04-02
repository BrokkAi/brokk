package ai.brokk.gui.git;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.HttpException;

class GitHubErrorUtilTest {

    @Test
    void isNoCommitsBetweenError_matches422WithNoCommitsMessage() {
        var ex = new HttpException(
                "Invalid request.\n\nNo commits between master and master",
                422,
                "Unprocessable Entity",
                "https://api.github.com/repos/owner/repo/pulls");

        assertTrue(GitHubErrorUtil.isNoCommitsBetweenError(ex));
    }

    @Test
    void isNoCommitsBetweenError_ignoresNon422Status() {
        var ex = new HttpException(
                "Invalid request.\n\nNo commits between master and master",
                400,
                "Bad Request",
                "https://api.github.com/repos/owner/repo/pulls");

        assertFalse(GitHubErrorUtil.isNoCommitsBetweenError(ex));
    }

    @Test
    void isNoCommitsBetweenError_searchesCauseChain() {
        var httpEx = new HttpException(
                "Invalid request.\n\nNo commits between master and master",
                422,
                "Unprocessable Entity",
                "https://api.github.com/repos/owner/repo/pulls");
        var wrapped = new RuntimeException("wrapper", httpEx);

        assertTrue(GitHubErrorUtil.isNoCommitsBetweenError(wrapped));
    }

    @Test
    void formatNoCommitsBetweenError_includesBranchesAndNoCommitsPhrase() {
        var base = "base";
        var head = "head";

        var msg = GitHubErrorUtil.formatNoCommitsBetweenError(base, head);
        var lower = msg.toLowerCase(Locale.ROOT);

        assertTrue(lower.contains("no commits"));
        assertTrue(msg.contains(base));
        assertTrue(msg.contains(head));
    }
}
