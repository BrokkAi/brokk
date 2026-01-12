package ai.brokk.executor.jobs;

import ai.brokk.git.GitRepo;
import java.io.IOException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Blocking;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;

/**
 * Helper class for GitHub PR operations used by the executor.
 *
 * <p>All methods are blocking and perform network I/O or git operations.
 */
public final class PrReviewService {
    private PrReviewService() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** PR metadata extracted from GitHub API. */
    public record PrDetails(String baseBranch, String headSha, String headRef) {}

    /**
     * Fetches PR details from GitHub.
     *
     * @param repo the GitHub repository
     * @param prNumber the pull request number
     * @return PR details including base branch, head SHA, and head ref
     * @throws IOException if the GitHub API call fails
     */
    @Blocking
    public static PrDetails fetchPrDetails(GHRepository repo, int prNumber) throws IOException {
        GHPullRequest pr = repo.getPullRequest(prNumber);
        String baseBranch = pr.getBase().getRef();
        String headSha = pr.getHead().getSha();
        String headRef = pr.getHead().getRef();
        return new PrDetails(baseBranch, headSha, headRef);
    }

    /**
     * Computes the diff between the current branch and the base branch.
     *
     * @param repo the git repository
     * @param baseBranch the base branch to diff against
     * @return the annotated diff string with file headers
     * @throws IllegalStateException if no merge-base exists between branches
     * @throws GitAPIException if git operations fail
     */
    @Blocking
    public static String computePrDiff(GitRepo repo, String baseBranch) throws GitAPIException {
        String currentBranch = repo.getCurrentBranch();
        String mergeBase = repo.getMergeBase(baseBranch, currentBranch);
        if (mergeBase == null) {
            throw new IllegalStateException(
                    "No merge-base found between base branch '"
                            + baseBranch
                            + "' and current branch '"
                            + currentBranch
                            + "'");
        }
        return repo.getDiff(mergeBase, currentBranch);
    }

    /**
     * Posts a review comment on the pull request.
     *
     * @param pr the GitHub pull request
     * @param body the comment body in markdown
     * @throws IOException if the GitHub API call fails
     */
    @Blocking
    public static void postReviewComment(GHPullRequest pr, String body) throws IOException {
        pr.comment(body);
    }
}
