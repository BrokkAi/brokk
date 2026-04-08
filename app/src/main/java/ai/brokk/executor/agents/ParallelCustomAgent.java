package ai.brokk.executor.agents;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.LlmOutputMeta;
import ai.brokk.MutedConsoleIO;
import ai.brokk.TaskResult;
import ai.brokk.TaskResult.StopReason;
import ai.brokk.concurrent.LoggingFuture;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.util.Json;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Utility to execute multiple read-only custom agent requests in parallel.
 * Mirrors {@link ai.brokk.tools.ParallelSearch} but for custom agents.
 * Only agents whose tool lists are purely read-only (as determined by
 * {@link AgentDefinition#isReadOnly}) are eligible for parallel execution.
 */
public class ParallelCustomAgent {
    private static final Logger logger = LogManager.getLogger(ParallelCustomAgent.class);

    private final IContextManager cm;
    private final StreamingChatModel model;

    public ParallelCustomAgent(IContextManager cm, StreamingChatModel model) {
        this.cm = cm;
        this.model = model;
    }

    @Tool(
            """
            Invoke a custom agent by name to perform a specialized task.
            Custom agents are user-defined AI workflows with specific system prompts and tool sets.
            Use this when the task matches a known custom agent's specialty (e.g., security auditing, \
            architecture explanation, test gap analysis).
            The agent runs its own search-and-answer loop and returns its findings.""")
    public String callCustomAgent(
            @P("Name of the custom agent to invoke (e.g., 'security-auditor')") String agentName,
            @P("Complete task description for the agent") String task)
            throws InterruptedException {
        var agentStore = cm.getAgentStore();
        var agentDef = agentStore
                .get(agentName)
                .orElseThrow(() -> new IllegalArgumentException("Custom agent not found: '%s'. Available agents: %s"
                        .formatted(
                                agentName,
                                agentStore.list().stream()
                                        .map(AgentDefinition::name)
                                        .toList())));

        logger.info(
                "Invoking custom agent '{}' sequentially with model {}",
                agentName,
                cm.getService().nameOf(model));

        var executor = new CustomAgentExecutor(cm, agentDef, model);
        var result = executor.executeInterruptibly(task);
        return extractExplanation(result.stopDetails().explanation());
    }

    /**
     * Execute multiple custom agent requests in parallel on virtual threads.
     * All requests must target read-only agents; the caller is responsible for
     * filtering non-read-only agents out before calling this method.
     */
    public ParallelCustomAgentResult execute(List<ToolExecutionRequest> requests, ToolRegistry tr)
            throws InterruptedException {
        int batchSize = requests.size();
        IConsoleIO io = cm.getIo();
        IConsoleIO mutedIo = new MutedConsoleIO(io);
        AtomicBoolean echoInUse = new AtomicBoolean(false);

        if (batchSize > 1) {
            io.llmOutput(
                    "Custom Agent: running " + batchSize + " agents in parallel; only the first will stream.",
                    ChatMessageType.AI,
                    LlmOutputMeta.newMessage());
        }

        List<CustomAgentTask> tasks = new ArrayList<>();
        for (var req : requests) {
            var future = LoggingFuture.supplyCallableVirtual(() -> {
                IConsoleIO taskIo;
                if (batchSize == 1) {
                    taskIo = io;
                } else {
                    boolean echoClaimed = echoInUse.compareAndSet(false, true);
                    taskIo = echoClaimed ? io : mutedIo;
                }
                return executeCustomAgentRequest(req, tr, taskIo);
            });
            tasks.add(new CustomAgentTask(req, future));
        }

        boolean interrupted = false;
        String firstFatalMessage = null;
        boolean waitingMessageShown = false;
        var toolExecutionMessages = new ArrayList<ToolExecutionResultMessage>();
        var descriptions = new ArrayList<String>();

        for (int i = 0; i < batchSize; i++) {
            var task = tasks.get(i);
            try {
                if (interrupted) {
                    task.future().cancel(true);
                    continue;
                }

                CustomAgentTaskResult outcome = task.future().get();

                if (!waitingMessageShown && batchSize > 1) {
                    io.llmOutput(
                            "Waiting for the other " + (batchSize - 1) + " custom agents...",
                            ChatMessageType.AI,
                            LlmOutputMeta.DEFAULT);
                    waitingMessageShown = true;
                }

                toolExecutionMessages.add(outcome.toolResult().toMessage());
                descriptions.add(outcome.agentName());

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
                if (e.getCause() instanceof InterruptedException) {
                    interrupted = true;
                    for (int j = i + 1; j < batchSize; j++) {
                        tasks.get(j).future().cancel(true);
                    }
                    continue;
                }
                var errorMessage = "Error executing custom agent: %s"
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

        var description = batchSize > 1
                ? "Parallel custom agents: " + String.join(", ", descriptions)
                : descriptions.isEmpty() ? "Custom agent" : descriptions.getFirst();

        return new ParallelCustomAgentResult(stopDetails, toolExecutionMessages, description);
    }

    private CustomAgentTaskResult executeCustomAgentRequest(
            ToolExecutionRequest request, ToolRegistry tr, IConsoleIO taskIo) throws InterruptedException {
        taskIo.beforeToolCall(request);

        String agentName;
        String task;
        try {
            var validated = tr.validateTool(request);
            var parameters = validated.parameters();
            agentName = (String) parameters.getFirst();
            task = (String) parameters.get(1);
        } catch (RuntimeException e) {
            var errorMessage = "Failed to parse callCustomAgent arguments: " + e.getMessage();
            logger.debug(errorMessage, e);
            var failure = ToolExecutionResult.requestError(request, errorMessage);
            taskIo.afterToolOutput(failure);
            return new CustomAgentTaskResult(failure, "unknown");
        }

        var agentStore = cm.getAgentStore();
        var agentDef = agentStore.get(agentName).orElse(null);
        if (agentDef == null) {
            var errorMessage = "Custom agent not found: '%s'. Available agents: %s"
                    .formatted(
                            agentName,
                            agentStore.list().stream()
                                    .map(AgentDefinition::name)
                                    .toList());
            var failure = ToolExecutionResult.requestError(request, errorMessage);
            taskIo.afterToolOutput(failure);
            return new CustomAgentTaskResult(failure, agentName);
        }

        logger.info(
                "Invoking custom agent '{}' in parallel with model {}",
                agentName,
                cm.getService().nameOf(model));

        try {
            var executor = new CustomAgentExecutor(cm, agentDef, model, taskIo);
            var result = executor.executeInterruptibly(task);
            var explanation = extractExplanation(result.stopDetails().explanation());
            var toolResult = toToolExecutionResult(request, result.stopDetails(), explanation);
            taskIo.afterToolOutput(toolResult);
            return new CustomAgentTaskResult(toolResult, agentName);
        } catch (RuntimeException e) {
            var errorMessage = "Error executing custom agent '%s': %s"
                    .formatted(agentName, Objects.toString(e.getMessage(), "Unknown error"));
            logger.debug(errorMessage, e);
            var failure = ToolExecutionResult.requestError(request, errorMessage);
            taskIo.afterToolOutput(failure);
            return new CustomAgentTaskResult(failure, agentName);
        }
    }

    private static String extractExplanation(String raw) {
        try {
            var node = Json.getMapper().readTree(raw);
            var explanation = node.get("explanation");
            if (explanation != null && explanation.isTextual()) {
                return explanation.asText();
            }
        } catch (Exception ignored) {
            // Not JSON -- return as-is
        }
        return raw;
    }

    static ToolExecutionResult toToolExecutionResult(
            ToolExecutionRequest request, TaskResult.StopDetails stopDetails, String explanation)
            throws InterruptedException {
        return switch (stopDetails.reason()) {
            case LLM_ERROR -> ToolExecutionResult.fatal(request, explanation);
            case INTERRUPTED -> throw new InterruptedException();
            default -> ToolExecutionResult.success(request, explanation);
        };
    }

    /**
     * Extract the agent name from a callCustomAgent tool request's JSON arguments.
     * Returns null if parsing fails.
     */
    public static @Nullable String extractAgentName(ToolExecutionRequest request, ToolRegistry tr) {
        try {
            var validated = tr.validateTool(request);
            return (String) validated.parameters().getFirst();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public record ParallelCustomAgentResult(
            TaskResult.StopDetails stopDetails,
            List<ToolExecutionResultMessage> toolExecutionMessages,
            String historyDescription) {}

    private record CustomAgentTask(ToolExecutionRequest request, Future<CustomAgentTaskResult> future) {}

    private record CustomAgentTaskResult(ToolExecutionResult toolResult, String agentName) {}
}
