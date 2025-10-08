package io.github.jbellis.brokk.agents;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.output.TokenUsage;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.TaskResult.StopDetails;
import io.github.jbellis.brokk.TaskResult.StopReason;
import io.github.jbellis.brokk.prompts.ArchitectPrompts;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.tools.ToolExecutionResult;
import io.github.jbellis.brokk.tools.ToolRegistry;
import io.github.jbellis.brokk.util.LogDescription;
import io.github.jbellis.brokk.util.Messages;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

public class ArchitectAgent {
    private static final Logger logger = LogManager.getLogger(ArchitectAgent.class);

    private final IConsoleIO io;

    // Helper record to associate a SearchAgent task Future with its request
    private record SearchTask(ToolExecutionRequest request, Future<ToolExecutionResult> future) {}

    private final ContextManager cm;
    private final StreamingChatModel planningModel;
    private final StreamingChatModel codeModel;
    private final ToolRegistry toolRegistry;
    private final String goal;
    // scope is explicit so we can use its changed-files-tracking feature w/ Code Agent's results
    private final ContextManager.TaskScope scope;
    // History of this agent's interactions
    private final List<ChatMessage> architectMessages = new ArrayList<>();

    private TokenUsage totalUsage = new TokenUsage(0, 0);
    private boolean offerUndoToolNext = false;

    // When CodeAgent succeeds, we immediately declare victory without another LLM round.
    private boolean codeAgentJustSucceeded = false;

    /**
     * Constructs a BrokkAgent that can handle multi-step tasks and sub-tasks.
     *
     * @param codeModel
     * @param goal The initial user instruction or goal for the agent.
     */
    public ArchitectAgent(
            ContextManager contextManager,
            StreamingChatModel planningModel,
            StreamingChatModel codeModel,
            String goal,
            ContextManager.TaskScope scope) {
        this.cm = contextManager;
        this.planningModel = planningModel;
        this.codeModel = codeModel;
        this.toolRegistry = contextManager.getToolRegistry();
        this.goal = goal;
        this.io = contextManager.getIo();
        this.scope = scope;
    }

    /** A tool for finishing the plan with a final answer. Similar to 'answerSearch' in SearchAgent. */
    @Tool(
            "Provide a final answer to the multi-step project. Use this when you're done or have everything you need. Do not combine with other tools.")
    public String projectFinished(
            @P("A final explanation or summary addressing all tasks. Format it in Markdown if desired.")
                    String finalExplanation) {
        var msg = "# Architect complete\n\n%s".formatted(finalExplanation);
        logger.debug(msg);
        io.llmOutput(msg, ChatMessageType.AI, true, false);

        return finalExplanation;
    }

    /** A tool to abort the plan if you cannot proceed or if it is irrelevant. */
    @Tool(
            "Abort the entire project. Use this if the tasks are impossible or out of scope. Do not combine with other tools.")
    public String abortProject(@P("Explain why the project must be aborted.") String reason) {
        var msg = "# Architect aborted\n\n%s".formatted(reason);
        logger.debug(msg);
        io.llmOutput(msg, ChatMessageType.AI, true, false);

        return reason;
    }

    private static class FatalLlmException extends RuntimeException { // Made static
        public FatalLlmException(String message) {
            super(message);
        }
    }

    /**
     * A tool that invokes the CodeAgent to solve the current top task using the given instructions. The instructions
     * can incorporate the stack's current top task or anything else.
     */
    @Tool(
            "Invoke the Code Agent to solve or implement the current task. Provide complete instructions. Only the Workspace and your instructions are visible to the Code Agent, NOT the entire chat history; you must therefore provide appropriate context for your instructions. If you expect your changes to temporarily break the build and plan to fix them in later steps, set 'deferBuild' to true to defer build/verification.")
    public String callCodeAgent(
            @P(
                            "Detailed instructions for the CodeAgent referencing the current project. Code Agent can figure out how to change the code at the syntax level but needs clear instructions of what exactly you want changed")
                    String instructions,
            @P(
                            "Defer build/verification for this CodeAgent call. Set to true when your changes are an intermediate step that will temporarily break the build")
                    boolean deferBuild)
            throws FatalLlmException, InterruptedException {
        addPlanningToHistory();

        logger.debug("callCodeAgent invoked with instructions: {}, deferBuild={}", instructions, deferBuild);

        io.llmOutput("Code Agent engaged: " + instructions, ChatMessageType.CUSTOM, true, false);
        var agent = new CodeAgent(cm, codeModel);
        var opts = new HashSet<CodeAgent.Option>();
        if (deferBuild) {
            opts.add(CodeAgent.Option.DEFER_BUILD);
        }
        var result = agent.runTask(instructions, opts);
        var stopDetails = result.stopDetails();
        var reason = stopDetails.reason();
        scope.append(result);

        if (result.stopDetails().reason() == TaskResult.StopReason.SUCCESS) {
            var resultString = "CodeAgent finished! Details are in the Workspace messages.";
            logger.debug("callCodeAgent finished");
            this.codeAgentJustSucceeded = !deferBuild && !result.changedFiles().isEmpty();
            return resultString;
        }

        // For non-SUCCESS outcomes:
        // throw errors that should halt the architect
        if (reason == TaskResult.StopReason.INTERRUPTED) {
            throw new InterruptedException();
        }
        if (reason == TaskResult.StopReason.LLM_ERROR) {
            logger.error("Fatal LLM error during CodeAgent execution: {}", stopDetails.explanation());
            throw new FatalLlmException(stopDetails.explanation());
        }

        // For other failures (PARSE_ERROR, APPLY_ERROR, BUILD_ERROR, etc.), explain how to recover
        this.offerUndoToolNext = true;
        var resultString =
                """
                        CodeAgent was not able to get to a clean build. Details are in the Workspace.
                        Changes were made but can be undone with 'undoLastChanges'
                        if CodeAgent made negative progress; you will have to determine this from the messages history and the
                        current Workspace contents.
                        """;
        logger.debug("failed callCodeAgent");
        return resultString;
    }

    private void addPlanningToHistory() {
        var messages = io.getLlmRawMessages();
        if (messages.isEmpty()) {
            return;
        }

        scope.append(resultWithMessages(StopReason.SUCCESS));
    }

    @Tool(
            "Undo the changes made by the most recent CodeAgent call. This should only be used if Code Agent left the project farther from the goal than when it started.")
    public String undoLastChanges() {
        logger.debug("undoLastChanges invoked");
        io.showNotification(IConsoleIO.NotificationRole.INFO, "Undoing last CodeAgent changes...");
        if (cm.undoContext()) {
            var resultMsg = "Successfully reverted the last CodeAgent changes.";
            logger.debug(resultMsg);
            io.showNotification(IConsoleIO.NotificationRole.INFO, resultMsg);
            return resultMsg;
        } else {
            var resultMsg = "Nothing to undo (concurrency bug?)";
            logger.debug(resultMsg);
            io.showNotification(IConsoleIO.NotificationRole.INFO, resultMsg);
            return resultMsg;
        }
    }

    /**
     * A tool that invokes the SearchAgent to perform searches and analysis based on a query. The SearchAgent will
     * decide which specific search/analysis tools to use (e.g., searchSymbols, getFileContents). The results are added
     * as a context fragment.
     */
    @Tool(
            "Invoke the Search Agent to find information relevant to the given query. The Workspace is visible to the Search Agent. Searching is much slower than adding content to the Workspace directly if you know what you are looking for, but the Agent can find things that you don't know the exact name of. ")
    public String callSearchAgent(
            @P("The search query or question for the SearchAgent. Query in English (not just keywords)") String query)
            throws FatalLlmException {
        addPlanningToHistory();
        logger.debug("callSearchAgent invoked with query: {}", query);

        // Instantiate and run SearchAgent
        io.llmOutput("Search Agent engaged: " + query, ChatMessageType.CUSTOM);
        var searchAgent = new SearchAgent(query, cm, planningModel, EnumSet.of(SearchAgent.Terminal.WORKSPACE));
        var result = searchAgent.execute();
        scope.append(result);

        if (result.stopDetails().reason() == TaskResult.StopReason.LLM_ERROR) {
            throw new FatalLlmException(result.stopDetails().explanation());
        }

        if (result.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
            logger.debug("SearchAgent returned non-success for query {}: {}", query, result.stopDetails());
            return result.stopDetails().toString();
        }

        var stringResult = "Search complete";
        logger.debug(stringResult);
        return stringResult;
    }

    /**
     * Run the multi-step project until we either produce a final answer, abort, or run out of tasks. This uses an
     * iterative approach, letting the LLM decide which tool to call each time.
     */
    public TaskResult execute() {
        // First turn: try CodeAgent directly with the goal instructions
        if (cm.liveContext().isEmpty()) {
            throw new IllegalArgumentException(); // Architect should only be invoked by Task List harness
        }

        try {
            return executeInternal();
        } catch (InterruptedException e) {
            return resultWithMessages(StopReason.INTERRUPTED);
        }
    }

    /**
     * Execute Architect with a SearchAgent pass first.
     *
     * <p>ACHTUNG! Unlike bare execute(), executeWithSearch appends both results to the provided scope.
     *
     * <p>Returns the search result if it fails, otherwise returns the Architect result.
     */
    public TaskResult executeWithSearch(ContextManager.TaskScope scope) {
        // Run Search first using the scan model (fast, token-friendly)
        var scanModel = cm.getService().getScanModel();
        var searchAgent = new SearchAgent(goal, cm, scanModel, EnumSet.of(SearchAgent.Terminal.WORKSPACE));
        io.llmOutput("Search Agent engaged: " + goal, ChatMessageType.CUSTOM);
        var searchResult = searchAgent.execute();
        scope.append(searchResult);
        if (searchResult.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
            return searchResult;
        }

        // Run Architect proper and append its result to scope
        var archResult = this.execute();
        scope.append(archResult);
        return archResult;
    }

    private TaskResult executeInternal() throws InterruptedException {
        // run code agent first
        try {
            var initialSummary = callCodeAgent(goal, false);
            architectMessages.add(new AiMessage("Initial CodeAgent attempt:\n" + initialSummary));
        } catch (FatalLlmException e) {
            var errorMessage = "Fatal LLM error executing initial Code Agent: %s".formatted(e.getMessage());
            io.showNotification(IConsoleIO.NotificationRole.INFO, errorMessage);
            return resultWithMessages(StopReason.LLM_ERROR);
        }

        // If CodeAgent succeeded, immediately finish without entering planning loop
        if (this.codeAgentJustSucceeded) {
            return codeAgentSuccessResult();
        }

        var llm = cm.getLlm(planningModel, "Architect: " + goal);
        var modelsService = cm.getService();

        while (true) {
            io.llmOutput("\n# Planning", ChatMessageType.AI, true, false);

            // Determine active models and their minimum input token limit
            var models = new ArrayList<StreamingChatModel>();
            models.add(this.planningModel);
            models.add(this.codeModel);
            int minInputTokenLimit = models.stream()
                    .mapToInt(modelsService::getMaxInputTokens)
                    .filter(limit -> limit > 0)
                    .min()
                    .orElse(64_000);

            if (minInputTokenLimit == 64_000) {
                logger.warn("Could not determine a valid minimum input token limit from active models {}", models);
            }

            // Calculate current workspace token size
            var workspaceContentMessages =
                    new ArrayList<>(CodePrompts.instance.getWorkspaceContentsMessages(cm.liveContext()));
            int workspaceTokenSize = Messages.getApproximateMessageTokens(workspaceContentMessages);

            // Build the prompt messages, including history and conditional warnings
            var messages = buildPrompt(workspaceTokenSize, minInputTokenLimit, workspaceContentMessages);

            // Figure out which tools are allowed in this step (hard-coded: Workspace, CodeAgent, Search (only with
            // Undo), Undo, Finish/Abort)
            var toolSpecs = new ArrayList<ToolSpecification>();
            var criticalWorkspaceSize = minInputTokenLimit < Integer.MAX_VALUE
                    && workspaceTokenSize > (ArchitectPrompts.WORKSPACE_CRITICAL_THRESHOLD * minInputTokenLimit);

            if (criticalWorkspaceSize) {
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        String.format(
                                "Workspace size (%,d tokens) is %.0f%% of limit %,d. Tool usage restricted to workspace modification.",
                                workspaceTokenSize,
                                (double) workspaceTokenSize / minInputTokenLimit * 100,
                                minInputTokenLimit));
                toolSpecs.addAll(toolRegistry.getTools(this, List.of("projectFinished", "abortProject")));

                var allowedWorkspaceModTools = new ArrayList<String>();
                allowedWorkspaceModTools.add("dropWorkspaceFragments");
                allowedWorkspaceModTools.add("addFileSummariesToWorkspace");
                allowedWorkspaceModTools.add("addTextToWorkspace");
                allowedWorkspaceModTools.add("addFilesToWorkspace");
                allowedWorkspaceModTools.add("addUrlContentsToWorkspace");

                toolSpecs.addAll(toolRegistry.getRegisteredTools(
                        allowedWorkspaceModTools.stream().distinct().toList()));
            } else {
                // Default tool population logic
                var workspaceTools = List.of(
                        "addFilesToWorkspace",
                        "addFileSummariesToWorkspace",
                        "addUrlContentsToWorkspace",
                        "addTextToWorkspace",
                        "dropWorkspaceFragments");
                toolSpecs.addAll(toolRegistry.getRegisteredTools(workspaceTools));

                // Always allow Code Agent
                toolSpecs.addAll(toolRegistry.getTools(this, List.of("callCodeAgent")));

                // Offer Undo if we previously set the flag. Search is only offered when Undo is offered.
                if (this.offerUndoToolNext) {
                    toolSpecs.addAll(toolRegistry.getTools(this, List.of("undoLastChanges")));
                    toolSpecs.addAll(toolRegistry.getTools(this, List.of("callSearchAgent")));
                }

                // Always allow terminal tools
                toolSpecs.addAll(toolRegistry.getTools(this, List.of("projectFinished", "abortProject")));
            }

            // Ask the LLM for the next step
            var result = llm.sendRequest(messages, new ToolContext(toolSpecs, ToolChoice.REQUIRED, this), true);

            if (result.error() != null) {
                logger.debug(
                        "Error from LLM while deciding next action: {}",
                        result.error().getMessage());
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "Error from LLM while deciding next action (see debug log for details)");
                return resultWithMessages(StopReason.LLM_ERROR);
            }
            // show thinking
            if (!result.text().isBlank()) {
                io.llmOutput("\n" + result.text(), ChatMessageType.AI);
            }

            totalUsage = TokenUsage.sum(
                    totalUsage, castNonNull(result.originalResponse()).tokenUsage());
            // Add the request and response to message history
            var aiMessage = ToolRegistry.removeDuplicateToolRequests(result.aiMessage());
            architectMessages.add(messages.getLast());
            architectMessages.add(aiMessage);

            var deduplicatedRequests = new LinkedHashSet<>(result.toolRequests());
            logger.debug("Unique tool requests are {}", deduplicatedRequests);
            io.llmOutput(
                    "\nTool call(s): %s"
                            .formatted(deduplicatedRequests.stream()
                                    .map(req -> "`" + req.name() + "`")
                                    .collect(Collectors.joining(", "))),
                    ChatMessageType.AI);

            // execute tool calls in the following order:
            // 1. projectFinished
            // 2. abortProject
            // 3. (workspace and other tools)
            // 4. searchAgent (background)
            // 5. codeAgent (serially)
            ToolExecutionRequest answerReq = null, abortReq = null;
            var searchAgentReqs = new ArrayList<ToolExecutionRequest>();
            var codeAgentReqs = new ArrayList<ToolExecutionRequest>();
            var otherReqs = new ArrayList<ToolExecutionRequest>();
            for (var req : deduplicatedRequests) {
                switch (req.name()) {
                    case "projectFinished" -> answerReq = req;
                    case "abortProject" -> abortReq = req;
                    case "callSearchAgent" -> searchAgentReqs.add(req);
                    case "callCodeAgent" -> codeAgentReqs.add(req);
                    default -> otherReqs.add(req);
                }
            }

            // If we see "projectFinished" or "abortProject", handle it and then exit.
            // If these final/abort calls are present together with other tool calls in the same LLM response,
            // do NOT execute them. Instead, create ToolExecutionResult entries indicating the call was ignored.
            boolean multipleRequests = deduplicatedRequests.size() > 1;

            if (answerReq != null) {
                if (multipleRequests) {
                    var ignoredMsg =
                            "Ignored 'projectFinished' because other tool calls were present in the same turn.";
                    var toolResult = ToolExecutionResult.failure(answerReq, ignoredMsg);
                    // Record the ignored result in the architect message history so planning history reflects this.
                    architectMessages.add(ToolExecutionResultMessage.from(answerReq, toolResult.resultText()));
                    logger.info("projectFinished ignored due to other tool calls present: {}", ignoredMsg);
                } else {
                    logger.debug("LLM decided to projectFinished. We'll finalize and stop");
                    var toolResult = toolRegistry.executeTool(this, answerReq);
                    io.llmOutput("Project final answer: " + toolResult.resultText(), ChatMessageType.AI);
                    return codeAgentSuccessResult();
                }
            }

            if (abortReq != null) {
                if (multipleRequests) {
                    var ignoredMsg = "Ignored 'abortProject' because other tool calls were present in the same turn.";
                    var toolResult = ToolExecutionResult.failure(abortReq, ignoredMsg);
                    architectMessages.add(ToolExecutionResultMessage.from(abortReq, toolResult.resultText()));
                    logger.info("abortProject ignored due to other tool calls present: {}", ignoredMsg);
                } else {
                    logger.debug("LLM decided to abortProject. We'll finalize and stop");
                    var toolResult = toolRegistry.executeTool(this, abortReq);
                    io.llmOutput("Project aborted: " + toolResult.resultText(), ChatMessageType.AI);
                    return resultWithMessages(StopReason.LLM_ABORTED);
                }
            }

            // Execute remaining tool calls in the desired order:
            otherReqs.sort(Comparator.comparingInt(req -> getPriorityRank(req.name())));
            for (var req : otherReqs) {
                var toolResult = toolRegistry.executeTool(this, req);
                architectMessages.add(ToolExecutionResultMessage.from(req, toolResult.resultText()));
                logger.debug("Executed tool '{}' => result: {}", req.name(), toolResult.resultText());
            }

            // Submit search agent tasks to run in the background (offered only when Undo is offered)
            var searchAgentTasks = new ArrayList<SearchTask>();
            for (var req : searchAgentReqs) {
                Callable<ToolExecutionResult> task = () -> {
                    var toolResult = toolRegistry.executeTool(this, req);
                    logger.debug("Finished SearchAgent task for request: {}", req.name());
                    return toolResult;
                };
                var taskDescription = "SearchAgent: " + LogDescription.getShortDescription(req.arguments());
                var future = cm.submitBackgroundTask(taskDescription, task);
                searchAgentTasks.add(new SearchTask(req, future));
            }

            // Collect search results, handling potential interruptions/cancellations
            var interrupted = false;
            for (var searchTask : searchAgentTasks) {
                var request = searchTask.request();
                var future = searchTask.future();
                try {
                    // If already interrupted, don't wait, just try to cancel
                    if (interrupted) {
                        future.cancel(true);
                        continue;
                    }
                    var toolResult = future.get(); // Wait for completion
                    architectMessages.add(
                            ToolExecutionResultMessage.from(toolResult.request(), toolResult.resultText()));
                    logger.debug(
                            "Collected result for tool '{}' => result: {}",
                            toolResult.request().name(),
                            toolResult.resultText());
                } catch (InterruptedException e) {
                    logger.warn("SearchAgent task for request '{}' was cancelled", request.name());
                    future.cancel(true);
                    interrupted = true;
                } catch (ExecutionException e) {
                    logger.warn("Error executing SearchAgent task '{}'", request.name(), e.getCause());
                    if (e.getCause() instanceof FatalLlmException) {
                        var errorMessage = "Fatal LLM error executing Search Agent: %s"
                                .formatted(Objects.toString(e.getCause().getMessage(), "Unknown error"));
                        io.showNotification(IConsoleIO.NotificationRole.INFO, errorMessage);
                        break;
                    }
                    var errorMessage = "Error executing Search Agent: %s"
                            .formatted(Objects.toString(
                                    e.getCause() != null ? e.getCause().getMessage() : "Unknown error",
                                    "Unknown execution error"));
                    architectMessages.add(ToolExecutionResultMessage.from(request, errorMessage));
                }
            }
            if (interrupted) {
                throw new InterruptedException();
            }

            // code agent calls are done serially
            for (var req : codeAgentReqs) {
                ToolExecutionResult toolResult;
                try {
                    toolResult = toolRegistry.executeTool(this, req);
                } catch (FatalLlmException e) {
                    return resultWithMessages(StopReason.LLM_ERROR);
                }

                architectMessages.add(ToolExecutionResultMessage.from(req, toolResult.resultText()));
                logger.debug("Executed tool '{}' => result: {}", req.name(), toolResult.resultText());
            }

            // If CodeAgent succeeded (after making edits), automatically declare victory and stop.
            if (this.codeAgentJustSucceeded) {
                return codeAgentSuccessResult();
            }
        }
    }

    private @NotNull TaskResult codeAgentSuccessResult() {
        // we've already added the code agent's result to history and we don't have anything extra to add to that here
        return new TaskResult(cm, "Architect: " + goal, List.of(), Set.of(), new StopDetails(StopReason.SUCCESS));
    }

    private TaskResult resultWithMessages(StopReason reason) {
        // include the messages we exchanged with the LLM for any planning steps since we ran a sub-agent
        return new TaskResult(cm, "Architect: " + goal, io.getLlmRawMessages(), Set.of(), new StopDetails(reason));
    }

    /** Helper method to get priority rank for tool names. Lower number means higher priority. */
    private int getPriorityRank(String toolName) {
        return switch (toolName) {
            case "dropWorkspaceFragments" -> 1;
            case "addFilesToWorkspace" -> 2;
            case "addFileSummariesToWorkspace" -> 3;
            case "addTextToWorkspace" -> 4;
            case "addUrlContentsToWorkspace" -> 5;
            default -> 7; // all other tools have lowest priority
        };
    }

    /**
     * Build the system/user messages for the LLM. This includes the standard system prompt, workspace contents,
     * history, agent's session messages, and the final user message with the goal and conditional workspace warnings.
     */
    private List<ChatMessage> buildPrompt(
            int workspaceTokenSize, int minInputTokenLimit, List<ChatMessage> precomputedWorkspaceMessages)
            throws InterruptedException {
        var messages = new ArrayList<ChatMessage>();
        // System message defines the agent's role and general instructions
        var reminder = CodePrompts.instance.architectReminder(cm.getService(), planningModel);
        messages.add(ArchitectPrompts.instance.systemMessage(cm, reminder));

        // Workspace contents are added directly
        messages.addAll(precomputedWorkspaceMessages);

        // Add auto-context as a separate message/ack pair
        var topClassesRaw = cm.liveContext().buildAutoContext(10).text();
        if (!topClassesRaw.isBlank()) {
            var topClassesText =
                    """
                            <related_classes>
                            Here are some classes that may be related to what is in your Workspace. They are not yet part of the Workspace!
                            If relevant, you should explicitly add them with addClassSummariesToWorkspace or addClassesToWorkspace so they are
                            visible to Code Agent. If they are not relevant, just ignore them.

                            %s
                            </related_classes>
                            """
                            .stripIndent()
                            .formatted(topClassesRaw);
            messages.add(new UserMessage(topClassesText));
            messages.add(new AiMessage("Okay, I will consider these related classes."));
        }

        // History from previous tasks/sessions
        messages.addAll(cm.getHistoryMessages());
        // This agent's own conversational history for the current goal
        messages.addAll(architectMessages);
        // Final user message with the goal and specific instructions for this turn, including workspace warnings
        messages.add(new UserMessage(
                ArchitectPrompts.instance.getFinalInstructions(cm, goal, workspaceTokenSize, minInputTokenLimit)));
        return messages;
    }
}
