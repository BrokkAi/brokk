package ai.brokk.executor.jobs;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Represents the input specification for a job.
 * This is the request payload and is persisted to meta.json for audit/replay.
 *
 * <p>The {@code plannerModel} field is required by the API and is used for ASK and ARCHITECT jobs.
 * The {@code codeModel} field is optional and, when supplied, is used for CODE and ARCHITECT jobs.</p>
 */
public record JobSpec(
        @JsonProperty("taskInput") String taskInput,
        @JsonProperty("autoCommit") boolean autoCommit,
        @JsonProperty("autoCompress") boolean autoCompress,
        @JsonProperty("plannerModel") String plannerModel,
        @JsonProperty("scanModel") @Nullable String scanModel,
        @JsonProperty("codeModel") @Nullable String codeModel,
        @JsonProperty("preScan") boolean preScan,
        @JsonProperty("tags") Map<String, String> tags,
        @JsonProperty("sourceBranch") @Nullable String sourceBranch,
        @JsonProperty("targetBranch") @Nullable String targetBranch) {

    /**
     * Tag keys that contain sensitive data and should not be persisted to disk.
     */
    private static final Set<String> SENSITIVE_TAG_KEYS = Set.of("github_token");

    /**
     * Returns a copy of tags with sensitive values redacted for safe persistence/logging.
     * Sensitive keys are replaced with "[REDACTED]" rather than removed entirely,
     * to preserve the structure for debugging while protecting the actual values.
     */
    public Map<String, String> redactedTags() {
        var result = new HashMap<>(tags);
        for (var key : SENSITIVE_TAG_KEYS) {
            if (result.containsKey(key)) {
                result.put(key, "[REDACTED]");
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Creates a JobSpec with minimal required fields.
     *
     * <p>This convenience factory uses sensible defaults for optional flags and sets {@code preScan} to {@code false}.</p>
     */
    public static JobSpec of(String taskInput, String plannerModel) {
        return new JobSpec(taskInput, true, true, plannerModel, null, null, false, Map.of(), null, null);
    }

    /**
     * Creates a JobSpec for PR review jobs.
     *
     * <p>This factory creates a job with empty taskInput and stores PR metadata in tags.
     * Auto-commit and auto-compress are disabled for PR review jobs.</p>
     */
    public static JobSpec ofPrReview(String plannerModel, String githubToken, String owner, String repo, int prNumber) {
        return new JobSpec(
                "",
                false,
                false,
                plannerModel,
                null,
                null,
                false,
                Map.of(
                        "github_token", githubToken,
                        "repo_owner", owner,
                        "repo_name", repo,
                        "pr_number", String.valueOf(prNumber)),
                null,
                null);
    }

    /**
     * Creates a JobSpec for Issue remediation jobs.
     *
     * <p>This factory creates a job with empty taskInput and stores Issue metadata in tags.
     * Auto-commit and auto-compress are disabled for Issue remediation jobs.</p>
     */
    public static JobSpec ofIssue(
            String plannerModel,
            @Nullable String codeModel,
            String githubToken,
            String owner,
            String repo,
            int issueNumber,
            String buildSettingsJson) {
        return new JobSpec(
                "",
                false,
                false,
                plannerModel,
                null,
                codeModel,
                false,
                Map.of(
                        "mode", "ISSUE",
                        "github_token", githubToken,
                        "repo_owner", owner,
                        "repo_name", repo,
                        "issue_number", String.valueOf(issueNumber),
                        "build_settings", buildSettingsJson),
                null,
                null);
    }

    /**
     * Creates a JobSpec with all fields except branch parameters (for backward compatibility).
     */
    public static JobSpec of(
            String taskInput,
            boolean autoCommit,
            boolean autoCompress,
            String plannerModel,
            @Nullable String scanModel,
            @Nullable String codeModel,
            boolean preScan,
            Map<String, String> tags) {
        return new JobSpec(
                taskInput, autoCommit, autoCompress, plannerModel, scanModel, codeModel, preScan, tags, null, null);
    }

    /**
     * Creates a JobSpec with all fields, including scanModel, preScan flag, and branch parameters.
     */
    public static JobSpec of(
            String taskInput,
            boolean autoCommit,
            boolean autoCompress,
            String plannerModel,
            @Nullable String scanModel,
            @Nullable String codeModel,
            boolean preScan,
            Map<String, String> tags,
            @Nullable String sourceBranch,
            @Nullable String targetBranch) {
        return new JobSpec(
                taskInput,
                autoCommit,
                autoCompress,
                plannerModel,
                scanModel,
                codeModel,
                preScan,
                tags,
                sourceBranch,
                targetBranch);
    }

    @JsonIgnore
    @Nullable
    public String getGithubToken() {
        return tags.get("github_token");
    }

    @JsonIgnore
    @Nullable
    public String getRepoOwner() {
        return tags.get("repo_owner");
    }

    @JsonIgnore
    @Nullable
    public String getRepoName() {
        return tags.get("repo_name");
    }

    @JsonIgnore
    @Nullable
    public Integer getPrNumber() {
        var prNumberStr = tags.get("pr_number");
        if (prNumberStr == null) {
            return null;
        }
        try {
            return Integer.parseInt(prNumberStr);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @JsonIgnore
    @Nullable
    public Integer getIssueNumber() {
        var issueNumberStr = tags.get("issue_number");
        if (issueNumberStr == null) {
            return null;
        }
        try {
            return Integer.parseInt(issueNumberStr);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @JsonIgnore
    @Nullable
    public String getBuildSettingsJson() {
        return tags.get("build_settings");
    }
}
