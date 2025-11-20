package ai.brokk.executor;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Request model for POST /v1/issues/{issue_number}/fix endpoint.
 * Encapsulates repository identification, issue details, and authentication.
 */
public record IssueFixRequest(
        @JsonProperty("owner") String owner,
        @JsonProperty("repo") String repo,
        @JsonProperty("issueNumber") int issueNumber,
        @JsonProperty("githubToken") @Nullable String githubToken,
        @JsonProperty("autoCommit") boolean autoCommit,
        @JsonProperty("autoCompress") boolean autoCompress,
        @JsonProperty("plannerModel") String plannerModel,
        @JsonProperty("codeModel") @Nullable String codeModel,
        @JsonProperty("tags") @Nullable Map<String, String> tags) {

    public IssueFixRequest {
        // Validation happens during endpoint processing
    }

    /**
     * Create a minimal request with required fields only.
     */
    public static IssueFixRequest minimal(String owner, String repo, int issueNumber, String plannerModel) {
        return new IssueFixRequest(owner, repo, issueNumber, null, false, false, plannerModel, null, null);
    }
}
