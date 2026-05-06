package ai.brokk.agents;

import ai.brokk.IAppContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.Llm;
import ai.brokk.LlmOutputMeta;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.concurrent.LoggingFuture;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextHistory;
import ai.brokk.executor.agents.CustomAgentTools;
import ai.brokk.metrics.SearchMetrics;
import ai.brokk.project.IProject;
import ai.brokk.project.ModelProperties;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.prompts.SearchPrompts.Objective;
import ai.brokk.prompts.WorkspacePrompts;
import ai.brokk.tools.SearchTools;
import ai.brokk.tools.ToolExecutionHelper;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolOutput;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.tools.WorkspaceTools;
import ai.brokk.util.Json;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.ContextTooLargeException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

/**
 * By design, SearchAgent does not write to Context history; it survives in the mopMessages and in
 * the ToolExecutionResults it returns; we're explicitly trying to move the noise of the search
 * OUT of the calling agents' contexts.
 */
public class SearchAgent {
    private record TerminalStopOutput(String llmText, TaskResult.StopDetails stopDetails) implements ToolOutput {}

    private static final int MAX_TURNS = 20;
    private static final Set<String> TERMINAL_TOOL_NAMES = Set.of("answer", "workspaceComplete", "abortSearch");
    private static final Set<String> PARALLEL_SAFE_SEARCH_TOOL_NAMES = Set.of(
            "searchSymbols",
            "scanUsages",
            "getSymbolLocations",
            "getSummaries",
            "skimFiles",
            "findFilesContaining",
            "findFilenames",
            "searchFileContents",
            "searchGitCommitMessages",
            "getGitLog",
            "explainCommit",
            "xmlSkim",
            "xmlSelect",
            "jq");

    private final IAppContextManager cm;
    private final String goal;
    private final StreamingChatModel model;
    private final Objective objective;
    private final IConsoleIO io;
    private final Llm llm;
    private final SearchTools searchTools;
    private final Context initialContext;

    private final SearchMetrics metrics;

    private Context context;
    private @Nullable LinkedHashSet<String> pendingWorkspaceCompleteIds;
    private boolean workspaceCompleteRetryOffered;

    public SearchAgent(Context initialContext, String goal, Objective objective, IConsoleIO io) {
        this(
                initialContext,
                goal,
                initialContext.getContextManager().getService().getModel(ModelProperties.ModelType.SEARCH),
                objective,
                io);
    }

    public SearchAgent(Context initialContext, String goal, StreamingChatModel model, Objective objective) {
        this(
                initialContext,
                goal,
                model,
                objective,
                initialContext.getContextManager().getIo());
    }

    public SearchAgent(
            Context initialContext, String goal, StreamingChatModel model, Objective objective, IConsoleIO io) {
        this(initialContext, goal, model, objective, io, null);
    }

    SearchAgent(
            Context initialContext,
            String goal,
            StreamingChatModel model,
            Objective objective,
            IConsoleIO io,
            @Nullable SearchMetrics metrics) {
        if (objective != Objective.ANSWER_ONLY && objective != Objective.WORKSPACE_ONLY) {
            throw new IllegalArgumentException("SearchAgent only supports ANSWER_ONLY and WORKSPACE_ONLY objectives");
        }
        this.cm = initialContext.getContextManager();
        this.goal = goal;
        this.model = model;
        this.objective = objective;
        this.io = io;
        this.metrics = metrics != null
                ? metrics
                : "true".equalsIgnoreCase(System.getenv("BRK_COLLECT_METRICS"))
                        ? SearchMetrics.tracking()
                        : SearchMetrics.noOp();
        this.initialContext = initialContext;
        this.context = initialContext;
        this.llm = cm.getLlm(new Llm.Options(model, goal, TaskResult.Type.SEARCH).withEcho());
        this.llm.setOutput(io);
        this.searchTools = new SearchTools(cm);
    }

    public TaskResult execute() {
        TaskResult result;
        try {
            result = executeSearch();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = interruptedResult();
        }

        if (metrics instanceof SearchMetrics.Tracking) {
            var json = metrics.toJson(goal, result.stopDetails().reason() == TaskResult.StopReason.SUCCESS);
            System.err.println("\nBRK_SEARCHAGENT_METRICS=" + json);
        }

        return result;
    }

    private TaskResult interruptedResult() {
        try {
            return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TaskResult(context, new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED));
        }
    }

    private TaskResult executeSearch() throws InterruptedException {
        var workspaceTools = new WorkspaceTools(context);
        var customAgentTools = new CustomAgentTools(cm, model);
        var toolRegistry = cm.getToolRegistry()
                .builder()
                .register(searchTools)
                .register(workspaceTools)
                .register(customAgentTools)
                .register(this)
                .build();
        var allowedTools = allowedToolNames(cm.getProject());
        var toolContext = new ToolContext(toolRegistry.getTools(allowedTools), ToolChoice.REQUIRED, toolRegistry);

        var messages = new ArrayList<ChatMessage>();
        messages.add(SearchPrompts.instance.searchSystemPrompt(context, objective, allowedTools));

        var previousTurnAdditions = List.<ContextFragment>of();

        for (int turn = 1; turn <= MAX_TURNS; turn++) {
            boolean isFinalTurn = turn == MAX_TURNS;
            messages.add(new UserMessage(buildTurnDirective(turn, previousTurnAdditions)));

            Set<ProjectFile> filesBeforeSet = workspaceFiles(context);
            try {
                metrics.startTurn();
                long turnStartTime = System.currentTimeMillis();

                io.showTransientMessage("Brokk Search is preparing next actions...");
                var response = llm.sendRequest(messages, toolContext);

                long llmTimeMs = System.currentTimeMillis() - turnStartTime;
                var tokenUsage = response.metadata();
                int inputTokens = tokenUsage != null ? tokenUsage.inputTokens() : 0;
                int cachedTokens = tokenUsage != null ? tokenUsage.cachedInputTokens() : 0;
                int thinkingTokens = tokenUsage != null ? tokenUsage.thinkingTokens() : 0;
                int outputTokens = tokenUsage != null ? tokenUsage.outputTokens() : 0;
                metrics.recordLlmCall(llmTimeMs, inputTokens, cachedTokens, thinkingTokens, outputTokens);

                if (response.error() != null) {
                    if (response.error() instanceof ContextTooLargeException) {
                        return failContextTooLarge();
                    }
                    return errorResult(new TaskResult.StopDetails(
                            TaskResult.StopReason.LLM_ERROR,
                            "Search planning failed: " + response.error().getMessage()));
                }

                var ai = ToolRegistry.removeDuplicateToolRequests(response.aiMessage());
                messages.add(ai);

                if (!ai.hasToolExecutionRequests()) {
                    return errorResult(new TaskResult.StopDetails(
                            TaskResult.StopReason.TOOL_ERROR, "Model returned no tool calls."));
                }

                var terminalPartition = ToolRegistry.partitionByNames(ai.toolExecutionRequests(), TERMINAL_TOOL_NAMES);
                if (isFinalTurn && terminalPartition.matchingRequests().isEmpty()) {
                    return errorResult(new TaskResult.StopDetails(
                            TaskResult.StopReason.TOOL_ERROR,
                            "Final turn requires a terminal tool call (for example, %s)."
                                    .formatted(terminalToolsForFinalTurn())));
                }

                var additionsThisTurn = new ArrayList<ContextFragment>();
                var parallelPartition =
                        ToolRegistry.partitionByNames(ai.toolExecutionRequests(), PARALLEL_SAFE_SEARCH_TOOL_NAMES);
                var parallelRequests = parallelPartition.matchingRequests();
                Map<ToolExecutionRequest, CompletableFuture<ToolExecutionResult>> parallelFutures =
                        new LinkedHashMap<>();
                if (parallelRequests.size() > 1) {
                    for (var request : parallelRequests) {
                        metrics.recordToolCall(request.name());
                        Context snapshotContext = context;
                        parallelFutures.put(request, LoggingFuture.supplyCallableVirtual(() -> {
                            var registry = ToolRegistry.fromBase(toolRegistry)
                                    .register(new WorkspaceTools(snapshotContext))
                                    .build();
                            return ToolExecutionHelper.executeWithApproval(io, registry, request);
                        }));
                    }
                }
                Runnable cancelOutstandingParallelFutures = () -> parallelFutures.values().stream()
                        .filter(f -> !f.isDone())
                        .forEach(f -> f.cancel(true));

                for (var request : ai.toolExecutionRequests()) {
                    ToolExecutionResult toolResult;
                    if (parallelFutures.containsKey(request)) {
                        try {
                            toolResult = parallelFutures.get(request).join();
                        } catch (CompletionException e) {
                            Throwable cause = e.getCause();
                            if (cause instanceof InterruptedException ie) {
                                throw ie;
                            }
                            String msg = cause == null ? "Unknown error" : cause.getMessage();
                            toolResult =
                                    ToolExecutionResult.internalError(request, msg == null ? "Unknown error" : msg);
                            io.afterToolOutput(toolResult);
                        }
                    } else {
                        metrics.recordToolCall(request.name());
                        var executionRegistry = ToolRegistry.fromBase(toolRegistry)
                                .register(new WorkspaceTools(context))
                                .build();
                        toolResult = ToolExecutionHelper.executeWithApproval(io, executionRegistry, request);
                    }
                    llm.recordToolExecution(toolResult);
                    messages.add(toolResult.toMessage());

                    if (toolResult.result() instanceof WorkspaceTools.WorkspaceMutationOutput output) {
                        context = output.context();
                        additionsThisTurn.addAll(output.addedFragments());
                    } else if (toolResult.result() instanceof WorkspaceTools.DropWorkspaceOutput output) {
                        context = output.context();
                        additionsThisTurn.addAll(output.addedFragments());
                    }

                    if (toolResult.status() == ToolExecutionResult.Status.FATAL) {
                        cancelOutstandingParallelFutures.run();
                        return errorResult(
                                new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, toolResult.resultText()));
                    }

                    if (isTerminalTool(request.name()) && toolResult.result() instanceof TerminalStopOutput tso) {
                        cancelOutstandingParallelFutures.run();
                        return createResult(tso.stopDetails());
                    }
                }

                previousTurnAdditions = List.copyOf(additionsThisTurn);
            } finally {
                endTurnAndRecordFileChanges(filesBeforeSet, context);
            }
        }

        return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.TURN_LIMIT, "Turn limit reached."));
    }

    String buildTurnDirective(int turn, List<ContextFragment> previousTurnAdditions) {
        String toc = WorkspacePrompts.formatToc(context)
                .replace(
                        "Here is a list of the full contents of the Workspace that you can refer to above.",
                        "Here is the full Workspace TOC, including the starting fragments and anything you have added so far.")
                .trim();

        boolean finalTurn = turn == MAX_TURNS;
        String nextToolRequest = finalTurn
                ? "This is the final turn. Make a terminal call to finish: %s.".formatted(terminalToolsForFinalTurn())
                : "Call as many next tools in parallel as will most effectively advance the search.";

        var additions =
                previousTurnAdditions.stream().map(ContextFragment::format).collect(Collectors.joining("\n"));
        var additionsBlock = previousTurnAdditions.isEmpty()
                ? ""
                : """
                  <previous_turn_additions>
                  %s
                  </previous_turn_additions>
                  """
                        .formatted(additions);

        var initialSummary = formatInitialWorkspaceSummary();

        return """
                <turn>
                %s

                <goal>%s</goal>

                %s

                <next_tool_request>%s</next_tool_request>

                %s
                </turn>
                """
                .formatted(initialSummary, goal, additionsBlock, nextToolRequest, toc);
    }

    private String formatInitialWorkspaceSummary() {
        var summaries = initialContext.getAllFragmentsInDisplayOrder().stream()
                .map(ContextFragment::formatSummary)
                .collect(Collectors.joining("\n"));
        var body = summaries.isBlank() ? "No starting fragments were provided." : summaries;
        return """
                <initial_workspace_summary>
                These starting workspace fragments have been summarized for brevity.
                If you need full source, call addFilesToWorkspace / addClassesToWorkspace.
                %s
                </initial_workspace_summary>
                """
                .formatted(body);
    }

    private boolean isTerminalTool(String toolName) {
        return switch (toolName) {
            case "abortSearch" -> true;
            case "answer", "workspaceComplete" -> true;
            default -> false;
        };
    }

    private String terminalToolsForFinalTurn() {
        return switch (objective) {
            case ANSWER_ONLY -> "answer, abortSearch";
            case WORKSPACE_ONLY -> "workspaceComplete, abortSearch";
            default -> "answer, workspaceComplete, abortSearch";
        };
    }

    private List<String> allowedToolNames(IProject project) {
        var names = new ArrayList<String>();

        names.add("searchSymbols");
        names.add("scanUsages");
        names.add("getSymbolLocations");
        names.add("getSummaries");
        names.add("skimFiles");
        names.add("findFilesContaining");
        names.add("findFilenames");
        names.add("searchFileContents");

        names.add("addClassesToWorkspace");
        names.add("addClassSummariesToWorkspace");
        names.add("addMethodsToWorkspace");
        names.add("addFileSummariesToWorkspace");
        names.add("addLineRangeToWorkspace");
        names.add("addFilesToWorkspace");
        names.add("addUrlContentsToWorkspace");

        if (project.hasGit()) {
            names.add("searchGitCommitMessages");
            names.add("getGitLog");
            names.add("explainCommit");
        }

        if (project.getAllFiles().stream().anyMatch(f -> f.extension().equals("xml"))) {
            names.add("xmlSkim");
            names.add("xmlSelect");
        }
        if (project.getAllFiles().stream().anyMatch(f -> f.extension().equals("json"))) {
            names.add("jq");
        }

        if (objective == Objective.ANSWER_ONLY) {
            names.add("answer");
        } else {
            names.add("workspaceComplete");
        }
        names.add("abortSearch");

        if (!cm.getAgentStore().list().isEmpty()) {
            names.add("callCustomAgent");
        }

        return WorkspaceTools.filterByAnalyzerAvailability(names, project);
    }

    private TaskResult failContextTooLarge() throws InterruptedException {
        var explanation =
                """
                Context limit exceeded while planning tool calls.

                Workspace TOC at failure:
                %s
                """
                        .formatted(WorkspacePrompts.formatToc(context));
        return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.LLM_CONTEXT_SIZE, explanation));
    }

    private TaskResult createResult(TaskResult.StopDetails details) throws InterruptedException {
        recordFinalWorkspaceState(context);
        metrics.recordOutcome(details.reason(), workspaceFiles(context).size());
        return new TaskResult(context, details);
    }

    private TaskResult errorResult(TaskResult.StopDetails details) throws InterruptedException {
        recordFinalWorkspaceState(context);
        metrics.recordOutcome(details.reason(), workspaceFiles(context).size());
        return new TaskResult(context, details);
    }

    private void endTurnAndRecordFileChanges(Set<ProjectFile> filesBeforeSet, Context contextAfterTurn)
            throws InterruptedException {
        Set<ProjectFile> filesAfterSet = workspaceFiles(contextAfterTurn);
        Set<ProjectFile> added = new HashSet<>(filesAfterSet);
        added.removeAll(filesBeforeSet);
        metrics.recordFilesAdded(added.size());
        metrics.recordFilesAddedPaths(toRelativePaths(added));
        metrics.endTurn(toRelativePaths(filesBeforeSet), toRelativePaths(filesAfterSet));
    }

    private void recordFinalWorkspaceState(Context context) throws InterruptedException {
        metrics.recordFinalWorkspaceFiles(toRelativePaths(workspaceFiles(context)));
        metrics.recordFinalWorkspaceFragments(getWorkspaceFragments(context));
    }

    private Set<ProjectFile> workspaceFiles(Context context) throws InterruptedException {
        context.awaitContentsAreComputed(ContextHistory.SNAPSHOT_AWAIT_TIMEOUT);
        return context.allFragments()
                .filter(f1 -> f1.getType().includeInProjectGuide())
                .flatMap(f -> f.sourceFiles().renderNowOr(Set.of()).stream())
                .collect(Collectors.toSet());
    }

    private Set<String> toRelativePaths(Set<ProjectFile> files) {
        return files.stream().map(pf -> pf.getRelPath().toString()).collect(Collectors.toSet());
    }

    private List<SearchMetrics.FragmentInfo> getWorkspaceFragments(Context context) throws InterruptedException {
        context.awaitContentsAreComputed(ContextHistory.SNAPSHOT_AWAIT_TIMEOUT);
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

    @Tool("Abort search immediately.")
    public TerminalStopOutput abortSearch(@P("Reason for abort.") String explanation) {
        var details = jsonWithExplanation(explanation);
        var stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.LLM_ABORTED, details);
        io.llmOutput(explanation, ChatMessageType.AI, LlmOutputMeta.DEFAULT);
        return new TerminalStopOutput(details, stopDetails);
    }

    @Tool("Provide final answer.")
    public TerminalStopOutput answer(@P("Comprehensive final answer in Markdown.") String explanation) {
        var details = jsonWithExplanation(explanation);
        var stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS, details);
        io.llmOutput("# Answer\n\n" + explanation, ChatMessageType.AI, LlmOutputMeta.newMessage());
        return new TerminalStopOutput(details, stopDetails);
    }

    @Tool("Signal workspace preparation is complete.")
    public ToolOutput workspaceComplete(
            @P("Selected workspace fragments as IDs, or exact fragment descriptions.")
                    List<String> fragmentIdsOrDescriptions) {
        var acceptedIds = new LinkedHashSet<String>();
        if (pendingWorkspaceCompleteIds != null) {
            acceptedIds.addAll(pendingWorkspaceCompleteIds);
        }

        var resolved = resolveFragmentSelections(fragmentIdsOrDescriptions);
        acceptedIds.addAll(resolved.acceptedIds());

        if (!resolved.badSelections().isEmpty() && !workspaceCompleteRetryOffered) {
            workspaceCompleteRetryOffered = true;
            pendingWorkspaceCompleteIds = new LinkedHashSet<>(acceptedIds);

            var retryMessage =
                    """
                    Invalid fragment selections: %s
                    Accepted fragment IDs: %s
                    Accepted selections are saved and do not need to be repeated.
                    Call workspaceComplete one more time with only corrected selections.
                    """
                            .formatted(resolved.badSelections(), acceptedIds);
            io.llmOutput(retryMessage, ChatMessageType.AI, LlmOutputMeta.DEFAULT);
            return new ToolOutput.TextOutput(retryMessage);
        }

        workspaceCompleteRetryOffered = false;
        pendingWorkspaceCompleteIds = null;

        var details = jsonWithFragmentIds(List.copyOf(acceptedIds));
        var stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS, details);
        // The wrapping workspaceComplete tool_call_update already produces a compact
        // "Workspace ready" card via displayTitle — emitting an extra banner here just
        // duplicates the entry with a verbose body.
        return new TerminalStopOutput(details, stopDetails);
    }

    private record FragmentSelectionResolution(List<String> acceptedIds, List<String> badSelections) {}

    private FragmentSelectionResolution resolveFragmentSelections(List<String> supplied) {
        var idLookup = context.getAllFragmentsInDisplayOrder().stream()
                .collect(Collectors.toMap(ContextFragment::id, ContextFragment::id, (a, b) -> a, LinkedHashMap::new));

        var descriptionToIds = new LinkedHashMap<String, List<String>>();
        for (var fragment : context.getAllFragmentsInDisplayOrder()) {
            descriptionToIds
                    .computeIfAbsent(fragment.description().join(), k -> new ArrayList<>())
                    .add(fragment.id());
        }

        var accepted = new LinkedHashSet<String>();
        var bad = new ArrayList<String>();

        for (var selection : supplied) {
            var token = selection.trim();
            if (token.isEmpty()) {
                bad.add(selection);
                continue;
            }

            var byId = idLookup.get(token);
            if (byId != null) {
                accepted.add(byId);
                continue;
            }

            var matchingIds = descriptionToIds.getOrDefault(token, List.of());
            if (matchingIds.size() == 1) {
                accepted.add(matchingIds.getFirst());
                continue;
            } else if (matchingIds.size() > 1) {
                bad.add(token + " (ambiguous description, matches multiple IDs: " + matchingIds + ")");
                continue;
            }

            bad.add(token);
        }

        return new FragmentSelectionResolution(List.copyOf(accepted), List.copyOf(bad));
    }

    private static String jsonWithExplanation(String explanation) {
        return Json.toJson(Map.of("explanation", explanation));
    }

    private static String jsonWithFragmentIds(List<String> fragmentIds) {
        return Json.toJson(Map.of("fragment_ids", fragmentIds));
    }
}
