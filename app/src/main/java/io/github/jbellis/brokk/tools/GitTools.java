package io.github.jbellis.brokk.tools;

import com.jakewharton.disklrucache.DiskLruCache;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.exception.ContextTooLargeException;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.Llm;
import io.github.jbellis.brokk.exception.LlmException;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.prompts.CommitPrompts;
import io.github.jbellis.brokk.prompts.MergePrompts;
import io.github.jbellis.brokk.prompts.SummarizerPrompts;
import io.github.jbellis.brokk.util.Messages;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.jetbrains.annotations.Nullable;

public class GitTools {

    private static final Logger logger = LogManager.getLogger(GitTools.class);
    private static final int EXPLAIN_COMMIT_FILE_LIMIT = 10;
    private static final int EXPLAIN_COMMIT_LINES_PER_FILE = 1_000;

    // Caching for commit explanations (best-effort using DiskLruCache per project)
    // A ConcurrentHashMap could be used for in-memory caching across runs for a single session
    // but DiskLruCache is preferred for persistence across sessions/restarts

    private final IContextManager cm;

    public GitTools(IContextManager cm) {
        this.cm = cm;
    }

    @Tool(
            "Explain a single commit by summarizing its diff vs its parent. Use this to understand intent behind changes.")
    public String explainCommit(
            @P("Commit id (or revision)") String revision,
            @P("Why you need this explanation (optional).") @Nullable String reasoning)
            throws InterruptedException {
        // Use the cached path with a detailed explanation
        return explainCommitCached(cm, revision, true);
    }

    /**
     * Explain a single commit with caching on the project's DiskLruCache (best-effort).
     * If {@code detailed} is true, a full explanation is generated using MergePrompts.collectMessages.
     * If {@code detailed} is false, a concise summary is generated using SummarizerPrompts.collectMessages (100 words).
     * Handles ContextTooLargeException by halving lines-per-file and retrying.
     */
    public static String explainCommitCached(IContextManager cm, String revision, boolean detailed)
            throws InterruptedException {
        var repo = (GitRepo) cm.getProject().getRepo();
        var shortHash = repo.shortHash(revision);
        var cacheKey = (detailed ? "explain-" : "summarize-") + shortHash;

        DiskLruCache cache = cm.getProject().getDiskCache();
        try (var snapshot = cache.get(cacheKey)) {
            if (snapshot != null) {
                try (var is = snapshot.getInputStream(0)) {
                    var bytes = is.readAllBytes();
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            logger.warn("Disk cache read failed for {}: {}", cacheKey, e.toString());
            // fallthrough to compute explanation
        }

        // Compute explanation
        String diff;
        try {
            diff = getDiffForRevision(repo, revision);
        } catch (GitAPIException e) {
            throw new RuntimeException("Failed to get diff for revision " + revision, e);
        }

        if (diff.isBlank()) {
            return "No changes detected for %s.".formatted(shortHash);
        }

        StreamingChatModel modelToUse = cm.getService().getScanModel();
        Llm llm = cm.getLlm(modelToUse, (detailed ? "Explain commit " : "Summarize commit ") + shortHash);
        String explanation = explainWithHalving(diff, detailed, revision, llm);

        // Try to write into cache (best-effort)
        DiskLruCache.Editor editor = null;
        boolean editorCommitted = false;
        try {
            editor = cache.edit(cacheKey);
            if (editor != null) {
                try (var os = editor.newOutputStream(0)) {
                    os.write(explanation.getBytes(StandardCharsets.UTF_8));
                }
                editor.commit();
                editorCommitted = true;
            }
        } catch (IOException e) {
            logger.warn("Disk cache write failed for {}: {}", cacheKey, e.toString());
        } finally {
            if (editor != null && !editorCommitted) {
                try {
                    editor.abort();
                } catch (IOException ignored) {
                    // Best-effort: ignore abort failures
                }
            }
        }

        return explanation;
    }

    private static String explainWithHalving(String diff, boolean detailed, String revision, Llm llm)
            throws InterruptedException {
        int linesPerFile = EXPLAIN_COMMIT_LINES_PER_FILE;
        while (true) {
            String preprocessedDiff = Messages.getApproximateTokens(diff) > 100_000
                    ? CommitPrompts.instance.preprocessUnifiedDiff(diff, EXPLAIN_COMMIT_FILE_LIMIT, linesPerFile)
                    : diff;

            try {
                Llm.StreamingResult response;
                if (detailed) {
                    var messages = MergePrompts.instance.collectMessages(preprocessedDiff, revision, revision);
                    response = llm.sendRequest(messages);
                } else {
                    var messages = SummarizerPrompts.instance.collectMessages(preprocessedDiff, 100);
                    response = llm.sendRequest(messages);
                }

                if (response.error() != null) {
                    throw new LlmException(
                            "LLM error while " + (detailed ? "explaining" : "summarizing")
                                    + " commit %s".formatted(revision),
                            response.error());
                }

                return response.text().trim();
            } catch (ContextTooLargeException e) {
                if (linesPerFile <= 100) {
                    logger.debug("Context still too large with minimum lines-per-file (50); giving up.");
                    throw e;
                }
                linesPerFile = linesPerFile / 2;
                logger.debug(
                        "Context too large for commit explanation; halving lines-per-file to {} and retrying.",
                        linesPerFile);
            }
        }
    }

    /**
     * Helper to get the diff between a commit and its parent (or empty tree for root commits).
     */
    private static String getDiffForRevision(GitRepo repo, String revision) throws GitAPIException {
        String parentRev = revision + "^";
        try {
            // If parent cannot be resolved (e.g., revision is a root commit), fall back to the empty tree.
            repo.resolveToObject(parentRev);
            return repo.getDiff(parentRev, revision);
        } catch (GitAPIException e) {
            // If resolving parent fails, it's likely a root commit or invalid revision
            return repo.getDiff(Constants.EMPTY_TREE_ID.getName(), revision);
        }
    }
}
