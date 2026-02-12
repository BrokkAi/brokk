package ai.brokk.agents;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.LlmOutputMeta;
import ai.brokk.Service;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.concurrent.ComputedValue;
import ai.brokk.context.Context;
import ai.brokk.context.ContextDelta;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.ContextHistory;
import ai.brokk.git.GitWorkflow;
import ai.brokk.gui.Chrome;
import ai.brokk.mcp.McpUtils;
import ai.brokk.metrics.SearchMetrics;
import ai.brokk.project.IProject;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.prompts.McpPrompts;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.prompts.SearchPrompts.Objective;
import ai.brokk.prompts.SearchPrompts.Terminal;
import ai.brokk.tools.DependencyTools;
import ai.brokk.tools.ExplanationRenderer;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.tools.WorkspaceTools;
import ai.brokk.util.Messages;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Streams;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
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
 * SearchAgent:
 * - Uses tools to curate Workspace context for follow-on coding.
 * - Starts by calling ContextAgent to add recommended fragments to the Workspace.
 * - Adds every learning step to Context history (no hidden state).
 * - Summarizes very large tool outputs before recording them.
 * - If the model exceeds its context limit, restores the last successful checkpoint and enters
 *   {@code dropOnlyMode}, where the agent is constrained to cleanup-only actions (dropping fragments) until the
 *   Workspace is under the limit.
 * - Never writes code or answers questions itself; it prepares the Workspace for a later Code Agent run.
 */
public class SearchAgent {
    private static final Logger logger = LogManager.getLogger(SearchAgent.class);

    /**
     * Configuration for automatic context scanning behavior.
     */
    public record ScanConfig(
            boolean autoScan, // Whether to auto-scan when workspace is empty or on first search tool
            @Nullable StreamingChatModel scanModel, // Model to use for ContextAgent (null = use project default)
            boolean appendToScope, // Whether to append scan results to scope history
            boolean autoPrune // Whether to run a janitor turn before starting search proper
            ) {
        public static ScanConfig defaults() {
            return new ScanConfig(true, null, true, true);
        }

        public static ScanConfig disabled() {
            return new ScanConfig(false, null, true, false);
        }

        public static ScanConfig withModel(StreamingChatModel model) {
            return new ScanConfig(true, model, true, true);
        }

        public static ScanConfig noAppend() {
            return new ScanConfig(true, null, false, true);
        }
    }

    private static final int SUMMARIZE_THRESHOLD = 1_000;

    private final IContextManager cm;
    private final StreamingChatModel model;
    private final ContextManager.@Nullable TaskScope scope;
    private final Llm llm;
    private final Llm summarizer;
    private final IConsoleIO io;
    private final String goal;
    private final List<McpPrompts.McpTool> mcpTools;
    private final List<String> staticTools;
    private final SearchMetrics metrics;
    private final ScanConfig scanConfig;
    private boolean scanPerformed;
    private boolean contextPruned;

    private boolean terminalCompletionReported = false;

    private final Objective objective;

    SearchState currentState;
    private SearchState checkpointState;

    private final Set<ContextFragment> originalPinnedFragments;
    private final List<ContextFragment> droppedFragments = new ArrayList<>();

    public SearchAgent(
            Context initialContext,
            String goal,
            StreamingChatModel model,
            ContextManager.TaskScope scope,
            IConsoleIO io,
            ScanConfig scanConfig) {
        this(initialContext, goal, model, Objective.WORKSPACE_ONLY, scope, io, scanConfig);
    }

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
        var llmOptions = new Llm.Options(model, goal, TaskResult.Type.SEARCH).withEcho();
        this.llm = cm.getLlm(llmOptions);
        this.llm.setOutput(this.io);

        var summarizeModel = cm.getService().getModel(ModelType.SCAN);
        this.summarizer = cm.getLlm(summarizeModel, goal, TaskResult.Type.SUMMARIZE);

        this.metrics = "true".equalsIgnoreCase(System.getenv("BRK_COLLECT_METRICS"))
                ? SearchMetrics.tracking()
                : SearchMetrics.noOp();

        this.mcpTools = initMcpTools(cm.getProject());
        this.currentState = SearchState.initial(initialContext);
        this.checkpointState = currentState;
        this.originalPinnedFragments = initialContext.getPinnedFragments().collect(Collectors.toSet());
        this.scanConfig = scanConfig;
        this.staticTools = initStaticTools(cm.getProject(), mcpTools);
        this.objective = objective;
    }

    private static List<McpPrompts.McpTool> initMcpTools(IProject project) {
        var mcpConfig = project.getMcpConfig();
        var tools = new ArrayList<McpPrompts.McpTool>();
        for (var server : mcpConfig.servers()) {
            if (server.tools() != null) {
                for (var toolName : server.tools()) {
                    tools.add(new McpPrompts.McpTool(server, toolName));
                }
            }
        }
        return tools;
    }

    private static List<String> initStaticTools(IProject project, List<McpPrompts.McpTool> mcpTools) {
        var tools = new ArrayList<String>();

        // Search-specific analyzer tools
        tools.add("searchSymbols");
        tools.add("scanUsages");
        tools.add("getSymbolLocations");
        tools.add("skimDirectory");

        // Workspace analyzer tools
        tools.add("addClassesToWorkspace");
        tools.add("addClassSummariesToWorkspace");
        tools.add("addMethodsToWorkspace");
        tools.add("addFileSummariesToWorkspace");

        // Non-analyzer tools
        tools.add("searchSubstrings");
        tools.add("searchGitCommitMessages");
        tools.add("getGitLog");
        tools.add("explainCommit");
        tools.add("searchFilenames");
        tools.add("addFilesToWorkspace");
        tools.add("addUrlContentsToWorkspace");

        if (!mcpTools.isEmpty()) {
            tools.add("callMcpTool");
        }

        // Filter out analyzer-required tools at the very end
        return WorkspaceTools.filterByAnalyzerAvailability(tools, project);
    }

    public SearchAgent(Context initialContext, String goal, StreamingChatModel model, ContextManager.TaskScope scope) {
        this(
                initialContext,
                goal,
                model,
                scope,
                initialContext.getContextManager().getIo(),
                ScanConfig.defaults());
    }

    public TaskResult execute() {
        TaskResult tr;
        try {
            tr = executeInternal();
            if (metrics instanceof SearchMetrics.Tracking) {
                var json = metrics.toJson(goal, tr.stopDetails().reason() == TaskResult.StopReason.SUCCESS);
                System.err.println("\nBRK_SEARCHAGENT_METRICS=" + json);
            }
        } catch (InterruptedException e) {
            logger.debug("Search interrupted", e);
            tr = errorResult(new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED));
        }

        var details = tr.stopDetails();
        if (details.reason() != TaskResult.StopReason.SUCCESS || !terminalCompletionReported) {
            var message =
                    switch (details.reason()) {
                        case SUCCESS -> "Search finished.";
                        case INTERRUPTED -> "Cancelled by user.";
                        default ->
                            details.explanation().isBlank() ? details.reason().name() : details.explanation();
                    };
            reportComplete(details.reason(), message);
        }

        return tr;
    }

    private boolean shouldAutomaticallyScan() {
        return scanConfig.autoScan() && !scanPerformed;
    }

    private TaskResult executeInternal() throws InterruptedException {
        if (scanConfig.autoPrune()) {
            pruneContext();
        }
        if (shouldAutomaticallyScan() && currentState.context().isFileContentEmpty()) {
            performAutoScan();
        }

        boolean dropOnlyMode = false;
        @Nullable PendingTerminal pendingTerminal = null;

        while (true) {
            SearchState stateAtTurnStart = currentState;

            if (pendingTerminal != null) {
                assert dropOnlyMode;
                assert hasDroppableFragments(currentState.context());
            }

            var sta = new SingleTurnAgent(this, stateAtTurnStart, dropOnlyMode, pendingTerminal);
            var outcome = sta.executeTurn();

            switch (outcome) {
                case TurnOutcome.Final f -> {
                    return f.result();
                }
                case TurnOutcome.Overflow overflow -> {
                    assert pendingTerminal == null;
                    if (currentState.equals(checkpointState)) {
                        // our checkpoint is bad, this can happen if the initial context given to SearchAgent is too
                        // large
                        return errorResult(
                                new TaskResult.StopDetails(
                                        TaskResult.StopReason.LLM_CONTEXT_SIZE,
                                        "Context limit exceeded before search started"),
                                taskMeta(),
                                currentState.context());
                    }

                    io.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            "Context limit exceeded. Restoring last successful checkpoint and entering recovery mode.");

                    currentState = checkpointState;
                    if (!hasDroppableFragments(currentState.context())) {
                        return errorResult(
                                new TaskResult.StopDetails(
                                        TaskResult.StopReason.LLM_CONTEXT_SIZE,
                                        "Context limit exceeded, but no fragments are droppable after restoring to the last checkpoint; cannot recover by pruning."),
                                taskMeta(),
                                currentState.context());
                    }

                    dropOnlyMode = true;
                }
                case TurnOutcome.AutoScan autoScan -> {
                    assert !dropOnlyMode;
                    performAutoScan();
                }
                case TurnOutcome.Continue c -> {
                    currentState = new SearchState(
                            c.contextAfterTurn(),
                            c.sessionMessagesAfterTurn(),
                            stateAtTurnStart.context(),
                            stateAtTurnStart.presentedRelatedFiles());
                    checkpointState = stateAtTurnStart;
                    dropOnlyMode = false;
                }
                case TurnOutcome.PendingTerminal pt -> {
                    currentState = new SearchState(
                            pt.contextAfterTurn(),
                            pt.sessionMessagesAfterTurn(),
                            stateAtTurnStart.lastTurnContext(),
                            stateAtTurnStart.presentedRelatedFiles());
                    pendingTerminal = pt.pendingTerminal();
                    dropOnlyMode = true;
                }
                default -> throw new AssertionError("Unexpected outcome " + outcome);
            }
        }
    }

    private ToolRegistry createToolRegistry(WorkspaceTools wst, Object toolProvider) {
        var builder = cm.getToolRegistry().builder().register(wst).register(toolProvider);
        if (DependencyTools.isSupported(cm.getProject())) {
            builder.register(new DependencyTools(cm));
        }
        return builder.build();
    }

    private List<String> calculateTerminalTools() {
        var terminals = new ArrayList<String>();
        var allowed = objective.terminals();

        if (allowed.contains(Terminal.DESCRIBE_ISSUE)) {
            terminals.add("describeIssue");
        }
        if (allowed.contains(Terminal.ANSWER)) {
            terminals.add("answer");
        }
        if (allowed.contains(Terminal.WORKSPACE)) {
            terminals.add("workspaceComplete");
        }
        if (allowed.contains(Terminal.TASK_LIST)) {
            terminals.add("createOrReplaceTaskList");
        }
        if (allowed.contains(Terminal.CODE)) {
            terminals.add("callCodeAgent");
        }

        // allow user-invoked SearchAgent to ask for clarification in an interactive Chrome
        if (io instanceof Chrome
                && Set.of(Objective.ANSWER_ONLY, Objective.TASKS_ONLY, Objective.LUTZ)
                        .contains(objective)) {
            terminals.add("askForClarification");
        }

        terminals.add("abortSearch");
        return terminals;
    }

    private void endTurnAndRecordFileChanges(Set<ProjectFile> filesBeforeSet, Context contextAfterTurn) {
        Set<ProjectFile> filesAfterSet = workspaceFiles(contextAfterTurn);
        Set<ProjectFile> added = new HashSet<>(filesAfterSet);
        added.removeAll(filesBeforeSet);
        metrics.recordFilesAdded(added.size());
        metrics.recordFilesAddedPaths(toRelativePaths(added));
        metrics.endTurn(toRelativePaths(filesBeforeSet), toRelativePaths(filesAfterSet));
    }

    private void updateDroppedHistory(Context oldContext, Context newContext) {
        if (oldContext.equals(newContext)) return;

        ContextDelta delta = ContextDelta.between(oldContext, newContext).join();
        droppedFragments.addAll(delta.removedFragments());
    }

    List<String> calculateAllowedToolNames(Context context, boolean dropOnlyMode) {
        if (dropOnlyMode) {
            assert hasDroppableFragments(context); // caller should have verified
            return List.of("dropWorkspaceFragments");
        }

        // start with the global search tools
        var names = new ArrayList<>(staticTools);

        if (hasDroppableFragments(context)) {
            names.add("dropWorkspaceFragments");
        }

        if (DependencyTools.isSupported(cm.getProject())) {
            names.add("importDependency");
        }

        return names;
    }

    enum ToolCategory {
        TERMINAL,
        WORKSPACE_HYGIENE,
        RESEARCH
    }

    ToolCategory categorizeTool(String toolName) {
        return switch (toolName) {
            case "answer",
                    "describeIssue",
                    "askForClarification",
                    "callCodeAgent",
                    "createOrReplaceTaskList",
                    "workspaceComplete",
                    "abortSearch" -> ToolCategory.TERMINAL;
            case "dropWorkspaceFragments" -> ToolCategory.WORKSPACE_HYGIENE;
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
        return switch (toolName) {
            case "dropWorkspaceFragments" -> 1;
            case "addFilesToWorkspace" -> 4;
            case "addClassesToWorkspace", "addFileSummariesToWorkspace" -> 5;
            case "addMethodsToWorkspace", "addClassSummariesToWorkspace" -> 6;
            case "searchSymbols",
                    "getSymbolLocations",
                    "scanUsages",
                    "searchSubstrings",
                    "searchFilenames",
                    "searchGitCommitMessages" -> 20;
            case "getClassSkeletons", "getClassSources", "getMethodSources" -> 30;
            case "getCallGraphTo", "getCallGraphFrom", "getFileContents", "getFileSummaries", "skimDirectory" -> 40;

            case "callCodeAgent" -> 99;
            case "createOrReplaceTaskList" -> 100;
            case "answer", "askForClarification", "workspaceComplete" -> 101;
            case "abortSearch" -> 200;
            default -> 9;
        };
    }

    private int priority(ToolExecutionRequest req) {
        return priority(req.name());
    }

    public Context pruneContext() throws InterruptedException {
        Context context = currentState.context();
        if (contextPruned || !hasDroppableFragments(context)) {
            return context;
        }

        var scanModel = getScanModel();
        var wst = new WorkspaceTools(context);
        var toolProvider = new SingleTurnAgent(this, currentState, false, null);
        var tr = createToolRegistry(wst, toolProvider);

        var messages = SearchPrompts.instance.buildPruningPrompt(context, goal);
        var toolNames = new ArrayList<String>();
        toolNames.add("performedInitialReview");
        if (hasDroppableFragments(context)) {
            toolNames.add("dropWorkspaceFragments");
        }
        var toolSpecs = tr.getTools(toolNames);

        io.llmOutput(
                "\n**Brokk** performing initial workspace review…", ChatMessageType.AI, LlmOutputMeta.newMessage());
        var janitorOpts = new Llm.Options(scanModel, "Janitor: " + goal, TaskResult.Type.SEARCH).withEcho();
        var jLlm = cm.getLlm(janitorOpts);
        jLlm.setOutput(this.io);

        var result = jLlm.sendRequest(messages, new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr));
        if (result.error() != null || result.isEmpty()) {
            return currentState.context();
        }

        var ai = ToolRegistry.removeDuplicateToolRequests(result.aiMessage());
        for (var req : ai.toolExecutionRequests()) {
            toolProvider.executeTool(req);
        }

        currentState = currentState.withContext(toolProvider.context);
        contextPruned = true;
        return currentState.context();
    }

    private void performAutoScan() throws InterruptedException {
        scanContext();
        scanPerformed = true;
    }

    private Objective getObjective() {
        return objective;
    }

    public Context scanContext() throws InterruptedException {
        Context context = currentState.context();

        StreamingChatModel scanModel = getScanModel();
        Set<ProjectFile> filesBeforeScan = workspaceFiles(context);

        var contextAgent = new ContextAgent(cm, scanModel, goal, this.io);
        io.llmOutput(
                "\n**Brokk Context Engine** analyzing repository context…\n",
                ChatMessageType.AI,
                LlmOutputMeta.newMessage());

        var recommendation = contextAgent.getRecommendations(context);
        var md = recommendation.metadata();
        var meta = new TaskResult.TaskMeta(TaskResult.Type.SCAN, Service.ModelConfig.from(scanModel, cm.getService()));

        if (recommendation.success() && !recommendation.fragments().isEmpty()) {
            var totalTokens = contextAgent.calculateFragmentTokens(recommendation.fragments());
            int finalBudget =
                    cm.getService().getMaxInputTokens(this.model) / 2 - Messages.getApproximateTokens(context);
            if (totalTokens > finalBudget) {
                var summaries = ContextFragment.describe(recommendation.fragments().stream());
                context = context.addFragments(List.of(new ContextFragments.StringFragment(
                        cm,
                        "ContextAgent analyzed the repository and marked these fragments as highly relevant. Since including all would exceed the model's context capacity, their summarized descriptions are provided below:\n\n"
                                + summaries,
                        "Summary of ContextAgent Findings",
                        SyntaxConstants.SYNTAX_STYLE_NONE)));
            } else {
                context = context.addFragments(recommendation.fragments());
                emitContextAddedExplanation(recommendation.fragments());
                io.llmOutput(
                        "\n\n**Brokk Context Engine** complete — contextual insights added to Workspace.\n",
                        ChatMessageType.AI,
                        LlmOutputMeta.DEFAULT);
            }
        } else {
            io.llmOutput("\n\nNo additional context insights found\n", ChatMessageType.AI, LlmOutputMeta.DEFAULT);
        }

        Set<ProjectFile> filesAfterScan = workspaceFiles(context);
        Set<ProjectFile> filesAdded = new HashSet<>(filesAfterScan);
        filesAdded.removeAll(filesBeforeScan);
        metrics.recordContextScan(filesAdded.size(), false, toRelativePaths(filesAdded), md);

        var contextAgentResult = createResult("Brokk Context Agent: " + goal, goal, meta, context);
        context = (scanConfig.appendToScope() && scope != null)
                ? scope.append(contextAgentResult)
                : contextAgentResult.context();

        currentState = currentState.withContext(context);
        return currentState.context();
    }

    private StreamingChatModel getScanModel() {
        return scanConfig.scanModel() == null ? cm.getService().getScanModel() : scanConfig.scanModel();
    }

    private boolean isSearchTool(String toolName) {
        return toolName.startsWith("search");
    }

    /**
     * Invokes the Code Agent to implement instructions using the current SearchState.
     * This is intended for internal/legacy callers and does not advance the SearchAgent's turn loop.
     */
    @Blocking
    public TaskResult callCodeAgent(String instructions) throws InterruptedException {
        if (scope == null) {
            return errorResult(new TaskResult.StopDetails(
                    TaskResult.StopReason.TOOL_ERROR, "Cannot call Code Agent without a Task Scope."));
        }

        ArchitectAgent architect = new ArchitectAgent(
                cm,
                cm.getService().getModel(ModelType.ARCHITECT),
                cm.getCodeModel(),
                instructions,
                scope,
                currentState.context());

        return architect.execute();
    }

    private void emitContextAddedExplanation(List<ContextFragment> fragments) {
        var details = new LinkedHashMap<String, Object>();
        details.put("fragmentCount", fragments.size());

        var descriptions = fragments.stream()
                .map(ContextFragment::description)
                .map(ComputedValue::renderNowOrNull)
                .filter(Objects::nonNull)
                .sorted()
                .toList();

        if (!descriptions.isEmpty()) {
            details.put("fragments", descriptions);
        }

        var explanation = ExplanationRenderer.renderExplanation("Adding context to workspace", details);
        io.llmOutput(explanation, ChatMessageType.AI, LlmOutputMeta.DEFAULT);
    }

    // public for ToolRegistry
    public static final class SingleTurnAgent {
        private final SearchAgent agent;
        private final boolean dropOnlyMode;
        private final @Nullable PendingTerminal pendingTerminal;

        private Context context; // mutable
        private final @Nullable Context lastTurnContext;
        private final List<ChatMessage> sessionMessages;

        private final WorkspaceTools wst;
        private final ToolRegistry tr;

        private SingleTurnAgent(
                SearchAgent agent,
                SearchState stateAtTurnStart,
                boolean dropOnlyMode,
                @Nullable PendingTerminal pendingTerminal) {
            this.agent = agent;
            this.dropOnlyMode = dropOnlyMode;
            this.pendingTerminal = pendingTerminal;

            this.context = stateAtTurnStart.context();
            this.lastTurnContext = stateAtTurnStart.lastTurnContext();
            this.sessionMessages = new ArrayList<>(stateAtTurnStart.sessionMessages());

            this.wst = new WorkspaceTools(context);
            this.tr = agent.createToolRegistry(wst, this);
        }

        private TurnOutcome executeTurn() throws InterruptedException {
            assert pendingTerminal == null || agent.hasDroppableFragments(context);

            var prep = preparePrompt();

            Set<ProjectFile> filesBeforeSet = agent.workspaceFiles(context);

            agent.metrics.startTurn();
            long turnStartTime = System.currentTimeMillis();
            try {
                agent.io.showTransientMessage("Brokk Search is preparing the next actions…");
                var result = agent.llm.sendRequest(
                        prep.messages(), new ToolContext(prep.toolSpecs(), ToolChoice.REQUIRED, tr));

                long llmTimeMs = System.currentTimeMillis() - turnStartTime;
                var tokenUsage = result.metadata();
                int inputTokens = tokenUsage != null ? tokenUsage.inputTokens() : 0;
                int cachedTokens = tokenUsage != null ? tokenUsage.cachedInputTokens() : 0;
                int thinkingTokens = tokenUsage != null ? tokenUsage.thinkingTokens() : 0;
                int outputTokens = tokenUsage != null ? tokenUsage.outputTokens() : 0;
                agent.metrics.recordLlmCall(llmTimeMs, inputTokens, cachedTokens, thinkingTokens, outputTokens);

                if (result.error() != null) {
                    var details = TaskResult.StopDetails.fromResponse(result);
                    if (details.reason() == TaskResult.StopReason.LLM_CONTEXT_SIZE) {
                        assert pendingTerminal == null;
                        return TurnOutcome.Overflow.INSTANCE;
                    }
                    agent.io.showNotification(
                            IConsoleIO.NotificationRole.INFO, "LLM error planning next step: " + details.explanation());
                    return new TurnOutcome.Final(agent.errorResult(details, agent.taskMeta(), context));
                }

                var ai = ToolRegistry.removeDuplicateToolRequests(result.aiMessage());

                if (agent.shouldAutomaticallyScan()
                        && ai.toolExecutionRequests().stream().anyMatch(req -> agent.isSearchTool(req.name()))) {
                    assert pendingTerminal == null;
                    return TurnOutcome.AutoScan.INSTANCE;
                }

                if (prep.extraUserMessage() != null) {
                    sessionMessages.add(prep.extraUserMessage());
                }
                sessionMessages.add(new UserMessage("What tools do you want to use next?"));
                sessionMessages.add(result.aiMessage());

                if (!ai.hasToolExecutionRequests()) {
                    return new TurnOutcome.Final(agent.errorResult(
                            new TaskResult.StopDetails(
                                    TaskResult.StopReason.TOOL_ERROR, "No tool requests found in LLM response."),
                            agent.taskMeta(),
                            context));
                }

                var orderedRequests = ai.toolExecutionRequests().stream()
                        .sorted(Comparator.comparingInt(agent::priority))
                        .toList();

                if (pendingTerminal != null) {
                    orderedRequests = orderedRequests.stream()
                            .filter(req -> "dropWorkspaceFragments".equals(req.name()))
                            .toList();
                }

                @Nullable ToolExecutionRequest terminalRequest = null;
                List<ToolExecutionRequest> primaryCalls;

                if (pendingTerminal == null) {
                    terminalRequest = orderedRequests.stream()
                            .filter(req -> agent.categorizeTool(req.name()) == ToolCategory.TERMINAL)
                            .findFirst()
                            .orElse(null);

                    primaryCalls = orderedRequests.stream()
                            .filter(req -> agent.categorizeTool(req.name()) != ToolCategory.TERMINAL)
                            .toList();
                } else {
                    primaryCalls = orderedRequests;
                }

                Set<ToolCategory> categoriesSeen = new HashSet<>();
                Context contextAtTurnStart = context;

                for (var req : primaryCalls) {
                    ToolExecutionResult toolResult = executeTool(req);
                    if (toolResult.status() == ToolExecutionResult.Status.FATAL) {
                        var details =
                                new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, toolResult.resultText());
                        return new TurnOutcome.Final(agent.errorResult(details, agent.taskMeta(), context));
                    }

                    ToolExecutionResult finalResult = toolResult;
                    if (pendingTerminal == null) {
                        var display = toolResult.resultText();
                        boolean summarize = toolResult.status() == ToolExecutionResult.Status.SUCCESS
                                && Messages.getApproximateTokens(display) > SUMMARIZE_THRESHOLD
                                && agent.shouldSummarize(req.name());

                        if (summarize) {
                            var reasoning = SearchAgent.getArgumentsMap(req)
                                    .getOrDefault("reasoning", "")
                                    .toString();
                            display = agent.summarizeResult(agent.goal, req, display, reasoning);
                            finalResult = ToolExecutionResult.create(req, toolResult.status(), display);
                        }
                    }

                    sessionMessages.add(finalResult.toExecutionResultMessage());
                    categoriesSeen.add(agent.categorizeTool(req.name()));
                }

                if (pendingTerminal != null) {
                    return new TurnOutcome.Final(
                            agent.finalizePendingTerminal(Objects.requireNonNull(pendingTerminal), context));
                }

                // if the agent ran a search, but also called a terminal, we'll let the terminal take priority
                boolean executedNonHygiene = categoriesSeen.stream().anyMatch(c -> c != ToolCategory.WORKSPACE_HYGIENE);
                boolean contextSafeForTerminal = context.equals(contextAtTurnStart) || !executedNonHygiene;
                if (terminalRequest != null && contextSafeForTerminal) {
                    var termExec = executeTool(terminalRequest);
                    sessionMessages.add(termExec.toExecutionResultMessage());

                    if (termExec.status() != ToolExecutionResult.Status.SUCCESS) {
                        return new TurnOutcome.Final(agent.errorResult(
                                new TaskResult.StopDetails(
                                        TaskResult.StopReason.TOOL_ERROR,
                                        "Terminal tool '" + terminalRequest.name() + "' failed: "
                                                + termExec.resultText()),
                                agent.taskMeta(),
                                context));
                    }

                    context = agent.resetPinsToOriginal(context);
                    var pending = new PendingTerminal(termExec);
                    // take an extra turn to drop fragments after making the terminal decision ONLY IF
                    // - we didn't already drop this turn, AND
                    // - there's a bunch of autopinned fragments that could have been stopping a drop this turn
                    // ... in short, we trust the agent to drop appropriately, but if it maybe wanted to drop
                    // but couldn't, we give it a final opportunity to do so.
                    boolean worthDropping = context.allFragments()
                                    .filter(f -> context.isPinned(f) && !agent.isPinnedBySystem(f))
                                    .mapToLong(f -> Messages.getApproximateTokens(
                                            f.text().join()))
                                    .sum()
                            > 20_000;
                    if (worthDropping && !categoriesSeen.contains(ToolCategory.WORKSPACE_HYGIENE)) {
                        return new TurnOutcome.PendingTerminal(context, List.copyOf(sessionMessages), pending);
                    }
                    return new TurnOutcome.Final(agent.finalizePendingTerminal(pending, context));
                }

                Set<ProjectFile> filesAfterSet = agent.workspaceFiles(context);

                Set<ProjectFile> added = new HashSet<>(filesAfterSet);
                added.removeAll(filesBeforeSet);
                if (!added.isEmpty() && agent.scope != null) {
                    agent.scope.publish(context);
                }

                return new TurnOutcome.Continue(context, List.copyOf(sessionMessages));
            } finally {
                agent.endTurnAndRecordFileChanges(filesBeforeSet, context);
            }
        }

        private TurnPrompt preparePrompt() throws InterruptedException {
            wst.setContext(context);

            var related = context.buildRelatedSymbols(10, 20, agent.currentState.presentedRelatedFiles());
            if (!related.isEmpty()) {
                Set<ProjectFile> updatedRelated = new HashSet<>(agent.currentState.presentedRelatedFiles());
                updatedRelated.addAll(related.keySet());
                agent.currentState = agent.currentState.withPresentedRelatedFiles(updatedRelated);
            }

            var messages = SearchPrompts.instance.buildPrompt(
                    context,
                    agent.model,
                    agent.taskMeta(),
                    agent.goal,
                    agent.getObjective(),
                    agent.mcpTools,
                    sessionMessages,
                    related);

            if (dropOnlyMode) {
                context = agent.resetPinsToOriginal(context);
                messages = new ArrayList<>(messages);

                assert agent.hasDroppableFragments(context);
                messages.add(
                        new UserMessage(
                                "The Workspace has exceeded the context limit. You MUST use 'dropWorkspaceFragments' to remove irrelevant or redundant fragments before you can continue."));
            } else {
                context = agent.applyPinning(context, lastTurnContext);
            }

            @Nullable UserMessage extraUserMessage = null;

            List<String> allowedToolNames;
            List<String> agentTerminalTools;

            if (pendingTerminal == null) {
                allowedToolNames = agent.calculateAllowedToolNames(context, dropOnlyMode);
                agentTerminalTools = dropOnlyMode ? List.of() : agent.calculateTerminalTools();
            } else {
                messages = new ArrayList<>(messages);
                extraUserMessage = new UserMessage(
                        "Search is complete. Please perform a final cleanup of the Workspace using 'dropWorkspaceFragments' to remove any remaining irrelevant information.");
                messages.add(extraUserMessage);

                allowedToolNames = agent.hasDroppableFragments(context) ? List.of("dropWorkspaceFragments") : List.of();
                agentTerminalTools = List.of();
            }

            var allAllowed = Streams.concat(allowedToolNames.stream(), agentTerminalTools.stream())
                    .toList();
            var toolSpecs = tr.getTools(allAllowed);
            return new TurnPrompt(messages, toolSpecs, extraUserMessage);
        }

        private ToolExecutionResult executeTool(ToolExecutionRequest req) throws InterruptedException {
            agent.metrics.recordToolCall(req.name());
            wst.setContext(context);

            var result = tr.executeTool(req);
            if (agent.isWorkspaceTool(req, tr)) {
                agent.updateDroppedHistory(context, wst.getContext());
                context = wst.getContext();
            }
            return result;
        }

        @Tool("Signal that the initial workspace review is complete and all fragments are relevant.")
        @SuppressWarnings("UnusedMethod")
        public String performedInitialReview() {
            return "Initial review complete; workspace is well-curated.";
        }

        @Tool(
                "Signal that the Workspace now contains all the information necessary to accomplish the goal. Call this when you have finished gathering and pruning context.")
        @SuppressWarnings("UnusedMethod")
        public String workspaceComplete() {
            return "Workspace marked complete for the current goal.";
        }

        @Tool("Abort when you determine the question is not answerable from this codebase or is out of scope.")
        @SuppressWarnings("UnusedMethod")
        public String abortSearch(
                @P("Clear explanation of why the question cannot be answered from this codebase.") String explanation) {
            agent.terminalCompletionReported = true;
            agent.io.llmOutput(explanation, ChatMessageType.AI, LlmOutputMeta.DEFAULT);
            return explanation;
        }

        @Tool("Calls a remote tool using the MCP (Model Context Protocol).")
        @SuppressWarnings("UnusedMethod")
        public String callMcpTool(
                @P("The name of the tool to call. This must be one of the configured MCP tools.") String toolName,
                @P(
                                "A map of argument names to values for the tool. Can be null or empty if the tool takes no arguments.")
                        @Nullable
                        Map<String, Object> arguments) {
            Map<String, Object> args = Objects.requireNonNullElseGet(arguments, HashMap::new);
            var mcpToolOptional = agent.mcpTools.stream()
                    .filter(t -> t.toolName().equals(toolName))
                    .findFirst();

            if (mcpToolOptional.isEmpty()) {
                return "Error: MCP tool '" + toolName + "' not found in configuration.";
            }

            var server = mcpToolOptional.get().server();
            try {
                var result = McpUtils.callTool(
                        server, toolName, args, agent.cm.getProject().getRoot());
                return McpPrompts.mcpToolPreamble() + "\n\n" + "MCP tool '" + toolName + "' output:\n" + result;
            } catch (IOException | RuntimeException e) {
                return "Error calling MCP tool '" + toolName + "': " + e.getMessage();
            }
        }

        @Tool("Provide a final answer to a purely informational request. Use this when no code changes are required.")
        @SuppressWarnings("UnusedMethod")
        public String answer(
                @P(
                                "Comprehensive explanation that answers the query. Include relevant code snippets and how they relate, formatted in Markdown.")
                        String explanation) {
            agent.terminalCompletionReported = true;
            agent.io.llmOutput("# Answer\n\n" + explanation, ChatMessageType.AI, LlmOutputMeta.newMessage());
            return explanation;
        }

        @Tool(
                "Ask the human for clarification when the goal is unclear or necessary information cannot be found. Outputs the provided question to the user and stops.")
        @SuppressWarnings("UnusedMethod")
        public String askForClarification(
                @P("A concise question or clarification request for the human user.") String queryForUser) {
            agent.terminalCompletionReported = true;
            agent.io.llmOutput(queryForUser, ChatMessageType.AI, LlmOutputMeta.newMessage());
            return queryForUser;
        }

        @Tool("Issue description final output. Create a high-quality issue description.")
        @SuppressWarnings("UnusedMethod")
        public String describeIssue(
                @P("Concise, specific issue title.") String title,
                @P("GitHub-flavored Markdown describing the problem and impact.") String body) {
            agent.terminalCompletionReported = true;
            var json = ai.brokk.util.Json.getMapper()
                    .createObjectNode()
                    .put("title", title)
                    .put("body", body)
                    .toString();
            agent.io.llmOutput(json, ChatMessageType.AI, LlmOutputMeta.newMessage());
            return json;
        }

        @Tool(
                "Invoke the Code Agent to implement the current goal in a single shot using your provided instructions. Provide complete, self-contained instructions; only the Workspace and your instructions are visible to the Code Agent.")
        @SuppressWarnings("UnusedMethod")
        public String callCodeAgent(
                @P("Detailed instructions for the CodeAgent, referencing the current project and Workspace.")
                        String instructions)
                throws InterruptedException, ToolRegistry.FatalLlmException {
            if (agent.scope == null) {
                throw new ToolRegistry.FatalLlmException("Cannot call Code Agent without a valid Task Scope.");
            }

            var searchResult = agent.createResult("Search: " + agent.goal, agent.goal, context);
            context = agent.scope.append(searchResult);

            logger.debug("SearchAgent.callCodeAgent invoked with instructions: {}", instructions);

            var architect = new ArchitectAgent(
                    agent.cm,
                    agent.cm.getService().getModel(ModelType.ARCHITECT),
                    agent.cm.getCodeModel(),
                    instructions,
                    agent.scope,
                    context);

            var result = architect.execute();
            var stopDetails = result.stopDetails();
            var reason = stopDetails.reason();
            context = agent.scope.append(result);

            if (reason == TaskResult.StopReason.SUCCESS) {
                new GitWorkflow(agent.cm).performAutoCommit(instructions);
                logger.debug("SearchAgent.callCodeAgent finished successfully");
                return "CodeAgent finished with a successful build!";
            }

            if (reason == TaskResult.StopReason.INTERRUPTED) {
                throw new InterruptedException();
            }
            if (reason == TaskResult.StopReason.LLM_ERROR) {
                agent.io.llmOutput(
                        "# Code Agent\n\nFatal LLM error during CodeAgent execution.",
                        ChatMessageType.AI,
                        LlmOutputMeta.newMessage());
                logger.error("Fatal LLM error during CodeAgent execution: {}", stopDetails.explanation());
                throw new ToolRegistry.FatalLlmException(stopDetails.explanation());
            }
            throw new ToolRegistry.ToolCallException(
                    ToolExecutionResult.Status.INTERNAL_ERROR, stopDetails.explanation());
        }
    }

    private TaskResult createResult(String action, String goal, Context context) {
        return createResult(
                action, goal, taskMeta(), context, new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS));
    }

    private TaskResult createResult(String action, String goal, TaskResult.TaskMeta meta, Context context) {
        return createResult(action, goal, meta, context, new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS));
    }

    private TaskResult createResult(
            String action, String goal, TaskResult.TaskMeta meta, Context context, TaskResult.StopDetails stopDetails) {
        List<ChatMessage> finalMessages = new ArrayList<>(io.getLlmRawMessages());
        var fragment = new ContextFragments.TaskFragment(cm, finalMessages, goal);

        recordFinalWorkspaceState(context);
        metrics.recordOutcome(stopDetails.reason(), workspaceFiles(context).size());

        return new TaskResult(action, fragment, context, stopDetails, meta);
    }

    private TaskResult errorResult(TaskResult.StopDetails details) {
        return errorResult(details, null, currentState.context());
    }

    private TaskResult errorResult(
            TaskResult.StopDetails details, @Nullable TaskResult.TaskMeta meta, Context context) {
        List<ChatMessage> finalMessages = new ArrayList<>(io.getLlmRawMessages());
        String action = "Search: " + goal + " [" + details.reason().name() + "]";
        var fragment = new ContextFragments.TaskFragment(cm, finalMessages, goal);

        recordFinalWorkspaceState(context);
        metrics.recordOutcome(details.reason(), workspaceFiles(context).size());

        return new TaskResult(action, fragment, context, details, meta);
    }

    private void recordFinalWorkspaceState(Context context) {
        metrics.recordFinalWorkspaceFiles(toRelativePaths(workspaceFiles(context)));
        metrics.recordFinalWorkspaceFragments(getWorkspaceFragments(context));
    }

    private Set<ProjectFile> workspaceFiles(Context context) {
        try {
            context.awaitContentsAreComputed(ContextHistory.SNAPSHOT_AWAIT_TIMEOUT);
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for contexts to be computed", e);
        }
        return context.allFragments()
                .filter(f1 -> f1.getType().includeInProjectGuide())
                .flatMap(f -> f.files().renderNowOr(Set.of()).stream())
                .collect(Collectors.toSet());
    }

    private Set<String> toRelativePaths(Set<ProjectFile> files) {
        return files.stream().map(pf -> pf.getRelPath().toString()).collect(Collectors.toSet());
    }

    private List<SearchMetrics.FragmentInfo> getWorkspaceFragments(Context context) {
        try {
            context.awaitContentsAreComputed(ContextHistory.SNAPSHOT_AWAIT_TIMEOUT);
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for contexts to be computed", e);
        }
        return context.allFragments()
                .filter(f1 -> f1.getType().includeInProjectGuide())
                .map(f -> new SearchMetrics.FragmentInfo(
                        f.getType().toString(),
                        f.id(),
                        f.description().renderNowOr("(incomplete)"),
                        f.files().renderNowOr(Set.of()).stream()
                                .map(pf -> pf.getRelPath().toString())
                                .sorted()
                                .toList()))
                .toList();
    }

    boolean hasDroppableFragments(Context context) {
        return context.allFragments().anyMatch(f -> !originalPinnedFragments.contains(f));
    }

    private boolean isPinnedBySystem(ContextFragment cf) {
        boolean isSpecialPin = cf instanceof ContextFragments.StringFragment sf
                && sf.specialType().map(st -> !st.droppable()).orElse(false);
        boolean isLearnedPin =
                droppedFragments.stream().filter(cf::hasSameSource).count() >= 2;
        boolean isOriginalPin = originalPinnedFragments.stream().anyMatch(cf::hasSameSource);
        return isSpecialPin || isOriginalPin || isLearnedPin;
    }

    Context resetPinsToOriginal(Context context) {
        Context current = context;
        for (ContextFragment f : current.allFragments().toList()) {
            boolean shouldBePinned = isPinnedBySystem(f);
            if (current.isPinned(f) != shouldBePinned) {
                current = current.withPinned(f, shouldBePinned);
            }
        }
        return current;
    }

    Context applyPinning(Context context, @Nullable Context lastTurnContext) {
        context = resetPinsToOriginal(context);

        double convergenceScore = calculateConvergenceScore(context, lastTurnContext);
        double cacheWeight = 1.0 - convergenceScore;

        List<ContextFragment> fragments = context.allFragments().toList();
        var fragmentsDelta = lastTurnContext == null
                ? context.allFragments().toList()
                : ContextDelta.between(lastTurnContext, context).join().addedFragments();

        int[] tokenCounts = new int[fragments.size()];
        for (int i = 0; i < fragments.size(); i++) {
            tokenCounts[i] =
                    Messages.getApproximateTokens(fragments.get(i).text().join());
        }

        long[] suffixSums = new long[fragments.size() + 1];
        for (int i = fragments.size() - 1; i >= 0; i--) {
            suffixSums[i] = suffixSums[i + 1] + tokenCounts[i];
        }

        logger.trace(
                "applyInequalityPinning: convergenceScore={}, cacheWeight={}, fragments={}, originalPinned={}",
                convergenceScore,
                cacheWeight,
                fragments.size(),
                originalPinnedFragments.size());

        Context next = context;
        for (int i = 0; i < fragments.size(); i++) {
            ContextFragment f = fragments.get(i);
            if (isPinnedBySystem(f)) {
                continue;
            }

            if (fragmentsDelta.contains(f)) {
                continue;
            }

            double freedTokens = 0.9 * tokenCounts[i];
            double lostTokens = suffixSums[i + 1];

            boolean pin = (cacheWeight * lostTokens) > freedTokens && (lostTokens - freedTokens) > 10_000;
            logger.trace(
                    "applyInequalityPinning: idx={}, id={}, desc='{}', freedTokens={}, lostTokens={}, pin={}",
                    i,
                    f.id(),
                    f.description().renderNowOr("(empty)"),
                    freedTokens,
                    lostTokens,
                    pin);
            if (pin) {
                next = next.withPinned(f, true);
            }
        }
        return next;
    }

    /**
     * Calculates a convergence score indicating how "settled" the workspace fragment set is.
     *
     * <p>The score is used to decide how aggressively to protect early workspace fragments
     * from being dropped, in order to preserve LLM prefix cache efficiency. Dropping an early
     * fragment invalidates the cached token prefix for all subsequent content, so we want to
     * avoid such drops while the search is still actively exploring.
     *
     * <p>The score combines two signals:
     * <ul>
     *   <li><b>Stability</b> (Jaccard similarity): {@code |intersection| / |union|}. High stability
     *       means the fragment set isn't changing much turn-to-turn.</li>
     *   <li><b>Novelty</b>: {@code |added| / |union|}. High novelty means we're still discovering
     *       new fragments (including deepening into already-known files).</li>
     * </ul>
     *
     * <p>The final score is {@code stability * (1 - novelty)}:
     * <ul>
     *   <li>Returns values near <b>1.0</b> when the workspace is stable and no new fragments are
     *       being added (converged state) — cache protection can be relaxed.</li>
     *   <li>Returns values near <b>0.0</b> when the workspace is churning or new fragments are
     *       being discovered (exploration state) — cache protection should be aggressive.</li>
     * </ul>
     *
     * <p>This score feeds into {@link #applyPinning(Context, Context)}, which temporarily pins
     * early fragments when {@code cacheWeight * lostTokens > freedTokens}, where
     * {@code cacheWeight = 1 - convergenceScore}.
     *
     * @param context the current turn context
     * @param lastTurnContext the context from the previous turn, or null if this is the first turn
     * @return a score in [0, 1] where higher values indicate greater convergence
     */
    double calculateConvergenceScore(Context context, @Nullable Context lastTurnContext) {
        if (!cm.getService().supportsPrefixCache(model)) {
            return 1.0;
        }
        if (lastTurnContext == null) {
            return 1.0;
        }

        var currIds = context.allFragments().map(ContextFragment::id).collect(Collectors.toSet());
        var prevIds = lastTurnContext.allFragments().map(ContextFragment::id).collect(Collectors.toSet());

        Set<String> intersection = new HashSet<>(prevIds);
        intersection.retainAll(currIds);

        Set<String> union = new HashSet<>(prevIds);
        union.addAll(currIds);

        if (union.isEmpty()) {
            return 1.0;
        }

        double stability = (double) intersection.size() / union.size();

        Set<String> noveltySet = new HashSet<>(currIds);
        noveltySet.removeAll(prevIds);
        double novelty = (double) noveltySet.size() / union.size();

        return stability * (1.0 - novelty);
    }

    private record PendingTerminal(ToolExecutionResult terminalExecution) {
        public String toolName() {
            return terminalExecution.toolName();
        }

        public String resultText() {
            return terminalExecution.resultText();
        }
    }

    private sealed interface TurnOutcome {
        record Continue(Context contextAfterTurn, List<ChatMessage> sessionMessagesAfterTurn) implements TurnOutcome {}

        record PendingTerminal(
                Context contextAfterTurn,
                List<ChatMessage> sessionMessagesAfterTurn,
                SearchAgent.PendingTerminal pendingTerminal)
                implements TurnOutcome {}

        record Final(TaskResult result) implements TurnOutcome {}

        enum Overflow implements TurnOutcome {
            INSTANCE
        }

        enum AutoScan implements TurnOutcome {
            INSTANCE
        }
    }

    private record TurnPrompt(
            List<ChatMessage> messages, List<ToolSpecification> toolSpecs, @Nullable UserMessage extraUserMessage) {}

    private TaskResult finalizePendingTerminal(PendingTerminal pendingTerminal, Context context) {
        if ("abortSearch".equals(pendingTerminal.toolName())) {
            return errorResult(
                    new TaskResult.StopDetails(
                            TaskResult.StopReason.LLM_ABORTED, "Aborted: " + pendingTerminal.resultText()),
                    taskMeta(),
                    context);
        }
        if ("describeIssue".equals(pendingTerminal.toolName())) {
            return createResult(
                    pendingTerminal.toolName(),
                    goal,
                    taskMeta(),
                    context,
                    new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS, pendingTerminal.resultText()));
        }
        return createResult(pendingTerminal.toolName(), goal, context);
    }

    private boolean shouldSummarize(String toolName) {
        return Set.of(
                        "getSymbolLocations",
                        "searchSymbols",
                        "scanUsages",
                        "searchSubstrings",
                        "searchFilenames",
                        "searchGitCommitMessages",
                        "getGitLog")
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
        return (sr.error() != null) ? rawResult : sr.text();
    }

    private static Map<String, Object> getArgumentsMap(ToolExecutionRequest request) {
        try {
            return new ObjectMapper().readValue(request.arguments(), new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            logger.error("Error parsing request args for {}: {}", request.name(), e.getMessage());
            return Map.of();
        }
    }

    private TaskResult.TaskMeta taskMeta() {
        return new TaskResult.TaskMeta(TaskResult.Type.SEARCH, Service.ModelConfig.from(model, cm.getService()));
    }

    void reportComplete(TaskResult.StopReason reason, String message) {
        logger.debug("SearchAgent completed: {}: {}", reason, message);
        var badge = StatusBadge.badgeFor(reason);
        io.llmOutput(
                "\n## Search Agent Finished\n" + badge + "\n\n**Reason:** " + message,
                ChatMessageType.CUSTOM,
                LlmOutputMeta.DEFAULT);
    }
}
