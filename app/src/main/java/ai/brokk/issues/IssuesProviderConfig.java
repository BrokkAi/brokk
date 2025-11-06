package ai.brokk.issues;

import ai.brokk.gui.util.GitUiUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/** Marker parent. Concrete records hold the supplier‐specific data. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = IssuesProviderConfig.NoneConfig.class, name = "none"),
    @JsonSubTypes.Type(value = IssuesProviderConfig.GithubConfig.class, name = "github"),
    @JsonSubTypes.Type(value = IssuesProviderConfig.JiraConfig.class, name = "jira")
})
public sealed interface IssuesProviderConfig
        permits IssuesProviderConfig.NoneConfig, IssuesProviderConfig.GithubConfig, IssuesProviderConfig.JiraConfig {

    /** Explicit “no issues” */
    record NoneConfig() implements IssuesProviderConfig {}

    /** GitHub provider */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GithubConfig(@Nullable String owner, @Nullable String repo, @Nullable String host)
            implements IssuesProviderConfig {
        /** Convenience ctor -> default to current repo on github.com */
        public GithubConfig() {
            this("", "", "");
        }

        /**
         * Checks if this configuration represents the default (i.e., derive from current project's git remote on
         * github.com).
         *
         * @return true if owner, repo, and host are blank or null, indicating default behavior.
         */
        @JsonIgnore
        public boolean isDefault() {
            return (owner == null || owner.isBlank())
                    && (repo == null || repo.isBlank())
                    && (host == null || host.isBlank());
        }

        /**
         * Returns the trimmed owner, or empty string if null.
         *
         * @return trimmed owner string
         */
        @JsonIgnore
        public String ownerTrimmed() {
            return (owner == null) ? "" : owner.trim();
        }

        /**
         * Returns the trimmed repo, or empty string if null.
         *
         * @return trimmed repo string
         */
        @JsonIgnore
        public String repoTrimmed() {
            return (repo == null) ? "" : repo.trim();
        }

        /**
         * Validates the owner and repo using {@link GitUiUtil#validateOwnerRepo}. Returns empty if either is empty
         * (incomplete config, treated as valid default), or if validation passes.
         *
         * @return Optional.empty() if valid or incomplete, or Optional.of(errorMessage) if invalid
         */
        @JsonIgnore
        public Optional<String> validationError() {
            String trimmedOwner = ownerTrimmed();
            String trimmedRepo = repoTrimmed();

            // If either is empty, treat as incomplete (valid default)
            if (trimmedOwner.isEmpty() || trimmedRepo.isEmpty()) {
                return Optional.empty();
            }

            // Validate only when both are non-empty
            return GitUiUtil.validateOwnerRepo(trimmedOwner, trimmedRepo);
        }
    }

    /** Jira provider */
    record JiraConfig(String baseUrl, String apiToken, String projectKey) implements IssuesProviderConfig {}
}
