package ai.brokk.executor;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jetbrains.annotations.Nullable;

/**
 * Response model for POST /v1/issues/{issue_number}/fix endpoint.
 * Returns job tracking information and pull request details on success.
 */
public record IssueFixResponse(
        @JsonProperty("jobId") String jobId,
        @JsonProperty("state") String state,
        @JsonProperty("issue") IssueInfo issue,
        @JsonProperty("worktreeBranch") String worktreeBranch,
        @JsonProperty("prUrl") @Nullable String prUrl) {

    /**
     * Issue information returned in the response.
     */
    public record IssueInfo(
            @JsonProperty("number") int number,
            @JsonProperty("title") String title,
            @JsonProperty("url") String url) {}

    /**
     * Create a response for a newly created job.
     */
    public static IssueFixResponse created(
            String jobId, int issueNumber, String issueTitle, String issueUrl, String branchName) {
        return new IssueFixResponse(
                jobId,
                "queued",
                new IssueInfo(issueNumber, issueTitle, issueUrl),
                branchName,
                null);
    }

    /**
     * Create a response indicating PR creation success.
     */
    public IssueFixResponse withPrUrl(String prUrl) {
        return new IssueFixResponse(jobId, "completed", issue, worktreeBranch, prUrl);
    }
}
