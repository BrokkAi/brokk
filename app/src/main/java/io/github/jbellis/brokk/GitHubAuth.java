package io.github.jbellis.brokk;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.util.GitUiUtil;
import io.github.jbellis.brokk.issues.IssueProviderType;
import io.github.jbellis.brokk.issues.IssuesProviderConfig;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestCommitDetail;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

/**
 * Handles GitHub authentication and API calls. This class is stateful and holds a connection to a specific repository.
 */
public class GitHubAuth {
    private static final Logger logger = LogManager.getLogger(GitHubAuth.class);
    private static @Nullable GitHubAuth instance;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String owner;
    private final String repoName;

    @Nullable
    private final String host; // For GHES endpoint

    private @Nullable GitHub githubClient;
    private @Nullable GHRepository ghRepository;

    public GitHubAuth(String owner, String repoName, @Nullable String host) {
        this.owner = owner;
        this.repoName = repoName;
        this.host = (host == null || host.isBlank()) ? null : host.trim(); // Store null if blank/default
    }

    /**
     * Gets the existing GitHubAuth instance, or creates a new one if not already created or if the instance is outdated
     * (e.g., remote URL changed for the current project).
     *
     * @param project The current project, used to determine repository details.
     * @return A GitHubAuth instance.
     * @throws IOException If the Git repository is not available, the remote URL cannot be parsed, or connection to
     *     GitHub fails.
     * @throws IllegalArgumentException If the project is null.
     */
    public static synchronized GitHubAuth getOrCreateInstance(IProject project) throws IOException {
        IssueProvider provider = project.getIssuesProvider();
        String effectiveOwner = null;
        String effectiveRepoName = null;
        String effectiveHost = null; // For GHES
        boolean usingOverride = false;

        if (provider.type() == IssueProviderType.GITHUB
                && provider.config() instanceof IssuesProviderConfig.GithubConfig githubConfig) {
            // Check if any part of the GithubConfig is non-default.
            // isDefault() now checks owner, repo, and host.
            if (!githubConfig.isDefault()) {
                effectiveOwner = githubConfig.owner();
                effectiveRepoName = githubConfig.repo();
                effectiveHost = githubConfig.host(); // May be blank/null if only owner/repo overridden
                usingOverride = true; // Indicates some form of override from project settings
                logger.info(
                        "Using GitHub config override: Owner='{}', Repo='{}', Host='{}' for project {}",
                        effectiveOwner,
                        effectiveRepoName,
                        (effectiveHost == null || effectiveHost.isBlank() ? "github.com (default)" : effectiveHost),
                        project.getRoot().getFileName().toString());
            }
        }

        // If not using an override from project settings, or if owner/repo were blank in the override,
        // derive from git remote. Host remains null (meaning github.com) unless explicitly set by override.
        if (!usingOverride
                || (effectiveOwner == null || effectiveOwner.isBlank())
                || (effectiveRepoName == null || effectiveRepoName.isBlank())) {
            var repo = (GitRepo) project.getRepo();

            var remoteUrl = repo.getRemoteUrl();
            // Use GitUiUtil for parsing owner/repo from URL
            var parsedOwnerRepoDetails = GitUiUtil.parseOwnerRepoFromUrl(Objects.requireNonNullElse(remoteUrl, ""));

            if (parsedOwnerRepoDetails != null) {
                effectiveOwner = parsedOwnerRepoDetails.owner();
                effectiveRepoName = parsedOwnerRepoDetails.repo();
                // effectiveHost remains as set by override, or null (github.com) if no override.
                // If we are here because override didn't specify owner/repo, we still use override's host if present.
                logger.info(
                        "Derived GitHub owner/repo from git remote: {}/{}. Host remains: '{}' for project {}",
                        effectiveOwner,
                        effectiveRepoName,
                        (effectiveHost == null || effectiveHost.isBlank() ? "github.com (default)" : effectiveHost),
                        project.getRoot().getFileName().toString());
            } else {
                logger.warn(
                        "Could not parse owner/repo from git remote URL: {} for project {}. GitHub integration might fail if owner/repo not set in override.",
                        remoteUrl,
                        project.getRoot().getFileName().toString());
                // effectiveOwner and effectiveRepoName may remain null from override or become null here.
            }
        }

        if (effectiveOwner == null
                || effectiveOwner.isBlank()
                || effectiveRepoName == null
                || effectiveRepoName.isBlank()) {
            if (instance != null) {
                logger.warn(
                        "Could not determine effective owner/repo for project '{}'. Invalidating GitHubAuth instance for {}/{} (Host: {}).",
                        project.getRoot().getFileName().toString(),
                        instance.getOwner(),
                        instance.getRepoName(),
                        instance.host);
                instance = null;
            }
            throw new IOException("Could not determine effective 'owner/repo' for GitHubAuth (project: "
                    + project.getRoot().getFileName().toString()
                    + "). Check git remote or GitHub override settings for owner/repo.");
        }

        // Compare all three: owner, repo, and host
        boolean hostMatches =
                (instance != null && instance.host == null && (effectiveHost == null || effectiveHost.isBlank()))
                        || (instance != null && instance.host != null && instance.host.equals(effectiveHost));

        if (instance != null
                && instance.getOwner().equals(effectiveOwner)
                && instance.getRepoName().equals(effectiveRepoName)
                && hostMatches) {
            logger.debug(
                    "Using existing GitHubAuth instance for {}/{} (Host: {}) (project {})",
                    instance.getOwner(),
                    instance.getRepoName(),
                    (instance.host == null ? "github.com" : instance.host),
                    project.getRoot().getFileName().toString());
            return instance;
        }

        if (instance != null) {
            logger.info(
                    "GitHubAuth instance for {}/{} (Host: {}) (project {}) is outdated (current effective {}/{} Host: {}). Re-creating.",
                    instance.getOwner(),
                    instance.getRepoName(),
                    (instance.host == null ? "github.com" : instance.host),
                    project.getRoot().getFileName().toString(),
                    effectiveOwner,
                    effectiveRepoName,
                    (effectiveHost == null || effectiveHost.isBlank() ? "github.com" : effectiveHost));
        } else {
            logger.info(
                    "No existing GitHubAuth instance. Creating new instance for {}/{} (Host: {}) (project {})",
                    effectiveOwner,
                    effectiveRepoName,
                    (effectiveHost == null || effectiveHost.isBlank() ? "github.com" : effectiveHost),
                    project.getRoot().getFileName().toString());
        }

        GitHubAuth newAuth = new GitHubAuth(effectiveOwner, effectiveRepoName, effectiveHost);
        instance = newAuth;
        logger.info(
                "Created and set new GitHubAuth instance for {}/{} (Host: {}) (project {})",
                newAuth.getOwner(),
                newAuth.getRepoName(),
                (newAuth.host == null ? "github.com" : newAuth.host),
                project.getRoot().getFileName().toString());
        return instance;
    }

    /**
     * Invalidates the current GitHubAuth instance. This is typically called when a GitHub token changes or an explicit
     * reset is needed.
     */
    public static synchronized void invalidateInstance() {
        if (instance != null) {
            logger.info(
                    "Invalidating GitHubAuth instance for {}/{} due to token change or explicit request.",
                    instance.getOwner(),
                    instance.getRepoName());
            instance = null;
        } else {
            logger.info("GitHubAuth instance is already null. No action taken for invalidation request.");
        }
    }

    /**
     * Checks if a GitHub token is configured, without performing network I/O. This is suitable for UI checks to
     * enable/disable features.
     *
     * @return true if a non-blank token is present.
     */
    public static boolean tokenPresent() {
        var token = getStoredToken();
        return !token.isBlank();
    }

    public static boolean validateStoredToken() {
        String token = getStoredToken();
        if (token.isEmpty()) {
            return false;
        }

        try {
            var github = new GitHubBuilder().withOAuthToken(token).build();
            github.getMyself();
            logger.debug("Stored GitHub token is valid");
            return true;
        } catch (IOException e) {
            logger.warn("Stored GitHub token is invalid: {}", e.getMessage());
            MainProject.setGitHubToken("");
            invalidateInstance();
            return false;
        }
    }

    /**
     * Gets the stored GitHub token. Single source of truth for token access.
     *
     * @return the GitHub token, or empty string if none configured
     */
    public static String getStoredToken() {
        return MainProject.getGitHubToken();
    }

    public static @Nullable String getAuthenticatedUsername() {
        String token = getStoredToken();
        if (token.isEmpty()) {
            return null;
        }

        try {
            var github = new GitHubBuilder().withOAuthToken(token).build();
            return github.getMyself().getLogin();
        } catch (Exception e) {
            // Silently ignore all errors for this nice-to-have feature
            return null;
        }
    }

    /**
     * Fetches the list of GitHub App installations for the authenticated user.
     * This is a helper method to centralize the /user/installations API call and JSON parsing.
     *
     * @return JsonNode containing the "installations" array, or null if the call fails or returns no installations
     */
    private static @Nullable JsonNode fetchUserInstallations() {
        String token = getStoredToken();
        if (token.isEmpty()) {
            return null;
        }

        try {
            var client = createGitHubApiClient();
            var request = new Request.Builder()
                    .url("https://api.github.com/user/installations")
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build();

            try (var response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.info("Failed to fetch user installations: HTTP {}", response.code());
                    return null;
                }

                var body = response.body();
                if (body == null) {
                    logger.info("Empty response body from /user/installations");
                    return null;
                }

                var json = objectMapper.readTree(body.string());
                var installations = json.get("installations");
                if (installations == null || !installations.isArray()) {
                    return null;
                }

                return installations;
            }
        } catch (Exception e) {
            logger.warn("Error fetching user installations: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Checks if the Brokk GitHub App is installed for the authenticated user (any org/repo).
     * This method requires a valid GitHub token and makes a direct API call to /user/installations.
     *
     * @return true if the Brokk app is installed anywhere, false otherwise (including errors)
     */
    public static boolean isBrokkAppInstalled() {
        var installations = fetchUserInstallations();
        if (installations == null) {
            return false;
        }

        for (JsonNode installation : installations) {
            var appSlug = installation.path("app_slug").asText("");
            if ("brokkai".equals(appSlug) || "brokk".equals(appSlug)) {
                logger.debug("Brokk GitHub App installation check: INSTALLED");
                return true;
            }
        }

        logger.debug("Brokk GitHub App installation check: NOT INSTALLED");
        return false;
    }

    /**
     * Checks if the Brokk GitHub App is installed for the authenticated user's personal account.
     * This is stricter than isBrokkAppInstalled(): it requires that the installation 'account'
     * matches the authenticated username and the account type is 'User'.
     *
     * @return true if installed for the current user's personal account; false otherwise
     */
    public static boolean isBrokkAppInstalledForCurrentUser() {
        String username = getAuthenticatedUsername();
        if (username == null || username.isBlank()) {
            logger.debug("Cannot determine authenticated username; treating user-installation check as not installed");
            return false;
        }

        var installations = fetchUserInstallations();
        if (installations == null) {
            return false;
        }

        for (JsonNode installation : installations) {
            var appSlug = installation.path("app_slug").asText("");
            var account = installation.path("account");
            var accountLogin = account.path("login").asText("");
            var accountType = account.path("type").asText("");

            boolean isBrokkApp = "brokkai".equals(appSlug) || "brokk".equals(appSlug);
            boolean isUserAccount = "User".equals(accountType) && username.equals(accountLogin);

            if (isBrokkApp && isUserAccount) {
                logger.debug("Detected Brokk app installation for personal account @{}", username);
                return true;
            }
        }

        logger.debug("No Brokk installation found for personal account @{}", username);
        return false;
    }

    /**
     * Checks if the Brokk GitHub App is installed for a specific repository
     * using the authenticated user's token. This works by:
     * 1) Listing the user's Brokk installations (/user/installations).
     * 2) For each installation, listing repositories via "repositories_url" and
     *    checking for the target "<owner>/<repo>".
     *
     * Note: The legacy app-scoped endpoint /repos/{owner}/{repo}/installation
     * requires authenticating as the GitHub App. With a user token it may return
     * 404/403 even when the app is installed. We therefore prefer the user-token
     * approach above, and only fall back to the legacy endpoint as a best-effort.
     *
     * @param owner The repository owner
     * @param repo The repository name
     * @return true if the Brokk app (brokk or brokkai) has access to this specific repo via any of the user's installations
     */
    public static boolean isBrokkAppInstalledForRepo(String owner, String repo) {
        String token = getStoredToken();
        if (token.isEmpty()) {
            return false;
        }

        try {
            var client = createGitHubApiClient();
            var repoUrls = fetchBrokkInstallationRepositoryUrls();

            if (repoUrls.isEmpty()) {
                logger.debug("No repositories_url found for Brokk installations; using legacy fallback endpoint");
                return fallbackRepoInstallationCheck(client, token, owner, repo);
            }

            return checkInstallationRepositories(client, token, owner, repo, repoUrls);
        } catch (Exception e) {
            logger.warn(
                    "Could not check Brokk GitHub App installation status for {}/{}: {}",
                    owner,
                    repo,
                    e.getMessage(),
                    e);
            return false;
        }
    }

    /**
     * Fetches repository URLs from all Brokk installations accessible to the user.
     */
    private static Set<String> fetchBrokkInstallationRepositoryUrls() {
        var installations = fetchUserInstallations();
        if (installations == null) {
            return Set.of();
        }

        var repoUrls = new LinkedHashSet<String>();
        for (JsonNode installation : installations) {
            var appSlug = installation.path("app_slug").asText("");
            if ("brokkai".equals(appSlug) || "brokk".equals(appSlug)) {
                var repositoriesUrl = installation.path("repositories_url").asText("");
                if (!repositoriesUrl.isBlank()) {
                    repoUrls.add(repositoriesUrl);
                }
            }
        }

        logger.debug("Found {} Brokk installation repository URLs", repoUrls.size());
        return repoUrls;
    }

    /**
     * Checks if any of the given installation repository URLs provide access to the target repository.
     */
    private static boolean checkInstallationRepositories(
            OkHttpClient client, String token, String owner, String repo, Set<String> repoUrls) throws IOException {
        final String fullName = owner + "/" + repo;

        for (String repositoriesUrl : repoUrls) {
            String nextUrl = repositoriesUrl + (repositoriesUrl.contains("?") ? "&" : "?") + "per_page=100";
            int pageCount = 0;

            while (nextUrl != null && pageCount++ < 10) {
                var request = new Request.Builder()
                        .url(nextUrl)
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .build();

                try (var response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        logger.info(
                                "Failed to list installation repositories from {}: HTTP {}",
                                repositoriesUrl,
                                response.code());
                        break;
                    }

                    var json = objectMapper.readTree(response.body().string());
                    var repositories = json.get("repositories");
                    if (repositories != null && repositories.isArray()) {
                        for (JsonNode repository : repositories) {
                            var repoFullName = repository.path("full_name").asText("");
                            if (fullName.equals(repoFullName)) {
                                logger.debug("Detected Brokk installation having access to {}", fullName);
                                return true;
                            }
                        }
                    }

                    // Parse Link header for pagination
                    String linkHeader = response.header("Link");
                    nextUrl = parseNextLink(linkHeader);
                } catch (Exception e) {
                    logger.warn("Error listing installation repositories at {}: {}", nextUrl, e.getMessage());
                    break;
                }
            }
        }

        logger.debug("No Brokk installation with access to {}/{}", owner, repo);
        return false;
    }

    /**
     * Creates an OkHttpClient configured for GitHub API calls with appropriate timeouts.
     */
    private static OkHttpClient createGitHubApiClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Parses an HTTP Link header to find a rel="next" URL; returns null if none.
     */
    private static @Nullable String parseNextLink(@Nullable String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) {
            return null;
        }

        // Typical format: <https://api.github.com/...&page=2>; rel="next", <...>; rel="last"
        int relNextIdx = linkHeader.indexOf("rel=\"next\"");
        if (relNextIdx < 0) {
            return null;
        }

        // Find the preceding <...>
        int lt = linkHeader.lastIndexOf('<', relNextIdx);
        int gt = linkHeader.indexOf('>', lt + 1);
        if (lt >= 0 && gt > lt) {
            return linkHeader.substring(lt + 1, gt);
        }
        return null;
    }

    /**
     * Legacy fallback: try /repos/{owner}/{repo}/installation with user token (may be unreliable).
     */
    private static boolean fallbackRepoInstallationCheck(OkHttpClient client, String token, String owner, String repo) {
        try {
            var request = new Request.Builder()
                    .url(String.format("https://api.github.com/repos/%s/%s/installation", owner, repo))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build();

            try (var response = client.newCall(request).execute()) {
                if (response.code() == 404) {
                    logger.debug("No GitHub App installation found for {}/{} (legacy endpoint)", owner, repo);
                    return false;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    logger.info("Legacy installation check failed for {}/{}: HTTP {}", owner, repo, response.code());
                    return false;
                }

                var json = objectMapper.readTree(response.body().string());
                var appSlug = json.path("app_slug").asText("");
                boolean installed = "brokkai".equals(appSlug) || "brokk".equals(appSlug);

                logger.debug(
                        "Legacy Brokk installation check for {}/{}: {}",
                        owner,
                        repo,
                        installed ? "INSTALLED" : "NOT INSTALLED");
                return installed;
            }
        } catch (Exception e) {
            logger.warn("Legacy installation check error for {}/{}: {}", owner, repo, e.getMessage());
            return false;
        }
    }

    public String getOwner() {
        return owner;
    }

    public String getRepoName() {
        return repoName;
    }

    private synchronized void connect() throws IOException {
        if (ghRepository != null) {
            return; // Already connected
        }

        // Try with token
        var token = getStoredToken();
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
                logger.debug(
                        "Attempting GitHub connection with token for {}/{} on host {}",
                        owner,
                        repoName,
                        targetHostDisplay);
                builder.withOAuthToken(token);
                this.githubClient = builder.build();
                this.ghRepository = this.githubClient.getRepository(owner + "/" + repoName);
                if (this.ghRepository != null) {
                    logger.info(
                            "Successfully connected to GitHub repository {}/{} on host {} with token",
                            owner,
                            repoName,
                            targetHostDisplay);
                    return;
                }
            } catch (IOException e) {
                logger.warn(
                        "GitHub connection with token failed for {}/{} on host {}: {}. Falling back...",
                        owner,
                        repoName,
                        targetHostDisplay,
                        e.getMessage());
                this.githubClient = null;
                this.ghRepository = null;
            }
        } else {
            logger.info(
                    "No GitHub token configured. Proceeding with anonymous connection attempt for {}/{} on host {}",
                    owner,
                    repoName,
                    targetHostDisplay);
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
                logger.debug(
                        "Attempting anonymous GitHub connection for {}/{} on host {}",
                        owner,
                        repoName,
                        targetHostDisplay);
                this.githubClient = builder.build(); // Will use default endpoint or the one set for GHES
                this.ghRepository = this.githubClient.getRepository(owner + "/" + repoName);
                if (this.ghRepository != null) {
                    logger.info(
                            "Successfully connected to GitHub repository {}/{} on host {} anonymously",
                            owner,
                            repoName,
                            targetHostDisplay);
                    return;
                }
            } catch (IOException e) {
                logger.warn(
                        "Anonymous GitHub connection failed for {}/{} on host {}: {}",
                        owner,
                        repoName,
                        targetHostDisplay,
                        e.getMessage());
                // Let it fall through to the exception
            }
        }

        // If still not connected
        throw new IOException("Failed to connect to GitHub repository " + owner + "/" + repoName + " on host "
                + targetHostDisplay + " (tried token and anonymous).");
    }

    public GHRepository getGhRepository() throws IOException {
        connect(); // Ensures ghRepository is initialized or throws
        return requireNonNull(this.ghRepository, "ghRepository should be non-null after successful connect()");
    }

    /**
     * Checks if the authenticated user has push (write) access to the repository. This method requires a configured
     * token and performs a network request.
     *
     * @return true if the user has push access, false otherwise.
     * @throws IOException if there's a problem connecting to GitHub, which can happen if the token is invalid or there
     *     are network issues.
     */
    public boolean hasWriteAccess() throws IOException {
        // Will throw IOException if connection fails, e.g., bad token.
        // hasPushAccess() returns the permission status for the authenticated user.
        return getGhRepository().hasPushAccess();
    }

    /** Fetches the default branch name for the connected repository. */
    public String getDefaultBranch() throws IOException {
        return getGhRepository().getDefaultBranch();
    }

    /**
     * Provides access to the underlying Kohsuke GitHub API client. Ensures connection before returning.
     *
     * @return The initialized GitHub client.
     * @throws IOException if connection fails.
     */
    public GitHub getGitHub() throws IOException {
        connect();
        return requireNonNull(this.githubClient, "githubClient should be non-null after successful connect()");
    }

    /** Lists pull requests for the connected repository based on the given state. */
    public List<GHPullRequest> listOpenPullRequests(GHIssueState state) throws IOException {
        return getGhRepository().getPullRequests(state);
    }

    /**
     * Lists issues for the connected repository based on the given state. Note: This may include Pull Requests as they
     * are a type of Issue in GitHub's API. Filtering to exclude PRs can be done by checking `issue.isPullRequest()`.
     */
    public List<GHIssue> listIssues(GHIssueState state) throws IOException {
        return getGhRepository().getIssues(state);
    }

    public GHIssue getIssue(int issueNumber) throws IOException {
        return getGhRepository().getIssue(issueNumber);
    }

    /**
     * Fetches commits in a given pull request for the connected repository. Returns them as GHPullRequestCommitDetail
     * objects.
     */
    public List<GHPullRequestCommitDetail> listPullRequestCommits(int prNumber) throws IOException {
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

        var token = getStoredToken();
        if (!token.isBlank()) {
            builder.addInterceptor(chain -> {
                Request originalRequest = chain.request();
                Request authenticatedRequest = originalRequest
                        .newBuilder()
                        .header("Authorization", "token " + token)
                        .build();
                return chain.proceed(authenticatedRequest);
            });
            logger.debug("Authenticated OkHttpClient created with token.");
        } else {
            logger.debug(
                    "GitHub token not found or blank; OkHttpClient will be unauthenticated for direct image fetches.");
        }
        return builder.build();
    }
}
