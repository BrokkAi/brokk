package io.github.jbellis.brokk.agents;

import static java.util.Objects.requireNonNull;

import com.jakewharton.disklrucache.DiskLruCache;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.GitWorkflow;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.tools.ToolExecutionResult;
import io.github.jbellis.brokk.tools.ToolRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

/** Encapsulates the per-file agentic merge planning loop. This class hosts the tool methods the LLM can call. */
public final class MergeOneFile {

    private static final Logger logger = LogManager.getLogger(MergeOneFile.class);

    public enum Status {
        RESOLVED,
        UNRESOLVED,
        IO_ERROR
    }

    public record Outcome(Status status, @Nullable String details) {}

    private final IContextManager cm;
    private final StreamingChatModel planningModel;
    private final StreamingChatModel codeModel;
    private final MergeAgent.MergeMode type;
    private final @Nullable String baseCommitId;
    private final String otherCommitId;
    private final Map<ProjectFile, String> mergedTestSources;
    private final List<ProjectFile> allTestFiles;
    private final ConflictAnnotator.ConflictFileCommits conflict;

    private transient @Nullable List<ChatMessage> currentSessionMessages = null;
    private final ToolRegistry tr;

    // Per-merge state
    private boolean abortRequested = false;
    private transient @Nullable String abortExplanation = null;

    // CodeAgent result holder (replaces previous ThreadLocal)
    private transient @Nullable TaskResult lastCodeAgentResult = null;

    public MergeOneFile(
            IContextManager cm,
            StreamingChatModel planningModel,
            StreamingChatModel codeModel,
            MergeAgent.MergeMode type,
            @Nullable String baseCommitId,
            String otherCommitId,
            Map<ProjectFile, String> mergedTestSources,
            List<ProjectFile> allTestFiles,
            ConflictAnnotator.ConflictFileCommits conflict) {
        this.cm = cm;
        this.planningModel = planningModel;
        this.codeModel = codeModel;
        this.type = type;
        this.baseCommitId = baseCommitId;
        this.otherCommitId = otherCommitId;
        this.mergedTestSources = mergedTestSources;
        this.allTestFiles = allTestFiles;
        this.conflict = conflict;
        this.tr = cm.getToolRegistry();
    }

    /** Merge-loop for a single file. Returns an Outcome describing the result. */
    public Outcome merge() throws InterruptedException {
        var repo = (GitRepo) cm.getProject().getRepo();
        var file = conflict.file();
        var llm = cm.getLlm(planningModel, "Merge %s: %s".formatted(repo.shortHash(otherCommitId), file));

        // Reset per-file state
        this.lastCodeAgentResult = null;
        this.abortRequested = false;
        this.abortExplanation = null;

        // Construct first-turn messages
        this.currentSessionMessages = new ArrayList<>();
        var sys = new SystemMessage(
                """
                You are a merge assistant resolving conflicts in ONE file. You can:
                  - Inspect class skeletons / sources / method bodies (if available)
                  - Read raw file contents (fallback)
                  - Explain single commits to understand intent
                When you have enough context, call `callCodeAgent` with concrete instructions; it will perform the edit.
                Do NOT output the final merged file directly; always delegate to CodeAgent.
                Keep changes minimal and strictly related to resolving conflicts and restoring compilation/tests.
                """
                        .stripIndent());
        var header = buildMergeHeader(file, conflict.ourCommits(), conflict.theirCommits());
        var testsSection = buildRelevantTestsSection(file); // may be empty
        var conflicted = readFileAsCodeBlock(file);
        var firstUser = new UserMessage(
                """
                %s

                %s

                <conflicted_file path="%s">
                %s
                </conflicted_file>

                Goal: resolve ALL conflict markers in this file with the minimal change that preserves semantics and passes tests.

                Remember, when making tool calls you can call multiple tools per turn, this will improve your performance.
                """
                        .stripIndent()
                        .formatted(header, testsSection, file.toString(), conflicted));
        currentSessionMessages.add(sys);
        currentSessionMessages.add(firstUser);

        // Tool exposure
        var allowed = new ArrayList<String>();
        if (cm.getAnalyzerWrapper().providesSummaries()) {
            allowed.addAll(List.of("getClassSkeletons", "getClassSources", "getMethodSources"));
        } else {
            allowed.addAll(List.of("getFileContents", "getFileSummaries"));
        }

        var toolSpecs = new ArrayList<ToolSpecification>();
        toolSpecs.addAll(tr.getRegisteredTools(allowed));
        toolSpecs.addAll(
                tr.getTools(this, List.of("explainCommit", "callCodeAgent", "getContentsAtRevision", "abortMerge")));
        var io = cm.getIo();

        // Bounded loop; stop once conflicts are gone or we hit max steps
        final int MAX_STEPS = 10;
        for (int step = 1; step <= MAX_STEPS; step++) {
            if (Thread.interrupted()) throw new InterruptedException();
            io.llmOutput("\n# Merge %s (step %d)".formatted(file, step), ChatMessageType.AI, true, false);

            var result = llm.sendRequest(
                    List.copyOf(currentSessionMessages), new ToolContext(toolSpecs, ToolChoice.REQUIRED, this), true);
            if (result.error() != null) {
                var msg = result.error() != null ? result.error().getMessage() : "Empty response";
                io.showNotification(IConsoleIO.NotificationRole.INFO, "LLM error in merge loop: " + msg);
                break;
            }
            if (!result.text().isBlank()) {
                io.llmOutput("\n" + result.text(), ChatMessageType.AI);
            }

            var ai = ToolRegistry.removeDuplicateToolRequests(result.aiMessage());
            currentSessionMessages.add(ai);

            if (!ai.hasToolExecutionRequests()) {
                continue;
            }
            var ordered = ai.toolExecutionRequests(); // keep AI order
            var sorted = new ArrayList<>(ordered);
            sorted.sort(
                    Comparator.comparingInt(r -> isTerminalToolName(r.name()) ? 1 : 0)); // stable sort; terminals last
            for (var req : sorted) {
                if (Thread.interrupted()) throw new InterruptedException();

                var explanation = cm.getToolRegistry().getExplanationForToolRequest(this, req);
                if (!explanation.isBlank()) {
                    io.llmOutput("\n" + explanation, ChatMessageType.AI);
                }

                ToolExecutionResult exec;
                try {
                    exec = tr.executeTool(this, req);
                } catch (Exception e) {
                    logger.warn("Tool execution failed for {}: {}", req.name(), e.getMessage(), e);
                    exec = ToolExecutionResult.failure(req, "Error: " + e.getMessage());
                }

                currentSessionMessages.add(ToolExecutionResultMessage.from(req, exec.resultText()));
                if (!exec.resultText().isBlank()) {
                    io.llmOutput(exec.resultText(), ChatMessageType.AI);
                }

                if ("callCodeAgent".equals(req.name())) {
                    var last = this.lastCodeAgentResult;
                    if (last != null) {
                        var sd = last.stopDetails();
                        var reason = sd.reason();
                        if (reason == TaskResult.StopReason.IO_ERROR) {
                            logger.warn("CodeAgent reported IO_ERROR for {}: {}", file, sd);
                            return new Outcome(Status.IO_ERROR, formatFailure(file, sd.toString()));
                        }
                        if (reason == TaskResult.StopReason.APPLY_ERROR) {
                            // Nudge the planner: use full-file replacement next
                            var nudge = buildApplyErrorNudgeMessage();
                            currentSessionMessages.add(new UserMessage(nudge));
                            io.llmOutput(nudge, ChatMessageType.USER);
                        }
                    }

                    var textOpt = file.read();
                    if (textOpt.isPresent() && !containsConflictMarkers(textOpt.get())) {
                        // Attempt to stage the resolved file so successful CodeAgent edits are added to the index.
                        try {
                            repo.add(List.of(file));
                            io.llmOutput("Conflicts resolved for " + file + " (staged)", ChatMessageType.AI);
                        } catch (GitAPIException e) {
                            logger.warn("Failed to add {} to index after CodeAgent success: {}", file, e.getMessage());
                            io.showNotification(
                                    IConsoleIO.NotificationRole.INFO,
                                    "Warning: failed to git add " + file + ": " + e.getMessage());
                            io.llmOutput("Conflicts resolved for " + file, ChatMessageType.AI);
                        }
                        return new Outcome(Status.RESOLVED, null);
                    } else {
                        var details = formatFailure(file, exec.resultText());
                        io.llmOutput("CodeAgent failed to resolve conflicts for " + file, ChatMessageType.AI);
                        return new Outcome(Status.UNRESOLVED, details);
                    }
                }
                if (abortRequested) {
                    return new Outcome(
                            Status.UNRESOLVED, abortExplanation == null ? "Aborted by planner" : abortExplanation);
                }
            }
        }
        return new Outcome(Status.UNRESOLVED, null);
    }

    // =====================
    // Tool implementations (hosted on this planner instance)
    // =====================

    @Tool(
            "Explain a single commit by summarizing its diff vs its parent. Use this to understand intent behind changes.")
    public String explainCommit(
            @P("Commit id (or revision)") String revision,
            @P("Why you need this explanation (optional).") @Nullable String reasoning) {
        logger.debug("explainCommit {} reason={}", revision, reasoning);
        return explainCommitCached((ContextManager) cm, revision);
    }

    @Tool("Get the content of a file at a specific revision.")
    public String getContentsAtRevision(
            @P("Repository-relative file path") String filepath, @P("Revision (commit id)") String revision) {
        var repo = (GitRepo) cm.getProject().getRepo();
        var pf = cm.toFile(filepath);
        try {
            return repo.getFileContent(revision, pf);
        } catch (GitAPIException e) {
            logger.warn("Failed to get file content for {} at {}: {}", filepath, revision, e.getMessage());
            return "Error retrieving file content: " + e.getMessage();
        }
    }

    @Tool("Abort the merge planning for this file when resolution is not possible or safe. Provide an explanation.")
    public String abortMerge(
            @P("Short explanation why this merge should be aborted for this file.") String explanation) {
        this.abortRequested = true;
        this.abortExplanation = explanation;
        logger.info("abortMerge requested for {}: {}", conflict.file(), explanation);
        return "Merge aborted for this file: " + explanation;
    }

    // =====================
    // Static explain helper
    // =====================
    // Caching of commit explanations is now stored per-project via DiskLruCache (best-effort).
    // The previous in-memory EXPLAIN_CACHE has been removed.

    /** Explain a single commit with caching on the project's DiskLruCache (best-effort). */
    public static String explainCommitCached(IContextManager cm, String revision) {
        var shortHash = ((GitRepo) cm.getProject().getRepo()).shortHash(revision);
        var key = "explain-" + shortHash;

        DiskLruCache cache = cm.getProject().getDiskCache();
        try (var snapshot = cache.get(key)) {
            if (snapshot != null) {
                try (var is = snapshot.getInputStream(0)) {
                    var bytes = is.readAllBytes();
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            logger.warn("Disk cache read failed for {}: {}", key, e.toString());
            // fallthrough to compute explanation
        }

        // Compute explanation
        var gw = new GitWorkflow(cm);
        var model = requireNonNull(cm.getService().getModel(Service.GPT_5_MINI));
        var explanation = gw.explainCommit(model, shortHash);

        // Try to write into cache (best-effort)
        DiskLruCache.Editor editor = null;
        boolean editorCommitted = false;
        try {
            editor = cache.edit(key);
            if (editor != null) {
                try (var os = editor.newOutputStream(0)) {
                    os.write(explanation.getBytes(StandardCharsets.UTF_8));
                }
                editor.commit();
                editorCommitted = true;
            }
        } catch (IOException e) {
            logger.warn("Disk cache write failed for {}: {}", key, e.toString());
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

    // =====================
    // Helpers
    // =====================

    private String buildMergeHeader(ProjectFile file, Set<String> ourCommits, Set<String> theirCommits) {
        // Determine our current commit id (HEAD). Avoid undefined resolveHead(); use repository state instead.
        var gr = (GitRepo) cm.getProject().getRepo();
        String oursId;
        try {
            oursId = gr.getCurrentCommitId();
        } catch (GitAPIException e) {
            logger.warn("Failed to resolve HEAD commit id: {}", e.getMessage());
            oursId = "HEAD";
        }
        var oursShort = gr.shortHash(oursId);
        var baseShort = baseCommitId == null ? "(none)" : gr.shortHash(baseCommitId);
        var theirsShort = gr.shortHash(otherCommitId);

        var header =
                """
                <merge_context>
                mode: %s
                file: %s
                ours: %s
                base: %s
                theirs: %s
                </merge_context>
                """
                        .stripIndent()
                        .formatted(type, file, oursShort, baseShort, theirsShort);

        // Append one-line commit info for relevant OUR/THEIR commits from this file's blame
        var sections = new ArrayList<String>(2);
        var repo = (GitRepo) cm.getProject().getRepo();

        if (!ourCommits.isEmpty()) {
            var lines = new ArrayList<String>();
            for (var id : ourCommits) {
                String oneLine;
                try {
                    var full = repo.getCommitFullMessage(id);
                    oneLine = full.contains("\n") ? full.substring(0, full.indexOf('\n')) : full;
                } catch (GitAPIException e) {
                    logger.error("Failed to read commit message for {}: {}", id, e.getMessage(), e);
                    oneLine = id;
                }
                lines.add("<commit id=\"" + id + "\">" + escapeXml(oneLine) + "</commit>");
            }
            sections.add("<our_commits>\n" + String.join("\n", lines) + "\n</our_commits>");
        }

        if (!theirCommits.isEmpty()) {
            var lines = new ArrayList<String>();
            for (var id : theirCommits) {
                String oneLine;
                try {
                    var full = repo.getCommitFullMessage(id);
                    oneLine = full.contains("\n") ? full.substring(0, full.indexOf('\n')) : full;
                } catch (GitAPIException e) {
                    logger.error("Failed to read commit message for {}: {}", id, e.getMessage(), e);
                    oneLine = id;
                }
                lines.add("<commit id=\"" + id + "\">" + escapeXml(oneLine) + "</commit>");
            }
            sections.add("<their_commits>\n" + String.join("\n", lines) + "\n</their_commits>");
        }

        return sections.isEmpty() ? header : header + "\n" + String.join("\n", sections);
    }

    private String readFileAsCodeBlock(ProjectFile file) {
        var ext = file.extension();
        var text = file.read().orElse(null);
        if (text == null) {
            return "```text\n<unable to read " + file + ">\n```";
        }
        return "```" + ext + "\n" + text + "\n```";
    }

    private String buildRelevantTestsSection(ProjectFile targetFile) throws InterruptedException {
        if (mergedTestSources.isEmpty() || allTestFiles.isEmpty()) return "";
        var quickest = cm.getService().quickestModel();
        var clf = cm.getLlm(quickest, "Merge/Relevance");

        var candidates = allTestFiles.stream()
                .filter(tf -> !tf.equals(targetFile))
                .filter(mergedTestSources::containsKey)
                .sorted(Comparator.comparing(ProjectFile::toString))
                .toList();

        if (candidates.isEmpty()) return "";

        var filterDescription =
                """
                Determine if the following test is relevant to resolving conflicts in "%s".
                Consider class/method names referenced in the conflict, imports, and behavior expectations.
                Reply strictly with BRK_RELEVANT or BRK_IRRELEVANT.
                """
                        .stripIndent()
                        .formatted(targetFile);

        var relevant = new ArrayList<ProjectFile>();
        for (var tf : candidates) {
            var src = mergedTestSources.get(tf);
            boolean keep;
            try {
                keep = RelevanceClassifier.isRelevant(clf, filterDescription, requireNonNull(src));
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Exception e) {
                logger.warn("RelevanceClassifier failed for {}: {}", tf, e.toString());
                continue;
            }
            if (keep) relevant.add(tf);
        }
        if (relevant.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("<relevant_tests>\n");
        for (var tf : relevant) {
            var src = mergedTestSources.get(tf);
            sb.append("### ").append(tf).append("\n");
            sb.append("```").append(tf.extension()).append("\n").append(src).append("\n```\n\n");
        }
        sb.append("</relevant_tests>");
        return sb.toString();
    }

    private static boolean containsConflictMarkers(String text) {
        return text.contains("<<<<<<<") || text.contains("=======") || text.contains(">>>>>>>");
    }

    /** Build a structured XML snippet for a CodeAgent failure for downstream parsing. */
    private static String formatFailure(ProjectFile file, String details) {
        return """
               <failure file="%s">
               %s
               </failure>
               """
                .stripIndent()
                .formatted(file, details);
    }

    private static boolean isTerminalToolName(String name) {
        return "callCodeAgent".equals(name) || "abortMerge".equals(name);
    }

    private String buildApplyErrorNudgeMessage() {
        return """
               Previous CodeAgent attempt failed due to a patch apply error.
               On your next call to callCodeAgent, use a full-file replacement strategy:
               provide clear, concise instructions to replace the entire file content with the correct,
               fully-resolved version (no conflict markers). Keep changes minimal and only resolve the conflicts.
               """
                .stripIndent();
    }

    /** Escape XML special characters for safe embedding of commit messages. */
    private static String escapeXml(@Nullable String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * Invoke CodeAgent to actually apply the merge edits to the current file. Provide precise instructions for how to
     * resolve the conflicts.
     */
    @Tool(
            "Invoke the Code Agent to resolve conflicts in THIS file. Provide concise, concrete instructions; the agent can see the conversation above.")
    public String callCodeAgent(
            @P("Detailed instructions for resolving the conflicts in this file.") String instructions)
            throws InterruptedException {
        var file = conflict.file();
        logger.debug("callCodeAgent invoked for {} with instructions: {}", file, instructions);

        instructions +=
                "\n\nRemember to use the BRK_CONFLICT_BEGIN_[n]..BRK_CONFLICT_END_[n] markers to simplify your SEARCH/REPLACE blocks!"
                        + "\nYou can also make non-conflict edits if necessary to fix related issues caused by the merge.";
        var agent = new CodeAgent(cm, codeModel, cm.getIo());
        var result = agent.runSingleFileEdit(
                file,
                instructions,
                requireNonNull(currentSessionMessages),
                EnumSet.of(CodePrompts.InstructionsFlags.MERGE_AGENT_MARKERS));
        this.lastCodeAgentResult = result;
        return String.valueOf(result.stopDetails());
    }
}
