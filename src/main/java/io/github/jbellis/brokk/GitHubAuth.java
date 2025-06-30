package io.github.jbellis.brokk;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import io.github.jbellis.brokk.git.GitRepo;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHPullRequestCommitDetail;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * Handles GitHub authentication and API calls.
 * This class is stateful and holds a connection to a specific repository.
 */
public class GitHubAuth
{
    private static final Logger logger = LogManager.getLogger(GitHubAuth.class);
    private static @Nullable GitHubAuth instance;

    private final String owner;
    private final String repoName;
    @Nullable private final String host; // For GHES endpoint

    private @Nullable GitHub githubClient;
    private @Nullable GHRepository ghRepository;

    public GitHubAuth(String owner, String repoName, @Nullable String host)
    {
        this.owner = owner;
        this.repoName = repoName;
        this.host = (host == null || host.isBlank()) ? null : host.trim(); // Store null if blank/default
    }

    /**
     * Gets the existing GitHubAuth instance, or creates a new one if not already created
     * or if the instance is outdated (e.g., remote URL changed for the current project).
     *
     * @param project The current project, used to determine repository details.
     * @return A GitHubAuth instance.
     * @throws IOException If the Git repository is not available, the remote URL cannot be parsed,
     *                     or connection to GitHub fails.
     * @throws IllegalArgumentException If the project is null.
     */
    public static synchronized GitHubAuth getOrCreateInstance(IProject project) throws IOException {
        io.github.jbellis.brokk.IssueProvider provider = project.getIssuesProvider();
        String effectiveOwner = null;
        String effectiveRepoName = null;
        String effectiveHost = null; // For GHES
        boolean usingOverride = false;

        if (provider.type() == io.github.jbellis.brokk.issues.IssueProviderType.GITHUB &&
            provider.config() instanceof io.github.jbellis.brokk.issues.IssuesProviderConfig.GithubConfig githubConfig) {
            // Check if any part of the GithubConfig is non-default.
            // isDefault() now checks owner, repo, and host.
            if (!githubConfig.isDefault()) {
                effectiveOwner = githubConfig.owner();
                effectiveRepoName = githubConfig.repo();
                effectiveHost = githubConfig.host(); // May be blank/null if only owner/repo overridden
                usingOverride = true; // Indicates some form of override from project settings
                logger.info("Using GitHub config override: Owner='{}', Repo='{}', Host='{}' for project {}",
                            effectiveOwner, effectiveRepoName, (effectiveHost == null || effectiveHost.isBlank() ? "github.com (default)" : effectiveHost),
                            project.getRoot().getFileName().toString());
            }
        }

        // If not using an override from project settings, or if owner/repo were blank in the override,
        // derive from git remote. Host remains null (meaning github.com) unless explicitly set by override.
        if (!usingOverride || (effectiveOwner == null || effectiveOwner.isBlank()) || (effectiveRepoName == null || effectiveRepoName.isBlank())) {
            var repo = (GitRepo) project.getRepo();

            var remoteUrl = repo.getRemoteUrl();
            // Use GitUiUtil for parsing owner/repo from URL
            var parsedOwnerRepoDetails = io.github.jbellis.brokk.gui.GitUiUtil.parseOwnerRepoFromUrl(Objects.requireNonNullElse(remoteUrl, ""));

            if (parsedOwnerRepoDetails != null) {
                effectiveOwner = parsedOwnerRepoDetails.owner();
                effectiveRepoName = parsedOwnerRepoDetails.repo();
                // effectiveHost remains as set by override, or null (github.com) if no override.
                // If we are here because override didn't specify owner/repo, we still use override's host if present.
                logger.info("Derived GitHub owner/repo from git remote: {}/{}. Host remains: '{}' for project {}",
                            effectiveOwner, effectiveRepoName, (effectiveHost == null || effectiveHost.isBlank() ? "github.com (default)" : effectiveHost),
                            project.getRoot().getFileName().toString());
            } else {
                logger.warn("Could not parse owner/repo from git remote URL: {} for project {}. GitHub integration might fail if owner/repo not set in override.",
                            remoteUrl, project.getRoot().getFileName().toString());
                // effectiveOwner and effectiveRepoName may remain null from override or become null here.
            }
        }


        if (effectiveOwner == null || effectiveOwner.isBlank() || effectiveRepoName == null || effectiveRepoName.isBlank()) {
            if (instance != null) {
                logger.warn("Could not determine effective owner/repo for project '{}'. Invalidating GitHubAuth instance for {}/{} (Host: {}).",
                            project.getRoot().getFileName().toString(), instance.getOwner(), instance.getRepoName(), instance.host);
                instance = null;
            }
            throw new IOException("Could not determine effective 'owner/repo' for GitHubAuth (project: " + project.getRoot().getFileName().toString() + "). Check git remote or GitHub override settings for owner/repo.");
        }

        // Compare all three: owner, repo, and host
        boolean hostMatches = (instance != null && instance.host == null && (effectiveHost == null || effectiveHost.isBlank())) ||
                              (instance != null && instance.host != null && instance.host.equals(effectiveHost));

        if (instance != null &&
            instance.getOwner().equals(effectiveOwner) &&
            instance.getRepoName().equals(effectiveRepoName) &&
            hostMatches) {
            logger.debug("Using existing GitHubAuth instance for {}/{} (Host: {}) (project {})",
                         instance.getOwner(), instance.getRepoName(), (instance.host == null ? "github.com" : instance.host), project.getRoot().getFileName().toString());
            return instance;
        }

        if (instance != null) {
            logger.info("GitHubAuth instance for {}/{} (Host: {}) (project {}) is outdated (current effective {}/{} Host: {}). Re-creating.",
                        instance.getOwner(), instance.getRepoName(), (instance.host == null ? "github.com" : instance.host),
                        project.getRoot().getFileName().toString(),
                        effectiveOwner, effectiveRepoName, (effectiveHost == null || effectiveHost.isBlank() ? "github.com" : effectiveHost));
        } else {
            logger.info("No existing GitHubAuth instance. Creating new instance for {}/{} (Host: {}) (project {})",
                        effectiveOwner, effectiveRepoName, (effectiveHost == null || effectiveHost.isBlank() ? "github.com" : effectiveHost),
                        project.getRoot().getFileName().toString());
        }

        GitHubAuth newAuth = new GitHubAuth(effectiveOwner, effectiveRepoName, effectiveHost);
        instance = newAuth;
        logger.info("Created and set new GitHubAuth instance for {}/{} (Host: {}) (project {})",
                    newAuth.getOwner(), newAuth.getRepoName(), (newAuth.host == null ? "github.com" : newAuth.host), project.getRoot().getFileName().toString());
        return instance;
    }

    /**
     * Invalidates the current GitHubAuth instance.
     * This is typically called when a GitHub token changes or an explicit reset is needed.
     */
    public static synchronized void invalidateInstance() {
        if (instance != null) {
            logger.info("Invalidating GitHubAuth instance for {}/{} due to token change or explicit request.", instance.getOwner(), instance.getRepoName());
            instance = null;
        } else {
            logger.info("GitHubAuth instance is already null. No action taken for invalidation request.");
        }
    }

    /**
     * Checks if a GitHub token is configured, without performing network I/O.
     * This is suitable for UI checks to enable/disable features.
     *
     * @param project The current project (reserved for future use, e.g., project-specific tokens).
     * @return true if a non-blank token is present.
     */
    public static boolean tokenPresent(IProject project)
    {
        var token = MainProject.getGitHubToken();
        return !token.isBlank();
    }

    public String getOwner()
    {
        return owner;
    }

    public String getRepoName()
    {
        return repoName;
    }

    private synchronized void connect() throws IOException
    {
        if (ghRepository != null) {
            return; // Already connected
        }

        // Try with token
        var token = MainProject.getGitHubToken();
        GitHubBuilder builder = new GitHubBuilder();
        String targetHostDisplay = (this.host == null || this.host.isBlank()) ? "api.github.com" : this.host;

        if (this.host != null && !this.host.isBlank()) {
            // Ensure host does not have scheme, GitHubBuilder wants just the hostname for enterprise.
            // It will construct https://{host}/api/v3 or similar internally.
            String enterpriseHost = this.host.replaceFirst("^https?://", "").replaceFirst("/$", "");
            builder.withEndpoint("https://" + enterpriseHost + "/api/v3"); // Explicitly set scheme and path for clarity
            logger.debug("Configuring GitHub client for enterprise host: {}", enterpriseHost);
        }


        if (!token.isBlank()) {
            try {
                logger.debug("Attempting GitHub connection with token for {}/{} on host {}", owner, repoName, targetHostDisplay);
                builder.withOAuthToken(token);
                this.githubClient = builder.build();
                this.ghRepository = this.githubClient.getRepository(owner + "/" + repoName);
                if (this.ghRepository != null) {
                    logger.info("Successfully connected to GitHub repository {}/{} on host {} with token", owner, repoName, targetHostDisplay);
                    return;
                }
            } catch (IOException e) {
                logger.warn("GitHub connection with token failed for {}/{} on host {}: {}. Falling back...", owner, repoName, targetHostDisplay, e.getMessage());
                this.githubClient = null;
                this.ghRepository = null;
            }
        } else {
            logger.info("No GitHub token configured. Proceeding with anonymous connection attempt for {}/{} on host {}", owner, repoName, targetHostDisplay);
        }

        // Try anonymous (if token failed or no token)
        // Re-initialize builder if it was modified by token attempt and failed, or if no token.
        // If host was set, it's already in builder. If not, builder is fresh or uses default endpoint.
        // GitHubBuilder is stateful for endpoint, so if it was set above, it persists.
        // If builder.withOAuthToken(token) failed, the endpoint setting is still there for anonymous.
        if (this.githubClient == null) { // only if token attempt failed or no token was present
            // builder already has endpoint if host was specified.
            // builder.build() will now be anonymous.
             try {
                 logger.debug("Attempting anonymous GitHub connection for {}/{} on host {}", owner, repoName, targetHostDisplay);
                 this.githubClient = builder.build(); // Will use default endpoint or the one set for GHES
                 this.ghRepository = this.githubClient.getRepository(owner + "/" + repoName);
                 if (this.ghRepository != null) {
                     logger.info("Successfully connected to GitHub repository {}/{} on host {} anonymously", owner, repoName, targetHostDisplay);
                     return;
                 }
             } catch (IOException e) {
                 logger.warn("Anonymous GitHub connection failed for {}/{} on host {}: {}", owner, repoName, targetHostDisplay, e.getMessage());
                 // Let it fall through to the exception
             }
        }


        // If still not connected
        throw new IOException("Failed to connect to GitHub repository " + owner + "/" + repoName + " on host " + targetHostDisplay + " (tried token and anonymous).");
    }

    public GHRepository getGhRepository() throws IOException {
        connect(); // Ensures ghRepository is initialized or throws
        return requireNonNull(this.ghRepository, "ghRepository should be non-null after successful connect()");
    }

    /**
     * Fetches the default branch name for the connected repository.
     */
    public String getDefaultBranch() throws IOException {
        return getGhRepository().getDefaultBranch();
    }

    /**
     * Provides access to the underlying Kohsuke GitHub API client.
     * Ensures connection before returning.
     * @return The initialized GitHub client.
     * @throws IOException if connection fails.
     */
    public GitHub getGitHub() throws IOException {
        connect();
        return requireNonNull(this.githubClient, "githubClient should be non-null after successful connect()");
    }

    /**
     * Lists pull requests for the connected repository based on the given state.
     */
    public List<GHPullRequest> listOpenPullRequests(GHIssueState state) throws IOException
    {
        return getGhRepository().getPullRequests(state);
    }

    /**
     * Lists issues for the connected repository based on the given state.
     * Note: This may include Pull Requests as they are a type of Issue in GitHub's API.
     * Filtering to exclude PRs can be done by checking `issue.isPullRequest()`.
     */
    public List<GHIssue> listIssues(GHIssueState state) throws IOException
    {
        return getGhRepository().getIssues(state);
    }

    public GHIssue getIssue(int issueNumber) throws IOException
    {
        return getGhRepository().getIssue(issueNumber);
    }

    /**
     * Fetches commits in a given pull request for the connected repository.
     * Returns them as GHPullRequestCommitDetail objects.
     */
    public List<GHPullRequestCommitDetail> listPullRequestCommits(int prNumber) throws IOException
    {
        var pr = getGhRepository().getPullRequest(prNumber);
        return pr.listCommits().toList();
    }

    /**
     * Creates an OkHttpClient configured with GitHub authentication.
     *
     * @return An authenticated OkHttpClient.
     */
    public OkHttpClient authenticatedClient() {
        var builder = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS) // Sensible defaults
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .followRedirects(true);

        var token = MainProject.getGitHubToken();
        if (!token.isBlank()) {
            builder.addInterceptor(chain -> {
                Request originalRequest = chain.request();
                Request authenticatedRequest = originalRequest.newBuilder()
                        .header("Authorization", "token " + token)
                        .build();
                return chain.proceed(authenticatedRequest);
            });
            logger.debug("Authenticated OkHttpClient created with token.");
        } else {
            logger.debug("GitHub token not found or blank; OkHttpClient will be unauthenticated for direct image fetches.");
        }
        return builder.build();
    }
}
