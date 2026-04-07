package ai.brokk.agents;

import static java.util.Objects.requireNonNull;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.LlmOutputMeta;
import ai.brokk.Service;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.concurrent.ComputedValue;
import ai.brokk.concurrent.LoggingFuture;
import ai.brokk.context.Context;
import ai.brokk.context.ContextDelta;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.ContextHistory;
import ai.brokk.executor.agents.CustomAgentTools;
import ai.brokk.git.GitWorkflow;
import ai.brokk.gui.Chrome;
import ai.brokk.mcpclient.McpUtils;
import ai.brokk.metrics.SearchMetrics;
import ai.brokk.project.IProject;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.prompts.McpPrompts;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.prompts.SearchPrompts.Objective;
import ai.brokk.prompts.SearchPrompts.Terminal;
import ai.brokk.tools.DependencyTools;
import ai.brokk.tools.ExplanationRenderer;
import ai.brokk.tools.ParallelSearch;
import ai.brokk.tools.SearchTools;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.tools.WorkspaceTools;
import ai.brokk.util.Json;
import ai.brokk.util.Messages;
import com.google.common.collect.Streams;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * LutzAgent:
 * - Uses tools to curate Workspace context for follow-on coding.
 * - Starts by calling ContextAgent to add recommended fragments to the Workspace.
 * - Adds every learning step to Context history (no hidden state).
 * - Summarizes very large tool outputs before recording them.
 * - If the model exceeds its context limit, restores the last successful checkpoint and enters
 *   {@code dropOnlyMode}, where the agent is constrained to cleanup-only actions (dropping fragments) until the
 *   Workspace is under the limit.
 * - Never writes code or answers questions itself; it prepares the Workspace for a later Code Agent run.
 */
public class LutzAgent {
    private static final Logger logger = LogManager.getLogger(LutzAgent.class);

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

    private static final int MAX_TOTAL_TURNS = 20;
    private final IContextManager cm;
    private final StreamingChatModel model;
    private final ContextManager.TaskScope scope;
    private final Llm llm;
    private final Llm scanLlm;
    private final IConsoleIO io;
    private final String goal;
    private final List<McpPrompts.McpTool> mcpTools;
    private final SearchTools searchTools;
    private final List<String> staticTools;
    private final List<String> terminalTools;
    private final Set<String> terminalToolNames;
    private final SearchMetrics metrics;
    private ScanConfig scanConfig;
    private boolean scanPerformed;

    private boolean terminalCompletionReported = false;

    private final Objective objective;

    SearchState currentState;
    private SearchState checkpointState;

    public enum DropMode {
        DROP_ONLY,
        DROP_ENCOURAGED,
        NORMAL
    }

    private static final double RESEARCH_TOKENS_EMA_ALPHA = 0.5;
    private static final int STAGNATION_TURNS_THRESHOLD = 5;
    private static final String READY_FOR_FINAL_TURN_FILTER =
            """
            The agent is expressing confidence that it has enough information to answer the question or resolve the problem.
            """;

    private final Set<ContextFragment> originalPinnedFragments;
    private final List<ContextFragment> droppedFragments = new ArrayList<>();
    private double researchTokensEma = 0.0;
    private boolean overflowNormalReplayPendingResult;
    private @Nullable String pendingOverflowRecoveryNote;

    private static final long OVERFLOW_GROWTH_REPLAY_THRESHOLD = 20_000L;
    private static final int MAX_OVERFLOW_NOTE_FRAGMENT_DETAILS = 25;

    private int consecutiveStagnantTurns = 0;
    private final AtomicBoolean finalTurnRequestedByClassifier = new AtomicBoolean(false);
    private final AtomicBoolean terminalDecisionRequestedByModel = new AtomicBoolean(false);

    /**
     * Tracks the fragment IDs present immediately after the most recent successful dropWorkspaceFragments call.
     * The "worthwhile" heuristic is then based on how many tokens have been added since that point.
     *
     * <p>When no drop has ever occurred, this is intentionally empty so the heuristic considers the entire
     * current Workspace as "added since last drop".</p>
     */
    private Set<String> fragmentIdsAtLastDrop = Set.of();

    public LutzAgent(
            Context initialContext,
            String goal,
            StreamingChatModel model,
            ContextManager.TaskScope scope,
            IConsoleIO io,
            ScanConfig scanConfig) {
        this(initialContext, goal, model, Objective.WORKSPACE_ONLY, scope, io, scanConfig);
    }

    public LutzAgent(
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

    public LutzAgent(
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
        this.scanConfig = scanConfig;

        var llmOptions = new Llm.Options(model, goal, TaskResult.Type.LUTZ).withEcho();
        this.llm = cm.getLlm(llmOptions);
        this.llm.setOutput(this.io);

        var scanLlmOptions = new Llm.Options(getScanModel(), goal, TaskResult.Type.CLASSIFY);
        this.scanLlm = cm.getLlm(scanLlmOptions);

        this.metrics = "true".equalsIgnoreCase(System.getenv("BRK_COLLECT_METRICS"))
                ? SearchMetrics.tracking()
                : SearchMetrics.noOp();

        this.mcpTools = initMcpTools(cm.getProject());
        this.searchTools = new SearchTools(cm);
        this.currentState = SearchState.initial(initialContext);
        this.checkpointState = currentState;
        this.originalPinnedFragments = initialContext.getPinnedFragments().collect(Collectors.toSet());
        this.objective = objective;

        this.terminalTools = List.copyOf(calculateTerminalTools());
        this.terminalToolNames = Set.copyOf(terminalTools);

        this.staticTools = initStaticTools(cm.getProject(), mcpTools);
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

        tools.add("callSearchAgent");

        // Direct anchored-lookup tools. These cover cheap one-hop discovery when the agent already has
        // concrete symbols, filenames, or string anchors and only needs exact locations.
        tools.add("searchSymbols");
        tools.add("scanUsages");
        tools.add("findFilenames");
        tools.add("searchFileContents");

        // Workspace analyzer tools
        tools.add("addClassesToWorkspace");
        tools.add("addClassSummariesToWorkspace");
        tools.add("addMethodsToWorkspace");
        tools.add("addFileSummariesToWorkspace");

        // Non-analyzer tools
        tools.add("addLineRangeToWorkspace");
        tools.add("addFilesToWorkspace");
        tools.add("addUrlContentsToWorkspace");

        // Shell command execution
        tools.add("runShellCommand");

        if (!mcpTools.isEmpty()) {
            tools.add("callMcpTool");
        }

        // Custom agents
        tools.add("callCustomAgent");

        // Filter out analyzer-required tools at the very end
        return WorkspaceTools.filterByAnalyzerAvailability(tools, project);
    }

    public LutzAgent(Context initialContext, String goal, StreamingChatModel model, ContextManager.TaskScope scope) {
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
        var finalMessages = io.getLlmRawMessages();
        if (!finalMessages.isEmpty()) {
            tr = tr.withAppendedMopMessagesToLastEntry(finalMessages);
        }

        return tr;
    }

    private boolean shouldAutomaticallyScan() {
        return scanConfig.autoScan() && !scanPerformed;
    }

    private void setupContextInternal() throws InterruptedException {
        Context contextBeforeSetup = currentState.context();
        boolean shouldPrune = scanConfig.autoPrune()
                && hasDroppableFragments(contextBeforeSetup)
                && isPruningWorthwhile(contextBeforeSetup);

        SetupContextResult setupResult = setupContext(contextBeforeSetup, goal, shouldPrune);
        Context preparedContext = setupResult.context();

        boolean contextChanged = !contextBeforeSetup.equals(preparedContext);
        boolean shouldPublish = scanConfig.appendToScope() && contextChanged;
        boolean hasNewReferences = !setupResult.newFragments().isEmpty();

        if (shouldPrune) {
            // Janitor activity: publish combined pruning + reference results as one history entry
            if (shouldPublish) {
                scope.append(preparedContext);
            }
            recordDropBaseline(preparedContext);
        } else if (hasNewReferences && shouldPublish) {
            // No pruning took place, but the ReferenceAgent added fragments that the user
            // should see as an explicit context change in the history
            scope.publish(preparedContext);
        }

        if (hasNewReferences) {
            checkpointState = SearchState.initial(preparedContext);
            logger.debug("Referenced fragments resolved: {}", setupResult.newFragments());
        }

        currentState = currentState.withContext(preparedContext);
    }

    private TaskResult executeInternal() throws InterruptedException {
        setupContextInternal();

        DropMode dropMode = calculateDropMode(currentState.context());

        for (int turn = 0; turn < MAX_TOTAL_TURNS; ) {
            if (dropMode != DropMode.DROP_ONLY && turn < MAX_TOTAL_TURNS - 1 && finalTurnRequestedByClassifier.get()) {
                turn = MAX_TOTAL_TURNS - 1;
            }

            SearchState stateAtTurnStart = currentState;
            var sta = new SingleTurnAgent(this, stateAtTurnStart, dropMode, turn, MAX_TOTAL_TURNS);
            var outcome = sta.executeTurn();

            switch (outcome) {
                case TurnOutcome.Final f -> {
                    return f.result();
                }
                case TurnOutcome.Overflow overflow -> {
                    Context overflowContext = currentState.context();
                    Context checkpointContext = checkpointState.context();
                    OverflowGrowth overflowGrowth = computeOverflowGrowth(checkpointContext, overflowContext);

                    if (currentState.equals(checkpointState)) {
                        String explanation = overflowNormalReplayPendingResult
                                ? "Context limit exceeded even after checkpoint replay retry."
                                : "Context limit exceeded and cannot be recovered by checkpoint restore or pruning.";
                        return errorResult(
                                new TaskResult.StopDetails(TaskResult.StopReason.LLM_CONTEXT_SIZE, explanation),
                                currentState.context());
                    }

                    logger.debug(
                            "Context limit exceeded. Restoring last successful checkpoint and entering recovery mode.");

                    currentState = checkpointState;
                    boolean pruningWorthwhileAtCheckpoint = isPruningWorthwhile(currentState.context());
                    if (!pruningWorthwhileAtCheckpoint
                            && overflowGrowth.netGrowthTokens() > OVERFLOW_GROWTH_REPLAY_THRESHOLD
                            && !overflowNormalReplayPendingResult) {
                        pendingOverflowRecoveryNote = buildOverflowRecoveryHarnessNote(overflowGrowth);
                        overflowNormalReplayPendingResult = true;
                        dropMode = DropMode.NORMAL;
                        continue;
                    }
                    if (!pruningWorthwhileAtCheckpoint) {
                        return errorResult(
                                new TaskResult.StopDetails(
                                        TaskResult.StopReason.LLM_CONTEXT_SIZE,
                                        "Context limit exceeded; no substantial fragments are available to drop."),
                                currentState.context());
                    }

                    // If we overflow on the final turn, re-run the final turn from the restored checkpoint.
                    // (Otherwise we'd immediately hit TURN_LIMIT without any chance to recover.)
                    if (turn == MAX_TOTAL_TURNS - 1) {
                        dropMode = calculateDropMode(currentState.context());
                        continue;
                    }

                    overflowNormalReplayPendingResult = false;
                    dropMode = DropMode.DROP_ONLY;
                    turn++;
                }
                case TurnOutcome.AutoScan autoScan -> {
                    assert dropMode != DropMode.DROP_ONLY;
                    overflowNormalReplayPendingResult = false;
                    performAutoScan();
                    turn++;
                }
                case TurnOutcome.Continue c -> {
                    SearchState nextState = new SearchState(
                            c.contextAfterTurn(),
                            c.sessionMessagesAfterTurn(),
                            stateAtTurnStart.context(),
                            c.presentedRelatedFiles());
                    currentState = nextState;

                    // if we just ran a drop-only turn, reset checkpoint to current state: this means that if we
                    // are still overflowed after the drop-only, next turn will exit instead of
                    // (almost certainly futily) retrying drop-only
                    checkpointState = dropMode == DropMode.DROP_ONLY ? nextState : stateAtTurnStart;
                    dropMode = calculateDropMode(c.contextAfterTurn());
                    overflowNormalReplayPendingResult = false;
                    turn++;
                }
                case TurnOutcome.ForceFinalTurn fft -> {
                    assert dropMode != DropMode.DROP_ONLY;
                    currentState = new SearchState(
                            fft.contextAfterTurn(),
                            fft.sessionMessagesAfterTurn(),
                            stateAtTurnStart.context(),
                            fft.presentedRelatedFiles());

                    checkpointState = stateAtTurnStart;
                    dropMode = calculateDropMode(fft.contextAfterTurn());
                    overflowNormalReplayPendingResult = false;

                    turn = MAX_TOTAL_TURNS - 1;
                }
                default -> throw new AssertionError("Unexpected outcome " + outcome);
            }
        }

        return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.TURN_LIMIT), currentState.context());
    }

    public record SetupContextResult(Context context, Set<ContextFragment> newFragments) {}

    /**
     * - always resolved references in `goal` and adds to returned Context
     * - When `prune` is true, also runs JanitorAgent to drop unwanted fragments
     */
    public static SetupContextResult setupContext(Context initialContext, String goal, boolean prune)
            throws InterruptedException {
        IContextManager cm = initialContext.getContextManager();

        // async referenceagent
        CompletableFuture<Set<ContextFragment>> referencesFuture = LoggingFuture.supplyCallableAsync(
                () -> new ReferenceAgent(cm).resolveReferencedFragments(goal, initialContext));

        // async janitoragent
        CompletableFuture<Context> pruneFuture;
        if (prune) {
            pruneFuture = LoggingFuture.supplyCallableAsync(() -> {
                IContextManager cm1 = initialContext.getContextManager();
                var janitor = new JanitorAgent(cm1, cm1.getIo(), goal, initialContext);
                return janitor.execute().context();
            });
        } else {
            pruneFuture = CompletableFuture.completedFuture(initialContext);
        }

        // wait for both agents
        Context preparedContext;
        Set<ContextFragment> referencedFragments;
        try {
            LoggingFuture.allOf(pruneFuture, referencesFuture).join();
            preparedContext = pruneFuture.join();
            referencedFragments = referencesFuture.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw e;
        }

        if (!referencedFragments.isEmpty()) {
            preparedContext = preparedContext.addFragments(referencedFragments);
        }

        return new SetupContextResult(preparedContext, referencedFragments);
    }

    private ToolRegistry createToolRegistry(WorkspaceTools wst, Object toolProvider, ParallelSearch parallelSearch) {
        var builder = cm.getToolRegistry()
                .builder()
                .register(searchTools)
                .register(wst)
                .register(toolProvider)
                .register(parallelSearch)
                .register(new CustomAgentTools(cm, model));
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

    private DropMode calculateDropMode(Context context) {
        if (!hasDroppableFragments(context)) {
            return DropMode.NORMAL;
        }

        long workspaceTokens = context.allFragments()
                .filter(f1 -> f1.getType().includeInProjectGuide())
                .mapToLong(f -> (long)
                        Math.ceil(1.2 * Messages.getApproximateTokens(f.text().join())))
                .sum();
        var maxInputTokens = cm.getService().getMaxInputTokens(model);
        double pct = (double) workspaceTokens / maxInputTokens * 100.0;

        if (pct > 90) {
            return DropMode.DROP_ONLY;
        } else if (pct > 60) {
            return DropMode.DROP_ENCOURAGED;
        }
        return DropMode.NORMAL;
    }

    List<String> calculateAllowedToolNames(Context context) {
        // Keep the toolset stable across turns; special turns use prompt-level allowlists.
        var names = new ArrayList<>(staticTools);

        // Always include dropWorkspaceFragments to avoid toolset changes when droppability changes.
        names.add("dropWorkspaceFragments");

        if (DependencyTools.isSupported(cm.getProject())) {
            names.add("importDependency");
        }

        return names;
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
            case "addFilesToWorkspace", "addLineRangeToWorkspace" -> 4;
            case "addClassesToWorkspace", "addFileSummariesToWorkspace" -> 5;
            case "addMethodsToWorkspace", "addClassSummariesToWorkspace" -> 6;
            case "searchSymbols",
                    "getSymbolLocations",
                    "scanUsages",
                    "findFilesContaining",
                    "searchFileContents",
                    "findFilenames",
                    "searchGitCommitMessages" -> 20;
            case "getClassSkeletons", "getClassSources", "getMethodSources" -> 30;
            case "getCallGraphTo", "getCallGraphFrom", "getFileContents", "getFileSummaries", "skimFiles" -> 40;

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

    private void performAutoScan() throws InterruptedException {
        scanContext();
        scanPerformed = true;
    }

    private Objective getObjective() {
        return objective;
    }

    Set<String> finalTurnPreTerminalToolNames() {
        return mcpTools.isEmpty() ? Set.of("dropWorkspaceFragments") : Set.of("dropWorkspaceFragments", "callMcpTool");
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
        metrics.recordContextScan(filesAdded.size(), false, toRelativePaths(filesAdded));
        context = context.addHistoryEntry(
                io.getLlmRawMessages(), List.of(), TaskResult.Type.SCAN, scanModel, "Locate relevant context");

        if (scanConfig.appendToScope) {
            this.scope.append(context);
        }

        currentState = currentState.withContext(context);
        return context;
    }

    private StreamingChatModel getScanModel() {
        return scanConfig.scanModel() == null ? cm.getService().getScanModel() : scanConfig.scanModel();
    }

    private boolean toolTriggersScan(ToolExecutionRequest req) {
        String toolName = req.name();
        return toolName.startsWith("search") || toolName.startsWith("find") || "callSearchAgent".equals(toolName);
    }

    private StreamingChatModel delegatedSearchModel() {
        return ParallelSearch.usePlannerModelForSearchAgent()
                ? model
                : cm.getService().getModel(ModelType.SEARCH);
    }

    /**
     * Invokes the Code Agent to implement instructions using the current SearchState.
     * This is intended for internal/legacy callers and does not advance the SearchAgent's turn loop.
     */
    @Blocking
    public TaskResult callCodeAgent(String instructions) throws InterruptedException {
        ArchitectAgent architect =
                new ArchitectAgent(cm, model, cm.getCodeModel(), instructions, scope, currentState.context());

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
        private final LutzAgent agent;
        private final DropMode dropMode;
        private final int turnNumber;
        private final int maxTurns;

        private Context context; // mutable
        private final @Nullable Context lastTurnContext;
        private final List<ChatMessage> sessionMessages;

        private final ParallelSearch parallelSearch;
        private final ToolRegistry tr;

        private SingleTurnAgent(
                LutzAgent agent, SearchState stateAtTurnStart, DropMode dropMode, int turnNumber, int maxTurns) {
            assert maxTurns > 0;
            assert turnNumber >= 0 && turnNumber < maxTurns;

            this.agent = agent;
            this.dropMode = dropMode;
            this.turnNumber = turnNumber;
            this.maxTurns = maxTurns;

            this.context = stateAtTurnStart.context();
            this.lastTurnContext = stateAtTurnStart.lastTurnContext();
            this.sessionMessages = new ArrayList<>(stateAtTurnStart.sessionMessages());

            this.parallelSearch =
                    new ParallelSearch(context.forSearchAgent(), agent.goal, agent.delegatedSearchModel());
            this.tr = agent.createToolRegistry(new WorkspaceTools(context), this, parallelSearch);
        }

        private TurnOutcome executeTurn() throws InterruptedException {
            boolean stagnationEligible = dropMode != DropMode.DROP_ONLY && !isFinalTurn();
            Context contextAtTurnStartForStagnation = context;

            Set<ProjectFile> filesBeforeSet = agent.workspaceFiles(context);
            TurnOutcome outcome;
            long researchTokensThisTurn = 0L;

            try {
                outcome = executeTurnInternal(filesBeforeSet);
                researchTokensThisTurn =
                        agent.calculateResearchTokensAddedThisTurn(contextAtTurnStartForStagnation, context);
            } finally {
                agent.recordResearchTokensForTurn(researchTokensThisTurn);
                agent.endTurnAndRecordFileChanges(filesBeforeSet, context);
            }

            if (stagnationEligible && outcome instanceof TurnOutcome.Continue c) {
                boolean shouldForceFinalTurn = agent.recordTurnForStagnation(
                        contextAtTurnStartForStagnation, c.contextAfterTurn(), researchTokensThisTurn);
                if (shouldForceFinalTurn) {
                    return new TurnOutcome.ForceFinalTurn(
                            c.contextAfterTurn(), c.sessionMessagesAfterTurn(), c.presentedRelatedFiles());
                }
            }

            return outcome;
        }

        private TurnOutcome executeTurnInternal(Set<ProjectFile> filesBeforeSet) throws InterruptedException {
            int invalidSpecialToolAttempts = 0;

            AiMessage ai;
            List<ToolExecutionRequest> orderedRequests;

            while (true) {
                var prep = preparePrompt(invalidSpecialToolAttempts >= 2);

                agent.metrics.startTurn();
                long turnStartTime = System.currentTimeMillis();

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
                        return TurnOutcome.Overflow.INSTANCE;
                    }
                    agent.io.showNotification(
                            IConsoleIO.NotificationRole.INFO, "LLM error planning next step: " + details.explanation());
                    return new TurnOutcome.Final(agent.errorResult(details, context));
                }

                ai = ToolRegistry.removeDuplicateToolRequests(result.aiMessage());

                if (agent.shouldAutomaticallyScan()
                        && ai.toolExecutionRequests().stream().anyMatch(req -> agent.toolTriggersScan(req))) {
                    return TurnOutcome.AutoScan.INSTANCE;
                }

                sessionMessages.add(new UserMessage("What tools do you want to use next?"));
                sessionMessages.add(result.aiMessage());

                if (!ai.hasToolExecutionRequests()) {
                    return new TurnOutcome.Final(agent.errorResult(
                            new TaskResult.StopDetails(
                                    TaskResult.StopReason.TOOL_ERROR, "No tool requests found in LLM response."),
                            context));
                }

                if (prep.strictToolAllowList() != null) {
                    var invalid = ai.toolExecutionRequests().stream()
                            .filter(req -> !prep.strictToolAllowList().contains(req.name()))
                            .toList();

                    if (!invalid.isEmpty()) {
                        invalidSpecialToolAttempts++;

                        var allowed =
                                prep.strictToolAllowList().stream().sorted().toList();
                        for (var req : invalid) {
                            var toolResult = ToolExecutionResult.requestError(
                                    req,
                                    "Tool '" + req.name() + "' is not allowed in this special turn. Allowed tools: "
                                            + allowed);
                            sessionMessages.add(toolResult.toMessage());
                        }

                        if (invalidSpecialToolAttempts <= 2) {
                            continue;
                        }

                        return new TurnOutcome.Final(agent.errorResult(
                                new TaskResult.StopDetails(
                                        TaskResult.StopReason.TOOL_ERROR,
                                        "Model repeatedly requested tools outside the special-turn allowlist: "
                                                + allowed),
                                context));
                    }
                }

                orderedRequests = ai.toolExecutionRequests().stream()
                        .sorted(Comparator.comparingInt((ToolExecutionRequest req) -> agent.priority(req)))
                        .toList();
                break;
            }

            var terminalPartition = ToolRegistry.partitionByNames(orderedRequests, agent.terminalToolNames);
            var terminalRequests = terminalPartition.matchingRequests();
            var primaryCalls = terminalPartition.otherRequests();
            boolean hasTerminalRequest = !terminalRequests.isEmpty();
            if (hasTerminalRequest) {
                agent.terminalDecisionRequestedByModel.compareAndSet(false, true);
            } else {
                String modelResponse = ai.reasoningContent() + "\n\n" + ai.text();
                agent.startWorkspaceCompleteClassification(modelResponse);
            }

            DropMode effectiveDropMode = isFinalTurn() ? DropMode.NORMAL : dropMode;
            if (isFinalTurn()) {
                @Nullable
                ToolExecutionRequest terminalRequest = terminalRequests.isEmpty() ? null : terminalRequests.getFirst();

                if (terminalRequest == null) {
                    return new TurnOutcome.Final(agent.errorResult(
                            new TaskResult.StopDetails(
                                    TaskResult.StopReason.TOOL_ERROR,
                                    "Final turn requires a terminal tool call (e.g. workspaceComplete/answer/createOrReplaceTaskList/callCodeAgent/describeIssue/abortSearch)."),
                            context));
                }

                var finalTurnPreTerminalTools = agent.finalTurnPreTerminalToolNames();
                for (var req : orderedRequests) {
                    if (!finalTurnPreTerminalTools.contains(req.name())) {
                        continue;
                    }

                    agent.io.beforeToolCall(req);
                    ToolExecutionResult toolResult = executeTool(req);
                    agent.io.afterToolOutput(toolResult);

                    if (toolResult.status() == ToolExecutionResult.Status.FATAL) {
                        var details =
                                new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, toolResult.resultText());
                        return new TurnOutcome.Final(agent.errorResult(details, context));
                    }

                    sessionMessages.add(toolResult.toMessage());
                }

                agent.io.beforeToolCall(terminalRequest);
                var termExec = executeTool(terminalRequest);
                agent.io.afterToolOutput(termExec);

                sessionMessages.add(termExec.toMessage());

                if (termExec.status() != ToolExecutionResult.Status.SUCCESS) {
                    return new TurnOutcome.Final(agent.errorResult(
                            new TaskResult.StopDetails(
                                    TaskResult.StopReason.TOOL_ERROR,
                                    "Terminal tool '%s' failed with status %s: %s"
                                            .formatted(
                                                    terminalRequest.name(), termExec.status(), termExec.resultText())),
                            context));
                }

                context = agent.resetPinsToOriginal(context);
                context = context.removeSupersededSummaries();
                var pending = new PendingTerminal(termExec);
                return new TurnOutcome.Final(agent.finalizePendingTerminal(pending, context));
            }

            if (effectiveDropMode == DropMode.DROP_ONLY) {
                var allowed =
                        Set.of("dropWorkspaceFragments", "addFileSummariesToWorkspace", "addClassSummariesToWorkspace");
                var disallowed = orderedRequests.stream()
                        .map(ToolExecutionRequest::name)
                        .filter(name -> !allowed.contains(name))
                        .distinct()
                        .sorted()
                        .toList();
                if (!disallowed.isEmpty()) {
                    return new TurnOutcome.Final(agent.errorResult(
                            new TaskResult.StopDetails(
                                    TaskResult.StopReason.TOOL_ERROR,
                                    "DROP_ONLY recovery allows only %s; disallowed: %s".formatted(allowed, disallowed)),
                            context));
                }
            }

            @Nullable
            ToolExecutionRequest terminalRequest = terminalRequests.isEmpty() ? null : terminalRequests.getFirst();

            Context contextAtTurnStart = context;
            boolean executedNonHygiene = false;
            List<String> nonHygieneToolCalls = new ArrayList<>();

            var searchPartition = ToolRegistry.partitionByNames(primaryCalls, Set.of("callSearchAgent"));
            var searchAgentReqs = searchPartition.matchingRequests();
            var otherPrimaryCalls = searchPartition.otherRequests();

            for (var req : otherPrimaryCalls) {
                agent.io.beforeToolCall(req);
                ToolExecutionResult toolResult = executeTool(req);
                agent.io.afterToolOutput(toolResult);

                if (toolResult.status() == ToolExecutionResult.Status.FATAL) {
                    var details = new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, toolResult.resultText());
                    return new TurnOutcome.Final(agent.errorResult(details, context));
                }

                sessionMessages.add(toolResult.toMessage());
                if (!"dropWorkspaceFragments".equals(req.name())) {
                    executedNonHygiene = true;
                    nonHygieneToolCalls.add(req.name());
                }
            }

            if (!searchAgentReqs.isEmpty()) {
                // record the current planning; show the search agent output inside a new task
                context = agent.appendUiMessagesToHistory(context);
                agent.scope.append(agent.resetPinsToOriginal(context));
                var searchResult = parallelSearch.execute(searchAgentReqs, tr);
                if (searchResult.stopDetails().reason() == TaskResult.StopReason.LLM_ERROR) {
                    return new TurnOutcome.Final(agent.errorResult(
                            new TaskResult.StopDetails(
                                    TaskResult.StopReason.LLM_ERROR,
                                    searchResult.stopDetails().explanation()),
                            context));
                }

                context = context.addFragments(
                        searchResult.context().allFragments().toList());
                sessionMessages.addAll(searchResult.toolExecutionMessages());
                context = context.addHistoryEntry(
                        searchResult.mopMessages(),
                        List.of(),
                        TaskResult.Type.SEARCH,
                        agent.model,
                        searchResult.historyDescription());
                agent.scope.append(agent.resetPinsToOriginal(context));

                executedNonHygiene = true;
                nonHygieneToolCalls.add("callSearchAgent");
            }

            boolean contextSafeForTerminal = context.equals(contextAtTurnStart) || !executedNonHygiene;
            if (terminalRequest != null && contextSafeForTerminal) {
                agent.io.beforeToolCall(terminalRequest);
                var termExec = executeTool(terminalRequest);
                agent.io.afterToolOutput(termExec);

                sessionMessages.add(termExec.toMessage());

                if (termExec.status() != ToolExecutionResult.Status.SUCCESS) {
                    return new TurnOutcome.Final(agent.errorResult(
                            new TaskResult.StopDetails(
                                    TaskResult.StopReason.TOOL_ERROR,
                                    "Terminal tool '%s' failed with status %s: %s"
                                            .formatted(
                                                    terminalRequest.name(), termExec.status(), termExec.resultText())),
                            context));
                }

                context = agent.resetPinsToOriginal(context);
                context = context.removeSupersededSummaries();
                return new TurnOutcome.Final(agent.finalizePendingTerminal(new PendingTerminal(termExec), context));
            }
            if (terminalRequest != null) {
                String changedTools =
                        nonHygieneToolCalls.stream().distinct().sorted().collect(Collectors.joining(", "));
                String ignoredMessage = "Terminal call '%s' ignored because tool calls %s changed the Workspace."
                        .formatted(terminalRequest.name(), changedTools);
                var ignored = ToolExecutionResult.requestError(terminalRequest, ignoredMessage);
                agent.io.beforeToolCall(terminalRequest);
                agent.io.afterToolOutput(ignored);
                sessionMessages.add(ignored.toMessage());
            }

            Set<ProjectFile> filesAfterSet = agent.workspaceFiles(context);

            Set<ProjectFile> added = new HashSet<>(filesAfterSet);
            added.removeAll(filesBeforeSet);
            if (!added.isEmpty()) {
                agent.scope.publish(agent.resetPinsToOriginal(context));
            }

            return new TurnOutcome.Continue(
                    context, List.copyOf(sessionMessages), agent.currentState.presentedRelatedFiles());
        }

        private TurnPrompt preparePrompt(boolean workspaceOnlyNoHistory) throws InterruptedException {
            DropMode effectiveDropMode = isFinalTurn() ? DropMode.NORMAL : dropMode;

            // update pins before generating prompt
            Map<ProjectFile, String> related;
            if (effectiveDropMode == DropMode.DROP_ONLY) {
                context = agent.resetPinsToOriginal(context);
                related = Map.of();
                assert agent.hasDroppableFragments(context);
            } else {
                related = context.buildRelatedSymbols(10, 20, agent.currentState.presentedRelatedFiles());
                if (!related.isEmpty()) {
                    Set<ProjectFile> updatedRelated = new HashSet<>(agent.currentState.presentedRelatedFiles());
                    updatedRelated.addAll(related.keySet());
                    agent.currentState = agent.currentState.withPresentedRelatedFiles(updatedRelated);
                }
                context = agent.applyPinning(context, lastTurnContext);
            }

            int turnsLeftAfterThisTurn = maxTurns - turnNumber - 1;

            List<String> allowedOrdinaryTools = agent.calculateAllowedToolNames(context);
            List<String> terminalTools = agent.terminalTools;

            @Nullable SearchPrompts.SpecialTurnTooling specialToolingNotice = null;
            if (isFinalTurn()) {
                var allowedFinalTurnTools = Streams.concat(
                                agent.finalTurnPreTerminalToolNames().stream(), terminalTools.stream())
                        .distinct()
                        .toList();
                specialToolingNotice = new SearchPrompts.SpecialTurnTooling("Final turn", allowedFinalTurnTools);
            } else if (effectiveDropMode == DropMode.DROP_ONLY) {
                specialToolingNotice = new SearchPrompts.SpecialTurnTooling(
                        "Context overflow recovery",
                        List.of(
                                "dropWorkspaceFragments",
                                "addFileSummariesToWorkspace",
                                "addClassSummariesToWorkspace"));
            }

            List<ChatMessage> messages;
            if (workspaceOnlyNoHistory) {
                messages = SearchPrompts.instance.buildPromptWorkspaceOnly(
                        context,
                        agent.model,
                        agent.goal,
                        agent.getObjective(),
                        agent.mcpTools,
                        effectiveDropMode,
                        turnsLeftAfterThisTurn,
                        requireNonNull(specialToolingNotice));
            } else {
                messages = SearchPrompts.instance.buildPrompt(
                        context,
                        agent.model,
                        agent.taskMeta(),
                        agent.goal,
                        agent.getObjective(),
                        agent.mcpTools,
                        sessionMessages,
                        related,
                        effectiveDropMode,
                        turnsLeftAfterThisTurn,
                        specialToolingNotice);
            }

            if (agent.pendingOverflowRecoveryNote != null) {
                messages = new ArrayList<>(messages);
                messages.add(new UserMessage(agent.pendingOverflowRecoveryNote));
                agent.pendingOverflowRecoveryNote = null;
            }

            @Nullable
            Set<String> strictToolAllowList =
                    specialToolingNotice == null ? null : Set.copyOf(specialToolingNotice.allowedTools());

            var toolNameAllowList = strictToolAllowList != null
                    ? strictToolAllowList
                    : Streams.concat(allowedOrdinaryTools.stream(), terminalTools.stream())
                            .distinct()
                            .collect(Collectors.toSet());

            var toolSpecs = tr.getTools(toolNameAllowList);
            return new TurnPrompt(messages, toolSpecs, strictToolAllowList);
        }

        private boolean isFinalTurn() {
            return turnNumber == maxTurns - 1;
        }

        private ToolExecutionResult executeTool(ToolExecutionRequest req) throws InterruptedException {
            agent.metrics.recordToolCall(req.name());
            var executionRegistry = ToolRegistry.fromBase(tr)
                    .register(new WorkspaceTools(context))
                    .build();
            var result = executionRegistry.executeTool(req);
            agent.llm.recordToolExecution(result);

            if (agent.isWorkspaceTool(req, executionRegistry)
                    && result.status() == ToolExecutionResult.Status.SUCCESS) {
                var updatedContext = "dropWorkspaceFragments".equals(req.name())
                        ? ((WorkspaceTools.DropWorkspaceOutput) result.result()).context()
                        : ((WorkspaceTools.WorkspaceMutationOutput) result.result()).context();
                agent.updateDroppedHistory(context, updatedContext);
                context = updatedContext;

                if ("dropWorkspaceFragments".equals(req.name())
                        && result.status() == ToolExecutionResult.Status.SUCCESS) {
                    agent.recordDropBaseline(context);
                }
            }
            return result;
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
            var json = Json.getMapper()
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
            if (agent.isPruningWorthwhile(context)) {
                var janitor = new JanitorAgent(agent.cm, agent.io, agent.goal, context);
                var janitorResult = janitor.execute();
                Context prunedContext = janitorResult.context();
                agent.updateDroppedHistory(context, prunedContext);
                context = prunedContext;
                agent.recordDropBaseline(prunedContext);
            }

            // record the current planning; show the code agent output inside a new task
            var oldContext = context;
            context = agent.appendUiMessagesToHistory(context);
            if (!oldContext.equals(context)) {
                agent.scope.append(agent.resetPinsToOriginal(context));
            }

            logger.debug("SearchAgent.callCodeAgent invoked with instructions: {}", instructions);

            var architect = new ArchitectAgent(
                    agent.cm,
                    agent.model,
                    agent.cm.getCodeModel(),
                    instructions,
                    agent.scope,
                    agent.resetPinsToOriginal(context));
            var buildDetails = agent.cm.getProject().awaitBuildDetails();
            String verifyCommand = buildDetails.afterTaskListCommand();
            if (!verifyCommand.isBlank()) {
                architect.setVerifyCommand(verifyCommand);
            }

            var result = architect.execute();
            var stopDetails = result.stopDetails();
            var reason = stopDetails.reason();
            agent.scope.append(result);
            context = result.context();

            if (reason == TaskResult.StopReason.SUCCESS) {
                if (agent.cm.isAutoCommit() && agent.cm.getProject().hasGit()) {
                    new GitWorkflow(agent.cm).performAutoCommit(instructions);
                }
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

    private TaskResult createResult(Context context) {
        return createResult(context, new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS));
    }

    private TaskResult createResult(Context context, TaskResult.StopDetails details) {
        context = appendUiMessagesToHistory(context);
        context = resetPinsToOriginal(context);
        recordFinalWorkspaceState(context);
        metrics.recordOutcome(details.reason(), workspaceFiles(context).size());

        return new TaskResult(context, details);
    }

    private TaskResult errorResult(TaskResult.StopDetails details) {
        return errorResult(details, currentState.context());
    }

    private TaskResult errorResult(TaskResult.StopDetails details, Context context) {
        context = appendUiMessagesToHistory(context);
        context = resetPinsToOriginal(context);

        recordFinalWorkspaceState(context);
        metrics.recordOutcome(details.reason(), workspaceFiles(context).size());

        return new TaskResult(context, details);
    }

    private Context appendUiMessagesToHistory(Context context) {
        return context.addHistoryEntry(cm.getIo().getLlmRawMessages(), TaskResult.Type.LUTZ, model, goal);
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
                .flatMap(f -> f.sourceFiles().renderNowOr(Set.of()).stream())
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
                        f.sourceFiles().renderNowOr(Set.of()).stream()
                                .map(pf -> pf.getRelPath().toString())
                                .sorted()
                                .toList()))
                .toList();
    }

    boolean hasDroppableFragments(Context context) {
        return context.allFragments().anyMatch(f -> !isPinnedBySystem(f));
    }

    record OverflowGrowth(long netGrowthTokens, List<AddedFragmentTokens> addedFragments) {}

    record AddedFragmentTokens(String label, long tokens) {}

    OverflowGrowth computeOverflowGrowth(Context checkpointContext, Context overflowContext) {
        long netGrowthTokens = contextTokenCount(overflowContext) - contextTokenCount(checkpointContext);
        List<AddedFragmentTokens> added =
                ContextDelta.between(checkpointContext, overflowContext).join().addedFragments().stream()
                        .map(f -> new AddedFragmentTokens(
                                fragmentLabel(f),
                                Messages.getApproximateTokens(f.text().join())))
                        .sorted(Comparator.comparingLong(AddedFragmentTokens::tokens)
                                .reversed())
                        .toList();
        return new OverflowGrowth(netGrowthTokens, added);
    }

    String buildOverflowRecoveryHarnessNote(OverflowGrowth growth) {
        String details = growth.addedFragments().stream()
                .limit(MAX_OVERFLOW_NOTE_FRAGMENT_DETAILS)
                .map(f -> f.label() + " (" + f.tokens() + " tokens)")
                .collect(Collectors.joining(", "));

        int remaining = growth.addedFragments().size()
                - Math.min(growth.addedFragments().size(), MAX_OVERFLOW_NOTE_FRAGMENT_DETAILS);
        if (remaining > 0) {
            details = details + ", ... and " + remaining + " more";
        }
        if (details.isBlank()) {
            details = "(no fragment details available)";
        }

        return "[HARNESS NOTE: Recovering from context overflow after checkpoint restore. Context grew by "
                + growth.netGrowthTokens()
                + " tokens since checkpoint. Added fragments: "
                + details
                + "]";
    }

    private long contextTokenCount(Context context) {
        return context.allFragments()
                .mapToLong(f -> Messages.getApproximateTokens(f.text().join()))
                .sum();
    }

    private String fragmentLabel(ContextFragment fragment) {
        var sourceFiles = fragment.sourceFiles().renderNowOr(Set.of());
        if (!sourceFiles.isEmpty()) {
            return sourceFiles.stream()
                    .map(pf -> pf.getRelPath().toString())
                    .sorted()
                    .collect(Collectors.joining(", "));
        }
        return fragment.description().renderNowOr("fragment " + fragment.id());
    }

    /**
     * Determines if the context contains enough droppable fragments added since the last successful
     * dropWorkspaceFragments call that stripping them would meaningfully reduce context pressure.
     *
     * <p>The threshold is based on tokens introduced by newly-added fragments since that last drop.</p>
     */
    private boolean isPruningWorthwhile(Context context) {
        // autoPinnedOnly is intentionally ignored under the "tokens added since last drop" heuristic.
        long addedTokens = context.allFragments()
                .filter(f -> !fragmentIdsAtLastDrop.contains(f.id()))
                .filter(f -> !isPinnedBySystem(f))
                .mapToLong(f -> Messages.getApproximateTokens(f.text().join()))
                .sum();

        return addedTokens >= 20_000;
    }

    private void recordDropBaseline(Context contextAfterDrop) {
        fragmentIdsAtLastDrop =
                contextAfterDrop.allFragments().map(ContextFragment::id).collect(Collectors.toSet());
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
        if (lastTurnContext == null || context.isEmpty()) {
            return 0.0;
        }

        var currFragments = context.allFragments().toList();
        var prevFragments = lastTurnContext.allFragments().toList();

        double prevTotal = 0.0;
        var prevWeightsById = new HashMap<String, Double>(prevFragments.size());
        for (var f : prevFragments) {
            double w = sqrtTokenWeight(f);
            prevWeightsById.put(f.id(), w);
            prevTotal += w;
        }

        double currTotal = 0.0;
        double intersectionWeight = 0.0;
        Set<String> matchedPrevIds = new HashSet<>();

        for (var f : currFragments) {
            double wCurr = sqrtTokenWeight(f);
            currTotal += wCurr;

            var prevMatchOpt = lastTurnContext.findWithSameSource(f);
            if (prevMatchOpt.isEmpty()) {
                continue;
            }

            var prevMatch = prevMatchOpt.get();
            if (!matchedPrevIds.add(prevMatch.id())) {
                continue;
            }

            double wPrev = prevWeightsById.getOrDefault(prevMatch.id(), sqrtTokenWeight(prevMatch));
            intersectionWeight += Math.min(wCurr, wPrev);
        }

        double unionWeight = prevTotal + currTotal - intersectionWeight;
        if (!(unionWeight > 0.0)) {
            return 1.0;
        }

        double stability = intersectionWeight / unionWeight;

        long workspaceTokens = currFragments.stream()
                .mapToLong(f -> Messages.getApproximateTokens(f.text().join()))
                .sum();
        double novelty = researchTokensEma <= 0.0 || workspaceTokens <= 0
                ? 0.0
                : (researchTokensEma / (researchTokensEma + workspaceTokens));

        return stability * (1.0 - novelty);
    }

    private long calculateResearchTokensAddedThisTurn(Context contextAtTurnStart, Context contextAtTurnEnd) {
        return ContextDelta.between(contextAtTurnStart, contextAtTurnEnd).join().addedFragments().stream()
                .mapToLong(f -> Messages.getApproximateTokens(f.text().join()))
                .sum();
    }

    private void recordResearchTokensForTurn(long researchTokensThisTurn) {
        researchTokensEma = RESEARCH_TOKENS_EMA_ALPHA * researchTokensThisTurn
                + (1.0 - RESEARCH_TOKENS_EMA_ALPHA) * researchTokensEma;
    }

    private boolean recordTurnForStagnation(
            Context contextAtTurnStart, Context contextAtTurnEnd, long researchTokensThisTurn) {
        if (researchTokensThisTurn != 0) {
            consecutiveStagnantTurns = 0;
            return false;
        }

        if (contextAtTurnStart.equals(contextAtTurnEnd)) {
            consecutiveStagnantTurns++;
        } else {
            ContextDelta delta =
                    ContextDelta.between(contextAtTurnStart, contextAtTurnEnd).join();
            if (delta.addedFragments().isEmpty()) {
                consecutiveStagnantTurns++;
            } else {
                consecutiveStagnantTurns = 0;
            }
        }

        if (consecutiveStagnantTurns < STAGNATION_TURNS_THRESHOLD) {
            return false;
        }

        consecutiveStagnantTurns = 0;
        logger.debug(
                "Search appears to have stalled (no new workspace additions and no new research across {} turns). Forcing completion.",
                STAGNATION_TURNS_THRESHOLD);
        return true;
    }

    private static double sqrtTokenWeight(ContextFragment fragment) {
        int tokens = Messages.getApproximateTokens(fragment.text().join());
        return Math.sqrt(Math.max(0, tokens));
    }

    private void startWorkspaceCompleteClassification(String modelResponse) {
        if (terminalDecisionRequestedByModel.get()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                if (RelevanceClassifier.isRelevant(scanLlm, READY_FOR_FINAL_TURN_FILTER, modelResponse)) {
                    finalTurnRequestedByClassifier.compareAndSet(false, true);
                }
            } catch (InterruptedException e) {
                throw new AssertionError();
            }
        });
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
        record Continue(
                Context contextAfterTurn,
                List<ChatMessage> sessionMessagesAfterTurn,
                Set<ProjectFile> presentedRelatedFiles)
                implements TurnOutcome {}

        record ForceFinalTurn(
                Context contextAfterTurn,
                List<ChatMessage> sessionMessagesAfterTurn,
                Set<ProjectFile> presentedRelatedFiles)
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
            List<ChatMessage> messages, List<ToolSpecification> toolSpecs, @Nullable Set<String> strictToolAllowList) {}

    private TaskResult finalizePendingTerminal(PendingTerminal pendingTerminal, Context context) {
        if ("abortSearch".equals(pendingTerminal.toolName())) {
            return errorResult(
                    new TaskResult.StopDetails(
                            TaskResult.StopReason.LLM_ABORTED, "Aborted: " + pendingTerminal.resultText()),
                    context);
        }
        if ("describeIssue".equals(pendingTerminal.toolName())) {
            return createResult(
                    context, new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS, pendingTerminal.resultText()));
        }
        if ("answer".equals(pendingTerminal.toolName()) || "askForClarification".equals(pendingTerminal.toolName())) {
            return createResult(
                    context, new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS, pendingTerminal.resultText()));
        }
        return createResult(context);
    }

    private TaskResult.TaskMeta taskMeta() {
        return new TaskResult.TaskMeta(TaskResult.Type.LUTZ, Service.ModelConfig.from(model, cm.getService()));
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
