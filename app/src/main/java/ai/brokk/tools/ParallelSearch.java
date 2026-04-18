package ai.brokk.tools;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.LlmOutputMeta;
import ai.brokk.MutedConsoleIO;
import ai.brokk.TaskResult;
import ai.brokk.TaskResult.StopReason;
import ai.brokk.agents.SearchAgent;
import ai.brokk.concurrent.LoggingFuture;
import ai.brokk.context.Context;
import ai.brokk.context.ContextDelta;
import ai.brokk.prompts.SearchPrompts;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility to execute multiple SearchAgent requests in parallel.
 */
public class ParallelSearch {
    private static final Logger logger = LogManager.getLogger(ParallelSearch.class);
    private static final String SEARCHAGENT_USES_PLANNER_ENV_VAR = "BRK_SEARCHAGENT_USES_PLANNER";

    private final IContextManager cm;
    private final Context ctx;
    private final String goal;
    private final StreamingChatModel delegatedSearchModel;

    public ParallelSearch(Context ctx, String goal, StreamingChatModel model) {
        this.ctx = ctx;
        this.cm = ctx.getContextManager();
        this.goal = goal;
        this.delegatedSearchModel = model;
    }

    public static boolean usePlannerModelForSearchAgent() {
        return "true".equalsIgnoreCase(System.getenv(SEARCHAGENT_USES_PLANNER_ENV_VAR));
    }

    StreamingChatModel delegatedSearchModel() {
        return delegatedSearchModel;
    }

    @Tool(
            """
                    Invoke the Search Agent to find relevant code, files, tests, or build/config context when the exact locations are not already known.

                    Use `callSearchAgent` when:
                    - the relevant symbols or files are unclear,
                    - there are multiple plausible places to look,
                    - you need to connect findings across code, tests, templates, resources, or build/config files before deciding what to add to the Workspace,
                    - or you want to explore several search branches in parallel before deciding what to add to the Workspace.

                    Direct search tools can be better for one-hop anchored lookup when you already have a concrete symbol, filename, or literal string.

                    Query guidance:
                    - Make the query self-contained.
                    - State the goal, what to find, and any likely locations, clues, or constraints.
                    - You can ask for either Workspace additions or a direct answer, depending on `mode`.
                    Use `mode="WORKSPACE"` to have Search Agent add relevant context to the Workspace.
                    Use `mode="ANSWER"` to get findings back without modifying the Workspace.

                    Prefer direct `add*ToWorkspace` tools instead when you already know exactly what you need to add.""")
    public String callSearchAgent(
            @P(
                            "A complete, explicit search request for SearchAgent in English (not just keywords). Do not rely on prior Architect conversation history; include the goal, constraints, relevant code locations, and what information you need.")
                    String query,
            @P(
                            "The search mode: WORKSPACE to add relevant fragments to the Workspace, or ANSWER to return an answer without modifying the Workspace.")
                    String mode) {
        throw new UnsupportedOperationException("do not invoke callSearchAgent directly, use ParallelSearch.execute");
    }

    public ParallelSearchResult execute(List<ToolExecutionRequest> requests, ToolRegistry tr)
            throws InterruptedException {
        int batchSize = requests.size();
        IConsoleIO io = cm.getIo();
        IConsoleIO mutedIo = new MutedConsoleIO(io);
        AtomicBoolean searchAgentEchoInUse = new AtomicBoolean(false);

        if (batchSize > 1) {
            io.llmOutput(
                    "Search Agent: running " + batchSize + " queries in parallel; only the first will stream.",
                    ChatMessageType.AI,
                    LlmOutputMeta.newMessage());
        }

        List<SearchTask> tasks = new ArrayList<>();
        for (var req : requests) {
            var future = LoggingFuture.supplyCallableVirtual(() -> {
                boolean echoClaimed;
                IConsoleIO taskIo;
                if (batchSize == 1) {
                    taskIo = io;
                } else {
                    // once claimed, echo stays claimed; for simplicity, we don't try to multiplex across agents
                    echoClaimed = searchAgentEchoInUse.compareAndSet(false, true);
                    taskIo = echoClaimed ? io : mutedIo;
                }

                return executeSearchRequest(req, ctx, tr, taskIo);
            });
            tasks.add(new SearchTask(req, future));
        }

        boolean interrupted = false;
        Context combinedContext = new Context(cm);
        String firstFatalMessage = null;
        boolean waitingMessageShown = false;
        var toolExecutionMessages = new ArrayList<ToolExecutionResultMessage>();

        for (int i = 0; i < batchSize; i++) {
            var task = tasks.get(i);
            try {
                if (interrupted) {
                    task.future().cancel(true);
                    continue;
                }

                SearchTaskResult outcome = task.future().get();

                if (!waitingMessageShown && batchSize > 1) {
                    io.llmOutput(
                            "Waiting for the other " + (batchSize - 1) + " SearchAgents...",
                            ChatMessageType.AI,
                            LlmOutputMeta.DEFAULT);
                    waitingMessageShown = true;
                }

                var outcomeContext = outcome.taskResult().context();
                if (outcome.objective() == SearchPrompts.Objective.WORKSPACE_ONLY) {
                    combinedContext = combinedContext.addFragments(
                            outcomeContext.allFragments().toList());
                }

                toolExecutionMessages.add(outcome.toolResult().toMessage());

                if (outcome.toolResult().status() == ToolExecutionResult.Status.FATAL && firstFatalMessage == null) {
                    firstFatalMessage = outcome.toolResult().resultText();
                    for (int j = i + 1; j < batchSize; j++) {
                        tasks.get(j).future().cancel(true);
                    }
                    break;
                }
            } catch (InterruptedException e) {
                interrupted = true;
                for (int j = i; j < batchSize; j++) {
                    tasks.get(j).future().cancel(true);
                }
            } catch (ExecutionException e) {
                var errorMessage = "Error executing Search Agent: %s"
                        .formatted(Objects.toString(
                                e.getCause() != null ? e.getCause().getMessage() : "Unknown error"));
                logger.debug(errorMessage, e);
                var failure = ToolExecutionResult.requestError(task.request(), errorMessage);
                toolExecutionMessages.add(failure.toMessage());
            }
        }

        if (interrupted) {
            throw new InterruptedException();
        }

        var stopDetails = firstFatalMessage == null
                ? new TaskResult.StopDetails(StopReason.SUCCESS)
                : new TaskResult.StopDetails(StopReason.LLM_ERROR, firstFatalMessage);

        var description =
                batchSize > 1 ? "Multiple concurrent searches" : extractQueryDescription(requests.getFirst(), tr);
        var mopMessages = List.copyOf(io.getLlmRawMessages());
        return new ParallelSearchResult(combinedContext, stopDetails, toolExecutionMessages, mopMessages, description);
    }

    private SearchTaskResult executeSearchRequest(
            ToolExecutionRequest request, Context searchStartContext, ToolRegistry tr, IConsoleIO io) {
        io.beforeToolCall(request, false);

        ToolExecutionResult toolResult;
        TaskResult taskResult;
        SearchPrompts.Objective objective = SearchPrompts.Objective.WORKSPACE_ONLY;

        try {
            var validated = tr.validateTool(request);
            var parameters = validated.parameters();
            var query = (String) parameters.getFirst();
            var mode = (String) parameters.get(1);
            objective = parseSearchObjective(mode);

            logger.debug("callSearchAgent invoked with query: {}, mode: {}", query, mode);
            io.llmOutput("**Search Agent** engaged:\n" + query, ChatMessageType.CUSTOM, LlmOutputMeta.newMessage());

            var searchAgent = new SearchAgent(searchStartContext, query, delegatedSearchModel, objective, io);
            taskResult = searchAgent.execute();

            if (taskResult.stopDetails().reason() == StopReason.LLM_ERROR) {
                toolResult = ToolExecutionResult.fatal(
                        request, taskResult.stopDetails().explanation());
            } else if (taskResult.stopDetails().reason() != StopReason.SUCCESS) {
                logger.debug("SearchAgent returned non-success for query {}: {}", query, taskResult.stopDetails());
                toolResult = ToolExecutionResult.success(
                        request, taskResult.stopDetails().toString());
            } else {
                var resultText = marshalSearchResult(searchStartContext, taskResult, objective);
                logger.debug(resultText);
                toolResult = ToolExecutionResult.success(request, resultText);
            }
        } catch (RuntimeException e) {
            var errorMessage = "Error executing Search Agent: " + Objects.toString(e.getMessage(), "Unknown error");
            logger.debug(errorMessage, e);
            taskResult = new TaskResult(
                    searchStartContext, new TaskResult.StopDetails(StopReason.PARSE_ERROR, errorMessage));
            toolResult = ToolExecutionResult.requestError(request, errorMessage);
        }

        io.afterToolOutput(toolResult);
        return new SearchTaskResult(toolResult, taskResult, objective);
    }

    private String marshalSearchResult(
            Context searchStartContext, TaskResult taskResult, SearchPrompts.Objective objective) {
        var lastEntry = taskResult.context().getTaskHistory().isEmpty()
                ? null
                : taskResult.context().getTaskHistory().getLast();
        var reasoningSummary = lastEntry != null ? lastEntry.description() : "(No reasoning provided)";

        if (objective == SearchPrompts.Objective.WORKSPACE_ONLY) {
            var delta = ContextDelta.between(
                            searchStartContext, taskResult.context().clearHistory())
                    .join();
            var addedFragmentList = delta.addedFragments().stream()
                    .map(f -> "- " + f.shortDescription().join())
                    .collect(Collectors.joining("\n"));
            return """
                    # Search results
                    Search Agent successfully completed; the workspace has been updated as requested.

                    ## Reasoning summary
                    %s

                    ## Added fragments
                    %s
                    """
                    .formatted(reasoningSummary, addedFragmentList.isEmpty() ? "(None)" : addedFragmentList);
        }

        return """
                %s

                ## Reasoning summary
                %s
                """
                .formatted(taskResult.stopDetails().explanation(), reasoningSummary);
    }

    private String extractQueryDescription(ToolExecutionRequest request, ToolRegistry tr) {
        try {
            var validated = tr.validateTool(request);
            var parameters = validated.parameters();
            return (String) parameters.getFirst();
        } catch (RuntimeException e) {
            logger.debug("Failed to parse single-search query for history; using goal", e);
            return goal;
        }
    }

    private static SearchPrompts.Objective parseSearchObjective(String mode) {
        return switch (mode.toUpperCase(Locale.ROOT)) {
            case "ANSWER" -> SearchPrompts.Objective.ANSWER_ONLY;
            default -> SearchPrompts.Objective.WORKSPACE_ONLY;
        };
    }

    public record ParallelSearchResult(
            Context context,
            TaskResult.StopDetails stopDetails,
            List<ToolExecutionResultMessage> toolExecutionMessages,
            List<ChatMessage> mopMessages,
            String historyDescription) {}

    private record SearchTask(ToolExecutionRequest request, Future<SearchTaskResult> future) {}

    private record SearchTaskResult(
            ToolExecutionResult toolResult, TaskResult taskResult, SearchPrompts.Objective objective) {}
}
