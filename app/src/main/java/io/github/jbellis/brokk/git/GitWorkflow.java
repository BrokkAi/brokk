package io.github.jbellis.brokk.git;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import io.github.jbellis.brokk.GitHubAuth;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.Llm;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.exception.LlmException;
import io.github.jbellis.brokk.prompts.CommitPrompts;
import io.github.jbellis.brokk.prompts.MergePrompts;
import io.github.jbellis.brokk.prompts.SummarizerPrompts;
import io.github.jbellis.brokk.util.Messages;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Code that uses LLMs to interact with Git goes here, instead of GitRepo. */
public final class GitWorkflow {
    private static final Logger logger = LogManager.getLogger(GitWorkflow.class);
    private static final int EXPLAIN_COMMIT_FILE_LIMIT = 50;

    public record CommitResult(String commitId, String firstLine) {}

    public record PushPullState(boolean hasUpstream, boolean canPull, boolean canPush, Set<String> unpushedCommitIds) {}

    public record BranchDiff(List<CommitInfo> commits, List<GitRepo.ModifiedFile> files, @Nullable String mergeBase) {}

    public record PrSuggestion(String title, String description, boolean usedCommitMessages) {}

    private final IContextManager cm;
    private final GitRepo repo;

    // Fields for tool calling results
    @Nullable
    private String prTitle;

    @Nullable
    private String prDescription;

    public GitWorkflow(IContextManager contextManager) {
        this.cm = contextManager;
        this.repo = (GitRepo) contextManager.getProject().getRepo();
    }

    /** Synchronously commit the given files. If {@code files} is empty, commit all modified files. */
    public CommitResult commit(List<ProjectFile> files, String msg) throws GitAPIException {
        assert !files.isEmpty();
        assert !msg.isBlank();

        String sha = repo.commitFiles(files, msg);
        var first = msg.contains("\n") ? msg.substring(0, msg.indexOf('\n')) : msg;
        return new CommitResult(sha, first);
    }

    /**
     * Background helper that returns a suggestion or empty string. The caller decides on threading; no Swing here. Can
     * throw RuntimeException if diffing fails or InterruptedException occurs.
     */
    public String suggestCommitMessage(List<ProjectFile> files, String taskDescription) throws InterruptedException {
        logger.debug("Suggesting commit message for {} files", files.size());

        String diff;
        try {
            diff = files.isEmpty() ? repo.diff() : repo.diffFiles(files);
        } catch (GitAPIException e) {
            logger.error("Git diff operation failed while suggesting commit message", e);
            throw new RuntimeException("Failed to generate diff for commit message suggestion", e);
        }

        if (diff.isBlank()) {
            throw new IllegalStateException("No modifications present in %s".formatted(files));
        }

        var messages = CommitPrompts.instance.collectMessages(cm.getProject(), diff);
        Llm.StreamingResult result;
        result = cm.getLlm(cm.getService().quickestModel(), "Infer commit message")
                .sendRequest(messages);

        return result.error() == null ? result.text() : taskDescription;
    }

    public PushPullState evaluatePushPull(String branch) throws GitAPIException {
        if (repo.isRemoteBranch(branch) || isSyntheticBranchName(branch)) {
            return new PushPullState(false, false, false, Set.of());
        }
        boolean hasUpstream = repo.hasUpstreamBranch(branch);
        Set<String> unpushedCommitIds;
        unpushedCommitIds = hasUpstream ? repo.remote().getUnpushedCommitIds(branch) : new HashSet<String>();
        boolean canPull = hasUpstream;
        boolean canPush = hasUpstream
                && !unpushedCommitIds.isEmpty(); // Can only push if there's an upstream and unpushed commits
        // or if no upstream but local commits exist (handled in push method)
        if (!hasUpstream && !repo.listCommitsDetailed(branch).isEmpty()) { // local branch with commits but no upstream
            canPush = true;
        }

        return new PushPullState(hasUpstream, canPull, canPush, unpushedCommitIds);
    }

    public String push(String branch) throws GitAPIException {
        // This check prevents attempting to push special views like "Search:" or "stashes"
        // or remote branches directly.
        if (repo.isRemoteBranch(branch) || isSyntheticBranchName(branch)) {
            logger.warn("Push attempted on invalid context: {}", branch);
            throw new GitOperationException("Push is not supported for this view: " + branch);
        }

        if (repo.hasUpstreamBranch(branch)) {
            repo.remote().push(branch);
            return "Pushed " + branch;
        } else {
            // Check if there are any commits to push before setting upstream.
            // This avoids an empty push -N "origin" "branch:branch" if the branch is empty or fully pushed.
            // However, listCommitsDetailed includes all commits, not just unpushed.
            // For a new branch, any commit is "unpushed" relative to a non-existent remote.
            if (repo.listCommitsDetailed(branch).isEmpty()) {
                return "Branch " + branch + " is empty. Nothing to push.";
            }
            repo.remote().pushAndSetRemoteTracking(branch, "origin");
            return "Pushed " + branch + " and set upstream to origin/" + branch;
        }
    }

    public String pull(String branch) throws GitAPIException {
        // This check prevents attempting to pull special views like "Search:" or "stashes"
        // or remote branches directly.
        if (repo.isRemoteBranch(branch) || isSyntheticBranchName(branch)) {
            logger.warn("Pull attempted on invalid context: {}", branch);
            throw new GitOperationException("Pull is not supported for this view: " + branch);
        }

        if (!repo.hasUpstreamBranch(branch)) {
            throw new GitOperationException("Branch '" + branch + "' has no upstream branch configured for pull.");
        }
        // Assumes pull on current branch is intended if branchName matches
        repo.remote().pull();
        return "Pulled " + branch;
    }

    public BranchDiff diffBetweenBranches(String source, String target) throws GitAPIException {
        var commits = repo.listCommitsBetweenBranches(source, target, /*excludeMergeCommitsFromTarget*/ true);
        var files = repo.listFilesChangedBetweenBranches(source, target);
        var merge = repo.getMergeBase(source, target);
        return new BranchDiff(commits, files, merge);
    }

    @SuppressWarnings("unused")
    @Tool("Suggest pull request title and description based on the changes")
    public void suggestPrDetails(
            @P("Brief PR title (12 words or fewer)") String title,
            @P("PR description in markdown (75-150 words, focus on intent and key changes)") String description) {
        this.prTitle = title;
        this.prDescription = description;
    }

    /**
     * Suggests pull request title and description with streaming output using tool calling. Blocks; caller should
     * off-load to a background thread (SwingWorker, etc.). Interruption is detected during LLM request and propagates
     * as InterruptedException.
     *
     * @param source The source branch name
     * @param target The target branch name
     * @param streamingOutput IConsoleIO for streaming output
     * @throws GitAPIException if git operations fail
     * @throws InterruptedException if the calling thread is interrupted during LLM request
     */
    public PrSuggestion suggestPullRequestDetails(String source, String target, IConsoleIO streamingOutput)
            throws GitAPIException, InterruptedException {
        var mergeBase = repo.getMergeBase(source, target);
        String diff = (mergeBase != null) ? repo.getDiff(mergeBase, source) : "";

        var service = cm.getService();
        var modelToUse = service.getScanModel();

        List<ChatMessage> messages;
        if (diff.length() > service.getMaxInputTokens(modelToUse) * 0.5) {
            var commitMessagesContent = repo.getCommitMessagesBetween(source, target);
            messages = SummarizerPrompts.instance.collectPrTitleAndDescriptionFromCommitMsgs(commitMessagesContent);
        } else {
            messages = SummarizerPrompts.instance.collectPrTitleAndDescriptionMessages(diff);
        }

        // Register tool providers
        var tr = cm.getToolRegistry()
                .builder()
                .register(this)
                .register(new io.github.jbellis.brokk.tools.WorkspaceTools((io.github.jbellis.brokk.ContextManager) cm))
                .build();

        var toolSpecs = new ArrayList<dev.langchain4j.agent.tool.ToolSpecification>();
        toolSpecs.addAll(tr.getTools(List.of("suggestPrDetails")));
        var toolContext = new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr);

        var llm = cm.getLlm(new Llm.Options(modelToUse, "PR-description")
                .withPartialResponses()
                .withEcho());
        llm.setOutput(streamingOutput);
        var result = llm.sendRequest(messages, toolContext);

        if (result.error() != null) {
            throw new RuntimeException("LLM error while generating PR details", result.error());
        }

        if (result.toolRequests().isEmpty()) {
            throw new RuntimeException("LLM did not call the suggestPrDetails tool");
        }

        tr.executeTool(result.toolRequests().getFirst());

        String title = prTitle;
        String description = prDescription;

        if (title == null || title.isEmpty() || description == null || description.isEmpty()) {
            throw new RuntimeException("LLM provided empty title or description");
        }

        return new PrSuggestion(title, description, diff.length() / 3.0 > service.getMaxInputTokens(modelToUse) * 0.9);
    }

    /** Pushes branch if needed and opens a PR. Returns the PR url. */
    public URI createPullRequest(String source, String target, String title, String body) throws Exception {
        // 1. Ensure branch is pushed
        if (repo.remote().branchNeedsPush(source)) {
            push(source);
        }

        // 2. Strip "origin/" prefix for GitHub
        String head = source.replaceFirst("^origin/", "");
        String base = target.replaceFirst("^origin/", "");

        // 3. GitHub call
        var auth = GitHubAuth.getOrCreateInstance(cm.getProject());
        var ghRepo = auth.getGhRepository();
        var pr = ghRepo.createPullRequest(title, head, base, body);

        return pr.getHtmlUrl().toURI();
    }

    public static boolean isSyntheticBranchName(String branchName) {
        // Callers (evaluatePushPull, push, pull) ensure branchName is not null.
        return "stashes".equals(branchName) || branchName.startsWith("Search:");
    }

    private String parentOrEmptyTree(String rev) {
        var parentRev = rev + "^";
        try {
            // If parent cannot be resolved (e.g., rev is a root commit), fall back to the empty tree.
            repo.resolveToObject(parentRev);
            return parentRev;
        } catch (GitAPIException e) {
            return Constants.EMPTY_TREE_ID.getName();
        }
    }

    /**
     * Auto-commit any modified files with a message that incorporates the task description.
     *
     * <p>This was previously implemented inside ContextManager. It has been moved into GitWorkflow so that all Git
     * operations (diffing/committing/suggesting messages) live in the Git domain class.
     *
     * <p>Behavior: - If modified files cannot be determined, show a tool error and return. - If no modified files, show
     * an informational notification. - Otherwise, suggest a commit message (falls back to taskDescription) and commit
     * the files. - On success, show a friendly notification and update the commit panel; on failure, show a tool error.
     */
    public Optional<CommitResult> performAutoCommit(String taskDescription) throws InterruptedException {
        try {
            return performAutoCommitInternal(taskDescription);
        } catch (GitAPIException e) {
            cm.getIo().showNotification(IConsoleIO.NotificationRole.ERROR, "Auto-commit failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    private @NotNull Optional<CommitResult> performAutoCommitInternal(String taskDescription)
            throws GitAPIException, InterruptedException {
        var io = cm.getIo();
        Set<GitRepo.ModifiedFile> modified;
        modified = repo.getModifiedFiles();

        if (modified.isEmpty()) {
            io.showNotification(IConsoleIO.NotificationRole.INFO, "No changes to commit for task: " + taskDescription);
            return Optional.empty();
        }

        var filesToCommit = modified.stream().map(GitRepo.ModifiedFile::file).collect(Collectors.toList());

        String message = suggestCommitMessage(filesToCommit, taskDescription);

        var commitResult = commit(filesToCommit, message);

        // Friendly notification: include short hash.
        io.showNotification(
                IConsoleIO.NotificationRole.INFO,
                "Committed " + repo.shortHash(commitResult.commitId()) + ": " + commitResult.firstLine());
        io.updateCommitPanel();
        return Optional.of(commitResult);
    }

    /**
     * Explain a single commit by generating a unified diff between the commit and its parent (or the empty tree if it
     * is a root commit), and asking an LLM to summarize it with emphasis on public API changes.
     *
     * @param model The LLM model to use.
     * @param revision The commit id (or any rev resolvable to a single commit).
     * @return Markdown-formatted explanation text from the LLM (may be empty if an error occurs).
     */
    public String explainCommit(StreamingChatModel model, String revision)
            throws GitAPIException, InterruptedException {
        assert !revision.isBlank();

        String diff = repo.getDiff(parentOrEmptyTree(revision), revision);

        var preprocessedDiff = Messages.getApproximateTokens(diff) > 100_000
                ? CommitPrompts.instance.preprocessUnifiedDiff(diff, EXPLAIN_COMMIT_FILE_LIMIT)
                : diff;
        if (preprocessedDiff.isBlank()) {
            return "No changes detected for %s.".formatted(revision);
        }
        var messages = MergePrompts.instance.collectMessages(preprocessedDiff, revision, revision);

        var shortId = repo.shortHash(revision);
        var llm = cm.getLlm(model, "Explain commit %s".formatted(shortId));
        Llm.StreamingResult response = llm.sendRequest(messages);

        if (response.error() != null) {
            throw new LlmException("LLM error while explaining commit %s".formatted(shortId), response.error());
        }

        return response.text().trim();
    }
}
