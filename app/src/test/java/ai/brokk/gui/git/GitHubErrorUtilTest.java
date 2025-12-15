package ai.brokk.gui.git;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.HttpException;

class GitHubErrorUtilTest {

    @Test
    void isNoCommitsBetweenError_matches422WithNoCommitsMessage_noBranches() {
        HttpException ex =
                new HttpException(
                        "Invalid request.\n\nNo commits between master and master",
                        422,
                        "Unprocessable Entity",
                        "https://api.github.com/repos/owner/repo/pulls");

        assertTrue(GitHubErrorUtil.isNoCommitsBetweenError(ex, null, null));
        assertTrue(GitHubErrorUtil.isNoCommitsBetweenError(ex));
    }

    @Test
    void isNoCommitsBetweenError_matches422WithBranches_caseInsensitive() {
        HttpException ex =
                new HttpException(
                        "Invalid request.\n\nNo commits between master and master",
                        422,
                        "Unprocessable Entity",
                        "https://api.github.com/repos/owner/repo/pulls");

        assertTrue(GitHubErrorUtil.isNoCommitsBetweenError(ex, "master", "master"));
        assertTrue(GitHubErrorUtil.isNoCommitsBetweenError(ex, "MaStEr", "MASTER"));
    }

    @Test
    void isNoCommitsBetweenError_falseWhenBranchesDoNotMatch() {
        HttpException ex =
                new HttpException(
                        "Invalid request.\n\nNo commits between master and master",
                        422,
                        "Unprocessable Entity",
                        "https://api.github.com/repos/owner/repo/pulls");

        assertFalse(GitHubErrorUtil.isNoCommitsBetweenError(ex, "main", "develop"));
    }

    @Test
    void isNoCommitsBetweenError_ignoresNon422Status() {
        HttpException ex =
                new HttpException(
                        "Invalid request.\n\nNo commits between master and master",
                        400,
                        "Bad Request",
                        "https://api.github.com/repos/owner/repo/pulls");

        assertFalse(GitHubErrorUtil.isNoCommitsBetweenError(ex, null, null));
    }

    @Test
    void isNoCommitsBetweenError_searchesCauseChain() {
        HttpException httpEx =
                new HttpException(
                        "Invalid request.\n\nNo commits between master and master",
                        422,
                        "Unprocessable Entity",
                        "https://api.github.com/repos/owner/repo/pulls");
        Throwable wrapped = new RuntimeException("wrapper", httpEx);

        assertTrue(GitHubErrorUtil.isNoCommitsBetweenError(wrapped, "master", "master"));
    }

    @Test
    void formatNoCommitsBetweenError_includesBranchesAndNoCommitsPhrase() {
        String base = "base";
        String head = "head";

        String msg = GitHubErrorUtil.formatNoCommitsBetweenError(base, head);
        String lower = msg.toLowerCase(Locale.ROOT);

        assertTrue(lower.contains("no commits"));
        assertTrue(msg.contains(base));
        assertTrue(msg.contains(head));
    }
}
