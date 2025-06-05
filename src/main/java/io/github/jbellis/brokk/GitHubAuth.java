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

import java.io.IOException;
import java.util.List;

/**
 * Handles GitHub authentication and API calls.
 * This class is stateful and holds a connection to a specific repository.
 */
public class GitHubAuth
{
    private static final Logger logger = LogManager.getLogger(GitHubAuth.class);
    private static GitHubAuth instance;

    private final String owner;
    private final String repoName;

    private GitHub githubClient;
    private GHRepository ghRepository;

    public GitHubAuth(String owner, String repoName)
    {
        this.owner = owner;
        this.repoName = repoName;
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
        if (project == null) {
            throw new IllegalArgumentException("Project cannot be null for GitHubAuth.");
        }

        var repo = (GitRepo) project.getRepo();
        if (repo == null) {
            if (instance != null) {
                logger.info("Git repository not available for project '{}'. Invalidating GitHubAuth instance for {}/{}.",
                            project.getRoot().getFileName().toString(), instance.getOwner(), instance.getRepoName());
                instance = null;
            }
            throw new IOException("Git repository not available from project '" + project.getRoot().getFileName().toString() + "' for GitHubAuth.");
        }

        var remoteUrl = repo.getRemoteUrl();
        // Use GitUiUtil for parsing owner/repo from URL
        var currentOwnerRepo = io.github.jbellis.brokk.gui.GitUiUtil.parseOwnerRepoFromUrl(remoteUrl);

        if (currentOwnerRepo == null) {
            if (instance != null) {
                logger.warn("Could not parse current owner/repo from remote URL for project '{}': {}. Invalidating GitHubAuth instance for {}/{}.",
                            project.getRoot().getFileName().toString(), remoteUrl, instance.getOwner(), instance.getRepoName());
                instance = null;
            }
            throw new IOException("Could not parse 'owner/repo' from remote: " + remoteUrl + " for GitHubAuth (project: " + project.getRoot().getFileName().toString() + ").");
        }

        if (instance != null &&
                instance.getOwner().equals(currentOwnerRepo.owner()) &&
                instance.getRepoName().equals(currentOwnerRepo.repo())) {
            logger.debug("Using existing GitHubAuth instance for {}/{} (project {})", instance.getOwner(), instance.getRepoName(), project.getRoot().getFileName().toString());
            return instance;
        }

        if (instance != null) {
            logger.info("GitHubAuth instance for {}/{} (project {}) is outdated (current remote {}/{}). Re-creating.",
                        instance.getOwner(), instance.getRepoName(), project.getRoot().getFileName().toString(), currentOwnerRepo.owner(), currentOwnerRepo.repo());
        } else {
            logger.info("No existing GitHubAuth instance. Creating new instance for {}/{} (project {})",
                        currentOwnerRepo.owner(), currentOwnerRepo.repo(), project.getRoot().getFileName().toString());
        }

        GitHubAuth newAuth = new GitHubAuth(currentOwnerRepo.owner(), currentOwnerRepo.repo());
        instance = newAuth;
        logger.info("Created and set new GitHubAuth instance for {}/{} (project {})", newAuth.getOwner(), newAuth.getRepoName(), project.getRoot().getFileName().toString());
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
        if (token != null && !token.isBlank()) {
            try {
                logger.debug("Attempting GitHub connection with token for {}/{}", owner, repoName);
                this.githubClient = new GitHubBuilder().withOAuthToken(token).build();
                this.ghRepository = this.githubClient.getRepository(owner + "/" + repoName);
                if (this.ghRepository != null) {
                    logger.info("Successfully connected to GitHub repository {}/{} with token", owner, repoName);
                    return;
                }
            } catch (IOException e) {
                logger.warn("GitHub connection with token failed for {}/{}: {}. Falling back...", owner, repoName, e.getMessage());
                // Clear potentially partially initialized state before fallback
                this.githubClient = null;
                this.ghRepository = null;
            }
        } else {
            logger.info("No GitHub token configured for project. Proceeding with anonymous connection attempt for {}/{}", owner, repoName);
        }

        // Try anonymous (if token failed or no token)
        logger.debug("Attempting anonymous GitHub connection for {}/{}", owner, repoName);
        try {
            this.githubClient = new GitHubBuilder().build();
            this.ghRepository = this.githubClient.getRepository(owner + "/" + repoName);
            if (this.ghRepository != null) {
                logger.info("Successfully connected to GitHub repository {}/{} anonymously", owner, repoName);
                return;
            }
        } catch (IOException e) {
            logger.warn("Anonymous GitHub connection failed for {}/{}: {}", owner, repoName, e.getMessage());
            // Let it fall through to the exception
        }

        // If still not connected
        throw new IOException("Failed to connect to GitHub repository " + owner + "/" + repoName + " (tried token and anonymous).");
    }

    private GHRepository getGhRepository() throws IOException
    {
        connect(); // Ensures ghRepository is initialized or throws
        return this.ghRepository;
    }

    /**
     * Fetches the default branch name for the connected repository.
     */
    public String getDefaultBranch() throws IOException
    {
        return getGhRepository().getDefaultBranch();
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

    /**
     * Fetches commits in a given pull request for the connected repository.
     * Returns them as GHPullRequestCommitDetail objects.
     */
    public List<GHPullRequestCommitDetail> listPullRequestCommits(int prNumber) throws IOException
    {
        var pr = getGhRepository().getPullRequest(prNumber);
        return pr.listCommits().toList();
    }
}
