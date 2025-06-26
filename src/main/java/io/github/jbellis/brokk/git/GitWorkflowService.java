package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.Llm;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.prompts.CommitPrompts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class GitWorkflowService {
    private static final Logger logger = LogManager.getLogger(GitWorkflowService.class);

    public record CommitResult(String commitId, String firstLine) {
    }

    public record PushPullState(
            boolean hasUpstream,
            boolean canPull,
            boolean canPush,
            Set<String> unpushedCommitIds
    ) {}

    private final ContextManager contextManager;
    private final GitRepo repo;

    public GitWorkflowService(ContextManager contextManager)
    {
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager");
        this.repo = (GitRepo) Objects.requireNonNull(
                contextManager.getProject().getRepo(), "repo cannot be null");
    }

    /**
     * Synchronously commit the given files.  If {@code files} is empty, commit
     * all modified files.  If {@code rawMessage} is null/blank, a suggestion
     * will be generated (may still be blank).  Comment lines (# …) are removed.
     */
    public CommitResult commit(List<ProjectFile> files,
                               @Nullable String rawMessage) throws GitAPIException
    {
        var filesToCommit = files.isEmpty()
                            ? repo.getModifiedFiles()
                                    .stream()
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
        var first = msg.contains("\n") ? msg.substring(0, msg.indexOf('\n'))
                                       : msg;
        return new CommitResult(sha, first);
    }

    /**
     * Background helper that returns a suggestion or empty string.
     * The caller decides on threading; no Swing here.
     * Can throw RuntimeException if diffing fails or InterruptedException occurs.
     */
    public String suggestCommitMessage(List<ProjectFile> files)
    {
        String diff;
        try {
            diff = files.isEmpty()
                   ? repo.diff()
                   : repo.diffFiles(files);
        } catch (GitAPIException e) {
            logger.error("Git diff operation failed while suggesting commit message", e);
            throw new RuntimeException("Failed to generate diff for commit message suggestion", e);
        }

        if (diff.isBlank()) {
            return "";
        }

        var messages = CommitPrompts.instance.collectMessages(contextManager.getProject(), diff);
        if (messages.isEmpty()) {
            return "";
        }

        Llm.StreamingResult result;
        try {
            result = contextManager.getLlm(
                            contextManager.getService().quickestModel(),
                            "Infer commit message")
                    .sendRequest(messages);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Commit message suggestion was interrupted", ie);
        }

        return result.error() == null ? result.text() : "";
    }

    public PushPullState evaluatePushPull(String branch) throws GitAPIException {
        if (branch.contains("/")) { // Remote branches or special views like "Search:" or "stashes"
            return new PushPullState(false, false, false, Set.of());
        }
        boolean hasUpstream = repo.hasUpstreamBranch(branch);
        Set<String> unpushedCommitIds = hasUpstream ? repo.getUnpushedCommitIds(branch) : new HashSet<>();
        boolean canPull = hasUpstream;
        boolean canPush = hasUpstream && !unpushedCommitIds.isEmpty(); // Can only push if there's an upstream and unpushed commits
                                                                    // or if no upstream but local commits exist (handled in push method)
        if (!hasUpstream && !repo.listCommitsDetailed(branch).isEmpty()) { // local branch with commits but no upstream
            canPush = true;
        }

        return new PushPullState(hasUpstream, canPull, canPush, unpushedCommitIds);
    }

    public String push(String branch) throws GitAPIException {
        // This check prevents attempting to push special views like "Search:" or "stashes"
        // or remote branches directly.
        if (branch.contains("/") || "stashes".equals(branch) || branch.startsWith("Search:")) {
            logger.warn("Push attempted on invalid context: {}", branch);
            throw new GitAPIException("Push is not supported for this view: " + branch) {};
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
        if (branch.contains("/") || "stashes".equals(branch) || branch.startsWith("Search:")) {
            logger.warn("Pull attempted on invalid context: {}", branch);
            throw new GitAPIException("Pull is not supported for this view: " + branch) {};
        }

        if (!repo.hasUpstreamBranch(branch)) {
            throw new GitAPIException("Branch '" + branch + "' has no upstream branch configured for pull.") {};
        }
        repo.pull(); // Assumes pull on current branch is intended if branchName matches
        return "Pulled " + branch;
    }

    private static String normaliseMessage(@Nullable String raw)
    {
        if (raw == null) return "";
        return Arrays.stream(raw.split("\n"))
                .filter(l -> !l.trim().startsWith("#"))
                .collect(Collectors.joining("\n"))
                .trim();
    }
}
