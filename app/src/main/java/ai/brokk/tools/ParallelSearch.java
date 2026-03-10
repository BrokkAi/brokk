package ai.brokk.tools;

import ai.brokk.ContextManager;
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

    private final IContextManager cm;
    private final StreamingChatModel planningModel;
    private final ContextManager.TaskScope scope;
    private final String goal;

    public ParallelSearch(
            IContextManager cm, StreamingChatModel planningModel, ContextManager.TaskScope scope, String goal) {
        this.cm = cm;
        this.planningModel = planningModel;
        this.scope = scope;
        this.goal = goal;
    }

    @Tool(
            "Invoke the Search Agent to find information relevant to the given query. The Search Agent explores the codebase to find relevant identifiers and files. It does NOT have access to Architect conversation history, so your query must include all necessary goal/context explicitly. Searching is slower than adding known files directly, but useful when you don't know exact names or locations.")
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

                return executeSearchRequest(req, new Context(cm), tr, taskIo);
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

                toolExecutionMessages.add(outcome.toolResult().toExecutionResultMessage());

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
                toolExecutionMessages.add(failure.toExecutionResultMessage());
            }
        }

        if (interrupted) {
            throw new InterruptedException();
        }

        var overallResult = firstFatalMessage == null
                ? new TaskResult(combinedContext, new TaskResult.StopDetails(StopReason.SUCCESS))
                : new TaskResult(combinedContext, new TaskResult.StopDetails(StopReason.LLM_ERROR, firstFatalMessage));

        var description =
                batchSize > 1 ? "Multiple concurrent searches" : extractQueryDescription(requests.getFirst(), tr);
        return new ParallelSearchResult(overallResult, toolExecutionMessages, io.getLlmRawMessages(), description);
    }

    private SearchTaskResult executeSearchRequest(
            ToolExecutionRequest request, Context searchStartContext, ToolRegistry tr, IConsoleIO io)
            throws InterruptedException {
        io.beforeToolCall(request);

        ToolExecutionResult toolResult;
        TaskResult taskResult;
        SearchPrompts.Objective objective = SearchPrompts.Objective.WORKSPACE_ONLY;
        List<ChatMessage> llmMessages = List.of();

        try {
            var validated = tr.validateTool(request);
            var parameters = validated.parameters();
            var query = (String) parameters.getFirst();
            var mode = (String) parameters.get(1);
            objective = parseSearchObjective(mode);

            logger.debug("callSearchAgent invoked with query: {}, mode: {}", query, mode);
            io.llmOutput("**Search Agent** engaged:\n" + query, ChatMessageType.CUSTOM, LlmOutputMeta.newMessage());

            var searchAgent = new SearchAgent(
                    searchStartContext, query, planningModel, objective, scope, io, SearchAgent.ScanConfig.noAppend());
            taskResult = searchAgent.execute();

            if (!taskResult.context().getTaskHistory().isEmpty()) {
                llmMessages = List.copyOf(
                        taskResult.context().getTaskHistory().getLast().mopMessages());
            }

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
        return new SearchTaskResult(toolResult, taskResult, objective, llmMessages);
    }

    private String marshalSearchResult(
            Context searchStartContext, TaskResult taskResult, SearchPrompts.Objective objective) {
        var lastEntry = taskResult.context().getTaskHistory().getLast();
        var reasoningSummary = lastEntry.description();

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
            TaskResult taskResult,
            List<ToolExecutionResultMessage> toolExecutionMessages,
            List<ChatMessage> llmRawMessages,
            String historyDescription) {}

    private record SearchTask(ToolExecutionRequest request, Future<SearchTaskResult> future) {}

    private record SearchTaskResult(
            ToolExecutionResult toolResult,
            TaskResult taskResult,
            SearchPrompts.Objective objective,
            List<ChatMessage> llmRawMessages) {}
}
