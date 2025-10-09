package io.github.jbellis.brokk.git;

import com.google.common.base.Splitter;
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
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.prompts.CommitPrompts;
import io.github.jbellis.brokk.prompts.MergePrompts;
import io.github.jbellis.brokk.prompts.SummarizerPrompts;
import io.github.jbellis.brokk.util.Messages;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.jetbrains.annotations.Nullable;

public final class GitWorkflow {
    private static final Logger logger = LogManager.getLogger(GitWorkflow.class);
    private static final int EXPLAIN_COMMIT_FILE_LIMIT = 50;

    public record CommitResult(String commitId, String firstLine) {}

    public record PushPullState(boolean hasUpstream, boolean canPull, boolean canPush, Set<String> unpushedCommitIds) {}

    public record BranchDiff(List<CommitInfo> commits, List<GitRepo.ModifiedFile> files, @Nullable String mergeBase) {}

    public record PrSuggestion(String title, String description, boolean usedCommitMessages) {}

    private final IContextManager contextManager;
    private final GitRepo repo;

    // Fields for tool calling results
    @Nullable
    private String prTitle;

    @Nullable
    private String prDescription;

    public GitWorkflow(IContextManager contextManager) {
        this.contextManager = contextManager;
        this.repo = (GitRepo) contextManager.getProject().getRepo();
    }

    /**
     * Synchronously commit the given files. If {@code files} is empty, commit all modified files. If {@code rawMessage}
     * is null/blank, a suggestion will be generated (may still be blank). Comment lines (# …) are removed.
     */
    public CommitResult commit(List<ProjectFile> files, @Nullable String rawMessage) throws GitAPIException {
        var filesToCommit = files.isEmpty()
                ? repo.getModifiedFiles().stream()
                        .map(GitRepo.ModifiedFile::file)
                        .toList()
                : files;

        if (filesToCommit.isEmpty()) {
            throw new IllegalStateException("No files to commit.");
        }

        String msg = normaliseMessage(rawMessage);
        if (msg.isBlank()) {
            // suggestCommitMessage can throw RuntimeException if diffing fails
            // or InterruptedException occurs. Let it propagate.
            msg = suggestCommitMessage(filesToCommit);
        }

        if (msg.isBlank()) {
            throw new IllegalStateException("No commit message available after attempting suggestion.");
        }

        String sha = repo.commitFiles(filesToCommit, msg);
        var first = msg.contains("\n") ? msg.substring(0, msg.indexOf('\n')) : msg;
        return new CommitResult(sha, first);
    }

    /**
     * Background helper that returns a suggestion or empty string. The caller decides on threading; no Swing here. Can
     * throw RuntimeException if diffing fails or InterruptedException occurs.
     */
    public String suggestCommitMessage(List<ProjectFile> files) {
        logger.debug("Suggesting commit message for {} files", files.size());

        String diff;
        try {
            diff = files.isEmpty() ? repo.diff() : repo.diffFiles(files);
        } catch (GitAPIException e) {
            logger.error("Git diff operation failed while suggesting commit message", e);
            throw new RuntimeException("Failed to generate diff for commit message suggestion", e);
        }

        if (diff.isBlank()) {
            logger.debug("Empty diff - no commit message to suggest");
            return "";
        }

        var messages = CommitPrompts.instance.collectMessages(contextManager.getProject(), diff);
        if (messages.isEmpty()) {
            logger.debug("No commit message generated - diff preprocessing returned empty result");
            return "";
        }

        Llm.StreamingResult result;
        try {
            result = contextManager
                    .getLlm(contextManager.getService().quickestModel(), "Infer commit message")
                    .sendRequest(messages);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Commit message suggestion was interrupted", ie);
        }

        return result.error() == null ? result.text() : "";
    }

    public PushPullState evaluatePushPull(String branch) throws GitAPIException {
        if (repo.isRemoteBranch(branch) || isSyntheticBranchName(branch)) {
            return new PushPullState(false, false, false, Set.of());
        }
        boolean hasUpstream = repo.hasUpstreamBranch(branch);
        Set<String> unpushedCommitIds = hasUpstream ? repo.getUnpushedCommitIds(branch) : new HashSet<>();
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
            repo.push(branch);
            return "Pushed " + branch;
        } else {
            // Check if there are any commits to push before setting upstream.
            // This avoids an empty push -N "origin" "branch:branch" if the branch is empty or fully pushed.
            // However, listCommitsDetailed includes all commits, not just unpushed.
            // For a new branch, any commit is "unpushed" relative to a non-existent remote.
            if (repo.listCommitsDetailed(branch).isEmpty()) {
                return "Branch " + branch + " is empty. Nothing to push.";
            }
            repo.pushAndSetRemoteTracking(branch, "origin");
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
        repo.pull(); // Assumes pull on current branch is intended if branchName matches
        return "Pulled " + branch;
    }

    public BranchDiff diffBetweenBranches(String source, String target) throws GitAPIException {
        var commits = repo.listCommitsBetweenBranches(source, target, /*excludeMergeCommitsFromTarget*/ true);
        var files = repo.listFilesChangedBetweenBranches(source, target);
        var merge = repo.getMergeBase(source, target);
        return new BranchDiff(commits, files, merge);
    }

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
        String diff = (mergeBase != null) ? repo.showDiff(source, mergeBase) : "";

        var service = contextManager.getService();
        var preferredModel = service.getModel(Service.GPT_5_MINI);
        var modelToUse = preferredModel != null ? preferredModel : service.quickestModel(); // Fallback

        List<ChatMessage> messages;
        if (diff.length() > service.getMaxInputTokens(modelToUse) * 0.5) {
            var commitMessagesContent = repo.getCommitMessagesBetween(source, target);
            messages = SummarizerPrompts.instance.collectPrTitleAndDescriptionFromCommitMsgs(commitMessagesContent);
        } else {
            messages = SummarizerPrompts.instance.collectPrTitleAndDescriptionMessages(diff);
        }

        var toolSpecs = contextManager.getToolRegistry().getTools(this, List.of("suggestPrDetails"));
        var toolContext = new ToolContext(toolSpecs, ToolChoice.REQUIRED, this);

        var llm = contextManager.getLlm(modelToUse, "PR-description", true);
        llm.setOutput(streamingOutput);
        var result = llm.sendRequest(messages, toolContext, true);

        if (result.error() != null) {
            throw new RuntimeException("LLM error while generating PR details", result.error());
        }

        if (result.toolRequests().isEmpty()) {
            throw new RuntimeException("LLM did not call the suggestPrDetails tool");
        }

        contextManager.getToolRegistry().executeTool(this, result.toolRequests().get(0));

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
        if (repo.branchNeedsPush(source)) {
            push(source);
        }

        // 2. Strip "origin/" prefix for GitHub
        String head = source.replaceFirst("^origin/", "");
        String base = target.replaceFirst("^origin/", "");

        // 3. GitHub call
        var auth = GitHubAuth.getOrCreateInstance(contextManager.getProject());
        var ghRepo = auth.getGhRepository();
        var pr = ghRepo.createPullRequest(title, head, base, body);

        return pr.getHtmlUrl().toURI();
    }

    private static String normaliseMessage(@Nullable String raw) {
        if (raw == null) return "";
        return Arrays.stream(raw.split("\n"))
                .filter(l -> !l.trim().startsWith("#"))
                .collect(Collectors.joining("\n"))
                .trim();
    }

    public static boolean isSyntheticBranchName(String branchName) {
        // Callers (evaluatePushPull, push, pull) ensure branchName is not null.
        return "stashes".equals(branchName) || branchName.startsWith("Search:");
    }

    private String parentOrEmptyTree(String rev) {
        var parentRev = rev + "^";
        try {
            // If parent cannot be resolved (e.g., rev is a root commit), fall back to the empty tree.
            repo.resolve(parentRev);
            return parentRev;
        } catch (GitAPIException e) {
            return Constants.EMPTY_TREE_ID.getName();
        }
    }

    /**
     * Explain a single commit by generating a unified diff between the commit and its parent (or the empty tree if it
     * is a root commit), and asking an LLM to summarize it with emphasis on public API changes.
     *
     * @param model The LLM model to use.
     * @param revision The commit id (or any rev resolvable to a single commit).
     * @return Markdown-formatted explanation text from the LLM (may be empty if an error occurs).
     */
    public String explainCommit(StreamingChatModel model, String revision) {
        if (revision.isBlank()) {
            throw new IllegalArgumentException("revision must be non-blank");
        }

        String diff;
        try {
            // Always explain a single commit relative to its parent (or empty tree)
            diff = repo.showDiff(revision, parentOrEmptyTree(revision));
        } catch (GitAPIException e) {
            throw new RuntimeException("Failed to produce diff for commit " + revision, e);
        }

        var preprocessedDiff = Messages.getApproximateTokens(diff) > 100_000
                ? CommitPrompts.instance.preprocessUnifiedDiff(diff, EXPLAIN_COMMIT_FILE_LIMIT)
                : diff;
        if (preprocessedDiff.isBlank()) {
            return "No changes detected for %s.".formatted(revision);
        }
        var messages = MergePrompts.instance.collectMessages(preprocessedDiff, revision, revision);

        try {
            var shortId = repo.shortHash(revision);
            var llm = contextManager.getLlm(model, "Explain commit %s".formatted(shortId));
            Llm.StreamingResult response = llm.sendRequest(messages);

            if (response.error() != null) {
                logger.warn("LLM returned an error while explaining {}: {}", revision, response.error());

                // 1) Obtain the full commit message if possible
                var commitMessage = "";
                try {
                    var commits = repo.listCommitsDetailed(revision);
                    commitMessage = commits.isEmpty() ? "" : commits.getFirst().message();
                } catch (Exception e) {
                    logger.debug("Could not retrieve commit message for {}", revision, e);
                }

                // 2) Extract file list + statuses from the unified diff
                var entries = parseFileStatuses(diff);
                var filesText = entries.isEmpty() ? "(no files detected in diff)" : String.join("\n", entries);

                // 3) Compose fallback output
                var header = commitMessage.isBlank() ? "Commit %s".formatted(revision) : commitMessage.trim();

                return """
                       %s

                       Modified files:
                       %s
                       """
                        .formatted(header, filesText)
                        .trim();
            }

            return response.text().trim();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Commit explanation was interrupted", ie);
        }
    }

    private static List<String> parseFileStatuses(String diffText) {
        var entries = new java.util.ArrayList<String>();
        String currentFile = null;
        String currentStatus = "M";
        for (var line : Splitter.on('\n').split(diffText)) {
            if (line.startsWith("diff --git ")) {
                if (currentFile != null) {
                    entries.add(currentStatus + " " + currentFile);
                }
                currentFile = null;
                currentStatus = "M";
                var parts = Splitter.on(' ').splitToList(line);
                if (parts.size() >= 4) {
                    var bpath = parts.get(3);
                    currentFile = bpath.startsWith("b/") ? bpath.substring(2) : bpath;
                }
            } else if (line.startsWith("new file mode")) {
                currentStatus = "A";
            } else if (line.startsWith("deleted file mode")) {
                currentStatus = "D";
            }
        }
        if (currentFile != null) {
            entries.add(currentStatus + " " + currentFile);
        }
        return List.copyOf(entries);
    }
}
