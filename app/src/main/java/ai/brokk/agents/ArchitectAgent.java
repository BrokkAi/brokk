package ai.brokk.agents;

import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.Llm;
import ai.brokk.Service.ModelConfig;
import ai.brokk.TaskResult;
import ai.brokk.TaskResult.StopReason;
import ai.brokk.context.Context;
import ai.brokk.gui.Chrome;
import ai.brokk.prompts.ArchitectPrompts;
import ai.brokk.prompts.CodePrompts;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.tools.WorkspaceTools;
import ai.brokk.util.LogDescription;
import ai.brokk.util.Messages;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ArchitectAgent {
    private static final Logger logger = LogManager.getLogger(ArchitectAgent.class);

    private final IConsoleIO io;

    // Helper record to associate a SearchAgent task Future with its request and result
    private record SearchTask(ToolExecutionRequest request, Future<SearchTaskResult> future) {}

    // Result of executing a single search request: both the tool execution result and the updated context
    private record SearchTaskResult(ToolExecutionResult toolResult, Context context) {}

    private final ContextManager cm;
    private final StreamingChatModel planningModel;
    private final StreamingChatModel codeModel;
    private final String goal;
    // scope is explicit so we can use its changed-files-tracking feature w/ Code Agent's results
    private final ContextManager.TaskScope scope;
    // Local working context snapshot for this agent
    private Context context;
    // History of this agent's interactions
    private final List<ChatMessage> architectMessages = new ArrayList<>();

    private TokenUsage totalUsage = new TokenUsage(0, 0);
    private boolean offerUndoToolNext = false;

    // When CodeAgent succeeds, we immediately declare victory without another LLM round.
    private boolean codeAgentJustSucceeded = false;

    private static final ThreadLocal<TaskResult> threadlocalSearchResult = new ThreadLocal<>();

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
        this.goal = goal;
        this.io = contextManager.getIo();
        this.scope = scope;
        this.context = contextManager.liveContext();
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

        io.llmOutput("**Code Agent** engaged: " + instructions, ChatMessageType.AI, true, false);
        var agent = new CodeAgent(cm, codeModel);
        var opts = new HashSet<CodeAgent.Option>();
        if (deferBuild) {
            opts.add(CodeAgent.Option.DEFER_BUILD);
        }
        var result = agent.runTask(context, List.of(), instructions, opts);
        var stopDetails = result.stopDetails();
        var reason = stopDetails.reason();
        // Update local context with the CodeAgent's resulting context
        var initialContext = context;
        context = scope.append(result);

        if (result.stopDetails().reason() == StopReason.SUCCESS) {
            var resultString = deferBuild
                    ? "CodeAgent finished! Details are in the Workspace messages."
                    : "CodeAgent finished with a successful build! Details are in the Workspace messages.";
            logger.debug("callCodeAgent finished successfully");
            codeAgentJustSucceeded = !deferBuild
                    && !context.freeze().getDiff(initialContext.freeze()).isEmpty();
            return resultString;
        }

        // For non-SUCCESS outcomes:
        // throw errors that should halt the architect
        if (reason == StopReason.INTERRUPTED) {
            throw new InterruptedException();
        }
        if (reason == StopReason.LLM_ERROR) {
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
        context = scope.append(resultWithMessages(StopReason.SUCCESS, "Architect planned for: " + goal));
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
            // Synchronize local context with latest global state after undo
            context = cm.topContext();
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
            throws FatalLlmException, InterruptedException {
        addPlanningToHistory();
        logger.debug("callSearchAgent invoked with query: {}", query);

        // Instantiate and run SearchAgent
        io.llmOutput("**Search Agent** engaged: " + query, ChatMessageType.AI);
        var searchAgent =
                new SearchAgent(context, query, planningModel, EnumSet.of(SearchAgent.Terminal.WORKSPACE), scope);
        var tr = searchAgent.scanInitialContext();
        context = scope.append(tr);
        var result = searchAgent.execute();
        // DO NOT set this.context here, it is not threadsafe; the main agent loop will update it via the threadlocal
        threadlocalSearchResult.set(result);

        if (result.stopDetails().reason() == StopReason.LLM_ERROR) {
            throw new FatalLlmException(result.stopDetails().explanation());
        }

        if (result.stopDetails().reason() != StopReason.SUCCESS) {
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
        if (context.isEmpty()) {
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
    public TaskResult executeWithScan() throws InterruptedException {
        // ContextAgent Scan
        var scanModel = cm.getService().getScanModel();
        var searchAgent =
                new SearchAgent(context, goal, scanModel, EnumSet.of(SearchAgent.Terminal.WORKSPACE), this.scope);
        var scanResult = searchAgent.scanInitialContext();
        context = scope.append(scanResult);

        // Run Architect proper
        var archResult = this.execute();
        context = scope.append(archResult);
        return archResult;
    }

    /**
     * Run the multi-step project loop: plan, choose tools, execute, repeat.
     *
     * Strategy:
     * 1) Try CodeAgent first with the goal.
     * 2) Enter planning loop. If the workspace is critical, restrict tools to workspace-trimming set.
     * 3) If the planning LLM returns ContextTooLarge, switch to GEMINI_2_5_PRO and run a single
     *    critical-turn (restricted tools) to shrink the workspace, then proceed with the result.
     */
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
        if (codeAgentJustSucceeded) {
            return codeAgentSuccessResult();
        }

        var llm = cm.getLlm(new Llm.Options(planningModel, "Architect: " + goal).withEcho());
        var modelsService = cm.getService();

        while (true) {
            io.llmOutput("\n**Brokk Architect** is preparing the next actions…\n\n", ChatMessageType.AI, true, false);

            // Determine active models and their maximum allowed input tokens
            var models = new ArrayList<StreamingChatModel>();
            models.add(this.planningModel);
            models.add(this.codeModel);
            int maxInputTokens = models.stream()
                    .mapToInt(modelsService::getMaxInputTokens)
                    .min()
                    .orElseThrow();

            // Calculate current workspace token size
            var workspaceContentMessages = new ArrayList<>(CodePrompts.instance.getWorkspaceContentsMessages(context));
            int workspaceTokenSize = Messages.getApproximateMessageTokens(workspaceContentMessages);

            // Build the prompt messages, including history and conditional warnings
            var messages = buildPrompt(workspaceTokenSize, maxInputTokens, workspaceContentMessages);

            // Create a local registry for this planning turn
            var wst = new WorkspaceTools(this.context);
            var tr = cm.getToolRegistry().builder().register(this).register(wst).build();

            // Decide tool availability for this step
            var toolSpecs = new ArrayList<ToolSpecification>();
            var criticalWorkspaceSize =
                    workspaceTokenSize > (ArchitectPrompts.WORKSPACE_CRITICAL_THRESHOLD * maxInputTokens);

            ToolContext toolContext;
            if (criticalWorkspaceSize) {
                notifyCriticalWorkspaceRestriction(workspaceTokenSize, maxInputTokens);
                var allowed = criticalAllowedTools();
                toolSpecs.addAll(tr.getTools(allowed));
                toolContext = new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr);
            } else {
                // Default tool population logic
                var allowed = new ArrayList<String>();
                allowed.add("addFilesToWorkspace");
                allowed.add("addFileSummariesToWorkspace");
                allowed.add("addUrlContentsToWorkspace");
                allowed.add("appendNote");
                allowed.add("dropWorkspaceFragments");
                allowed.add("explainCommit");

                if (io instanceof Chrome) {
                    allowed.add("askHuman");
                }

                // Agent tools
                allowed.add("callCodeAgent");

                if (this.offerUndoToolNext) {
                    allowed.add("undoLastChanges");
                    allowed.add("callSearchAgent");
                }

                // Terminals
                allowed.add("projectFinished");
                allowed.add("abortProject");

                toolSpecs.addAll(tr.getTools(allowed));
                toolContext = new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr);
            }

            // Ask the LLM for the next step
            var result = llm.sendRequest(messages, toolContext);

            // Handle errors, with special recovery for ContextTooLarge
            if (result.error() != null) {
                if (!(result.error() instanceof dev.langchain4j.exception.ContextTooLargeException)) {
                    logger.debug(
                            "Error from LLM while deciding next action: {}",
                            result.error().getMessage());
                    io.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            "Error from LLM while deciding next action (see debug log for details)");
                    return resultWithMessages(StopReason.LLM_ERROR);
                }

                // we know workspace is too large; we don't know by how much so we'll guess 0.8 as the threshold
                messages = buildPrompt(workspaceTokenSize, (int) (workspaceTokenSize * 0.8), workspaceContentMessages);
                var currentModelTokens = modelsService.getMaxInputTokens(this.planningModel);
                var fallbackModel = requireNonNull(modelsService.getModel(ai.brokk.Service.GEMINI_2_5_PRO));
                var fallbackModelTokens = modelsService.getMaxInputTokens(fallbackModel);
                if (fallbackModelTokens < currentModelTokens * 1.2) {
                    return resultWithMessages(StopReason.LLM_ERROR);
                }
                logger.warn(
                        "Context too large for current model; attempting emergency retry with {} (tokens: {} vs {})",
                        ai.brokk.Service.GEMINI_2_5_PRO,
                        fallbackModelTokens,
                        currentModelTokens);

                // Emergency LLM restricted to critical workspace tools
                var emergencyLlm = cm.getLlm(
                        new Llm.Options(fallbackModel, "Architect emergency (context too large): " + goal).withEcho());
                notifyCriticalWorkspaceRestriction(workspaceTokenSize, fallbackModelTokens);
                var emergencyAllowed = criticalAllowedTools();
                var emergencyToolContext = new ToolContext(tr.getTools(emergencyAllowed), ToolChoice.REQUIRED, tr);

                var emergencyResult = emergencyLlm.sendRequest(messages, emergencyToolContext);
                if (emergencyResult.error() != null) {
                    logger.debug(
                            "Error from LLM during emergency reduced-context turn: {}",
                            emergencyResult.error().getMessage());
                    io.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            "Error from LLM during emergency reduced-context turn (see debug log for details)");
                    return resultWithMessages(StopReason.LLM_ERROR);
                }
                result = emergencyResult; // proceed with emergency result
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
                    var toolResult = tr.executeTool(answerReq);
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
                    var toolResult = tr.executeTool(abortReq);
                    io.llmOutput("Project aborted: " + toolResult.resultText(), ChatMessageType.AI);
                    return resultWithMessages(StopReason.LLM_ABORTED);
                }
            }

            // Execute remaining tool calls in the desired order (all use the local registry)
            otherReqs.sort(Comparator.comparingInt(req -> getPriorityRank(req.name())));
            for (var req : otherReqs) {
                wst.setContext(context);
                ToolExecutionResult toolResult = tr.executeTool(req);
                context = wst.getContext();
                architectMessages.add(ToolExecutionResultMessage.from(req, toolResult.resultText()));
                logger.debug("Executed tool '{}' => result: {}", req.name(), toolResult.resultText());
            }

            // Submit search agent tasks to run in the background (offered only when Undo is offered)
            // Each task gets its own isolated WorkspaceTools and Context, all seeded from the same baseContext
            var searchAgentTasks = new ArrayList<SearchTask>();
            for (var req : searchAgentReqs) {
                Callable<SearchTaskResult> task = () -> {
                    // Ensure a clean slate for this thread before invoking the tool
                    threadlocalSearchResult.remove();
                    var toolResult = tr.executeTool(req);
                    var saResult = requireNonNull(threadlocalSearchResult.get());
                    logger.debug("Finished SearchAgent task for request: {}", req.name());
                    return new SearchTaskResult(toolResult, saResult.context());
                };
                var taskDescription = "SearchAgent: " + LogDescription.getShortDescription(req.arguments());
                var future = cm.submitBackgroundTask(taskDescription, task);
                searchAgentTasks.add(new SearchTask(req, future));
            }

            // Collect search results in request order and merge deterministically
            var interrupted = false;
            var n = searchAgentTasks.size();
            for (int i = 0; i < n; i++) {
                var searchTask = searchAgentTasks.get(i);
                var request = searchTask.request();
                var future = searchTask.future();
                try {
                    if (interrupted) {
                        future.cancel(true);
                        continue;
                    }
                    var outcome = future.get();
                    context = context.union(outcome.context());
                    architectMessages.add(ToolExecutionResultMessage.from(
                            request, outcome.toolResult().resultText()));
                    logger.debug(
                            "Collected result for tool '{}' => result: {}",
                            request.name(),
                            outcome.toolResult().resultText());
                } catch (InterruptedException e) {
                    logger.warn("SearchAgent task for request '{}' was interrupted", request.name());
                    interrupted = true;
                    // cancel remaining
                    for (int j = i; j < n; j++) {
                        searchAgentTasks.get(j).future().cancel(true);
                    }
                } catch (ExecutionException e) {
                    logger.warn("Error executing SearchAgent task '{}'", request.name(), e.getCause());
                    // Record failure for this request but continue processing others
                    var errorMessage = "Error executing Search Agent: %s"
                            .formatted(Objects.toString(
                                    e.getCause() != null ? e.getCause().getMessage() : "Unknown error",
                                    "Unknown execution error"));
                    var failure = ToolExecutionResult.failure(request, errorMessage);
                    architectMessages.add(ToolExecutionResultMessage.from(request, failure.resultText()));
                }
            }
            if (interrupted) {
                throw new InterruptedException();
            }

            // code agent calls are done serially
            for (var req : codeAgentReqs) {
                ToolExecutionResult toolResult;
                try {
                    toolResult = tr.executeTool(req);
                } catch (FatalLlmException e) {
                    return resultWithMessages(StopReason.LLM_ERROR);
                }

                architectMessages.add(ToolExecutionResultMessage.from(req, toolResult.resultText()));
                logger.debug("Executed tool '{}' => result: {}", req.name(), toolResult.resultText());
            }

            // If CodeAgent succeeded (after making edits), automatically declare victory and stop.
            if (codeAgentJustSucceeded) {
                return codeAgentSuccessResult();
            }
        }
    }

    /**
     * Notifies the user that tool usage is being restricted due to large workspace size.
     */
    private void notifyCriticalWorkspaceRestriction(int workspaceTokenSize, int minInputTokenLimit) {
        io.showNotification(
                IConsoleIO.NotificationRole.INFO,
                String.format(
                        "Workspace size (%,d tokens) is %.0f%% of limit %,d. Tool usage restricted to workspace modification.",
                        workspaceTokenSize,
                        (double) workspaceTokenSize / Math.max(1, minInputTokenLimit) * 100,
                        minInputTokenLimit));
    }

    /**
     * Returns the list of tools allowed during a critical workspace turn. These tools are
     * limited to workspace management and safe terminal actions to help shrink context.
     */
    private List<String> criticalAllowedTools() {
        var allowed = new ArrayList<String>();
        allowed.add("projectFinished");
        allowed.add("abortProject");
        allowed.add("dropWorkspaceFragments");
        allowed.add("addFileSummariesToWorkspace");
        allowed.add("appendNote");
        if (io instanceof Chrome) {
            allowed.add("askHuman");
        }
        return allowed;
    }

    private TaskResult codeAgentSuccessResult() {
        // we've already added the code agent's result to history and we don't have anything extra to add to that here
        return new TaskResult(
                cm,
                "Architect finished work for: " + goal,
                io.getLlmRawMessages(),
                context,
                new TaskResult.StopDetails(StopReason.SUCCESS),
                new TaskResult.TaskMeta(TaskResult.Type.ARCHITECT, ModelConfig.from(planningModel, cm.getService())));
    }

    private TaskResult resultWithMessages(StopReason reason, String message) {
        // include the messages we exchanged with the LLM for any planning steps since we ran a sub-agent
        return new TaskResult(
                cm,
                message,
                io.getLlmRawMessages(),
                context,
                new TaskResult.StopDetails(reason),
                new TaskResult.TaskMeta(TaskResult.Type.ARCHITECT, ModelConfig.from(planningModel, cm.getService())));
    }

    private TaskResult resultWithMessages(StopReason reason) {
        // include the messages we exchanged with the LLM for any planning steps since we ran a sub-agent
        return resultWithMessages(reason, "Architect: " + goal);
    }

    /** Helper method to get priority rank for tool names. Lower number means higher priority. */
    private int getPriorityRank(String toolName) {
        return switch (toolName) {
            case "dropWorkspaceFragments" -> 1;
            case "appendNote", "askHuman" -> 2;
            case "addFilesToWorkspace" -> 3;
            case "addFileSummariesToWorkspace" -> 4;
            case "addUrlContentsToWorkspace" -> 5;
            default -> 7; // all other tools have lowest priority
        };
    }

    /**
     * Build the system/user messages for the LLM. This includes the standard system prompt, workspace contents,
     * history, agent's session messages, and the final user message with the goal and conditional workspace warnings.
     */
    private List<ChatMessage> buildPrompt(
            int workspaceTokenSize, int maxInputTokens, List<ChatMessage> precomputedWorkspaceMessages)
            throws InterruptedException {
        var messages = new ArrayList<ChatMessage>();
        // System message defines the agent's role and general instructions
        var reminder = CodePrompts.instance.architectReminder();
        messages.add(ArchitectPrompts.instance.systemMessage(cm, reminder));

        // Workspace contents are added directly
        messages.addAll(precomputedWorkspaceMessages);

        // Add related identifiers as a separate message/ack pair
        var related = context.buildRelatedIdentifiers(10);
        if (!related.isEmpty()) {
            var relatedBlock = ArchitectPrompts.formatRelatedFiles(related);
            var topFilesText =
                    """
                    <related_files>
                    Here are some files that may be related to what is in your Workspace, and the identifiers declared in each. They are not yet part of the Workspace!
                    If relevant, explicitly add them (e.g., summaries or sources) so they become visible to Code Agent. If they are not relevant, ignore them.

                    %s
                    </related_files>
                    """
                            .formatted(relatedBlock);
            messages.add(new UserMessage(topFilesText));
            messages.add(new AiMessage("Okay, I will consider these related files."));
        }

        // History from previous tasks/sessions
        messages.addAll(cm.getHistoryMessages());
        // This agent's own conversational history for the current goal
        messages.addAll(architectMessages);
        // Final user message with the goal and specific instructions for this turn, including workspace warnings
        messages.add(new UserMessage(
                ArchitectPrompts.instance.getFinalInstructions(cm, goal, workspaceTokenSize, maxInputTokens)));
        return messages;
    }
}
