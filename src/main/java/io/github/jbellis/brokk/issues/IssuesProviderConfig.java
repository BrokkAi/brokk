package io.github.jbellis.brokk.issues;

/**
 * Marker parent.  Concrete records hold the supplier‐specific data.
 */
public sealed interface IssuesProviderConfig
        permits IssuesProviderConfig.NoneConfig, IssuesProviderConfig.GithubConfig, IssuesProviderConfig.JiraConfig {

    /** Explicit “no issues” */
    record NoneConfig() implements IssuesProviderConfig {}

    /** GitHub provider */
    record GithubConfig(String owner, String repo, String host) implements IssuesProviderConfig {
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
