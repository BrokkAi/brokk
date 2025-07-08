package io.github.jbellis.brokk;

import io.github.jbellis.brokk.issues.IssueProviderType;
import io.github.jbellis.brokk.issues.IssuesProviderConfig;

public record IssueProvider(IssueProviderType type, IssuesProviderConfig config) {

    public static IssueProvider none() {
        return new IssueProvider(IssueProviderType.NONE, new IssuesProviderConfig.NoneConfig());
    }

    // Default GitHub: owner/repo derived from current project's git remote, host defaults to github.com
    public static IssueProvider github() {
        return new IssueProvider(IssueProviderType.GITHUB, new IssuesProviderConfig.GithubConfig()); // owner="", repo="", host=""
    }

    // GitHub with specific owner/repo, host defaults to github.com
    public static IssueProvider github(String owner, String repo) {
        return new IssueProvider(IssueProviderType.GITHUB, new IssuesProviderConfig.GithubConfig(owner, repo, ""));
    }

    // GitHub with specific owner, repo, and host (for GHES)
    public static IssueProvider github(String owner, String repo, String host) {
        return new IssueProvider(IssueProviderType.GITHUB, new IssuesProviderConfig.GithubConfig(owner, repo, host));
    }

    public static IssueProvider jira(String baseUrl, String apiToken, String projectKey) {
        return new IssueProvider(IssueProviderType.JIRA, new IssuesProviderConfig.JiraConfig(baseUrl, apiToken, projectKey));
    }

    // Returns an empty issue provider configuration
    public static IssueProvider empty() {
        return none();
    }
}
