package ai.brokk.executor.agents;

import ai.brokk.IAppContextManager;
import ai.brokk.TaskResult;
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
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Tool provider that exposes custom agents as invocable tools.
 * Register this on any agent (SearchAgent, LutzAgent, ArchitectAgent) to let
 * the LLM call stored custom agents by name during its loop.
 * The model is inherited from the parent agent, following the same pattern as
 * {@code ParallelSearch} which receives its model at construction time.
 */
public class CustomAgentTools {
    private static final Logger logger = LogManager.getLogger(CustomAgentTools.class);

    private final IAppContextManager cm;
    private final StreamingChatModel model;
    private final ResponseSchemaRegistry responseSchemaRegistry;
    private final ChildAgentArtifactSink childAgentArtifactSink;

    public CustomAgentTools(IAppContextManager cm, StreamingChatModel model) {
        this(cm, model, ResponseSchemaRegistry.empty());
    }

    public CustomAgentTools(
            IAppContextManager cm, StreamingChatModel model, ResponseSchemaRegistry responseSchemaRegistry) {
        this(cm, model, responseSchemaRegistry, ChildAgentArtifactSink.noop());
    }

    public CustomAgentTools(
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
        var childRunId = UUID.randomUUID().toString();
        var startNanos = System.nanoTime();
        JobSpec.ResponseSchema resolvedSchema;
        try {
            resolvedSchema = CustomAgentResponseSchemaResolver.resolve(responseSchemaName, responseSchemaRegistry);
        } catch (ToolRegistry.ToolValidationException e) {
            var errorMessage = Objects.requireNonNullElse(e.getMessage(), "Invalid responseSchemaName");
            recordArtifact(
                    childRunId,
                    agentName,
                    responseSchemaName,
                    ChildAgentArtifact.STATUS_FAILED,
                    null,
                    null,
                    null,
                    errorMessage,
                    elapsedMs(startNanos));
            throw new ToolRegistry.ToolCallException(ToolExecutionResult.Status.REQUEST_ERROR, errorMessage);
        }
        var result = executeCustomAgent(agentName, task, resolvedSchema);
        if (result.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
            recordFailureArtifact(
                    childRunId,
                    agentName,
                    responseSchemaName,
                    result.stopDetails().explanation(),
                    elapsedMs(startNanos));
            throw new ToolRegistry.ToolCallException(
                    ToolExecutionResult.Status.FATAL, result.stopDetails().explanation());
        }
        var explanation = result.stopDetails().explanation();
        recordSuccessArtifact(childRunId, agentName, responseSchemaName, explanation, elapsedMs(startNanos));
        return explanation;
    }

    private void recordSuccessArtifact(
            String childRunId, String agentName, String responseSchemaName, String explanation, long elapsedMs) {
        try {
            var parsed = Json.getMapper().readTree(explanation);
            if (parsed.isObject()) {
                recordArtifact(
                        childRunId,
                        agentName,
                        responseSchemaName,
                        ChildAgentArtifact.STATUS_SUCCESS,
                        parsed,
                        null,
                        null,
                        null,
                        elapsedMs);
            } else {
                recordArtifact(
                        childRunId,
                        agentName,
                        responseSchemaName,
                        ChildAgentArtifact.STATUS_SCHEMA_INVALID,
                        null,
                        "validated child response was not a JSON object",
                        excerpt(explanation),
                        null,
                        elapsedMs);
            }
        } catch (Exception e) {
            recordArtifact(
                    childRunId,
                    agentName,
                    responseSchemaName,
                    ChildAgentArtifact.STATUS_SCHEMA_INVALID,
                    null,
                    "validated child response could not be parsed as JSON: " + e.getMessage(),
                    excerpt(explanation),
                    null,
                    elapsedMs);
        }
    }

    private void recordFailureArtifact(
            String childRunId, String agentName, String responseSchemaName, String explanation, long elapsedMs) {
        if (explanation.contains("RESPONSE_SCHEMA_OUTPUT_INVALID")) {
            recordArtifact(
                    childRunId,
                    agentName,
                    responseSchemaName,
                    ChildAgentArtifact.STATUS_SCHEMA_INVALID,
                    null,
                    extractSchemaValidationError(explanation),
                    Objects.requireNonNullElseGet(
                            extractDiagnosticValue(explanation, "invalidOutputExcerpt"), () -> excerpt(explanation)),
                    null,
                    elapsedMs);
            return;
        }
        recordArtifact(
                childRunId,
                agentName,
                responseSchemaName,
                statusForNonSchemaFailure(explanation),
                null,
                null,
                null,
                excerpt(explanation),
                elapsedMs);
    }

    private void recordArtifact(
            String childRunId,
            String agentName,
            String responseSchemaName,
            String status,
            @Nullable JsonNode validatedResponse,
            @Nullable String validationError,
            @Nullable String invalidOutputExcerpt,
            @Nullable String errorMessage,
            long elapsedMs) {
        childAgentArtifactSink.record(new ChildAgentArtifact(
                childAgentArtifactSink.parentJobId(),
                childRunId,
                null,
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
                null));
    }

    private static String statusForNonSchemaFailure(String explanation) {
        var lower = explanation.toLowerCase(Locale.ROOT);
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

    private static @Nullable String extractDiagnosticValue(String text, String key) {
        var prefix = key + "=";
        var start = findDiagnosticKeyStart(text, key);
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
        return diagnosticKeys().stream()
                .flatMap(key -> List.of(" " + key + "=", "\n" + key + "=").stream())
                .mapToInt(key -> text.indexOf(key, valueStart))
                .filter(index -> index >= 0)
                .min()
                .orElse(-1);
    }

    private static int findDiagnosticKeyStart(String text, String key) {
        var prefix = key + "=";
        var start = text.indexOf(prefix);
        while (start >= 0) {
            if (start == 0 || Character.isWhitespace(text.charAt(start - 1))) {
                return start;
            }
            start = text.indexOf(prefix, start + prefix.length());
        }
        return -1;
    }

    private static @Nullable String extractSchemaValidationError(String text) {
        var finalValidation = extractDiagnosticValue(text, "finalValidation");
        if (finalValidation != null && !finalValidation.isBlank() && !"null".equals(finalValidation)) {
            return finalValidation;
        }
        var originalValidation = extractDiagnosticValue(text, "originalValidation");
        if (originalValidation != null && !originalValidation.isBlank() && !"null".equals(originalValidation)) {
            return originalValidation;
        }
        return extractDiagnosticValue(text, "validation");
    }

    private static List<String> diagnosticKeys() {
        return List.of(
                "schema",
                "candidateSource",
                "originalValidation",
                "finalValidation",
                "validation",
                "attempts",
                "finishReason",
                "initialInvalidOutputExcerpt",
                "originalOutputExcerpt",
                "invalidOutputExcerpt",
                "finalValidationError",
                "llmRepairAttempted",
                "deterministicRepairAttempted",
                "deterministicChanges",
                "salvageAttempted",
                "salvageChanges");
    }

    private static String excerpt(String text) {
        var normalized = text.strip();
        if (normalized.length() <= 2_000) {
            return normalized;
        }
        return normalized.substring(0, 2_000) + "...[truncated " + (normalized.length() - 2_000) + " chars]";
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
                "Invoking custom agent '{}' with model {}",
                agentName,
                cm.getService().nameOf(model));

        var executor = new CustomAgentExecutor(cm, agentDef, model, cm.getIo(), responseSchema);
        return executor.execute(task);
    }

    /**
     * The executor stores stop details as JSON ({@code {"explanation":"..."}}).
     * Extract the inner explanation text so the parent agent gets plain markdown.
     */
    private static String extractExplanation(String raw) {
        try {
            var node = Json.getMapper().readTree(raw);
            var explanation = node.get("explanation");
            if (explanation != null && explanation.isTextual()) {
                return explanation.asText();
            }
        } catch (Exception ignored) {
            // Not JSON — return as-is
        }
        return raw;
    }
}
