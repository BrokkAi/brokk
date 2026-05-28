package ai.brokk.executor.agents;

import ai.brokk.IAppContextManager;
import ai.brokk.TaskResult;
import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.executor.jobs.ResponseSchemaRegistry;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.util.Json;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.Objects;
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

    public CustomAgentTools(IAppContextManager cm, StreamingChatModel model) {
        this(cm, model, ResponseSchemaRegistry.empty());
    }

    public CustomAgentTools(
            IAppContextManager cm, StreamingChatModel model, ResponseSchemaRegistry responseSchemaRegistry) {
        this.cm = cm;
        this.model = model;
        this.responseSchemaRegistry = responseSchemaRegistry;
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
            The agent still uses its normal tools to gather context, then produces one schema-constrained final result.""")
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
        if (result.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
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
