package ai.brokk.executor.agents;

import ai.brokk.IAppContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.LlmOutputMeta;
import ai.brokk.MutedConsoleIO;
import ai.brokk.TaskResult;
import ai.brokk.TaskResult.StopReason;
import ai.brokk.concurrent.LoggingFuture;
import ai.brokk.executor.jobs.ChildAgentArtifact;
import ai.brokk.executor.jobs.ChildAgentArtifactSink;
import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.executor.jobs.ResponseSchemaRegistry;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
    public static final Set<String> TOOL_NAMES = Set.of("callCustomAgent", "callCustomAgentWithSchema");

    private final IAppContextManager cm;
    private final StreamingChatModel model;
    private final ResponseSchemaRegistry responseSchemaRegistry;
    private final ChildAgentArtifactSink childAgentArtifactSink;

    public ParallelCustomAgent(IAppContextManager cm, StreamingChatModel model) {
        this(cm, model, ResponseSchemaRegistry.empty());
    }

    public ParallelCustomAgent(
            IAppContextManager cm, StreamingChatModel model, ResponseSchemaRegistry responseSchemaRegistry) {
        this(cm, model, responseSchemaRegistry, ChildAgentArtifactSink.noop());
    }

    public ParallelCustomAgent(
            IAppContextManager cm,
            StreamingChatModel model,
            ResponseSchemaRegistry responseSchemaRegistry,
            ChildAgentArtifactSink childAgentArtifactSink) {
        this.cm = cm;
        this.model = model;
        this.responseSchemaRegistry = responseSchemaRegistry;
        this.childAgentArtifactSink = childAgentArtifactSink;
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
        var result = executeCustomAgent(agentName, task, null);
        return extractExplanation(result.stopDetails().explanation());
    }

    @Tool(
            """
            Invoke a custom agent by name and require its final answer to match a JSON response schema.
            Use this when the caller needs a typed child-agent result instead of Markdown findings.
            The agent still uses its normal tools to gather context, then produces one schema-constrained final result.
            Summarize child dispatch outcomes in your final answer; do not reconstruct or relay the child JSON.""")
    public String callCustomAgentWithSchema(
            @P("Name of the custom agent to invoke (e.g., 'security-auditor')") String agentName,
            @P("Complete task description for the agent") String task,
            @P("Name of an available parent response schema") String responseSchemaName)
            throws InterruptedException {
        JobSpec.ResponseSchema resolvedSchema;
        try {
            resolvedSchema = CustomAgentResponseSchemaResolver.resolve(responseSchemaName, responseSchemaRegistry);
        } catch (ToolRegistry.ToolValidationException e) {
            throw new ToolRegistry.ToolCallException(
                    ToolExecutionResult.Status.REQUEST_ERROR,
                    Objects.requireNonNullElse(e.getMessage(), "Invalid responseSchemaName"));
        }
        var result = executeCustomAgent(agentName, task, resolvedSchema);
        if (result.stopDetails().reason() != StopReason.SUCCESS) {
            throw new ToolRegistry.ToolCallException(
                    ToolExecutionResult.Status.FATAL, result.stopDetails().explanation());
        }
        return result.stopDetails().explanation();
    }

    private TaskResult executeCustomAgent(
            String agentName, String task, JobSpec.@Nullable ResponseSchema responseSchema)
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

        var executor = new CustomAgentExecutor(cm, agentDef, model, cm.getIo(), responseSchema);
        return executor.executeInterruptibly(task);
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

                // Keep collecting sibling results after a fatal child result. Callers need the
                // full batch of tool messages for attribution and retry diagnostics.
                if (outcome.toolResult().status() == ToolExecutionResult.Status.FATAL && firstFatalMessage == null) {
                    firstFatalMessage = outcome.toolResult().resultText();
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
        taskIo.beforeToolCall(request, false);
        var childRunId = UUID.randomUUID().toString();
        var startNanos = System.nanoTime();

        String agentName;
        String task;
        String responseSchemaName = "";
        JobSpec.@Nullable ResponseSchema responseSchema = null;
        try {
            var validated = tr.validateTool(request);
            var parameters = validated.parameters();
            agentName = (String) parameters.getFirst();
            task = (String) parameters.get(1);
            if ("callCustomAgentWithSchema".equals(request.name())) {
                responseSchemaName = (String) parameters.get(2);
                responseSchema = CustomAgentResponseSchemaResolver.resolve(responseSchemaName, responseSchemaRegistry);
            }
        } catch (RuntimeException e) {
            var errorMessage = "Failed to parse custom agent arguments: " + e.getMessage();
            logger.debug(errorMessage, e);
            var failure = ToolExecutionResult.requestError(request, errorMessage);
            maybeRecordArtifact(
                    request,
                    childRunId,
                    extractStringArgument(request, "agentName", "unknown"),
                    extractStringArgument(request, "responseSchemaName", responseSchemaName),
                    failure,
                    elapsedMs(startNanos));
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
            maybeRecordArtifact(request, childRunId, agentName, responseSchemaName, failure, elapsedMs(startNanos));
            taskIo.afterToolOutput(failure);
            return new CustomAgentTaskResult(failure, agentName);
        }

        logger.info(
                "Invoking custom agent '{}' in parallel with model {}",
                agentName,
                cm.getService().nameOf(model));

        try {
            var executor = new CustomAgentExecutor(cm, agentDef, model, taskIo, responseSchema);
            var result = executor.executeInterruptibly(task);
            var explanation = responseSchema == null
                    ? extractExplanation(result.stopDetails().explanation())
                    : result.stopDetails().explanation();
            var toolResult = responseSchema == null
                    ? toToolExecutionResult(request, result.stopDetails(), explanation)
                    : toSchemaToolExecutionResult(request, result.stopDetails(), explanation);
            maybeRecordArtifact(request, childRunId, agentName, responseSchemaName, toolResult, elapsedMs(startNanos));
            taskIo.afterToolOutput(toolResult);
            return new CustomAgentTaskResult(toolResult, agentName);
        } catch (RuntimeException e) {
            var errorMessage = "Error executing custom agent '%s': %s"
                    .formatted(agentName, Objects.toString(e.getMessage(), "Unknown error"));
            logger.debug(errorMessage, e);
            var failure = ToolExecutionResult.requestError(request, errorMessage);
            maybeRecordArtifact(request, childRunId, agentName, responseSchemaName, failure, elapsedMs(startNanos));
            taskIo.afterToolOutput(failure);
            return new CustomAgentTaskResult(failure, agentName);
        }
    }

    private void maybeRecordArtifact(
            ToolExecutionRequest request,
            String childRunId,
            String agentName,
            String responseSchemaName,
            ToolExecutionResult result,
            long elapsedMs) {
        if (!"callCustomAgentWithSchema".equals(request.name())) {
            return;
        }
        childAgentArtifactSink.record(
                toArtifact(childRunId, request, agentName, responseSchemaName, result, elapsedMs));
    }

    private ChildAgentArtifact toArtifact(
            String childRunId,
            ToolExecutionRequest request,
            String agentName,
            String responseSchemaName,
            ToolExecutionResult result,
            long elapsedMs) {
        var resultText = result.resultText();
        JsonNode validatedResponse = null;
        String validationError = null;
        String invalidOutputExcerpt = null;
        String errorMessage = null;
        String status;

        if (result.status() == ToolExecutionResult.Status.SUCCESS) {
            try {
                var parsed = Json.getMapper().readTree(resultText);
                if (parsed.isObject()) {
                    validatedResponse = parsed;
                    status = ChildAgentArtifact.STATUS_SUCCESS;
                } else {
                    status = ChildAgentArtifact.STATUS_SCHEMA_INVALID;
                    validationError = "validated child response was not a JSON object";
                    invalidOutputExcerpt = excerpt(resultText);
                }
            } catch (Exception e) {
                status = ChildAgentArtifact.STATUS_SCHEMA_INVALID;
                validationError = "validated child response could not be parsed as JSON: " + e.getMessage();
                invalidOutputExcerpt = excerpt(resultText);
            }
        } else if (result.status() == ToolExecutionResult.Status.FATAL
                && resultText.contains("RESPONSE_SCHEMA_OUTPUT_INVALID")) {
            status = ChildAgentArtifact.STATUS_SCHEMA_INVALID;
            validationError = extractDiagnosticValue(resultText, "validation");
            if (validationError == null) {
                validationError = resultText.lines().findFirst().orElse("RESPONSE_SCHEMA_OUTPUT_INVALID");
            }
            invalidOutputExcerpt = Objects.requireNonNullElseGet(
                    extractDiagnosticValue(resultText, "invalidOutputExcerpt"), () -> excerpt(resultText));
        } else {
            status = statusForNonSchemaFailure(resultText);
            errorMessage = excerpt(resultText);
        }

        return new ChildAgentArtifact(
                childAgentArtifactSink.parentJobId(),
                childRunId,
                request.id(),
                agentName,
                responseSchemaName,
                status,
                validatedResponse,
                validationError,
                invalidOutputExcerpt,
                errorMessage,
                elapsedMs,
                cm.getService().nameOf(model),
                null,
                null,
                null);
    }

    private static String statusForNonSchemaFailure(String resultText) {
        var lower = resultText.toLowerCase(Locale.ROOT);
        if (lower.contains("cancel")) {
            return ChildAgentArtifact.STATUS_CANCELLED;
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return ChildAgentArtifact.STATUS_TIMEOUT;
        }
        return ChildAgentArtifact.STATUS_FAILED;
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static String extractStringArgument(ToolExecutionRequest request, String key, String fallback) {
        try {
            var node = Json.getMapper().readTree(request.arguments());
            var value = node.get(key);
            if (value != null && value.isTextual()) {
                return value.asText();
            }
        } catch (Exception ignored) {
            // Fall through to fallback.
        }
        return fallback;
    }

    private static @Nullable String extractDiagnosticValue(String text, String key) {
        var prefix = key + "=";
        var start = text.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        var valueStart = start + prefix.length();
        var nextKeyStart = nextDiagnosticKeyStart(text, valueStart);
        if (nextKeyStart < 0) {
            return text.substring(valueStart).strip();
        }
        return text.substring(valueStart, nextKeyStart).strip();
    }

    private static int nextDiagnosticKeyStart(String text, int valueStart) {
        var keys = List.of(
                "\nschema=",
                "\nvalidation=",
                "\nattempts=",
                "\nfinishReason=",
                "\ninitialInvalidOutputExcerpt=",
                "\ninvalidOutputExcerpt=",
                "\nfinalValidationError=",
                "\nllmRepairAttempted=",
                "\ndeterministicRepairAttempted=");
        return keys.stream()
                .mapToInt(key -> text.indexOf(key, valueStart))
                .filter(index -> index >= 0)
                .min()
                .orElse(-1);
    }

    private static String excerpt(String text) {
        var normalized = text.strip();
        if (normalized.length() <= 2_000) {
            return normalized;
        }
        return normalized.substring(0, 2_000) + "...[truncated " + (normalized.length() - 2_000) + " chars]";
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

    static ToolExecutionResult toSchemaToolExecutionResult(
            ToolExecutionRequest request, TaskResult.StopDetails stopDetails, String explanation)
            throws InterruptedException {
        return switch (stopDetails.reason()) {
            case SUCCESS -> ToolExecutionResult.success(request, explanation);
            case INTERRUPTED -> throw new InterruptedException();
            default -> ToolExecutionResult.fatal(request, explanation);
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
