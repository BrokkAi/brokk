package ai.brokk.agents;

import ai.brokk.IAppContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.Llm;
import ai.brokk.TaskResult;
import ai.brokk.tools.ShellTools;
import ai.brokk.tools.ToolExecutionHelper;
import ai.brokk.tools.ToolExecutionResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;

/**
 * A lightweight agent that executes shell commands on behalf of the user.
 * Has a permissive prompt and loops until the task is complete or fails.
 */
public class ShellAgent {
    private static final Logger logger = LogManager.getLogger(ShellAgent.class);
    private static final int MAX_TURNS = 10;

    private static final String SYSTEM_PROMPT =
            """
            You are the Shell Agent, a command-line executor with full access to the system shell.
            Your job is to run shell commands to accomplish the user's request.

            You have the `runShellCommand` tool to execute any command. The user will be prompted
            to approve each command before it runs. Always attempt commands directly -- never refuse
            or suggest the user run them manually. If a command fails, analyze the error and try
            a different approach.

            When you have accomplished the task or determined it cannot be done, call `finish`
            with a summary of what happened.
            """;

    private final IAppContextManager cm;
    private final StreamingChatModel model;
    private final IConsoleIO io;
    private final String task;

    public ShellAgent(IAppContextManager cm, StreamingChatModel model, String task) {
        this.cm = cm;
        this.model = model;
        this.io = cm.getIo();
        this.task = task;
    }

    @Blocking
    public String execute() throws InterruptedException {
        var shellTools = new ShellTools(cm);
        var toolRegistry = cm.getToolRegistry()
                .builder()
                .register(shellTools)
                .register(this)
                .build();
        var allowedTools = List.of("runShellCommand", "finish");
        var toolContext = new ToolContext(toolRegistry.getTools(allowedTools), ToolChoice.REQUIRED, toolRegistry);

        var llm = cm.getLlm(new Llm.Options(model, "Shell", TaskResult.Type.SEARCH).withEcho());
        llm.setOutput(io);

        var messages = new ArrayList<ChatMessage>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        messages.add(new UserMessage(task));

        for (int turn = 0; turn < MAX_TURNS; turn++) {
            var response = llm.sendRequest(messages, toolContext);
            if (response.error() != null) {
                logger.error("Shell Agent LLM error: {}", response.error().getMessage());
                return "Shell Agent encountered an LLM error: "
                        + response.error().getMessage();
            }

            var ai = response.aiMessage();
            messages.add(ai);

            if (ai.toolExecutionRequests() == null || ai.toolExecutionRequests().isEmpty()) {
                return ai.text() != null ? ai.text() : "Shell Agent completed without output.";
            }

            for (var request : ai.toolExecutionRequests()) {
                if ("finish".equals(request.name())) {
                    var result = toolRegistry.executeTool(request);
                    return result.resultText();
                }
                var result = ToolExecutionHelper.executeWithApproval(io, toolRegistry, request);
                llm.recordToolExecution(result);
                messages.add(result.toMessage());

                if (result.status() == ToolExecutionResult.Status.FATAL) {
                    return "Shell Agent failed: " + result.resultText();
                }
            }
        }

        return "Shell Agent reached maximum turns (%d) without completing.".formatted(MAX_TURNS);
    }

    @Tool("Report the result of the shell task and finish execution.")
    @SuppressWarnings("UnusedMethod")
    public String finish(@P("Summary of what was accomplished or why the task could not be completed") String summary) {
        return summary;
    }
}
