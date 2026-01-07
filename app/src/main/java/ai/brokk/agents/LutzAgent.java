package ai.brokk.agents;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.TaskResult;
import ai.brokk.context.Context;
import ai.brokk.git.GitWorkflow;
import ai.brokk.gui.Chrome;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.prompts.SearchPrompts.Terminal;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.tools.WorkspaceTools;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.ArrayList;
import java.util.List;

/**
 * LutzAgent: - Specialization of SearchAgent that includes code execution and task list objectives.
 */
public class LutzAgent extends SearchAgent {

    private final SearchPrompts.Objective objective;

    /**
     * Primary constructor with explicit IO and ScanConfig.
     */
    public LutzAgent(
            Context initialContext,
            String goal,
            StreamingChatModel model,
            SearchPrompts.Objective objective,
            ContextManager.TaskScope scope,
            IConsoleIO io,
            ScanConfig scanConfig) {
        super(initialContext, goal, model, scope, io, scanConfig);
        this.objective = objective;
    }

    /**
     * Creates a LutzAgent with default scan configuration and output streaming.
     */
    public LutzAgent(
            Context initialContext,
            String goal,
            StreamingChatModel model,
            SearchPrompts.Objective objective,
            ContextManager.TaskScope scope) {
        this(
                initialContext,
                goal,
                model,
                objective,
                scope,
                initialContext.getContextManager().getIo(),
                ScanConfig.defaults());
    }

    @Override
    protected SearchPrompts.Objective getObjective() {
        return objective;
    }

    @Override
    protected ToolRegistry createToolRegistry(WorkspaceTools wst) {
        return cm.getToolRegistry().builder().register(wst).register(this).build();
    }

    @Override
    protected List<String> calculateTerminalTools() {
        var terminals = new ArrayList<String>();
        var allowed = objective.terminals();
        if (allowed.contains(Terminal.ANSWER)) {
            terminals.add("answer");
            terminals.add("askForClarification");
        }
        if (allowed.contains(Terminal.WORKSPACE)) {
            terminals.add("workspaceComplete");
        }
        if (allowed.contains(Terminal.TASK_LIST)) {
            terminals.add("createOrReplaceTaskList");
        }
        if (allowed.contains(Terminal.CODE)) {
            terminals.add("callCodeAgent");
        }
        terminals.add("abortSearch");
        return terminals;
    }

    // =======================
    // Lutz-specific tools
    // =======================

    @Tool("Provide a final answer to a purely informational request. Use this when no code changes are required.")
    public String answer(
            @P(
                            "Comprehensive explanation that answers the query. Include relevant code snippets and how they relate, formatted in Markdown.")
                    String explanation) {
        conversation.appendUi("# Answer\n\n" + explanation, true);
        return explanation;
    }

    @Tool(
            "Ask the human for clarification when the goal is unclear or necessary information cannot be found. Outputs the provided question to the user and stops.")
    public String askForClarification(
            @P("A concise question or clarification request for the human user.") String queryForUser) {
        conversation.appendUi(queryForUser, true);
        return queryForUser;
    }

    @Tool(
            "Invoke the Code Agent to implement the current goal in a single shot using your provided instructions. Provide complete, self-contained instructions; only the Workspace and your instructions are visible to the Code Agent.")
    public String callCodeAgent(
            @P("Detailed instructions for the CodeAgent, referencing the current project and Workspace.")
                    String instructions)
            throws InterruptedException, ToolRegistry.FatalLlmException {
        // Append first the SearchAgent's result so far; CodeAgent appends its own result
        context = scope.append(createResult("Search: " + goal, goal));

        // Call the agent (actually Architect, not Code, so it can recover if the Context isn't quite complete)
        logger.debug("SearchAgent.callCodeAgent invoked with instructions: {}", instructions);
        var agent = new ArchitectAgent(
                cm, cm.getService().getModel(ModelType.ARCHITECT), cm.getCodeModel(), instructions, scope, context);
        var result = agent.execute();
        var stopDetails = result.stopDetails();
        var reason = stopDetails.reason();
        context = scope.append(result);

        if (reason == TaskResult.StopReason.SUCCESS) {
            // housekeeping
            new GitWorkflow(cm).performAutoCommit(instructions);
            // CodeAgent appended its own result; we don't need to llmOutput anything redundant
            logger.debug("SearchAgent.callCodeAgent finished successfully");
            return "CodeAgent finished with a successful build!";
        }

        // handle failure
        if (reason == TaskResult.StopReason.INTERRUPTED) {
            throw new InterruptedException();
        }
        if (reason == TaskResult.StopReason.LLM_ERROR) {
            conversation.appendUi("# Code Agent\n\nFatal LLM error during CodeAgent execution.", true);
            logger.error("Fatal LLM error during CodeAgent execution: {}", stopDetails.explanation());
            throw new ToolRegistry.FatalLlmException(stopDetails.explanation());
        }
        throw new ToolRegistry.ToolCallException(
                ai.brokk.tools.ToolExecutionResult.Status.INTERNAL_ERROR, stopDetails.explanation());
    }

    @Override
    protected List<String> calculateAllowedToolNames() {
        var parentNames = super.calculateAllowedToolNames();
        // Parent may return immutable list in beast mode, so always create a new mutable list
        var names = new ArrayList<>(parentNames);
        if (io instanceof Chrome && objective != SearchPrompts.Objective.TASKS_ONLY) {
            if (!names.contains("askHuman")) {
                names.add("askHuman");
            }
        }
        return names;
    }
}
