package ai.brokk.agents;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.LlmOutputMeta;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextDelta;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextHistory;
import ai.brokk.metrics.SearchMetrics;
import ai.brokk.project.IProject;
import ai.brokk.project.ModelProperties;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.prompts.SearchPrompts.Objective;
import ai.brokk.prompts.WorkspacePrompts;
import ai.brokk.tools.SearchTools;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.tools.WorkspaceTools;
import ai.brokk.util.Json;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.data.message.AiMessage;
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
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

/**
 * By design, SearchAgent does not write to Context history; it survives in the mopMessages and in
 * the ToolExecutionResults it returns; we're explicitly trying to move the noise of the search
 * OUT of the calling agents' contexts.
 */
public class SearchAgent {
    private static final int MAX_TURNS = 20;

    private final IContextManager cm;
    private final String goal;
    private final Objective objective;
    private final IConsoleIO io;
    private final Llm llm;
    private final SearchTools searchTools;

    private final SearchMetrics metrics;

    private Context context;
    private @Nullable TaskResult.StopDetails terminalStopDetails;
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
        this.objective = objective;
        this.io = io;
        this.metrics = metrics != null
                ? metrics
                : "true".equalsIgnoreCase(System.getenv("BRK_COLLECT_METRICS"))
                        ? SearchMetrics.tracking()
                        : SearchMetrics.noOp();
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
        var toolRegistry = cm.getToolRegistry()
                .builder()
                .register(searchTools)
                .register(workspaceTools)
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
                long turnStartTime = System.currentTimeMillis();
                metrics.startTurn(turnStartTime);

                io.showTransientMessage("Brokk Search is preparing next actions...");
                var response = llm.sendRequest(messages, toolContext);

                long llmCompletedAtMs = System.currentTimeMillis();
                long llmTimeMs = llmCompletedAtMs - turnStartTime;
                var tokenUsage = response.metadata();
                int inputTokens = tokenUsage != null ? tokenUsage.inputTokens() : 0;
                int cachedTokens = tokenUsage != null ? tokenUsage.cachedInputTokens() : 0;
                int thinkingTokens = tokenUsage != null ? tokenUsage.thinkingTokens() : 0;
                int outputTokens = tokenUsage != null ? tokenUsage.outputTokens() : 0;
                metrics.recordLlmCall(
                        llmCompletedAtMs, llmTimeMs, inputTokens, cachedTokens, thinkingTokens, outputTokens);

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

                if (isFinalTurn && !containsTerminalTool(ai)) {
                    return errorResult(new TaskResult.StopDetails(
                            TaskResult.StopReason.TOOL_ERROR,
                            "Final turn requires a terminal tool call (for example, %s)."
                                    .formatted(terminalToolsForFinalTurn())));
                }

                var additionsThisTurn = new ArrayList<ContextFragment>();

                for (var request : ai.toolExecutionRequests()) {
                    metrics.recordToolCall(request.name());
                    io.beforeToolCall(request);
                    Context before = workspaceTools.getContext();
                    var toolResult = toolRegistry.executeTool(request);
                    io.afterToolOutput(toolResult);
                    messages.add(toolResult.toMessage());

                    context = workspaceTools.getContext();
                    additionsThisTurn.addAll(
                            ContextDelta.between(before, context).join().addedFragments());

                    if (toolResult.status() == ToolExecutionResult.Status.FATAL) {
                        return errorResult(
                                new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, toolResult.resultText()));
                    }

                    if (terminalStopDetails != null) {
                        return createResult(terminalStopDetails);
                    }
                }

                previousTurnAdditions = List.copyOf(additionsThisTurn);
            } finally {
                endTurnAndRecordFileChanges(filesBeforeSet, context, System.currentTimeMillis());
            }
        }

        return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.TURN_LIMIT, "Turn limit reached."));
    }

    private String buildTurnDirective(int turn, List<ContextFragment> previousTurnAdditions) {
        var workspaceToc = WorkspacePrompts.formatToc(context).trim();
        String tocContent = workspaceToc;
        if (workspaceToc.startsWith("<workspace_toc>") && workspaceToc.endsWith("</workspace_toc>")) {
            tocContent = workspaceToc.substring(
                    "<workspace_toc>".length(), workspaceToc.length() - "</workspace_toc>".length());
        }

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

        return """
                <turn>
                  <goal>%s</goal>
                %s
                  <next_tool_request>%s</next_tool_request>
                  <workspace_toc>%s</workspace_toc>
                </turn>
                """
                .formatted(goal, additionsBlock, nextToolRequest, tocContent);
    }

    private boolean containsTerminalTool(AiMessage message) {
        return message.toolExecutionRequests().stream().anyMatch(req -> isTerminalTool(req.name()));
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

    private void endTurnAndRecordFileChanges(
            Set<ProjectFile> filesBeforeSet, Context contextAfterTurn, long turnCompletedAtMs)
            throws InterruptedException {
        Set<ProjectFile> filesAfterSet = workspaceFiles(contextAfterTurn);
        Set<ProjectFile> added = new HashSet<>(filesAfterSet);
        added.removeAll(filesBeforeSet);
        metrics.recordFilesAdded(added.size());
        metrics.recordFilesAddedPaths(toRelativePaths(added));
        metrics.endTurn(turnCompletedAtMs, toRelativePaths(filesBeforeSet), toRelativePaths(filesAfterSet));
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
    public String abortSearch(@P("Reason for abort.") String explanation) {
        var details = jsonWithExplanation(explanation);
        terminalStopDetails = new TaskResult.StopDetails(TaskResult.StopReason.LLM_ABORTED, details);
        io.llmOutput(explanation, ChatMessageType.AI, LlmOutputMeta.DEFAULT);
        return details;
    }

    @Tool("Provide final answer.")
    public String answer(@P("Comprehensive final answer in Markdown.") String explanation) {
        var details = jsonWithExplanation(explanation);
        terminalStopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS, details);
        io.llmOutput("# Answer\n\n" + explanation, ChatMessageType.AI, LlmOutputMeta.newMessage());
        return details;
    }

    @Tool("Signal workspace preparation is complete.")
    public String workspaceComplete(
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
            return retryMessage;
        }

        workspaceCompleteRetryOffered = false;
        pendingWorkspaceCompleteIds = null;

        var details = jsonWithFragmentIds(List.copyOf(acceptedIds));
        terminalStopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS, details);
        io.llmOutput(details, ChatMessageType.AI, LlmOutputMeta.newMessage());
        return details;
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
