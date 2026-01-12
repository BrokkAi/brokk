package ai.brokk.executor.jobs;

import ai.brokk.git.GitRepo;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Blocking;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestReviewComment;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.HttpException;

/**
 * Helper class for GitHub PR operations used by the executor.
 *
 * <p>All methods are blocking and perform network I/O or git operations.
 */
public final class PrReviewService {
    private static final Logger logger = LogManager.getLogger(PrReviewService.class);
    private static final Pattern HUNK_PATTERN = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

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
     * Computes the diff between the base branch and the PR head ref.
     *
     * @param repo the git repository
     * @param baseBranch the base branch to diff against (e.g., "main", "origin/main")
     * @param headRef the PR head ref or SHA to diff from
     * @return the annotated diff string with file headers
     * @throws IllegalStateException if no merge-base exists between branches
     * @throws GitAPIException if git operations fail
     */
    @Blocking
    public static String computePrDiff(GitRepo repo, String baseBranch, String headRef) throws GitAPIException {
        String mergeBase = repo.getMergeBase(baseBranch, headRef);
        if (mergeBase == null) {
            throw new IllegalStateException(
                    "No merge-base found between base branch '" + baseBranch + "' and head ref '" + headRef + "'");
        }
        return repo.getDiff(mergeBase, headRef);
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

    /**
     * Posts an inline review comment on a specific line of a file in the pull request.
     *
     * <p>If the inline comment fails with HTTP 422 (e.g., line not part of the diff), falls back
     * to posting a regular PR comment with file and line context.
     *
     * @param pr the GitHub pull request
     * @param path the file path relative to repository root
     * @param line the line number in the file (1-indexed)
     * @param body the comment body in markdown
     * @param commitId the commit SHA to comment on
     * @throws IOException if the GitHub API call fails (other than HTTP 422)
     */
    @Blocking
    public static void postLineComment(GHPullRequest pr, String path, int line, String body, String commitId)
            throws IOException {
        try {
            pr.createReviewComment()
                    .body(body)
                    .commitId(commitId)
                    .path(path)
                    .line(line)
                    .create();
            logger.info("Posted inline comment on {}:{} in PR #{}", path, line, pr.getNumber());
        } catch (HttpException e) {
            if (e.getResponseCode() == 422) {
                logger.warn(
                        "Failed to post inline comment on {}:{} (HTTP 422), falling back to regular comment",
                        path,
                        line);
                String fallbackBody = String.format("**Comment on `%s` line %d:**\n\n%s", path, line, body);
                pr.comment(fallbackBody);
                logger.info("Posted fallback comment for {}:{} in PR #{}", path, line, pr.getNumber());
            } else {
                throw e;
            }
        }
    }

    /**
     * Checks if an existing review comment already exists on the specified line of a file.
     *
     * @param pr the GitHub pull request
     * @param path the file path relative to repository root
     * @param line the line number in the file (1-indexed)
     * @return true if a comment already exists on that line, false otherwise
     * @throws IOException if the GitHub API call fails
     */
    @Blocking
    public static boolean hasExistingLineComment(GHPullRequest pr, String path, int line) throws IOException {
        for (GHPullRequestReviewComment comment : pr.listReviewComments()) {
            if (path.equals(comment.getPath()) && line == comment.getLine()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Annotates a unified diff with explicit line numbers for LLM review.
     *
     * <p>Transforms standard unified diff format into an annotated format where each content line
     * is prefixed with [OLD:N NEW:N] markers showing the exact line numbers:
     *
     * <ul>
     *   <li>Context lines: [OLD:N NEW:M] (both line numbers)</li>
     *   <li>Added lines: [OLD:- NEW:N] (only new line number)</li>
     *   <li>Removed lines: [OLD:N NEW:-] (only old line number)</li>
     * </ul>
     *
     * <p>File headers (diff --git, ---, +++, index) are preserved as-is without annotation.
     *
     * @param unifiedDiff the standard unified diff string
     * @return the annotated diff with line number prefixes
     */
    public static String annotateDiffWithLineNumbers(String unifiedDiff) {
        if (unifiedDiff.isEmpty()) {
            return "";
        }

        String[] lines = unifiedDiff.split("\n", -1);
        StringBuilder result = new StringBuilder();
        int oldLine = 0;
        int newLine = 0;

        for (String line : lines) {
            if (line.startsWith("diff --git")
                    || line.startsWith("---")
                    || line.startsWith("+++")
                    || line.startsWith("index ")) {
                result.append(line).append('\n');
            } else if (line.startsWith("@@")) {
                Matcher matcher = HUNK_PATTERN.matcher(line);
                if (matcher.find()) {
                    oldLine = Integer.parseInt(matcher.group(1));
                    newLine = Integer.parseInt(matcher.group(2));
                }
                result.append(line).append('\n');
            } else if (line.startsWith(" ")) {
                result.append("[OLD:")
                        .append(oldLine)
                        .append(" NEW:")
                        .append(newLine)
                        .append("] ")
                        .append(line)
                        .append('\n');
                oldLine++;
                newLine++;
            } else if (line.startsWith("+")) {
                result.append("[OLD:- NEW:")
                        .append(newLine)
                        .append("] ")
                        .append(line)
                        .append('\n');
                newLine++;
            } else if (line.startsWith("-")) {
                result.append("[OLD:")
                        .append(oldLine)
                        .append(" NEW:-] ")
                        .append(line)
                        .append('\n');
                oldLine++;
            } else {
                result.append(line).append('\n');
            }
        }

        if (result.length() > 0 && result.charAt(result.length() - 1) == '\n') {
            result.setLength(result.length() - 1);
        }

        return result.toString();
    }
}
