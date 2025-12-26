package ai.brokk.agents;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.Service;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.ContextHistory;
import ai.brokk.context.ViewingPolicy;
import ai.brokk.git.GitWorkflow;
import ai.brokk.gui.Chrome;
import ai.brokk.mcp.McpUtils;
import ai.brokk.metrics.SearchMetrics;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.prompts.ArchitectPrompts;
import ai.brokk.prompts.CodePrompts;
import ai.brokk.prompts.McpPrompts;
import ai.brokk.prompts.WorkspacePrompts;
import ai.brokk.tools.ExplanationRenderer;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.tools.WorkspaceTools;
import ai.brokk.util.ComputedValue;
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
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Blocking;
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

    private enum Terminal {
        TASK_LIST,
        ANSWER,
        WORKSPACE,
        CODE
    }

    public enum Objective {
        ANSWER_ONLY {
            @Override
            public Set<Terminal> terminals() {
                return EnumSet.of(Terminal.ANSWER);
            }
        },
        TASKS_ONLY {
            @Override
            public Set<Terminal> terminals() {
                return EnumSet.of(Terminal.TASK_LIST);
            }
        },
        WORKSPACE_ONLY {
            @Override
            public Set<Terminal> terminals() {
                return EnumSet.of(Terminal.WORKSPACE);
            }
        },
        LUTZ {
            @Override
            public Set<Terminal> terminals() {
                return EnumSet.of(Terminal.ANSWER, Terminal.CODE, Terminal.TASK_LIST);
            }
        };

        public abstract Set<Terminal> terminals();
    }

    /**
     * Configuration for automatic context scanning behavior.
     */
    public record ScanConfig(
            boolean autoScan, // Whether to auto-scan when workspace is empty or on first search tool
            @Nullable StreamingChatModel scanModel, // Model to use for ContextAgent (null = use project default)
            boolean appendToScope // Whether to append scan results to scope history
            ) {
        public static ScanConfig defaults() {
            return new ScanConfig(true, null, true);
        }

        public static ScanConfig disabled() {
            return new ScanConfig(false, null, true);
        }

        public static ScanConfig withModel(StreamingChatModel model) {
            return new ScanConfig(true, model, true);
        }

        public static ScanConfig noAppend() {
            return new ScanConfig(true, null, false);
        }
    }

    // Keep thresholds consistent with other agents
    private static final int SUMMARIZE_THRESHOLD = 1_000; // ~120 LOC equivalent
    private static final double WORKSPACE_CRITICAL = 0.80; // 90% of input limit

    private final IContextManager cm;
    private final StreamingChatModel model;
    private final ContextManager.TaskScope scope;
    private final Llm llm;
    private final Llm summarizer;
    private final IConsoleIO io;
    private final String goal;
    private final Objective objective;
    private final List<McpPrompts.McpTool> mcpTools;
    private final SearchMetrics metrics;
    private final ScanConfig scanConfig;
    private boolean scanPerformed;
    private boolean contextPruned;

    // Local working context snapshot for this agent
    private Context context;

    // Session-local conversation for this agent
    private final List<ChatMessage> sessionMessages = new ArrayList<>();

    // State toggles
    private boolean beastMode;
    private boolean codeAgentJustSucceeded;

    /**
     * Primary constructor with explicit IO and ScanConfig.
     *
     * @param initialContext the initial context
     * @param goal the search goal
     * @param model the LLM model to use
     * @param objective the search objective
     * @param scope the task scope for history recording
     * @param io the IConsoleIO instance for output
     * @param scanConfig configuration for automatic context scanning
     */
    public SearchAgent(
            Context initialContext,
            String goal,
            StreamingChatModel model,
            Objective objective,
            ContextManager.TaskScope scope,
            IConsoleIO io,
            ScanConfig scanConfig) {
        this.goal = goal;
        this.cm = initialContext.getContextManager();
        this.model = model;
        this.scope = scope;

        this.io = io;
        var llmOptions = new Llm.Options(model, "Search: " + goal).withEcho();
        this.llm = cm.getLlm(llmOptions);
        this.llm.setOutput(this.io);

        var summarizeModel = cm.getService().getModel(ModelType.SCAN);
        this.summarizer = cm.getLlm(summarizeModel, "Summarizer: " + goal);

        this.beastMode = false;
        this.codeAgentJustSucceeded = false;
        this.objective = objective;
        this.metrics = "true".equalsIgnoreCase(System.getenv("BRK_COLLECT_METRICS"))
                ? SearchMetrics.tracking()
                : SearchMetrics.noOp();

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
        this.scanConfig = scanConfig;
    }

    /**
     * Creates a SearchAgent with output streaming enabled (default behavior) and default IO.
     */
    public SearchAgent(
            Context initialContext,
            String goal,
            StreamingChatModel model,
            Objective objective,
            ContextManager.TaskScope scope) {
        this(
                initialContext,
                goal,
                model,
                objective,
                scope,
                initialContext.getContextManager().getIo(),
                ScanConfig.defaults());
    }

    /** Entry point. Runs until answer/abort or interruption. */
    public TaskResult execute() {
        try {
            var tr = executeInternal();
            if (metrics instanceof SearchMetrics.Tracking) {
                var json = metrics.toJson(
                        goal,
                        countTurns(tr.output().messages()),
                        tr.stopDetails().reason() == TaskResult.StopReason.SUCCESS);
                System.err.println("\nBRK_SEARCHAGENT_METRICS=" + json);
            }
            return tr;
        } catch (InterruptedException e) {
            logger.debug("Search interrupted", e);
            return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED));
        }
    }

    private static int countTurns(List<ChatMessage> messages) {
        // Count AI messages as turns
        return (int) messages.stream()
                .filter(msg -> msg.type() == ChatMessageType.AI)
                .count();
    }

    private boolean shouldAutomaticallyScan() {
        return scanConfig.autoScan() && !scanPerformed;
    }

    private TaskResult executeInternal() throws InterruptedException {
        // Create a per-turn WorkspaceTools instance bound to the agent-local Context
        var wst = new WorkspaceTools(context);
        var tr = cm.getToolRegistry().builder().register(wst).register(this).build();

        pruneContext();
        if (shouldAutomaticallyScan() && context.isFileContentEmpty()) {
            performAutoScan();
        }

        // Main loop: propose actions, execute, record, repeat until finalization
        while (true) {
            wst.setContext(context);

            // Beast mode triggers
            var inputLimit = cm.getService().getMaxInputTokens(model);
            // Determine viewing policy based on search objective
            boolean useTaskList = objective == Objective.LUTZ || objective == Objective.TASKS_ONLY;
            var viewingPolicy = new ViewingPolicy(TaskResult.Type.SEARCH, useTaskList);
            // Build workspace messages in insertion order with viewing policy applied
            var workspaceMessages = new ArrayList<>(WorkspacePrompts.getMessagesInAddedOrder(context, viewingPolicy));
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
            var allowedTerminals = objective.terminals();
            var agentTerminalTools = new ArrayList<String>();
            if (allowedTerminals.contains(Terminal.ANSWER)) {
                agentTerminalTools.add("answer");
                agentTerminalTools.add("askForClarification");
            }
            if (allowedTerminals.contains(Terminal.WORKSPACE)) {
                agentTerminalTools.add("workspaceComplete");
            }
            if (allowedTerminals.contains(Terminal.CODE)) {
                agentTerminalTools.add("callCodeAgent");
            }
            // Always allow abort
            agentTerminalTools.add("abortSearch");

            // Global terminal tool(s) implemented outside SearchAgent (e.g., in SearchTools)
            var globalTerminals = new ArrayList<String>();
            if (allowedTerminals.contains(Terminal.TASK_LIST)) {
                globalTerminals.add("createOrReplaceTaskList");
            }

            // Merge allowed names with agent terminals and global terminals
            var allAllowed =
                    new ArrayList<String>(allowedToolNames.size() + agentTerminalTools.size() + globalTerminals.size());
            allAllowed.addAll(allowedToolNames);
            allAllowed.addAll(agentTerminalTools);
            allAllowed.addAll(globalTerminals);
            var toolSpecs = tr.getTools(allAllowed);

            // Start tracking this turn before LLM call
            metrics.startTurn();
            long turnStartTime = System.currentTimeMillis();

            // Decide next action(s)
            io.showTransientMessage("Brokk Search is preparing the next actions…");
            var result = llm.sendRequest(messages, new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr));

            long llmTimeMs = System.currentTimeMillis() - turnStartTime;
            var tokenUsage = result.metadata();
            int inputTokens = tokenUsage != null ? tokenUsage.inputTokens() : 0;
            int cachedTokens = tokenUsage != null ? tokenUsage.cachedInputTokens() : 0;
            int thinkingTokens = tokenUsage != null ? tokenUsage.thinkingTokens() : 0;
            int outputTokens = tokenUsage != null ? tokenUsage.outputTokens() : 0;
            metrics.recordLlmCall(llmTimeMs, inputTokens, cachedTokens, thinkingTokens, outputTokens);

            if (result.error() != null) {
                var details = TaskResult.StopDetails.fromResponse(result);
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO, "LLM error planning next step: " + details.explanation());
                return errorResult(details, taskMeta());
            }

            // If we haven't scanned yet and the model needs to search, preempt the turn with scan and retry
            var ai = ToolRegistry.removeDuplicateToolRequests(result.aiMessage());
            if (shouldAutomaticallyScan()
                    && ai.toolExecutionRequests().stream().anyMatch(req -> isSearchTool(req.name()))) {
                logger.debug("Lazy scan triggered by first search tool");
                performAutoScan();
                continue;
            }

            // Record turn
            sessionMessages.add(new UserMessage("What tools do you want to use next?"));
            sessionMessages.add(result.aiMessage());

            // LLM tries to enforce tc requirement so don't retry if it's missing; we're done
            if (!ai.hasToolExecutionRequests()) {
                return errorResult(
                        new TaskResult.StopDetails(
                                TaskResult.StopReason.TOOL_ERROR, "No tool requests found in LLM response."),
                        taskMeta());
            }

            // Get workspace snapshot for file diff tracking
            Set<ProjectFile> filesBeforeSet = getWorkspaceFileSet();

            boolean executedResearch = false;
            Context contextAtTurnStart = context;
            try {
                // Execute all tool calls in a deterministic order (Workspace ops before exploration helps pruning)
                var sortedNonterminalCalls = ai.toolExecutionRequests().stream()
                        .filter(req -> categorizeTool(req.name()) != ToolCategory.TERMINAL)
                        .sorted(Comparator.comparingInt(req -> priority(req.name())))
                        .toList();

                for (var req : sortedNonterminalCalls) {
                    ToolExecutionResult toolResult = executeTool(req, tr, wst);
                    if (toolResult.status() == ToolExecutionResult.Status.FATAL) {
                        var details =
                                new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, toolResult.resultText());
                        return errorResult(details, taskMeta());
                    }

                    // Summarize large results
                    var display = toolResult.resultText();
                    boolean summarize = toolResult.status() == ToolExecutionResult.Status.SUCCESS
                            && Messages.getApproximateTokens(display) > SUMMARIZE_THRESHOLD
                            && shouldSummarize(req.name());
                    ToolExecutionResult finalResult;
                    if (summarize) {
                        var reasoning = getArgumentsMap(req)
                                .getOrDefault("reasoning", "")
                                .toString();
                        display = summarizeResult(goal, req, display, reasoning);
                        finalResult = new ToolExecutionResult(req, toolResult.status(), display);
                    } else {
                        finalResult = toolResult;
                    }

                    // Write to visible transcript and to Context history
                    sessionMessages.add(finalResult.toExecutionResultMessage());

                    // Track research categories to decide later if finalization is permitted
                    var category = categorizeTool(req.name());
                    if (category == ToolCategory.RESEARCH) {
                        if (!isWorkspaceTool(req, tr)) {
                            executedResearch = true;
                        }
                    }
                }

                // allow terminals if workspace has not changed and we did no new research
                var terminal = ai.toolExecutionRequests().stream()
                        .filter(req -> categorizeTool(req.name()) == ToolCategory.TERMINAL)
                        .min(Comparator.comparingInt(req -> priority(req.name())));
                if (terminal.isPresent() && context.equals(contextAtTurnStart) && !executedResearch) {
                    var termReq = terminal.get();
                    var termExec = executeTool(termReq, tr, wst);

                    var display = termExec.resultText();
                    sessionMessages.add(termExec.toExecutionResultMessage());

                    if (termExec.status() != ToolExecutionResult.Status.SUCCESS) {
                        return errorResult(
                                new TaskResult.StopDetails(
                                        TaskResult.StopReason.TOOL_ERROR,
                                        "Terminal tool '" + termReq.name() + "' failed: " + display),
                                taskMeta());
                    }

                    if (termReq.name().equals("abortSearch")) {
                        return errorResult(
                                new TaskResult.StopDetails(TaskResult.StopReason.LLM_ABORTED, "Aborted: " + display),
                                taskMeta());
                    } else if (termReq.name().equals("callCodeAgent")) {
                        if (codeAgentJustSucceeded) {
                            // code agent already appended output to history, empty messages are skipped by scope.append
                            return TaskResult.humanResult(
                                    cm, "CodeAgent finished", List.of(), context, TaskResult.StopReason.SUCCESS);
                        }
                        // If CodeAgent did not succeed, continue planning/search loop
                    } else {
                        return createResult(termReq.name(), goal);
                    }
                }
            } finally {
                // End turn tracking - always called even on exceptions
                endTurnAndRecordFileChanges(filesBeforeSet);
            }
        }
    }

    private ToolExecutionResult executeTool(ToolExecutionRequest req, ToolRegistry registry, WorkspaceTools wst)
            throws InterruptedException {
        metrics.recordToolCall(req.name());

        // ensure WorkspaceTools sees the freshest Context before executing any tool.
        // This hardens against future tools that might mutate context between calls
        wst.setContext(context);

        var result = registry.executeTool(req);

        // If this was a WorkspaceTools call, copy its immutable update back to the agent-local Context.
        if (isWorkspaceTool(req, registry)) {
            context = wst.getContext();
        }

        return result;
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

        var nonDroppableSection = buildNonDroppableSection();
        var workspaceToc = WorkspacePrompts.formatToc(context);

        var sys = new SystemMessage(
                """
                <instructions>
                %s
                Critical rules:
                  1) PRUNE FIRST at every turn.
                     - Remove fragments that are not directly useful for the goal (add a reason).
                     - Prefer concise, goal-focused summaries over full files when possible.
                     - When you pull information from a long fragment, first add your extraction/summary, then drop the original from workspace.
                     - Keep the Workspace focused on answering/solving the goal.
                  2) Use search and inspection tools to discover relevant code, including classes/methods/usages/call graphs.
                  3) The symbol-based tools only have visibility into the following file types: %s
                     Use text-based tools if you need to search other file types.
                  4) Group related lookups into a single tool call when possible.
                  5) Make multiple tool calls at once when searching for different types of code.
                  6) Your responsibility ends at providing context.
                     Do not attempt to write the solution or pseudocode for the solution.
                     Your job is to *gather* the materials; the Code Agent's job is to *use* them.
                     Where code changes are needed, add the *target files* to the workspace using `addFilesToWorkspace`
                     and let the Code Agent write the code. (But when refactoring, it is usually sufficient to call `addSymbolUsagesToWorkspace`
                     and let Code Agent edit those fragments directly, instead of adding each call site's entire file.)
                     Note: Code Agent will also take care of creating new files, you only need to add existing files
                     to the Workspace.

                Output discipline:
                  - Start each turn by pruning and summarizing before any new exploration.
                  - Think before calling tools.
                  - If you already know what to add, use Workspace tools directly; do not search redundantly.

                %s
                %s
                </instructions>
                <workspace-toc>
                %s
                </workspace-toc>
                """
                        .formatted(
                                getSystemMessage().text(),
                                supportedTypes,
                                reminder,
                                nonDroppableSection,
                                workspaceToc));
        messages.add(sys);

        // Describe available MCP tools
        var mcpToolPrompt = McpPrompts.mcpToolPrompt(mcpTools);
        if (mcpToolPrompt != null) {
            messages.add(new SystemMessage(mcpToolPrompt));
        }

        // Current Workspace contents (apply viewing policy for visibility filtering)
        messages.addAll(precomputedWorkspaceMessages);

        // Related identifiers from nearby files
        var related = context.buildRelatedIdentifiers(10);
        if (!related.isEmpty()) {
            var relatedBlock = ArchitectPrompts.formatRelatedFiles(related);
            messages.add(new UserMessage(
                    """
        <related_files>
        These files (with the identifiers they declare) MAY be relevant. They are NOT in the Workspace yet.
        Add summaries or sources if needed; otherwise ignore them.

        %s
        </related_files>
        """
                            .formatted(relatedBlock)));
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

        var allowedTerminals = objective.terminals();
        var finals = new ArrayList<String>();
        if (allowedTerminals.contains(Terminal.ANSWER)) {
            finals.add(
                    "- Use answer(String) when the request is purely informational and you have enough information to answer. The answer needs to be Markdown-formatted (see <persistence>).");
            finals.add(
                    "- Use askForClarification(String queryForUser) when the goal is unclear or you cannot find the necessary information; this will ask the user directly and stop.");
        }
        if (allowedTerminals.contains(Terminal.TASK_LIST)) {
            finals.add(
                    """
                    - Use createOrReplaceTaskList(String explanation, List<String> tasks) to replace the entire task list when the request involves code changes. Titles are summarized automatically from task text; pass task texts only. Completed tasks from the previous list are implicitly dropped. Produce a clear, minimal, incremental, and testable sequence of tasks that an Architect/Code agent can execute, once you understand where all the necessary pieces live.
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
        if (allowedTerminals.contains(Terminal.CODE)) {
            finals.add(
                    "- Use callCodeAgent(String instructions, boolean deferBuild) to attempt implementation now in a single shot. If it succeeds, we finish; otherwise, continue with search/planning. Only use this when the goal is small enough to not need decomposition into a task list, and after you have added all the necessary context to the Workspace.");
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

        String emptyProjectGuidance = "";
        if (cm.getProject().isEmptyProject()) {
            emptyProjectGuidance =
                    """
                    <empty-project-notice>
                    The project appears to be empty or uninitialized (few or no source files).
                    Adapt your approach:
                      - Prefer searching the repository structure first (e.g., `skimDirectory`, `searchFilenames`) to confirm what exists.
                      - If the user's request requires new code, your role is still to prepare context and produce tasks, not to write code.
                      - For code-change requests, prefer producing a task list that starts with creating the minimal project skeleton and build/test setup.
                    </empty-project-notice>
                    """;
        }

        String buildSetupTaskGuidance = "";
        boolean tasksObjective = objective == Objective.LUTZ || objective == Objective.TASKS_ONLY;
        if (tasksObjective && cm.getProject().loadBuildDetails().equals(BuildAgent.BuildDetails.EMPTY)) {
            buildSetupTaskGuidance =
                    """
                    <build-setup-task-guidance>
                    If you produce a task list, the FIRST task MUST configure the build and test stack (and any required environment variables)
                    so that subsequent tasks can run `build/lint` and tests.
                    </build-setup-task-guidance>
                    """;
        }

        String directive =
                """
                        <instructions>
                        Critical rules:
                          1) PRUNE FIRST at every turn.
                             - Remove fragments that are not directly useful for the goal (add a reason).
                             - Prefer concise, goal-focused summaries over full files when possible.
                             - When you pull information from a long fragment, first add your extraction/summary, then drop the original from workspace.
                             - Keep the Workspace focused on answering/solving the goal.
                          2) Use search and inspection tools to discover relevant code, including classes/methods/usages/call graphs.
                             The symbol-based tools only have visibility into the following file types: %s
                             Use text-based tools if you need to search other file types.
                          3) Group related lookups into a single tool call when possible.
                          4) Make multiple tool calls at once when searching for different types of code.
                          5) Your responsibility ends at providing context.
                             Do not attempt to write the solution or pseudocode for the solution.
                             Your job is to *gather* the materials; the Code Agent's job is to *use* them.
                             Where code changes are needed, add the *target files* to the workspace using `addFilesToWorkspace`
                             and let the Code Agent write the code. (But when refactoring, it is usually sufficient to call `addSymbolUsagesToWorkspace`
                             and let Code Agent edit those fragments directly, instead of adding each call site's entire file.)
                             Note: Code Agent will also take care of creating new files, you only need to add existing files
                             to the Workspace.

                        Output discipline:
                          - Start each turn by pruning and summarizing before any new exploration.
                          - Think before calling tools.
                          - If you already know what to add, use Workspace tools directly; do not search redundantly.

                        %s
                        </instructions>

                        <%s>
                        %s
                        </%s>

                        <search-objective>
                        %s
                        </search-objective>

                        %s
                        %s

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
                        Final actions (answer, createOrReplaceTaskList, workspaceComplete, abortSearch) must be the ONLY tool in a turn.
                        If you include a final together with other tools, the final will be ignored for this turn.
                        It is NOT your objective to write code.

                        %s
                        """
                        .formatted(
                                supportedTypes,
                                CodePrompts.instance.askReminder(),
                                terminalObjective.type(),
                                goal,
                                terminalObjective.type(),
                                terminalObjective.text(),
                                emptyProjectGuidance,
                                buildSetupTaskGuidance,
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
                    Prefer answer(String) when no code changes are needed.
                    For code-change requests, use createOrReplaceTaskList(String explanation, List<String> tasks) to replace the entire list (completed tasks will be dropped). Titles are summarized automatically from task text; pass task texts only. Otherwise use abortSearch with reasons.
                    </beast-mode>
                    """;
        }

        messages.add(new UserMessage(directive));
        return messages;
    }

    static SystemMessage getSystemMessage() {
        return new SystemMessage(
                """
                        You are the Search Agent.
                        Your job is to be the **Code Agent's preparer**. You are a researcher and librarian, not a developer.
                          Your responsibilities are:
                            1.  **Find & Discover:** Use search and inspection tools to locate all relevant files, classes, and methods.
                            2.  **Curate & Prepare:** Aggressively prune the Workspace to leave *only* the essential context (files, summaries, notes) that the Code Agent will need.
                            3.  **Handoff:** Your final output is a clean workspace ready for the Code Agent to begin implementation.

                          Remember: **You must never write, create, or modify code.**
                          Your purpose is to *find* existing code, not *create* new code.
                          The Code Agent is solely responsible for all code generation and modification.
                        """);
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
            names.add("getSymbolLocations");
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
        names.add("skimDirectory");

        // Workspace curation
        names.add("addFilesToWorkspace");
        names.add("addClassesToWorkspace");
        names.add("addClassSummariesToWorkspace");
        names.add("addMethodsToWorkspace");
        names.add("addFileSummariesToWorkspace");
        names.add("addSymbolUsagesToWorkspace");
        names.add("appendNote");
        if (hasDroppableFragments()) {
            names.add("dropWorkspaceFragments");
        }

        // Human-in-the-loop tool (only meaningful when GUI is available; safe to include otherwise)
        if (io instanceof Chrome && objective != Objective.WORKSPACE_ONLY) {
            names.add("askHuman");
        }

        if (!mcpTools.isEmpty()) {
            names.add("callMcpTool");
        }

        // Task list tools are exposed only when the objective allows TASK_LIST via globalTerminals.
        // Do not add them here to avoid exposing them for other objectives.

        logger.debug("Allowed tool names: {}", names);
        return names;
    }

    private record TerminalObjective(String type, String text) {}

    private TerminalObjective buildTerminalObjective() {
        return switch (objective) {
            case ANSWER_ONLY ->
                new TerminalObjective(
                        "query",
                        """
                    Deliver a written answer using the answer(String) tool.
                    """);
            case TASKS_ONLY ->
                new TerminalObjective(
                        "instructions",
                        """
                    Deliver a task list using the createOrReplaceTaskList(String explanation, List<String> tasks) tool.
                    """);
            case WORKSPACE_ONLY ->
                new TerminalObjective(
                        "task",
                        """
                    Deliver a curated Workspace containing everything required for the follow-on Code Agent
                    to solve the given task.
                    """);
            case LUTZ ->
                new TerminalObjective(
                        "query_or_instructions",
                        """
                    Either deliver a written answer, solve the problem by invoking Code Agent, or decompose the problem into a task list.
                    In all cases, find and add appropriate source context to the Workspace so that you do not have to guess. Then,
                      - Prefer answer(String) when no code changes are needed.
                      - Prefer callCodeAgent(String) if the requested change is small.
                      - Otherwise, decompose the problem with createOrReplaceTaskList(String explanation, List<String> tasks); do not attempt to write code yet.
                    """);
        };
    }

    private enum ToolCategory {
        TERMINAL, // answer, createOrReplaceTaskList, workspaceComplete, abortSearch
        WORKSPACE_HYGIENE, // dropWorkspaceFragments, appendNote (safe to pair with terminals)
        RESEARCH // everything else (blocks terminals)
    }

    private ToolCategory categorizeTool(String toolName) {
        return switch (toolName) {
            case "answer",
                    "askForClarification",
                    "callCodeAgent",
                    "createOrReplaceTaskList",
                    "workspaceComplete",
                    "abortSearch" -> ToolCategory.TERMINAL;
            case "dropWorkspaceFragments", "appendNote" -> ToolCategory.WORKSPACE_HYGIENE;
            default -> ToolCategory.RESEARCH;
        };
    }

    private boolean isWorkspaceTool(ToolExecutionRequest request, ToolRegistry tr) {
        try {
            var vi = tr.validateTool(request);
            return vi.instance() instanceof WorkspaceTools;
        } catch (ToolRegistry.ToolValidationException e) {
            return false;
        }
    }

    private int priority(String toolName) {
        // Prioritize workspace pruning and adding summaries before deeper exploration.
        return switch (toolName) {
            case "dropWorkspaceFragments" -> 1;
            case "appendNote" -> 2;
            case "askHuman" -> 2;
            case "addClassSummariesToWorkspace", "addFileSummariesToWorkspace", "addMethodsToWorkspace" -> 3;
            case "addFilesToWorkspace", "addClassesToWorkspace", "addSymbolUsagesToWorkspace" -> 4;
            case "searchSymbols",
                    "getSymbolLocations",
                    "getUsages",
                    "searchSubstrings",
                    "searchFilenames",
                    "searchGitCommitMessages" -> 6;
            case "getClassSkeletons", "getClassSources", "getMethodSources" -> 7;
            case "getCallGraphTo", "getCallGraphFrom", "getFileContents", "getFileSummaries", "skimDirectory" -> 8;

            case "callCodeAgent" -> 99;
            case "createOrReplaceTaskList" -> 100;
            case "answer", "askForClarification", "workspaceComplete" -> 101; // should never co-occur
            case "abortSearch" -> 200;
            default -> 9;
        };
    }

    public Context pruneContext() throws InterruptedException {
        // Skip if workspace is empty
        if (contextPruned || context.isEmpty()) {
            return context;
        }

        var model = getScanModel();

        var wst = new WorkspaceTools(context);
        var tr = cm.getToolRegistry().builder().register(wst).register(this).build();

        var messages = buildInitialPruningPrompt();
        var toolNames = new ArrayList<String>();
        toolNames.add("performedInitialReview");
        if (hasDroppableFragments()) {
            toolNames.add("dropWorkspaceFragments");
        }
        var toolSpecs = tr.getTools(toolNames);

        io.llmOutput("\n**Brokk** performing initial workspace review…", ChatMessageType.AI, true, false);
        var janitorOpts = new Llm.Options(model, "Janitor: " + goal).withEcho();
        var jLlm = cm.getLlm(janitorOpts);
        jLlm.setOutput(this.io);
        var result = jLlm.sendRequest(messages, new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr));
        if (result.error() != null || result.isEmpty()) {
            return context;
        }

        // Record the turn
        sessionMessages.add(new UserMessage("Review the current workspace. If relevant, prune irrelevant fragments."));
        sessionMessages.add(result.aiMessage());

        // Execute tool requests (one shot; we're not responding back with results)
        var ai = ToolRegistry.removeDuplicateToolRequests(result.aiMessage());
        for (var req : ai.toolExecutionRequests()) {
            executeTool(req, tr, wst);
        }

        contextPruned = true;
        return context;
    }

    private List<ChatMessage> buildInitialPruningPrompt() {
        var messages = new ArrayList<ChatMessage>();
        var nonDroppableSection = buildNonDroppableSection();

        var sys = new SystemMessage(
                """
                %s
                You are the Janitor Agent (Workspace Reviewer). Single-shot cleanup: one response, then done.

                Scope:
                - Workspace curation ONLY. No code, no answers, no plans.

                Tools (exactly one):
                - performedInitialReview(): use ONLY when ALL fragments are short, focused, clean, and directly relevant.
                - dropWorkspaceFragments(fragments: {fragmentId, explanation}[]): batch ALL drops in a single call.

                Default behavior:
                - If a fragment is large, noisy, or mixed → write a short summary in the drop explanation → DROP it.
                  Large/noisy/mixed = long, multi-file, logs/traces/issues, big diffs, UI/test noise, unfocused content.

                Keep rule:
                - KEEP only if it is short, focused, directly relevant, AND keeping it is clearer than summarizing (i.e. to much information loss on summary).

                fragment.explanation (string) format:
                - Summary: information needed to solve the goal (e.g. descriptions, file paths, class names, method names, code snippets, stack traces)
                - Reason: one short sentence why dropped.
                - No implementation instructions.

                Response rule:
                - Tool call only; return exactly ONE tool call (performedInitialReview OR a single batched dropWorkspaceFragments).

                Do NOT drop non-droppable fragments (listed below).

                %s
                """
                        .formatted(getSystemMessage().text(), nonDroppableSection));
        messages.add(sys);

        // Current Workspace contents (use default viewing policy)
        ViewingPolicy vp = new ViewingPolicy(TaskResult.Type.CONTEXT);
        messages.addAll(WorkspacePrompts.getMessagesInAddedOrder(context, vp));

        // Goal and project context
        messages.add(new UserMessage(
                """
                <goal>
                %s
                </goal>

                Review the Workspace above. Use the dropWorkspaceFragments tool to remove ALL fragments that are not directly useful for accomplishing the goal.
                If the workspace is already well-curated, you're done!
                """
                        .formatted(goal)));

        return messages;
    }

    /**
     * Performs auto-scan using configured scan model and append behavior.
     * Sets scanAlreadyPerformed to true on success or failure to prevent retries.
     */
    private void performAutoScan() throws InterruptedException {
        scanContext();
        scanPerformed = true;
    }

    public Context scanContext() throws InterruptedException {
        StreamingChatModel scanModel = getScanModel();

        Set<ProjectFile> filesBeforeScan = getWorkspaceFileSet();

        var contextAgent = new ContextAgent(cm, scanModel, goal, this.io);
        io.llmOutput("\n**Brokk Context Engine** analyzing repository context…\n", ChatMessageType.AI, true, false);

        var recommendation = contextAgent.getRecommendations(context);
        var md = recommendation.metadata();
        var meta =
                new TaskResult.TaskMeta(TaskResult.Type.CONTEXT, Service.ModelConfig.from(scanModel, cm.getService()));

        if (recommendation.success() && !recommendation.fragments().isEmpty()) {
            var totalTokens = contextAgent.calculateFragmentTokens(recommendation.fragments());
            int finalBudget =
                    cm.getService().getMaxInputTokens(this.model) / 2 - Messages.getApproximateTokens(context);
            if (totalTokens > finalBudget) {
                var summaries = ContextFragment.describe(recommendation.fragments());
                context = context.addFragments(List.of(new ContextFragments.StringFragment(
                        cm,
                        "ContextAgent analyzed the repository and marked these fragments as highly relevant. Since including all would exceed the model's context capacity, their summarized descriptions are provided below:\n\n"
                                + summaries,
                        "Summary of ContextAgent Findings",
                        SyntaxConstants.SYNTAX_STYLE_NONE)));
            } else {
                logger.debug("Recommended context fits within final budget.");
                addToWorkspace(recommendation);
                io.llmOutput(
                        "\n\n**Brokk Context Engine** complete — contextual insights added to Workspace.\n",
                        ChatMessageType.AI);
            }
        } else {
            io.llmOutput("\n\nNo additional context insights found\n", ChatMessageType.AI);
        }

        // Record metrics and finalize context
        Set<ProjectFile> filesAfterScan = getWorkspaceFileSet();
        Set<ProjectFile> filesAdded = new HashSet<>(filesAfterScan);
        filesAdded.removeAll(filesBeforeScan);
        metrics.recordContextScan(filesAdded.size(), false, toRelativePaths(filesAdded), md);

        var contextAgentResult = createResult("Brokk Context Agent: " + goal, goal, meta);
        context = scanConfig.appendToScope() ? scope.append(contextAgentResult) : contextAgentResult.context();

        return context;
    }

    private StreamingChatModel getScanModel() {
        return scanConfig.scanModel() == null ? cm.getService().getScanModel() : scanConfig.scanModel();
    }

    /**
     * Returns true if the tool is a search/exploration tool that benefits from ContextAgent scanning.
     * Tool names must match those in calculateAllowedToolNames().
     */
    private boolean isSearchTool(String toolName) {
        return toolName.startsWith("search");
    }

    @Blocking
    public void addToWorkspace(ContextAgent.RecommendationResult recommendationResult) {
        logger.debug("Recommended context fits within final budget.");
        List<ContextFragment> selected = recommendationResult.fragments();
        // Group selected fragments by type
        var groupedByType = selected.stream().collect(Collectors.groupingBy(ContextFragment::getType));

        // Process ProjectPathFragments
        var pathFragments = groupedByType.getOrDefault(ContextFragment.FragmentType.PROJECT_PATH, List.of()).stream()
                .map(ContextFragments.ProjectPathFragment.class::cast)
                .toList();
        if (!pathFragments.isEmpty()) {
            logger.debug(
                    "Adding selected ProjectPathFragments: {}",
                    pathFragments.stream().map(ppf -> ppf.file().toString()).collect(Collectors.joining(", ")));
            context = context.addFragments(pathFragments);
        }

        // Process SkeletonFragments
        var skeletonFragments = groupedByType.getOrDefault(ContextFragment.FragmentType.SKELETON, List.of()).stream()
                .map(ContextFragments.SummaryFragment.class::cast)
                .toList();
        if (!skeletonFragments.isEmpty()) {
            context = context.addFragments(skeletonFragments);
        }

        // Emit pseudo-tool explanation for UX parity
        emitContextAddedExplanation(pathFragments, skeletonFragments);
    }

    private void emitContextAddedExplanation(
            List<ContextFragments.ProjectPathFragment> pathFragments,
            List<ContextFragments.SummaryFragment> skeletonFragments) {
        var details = new LinkedHashMap<String, Object>();
        details.put("fragmentCount", pathFragments.size() + skeletonFragments.size());

        if (!pathFragments.isEmpty()) {
            var paths = pathFragments.stream()
                    .map(ppf -> ppf.file().toString())
                    .sorted()
                    .toList();
            details.put("pathFragments", paths);
        }

        if (!skeletonFragments.isEmpty()) {
            var skeletonNames = skeletonFragments.stream()
                    .map(ContextFragment::description)
                    .map(ComputedValue::renderNowOrNull)
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();
            details.put("skeletonFragments", skeletonNames);
        }

        var explanation = ExplanationRenderer.renderExplanation("Adding context to workspace", details);
        io.llmOutput(explanation, ChatMessageType.AI);
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
            "Ask the human for clarification when the goal is unclear or necessary information cannot be found. Outputs the provided question to the user and stops.")
    public String askForClarification(
            @P("A concise question or clarification request for the human user.") String queryForUser) {
        io.llmOutput(queryForUser, ChatMessageType.AI);
        return queryForUser;
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

    @Tool(
            "Invoke the Code Agent to implement the current goal in a single shot using your provided instructions. Provide complete, self-contained instructions; only the Workspace and your instructions are visible to the Code Agent.")
    public String callCodeAgent(
            @P("Detailed instructions for the CodeAgent, referencing the current project and Workspace.")
                    String instructions)
            throws InterruptedException, ToolRegistry.FatalLlmException {
        // Append first the SearchAgent's result so far; CodeAgent appends its own result
        context = scope.append(createResult("Search: " + goal, goal));

        logger.debug("SearchAgent.callCodeAgent invoked with instructions: {}", instructions);
        io.llmOutput("**Code Agent** engaged: " + instructions, ChatMessageType.AI, true, false);
        var agent = new CodeAgent(cm, cm.getCodeModel());
        var opts = new HashSet<CodeAgent.Option>();

        // Keep a snapshot to determine if changes occurred
        Context contextBefore = context;

        var result = agent.executeWithoutHistory(context, instructions, opts);
        var stopDetails = result.stopDetails();
        var reason = stopDetails.reason();

        context = scope.append(result);

        boolean didChange = !context.getChangedFiles(contextBefore).isEmpty();

        if (reason == TaskResult.StopReason.SUCCESS) {
            // housekeeping
            new GitWorkflow(cm).performAutoCommit(instructions);
            cm.compressHistory();
            // CodeAgent appended its own result; we don't need to llmOutput anything redundant
            logger.debug("SearchAgent.callCodeAgent finished successfully");
            codeAgentJustSucceeded = true;
            return "CodeAgent finished with a successful build!";
        }

        // propagate critical failures
        if (reason == TaskResult.StopReason.INTERRUPTED) {
            throw new InterruptedException();
        }
        if (reason == TaskResult.StopReason.LLM_ERROR) {
            io.llmOutput("# Code Agent\n\nFatal LLM error during CodeAgent execution.", ChatMessageType.AI);
            logger.error("Fatal LLM error during CodeAgent execution: {}", stopDetails.explanation());
            throw new ToolRegistry.FatalLlmException(stopDetails.explanation());
        }

        // Non-success outcomes: continue planning on next loop
        codeAgentJustSucceeded = false;
        logger.debug("SearchAgent.callCodeAgent failed with reason {}; continuing planning", reason);

        // Provide actionable guidance; reserve workspace details only for BUILD_ERROR
        StringBuilder sb = new StringBuilder();
        if (reason == TaskResult.StopReason.BUILD_ERROR) {
            sb.append("CodeAgent was not able to get to a clean build. Details are in the Workspace.\n");
            if (didChange) {
                sb.append("Changes were made; you can undo with 'undoLastChanges' if they are negative progress.\n");
            }
            sb.append(
                    "Consider retrying with 'deferBuild=true' for multi-step changes, then complete follow-up fixes.\n");
        } else {
            sb.append("CodeAgent did not complete successfully.\n");
            if (didChange) {
                sb.append("If the changes are not helpful, you can undo with 'undoLastChanges'.\n");
            }
            sb.append(
                    "Try smaller, focused edits with valid SEARCH/REPLACE blocks; add or refresh required files using workspace tools "
                            + "(e.g., addFilesToWorkspace, addFileSummariesToWorkspace), or use callSearchAgent to locate the correct targets, then retry callCodeAgent.\n");
        }
        sb.append("Continuing search and planning.\n");
        return sb.toString();
    }

    // =======================
    // Finalization and errors
    // =======================

    private TaskResult createResult(String action, String goal) {
        return createResult(action, goal, taskMeta());
    }

    private TaskResult createResult(String action, String goal, TaskResult.TaskMeta meta) {
        // Build final messages from already-streamed transcript; fallback to session-local messages if empty
        List<ChatMessage> finalMessages = new ArrayList<>(io.getLlmRawMessages());
        if (finalMessages.isEmpty()) {
            finalMessages = new ArrayList<>(sessionMessages);
        }

        var stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
        var fragment = new ContextFragments.TaskFragment(cm, finalMessages, goal);

        // Record final metrics
        recordFinalWorkspaceState();
        metrics.recordOutcome(stopDetails.reason(), getWorkspaceFileSet().size());

        return new TaskResult(action, fragment, context, stopDetails, meta);
    }

    private TaskResult errorResult(TaskResult.StopDetails details) {
        return errorResult(details, null);
    }

    private TaskResult errorResult(TaskResult.StopDetails details, @Nullable TaskResult.TaskMeta meta) {
        // Build final messages from already-streamed transcript; fallback to session-local messages if empty
        List<ChatMessage> finalMessages = new ArrayList<>(io.getLlmRawMessages());
        if (finalMessages.isEmpty()) {
            finalMessages = new ArrayList<>(sessionMessages);
        }

        String action = "Search: " + goal + " [" + details.reason().name() + "]";
        var fragment = new ContextFragments.TaskFragment(cm, finalMessages, goal);

        // Record final metrics
        recordFinalWorkspaceState();
        metrics.recordOutcome(details.reason(), getWorkspaceFileSet().size());

        return new TaskResult(action, fragment, context, details, meta);
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
        // Allow time to compute
        try {
            context.awaitContextsAreComputed(ContextHistory.SNAPSHOT_AWAIT_TIMEOUT);
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for contexts to be computed", e);
        }
        return context.allFragments()
                .filter(SearchAgent::isWorkspaceFileFragment)
                .flatMap(f -> f.files().renderNowOr(Set.of()).stream())
                .collect(Collectors.toSet());
    }

    private Set<String> toRelativePaths(Set<ProjectFile> files) {
        return files.stream().map(pf -> pf.getRelPath().toString()).collect(Collectors.toSet());
    }

    private List<SearchMetrics.FragmentInfo> getWorkspaceFragments() {
        // Allow time to compute
        try {
            context.awaitContextsAreComputed(ContextHistory.SNAPSHOT_AWAIT_TIMEOUT);
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for contexts to be computed", e);
        }
        return context.allFragments()
                .filter(SearchAgent::isWorkspaceFileFragment)
                .map(f -> new SearchMetrics.FragmentInfo(
                        f.getType().toString(),
                        f.id(),
                        f.description().renderNowOr("(empty)"),
                        f.files().renderNowOr(Set.of()).stream()
                                .map(pf -> pf.getRelPath().toString())
                                .sorted()
                                .toList()))
                .toList();
    }

    // =======================
    // Non-droppable helpers
    // =======================

    /**
     * Returns a formatted section listing non-droppable fragments present in the current workspace,
     * or an empty string when no such fragments exist. This educates the LLM to avoid futile pruning loops.
     */
    private String buildNonDroppableSection() {
        var items = context.allFragments()
                .filter(f -> f instanceof ContextFragments.StringFragment)
                .map(f -> (ContextFragments.StringFragment) f)
                .filter(sf ->
                        sf.specialType().isPresent() && !sf.specialType().get().droppable())
                .map(sf -> "- " + sf.description().join() + " (fragmentid=" + sf.id() + "): non-droppable by policy.")
                .sorted()
                .toList();

        if (items.isEmpty()) {
            return "";
        }

        return """
                <non_droppable>
                The following fragments cannot be dropped by policy. Do NOT attempt to drop them:
                %s
                </non_droppable>
                """
                .formatted(String.join("\n", items));
    }

    /**
     * True when there exists at least one droppable fragment in the current workspace.
     * Special text fragments may be marked non-droppable via SpecialTextType.
     */
    private boolean hasDroppableFragments() {
        return context.allFragments().anyMatch(f -> {
            if (f instanceof ContextFragments.StringFragment sf) {
                var st = sf.specialType();
                if (st.isPresent()) {
                    return st.get().droppable();
                }
            }
            // All other fragment types are considered droppable by default
            return true;
        });
    }

    // =======================
    // State and summarization
    // =======================

    private boolean shouldSummarize(String toolName) {
        return Set.of(
                        "getSymbolLocations",
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

    private TaskResult.TaskMeta taskMeta() {
        return new TaskResult.TaskMeta(TaskResult.Type.SEARCH, Service.ModelConfig.from(model, cm.getService()));
    }
}
