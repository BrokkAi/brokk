package ai.brokk.agents;

import static java.util.Objects.requireNonNull;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.*;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.gui.Chrome;
import ai.brokk.mcp.McpUtils;
import ai.brokk.metrics.SearchMetrics;
import ai.brokk.prompts.CodePrompts;
import ai.brokk.prompts.McpPrompts;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.tools.WorkspaceTools;
import ai.brokk.util.Messages;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * SearchAgent: - Uses tools to both answer questions AND curate Workspace context for follow-on coding. - Starts by
 * calling ContextAgent to add recommended fragments to the Workspace. - Adds every learning step to Context history (no
 * hidden state). - Summarizes very large tool outputs before recording them. - Enters "beast mode" to finalize with
 * existing info if interrupted or context is near full. - Never writes code itself; it prepares the Workspace for a
 * later Code Agent run.
 */
public class SearchAgent {
    private static final Logger logger = LogManager.getLogger(SearchAgent.class);

    public enum Terminal {
        TASK_LIST,
        ANSWER,
        WORKSPACE
    }

    // Keep thresholds consistent with other agents
    private static final int SUMMARIZE_THRESHOLD = 1_000; // ~120 LOC equivalent
    private static final double WORKSPACE_CRITICAL = 0.80; // 90% of input limit

    private final IContextManager cm;
    private final StreamingChatModel model;
    private final Llm llm;
    private final Llm summarizer;
    private final IConsoleIO io;
    private final String goal;
    private final Set<Terminal> allowedTerminals;
    private final List<McpPrompts.McpTool> mcpTools;
    private final SearchMetrics metrics;
    private final ContextManager.TaskScope scope;

    // Local working context snapshot for this agent
    private Context context;

    // Session-local conversation for this agent
    private final List<ChatMessage> sessionMessages = new ArrayList<>();

    // State toggles
    private boolean beastMode;

    public SearchAgent(
            Context initialContext,
            String goal,
            StreamingChatModel model,
            Set<Terminal> allowedTerminals,
            SearchMetrics metrics,
            ContextManager.TaskScope scope) {
        this.goal = goal;
        this.cm = initialContext.getContextManager();
        this.model = model;

        this.io = cm.getIo();
        this.llm = cm.getLlm(new Llm.Options(model, "Search: " + goal).withEcho());
        this.llm.setOutput(io);
        this.summarizer = cm.getLlm(cm.getService().getScanModel(), "Summarizer: " + goal);

        this.beastMode = false;
        this.allowedTerminals = Set.copyOf(allowedTerminals);
        this.metrics = metrics;
        this.scope = scope;

        var mcpConfig = cm.getProject().getMcpConfig();
        List<McpPrompts.McpTool> tools = new ArrayList<>();
        for (var server : mcpConfig.servers()) {
            if (server.tools() != null) {
                for (var toolName : server.tools()) {
                    tools.add(new McpPrompts.McpTool(server, toolName));
                }
            }
        }
        this.mcpTools = List.copyOf(tools);
        this.context = initialContext;
    }

    /** Entry point. Runs until answer/abort or interruption. */
    public TaskResult execute() {
        try {
            return executeInternal();
        } catch (InterruptedException e) {
            logger.debug("Search interrupted", e);
            return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED));
        }
    }

    private TaskResult executeInternal() throws InterruptedException {
        // Create a per-turn WorkspaceTools instance bound to the agent-local Context
        var wst = new WorkspaceTools(context);
        var tr = cm.getToolRegistry().builder().register(wst).register(this).build();

        // Single pruning turn if workspace is not empty
        performInitialPruningTurn(tr);
        context = wst.getContext();

        // Main loop: propose actions, execute, record, repeat until finalization
        while (true) {
            wst.setContext(context);

            // Beast mode triggers
            var inputLimit = cm.getService().getMaxInputTokens(model);
            var workspaceMessages = new ArrayList<>(CodePrompts.instance.getWorkspaceContentsMessages(context));
            var workspaceTokens = Messages.getApproximateMessageTokens(workspaceMessages);
            if (!beastMode && inputLimit > 0 && workspaceTokens > WORKSPACE_CRITICAL * inputLimit) {
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "Workspace is near the context limit; attempting finalization based on current knowledge");
                beastMode = true;
            }

            // Build prompt and allowed tools
            var messages = buildPrompt(workspaceTokens, inputLimit, workspaceMessages);
            var allowedToolNames = calculateAllowedToolNames();

            // Agent-owned tools (instance methods)
            var agentTerminalTools = new ArrayList<String>();
            if (allowedTerminals.contains(Terminal.ANSWER)) {
                agentTerminalTools.add("answer");
            }
            if (allowedTerminals.contains(Terminal.WORKSPACE)) {
                agentTerminalTools.add("workspaceComplete");
            }
            // Always allow abort
            agentTerminalTools.add("abortSearch");

            // Global terminal tool(s) implemented outside SearchAgent (e.g., in SearchTools)
            var globalTerminals = new ArrayList<String>();
            if (allowedTerminals.contains(Terminal.TASK_LIST)) {
                globalTerminals.add("createTaskList");
            }

            // Merge allowed names with agent terminals and global terminals
            var allAllowed =
                    new ArrayList<String>(allowedToolNames.size() + agentTerminalTools.size() + globalTerminals.size());
            allAllowed.addAll(allowedToolNames);
            allAllowed.addAll(agentTerminalTools);
            allAllowed.addAll(globalTerminals);
            var toolSpecs = tr.getTools(allAllowed);

            // Decide next action(s)
            io.llmOutput("\n**Brokk Search** is preparing the next actions…\n\n", ChatMessageType.AI, true, false);
            var result = llm.sendRequest(messages, new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr));
            if (result.error() != null || result.isEmpty()) {
                var details =
                        result.error() != null ? requireNonNull(result.error().getMessage()) : "Empty response";
                io.showNotification(IConsoleIO.NotificationRole.INFO, "LLM error planning next step: " + details);
                return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, details));
            }

            // Record turn
            sessionMessages.add(new UserMessage("What tools do you want to use next?"));
            sessionMessages.add(result.aiMessage());

            // De-duplicate requested tools and handle answer/abort isolation
            var ai = ToolRegistry.removeDuplicateToolRequests(result.aiMessage());
            if (!ai.hasToolExecutionRequests()) {
                return errorResult(new TaskResult.StopDetails(
                        TaskResult.StopReason.LLM_ERROR, "No tool requests found in LLM response."));
            }
            var next = parseResponseToRequests(ai, tr);
            if (next.isEmpty()) {
                // If everything got filtered (e.g., only terminal tool kept), force beast mode next turn if needed
                beastMode = true;
                continue;
            }

            // Start tracking this turn (only after successful LLM planning)
            Set<ProjectFile> filesBeforeSet = getWorkspaceFileSet();
            metrics.startTurn();

            // Final tools are executed only when they are the sole requested action in a turn.
            // This ensures research results are evaluated by the LLM before finalization.

            String executedFinalTool = null;
            String executedFinalText = "";
            boolean executedWorkspaceResearch = false;
            boolean executedNonWorkspaceResearch = false;
            Context contextAtTurnStart = context;

            try {
                // Execute all tool calls in a deterministic order (Workspace ops before exploration helps pruning)
                var sortedCalls = next.stream()
                        .sorted(Comparator.comparingInt(req -> priority(req.name())))
                        .toList();

                for (var req : sortedCalls) {
                    // Record tool call
                    metrics.recordToolCall(req.name());

                    ToolExecutionResult exec;
                    try {
                        exec = tr.executeTool(req);
                        context = wst.getContext();
                    } catch (Exception e) {
                        logger.warn("Tool execution failed for {}: {}", req.name(), e.getMessage(), e);
                        exec = ToolExecutionResult.failure(req, "Error: " + e.getMessage());
                    }

                    // Summarize large results
                    var display = exec.resultText();
                    boolean summarize = exec.status() == ToolExecutionResult.Status.SUCCESS
                            && Messages.getApproximateTokens(display) > SUMMARIZE_THRESHOLD
                            && shouldSummarize(req.name());
                    if (summarize) {
                        var reasoning = getArgumentsMap(req)
                                .getOrDefault("reasoning", "")
                                .toString();
                        display = summarizeResult(goal, req, display, reasoning);
                    }

                    // Write to visible transcript and to Context history
                    sessionMessages.add(ToolExecutionResultMessage.from(req, display));

                    // Track research categories to decide later if finalization is permitted
                    var category = categorizeTool(req.name());
                    if (category == ToolCategory.RESEARCH) {
                        if (isWorkspaceTool(req, tr)) {
                            executedWorkspaceResearch = true;
                        } else {
                            executedNonWorkspaceResearch = true;
                        }
                    }

                    // Track if we executed a final tool; finalize after the loop
                    if (req.name().equals("answer")
                            || req.name().equals("createTaskList")
                            || req.name().equals("workspaceComplete")
                            || req.name().equals("abortSearch")) {
                        executedFinalTool = req.name();
                        executedFinalText = display;
                    }
                }
            } finally {
                // End turn tracking after tool execution - always called even on exceptions
                endTurnAndRecordFileChanges(filesBeforeSet);
            }

            // If we executed a final tool, finalize appropriately
            if (executedFinalTool != null) {
                if (executedFinalTool.equals("abortSearch")) {
                    var explain = executedFinalText.isBlank() ? "No explanation provided by agent." : executedFinalText;
                    return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.LLM_ABORTED, explain));
                } else {
                    boolean contextChanged = !context.equals(contextAtTurnStart);
                    if (executedNonWorkspaceResearch) {
                        logger.info("Deferring finalization; non-workspace research tools were executed this turn.");
                    } else if (executedWorkspaceResearch && contextChanged) {
                        logger.info("Deferring finalization; workspace changed during this turn.");
                    } else {
                        io.llmOutput("\n\n**Brokk Search** Context is complete.\n", ChatMessageType.AI, true, false);
                        return createResult("Brokk Search: " + goal, goal);
                    }
                }
            }
        }
    }

    // =======================
    // Prompt and planning
    // =======================

    private List<ChatMessage> buildPrompt(
            int workspaceTokens, int minInputLimit, List<ChatMessage> precomputedWorkspaceMessages)
            throws InterruptedException {
        var messages = new ArrayList<ChatMessage>();

        var reminder = CodePrompts.instance.askReminder();
        var supportedTypes = cm.getProject().getAnalyzerLanguages().stream()
                .map(Language::name)
                .collect(Collectors.joining(", "));

        var sys = new SystemMessage(
                """
                <instructions>
                You are the Search Agent.
                Your job is to be the **Code Agent's preparer**. You are a researcher and librarian, not a developer.
                  Your responsibilities are:
                    1.  **Find & Discover:** Use search and inspection tools to locate all relevant files, classes, and methods.
                    2.  **Curate & Prepare:** Aggressively prune the Workspace to leave *only* the essential context (files, summaries, notes) that the Code Agent will need.
                    3.  **Handoff:** Your final output is a clean workspace ready for the Code Agent to begin implementation.

                  Remember: **You must never write, create, or modify code.**
                  Your purpose is to *find* existing code, not *create* new code.
                  The Code Agent is solely responsible for all code generation and modification.

                Critical rules:
                  1) PRUNE FIRST at every turn.
                     - Remove fragments that are not directly useful for the goal.
                     - Prefer concise, goal-focused summaries over full files.
                     - When you pull information from a long fragment, first add your extraction, then drop the original.
                     - Keep the Workspace focused on answering/solving the goal.
                  2) Use search and inspection tools to discover relevant code, including classes/methods/usages/call graphs.
                  3) The symbol-based tools only have visibility into the following file types: %s
                     Use text-based tools if you need to search other file types.
                  4) Group related lookups into a single call when possible.
                  5) Make multiple tool calls at once when searching for different types of code.
                  6) Your responsibility ends at providing context.
                     Do not attempt to write the solution or pseudocode for the solution.
                     Your job is to *gather* the materials; the Code Agent's job is to *use* them.
                     Where new code is needed, add the *target file* to the workspace using `addFilesToWorkspace`
                     and let the Code Agent write the code. (But when refactoring, it is usually sufficient to call `addSymbolUsagesToWorkspace`
                     and let Code Agent edit those fragments directly, instead of adding each call site's entire file.)

                Output discipline:
                  - Start each turn by pruning and summarizing before any new exploration.
                  - Think before calling tools.
                  - If you already know what to add, use Workspace tools directly; do not search redundantly.

                %s
                </instructions>
                """
                        .formatted(supportedTypes, reminder));
        messages.add(sys);

        // Describe available MCP tools
        var mcpToolPrompt = McpPrompts.mcpToolPrompt(mcpTools);
        if (mcpToolPrompt != null) {
            messages.add(new SystemMessage(mcpToolPrompt));
        }

        // Current Workspace contents
        messages.addAll(precomputedWorkspaceMessages);

        // Related identifiers from nearby files
        var related = context.buildRelatedIdentifiers(10);
        if (!related.isEmpty()) {
            var formatted = related.entrySet().stream()
                    .sorted(Comparator.comparing(e -> e.getKey().fqName()))
                    .map(e -> {
                        var cu = e.getKey();
                        var subs = e.getValue();
                        return "- " + cu.fqName() + (subs.isBlank() ? "" : " (members: " + subs + ")");
                    })
                    .collect(Collectors.joining("\n"));
            messages.add(new UserMessage(
                    """
        <related_classes>
        These classes (given with the identifiers they declare) MAY be relevant. They are NOT in the Workspace yet.
        Add summaries or sources if needed; otherwise ignore them.

        %s
        </related_classes>
        """
                            .formatted(formatted)));
            messages.add(new AiMessage("Acknowledged. I will explicitly add only what is relevant."));
        }

        // Recent project history plus this agent's messages
        messages.addAll(cm.getHistoryMessages());
        messages.addAll(sessionMessages);

        // Workspace size warning and final instruction
        String warning = "";
        if (minInputLimit > 0) {
            double pct = (double) workspaceTokens / minInputLimit * 100.0;
            if (pct > 90.0) {
                warning =
                        """
                                CRITICAL: Workspace is using %.0f%% of input budget (%d tokens of %d).
                                You MUST reduce Workspace size immediately before any further exploration.
                                Replace full text with summaries and drop non-essential fragments first.
                                """
                                .formatted(pct, workspaceTokens, minInputLimit);
            } else if (pct > 60.0) {
                warning =
                        """
                                NOTICE: Workspace is using %.0f%% of input budget (%d tokens of %d).
                                Prefer summaries and prune aggressively before expanding further.
                                """
                                .formatted(pct, workspaceTokens, minInputLimit);
            }
        }

        var finals = new ArrayList<String>();
        if (allowedTerminals.contains(Terminal.ANSWER)) {
            finals.add(
                    "- Use answer(String) when the request is purely informational and you have enough information to answer. The answer needs to be Markdown-formatted (see <persistence>).");
        }
        if (allowedTerminals.contains(Terminal.TASK_LIST)) {
            finals.add(
                    """
                    - Use createTaskList(List<String>) when the request involves code changes; produce a clear, minimal, incremental, and testable sequence of tasks that an Architect/Code agent can execute, once you understand where all the necessary pieces live.
                      Guidance:
                        - Each task should be self-contained and verifiable via code review or automated tests.
                        - Prefer adding or updating automated tests to demonstrate behavior; if automation is not a good fit, it is acceptable to omit tests rather than prescribe manual steps.
                        - Keep the project buildable and testable after each step.
                        - The executing agent may adjust task scope/order based on more up-to-date information discovered during implementation.
                        - Each task needs to be Markdown-formatted, use `inline code` (for file, directory, function, class names and other symbols).
                    """);
        }
        if (allowedTerminals.contains(Terminal.WORKSPACE)) {
            finals.add(
                    "- Use workspaceComplete() when the Workspace contains all the information necessary to accomplish the goal.");
        }
        finals.add(
                "- If we cannot find the answer or the request is out of scope for this codebase, use abortSearch with a clear explanation.");

        String finalsStr = String.join("\n", finals);

        String testsGuidance = "";
        if (allowedTerminals.contains(Terminal.WORKSPACE)) {
            var toolHint =
                    "- To locate tests, prefer getUsages to find tests referencing relevant classes and methods.";
            testsGuidance =
                    """
                    Tests:
                      - Code Agent will run the tests in the Workspace to validate its changes.
                        These can be full files (if it also needs to edit or understand test implementation details),
                        or simple summaries if they just need to be run for validation.
                      %s
                    """
                            .formatted(toolHint);
        }

        var terminalObjective = buildTerminalObjective();

        String directive =
                """
                        <%s>
                        %s
                        </%s>

                        <search-objective>
                        %s
                        </search-objective>

                        Decide the next tool action(s) to make progress toward the objective in service of the goal.

                        Pruning mandate:
                          - Before any new exploration, prune the Workspace.
                          - Replace full text with concise, goal-focused summaries and drop the originals.
                          - Expand the Workspace only after pruning; avoid re-adding irrelevant content.

                        %s

                        Finalization options:
                        %s

                        You can call multiple non-final tools in a single turn. Provide a list of separate tool calls,
                        each with its own name and arguments (add summaries, drop fragments, etc).
                        Final actions (answer, createTaskList, workspaceComplete, abortSearch) must be the ONLY tool in a turn.
                        If you include a final together with other tools, the final will be ignored for this turn.
                        It is NOT your objective to write code.

                        %s
                        """
                        .formatted(
                                terminalObjective.type,
                                goal,
                                terminalObjective.type(),
                                terminalObjective.text(),
                                testsGuidance,
                                finalsStr,
                                warning);

        // Beast mode directive
        if (beastMode) {
            directive = directive
                    + """
                    <beast-mode>
                    The Workspace is full or execution was interrupted.
                    Finalize now using the best available information.
                    Prefer answer(String) for informational requests; for code-change requests, provide a concise createTaskList(List<String>) if feasible; otherwise use abortSearch with reasons.
                    </beast-mode>
                    """;
        }

        messages.add(new UserMessage(directive));
        return messages;
    }

    private List<String> calculateAllowedToolNames() {
        if (beastMode) {
            // Only answer/abort will be exposed alongside this when we build tools list
            return List.of();
        }

        var names = new ArrayList<String>();

        // Any Analyzer at all provides these
        if (!cm.getProject().getAnalyzerLanguages().equals(Set.of(Languages.NONE))) {
            names.add("searchSymbols");
            names.add("getFiles");
        }

        // Fine-grained Analyzer capabilities
        names.add("getClassSkeletons");
        names.add("getClassSources");
        names.add("getMethodSources");
        names.add("getUsages");

        // Text-based search
        names.add("searchSubstrings");
        names.add("searchGitCommitMessages");
        names.add("searchFilenames");
        names.add("getFileContents");
        names.add("getFileSummaries");

        // Workspace curation
        names.add("addFilesToWorkspace");
        names.add("addClassesToWorkspace");
        names.add("addClassSummariesToWorkspace");
        names.add("addMethodsToWorkspace");
        names.add("addFileSummariesToWorkspace");
        names.add("addSymbolUsagesToWorkspace");
        names.add("appendNote");
        names.add("dropWorkspaceFragments");

        // Human-in-the-loop tool (only meaningful when GUI is available; safe to include otherwise)
        if (io instanceof Chrome) {
            names.add("askHuman");
        }

        if (!mcpTools.isEmpty()) {
            names.add("callMcpTool");
        }

        logger.debug("Allowed tool names: {}", names);
        return names;
    }

    private record TerminalObjective(String type, String text) {}

    private TerminalObjective buildTerminalObjective() {
        boolean hasAnswer = allowedTerminals.contains(Terminal.ANSWER);
        boolean hasTaskList = allowedTerminals.contains(Terminal.TASK_LIST);
        boolean hasWorkspace = allowedTerminals.contains(Terminal.WORKSPACE);

        if (hasAnswer && hasTaskList) {
            assert !hasWorkspace;
            return new TerminalObjective(
                    "query",
                    """
                    Deliver either a written answer or a task list:
                      - Prefer answer(String) when no code changes are needed.
                      - Prefer createTaskList(List<String>) if code changes will be needed next.
                    """);
        }

        if (hasAnswer && !hasTaskList) {
            assert !hasWorkspace;
            return new TerminalObjective(
                    "query",
                    """
                    Deliver a written answer using the answer(String) tool.
                    """);
        }

        if (hasTaskList && !hasAnswer) {
            assert !hasWorkspace;
            return new TerminalObjective(
                    "task",
                    """
                    Deliver a task list using the createTaskList(List<String>) tool.
                    """);
        }

        if (hasWorkspace) {
            assert !hasAnswer && !hasTaskList;
            return new TerminalObjective(
                    "task",
                    """
                    Deliver a curated Workspace containing everything required for the follow-on Code Agent
                    to solve the given task.
                    """);
        }

        throw new IllegalStateException();
    }

    private enum ToolCategory {
        TERMINAL, // answer, createTaskList, workspaceComplete, abortSearch
        WORKSPACE_HYGIENE, // dropWorkspaceFragments, appendNote (safe to pair with terminals)
        RESEARCH // everything else (blocks terminals)
    }

    private ToolCategory categorizeTool(String toolName) {
        return switch (toolName) {
            case "answer", "createTaskList", "workspaceComplete", "abortSearch" -> ToolCategory.TERMINAL;
            case "dropWorkspaceFragments", "appendNote" -> ToolCategory.WORKSPACE_HYGIENE;
            default -> ToolCategory.RESEARCH;
        };
    }

    private boolean isWorkspaceTool(ToolExecutionRequest request, ToolRegistry tr) {
        try {
            var vi = tr.validateTool(request);
            return vi.instance() instanceof WorkspaceTools;
        } catch (Exception e) {
            // If validation fails, fall back to conservative assumption (not a workspace tool)
            return false;
        }
    }

    private List<ToolExecutionRequest> parseResponseToRequests(AiMessage response, ToolRegistry tr) {
        if (!response.hasToolExecutionRequests()) {
            return List.of();
        }

        var terminals = new ArrayList<ToolExecutionRequest>();
        var hygiene = new ArrayList<ToolExecutionRequest>();
        var researchOrBlocking = new ArrayList<ToolExecutionRequest>();

        for (var r : response.toolExecutionRequests()) {
            switch (categorizeTool(r.name())) {
                case TERMINAL -> terminals.add(r);
                case WORKSPACE_HYGIENE -> hygiene.add(r);
                case RESEARCH -> researchOrBlocking.add(r);
            }
        }

        // Rule: Terminal actions can coexist with workspace hygiene, but not with research/blocking tools.
        // This ensures research results are evaluated before finalization.
        if (!researchOrBlocking.isEmpty()) {
            boolean allWorkspace = researchOrBlocking.stream().allMatch(r -> isWorkspaceTool(r, tr));
            if (allWorkspace && !terminals.isEmpty()) {
                // Allow terminal to coexist with workspace tools; finalization will occur only if no net changes are
                // made.
                var result = new ArrayList<>(researchOrBlocking);
                result.addAll(hygiene);
                result.add(terminals.getFirst());
                return result;
            }

            if (!terminals.isEmpty()) {
                logger.info(
                        "Final tool requested alongside research/blocking tools; deferring final to a later turn. Finals present: {}",
                        terminals.stream().map(ToolExecutionRequest::name).toList());
            }
            var result = new ArrayList<>(researchOrBlocking);
            result.addAll(hygiene);
            return result;
        }

        // Only hygiene and/or terminals present: allow terminal with hygiene
        if (!terminals.isEmpty()) {
            var result = new ArrayList<>(hygiene);
            result.add(terminals.get(0)); // Keep the first terminal
            return result;
        }

        // Only hygiene: return it
        return hygiene;
    }

    private int priority(String toolName) {
        // Prioritize workspace pruning and adding summaries before deeper exploration.
        return switch (toolName) {
            case "dropWorkspaceFragments" -> 1;
            case "appendNote" -> 2;
            case "askHuman" -> 2;
            case "addClassSummariesToWorkspace", "addFileSummariesToWorkspace", "addMethodsToWorkspace" -> 3;
            case "addFilesToWorkspace", "addClassesToWorkspace", "addSymbolUsagesToWorkspace" -> 4;
            case "searchSymbols", "getUsages", "searchSubstrings", "searchFilenames", "searchGitCommitMessages" -> 6;
            case "getClassSkeletons", "getClassSources", "getMethodSources" -> 7;
            case "getCallGraphTo", "getCallGraphFrom", "getFileContents", "getFileSummaries", "getFiles" -> 8;
            case "answer", "createTaskList", "workspaceComplete", "abortSearch" -> 100;
            default -> 9;
        };
    }

    private void performInitialPruningTurn(ToolRegistry tr) throws InterruptedException {
        // Skip if workspace is empty
        if (cm.liveContext().isEmpty()) {
            return;
        }

        var messages = buildInitialPruningPrompt();
        var toolSpecs = tr.getTools(List.of("performedInitialReview", "dropWorkspaceFragments"));

        io.llmOutput("\n**Brokk** performing initial workspace review…", ChatMessageType.AI, true, false);
        var jLlm = cm.getLlm(new Llm.Options(cm.getService().getScanModel(), "Janitor: " + goal).withEcho());
        var result = jLlm.sendRequest(messages, new ToolContext(toolSpecs, ToolChoice.AUTO, tr));
        if (result.error() != null || result.isEmpty()) {
            return;
        }

        // Record the turn
        sessionMessages.add(new UserMessage("Review the current workspace. If relevant, prune irrelevant fragments."));
        sessionMessages.add(result.aiMessage());

        // Execute tool requests
        var ai = ToolRegistry.removeDuplicateToolRequests(result.aiMessage());
        for (var req : ai.toolExecutionRequests()) {
            try {
                tr.executeTool(req);
            } catch (Exception e) {
                logger.warn("Tool execution failed during initial pruning for {}: {}", req.name(), e.getMessage());
            }
        }
    }

    private List<ChatMessage> buildInitialPruningPrompt() {
        var messages = new ArrayList<ChatMessage>();

        var sys = new SystemMessage(
                """
                You are the Janitor Agent cleaning the Workspace. It is critically important to remove irrelevant
                fragments before proceeding; they are highly distracting to the other Agents.

                Your task:
                  - Evaluate the current workspace contents.
                  - Call dropWorkspaceFragments to remove irrelevant fragments.
                  - ONLY if all fragments are relevant, do nothing (skip the tool call).
                """);
        messages.add(sys);

        // Current Workspace contents
        messages.addAll(CodePrompts.instance.getWorkspaceContentsMessages(cm.liveContext()));

        // Goal and project context
        messages.add(new UserMessage(
                """
                <goal>
                %s
                </goal>

                Review the Workspace above. Remove ALL fragments that are not directly useful for accomplishing the goal.
                If the workspace is already well-curated, you're done!
                """
                        .formatted(goal)));

        return messages;
    }

    /**
     * Scan initial context using ContextAgent and add recommendations to the workspace.
     * Callers should invoke this before calling execute() if they want the initial context scan.
     */
    public void scanInitialContext() throws InterruptedException {
        long scanStartTime = System.currentTimeMillis();
        Set<ProjectFile> filesBeforeScan = getWorkspaceFileSet();

        var contextAgent = new ContextAgent(cm, cm.getService().getScanModel(), goal);
        io.llmOutput("\n**Brokk Context Engine** analyzing repository context…\n", ChatMessageType.AI, true, false);

        var recommendation = contextAgent.getRecommendations(context);
        if (!recommendation.success() || recommendation.fragments().isEmpty()) {
            io.llmOutput("\n\nNo additional context insights found\n", ChatMessageType.CUSTOM);
            long scanTime = System.currentTimeMillis() - scanStartTime;
            metrics.recordContextScan(0, scanTime, false, Set.of());
            return;
        }
        scope.append(new TaskResult(cm, "Context Engine", List.of(), context, TaskResult.StopReason.SUCCESS));

        var totalTokens = contextAgent.calculateFragmentTokens(recommendation.fragments());
        int finalBudget = cm.getService().getMaxInputTokens(model) / 2;
        if (totalTokens > finalBudget) {
            var summaries = ContextFragment.describe(recommendation.fragments());
            context = context.addVirtualFragments(List.of(new ContextFragment.StringFragment(
                    cm,
                    summaries,
                    "Summary of Scan Results",
                    recommendation.fragments().stream()
                            .findFirst()
                            .orElseThrow()
                            .syntaxStyle())));
        } else {
            logger.debug("Recommended context fits within final budget.");
            addToWorkspace(recommendation);
            io.llmOutput(
                    "\n\n**Brokk Context Engine** complete — contextual insights added to Workspace.\n",
                    ChatMessageType.AI);
        }

        // Track metrics
        Set<ProjectFile> filesAfterScan = getWorkspaceFileSet();
        Set<ProjectFile> filesAdded = new HashSet<>(filesAfterScan);
        filesAdded.removeAll(filesBeforeScan);
        long scanTime = System.currentTimeMillis() - scanStartTime;
        metrics.recordContextScan(filesAdded.size(), scanTime, false, toRelativePaths(filesAdded));
    }

    public void addToWorkspace(ContextAgent.RecommendationResult recommendationResult) {
        logger.debug("Recommended context fits within final budget.");
        List<ContextFragment> selected = recommendationResult.fragments();
        // Group selected fragments by type
        var groupedByType = selected.stream().collect(Collectors.groupingBy(ContextFragment::getType));

        // Process ProjectPathFragments
        var pathFragments = groupedByType.getOrDefault(ContextFragment.FragmentType.PROJECT_PATH, List.of()).stream()
                .map(ContextFragment.ProjectPathFragment.class::cast)
                .toList();
        if (!pathFragments.isEmpty()) {
            logger.debug(
                    "Adding selected ProjectPathFragments: {}",
                    pathFragments.stream().map(ppf -> ppf.file().toString()).collect(Collectors.joining(", ")));
            context = context.addPathFragments(pathFragments);
        }

        // Process SkeletonFragments
        var skeletonFragments = groupedByType.getOrDefault(ContextFragment.FragmentType.SKELETON, List.of()).stream()
                .map(ContextFragment.SummaryFragment.class::cast)
                .toList();
        if (!skeletonFragments.isEmpty()) {
            context = context.addVirtualFragments(skeletonFragments);
        }
    }

    // =======================
    // Answer/abort tools
    // =======================

    @Tool("Signal that the initial workspace review is complete and all fragments are relevant.")
    public String performedInitialReview() {
        logger.debug("performedInitialReview: workspace is already well-curated");
        return "Initial review complete; workspace is well-curated.";
    }

    @Tool("Provide a final answer to a purely informational request. Use this when no code changes are required.")
    public String answer(
            @P(
                            "Comprehensive explanation that answers the query. Include relevant code snippets and how they relate, formatted in Markdown.")
                    String explanation) {
        io.llmOutput("# Answer\n\n" + explanation, ChatMessageType.AI);
        return explanation;
    }

    @Tool(
            "Signal that the Workspace now contains all the information necessary to accomplish the goal. Call this when you have finished gathering and pruning context.")
    public String workspaceComplete() {
        logger.debug("workspaceComplete selected");
        return "Workspace marked complete for the current goal.";
    }

    @Tool("Abort when you determine the question is not answerable from this codebase or is out of scope.")
    public String abortSearch(
            @P("Clear explanation of why the question cannot be answered from this codebase.") String explanation) {
        logger.debug("abortSearch selected with explanation length {}", explanation.length());
        io.llmOutput(explanation, ChatMessageType.AI);
        return explanation;
    }

    @Tool("Calls a remote tool using the MCP (Model Context Protocol).")
    public String callMcpTool(
            @P("The name of the tool to call. This must be one of the configured MCP tools.") String toolName,
            @P("A map of argument names to values for the tool. Can be null or empty if the tool takes no arguments.")
                    @Nullable
                    Map<String, Object> arguments) {
        Map<String, Object> args = Objects.requireNonNullElseGet(arguments, HashMap::new);
        var mcpToolOptional =
                mcpTools.stream().filter(t -> t.toolName().equals(toolName)).findFirst();

        if (mcpToolOptional.isEmpty()) {
            var err = "Error: MCP tool '" + toolName + "' not found in configuration.";
            if (toolName.contains("(") || toolName.contains("{")) {
                err = err
                        + " Possible arguments found in the tool name. Hint: The first argument, 'toolName', is the tool name only. Any arguments must be defined as a map in the second argument, named 'arguments'.";
            }
            logger.warn(err);
            return err;
        }

        var server = mcpToolOptional.get().server();
        try {
            var projectRoot = this.cm.getProject().getRoot();
            var result = McpUtils.callTool(server, toolName, args, projectRoot);
            var preamble = McpPrompts.mcpToolPreamble();
            var msg = preamble + "\n\n" + "MCP tool '" + toolName + "' output:\n" + result;
            logger.info("MCP tool '{}' executed successfully via server '{}'", toolName, server.name());
            return msg;
        } catch (IOException | RuntimeException e) {
            var err = "Error calling MCP tool '" + toolName + "': " + e.getMessage();
            logger.error(err, e);
            return err;
        }
    }

    // =======================
    // Finalization and errors
    // =======================

    private TaskResult createResult(String action, String goal) {
        // Build final messages from already-streamed transcript; fallback to session-local messages if empty
        List<ChatMessage> finalMessages = new ArrayList<>(io.getLlmRawMessages());
        if (finalMessages.isEmpty()) {
            finalMessages = new ArrayList<>(sessionMessages);
        }

        var stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
        var fragment = new ContextFragment.TaskFragment(cm, finalMessages, goal);

        // Record final metrics
        recordFinalWorkspaceState();
        metrics.recordOutcome(stopDetails.reason(), getWorkspaceFileSet().size());

        return new TaskResult(action, fragment, context, stopDetails);
    }

    private TaskResult errorResult(TaskResult.StopDetails details) {
        // Build final messages from already-streamed transcript; fallback to session-local messages if empty
        List<ChatMessage> finalMessages = new ArrayList<>(io.getLlmRawMessages());
        if (finalMessages.isEmpty()) {
            finalMessages = new ArrayList<>(sessionMessages);
        }

        String action = "Search: " + goal + " [" + details.reason().name() + "]";
        var fragment = new ContextFragment.TaskFragment(cm, finalMessages, goal);

        // Record final metrics
        recordFinalWorkspaceState();
        metrics.recordOutcome(details.reason(), getWorkspaceFileSet().size());

        return new TaskResult(action, fragment, context, details);
    }

    // =======================
    // Metrics helpers
    // =======================

    private void endTurnAndRecordFileChanges(Set<ProjectFile> filesBeforeSet) {
        Set<ProjectFile> filesAfterSet = getWorkspaceFileSet();
        Set<ProjectFile> added = new HashSet<>(filesAfterSet);
        added.removeAll(filesBeforeSet);
        metrics.recordFilesAdded(added.size());
        metrics.recordFilesAddedPaths(toRelativePaths(added));
        metrics.endTurn(toRelativePaths(filesBeforeSet), toRelativePaths(filesAfterSet));
    }

    private void recordFinalWorkspaceState() {
        metrics.recordFinalWorkspaceFiles(toRelativePaths(getWorkspaceFileSet()));
        metrics.recordFinalWorkspaceFragments(getWorkspaceFragments());
    }

    /**
     * Returns true if the fragment represents a workspace file (PROJECT_PATH or SKELETON).
     * These are the fragment types that contribute actual files to the workspace for editing/viewing.
     */
    private static boolean isWorkspaceFileFragment(ContextFragment f) {
        return f.getType() == ContextFragment.FragmentType.PROJECT_PATH
                || f.getType() == ContextFragment.FragmentType.SKELETON;
    }

    private Set<ProjectFile> getWorkspaceFileSet() {
        return context.allFragments()
                .filter(SearchAgent::isWorkspaceFileFragment)
                .flatMap(f -> f.files().stream())
                .collect(Collectors.toSet());
    }

    private Set<String> toRelativePaths(Set<ProjectFile> files) {
        return files.stream().map(pf -> pf.getRelPath().toString()).collect(Collectors.toSet());
    }

    private List<SearchMetrics.FragmentInfo> getWorkspaceFragments() {
        return context.allFragments()
                .filter(SearchAgent::isWorkspaceFileFragment)
                .map(f -> new SearchMetrics.FragmentInfo(
                        f.getType().toString(),
                        f.id(),
                        f.description(),
                        f.files().stream()
                                .map(pf -> pf.getRelPath().toString())
                                .sorted()
                                .toList()))
                .toList();
    }

    // =======================
    // State and summarization
    // =======================

    private boolean shouldSummarize(String toolName) {
        return Set.of(
                        "searchSymbols",
                        "getUsages",
                        "getClassSources",
                        "searchSubstrings",
                        "searchFilenames",
                        "searchGitCommitMessages",
                        "getFileContents",
                        "getFileSummaries")
                .contains(toolName);
    }

    private String summarizeResult(
            String query, ToolExecutionRequest request, String rawResult, @Nullable String reasoning)
            throws InterruptedException {
        var sys = new SystemMessage(
                """
                        You are a code expert extracting ALL information relevant to the given goal
                        from the provided tool call result.

                        Your output will be given to the agent running the search, and replaces the raw result.
                        Thus, you must include every relevant class/method name and any
                        relevant code snippets that may be needed later. DO NOT speculate; only use the provided content.
                        """);

        var user = new UserMessage(
                """
                        <goal>
                        %s
                        </goal>
                        <reasoning>
                        %s
                        </reasoning>
                        <tool name="%s">
                        %s
                        </tool>
                        """
                        .formatted(query, reasoning == null ? "" : reasoning, request.name(), rawResult));
        Llm.StreamingResult sr = summarizer.sendRequest(List.of(sys, user));
        if (sr.error() != null) {
            return rawResult; // fallback to raw
        }
        return sr.text();
    }

    private static Map<String, Object> getArgumentsMap(ToolExecutionRequest request) {
        try {
            var mapper = new ObjectMapper();
            return mapper.readValue(request.arguments(), new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            logger.error("Error parsing request args for {}: {}", request.name(), e.getMessage());
            return Map.of();
        }
    }
}
