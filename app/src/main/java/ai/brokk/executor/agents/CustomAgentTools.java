package ai.brokk.executor.agents;

import ai.brokk.IContextManager;
import ai.brokk.Service;
import ai.brokk.TaskResult;
import ai.brokk.project.ModelProperties;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Tool provider that exposes custom agents as invocable tools.
 * Register this on any agent (SearchAgent, LutzAgent, ArchitectAgent) to let
 * the LLM call stored custom agents by name during its loop.
 */
public class CustomAgentTools {
    private static final Logger logger = LogManager.getLogger(CustomAgentTools.class);

    private final IContextManager cm;

    public CustomAgentTools(IContextManager cm) {
        this.cm = cm;
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

        // Resolve model: agent definition's model if set, otherwise project's SEARCH model
        var modelName = agentDef.model();
        StreamingChatModel model;
        if (modelName != null && !modelName.isBlank()) {
            var resolved = cm.getService().getModel(new Service.ModelConfig(modelName));
            if (resolved == null) {
                throw new IllegalArgumentException(
                        "Model unavailable for agent '%s': %s".formatted(agentName, modelName));
            }
            model = resolved;
        } else {
            model = cm.getService().getModel(ModelProperties.ModelType.SEARCH);
        }

        logger.info(
                "Invoking custom agent '{}' with model {}",
                agentName,
                cm.getService().nameOf(model));

        var executor = new CustomAgentExecutor(cm, agentDef, model);
        var result = executor.execute(task);

        if (result.stopDetails().reason() == TaskResult.StopReason.SUCCESS) {
            return result.stopDetails().explanation();
        } else {
            return "Agent '%s' finished with status %s: %s"
                    .formatted(
                            agentName,
                            result.stopDetails().reason(),
                            result.stopDetails().explanation());
        }
    }
}
