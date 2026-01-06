package ai.brokk.agents;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.Service;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.ContextHistory;
import ai.brokk.mcp.McpUtils;
import ai.brokk.metrics.SearchMetrics;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.prompts.McpPrompts;
import ai.brokk.prompts.SearchPrompts;
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
 * SearchAgent: - Uses tools to curate Workspace context for follow-on coding. - Starts by
 * calling ContextAgent to add recommended fragments to the Workspace. - Adds every learning step to Context history (no
 * hidden state). - Summarizes very large tool outputs before recording them. - Enters "beast mode" to finalize with
 * existing info if interrupted or context is near full. - Never writes code or answers questions itself; it prepares
 * the Workspace for a later Code Agent run.
 */
public class SearchAgent {
    protected static final Logger logger = LogManager.getLogger(SearchAgent.class);

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

    protected static final int SUMMARIZE_THRESHOLD = 1_000;

    protected final IContextManager cm;
    protected final StreamingChatModel model;
    protected final ContextManager.TaskScope scope;
    protected final Llm llm;
    protected final Llm summarizer;
    protected final IConsoleIO io;
    protected final String goal;
    protected final List<McpPrompts.McpTool> mcpTools;
    protected final SearchMetrics metrics;
    protected final ScanConfig scanConfig;
    protected boolean scanPerformed;
    protected boolean contextPruned;

    // Local working context snapshot for this agent
    protected Context context;

    // Session-local conversation for this agent
    protected final List<ChatMessage> sessionMessages = new ArrayList<>();

    // State toggles
    protected boolean beastMode;

    public SearchAgent(
            Context initialContext,
            String goal,
            StreamingChatModel model,
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
        return (int) messages.stream()
                .filter(msg -> msg.type() == ChatMessageType.AI)
                .count();
    }

    private boolean shouldAutomaticallyScan() {
        return scanConfig.autoScan() && !scanPerformed;
    }

    protected TaskResult executeInternal() throws InterruptedException {
        var wst = new WorkspaceTools(context);
        var tr = createToolRegistry(wst);

        pruneContext();
        if (shouldAutomaticallyScan() && context.isFileContentEmpty()) {
            performAutoScan();
        }

        while (true) {
            wst.setContext(context);

            var promptResult =
                    SearchPrompts.instance.buildPrompt(context, model, goal, getObjective(), mcpTools, sessionMessages);
            var messages = promptResult.messages();

            if (!beastMode && promptResult.engageBeastMode()) {
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "Workspace is near the context limit; attempting finalization based on current knowledge");
                beastMode = true;
            }

            var allowedToolNames = calculateAllowedToolNames();
            var agentTerminalTools = calculateTerminalTools();

            var allAllowed = new ArrayList<String>(allowedToolNames.size() + agentTerminalTools.size());
            allAllowed.addAll(allowedToolNames);
            allAllowed.addAll(agentTerminalTools);
            var toolSpecs = tr.getTools(allAllowed);

            metrics.startTurn();
            long turnStartTime = System.currentTimeMillis();

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

            var ai = ToolRegistry.removeDuplicateToolRequests(result.aiMessage());
            if (shouldAutomaticallyScan()
                    && ai.toolExecutionRequests().stream().anyMatch(req -> isSearchTool(req.name()))) {
                performAutoScan();
                continue;
            }

            sessionMessages.add(new UserMessage("What tools do you want to use next?"));
            sessionMessages.add(result.aiMessage());

            if (!ai.hasToolExecutionRequests()) {
                return errorResult(
                        new TaskResult.StopDetails(
                                TaskResult.StopReason.TOOL_ERROR, "No tool requests found in LLM response."),
                        taskMeta());
            }

            Set<ProjectFile> filesBeforeSet = getWorkspaceFileSet();
            boolean executedResearch = false;
            Context contextAtTurnStart = context;
            try {
                var sortedNonterminalCalls = ai.toolExecutionRequests().stream()
                        .filter(req -> categorizeTool(req.name()) != ToolCategory.TERMINAL)
                        .sorted(Comparator.comparingInt(this::priority))
                        .toList();

                for (var req : sortedNonterminalCalls) {
                    ToolExecutionResult toolResult = executeTool(req, tr, wst);
                    if (toolResult.status() == ToolExecutionResult.Status.FATAL) {
                        var details =
                                new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, toolResult.resultText());
                        return errorResult(details, taskMeta());
                    }

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

                    sessionMessages.add(finalResult.toExecutionResultMessage());
                    if (categorizeTool(req.name()) == ToolCategory.RESEARCH) {
                        if (!isWorkspaceTool(req, tr)) {
                            executedResearch = true;
                        }
                    }
                }

                var terminal = ai.toolExecutionRequests().stream()
                        .filter(req -> categorizeTool(req.name()) == ToolCategory.TERMINAL)
                        .min(Comparator.comparingInt(this::priority));

                if (terminal.isPresent() && context.equals(contextAtTurnStart) && !executedResearch) {
                    var termReq = terminal.get();
                    var termExec = executeTool(termReq, tr, wst);
                    sessionMessages.add(termExec.toExecutionResultMessage());

                    if (termExec.status() != ToolExecutionResult.Status.SUCCESS) {
                        return errorResult(
                                new TaskResult.StopDetails(
                                        TaskResult.StopReason.TOOL_ERROR,
                                        "Terminal tool '" + termReq.name() + "' failed: " + termExec.resultText()),
                                taskMeta());
                    }

                    if (termReq.name().equals("abortSearch")) {
                        return errorResult(
                                new TaskResult.StopDetails(
                                        TaskResult.StopReason.LLM_ABORTED, "Aborted: " + termExec.resultText()),
                                taskMeta());
                    } else {
                        return createResult(termReq.name(), goal);
                    }
                }

                Set<ProjectFile> filesAfterSet = getWorkspaceFileSet();
                Set<ProjectFile> added = new HashSet<>(filesAfterSet);
                added.removeAll(filesBeforeSet);
                if (!added.isEmpty()) {
                    scope.publish(context);
                }
            } finally {
                endTurnAndRecordFileChanges(filesBeforeSet);
            }
        }
    }

    protected ToolRegistry createToolRegistry(WorkspaceTools wst) {
        return cm.getToolRegistry().builder().register(wst).register(this).build();
    }

    protected List<String> calculateTerminalTools() {
        var terminals = new ArrayList<String>();
        terminals.add("workspaceComplete");
        terminals.add("abortSearch");
        return terminals;
    }

    protected void endTurnAndRecordFileChanges(Set<ProjectFile> filesBeforeSet) {
        Set<ProjectFile> filesAfterSet = getWorkspaceFileSet();
        Set<ProjectFile> added = new HashSet<>(filesAfterSet);
        added.removeAll(filesBeforeSet);
        metrics.recordFilesAdded(added.size());
        metrics.recordFilesAddedPaths(toRelativePaths(added));
        metrics.endTurn(toRelativePaths(filesBeforeSet), toRelativePaths(filesAfterSet));
    }

    protected ToolExecutionResult executeTool(ToolExecutionRequest req, ToolRegistry registry, WorkspaceTools wst)
            throws InterruptedException {
        metrics.recordToolCall(req.name());
        wst.setContext(context);
        var result = registry.executeTool(req);
        if (isWorkspaceTool(req, registry)) {
            context = wst.getContext();
        }
        return result;
    }

    protected List<String> calculateAllowedToolNames() {
        if (beastMode) {
            return List.of();
        }

        var names = new ArrayList<String>();
        if (!cm.getProject().getAnalyzerLanguages().equals(Set.of(Languages.NONE))) {
            names.add("searchSymbols");
            names.add("getSymbolLocations");
        }

        names.add("getClassSkeletons");
        names.add("getClassSources");
        names.add("getMethodSources");
        names.add("getUsages");
        names.add("searchSubstrings");
        names.add("searchGitCommitMessages");
        names.add("searchFilenames");
        names.add("getFileContents");
        names.add("getFileSummaries");
        names.add("skimDirectory");
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

        if (!mcpTools.isEmpty()) {
            names.add("callMcpTool");
        }

        return names;
    }

    protected enum ToolCategory {
        TERMINAL,
        WORKSPACE_HYGIENE,
        RESEARCH
    }

    protected ToolCategory categorizeTool(String toolName) {
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

    protected boolean isWorkspaceTool(dev.langchain4j.agent.tool.ToolExecutionRequest request, ToolRegistry tr) {
        try {
            var vi = tr.validateTool(request);
            return vi.instance() instanceof WorkspaceTools;
        } catch (ToolRegistry.ToolValidationException e) {
            return false;
        }
    }

    protected int priority(String toolName) {
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
            case "answer", "askForClarification", "workspaceComplete" -> 101;
            case "abortSearch" -> 200;
            default -> 9;
        };
    }

    protected int priority(ToolExecutionRequest req) {
        return priority(req.name());
    }

    public Context pruneContext() throws InterruptedException {
        if (contextPruned || context.isEmpty()) {
            return context;
        }

        var scanModel = getScanModel();
        var wst = new WorkspaceTools(context);
        var tr = cm.getToolRegistry().builder().register(wst).register(this).build();

        var messages = SearchPrompts.instance.buildPruningPrompt(context, goal);
        var toolNames = new ArrayList<String>();
        toolNames.add("performedInitialReview");
        if (hasDroppableFragments()) {
            toolNames.add("dropWorkspaceFragments");
        }
        var toolSpecs = tr.getTools(toolNames);

        io.llmOutput("\n**Brokk** performing initial workspace review…", ChatMessageType.AI, true, false);
        var janitorOpts = new Llm.Options(scanModel, "Janitor: " + goal).withEcho();
        var jLlm = cm.getLlm(janitorOpts);
        jLlm.setOutput(this.io);
        var result = jLlm.sendRequest(messages, new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr));
        if (result.error() != null || result.isEmpty()) {
            return context;
        }

        var ai = ToolRegistry.removeDuplicateToolRequests(result.aiMessage());
        for (var req : ai.toolExecutionRequests()) {
            executeTool(req, tr, wst);
        }

        contextPruned = true;
        return context;
    }

    protected void performAutoScan() throws InterruptedException {
        scanContext();
        scanPerformed = true;
    }

    protected SearchPrompts.Objective getObjective() {
        return SearchPrompts.Objective.WORKSPACE_ONLY;
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
                addToWorkspace(recommendation);
                io.llmOutput(
                        "\n\n**Brokk Context Engine** complete — contextual insights added to Workspace.\n",
                        ChatMessageType.AI);
            }
        } else {
            io.llmOutput("\n\nNo additional context insights found\n", ChatMessageType.AI);
        }

        Set<ProjectFile> filesAfterScan = getWorkspaceFileSet();
        Set<ProjectFile> filesAdded = new HashSet<>(filesAfterScan);
        filesAdded.removeAll(filesBeforeScan);
        metrics.recordContextScan(filesAdded.size(), false, toRelativePaths(filesAdded), md);

        var contextAgentResult = createResult("Brokk Context Agent: " + goal, goal, meta);
        context = scanConfig.appendToScope() ? scope.append(contextAgentResult) : contextAgentResult.context();

        return context;
    }

    protected StreamingChatModel getScanModel() {
        return scanConfig.scanModel() == null ? cm.getService().getScanModel() : scanConfig.scanModel();
    }

    protected boolean isSearchTool(String toolName) {
        return toolName.startsWith("search");
    }

    @Blocking
    public void addToWorkspace(ContextAgent.RecommendationResult recommendationResult) {
        List<ContextFragment> selected = recommendationResult.fragments();
        var groupedByType = selected.stream().collect(Collectors.groupingBy(ContextFragment::getType));

        var pathFragments = groupedByType.getOrDefault(ContextFragment.FragmentType.PROJECT_PATH, List.of()).stream()
                .map(ContextFragments.ProjectPathFragment.class::cast)
                .toList();
        if (!pathFragments.isEmpty()) {
            context = context.addFragments(pathFragments);
        }

        var skeletonFragments = groupedByType.getOrDefault(ContextFragment.FragmentType.SKELETON, List.of()).stream()
                .map(ContextFragments.SummaryFragment.class::cast)
                .toList();
        if (!skeletonFragments.isEmpty()) {
            context = context.addFragments(skeletonFragments);
        }

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

    @Tool("Signal that the initial workspace review is complete and all fragments are relevant.")
    public String performedInitialReview() {
        return "Initial review complete; workspace is well-curated.";
    }

    @Tool(
            "Signal that the Workspace now contains all the information necessary to accomplish the goal. Call this when you have finished gathering and pruning context.")
    public String workspaceComplete() {
        return "Workspace marked complete for the current goal.";
    }

    @Tool("Abort when you determine the question is not answerable from this codebase or is out of scope.")
    public String abortSearch(
            @P("Clear explanation of why the question cannot be answered from this codebase.") String explanation) {
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
            return "Error: MCP tool '" + toolName + "' not found in configuration.";
        }

        var server = mcpToolOptional.get().server();
        try {
            var result =
                    McpUtils.callTool(server, toolName, args, cm.getProject().getRoot());
            return McpPrompts.mcpToolPreamble() + "\n\n" + "MCP tool '" + toolName + "' output:\n" + result;
        } catch (IOException | RuntimeException e) {
            return "Error calling MCP tool '" + toolName + "': " + e.getMessage();
        }
    }

    protected TaskResult createResult(String action, String goal) {
        return createResult(action, goal, taskMeta());
    }

    protected TaskResult createResult(String action, String goal, TaskResult.TaskMeta meta) {
        List<ChatMessage> finalMessages = new ArrayList<>(io.getLlmRawMessages());
        var stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
        var fragment = new ContextFragments.TaskFragment(cm, finalMessages, goal);

        recordFinalWorkspaceState();
        metrics.recordOutcome(stopDetails.reason(), getWorkspaceFileSet().size());

        return new TaskResult(action, fragment, context, stopDetails, meta);
    }

    protected TaskResult errorResult(TaskResult.StopDetails details) {
        return errorResult(details, null);
    }

    protected TaskResult errorResult(TaskResult.StopDetails details, @Nullable TaskResult.TaskMeta meta) {
        List<ChatMessage> finalMessages = new ArrayList<>(io.getLlmRawMessages());
        String action = "Search: " + goal + " [" + details.reason().name() + "]";
        var fragment = new ContextFragments.TaskFragment(cm, finalMessages, goal);

        recordFinalWorkspaceState();
        metrics.recordOutcome(details.reason(), getWorkspaceFileSet().size());

        return new TaskResult(action, fragment, context, details, meta);
    }

    protected void recordFinalWorkspaceState() {
        metrics.recordFinalWorkspaceFiles(toRelativePaths(getWorkspaceFileSet()));
        metrics.recordFinalWorkspaceFragments(getWorkspaceFragments());
    }

    private static boolean isWorkspaceFileFragment(ContextFragment f) {
        return f.getType() == ContextFragment.FragmentType.PROJECT_PATH
                || f.getType() == ContextFragment.FragmentType.SKELETON;
    }

    protected Set<ProjectFile> getWorkspaceFileSet() {
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

    protected Set<String> toRelativePaths(Set<ProjectFile> files) {
        return files.stream().map(pf -> pf.getRelPath().toString()).collect(Collectors.toSet());
    }

    protected List<SearchMetrics.FragmentInfo> getWorkspaceFragments() {
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

    protected boolean hasDroppableFragments() {
        return context.allFragments().anyMatch(f -> !context.isPinned(f));
    }

    protected boolean shouldSummarize(String toolName) {
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

    protected String summarizeResult(
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

    protected static Map<String, Object> getArgumentsMap(ToolExecutionRequest request) {
        try {
            return new ObjectMapper().readValue(request.arguments(), new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            logger.error("Error parsing request args for {}: {}", request.name(), e.getMessage());
            return Map.of();
        }
    }

    protected TaskResult.TaskMeta taskMeta() {
        return new TaskResult.TaskMeta(TaskResult.Type.SEARCH, Service.ModelConfig.from(model, cm.getService()));
    }
}
