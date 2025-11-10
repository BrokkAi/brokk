package ai.brokk.agents;

import static java.util.Objects.requireNonNull;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.git.GitRepo;
import ai.brokk.gui.dialogs.BlitzForgeProgressDialog;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.tools.WorkspaceTools;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

/** Encapsulates the per-file agentic merge planning loop. This class hosts the tool methods the LLM can call. */
public final class MergeOneFile {

    private static final Logger logger = LogManager.getLogger(MergeOneFile.class);

    public enum Status {
        RESOLVED,
        UNRESOLVED,
        INTERRUPTED,
        IO_ERROR
    }

    public record Outcome(Status status, @Nullable String details) {}

    private final IContextManager cm;
    private final StreamingChatModel planningModel;
    private final StreamingChatModel codeModel;
    private final MergeAgent.MergeMode type;
    private final @Nullable String baseCommitId;
    private final String otherCommitId;
    private final ConflictAnnotator.ConflictFileCommits conflict;
    private final IConsoleIO io;

    private @Nullable List<ChatMessage> currentSessionMessages = null;

    // Per-merge state
    private boolean abortRequested = false;
    private @Nullable String abortExplanation = null;

    // CodeAgent result holder (replaces previous ThreadLocal)
    private @Nullable TaskResult lastCodeAgentResult = null;

    // Last instructions sent to CodeAgent for this file (for debugging/visibility)
    private @Nullable String codeAgentInstructions = null;

    public MergeOneFile(
            IContextManager cm,
            StreamingChatModel planningModel,
            StreamingChatModel codeModel,
            MergeAgent.MergeMode type,
            @Nullable String baseCommitId,
            String otherCommitId,
            ConflictAnnotator.ConflictFileCommits conflict,
            IConsoleIO io) {
        this.cm = cm;
        this.planningModel = planningModel;
        this.codeModel = codeModel;
        this.type = type;
        this.baseCommitId = baseCommitId;
        this.otherCommitId = otherCommitId;
        this.conflict = conflict;
        this.io = io;
    }

    /** Merge-loop for a single file. Returns an Outcome describing the result. */
    public Outcome merge() throws InterruptedException {
        var repo = (GitRepo) cm.getProject().getRepo();
        var file = conflict.file();
        var llm =
                cm.getLlm(new Llm.Options(planningModel, "Merge %s: %s".formatted(repo.shortHash(otherCommitId), file))
                        .withEcho());
        llm.setOutput(io);

        // refine the progress bar total to reflect merge complexity
        if (io instanceof BlitzForgeProgressDialog.ProgressAware pa) {
            int conflicted1 = conflict.conflictLineCount();
            if (conflicted1 > 0) {
                pa.setProgressTotal(Math.max(1, 3 * conflicted1));
            }
        }

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
        """);
        var header = buildMergeHeader(file, conflict.ourCommits(), conflict.theirCommits());
        var conflicted = readFileAsCodeBlock(file);
        var firstUser = new UserMessage(
                """
                %s

                <conflicted_file path="%s">
                %s
                </conflicted_file>

                <goal>
                Resolve ALL conflicts with the minimal change that preserves the
                semantics of the changes made in both "theirs" and "ours."
                </goal>

                Remember, when making tool calls you can call multiple tools per turn, this will improve your performance.
                """
                        .formatted(header, file.toString(), conflicted));
        currentSessionMessages.add(sys);
        currentSessionMessages.add(firstUser);

        // Tool exposure
        var allowed = new ArrayList<>(List.of("getClassSkeletons", "getClassSources", "getMethodSources"));

        // Register tools
        var tr = cm.getToolRegistry()
                .builder()
                .register(new WorkspaceTools((ContextManager) cm))
                .register(this)
                .build();

        var toolSpecs = new ArrayList<ToolSpecification>();
        toolSpecs.addAll(tr.getTools(allowed));
        toolSpecs.addAll(tr.getTools(List.of("explainCommit", "callCodeAgent", "getContentsAtRevision", "abortMerge")));

        // Bounded loop; stop once conflicts are gone or we hit max steps
        final int MAX_STEPS = 10;
        for (int step = 1; step <= MAX_STEPS; step++) {
            if (Thread.interrupted()) {
                return new Outcome(Status.INTERRUPTED, null);
            }
            io.llmOutput("\n# Merge %s (step %d)".formatted(file, step), ChatMessageType.AI, true, false);

            Llm.StreamingResult result;
            try {
                result = llm.sendRequest(
                        List.copyOf(currentSessionMessages), new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                continue;
            }
            if (result.error() != null) {
                var msg = result.error().getMessage();
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
                if (Thread.interrupted()) {
                    return new Outcome(Status.INTERRUPTED, null);
                }

                var explanation = tr.getExplanationForToolRequest(req);
                if (!explanation.isBlank()) {
                    io.llmOutput("\n" + explanation, ChatMessageType.AI);
                }

                ToolExecutionResult exec = tr.executeTool(req);

                currentSessionMessages.add(exec.toExecutionResultMessage());
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
                    if (textOpt.isPresent() && !ConflictAnnotator.containsConflictMarkers(textOpt.get())) {
                        io.llmOutput("\nConflicts resolved for " + file, ChatMessageType.AI);
                        return new Outcome(Status.RESOLVED, null);
                    } else {
                        var details = formatFailure(file, exec.resultText());
                        io.llmOutput("\nCodeAgent failed to resolve conflicts for " + file, ChatMessageType.AI);
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
                lines.add("<commit id=\"" + id + "\">" + StringEscapeUtils.escapeXml10(oneLine) + "</commit>");
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
                lines.add("<commit id=\"" + id + "\">" + StringEscapeUtils.escapeXml10(oneLine) + "</commit>");
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

    /** Build a structured XML snippet for a CodeAgent failure for downstream parsing. */
    private static String formatFailure(ProjectFile file, String details) {
        return """
               <failure file="%s">
               %s
               </failure>
               """
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
               """;
    }

    /** Accessor for the last per-file CodeAgent instructions persisted to the Workspace (debugging use). */
    public @Nullable String getCodeAgentInstructions() {
        return codeAgentInstructions;
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

        // Persist instructions to Workspace so Architect can read them later
        this.codeAgentInstructions = instructions;
        try {
            var desc = "Merge instructions for " + file;
            var fragment =
                    new ContextFragment.StringFragment(cm, instructions, desc, SyntaxConstants.SYNTAX_STYLE_NONE);
            cm.addVirtualFragment(fragment);
        } catch (Exception e) {
            logger.warn("Failed to persist merge instructions for {}: {}", file, e.toString(), e);
        }

        var agent = new CodeAgent(cm, codeModel, io);
        var result = agent.runSingleFileEdit(file, instructions, requireNonNull(currentSessionMessages));
        this.lastCodeAgentResult = result;
        return String.valueOf(result.stopDetails());
    }
}
