package ai.brokk.executor.jobs;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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
        @JsonProperty("targetBranch") @Nullable String targetBranch,
        @JsonProperty("reasoningLevel") @Nullable String reasoningLevel,
        @JsonProperty("reasoningLevelCode") @Nullable String reasoningLevelCode,
        @JsonProperty("temperature") @Nullable Double temperature,
        @JsonProperty("temperatureCode") @Nullable Double temperatureCode,
        @JsonProperty("maxIssueFixAttempts") @Nullable Integer maxIssueFixAttempts) {

    public static final int DEFAULT_MAX_ISSUE_FIX_ATTEMPTS = 20;

    public record ModelOverrides(
            @Nullable String reasoningLevel,
            @Nullable String reasoningLevelCode,
            @Nullable Double temperature,
            @Nullable Double temperatureCode) {}

    /**
     * Tag keys that contain sensitive data and should not be persisted to disk.
     */
    private static final Set<String> SENSITIVE_TAG_KEYS = Set.of("github_token");

    /**
     * Returns a copy of tags with sensitive values redacted for safe persistence/logging.
     * Sensitive keys are replaced with "[REDACTED]" rather than removed entirely,
     * to preserve the structure for debugging while protecting the actual values.
     */
    public JobSpec(
            @JsonProperty("taskInput") String taskInput,
            @JsonProperty("autoCommit") boolean autoCommit,
            @JsonProperty("autoCompress") boolean autoCompress,
            @JsonProperty("plannerModel") String plannerModel,
            @JsonProperty("scanModel") @Nullable String scanModel,
            @JsonProperty("codeModel") @Nullable String codeModel,
            @JsonProperty("preScan") boolean preScan,
            @JsonProperty("tags") @Nullable Map<String, String> tags,
            @JsonProperty("sourceBranch") @Nullable String sourceBranch,
            @JsonProperty("targetBranch") @Nullable String targetBranch,
            @JsonProperty("reasoningLevel") @Nullable String reasoningLevel,
            @JsonProperty("reasoningLevelCode") @Nullable String reasoningLevelCode,
            @JsonProperty("temperature") @Nullable Double temperature,
            @JsonProperty("temperatureCode") @Nullable Double temperatureCode,
            @JsonProperty("maxIssueFixAttempts") @Nullable Integer maxIssueFixAttempts) {
        this.taskInput = taskInput;
        this.autoCommit = autoCommit;
        this.autoCompress = autoCompress;
        this.plannerModel = plannerModel;
        this.scanModel = scanModel;
        this.codeModel = codeModel;
        this.preScan = preScan;
        this.tags = tags == null ? Map.of() : Map.copyOf(tags);
        this.sourceBranch = sourceBranch;
        this.targetBranch = targetBranch;
        this.reasoningLevel = reasoningLevel;
        this.reasoningLevelCode = reasoningLevelCode;
        this.temperature = temperature;
        this.temperatureCode = temperatureCode;
        this.maxIssueFixAttempts = maxIssueFixAttempts;
    }

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
        return new JobSpec(
                taskInput,
                true,
                true,
                plannerModel,
                null,
                null,
                false,
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);
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
                null,
                null,
                null,
                null,
                null,
                DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);
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
            @Nullable String buildSettingsJson) {
        return ofIssue(
                plannerModel,
                codeModel,
                githubToken,
                owner,
                repo,
                issueNumber,
                buildSettingsJson,
                DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);
    }

    public static JobSpec ofIssue(
            String plannerModel,
            @Nullable String codeModel,
            String githubToken,
            String owner,
            String repo,
            int issueNumber,
            @Nullable String buildSettingsJson,
            int maxIssueFixAttempts) {
        // Only include build_settings tag when JSON is non-blank; absent means "use repo-level fallback"
        Map<String, String> tags;
        if (buildSettingsJson != null && !buildSettingsJson.isBlank()) {
            tags = Map.of(
                    "mode", "ISSUE",
                    "github_token", githubToken,
                    "repo_owner", owner,
                    "repo_name", repo,
                    "issue_number", String.valueOf(issueNumber),
                    "build_settings", buildSettingsJson);
        } else {
            tags = Map.of(
                    "mode", "ISSUE",
                    "github_token", githubToken,
                    "repo_owner", owner,
                    "repo_name", repo,
                    "issue_number", String.valueOf(issueNumber));
        }
        return new JobSpec(
                "",
                false,
                false,
                plannerModel,
                null,
                codeModel,
                false,
                tags,
                null,
                null,
                null,
                null,
                null,
                null,
                maxIssueFixAttempts);
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
            Map<String, String> tags,
            @Nullable String reasoningLevelCode) {
        return new JobSpec(
                taskInput,
                autoCommit,
                autoCompress,
                plannerModel,
                scanModel,
                codeModel,
                preScan,
                tags,
                null,
                null,
                null,
                reasoningLevelCode,
                null,
                null,
                DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);
    }

    /**
     * Creates a JobSpec with job-level overrides for reasoningLevel and temperature.
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
            @Nullable ModelOverrides overrides) {
        return new JobSpec(
                taskInput,
                autoCommit,
                autoCompress,
                plannerModel,
                scanModel,
                codeModel,
                preScan,
                tags,
                null,
                null,
                overrides != null ? overrides.reasoningLevel() : null,
                overrides != null ? overrides.reasoningLevelCode() : null,
                overrides != null ? overrides.temperature() : null,
                overrides != null ? overrides.temperatureCode() : null,
                DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);
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
            @Nullable String targetBranch,
            @Nullable String reasoningLevelCode) {
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
                targetBranch,
                null,
                reasoningLevelCode,
                null,
                null,
                DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);
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
    public int effectiveMaxIssueFixAttempts() {
        return Objects.requireNonNullElse(maxIssueFixAttempts, DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);
    }

    @JsonIgnore
    @Nullable
    public String getBuildSettingsJson() {
        return tags.get("build_settings");
    }
}
