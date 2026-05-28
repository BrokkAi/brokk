package ai.brokk.executor.agents;

import ai.brokk.IAppContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.Llm;
import ai.brokk.LlmOutputMeta;
import ai.brokk.TaskResult;
import ai.brokk.concurrent.LoggingFuture;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.executor.jobs.JobResponseSchemaSupport;
import ai.brokk.executor.jobs.JobSpec;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
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
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ToolChoice;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Executes a custom agent defined by an {@link AgentDefinition}.
 * Replicates SearchAgent's agentic loop (tool registration, parallel execution,
 * terminal detection) but parameterized by user-supplied configuration.
 */
public class CustomAgentExecutor {
    private static final Logger logger = LogManager.getLogger(CustomAgentExecutor.class);

    private record TerminalStopOutput(String llmText, TaskResult.StopDetails stopDetails) implements ToolOutput {}

    private record SchemaCandidate(String source, String json) {}

    private record NormalizedCandidate(String json, List<String> changes) {}

    private static final Set<String> TERMINAL_TOOL_NAMES = Set.of("answer", "abortSearch");
    private static final Set<String> PARALLEL_SAFE_SEARCH_TOOL_NAMES = AgentDefinition.PARALLEL_SAFE_SEARCH_TOOL_NAMES;
    private static final int STRUCTURED_REPAIR_MIN_COMPLETION_TOKENS = 192;
    private static final int STRUCTURED_REPAIR_MAX_COMPLETION_TOKENS = 1024;
    private static final int STRUCTURED_REPAIR_TOKENS_PER_SCHEMA_NODE = 24;
    private static final int INVALID_PREVIOUS_RESPONSE_MAX_CHARS = 8000;

    private final IAppContextManager cm;
    private final AgentDefinition agentDef;
    private final StreamingChatModel model;
    private final @Nullable JobSpec.ResponseSchema responseSchema;
    private final @Nullable ResponseFormat responseFormat;
    private final Llm llm;
    private final SearchTools searchTools;
    private final IConsoleIO io;

    private Context context;

    public CustomAgentExecutor(IAppContextManager cm, AgentDefinition agentDef, StreamingChatModel model) {
        this(cm, agentDef, model, cm.getIo(), null);
    }

    public CustomAgentExecutor(
            IAppContextManager cm, AgentDefinition agentDef, StreamingChatModel model, IConsoleIO io) {
        this(cm, agentDef, model, io, null);
    }

    public CustomAgentExecutor(
            IAppContextManager cm,
            AgentDefinition agentDef,
            StreamingChatModel model,
            IConsoleIO io,
            @Nullable JobSpec.ResponseSchema responseSchema) {
        this.cm = cm;
        this.agentDef = agentDef;
        this.model = model;
        this.io = io;
        this.responseSchema = responseSchema;
        this.responseFormat = responseSchema == null ? null : JobResponseSchemaSupport.toResponseFormat(responseSchema);
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
        if (responseFormat != null && !cm.getService().supportsJsonSchema(model)) {
            return new TaskResult(
                    context,
                    new TaskResult.StopDetails(
                            TaskResult.StopReason.LLM_ERROR,
                            "MODEL_UNSUPPORTED_RESPONSE_SCHEMA: "
                                    + cm.getService().nameOf(model)));
        }

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
            var executionRegistry = registryForContext(toolRegistry, context);

            // Parallel execution for safe tools
            var parallelPartition =
                    ToolRegistry.partitionByNames(ai.toolExecutionRequests(), PARALLEL_SAFE_SEARCH_TOOL_NAMES);
            var parallelRequests = parallelPartition.matchingRequests();
            Map<ToolExecutionRequest, CompletableFuture<ToolExecutionResult>> parallelFutures =
                    new LinkedHashMap<>(parallelRequests.size());
            if (parallelRequests.size() > 1) {
                var parallelExecutionRegistry = executionRegistry;
                for (var request : parallelRequests) {
                    boolean destructive = toolRegistry.isToolAnnotated(request.name(), Destructive.class);
                    var approval = io.beforeToolCall(request, destructive);
                    if (!approval.isApproved()) {
                        parallelFutures.put(
                                request,
                                CompletableFuture.completedFuture(ToolExecutionResult.requestError(
                                        request, "Tool call '%s' was denied by user.".formatted(request.name()))));
                    } else {
                        io.toolCallInProgress(request);
                        parallelFutures.put(
                                request,
                                LoggingFuture.supplyCallableVirtual(
                                        () -> parallelExecutionRegistry.executeTool(request)));
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
                        io.toolCallInProgress(request);
                        toolResult = executionRegistry.executeTool(request);
                        io.afterToolOutput(toolResult);
                    }
                }
                llm.recordToolExecution(toolResult);

                if (toolResult.result() instanceof WorkspaceTools.WorkspaceMutationOutput output) {
                    context = output.context();
                    executionRegistry = registryForContext(toolRegistry, context);
                    additionsThisTurn.addAll(output.addedFragments());
                } else if (toolResult.result() instanceof WorkspaceTools.DropWorkspaceOutput output) {
                    context = output.context();
                    executionRegistry = registryForContext(toolRegistry, context);
                    additionsThisTurn.addAll(output.addedFragments());
                }

                if (toolResult.status() == ToolExecutionResult.Status.FATAL) {
                    cancelOutstandingParallelFutures.run();
                    return errorResult(toolResult.resultText());
                }

                if (TERMINAL_TOOL_NAMES.contains(request.name())
                        && toolResult.result() instanceof TerminalStopOutput tso) {
                    cancelOutstandingParallelFutures.run();
                    if (responseFormat != null && tso.stopDetails().reason() == TaskResult.StopReason.SUCCESS) {
                        return structuredFinalAnswer(taskInput, tso.llmText());
                    }
                    return new TaskResult(context, tso.stopDetails());
                }

                messages.add(toolResult.toMessage());
            }

            previousTurnAdditions = List.copyOf(additionsThisTurn);
        }

        return errorResult("Turn limit reached (%d turns).".formatted(maxTurns));
    }

    private TaskResult structuredFinalAnswer(String taskInput, String finalNotes) throws InterruptedException {
        var schema = Objects.requireNonNull(responseSchema);
        var candidates = schemaCandidates(finalNotes);
        if (candidates.isEmpty()) {
            return new TaskResult(
                    context,
                    new TaskResult.StopDetails(
                            TaskResult.StopReason.LLM_ERROR,
                            "RESPONSE_SCHEMA_OUTPUT_MISSING: schema=%s candidateSource=none".formatted(schema.name())));
        }

        var validationErrors = new LinkedHashMap<String, String>();
        for (var candidate : candidates) {
            var validationError = JobResponseSchemaSupport.validateOutput(schema, candidate.json());
            if (validationError.isEmpty()) {
                io.llmOutput(candidate.json(), ChatMessageType.AI, LlmOutputMeta.newMessage());
                return new TaskResult(
                        context, new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS, candidate.json()));
            }
            validationErrors.put(candidate.source(), validationError.get());
        }

        var repairCandidates = repairCandidateOrder(candidates);
        boolean deterministicRepairAttempted = false;
        for (var candidate : repairCandidates) {
            deterministicRepairAttempted = true;
            var normalizedCandidate = deterministicNormalize(schema, candidate.json());
            if (normalizedCandidate != null) {
                var normalizedValidationError =
                        JobResponseSchemaSupport.validateOutput(schema, normalizedCandidate.json());
                if (normalizedValidationError.isEmpty()) {
                    logger.info(
                            "Schema-aware custom-agent terminal output normalized deterministically for schema {} from {}: {}",
                            schema.name(),
                            candidate.source(),
                            normalizedCandidate.changes());
                    io.llmOutput(normalizedCandidate.json(), ChatMessageType.AI, LlmOutputMeta.newMessage());
                    return new TaskResult(
                            context,
                            new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS, normalizedCandidate.json()));
                }
            }
        }

        var repairCandidate = repairCandidates.getFirst();
        var initialValidationError =
                validationErrors.getOrDefault(repairCandidate.source(), "unknown validation error");
        var structuredLlm = cm.getLlm(
                new Llm.Options(model, agentDef.name() + " structured answer", TaskResult.Type.SEARCH).withEcho());
        structuredLlm.setOutput(io);
        var repairMaxCompletionTokens = repairMaxCompletionTokens(schema);

        var toc = WorkspacePrompts.formatToc(context).trim();
        var messages = List.<ChatMessage>of(
                new SystemMessage(agentDef.systemPrompt()),
                new UserMessage(formatStructuredFinalPrompt(taskInput, repairCandidate.json(), toc)));

        String structuredText = "";
        for (int attempt = 1; attempt <= 2; attempt++) {
            var response = structuredLlm.sendRequest(
                    messages,
                    structuredLlm
                            .requestOptions()
                            .withResponseFormat(responseFormat)
                            .withMaxAttempts(1)
                            .withMaxCompletionTokens(repairMaxCompletionTokens));
            if (response.error() != null) {
                return new TaskResult(context, TaskResult.StopDetails.fromResponse(response));
            }

            structuredText = response.text();
            if (response.isPartial()) {
                return new TaskResult(
                        context,
                        new TaskResult.StopDetails(
                                TaskResult.StopReason.LLM_ERROR,
                                repairFailureMessage(
                                        schema,
                                        repairCandidate.source(),
                                        initialValidationError,
                                        deterministicRepairAttempted,
                                        true,
                                        "LENGTH",
                                        structuredText)));
            }

            var validationError = JobResponseSchemaSupport.validateOutput(schema, structuredText);
            if (validationError.isEmpty()) {
                io.llmOutput(structuredText, ChatMessageType.AI, LlmOutputMeta.newMessage());
                return new TaskResult(
                        context, new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS, structuredText));
            }

            if (attempt == 2) {
                return new TaskResult(
                        context,
                        new TaskResult.StopDetails(
                                TaskResult.StopReason.LLM_ERROR,
                                repairFailureMessage(
                                        schema,
                                        repairCandidate.source(),
                                        validationError.get(),
                                        deterministicRepairAttempted,
                                        true,
                                        "complete",
                                        structuredText)));
            }

            messages = List.of(
                    new SystemMessage(agentDef.systemPrompt()),
                    new UserMessage(formatStructuredFinalRetryPrompt(
                            taskInput,
                            repairCandidate.json(),
                            toc,
                            abbreviateInvalidOutput(structuredText),
                            validationError.get())));
        }

        throw new IllegalStateException("unreachable structured answer retry state");
    }

    static List<SchemaCandidate> schemaCandidates(String finalNotes) {
        var candidates = new ArrayList<SchemaCandidate>();
        var raw = finalNotes.trim();
        if (!raw.isBlank()) {
            candidates.add(new SchemaCandidate("raw", raw));
        }

        try {
            var node = Json.getMapper().readTree(finalNotes);
            var explanation = node.get("explanation");
            if (explanation != null && explanation.isTextual()) {
                var text = explanation.asText().trim();
                if (!text.isBlank()) {
                    candidates.add(new SchemaCandidate("answer.explanation.text", text));
                }
            } else if (explanation != null && explanation.isObject()) {
                candidates.add(new SchemaCandidate("answer.explanation.object", Json.toJson(explanation)));
            }
        } catch (Exception ignored) {
            // Not an answer-tool envelope. Validate the raw text above.
        }

        return candidates.stream().distinct().toList();
    }

    private static List<SchemaCandidate> repairCandidateOrder(List<SchemaCandidate> candidates) {
        var extracted = candidates.stream()
                .filter(candidate -> !"raw".equals(candidate.source()))
                .toList();
        if (extracted.isEmpty()) {
            return candidates;
        }

        var ordered = new ArrayList<>(extracted);
        candidates.stream()
                .filter(candidate -> "raw".equals(candidate.source()))
                .forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private static @Nullable NormalizedCandidate deterministicNormalize(
            JobSpec.ResponseSchema schema, String candidate) {
        try {
            var root = Json.getMapper().readTree(candidate);
            var changes = new ArrayList<String>();
            var normalized = normalizeNode(root, schema.schema(), "response", "", changes);
            if (changes.isEmpty()) {
                return null;
            }
            return new NormalizedCandidate(Json.toJson(normalized), List.copyOf(changes));
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonNode normalizeNode(
            JsonNode value, JsonNode schema, String path, String propertyName, List<String> changes) {
        if (value.isNull()) {
            return value;
        }

        return switch (schemaType(schema)) {
            case "object" -> normalizeObject(value, schema, path, changes);
            case "array" -> normalizeArray(value, schema, path, propertyName, changes);
            case "string" -> normalizeString(value, schema, path, propertyName, changes);
            default -> value;
        };
    }

    private static JsonNode normalizeObject(JsonNode value, JsonNode schema, String path, List<String> changes) {
        if (!value.isObject()) {
            return value;
        }

        var object = (ObjectNode) value.deepCopy();
        var properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            if (additionalPropertiesDisabled(schema) && allRequiredPropertiesPresent(object, schema)) {
                var allowedNames = new HashSet<String>();
                properties.properties().forEach(entry -> allowedNames.add(entry.getKey()));
                var namesToRemove = new ArrayList<String>();
                object.properties().forEach(entry -> {
                    if (!allowedNames.contains(entry.getKey())) {
                        namesToRemove.add(entry.getKey());
                    }
                });
                namesToRemove.forEach(name -> {
                    object.remove(name);
                    changes.add(path + "." + name + " unknown property dropped");
                });
            }

            properties.properties().forEach(entry -> {
                var child = object.get(entry.getKey());
                if (child != null) {
                    object.set(
                            entry.getKey(),
                            normalizeNode(
                                    child, entry.getValue(), path + "." + entry.getKey(), entry.getKey(), changes));
                }
            });
        }
        return object;
    }

    private static JsonNode normalizeArray(
            JsonNode value, JsonNode schema, String path, String propertyName, List<String> changes) {
        var items = schema.get("items");
        if (value.isArray()) {
            var array = Json.getMapper().createArrayNode();
            for (int i = 0; i < value.size(); i++) {
                array.add(
                        items == null
                                ? value.get(i)
                                : normalizeNode(value.get(i), items, path + "[" + i + "]", propertyName, changes));
            }
            return array;
        }

        var array = Json.getMapper().createArrayNode();
        array.add(items == null ? value : normalizeNode(value, items, path + "[0]", propertyName, changes));
        changes.add(path + " " + outputType(value) + " -> array");
        return array;
    }

    private static JsonNode normalizeString(
            JsonNode value, JsonNode schema, String path, String propertyName, List<String> changes) {
        if (value.isTextual()) {
            return value;
        }

        if (isConfidenceProperty(propertyName) && value.isNumber()) {
            var confidence = confidenceLabel(value.doubleValue(), schema);
            if (confidence != null) {
                changes.add(path + " numeric confidence -> string enum");
                return TextNode.valueOf(confidence);
            }
        }

        if (value.isNumber() || value.isBoolean()) {
            changes.add(path + " " + outputType(value) + " -> string");
            return TextNode.valueOf(value.asText());
        }

        return value;
    }

    private static boolean isConfidenceProperty(String propertyName) {
        return "confidence".equals(propertyName);
    }

    private static @Nullable String confidenceLabel(double value, JsonNode schema) {
        var enumNode = schema.get("enum");
        if (enumNode == null || !enumNode.isArray()) {
            return null;
        }
        var labels = Json.stringArrayToSet(enumNode);
        if (!labels.containsAll(Set.of("low", "medium", "high"))) {
            return null;
        }
        if (Double.compare(value, 1.0) == 0) {
            return "high";
        }
        if (Double.compare(value, 0.5) == 0) {
            return "medium";
        }
        if (Double.compare(value, 0.0) == 0) {
            return "low";
        }
        return null;
    }

    private static boolean allRequiredPropertiesPresent(ObjectNode value, JsonNode schema) {
        var required = schema.get("required");
        if (required == null || !required.isArray()) {
            return true;
        }
        for (var requiredName : required) {
            if (!requiredName.isTextual() || !value.hasNonNull(requiredName.textValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean additionalPropertiesDisabled(JsonNode schema) {
        var additionalProperties = schema.get("additionalProperties");
        return additionalProperties != null && additionalProperties.isBoolean() && !additionalProperties.booleanValue();
    }

    private static String schemaType(JsonNode schema) {
        var type = schema.get("type");
        return type != null && type.isTextual() ? type.textValue() : "";
    }

    private static String outputType(JsonNode value) {
        if (value.isNumber()) {
            return value.isIntegralNumber() ? "integer" : "number";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        if (value.isArray()) {
            return "array";
        }
        if (value.isObject()) {
            return "object";
        }
        return value.getNodeType().name().toLowerCase(Locale.ROOT);
    }

    private static int repairMaxCompletionTokens(JobSpec.ResponseSchema schema) {
        return Math.min(
                STRUCTURED_REPAIR_MAX_COMPLETION_TOKENS,
                Math.max(
                        STRUCTURED_REPAIR_MIN_COMPLETION_TOKENS,
                        countSchemaNodes(schema.schema()) * STRUCTURED_REPAIR_TOKENS_PER_SCHEMA_NODE));
    }

    private static int countSchemaNodes(JsonNode schema) {
        var type = schemaType(schema);
        return switch (type) {
            case "object" -> {
                var properties = schema.get("properties");
                if (properties == null || !properties.isObject()) {
                    yield 1;
                }
                var count = 1;
                for (var entry : properties.properties()) {
                    count += countSchemaNodes(entry.getValue());
                }
                yield count;
            }
            case "array" -> {
                var items = schema.get("items");
                yield items == null ? 1 : 1 + countSchemaNodes(items);
            }
            default -> 1;
        };
    }

    private static String repairFailureMessage(
            JobSpec.ResponseSchema schema,
            String candidateSource,
            String validationError,
            boolean deterministicRepairAttempted,
            boolean llmRepairAttempted,
            String finishReason,
            String invalidOutput) {
        return "RESPONSE_SCHEMA_OUTPUT_INVALID: schema=%s candidateSource=%s validation=%s deterministicRepairAttempted=%s llmRepairAttempted=%s finishReason=%s invalidOutputExcerpt=%s"
                .formatted(
                        schema.name(),
                        candidateSource,
                        validationError,
                        deterministicRepairAttempted,
                        llmRepairAttempted,
                        finishReason,
                        abbreviateInvalidOutput(invalidOutput));
    }

    private static String abbreviateInvalidOutput(String invalidOutput) {
        if (invalidOutput.length() <= INVALID_PREVIOUS_RESPONSE_MAX_CHARS) {
            return invalidOutput;
        }
        return invalidOutput.substring(0, INVALID_PREVIOUS_RESPONSE_MAX_CHARS)
                + "\n\n[truncated invalid response: "
                + (invalidOutput.length() - INVALID_PREVIOUS_RESPONSE_MAX_CHARS)
                + " chars omitted]";
    }

    static String formatStructuredFinalPrompt(String taskInput, String finalNotes, String toc) {
        return """
                <task>
                %s
                </task>

                <custom_agent_final_notes>
                %s
                </custom_agent_final_notes>

                %s

                Produce the final custom-agent result according to the supplied response schema.
                Return only the JSON object.
                Do not include markdown, commentary, copied schema, or explanation.
                Preserve all evidence from the candidate.
                Only fix JSON shape/types to match the schema.
                """
                .formatted(taskInput, finalNotes, toc);
    }

    static String formatStructuredFinalRetryPrompt(
            String taskInput, String finalNotes, String toc, String invalidOutput, String validationError) {
        return """
                <task>
                %s
                </task>

                <custom_agent_final_notes>
                %s
                </custom_agent_final_notes>

                %s

                Your previous structured response did not satisfy the supplied response schema.
                Validation error: %s

                <invalid_previous_response>
                %s
                </invalid_previous_response>

                Produce a corrected final custom-agent result according to the supplied response schema.
                Return only the JSON object.
                Do not include markdown, commentary, copied schema, or explanation.
                Preserve all evidence from the candidate.
                Only fix JSON shape/types to match the schema.
                """
                .formatted(taskInput, finalNotes, toc, validationError, invalidOutput);
    }

    private static ToolRegistry registryForContext(ToolRegistry baseRegistry, Context context) {
        return ToolRegistry.fromBase(baseRegistry)
                .register(new WorkspaceTools(context))
                .build();
    }

    private String buildTurnDirective(
            int turn, int maxTurns, String taskInput, List<ContextFragment> previousTurnAdditions) {
        String toc = WorkspacePrompts.formatToc(context).trim();
        var additionFormats =
                previousTurnAdditions.stream().map(ContextFragment::format).toList();

        return formatTurnDirective(turn, maxTurns, taskInput, additionFormats, toc);
    }

    static String formatTurnDirective(
            int turn, int maxTurns, String taskInput, List<String> previousTurnAdditions, String toc) {
        boolean finalTurn = turn == maxTurns;
        String nextToolRequest = finalTurn
                ? "This is the final turn. Call 'answer' or 'abortSearch' to finish."
                : "Call as many next tools in parallel as will most effectively advance your work.";

        var additions = String.join("\n", previousTurnAdditions);
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
                <goal>Continue the custom agent task.</goal>

                %s

                <next_tool_request>%s</next_tool_request>

                %s
                </turn>
                """
                .formatted(additionsBlock, nextToolRequest, toc);
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
        if (responseFormat == null) {
            io.llmOutput("# Answer\n\n" + explanation, ChatMessageType.AI, LlmOutputMeta.newMessage());
        }
        return new TerminalStopOutput(responseFormat == null ? details : explanation, stopDetails);
    }
}
