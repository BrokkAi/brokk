package ai.brokk.git;

import ai.brokk.ContextManager;
import ai.brokk.GitHubAuth;
import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.TaskResult;
import ai.brokk.agents.ReviewScope;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.prompts.CommitPrompts;
import ai.brokk.prompts.SummarizerPrompts;
import ai.brokk.tools.WorkspaceTools;
import ai.brokk.util.Json;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Code that uses LLMs to interact with Git goes here, instead of GitRepo. */
public final class GitWorkflow {
    private static final Logger logger = LogManager.getLogger(GitWorkflow.class);

    public record CommitResult(String commitId, String firstLine) {}

    public record PushPullState(boolean hasUpstream, boolean canPull, boolean canPush, Set<String> unpushedCommitIds) {}

    public record BranchDiff(List<CommitInfo> commits, List<GitRepo.ModifiedFile> files, @Nullable String mergeBase) {}

    public record PrSuggestion(String title, String description, boolean usedCommitMessages) {}
    record CommitMessageDraft(String subject, @Nullable List<String> body, boolean useBody) {}

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
    public CommitResult commit(Collection<ProjectFile> files, String msg) throws GitAPIException {
        assert !files.isEmpty();
        assert !msg.isBlank();

        String sha = repo.commitFiles(files, msg);
        var first = msg.contains("\n") ? msg.substring(0, msg.indexOf('\n')) : msg;
        return new CommitResult(sha, first);
    }

    /**
     * Background helper that returns a suggested commit message. The caller decides on threading; no Swing here.
     * Returns empty Optional if diff is blank, unparseable, or LLM fails.
     *
     * @param files the files to include in the diff (empty => all modified)
     * @param oneLine request a one-line subject when true; full commit message (subject + body) when false
     */
    public Optional<String> suggestCommitMessage(List<ProjectFile> files, boolean oneLine) throws InterruptedException {
        logger.debug("Suggesting commit message for {} files (oneLine={})", files.size(), oneLine);

        String diff;
        try {
            diff = files.isEmpty() ? repo.diff() : repo.diffFiles(files);
        } catch (GitAPIException e) {
            logger.error("Git diff operation failed while suggesting commit message", e);
            return Optional.empty();
        }

        if (diff.isBlank()) {
            logger.debug("No modifications present in {}", files);
            return Optional.empty();
        }

        var messages = CommitPrompts.instance.collectMessages(cm.getProject(), diff, oneLine);
        if (messages.isEmpty()) {
            logger.debug("No messages generated from diff");
            return Optional.empty();
        }
        var model = cm.getService().getModel(ModelType.COMMIT_MESSAGE);
        var llm = cm.getLlm(model, "Infer commit message", TaskResult.Type.SUMMARIZE);
        var result = llm.sendRequest(messages, structuredCommitRequestOptions(llm, model));

        if (result.error() != null) {
            logger.debug(
                    "LLM failed to generate commit message: {}", result.error().getMessage());
            return Optional.empty();
        }
        return parseCommitMessageDraft(result.text(), oneLine).map(GitWorkflow::formatCommitMessage);
    }

    /**
     * Streaming variant of suggestCommitMessage. Streams tokens to the provided IConsoleIO while the LLM runs.
     * Blocks and should be executed off the EDT. Throws RuntimeException on LLM errors.
     */
    public String suggestCommitMessageStreaming(
            Collection<ProjectFile> files, boolean oneLine, IConsoleIO streamingOutput)
            throws GitAPIException, InterruptedException {
        logger.debug("Suggesting commit message (streaming) for {} files (oneLine={})", files.size(), oneLine);

        String diff;
        try {
            diff = files.isEmpty() ? repo.diff() : repo.diffFiles(files);
        } catch (GitAPIException e) {
            logger.error("Git diff operation failed while suggesting commit message (streaming)", e);
            throw e;
        }

        if (diff.isBlank()) {
            logger.debug("No modifications present in {}", files);
            throw new RuntimeException("No modifications to suggest a commit message for.");
        }

        var messages = CommitPrompts.instance.collectMessages(cm.getProject(), diff, oneLine);
        if (messages.isEmpty()) {
            logger.debug("No messages generated from diff");
            throw new RuntimeException("Unable to construct LLM messages for commit suggestion.");
        }

        var modelToUse = cm.getService().getModel(ModelType.COMMIT_MESSAGE);
        var llm = cm.getLlm(
                new Llm.Options(modelToUse, "Infer commit message (streaming)", TaskResult.Type.SUMMARIZE).withEcho());
        llm.setOutput(streamingOutput);
        var result = llm.sendRequest(messages, structuredCommitRequestOptions(llm, modelToUse));

        if (result.error() != null) {
            throw new RuntimeException("LLM error while generating commit message", result.error());
        }

        return parseCommitMessageDraft(result.text(), oneLine)
                .map(GitWorkflow::formatCommitMessage)
                .orElseThrow(() -> new RuntimeException("LLM returned an invalid commit message payload."));
    }

    private Llm.RequestOptions structuredCommitRequestOptions(
            Llm llm, dev.langchain4j.model.chat.StreamingChatModel model) {
        var options = llm.requestOptions();
        if (!cm.getService().supportsJsonSchema(model)) {
            return options;
        }

        var schema = JsonSchema.builder()
                .name("CommitMessageDraft")
                .rootElement(JsonObjectSchema.builder()
                        .addProperty("subject", new JsonStringSchema())
                        .addProperty(
                                "body",
                                JsonArraySchema.builder().items(new JsonStringSchema()).build())
                        .addProperty("useBody", new JsonBooleanSchema())
                        .required("subject", "body", "useBody")
                        .additionalProperties(false)
                        .build())
                .build();

        return options.withResponseFormat(ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(schema)
                .build());
    }

    static Optional<CommitMessageDraft> parseCommitMessageDraft(@Nullable String rawText, boolean oneLine) {
        if (rawText == null || rawText.isBlank()) {
            return Optional.empty();
        }

        var direct = parseCommitMessageDraftJson(rawText, oneLine);
        if (direct.isPresent()) {
            return direct;
        }

        var candidates = extractBalancedJsonObjects(rawText);
        for (int i = candidates.size() - 1; i >= 0; i--) {
            var parsed = parseCommitMessageDraftJson(candidates.get(i), oneLine);
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return Optional.empty();
    }

    private static Optional<CommitMessageDraft> parseCommitMessageDraftJson(String candidateText, boolean oneLine) {
        try {
            var root = Json.getMapper().readTree(candidateText);
            if (!root.isObject()) {
                return Optional.empty();
            }

            var subjectNode = root.get("subject");
            var bodyNode = root.get("body");
            var useBodyNode = root.get("useBody");
            if (subjectNode == null || !subjectNode.isTextual() || bodyNode == null || !bodyNode.isArray()
                    || useBodyNode == null || !useBodyNode.isBoolean()) {
                return Optional.empty();
            }

            var body = new ArrayList<String>();
            for (var item : bodyNode) {
                if (!item.isTextual()) {
                    return Optional.empty();
                }
                var line = item.asText().strip();
                if (!line.isEmpty()) {
                    body.add(line);
                }
            }

            var subject = subjectNode.asText().strip();
            boolean useBody = useBodyNode.asBoolean();
            if (oneLine) {
                useBody = false;
                body.clear();
            }

            var draft = new CommitMessageDraft(subject, body, useBody);
            return isValidCommitMessageDraft(draft, oneLine) ? Optional.of(draft) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static boolean isValidCommitMessageDraft(CommitMessageDraft draft, boolean oneLine) {
        if (draft.subject().isBlank() || draft.subject().contains("\n") || draft.subject().length() > 72) {
            return false;
        }
        if (containsMetaNarration(draft.subject())) {
            return false;
        }

        var body = draft.body() == null ? List.<String>of() : draft.body();
        if (!draft.useBody() && !body.isEmpty()) {
            return false;
        }
        if (draft.useBody() && body.isEmpty()) {
            return false;
        }
        if (oneLine && (!body.isEmpty() || draft.useBody())) {
            return false;
        }

        for (var line : body) {
            if (line.isBlank() || line.contains("\n") || line.length() > 72 || containsMetaNarration(line)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsMetaNarration(String text) {
        var lower = text.toLowerCase();
        return lower.startsWith("subject:")
                || lower.startsWith("body:")
                || lower.startsWith("final check:")
                || lower.startsWith("wait,")
                || lower.contains("the prompt says")
                || lower.contains("i'll go with")
                || lower.contains("let's stick to")
                || lower.contains("one more check");
    }

    static String formatCommitMessage(CommitMessageDraft draft) {
        var body = draft.body() == null ? List.<String>of() : draft.body();
        if (!draft.useBody() || body.isEmpty()) {
            return draft.subject();
        }
        return draft.subject() + "\n\n" + String.join("\n", body);
    }

    private static List<String> extractBalancedJsonObjects(String text) {
        var objects = new ArrayList<String>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escape = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }

            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(text.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
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
        return push(branch, null);
    }

    public String push(String branch, @Nullable String githubToken) throws GitAPIException {
        // This check prevents attempting to push special views like "Search:" or "stashes"
        // or remote branches directly.
        if (repo.isRemoteBranch(branch) || isSyntheticBranchName(branch)) {
            logger.warn("Push attempted on invalid context: {}", branch);
            throw new GitOperationException("Push is not supported for this view: " + branch);
        }

        if (repo.hasUpstreamBranch(branch)) {
            repo.remote().push(branch, githubToken);
            return "Pushed " + branch;
        } else {
            // Check if there are any commits to push before setting upstream.
            // This avoids an empty push -N "origin" "branch:branch" if the branch is empty or fully pushed.
            // However, listCommitsDetailed includes all commits, not just unpushed.
            // For a new branch, any commit is "unpushed" relative to a non-existent remote.
            if (repo.listCommitsDetailed(branch).isEmpty()) {
                return "Branch " + branch + " is empty. Nothing to push.";
            }
            repo.remote().pushAndSetRemoteTracking(branch, "origin", branch, githubToken);
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

    public BranchDiff diffBetweenBranches(String oldBranch, String newBranch) throws GitAPIException {
        var commits = repo.listCommitsBetweenBranches(oldBranch, newBranch, /*excludeMergeCommitsFromTarget*/ true);
        var files = repo.listFilesChangedBetweenBranches(oldBranch, newBranch);
        var merge = repo.getMergeBase(newBranch, oldBranch);
        return new BranchDiff(commits, files, merge);
    }

    @SuppressWarnings("unused")
    @Tool("Suggest pull request title and description based on the changes")
    public void suggestPrDetails(
            @P("Brief PR title (12 words or fewer, include intent)") String title,
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
    @Blocking
    public PrSuggestion suggestPullRequestDetails(String source, String target, IConsoleIO streamingOutput)
            throws GitAPIException, InterruptedException {
        return suggestPullRequestDetails(source, target, streamingOutput, List.of());
    }

    /**
     * Suggests pull request title and description with streaming output using tool calling and session context.
     *
     * @param source The source branch name
     * @param target The target branch name
     * @param streamingOutput IConsoleIO for streaming output
     * @param sessionIds List of session IDs to extract context from (may be empty)
     * @throws GitAPIException if git operations fail
     * @throws InterruptedException if the calling thread is interrupted during LLM request
     */
    @Blocking
    public PrSuggestion suggestPullRequestDetails(
            String source, String target, IConsoleIO streamingOutput, List<UUID> sessionIds)
            throws GitAPIException, InterruptedException {
        var mergeBase = repo.getMergeBase(source, target);
        String diff = (mergeBase != null) ? repo.getDiff(mergeBase, source) : "";

        var service = cm.getService();
        var modelToUse = service.getScanModel();

        String sessionContext = null;
        if (!sessionIds.isEmpty()) {
            // Compute edited files to exclude from "Sources Used" fragment hints
            var changedFiles = repo.listFilesChangedBetweenBranches(target, source);
            var editedFiles =
                    changedFiles.stream().map(GitRepo.ModifiedFile::file).collect(Collectors.toSet());

            var extracted = ReviewScope.extractSessionContext(cm, sessionIds, editedFiles);
            if (!extracted.patchInstructions().isEmpty()) {
                sessionContext =
                        """
                        Patch Instructions:
                        %s
                        """
                                .formatted(String.join("\n-----\n", extracted.patchInstructions()));
            }
        }

        List<ChatMessage> messages;
        if (diff.length() > service.getMaxInputTokens(modelToUse) * 0.5) {
            var commitMessagesContent = repo.getCommitMessagesBetween(target, source);
            messages = SummarizerPrompts.instance.collectPrTitleAndDescriptionFromCommitMsgsWithContext(
                    commitMessagesContent, sessionContext);
        } else {
            messages = SummarizerPrompts.instance.collectPrTitleAndDescriptionMessagesWithContext(diff, sessionContext);
        }

        // Register tool providers
        var tr = cm.getToolRegistry()
                .builder()
                .register(this)
                .register(new WorkspaceTools(((ContextManager) cm).liveContext()))
                .build();

        var toolSpecs = new ArrayList<ToolSpecification>();
        toolSpecs.addAll(tr.getTools(List.of("suggestPrDetails")));
        var toolContext = new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr);

        var llm = cm.getLlm(new Llm.Options(modelToUse, "PR-description", TaskResult.Type.SUMMARIZE)
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
        return createPullRequest(source, target, title, body, null);
    }

    /** Pushes branch if needed and opens a PR. Returns the PR url. */
    public URI createPullRequest(String source, String target, String title, String body, @Nullable String githubToken)
            throws Exception {
        // 1. Ensure branch is pushed
        if (repo.remote().branchNeedsPush(source)) {
            push(source, githubToken);
        }

        // 2. Strip "origin/" prefix for GitHub
        String head = source.replaceFirst("^origin/", "");
        String base = target.replaceFirst("^origin/", "");

        // 3. GitHub call
        var auth = (githubToken == null)
                ? GitHubAuth.getOrCreateInstance(cm.getProject())
                : GitHubAuth.createForProject(cm.getProject(), githubToken);
        var ghRepo = auth.getGhRepository();
        var pr = ghRepo.createPullRequest(title, head, base, body);

        return pr.getHtmlUrl().toURI();
    }

    public static boolean isSyntheticBranchName(String branchName) {
        // Callers (evaluatePushPull, push, pull) ensure branchName is not null.
        return "stashes".equals(branchName) || branchName.startsWith("Search:");
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

        var filesToCommit = modified.stream().map(GitRepo.ModifiedFile::file).toList();

        // For auto-commit we prefer a concise one-line subject
        var message = suggestCommitMessage(filesToCommit, true).orElse(taskDescription);

        var commitResult = commit(filesToCommit, message);

        // Friendly notification: include short hash.
        io.showNotification(
                IConsoleIO.NotificationRole.INFO,
                "Committed " + repo.shortHash(commitResult.commitId()) + ": " + commitResult.firstLine());
        return Optional.of(commitResult);
    }
}
