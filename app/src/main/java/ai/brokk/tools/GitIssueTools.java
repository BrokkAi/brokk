package ai.brokk.tools;

import ai.brokk.ContextManager;
import ai.brokk.context.ContextFragment;
import ai.brokk.gui.util.GitUiUtil;
import ai.brokk.issues.Comment;
import ai.brokk.issues.GitHubFilterOptions;
import ai.brokk.issues.GitHubIssueService;
import ai.brokk.issues.IssueCaptureBuilder;
import ai.brokk.issues.IssueDetails;
import ai.brokk.issues.IssueHeader;
import ai.brokk.issues.IssueService;
import ai.brokk.issues.IssueProviderType;
import ai.brokk.issues.JiraIssueService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.ChatMessage;
import java.nio.file.Path;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Tools for capturing GitHub issues into the Workspace Context.
 *
 * Modeled after WorkspaceTools: it uses ContextManager to add fragments.
 * v1 validation: ensures the project is a GitHub repo and that the provided repo name matches the
 * current project's folder name. If you need stricter validation against the actual remote URL,
 * please expose the IGitRepo API for retrieving the origin URL and we can tighten this.
 */
public class GitIssueTools {

  private static final Logger logger = LogManager.getLogger(GitIssueTools.class);

  private final ContextManager contextManager;

  public GitIssueTools(ContextManager cm) {
    this.contextManager = cm;
  }

  @Tool(
      "Add a compact summary of all issues for the given GitHub repository URL to the Workspace as a Markdown fragment.")
  public String addAllGithubIssuesAsFragment(
      @P("GitHub repository URL (e.g., 'https://github.com/owner/repo' or 'git@github.com:owner/repo.git')") String repoUrl) {
    try {
      var ownerRepo = parseRepoUrlOrThrow(repoUrl);

      IssueService service = getIssueService();
      // Per instructions, use "ALL" and empty query string
      var filter = new GitHubFilterOptions("ALL", null, null, null, "");
      List<IssueHeader> headers = service.listIssues(filter);

      String markdown =
          IssueCaptureBuilder.buildCompactListMarkdown(headers, ownerRepo.owner(), ownerRepo.repo());
      String description = "GitHub Issues: " + ownerRepo.owner() + "/" + ownerRepo.repo() + " (summary)";
      ContextFragment.StringFragment fragment =
          new ContextFragment.StringFragment(contextManager, markdown, description, "md");
      contextManager.addVirtualFragment(fragment);

      return "Added summary for " + headers.size() + " issues from " + ownerRepo.owner() + "/" + ownerRepo.repo() + ".";
    } catch (Exception e) {
      logger.error("Failed to add GitHub issues summary for URL {}: {}", repoUrl, e.getMessage(), e);
      return "Error: Failed to add GitHub issues summary: " + e.getMessage();
    }
  }

  @Tool(
      "Add a full GitHub issue (and comments) for the given repository URL and issue ID to the Workspace as fragments.")
  public String addGithubIssueAsFragment(
      @P("GitHub repository URL (e.g., 'https://github.com/owner/repo' or 'git@github.com:owner/repo.git')") String repoUrl,
      @P("Issue ID or number (e.g., '123')") String issueId) {
    try {
      var ownerRepo = parseRepoUrlOrThrow(repoUrl);

      IssueService service = getIssueService();
      IssueDetails details = service.loadDetails(issueId);

      // Issue main content
      List<ChatMessage> issueTextMessages = IssueCaptureBuilder.buildIssueTextMessages(service, details);
      String issueTitle = details.header().title();
      String issueFragmentDescription = "Issue " + details.header().id() + ": " + issueTitle;
      ContextFragment.TaskFragment issueTextFragment =
          new ContextFragment.TaskFragment(contextManager, issueTextMessages, issueFragmentDescription, false);
      contextManager.addVirtualFragment(issueTextFragment);

      // Comments, if any
      List<Comment> comments = details.comments();
      List<ChatMessage> commentMessages = IssueCaptureBuilder.buildCommentMessages(service, comments);
      if (!commentMessages.isEmpty()) {
        String commentsDescription = "Issue " + details.header().id() + ": Comments";
        ContextFragment.TaskFragment commentsFragment =
            new ContextFragment.TaskFragment(contextManager, commentMessages, commentsDescription, false);
        contextManager.addVirtualFragment(commentsFragment);
      }

      return "Captured issue " + details.header().id()
          + " (" + issueTitle + ") with " + comments.size() + " comment(s).";
    } catch (Exception e) {
      logger.error(
          "Failed to add GitHub issue as fragment for URL {} and issueId {}: {}",
          repoUrl, issueId, e.getMessage(), e);
      return "Error: Failed to add GitHub issue: " + e.getMessage();
    }
  }

  private IssueService getIssueService() {
    var project = contextManager.getProject();
    var providerType = project.getIssuesProvider().type();
    return switch (providerType) {
      case JIRA -> new JiraIssueService(project);
      default -> new GitHubIssueService(project);
    };
  }

  private GitUiUtil.OwnerRepo parseRepoUrlOrThrow(String repoUrl) {
    var ownerRepo = GitUiUtil.parseOwnerRepoFromUrl(repoUrl);
    if (ownerRepo == null) {
      throw new IllegalArgumentException("Could not parse owner/repo from URL: " + repoUrl);
    }
    // Ensure the provided repo matches the current project's GitHub repo configuration
    validateProjectMatchesRepoOrThrow(ownerRepo.owner(), ownerRepo.repo());
    return ownerRepo;
  }

  /**
   * v1 validation:
   * - Ensure the project is a GitHub repo.
   * - Ensure the provided repo name matches the project folder name.
   */
  private void validateProjectMatchesRepoOrThrow(String owner, String repo) {
    var project = contextManager.getProject();
    if (!project.isGitHubRepo()) {
      throw new IllegalArgumentException("Current project is not configured as a GitHub repository.");
    }
    Path projectRoot = project.getRoot();
    String projectFolderName = projectRoot.getFileName().toString();
    if (!repo.equals(projectFolderName)) {
      throw new IllegalArgumentException(
          "Provided repository '" + owner + "/" + repo + "' does not appear to match current project folder '"
              + projectFolderName + "'. Cross-repo capture is not yet supported.");
    }
  }
}
