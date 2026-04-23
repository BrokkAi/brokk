package ai.brokk.executor.agents;

import ai.brokk.IAppContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.Llm;
import ai.brokk.LlmOutputMeta;
import ai.brokk.TaskResult;
import ai.brokk.concurrent.LoggingFuture;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.prompts.WorkspacePrompts;
import ai.brokk.tools.CodeQualityTools;
import ai.brokk.tools.DependencyTools;
import ai.brokk.tools.Destructive;
import ai.brokk.tools.SearchTools;
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
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.ContextTooLargeException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Blocking;

/**
 * Executes a custom agent defined by an {@link AgentDefinition}.
 * Replicates SearchAgent's agentic loop (tool registration, parallel execution,
 * terminal detection) but parameterized by user-supplied configuration.
 */
public class CustomAgentExecutor {
    private record TerminalStopOutput(String llmText, TaskResult.StopDetails stopDetails) implements ToolOutput {}

    private static final Set<String> TERMINAL_TOOL_NAMES = Set.of("answer", "abortSearch");
    private static final Set<String> PARALLEL_SAFE_SEARCH_TOOL_NAMES = AgentDefinition.PARALLEL_SAFE_SEARCH_TOOL_NAMES;

    private final IAppContextManager cm;
    private final AgentDefinition agentDef;
    private final Llm llm;
    private final SearchTools searchTools;
    private final IConsoleIO io;

    private Context context;

    public CustomAgentExecutor(IAppContextManager cm, AgentDefinition agentDef, StreamingChatModel model) {
        this(cm, agentDef, model, cm.getIo());
    }

    public CustomAgentExecutor(
            IAppContextManager cm, AgentDefinition agentDef, StreamingChatModel model, IConsoleIO io) {
        this.cm = cm;
        this.agentDef = agentDef;
        this.io = io;
        this.context = cm.liveContext();
        this.llm = cm.getLlm(new Llm.Options(model, agentDef.name(), TaskResult.Type.SEARCH).withEcho());
        this.llm.setOutput(io);
        this.searchTools = new SearchTools(cm);
    }

    @Blocking
    public TaskResult execute(String taskInput) {
        try {
            return executeInterruptibly(taskInput);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TaskResult(context, new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED));
        }
    }

    @Blocking
    public TaskResult executeInterruptibly(String taskInput) throws InterruptedException {
        return executeLoop(taskInput);
    }

    private TaskResult executeLoop(String taskInput) throws InterruptedException {
        var workspaceTools = new WorkspaceTools(context);
        var builder = cm.getToolRegistry()
                .builder()
                .register(searchTools)
                .register(workspaceTools)
                .register(this);
        if (DependencyTools.isSupported(cm.getProject())) {
            builder.register(new DependencyTools(cm));
        }
        builder.register(new CodeQualityTools(cm));
        var toolRegistry = builder.build();
        var allowedTools = agentDef.effectiveTools(cm.getProject());
        var toolContext = new ToolContext(toolRegistry.getTools(allowedTools), ToolChoice.REQUIRED, toolRegistry);

        var messages = new ArrayList<ChatMessage>();
        messages.add(new SystemMessage(agentDef.systemPrompt()));

        int maxTurns = agentDef.effectiveMaxTurns();
        var previousTurnAdditions = List.<ContextFragment>of();

        for (int turn = 1; turn <= maxTurns; turn++) {
            boolean isFinalTurn = turn == maxTurns;
            messages.add(new UserMessage(buildTurnDirective(turn, maxTurns, taskInput, previousTurnAdditions)));

            io.showTransientMessage("Custom agent '%s' preparing next actions...".formatted(agentDef.name()));
            var response = llm.sendRequest(messages, toolContext);

            if (response.error() != null) {
                if (response.error() instanceof ContextTooLargeException) {
                    return errorResult("Context limit exceeded");
                }
                return errorResult("Agent execution failed: " + response.error().getMessage());
            }

            var ai = ToolRegistry.removeDuplicateToolRequests(response.aiMessage());
            messages.add(ai);

            if (!ai.hasToolExecutionRequests()) {
                return errorResult("Model returned no tool calls.");
            }

            var terminalPartition = ToolRegistry.partitionByNames(ai.toolExecutionRequests(), TERMINAL_TOOL_NAMES);
            if (isFinalTurn && terminalPartition.matchingRequests().isEmpty()) {
                return errorResult("Final turn requires a terminal tool call (answer or abortSearch).");
            }

            var additionsThisTurn = new ArrayList<ContextFragment>();

            // Parallel execution for safe tools
            var parallelPartition =
                    ToolRegistry.partitionByNames(ai.toolExecutionRequests(), PARALLEL_SAFE_SEARCH_TOOL_NAMES);
            var parallelRequests = parallelPartition.matchingRequests();
            Map<ToolExecutionRequest, CompletableFuture<ToolExecutionResult>> parallelFutures = new LinkedHashMap<>();
            if (parallelRequests.size() > 1) {
                for (var request : parallelRequests) {
                    boolean destructive = toolRegistry.isToolAnnotated(request.name(), Destructive.class);
                    var approval = io.beforeToolCall(request, destructive);
                    if (!approval.isApproved()) {
                        parallelFutures.put(
                                request,
                                CompletableFuture.completedFuture(ToolExecutionResult.requestError(
                                        request, "Tool call '%s' was denied by user.".formatted(request.name()))));
                    } else {
                        Context snapshotContext = context;
                        parallelFutures.put(
                                request, LoggingFuture.supplyCallableVirtual(() -> ToolRegistry.fromBase(toolRegistry)
                                        .register(new WorkspaceTools(snapshotContext))
                                        .build()
                                        .executeTool(request)));
                    }
                }
            }
            Runnable cancelOutstandingParallelFutures = () ->
                    parallelFutures.values().stream().filter(f -> !f.isDone()).forEach(f -> f.cancel(true));

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
                        toolResult = ToolExecutionResult.internalError(request, msg == null ? "Unknown error" : msg);
                    }
                    io.afterToolOutput(toolResult);
                } else {
                    boolean destructive = toolRegistry.isToolAnnotated(request.name(), Destructive.class);
                    var approval = io.beforeToolCall(request, destructive);
                    if (!approval.isApproved()) {
                        toolResult = ToolExecutionResult.requestError(
                                request, "Tool call '%s' was denied by user.".formatted(request.name()));
                        io.afterToolOutput(toolResult);
                    } else {
                        var executionRegistry = ToolRegistry.fromBase(toolRegistry)
                                .register(new WorkspaceTools(context))
                                .build();
                        toolResult = executionRegistry.executeTool(request);
                        io.afterToolOutput(toolResult);
                    }
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
                    return errorResult(toolResult.resultText());
                }

                if (TERMINAL_TOOL_NAMES.contains(request.name())
                        && toolResult.result() instanceof TerminalStopOutput tso) {
                    cancelOutstandingParallelFutures.run();
                    return new TaskResult(context, tso.stopDetails());
                }
            }

            previousTurnAdditions = List.copyOf(additionsThisTurn);
        }

        return errorResult("Turn limit reached (%d turns).".formatted(maxTurns));
    }

    private String buildTurnDirective(
            int turn, int maxTurns, String taskInput, List<ContextFragment> previousTurnAdditions) {
        String toc = WorkspacePrompts.formatToc(context).trim();

        boolean finalTurn = turn == maxTurns;
        String nextToolRequest = finalTurn
                ? "This is the final turn. Call 'answer' or 'abortSearch' to finish."
                : "Call as many next tools in parallel as will most effectively advance your work.";

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

        if (turn == 1) {
            return """
                    <turn>
                    <task>%s</task>

                    %s

                    <next_tool_request>%s</next_tool_request>

                    %s
                    </turn>
                    """
                    .formatted(taskInput, additionsBlock, nextToolRequest, toc);
        }

        return """
                <turn>
                <goal>%s</goal>

                %s

                <next_tool_request>%s</next_tool_request>

                %s
                </turn>
                """
                .formatted(taskInput, additionsBlock, nextToolRequest, toc);
    }

    private TaskResult errorResult(String message) {
        return new TaskResult(context, new TaskResult.StopDetails(TaskResult.StopReason.TOOL_ERROR, message));
    }

    // ---- Terminal tools ----

    @Tool("Abort the agent immediately.")
    public TerminalStopOutput abortSearch(@P("Reason for abort.") String explanation) {
        var details = Json.toJson(Map.of("explanation", explanation));
        var stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.LLM_ABORTED, details);
        io.llmOutput(explanation, ChatMessageType.AI, LlmOutputMeta.DEFAULT);
        return new TerminalStopOutput(details, stopDetails);
    }

    @Tool("Provide final answer.")
    public TerminalStopOutput answer(@P("Comprehensive final answer in Markdown.") String explanation) {
        var details = Json.toJson(Map.of("explanation", explanation));
        var stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS, details);
        io.llmOutput("# Answer\n\n" + explanation, ChatMessageType.AI, LlmOutputMeta.newMessage());
        return new TerminalStopOutput(details, stopDetails);
    }
}
