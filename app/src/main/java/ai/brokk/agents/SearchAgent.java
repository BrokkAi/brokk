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
import ai.brokk.mcp.McpUtils;
import ai.brokk.metrics.SearchMetrics;
import ai.brokk.project.IProject;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.prompts.McpPrompts;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.tools.ExplanationRenderer;
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
 *   Workspace is under the limit (or, if nothing is droppable, only terminal tools are offered to end the search).
 * - Never writes code or answers questions itself; it prepares the Workspace for a later Code Agent run.
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
    protected final ContextManager.@Nullable TaskScope scope;
    protected final Llm llm;
    protected final Llm summarizer;
    protected final IConsoleIO io;
    protected final String goal;
    protected final List<McpPrompts.McpTool> mcpTools;
    protected final List<String> staticTools;
    protected final SearchMetrics metrics;
    protected final ScanConfig scanConfig;
    protected boolean scanPerformed;
    protected boolean contextPruned;

    // Local working context snapshot for this agent
    protected Context context;

    protected record SearchState(Context context, List<ChatMessage> sessionMessages) {}

    // Session-local conversation for this agent
    protected final List<ChatMessage> sessionMessages = new ArrayList<>();

    // State toggles
    protected boolean dropOnlyMode;

    protected final Set<ContextFragment> originalPinnedFragments;
    protected final List<ContextFragment> droppedFragments = new ArrayList<>();
    protected @Nullable SearchState checkpoint;
    protected @Nullable Context lastTurnContext;

    @FunctionalInterface
    public interface TurnListener {
        void turnFinished();
    }

    private @Nullable TurnListener turnListener;

    public void setTurnListener(@Nullable TurnListener listener) {
        this.turnListener = listener;
    }

    public SearchAgent(
            Context initialContext,
            String goal,
            StreamingChatModel model,
            ContextManager.TaskScope scope,
            IConsoleIO io,
            ScanConfig scanConfig) {
        this(initialContext, goal, model, scope, io, scanConfig, null);
    }

    public SearchAgent(
            Context initialContext,
            String goal,
            StreamingChatModel model,
            ContextManager.TaskScope scope,
            IConsoleIO io,
            ScanConfig scanConfig,
            @Nullable List<String> staticTools) {
        this.goal = goal;
        this.cm = initialContext.getContextManager();
        this.model = model;
        this.scope = scope;

        this.io = io;
        var llmOptions = new Llm.Options(model, "Search: " + goal, TaskResult.Type.SEARCH).withEcho();
        this.llm = cm.getLlm(llmOptions);
        this.llm.setOutput(this.io);

        var summarizeModel = cm.getService().getModel(ModelType.SCAN);
        this.summarizer = cm.getLlm(summarizeModel, "Summarizer: " + goal, TaskResult.Type.SUMMARIZE);

        this.dropOnlyMode = false;
        this.metrics = "true".equalsIgnoreCase(System.getenv("BRK_COLLECT_METRICS"))
                ? SearchMetrics.tracking()
                : SearchMetrics.noOp();

        this.mcpTools = initMcpTools(cm.getProject());
        this.context = initialContext;
        this.originalPinnedFragments = initialContext.getPinnedFragments().collect(Collectors.toSet());
        this.scanConfig = scanConfig;
        this.staticTools = initStaticTools(staticTools, cm.getProject(), mcpTools);
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

    private static List<String> initStaticTools(
            @Nullable List<String> explicitTools, IProject project, List<McpPrompts.McpTool> mcpTools) {
        if (explicitTools != null) {
            return WorkspaceTools.filterByAnalyzerAvailability(explicitTools, project);
        }

        var tools = new ArrayList<String>();

        // Search-specific analyzer tools
        tools.add("searchSymbols");
        tools.add("getSymbolLocations");
        tools.add("skimDirectory");

        // Workspace analyzer tools
        tools.add("addSymbolUsagesToWorkspace");
        tools.add("addClassesToWorkspace");
        tools.add("addClassSummariesToWorkspace");
        tools.add("addMethodsToWorkspace");
        tools.add("addFileSummariesToWorkspace");

        // Non-analyzer tools
        tools.add("searchSubstrings");
        tools.add("searchGitCommitMessages");
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
        try {
            var tr = executeInternal();
            if (metrics instanceof SearchMetrics.Tracking) {
                var json = metrics.toJson(goal, tr.stopDetails().reason() == TaskResult.StopReason.SUCCESS);
                System.err.println("\nBRK_SEARCHAGENT_METRICS=" + json);
            }
            return tr;
        } catch (InterruptedException e) {
            logger.debug("Search interrupted", e);
            return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED));
        }
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

        @Nullable PendingTerminal pendingTerminal = null;
        while (true) {
            var outcome = executeTurn(tr, wst, pendingTerminal);
            if (outcome instanceof TurnOutcome.Final f) {
                return f.result();
            }
            if (outcome instanceof TurnOutcome.Continue c) {
                pendingTerminal = c.pendingTerminal();
            } else {
                throw new AssertionError("Unhandled TurnOutcome: " + outcome);
            }
        }
    }

    protected ToolRegistry createToolRegistry(WorkspaceTools wst) {
        return cm.getToolRegistry().builder().register(wst).register(this).build();
    }

    protected List<String> calculateTerminalTools() {
        if (dropOnlyMode && hasDroppableFragments()) {
            return List.of();
        }
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
            updateDroppedHistory(context, wst.getContext());
            context = wst.getContext();
        }
        return result;
    }

    private void updateDroppedHistory(Context oldContext, Context newContext) {
        if (oldContext.equals(newContext)) return;

        ContextDelta delta = ContextDelta.between(oldContext, newContext).join();
        droppedFragments.addAll(delta.removedFragments());
    }

    protected List<String> calculateAllowedToolNames() {
        if (dropOnlyMode) {
            return hasDroppableFragments() ? List.of("dropWorkspaceFragments") : List.of();
        }

        var names = new ArrayList<String>(staticTools);
        if (hasDroppableFragments()) {
            names.add("dropWorkspaceFragments");
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
            case "dropWorkspaceFragments" -> ToolCategory.WORKSPACE_HYGIENE;
            default -> ToolCategory.RESEARCH;
        };
    }

    protected boolean isWorkspaceTool(ToolExecutionRequest request, ToolRegistry tr) {
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
        if (contextPruned || !hasDroppableFragments()) {
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

        io.llmOutput(
                "\n**Brokk** performing initial workspace review…", ChatMessageType.AI, LlmOutputMeta.newMessage());
        var janitorOpts = new Llm.Options(scanModel, "Janitor: " + goal, TaskResult.Type.SEARCH).withEcho();
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
                addToWorkspace(recommendation);
                io.llmOutput(
                        "\n\n**Brokk Context Engine** complete — contextual insights added to Workspace.\n",
                        ChatMessageType.AI,
                        LlmOutputMeta.DEFAULT);
            }
        } else {
            io.llmOutput("\n\nNo additional context insights found\n", ChatMessageType.AI, LlmOutputMeta.DEFAULT);
        }

        Set<ProjectFile> filesAfterScan = getWorkspaceFileSet();
        Set<ProjectFile> filesAdded = new HashSet<>(filesAfterScan);
        filesAdded.removeAll(filesBeforeScan);
        metrics.recordContextScan(filesAdded.size(), false, toRelativePaths(filesAdded), md);

        var contextAgentResult = createResult("Brokk Context Agent: " + goal, goal, meta);
        context = (scanConfig.appendToScope() && scope != null)
                ? scope.append(contextAgentResult)
                : contextAgentResult.context();

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
        context = context.addFragments(recommendationResult.fragments());
        emitContextAddedExplanation(recommendationResult.fragments());
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
        io.llmOutput(explanation, ChatMessageType.AI, LlmOutputMeta.DEFAULT);
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

    protected static Set<ProjectFile> workspaceFiles(Context context) {
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

    protected Set<ProjectFile> getWorkspaceFileSet() {
        return workspaceFiles(context);
    }

    protected Set<String> toRelativePaths(Set<ProjectFile> files) {
        return files.stream().map(pf -> pf.getRelPath().toString()).collect(Collectors.toSet());
    }

    protected List<SearchMetrics.FragmentInfo> getWorkspaceFragments() {
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
                        f.description().renderNowOr("(empty)"),
                        f.files().renderNowOr(Set.of()).stream()
                                .map(pf -> pf.getRelPath().toString())
                                .sorted()
                                .toList()))
                .toList();
    }

    protected boolean hasDroppableFragments() {
        return context.allFragments().anyMatch(f -> !originalPinnedFragments.contains(f));
    }

    private boolean isPinnedBySystem(ContextFragment cf) {
        boolean isLearnedPin =
                droppedFragments.stream().filter(cf::hasSameSource).count() >= 2;
        return originalPinnedFragments.contains(cf) || isLearnedPin;
    }

    void resetPinsToOriginal() {
        Context current = context;
        for (ContextFragment f : current.allFragments().toList()) {
            boolean shouldBePinned = isPinnedBySystem(f);
            if (current.isPinned(f) != shouldBePinned) {
                current = current.withPinned(f, shouldBePinned);
            }
        }
        context = current;
    }

    void applyPinning() {
        resetPinsToOriginal();
        double convergenceScore = calculateConvergenceScore();
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

        if (logger.isDebugEnabled()) {
            logger.debug(
                    "applyInequalityPinning: convergenceScore={}, cacheWeight={}, fragments={}, originalPinned={}",
                    convergenceScore,
                    cacheWeight,
                    fragments.size(),
                    originalPinnedFragments.size());
        }

        Context next = context;
        for (int i = 0; i < fragments.size(); i++) {
            ContextFragment f = fragments.get(i);
            if (isPinnedBySystem(f)) {
                continue;
            }

            // Optimization 1: Fragments added in the previous turn should never be pinned
            if (fragmentsDelta.contains(f)) {
                continue;
            }

            double freedTokens = 0.9 * tokenCounts[i]; // hardcoding prefix cache cost = 10% of uncached token cost
            double lostTokens = suffixSums[i + 1];

            // Optimization 2: Only pin if the delta (lost - freed) is significant (> 10,000 tokens)
            boolean pin = (cacheWeight * lostTokens) > freedTokens && (lostTokens - freedTokens) > 10_000;

            if (logger.isDebugEnabled()) {
                logger.debug(
                        "applyInequalityPinning: idx={}, id={}, desc='{}', freedTokens={}, lostTokens={}, pin={}",
                        i,
                        f.id(),
                        f.description().renderNowOr("(empty)"),
                        freedTokens,
                        lostTokens,
                        pin);
            }

            if (pin) {
                next = next.withPinned(f, true);
            }
        }
        context = next;
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
     * <p>This score feeds into {@link #applyPinning(double)}, which temporarily pins
     * early fragments when {@code cacheWeight * lostTokens > freedTokens}, where
     * {@code cacheWeight = 1 - convergenceScore}.
     *
     * @param prevIds the workspace fragment IDs from the previous turn, or null if this is the first turn
     * @param currIds the workspace fragment IDs from the current turn
     * @return a score in [0, 1] where higher values indicate greater convergence
     */
    double calculateConvergenceScore() {
        if (lastTurnContext == null) {
            return 1.0;
        }

        var currIds = context.allFragments().map(ContextFragment::id).collect(Collectors.toSet());
        var prevIds = lastTurnContext.allFragments().map(ContextFragment::id).collect(Collectors.toSet());

        Set<String> intersection = new HashSet<>(prevIds);
        intersection.retainAll(currIds);

        Set<String> union = new HashSet<>(prevIds);
        union.addAll(currIds);

        if (union.isEmpty()) return 1.0;

        double stability = (double) intersection.size() / union.size();

        Set<String> noveltySet = new HashSet<>(currIds);
        noveltySet.removeAll(prevIds);
        double novelty = (double) noveltySet.size() / union.size();

        return stability * (1.0 - novelty);
    }

    private enum TurnMode {
        NORMAL,
        POST_TERMINAL_CLEANUP
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
        record Continue(@Nullable PendingTerminal pendingTerminal) implements TurnOutcome {}

        record Final(TaskResult result) implements TurnOutcome {}
    }

    private record TurnPrompt(
            List<ChatMessage> messages, List<ToolSpecification> toolSpecs, @Nullable UserMessage extraUserMessage) {}

    private TurnPrompt preparePrompt(ToolRegistry tr, WorkspaceTools wst, TurnMode mode) throws InterruptedException {
        wst.setContext(context);

        if (mode == TurnMode.NORMAL) {
            applyPinning();
        } else {
            resetPinsToOriginal();
        }

        var promptResult = SearchPrompts.instance.buildPrompt(
                context, model, taskMeta(), goal, getObjective(), mcpTools, sessionMessages);
        List<ChatMessage> messages = promptResult.messages();

        if (mode == TurnMode.NORMAL && dropOnlyMode) {
            resetPinsToOriginal();
            messages = new ArrayList<>(messages);
            if (hasDroppableFragments()) {
                messages.add(
                        new UserMessage(
                                "The Workspace has exceeded the context limit. You MUST use 'dropWorkspaceFragments' to remove irrelevant or redundant fragments before you can continue."));
            } else {
                messages.add(
                        new UserMessage(
                                "The Workspace exceeded the context limit and was restored to the last successful checkpoint, but there are no droppable fragments remaining. You may only use terminal tools to finish (e.g., 'workspaceComplete') or stop ('abortSearch')."));
            }
        }

        @Nullable UserMessage extraUserMessage = null;
        List<String> allowedToolNames;
        List<String> agentTerminalTools;

        if (mode == TurnMode.POST_TERMINAL_CLEANUP) {
            messages = new ArrayList<>(messages);
            extraUserMessage = new UserMessage(
                    "Search is complete. Please perform a final cleanup of the Workspace using 'dropWorkspaceFragments' to remove any remaining irrelevant information.");
            messages.add(extraUserMessage);

            allowedToolNames = hasDroppableFragments() ? List.of("dropWorkspaceFragments") : List.of();
            agentTerminalTools = List.of();
        } else {
            allowedToolNames = calculateAllowedToolNames();
            agentTerminalTools = calculateTerminalTools();
        }

        var allAllowed = new ArrayList<String>(allowedToolNames.size() + agentTerminalTools.size());
        allAllowed.addAll(allowedToolNames);
        allAllowed.addAll(agentTerminalTools);

        var toolSpecs = tr.getTools(allAllowed);
        return new TurnPrompt(messages, toolSpecs, extraUserMessage);
    }

    private TurnOutcome executeTurn(ToolRegistry tr, WorkspaceTools wst, @Nullable PendingTerminal pendingTerminal)
            throws InterruptedException {
        TurnMode mode = pendingTerminal == null ? TurnMode.NORMAL : TurnMode.POST_TERMINAL_CLEANUP;

        if (mode == TurnMode.POST_TERMINAL_CLEANUP && !hasDroppableFragments()) {
            return new TurnOutcome.Final(finalizePendingTerminal(Objects.requireNonNull(pendingTerminal)));
        }

        var prep = preparePrompt(tr, wst, mode);
        Set<ProjectFile> filesBeforeSet = getWorkspaceFileSet();
        if (mode == TurnMode.NORMAL) {
            // preparePrompt depends on lastTurnContext, so update lTC after that
            lastTurnContext = context;
        }

        metrics.startTurn();
        long turnStartTime = System.currentTimeMillis();
        try {
            io.showTransientMessage("Brokk Search is preparing the next actions…");
            var result = llm.sendRequest(prep.messages(), new ToolContext(prep.toolSpecs(), ToolChoice.REQUIRED, tr));

            long llmTimeMs = System.currentTimeMillis() - turnStartTime;
            var tokenUsage = result.metadata();
            int inputTokens = tokenUsage != null ? tokenUsage.inputTokens() : 0;
            int cachedTokens = tokenUsage != null ? tokenUsage.cachedInputTokens() : 0;
            int thinkingTokens = tokenUsage != null ? tokenUsage.thinkingTokens() : 0;
            int outputTokens = tokenUsage != null ? tokenUsage.outputTokens() : 0;
            metrics.recordLlmCall(llmTimeMs, inputTokens, cachedTokens, thinkingTokens, outputTokens);

            if (result.error() != null) {
                var details = TaskResult.StopDetails.fromResponse(result);
                if (details.reason() == TaskResult.StopReason.LLM_CONTEXT_SIZE && checkpoint != null) {
                    io.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            "Context limit exceeded. Restoring last successful checkpoint and entering recovery mode.");
                    context = checkpoint.context();
                    sessionMessages.clear();
                    sessionMessages.addAll(checkpoint.sessionMessages());
                    this.dropOnlyMode = true;
                    return new TurnOutcome.Continue(pendingTerminal);
                }
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO, "LLM error planning next step: " + details.explanation());
                return new TurnOutcome.Final(errorResult(details, taskMeta()));
            }

            var ai = ToolRegistry.removeDuplicateToolRequests(result.aiMessage());
            if (mode == TurnMode.NORMAL
                    && shouldAutomaticallyScan()
                    && ai.toolExecutionRequests().stream().anyMatch(req -> isSearchTool(req.name()))) {
                performAutoScan();
                return new TurnOutcome.Continue(null);
            }

            if (prep.extraUserMessage() != null) {
                sessionMessages.add(prep.extraUserMessage());
            }
            sessionMessages.add(new UserMessage("What tools do you want to use next?"));
            sessionMessages.add(result.aiMessage());

            if (!ai.hasToolExecutionRequests()) {
                return new TurnOutcome.Final(errorResult(
                        new TaskResult.StopDetails(
                                TaskResult.StopReason.TOOL_ERROR, "No tool requests found in LLM response."),
                        taskMeta()));
            }

            if (mode == TurnMode.POST_TERMINAL_CLEANUP) {
                PendingTerminal pt = Objects.requireNonNull(pendingTerminal);

                var unexpected = ai.toolExecutionRequests().stream()
                        .filter(req -> !"dropWorkspaceFragments".equals(req.name()))
                        .map(ToolExecutionRequest::name)
                        .collect(Collectors.toSet());

                if (!unexpected.isEmpty()) {
                    return new TurnOutcome.Final(errorResult(
                            new TaskResult.StopDetails(
                                    TaskResult.StopReason.TOOL_ERROR,
                                    "Unexpected tool request(s) during post-terminal cleanup: " + unexpected),
                            taskMeta()));
                }

                for (var req : ai.toolExecutionRequests()) {
                    ToolExecutionResult toolResult = executeTool(req, tr, wst);
                    sessionMessages.add(toolResult.toExecutionResultMessage());
                    if (toolResult.status() != ToolExecutionResult.Status.SUCCESS) {
                        return new TurnOutcome.Final(errorResult(
                                new TaskResult.StopDetails(
                                        TaskResult.StopReason.TOOL_ERROR,
                                        "Cleanup tool '" + req.name() + "' failed: " + toolResult.resultText()),
                                taskMeta()));
                    }
                }

                dropOnlyMode = false;
                return new TurnOutcome.Final(finalizePendingTerminal(pt));
            }

            boolean executedResearch = false;
            boolean executedNonHygiene = false;
            Context contextAtTurnStart = context;

            var sortedNonterminalCalls = ai.toolExecutionRequests().stream()
                    .filter(req -> categorizeTool(req.name()) != ToolCategory.TERMINAL)
                    .sorted(Comparator.comparingInt(this::priority))
                    .toList();

            for (var req : sortedNonterminalCalls) {
                ToolExecutionResult toolResult = executeTool(req, tr, wst);
                if (toolResult.status() == ToolExecutionResult.Status.FATAL) {
                    var details = new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, toolResult.resultText());
                    return new TurnOutcome.Final(errorResult(details, taskMeta()));
                }

                var display = toolResult.resultText();
                boolean summarize = toolResult.status() == ToolExecutionResult.Status.SUCCESS
                        && Messages.getApproximateTokens(display) > SUMMARIZE_THRESHOLD
                        && shouldSummarize(req.name());
                ToolExecutionResult finalResult;
                if (summarize) {
                    var reasoning =
                            getArgumentsMap(req).getOrDefault("reasoning", "").toString();
                    display = summarizeResult(goal, req, display, reasoning);
                    finalResult = new ToolExecutionResult(req, toolResult.status(), display);
                } else {
                    finalResult = toolResult;
                }

                sessionMessages.add(finalResult.toExecutionResultMessage());

                var category = categorizeTool(req.name());
                if (category != ToolCategory.WORKSPACE_HYGIENE) {
                    executedNonHygiene = true;
                }
                if (category == ToolCategory.RESEARCH && !isWorkspaceTool(req, tr)) {
                    executedResearch = true;
                }
            }

            var terminal = ai.toolExecutionRequests().stream()
                    .filter(req -> categorizeTool(req.name()) == ToolCategory.TERMINAL)
                    .min(Comparator.comparingInt(this::priority));

            boolean contextSafeForTerminal = context.equals(contextAtTurnStart) || !executedNonHygiene;

            if (terminal.isPresent() && contextSafeForTerminal && !executedResearch) {
                var termReq = terminal.get();
                var termExec = executeTool(termReq, tr, wst);
                sessionMessages.add(termExec.toExecutionResultMessage());

                if (termExec.status() != ToolExecutionResult.Status.SUCCESS) {
                    return new TurnOutcome.Final(errorResult(
                            new TaskResult.StopDetails(
                                    TaskResult.StopReason.TOOL_ERROR,
                                    "Terminal tool '" + termReq.name() + "' failed: " + termExec.resultText()),
                            taskMeta()));
                }

                resetPinsToOriginal();
                return new TurnOutcome.Continue(new PendingTerminal(termExec));
            }

            Set<ProjectFile> filesAfterSet = getWorkspaceFileSet();
            dropOnlyMode = false;

            // Update checkpoint at the end of a successful NORMAL turn
            checkpoint = new SearchState(context, List.copyOf(sessionMessages));

            Set<ProjectFile> added = new HashSet<>(filesAfterSet);
            added.removeAll(filesBeforeSet);
            if (!added.isEmpty() && scope != null) {
                scope.publish(context);
            }

            return new TurnOutcome.Continue(null);
        } finally {
            endTurnAndRecordFileChanges(filesBeforeSet);
            if (turnListener != null) {
                turnListener.turnFinished();
            }
        }
    }

    private TaskResult finalizePendingTerminal(PendingTerminal pendingTerminal) {
        if ("abortSearch".equals(pendingTerminal.toolName())) {
            return errorResult(
                    new TaskResult.StopDetails(
                            TaskResult.StopReason.LLM_ABORTED, "Aborted: " + pendingTerminal.resultText()),
                    taskMeta());
        }
        return createResult(pendingTerminal.toolName(), goal);
    }

    protected boolean shouldSummarize(String toolName) {
        return Set.of(
                        "getSymbolLocations",
                        "searchSymbols",
                        "getUsages",
                        "searchSubstrings",
                        "searchFilenames",
                        "searchGitCommitMessages")
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
