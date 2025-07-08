package io.github.jbellis.brokk.issues;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jetbrains.annotations.Nullable;

/**
 * Marker parent.  Concrete records hold the supplier‐specific data.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME,
              include = JsonTypeInfo.As.PROPERTY,
              property = "kind")
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
    record GithubConfig(@Nullable String owner, @Nullable String repo, @Nullable String host) implements IssuesProviderConfig {
        /** Convenience ctor -> default to current repo on github.com */
        public GithubConfig() { this("", "", ""); }

        /**
         * Checks if this configuration represents the default (i.e., derive from current project's git remote on github.com).
         * @return true if owner, repo, and host are blank or null, indicating default behavior.
         */
        public boolean isDefault() {
            return (owner == null || owner.isBlank()) &&
                   (repo == null || repo.isBlank()) &&
                   (host == null || host.isBlank());
        }
    }

    /** Jira provider */
    record JiraConfig(String baseUrl, String apiToken, String projectKey) implements IssuesProviderConfig {}
}
