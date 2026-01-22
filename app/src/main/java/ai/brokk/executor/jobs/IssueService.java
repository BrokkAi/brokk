package ai.brokk.executor.jobs;

import ai.brokk.agents.BuildAgent;
import ai.brokk.git.GitRepo;
import ai.brokk.util.Json;
import java.io.IOException;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHRepository;

/**
 * Helper class for GitHub Issue operations used by the executor.
 *
 * <p>Methods performing network I/O are marked as {@link Blocking}.
 */
public final class IssueService {
    private static final Logger logger = LogManager.getLogger(IssueService.class);

    private IssueService() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Issue metadata extracted from GitHub API. */
    public record IssueDetails(String title, String body, String author, String state) {}

    /**
     * Fetches issue details from GitHub.
     *
     * @param repo the GitHub repository
     * @param issueNumber the issue number
     * @return Issue details including title, body, author, and state
     * @throws IOException if the GitHub API call fails
     */
    @Blocking
    public static IssueDetails fetchIssueDetails(GHRepository repo, int issueNumber) throws IOException {
        GHIssue issue = repo.getIssue(issueNumber);
        String title = Objects.requireNonNullElse(issue.getTitle(), "");
        String body = Objects.requireNonNullElse(issue.getBody(), "");
        String author = issue.getUser() != null ? issue.getUser().getLogin() : "unknown";
        String state = issue.getState() != null ? issue.getState().toString() : "unknown";

        return new IssueDetails(title, body, author, state);
    }

    /**
     * Deserializes a JSON string into {@link BuildAgent.BuildDetails}.
     *
     * @param buildSettingsJson the JSON string representing build settings
     * @return the deserialized BuildDetails, or {@link BuildAgent.BuildDetails#EMPTY} if input is null or blank
     * @throws RuntimeException if deserialization fails
     */
    public static BuildAgent.BuildDetails parseBuildSettings(@Nullable String buildSettingsJson) {
        if (buildSettingsJson == null || buildSettingsJson.isBlank()) {
            return BuildAgent.BuildDetails.EMPTY;
        }

        try {
            return Json.fromJson(buildSettingsJson, BuildAgent.BuildDetails.class);
        } catch (Exception e) {
            logger.error("Failed to parse build settings JSON: {}", buildSettingsJson, e);
            throw new IssueExecutionException("Error parsing build settings", e);
        }
    }

    /**
     * Build a pull request description by combining an optional summary with a Fixes line
     * referencing the issue number.
     *
     * <p>Behavior:
     * - Treat null summaryMarkdown as empty.
     * - Trim leading/trailing whitespace from summaryMarkdown.
     * - If the resulting summary is empty, return only "Fixes #<issueNumber>".
     * - If non-empty, return summary + two newlines + "Fixes #<issueNumber>".
     */
    public static String buildPrDescription(@Nullable String summaryMarkdown, int issueNumber) {
        String summary = summaryMarkdown == null ? "" : summaryMarkdown.strip();
        String fixes = "Fixes #" + issueNumber;
        return summary.isEmpty() ? fixes : summary + "\n\n" + fixes;
    }

    /**
     * Generates a sanitized and unique branch name for an issue.
     *
     * @param issueNumber the GitHub issue number
     * @param repo the Git repository to check for existing branches
     * @return a sanitized branch name like "brokk/issue-42" (or "brokk/issue-42-2" if "brokk/issue-42" exists)
     * @throws GitAPIException if checking existing branches fails
     */
    public static String generateBranchName(int issueNumber, GitRepo repo) throws GitAPIException {
        String proposedName = "brokk/issue-" + issueNumber;
        return repo.sanitizeBranchName(proposedName);
    }

}
