package ai.brokk.agents;

import static java.lang.Math.min;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.AbstractService;
import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.LlmOutputMeta;
import ai.brokk.TaskEntry;
import ai.brokk.TaskResult;
import ai.brokk.TaskResult.StopReason;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextDelta;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.SpecialTextType;
import ai.brokk.exception.GlobalExceptionHandler;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.prompts.ArchitectPrompts;
import ai.brokk.prompts.WorkspacePrompts;
import ai.brokk.tools.DependencyTools;
import ai.brokk.tools.Destructive;
import ai.brokk.tools.ParallelSearch;
import ai.brokk.tools.ToolExecutionHelper;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.tools.WorkspaceTools;
import ai.brokk.util.BuildVerifier;
import ai.brokk.util.Messages;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.ContextTooLargeException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.output.TokenUsage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

public class ArchitectAgent {
    private static final Logger logger = LogManager.getLogger(ArchitectAgent.class);
    private static final int MAX_TURNS = 10;
    /**
     * Listener for ArchitectAgent events.
     */
    @FunctionalInterface
    public interface ArchitectListener {
        /**
         * Invoked when a CodeAgent call completes.
         *
         * @param context the resulting context from the CodeAgent run
         */
        void onCodeAgentResult(Context context);
    }

    private final IConsoleIO io;

    private record PlanningTurn(
            ToolRegistry toolRegistry,
            WorkspaceTools workspaceTools,
            ParallelSearch parallelSearch,
            List<ChatMessage> messages,
            Llm.StreamingResult result) {}

    private sealed interface PlanningTurnOutcome permits PlanningTurnOutcome.Success, PlanningTurnOutcome.Terminal {
        record Success(PlanningTurn turn) implements PlanningTurnOutcome {}

        record Terminal(TaskResult taskResult) implements PlanningTurnOutcome {}
    }

    private final IContextManager cm;
    private final StreamingChatModel planningModel;
    private final StreamingChatModel codeModel;
    private final String goal;
    // scope is explicit so we can use its changed-files-tracking feature w/ Code Agent's results
    private final ContextManager.TaskScope scope;
    // Local working context snapshot for this agent
    private Context context;
    // History of this agent's interactions
    private final List<ChatMessage> architectMessages = new ArrayList<>();

    // Tracks if we have ever entered emergency mode (restricted tools due to context size)
    private boolean hasEnteredEmergencyMode = false;

    @Nullable
    private ArchitectListener listener;

    private final Set<ProjectFile> presentedRelatedFiles = new HashSet<>();

    private TokenUsage totalUsage = new TokenUsage(0, 0);
    private boolean offerUndoToolNext = false;

    @Nullable
    private StopReason lastFatalReason = null;

    private boolean terminalCompletionReported = false;

    // When CodeAgent succeeds, we immediately declare victory without another LLM round.
    private boolean codeAgentJustSucceeded = false;

    private boolean deferBuildForInitialCodeAgentCall = false;

    /** When true, all CodeAgent calls always defer build, regardless of what the LLM requests. */
    private boolean alwaysDeferBuild = false;

    /** When true, this Architect run may not invoke CodeAgent or other workspace-editing actions. */
    private boolean readOnly = false;

    /** When false, build-configuration and build-execution tools are unavailable for this run. */
    private boolean buildToolsEnabled = true;

    @Nullable
    private CompletableFuture<List<TaskEntry>> compressedHistoryFuture;

    @Nullable
    private String verifyCommand;

    /**
     * Constructs a BrokkAgent that can handle multi-step tasks and sub-tasks.
     *
     * @param codeModel the code model to use.
     * @param goal      The initial user instruction or goal for the agent.
     */
    public ArchitectAgent(
            IContextManager contextManager,
            StreamingChatModel planningModel,
            StreamingChatModel codeModel,
            String goal,
            ContextManager.TaskScope scope) {
        this(contextManager, planningModel, codeModel, goal, scope, contextManager.liveContext(), (CompletableFuture<
                        List<TaskEntry>>)
                null);
    }

    /**
     * Constructs a BrokkAgent with an explicit initial context.
     * Use this when the caller has a more up-to-date context than liveContext().
     */
    public ArchitectAgent(
            IContextManager contextManager,
            StreamingChatModel planningModel,
            StreamingChatModel codeModel,
            String goal,
            ContextManager.TaskScope scope,
            Context initialContext) {
        this(contextManager, planningModel, codeModel, goal, scope, initialContext, null, contextManager.getIo());
    }

    public ArchitectAgent(
            IContextManager contextManager,
            StreamingChatModel planningModel,
            StreamingChatModel codeModel,
            String goal,
            ContextManager.TaskScope scope,
            Context initialContext,
            @Nullable CompletableFuture<List<TaskEntry>> compressedHistoryFuture) {
        this(
                contextManager,
                planningModel,
                codeModel,
                goal,
                scope,
                initialContext,
                compressedHistoryFuture,
                contextManager.getIo());
    }

    /**
     * Constructs a BrokkAgent with an explicit IConsoleIO.
     */
    public ArchitectAgent(
            IContextManager contextManager,
            StreamingChatModel planningModel,
            StreamingChatModel codeModel,
            String goal,
            ContextManager.TaskScope scope,
            Context initialContext,
            IConsoleIO io) {
        this(contextManager, planningModel, codeModel, goal, scope, initialContext, null, io);
    }

    public ArchitectAgent(
            IContextManager contextManager,
            StreamingChatModel planningModel,
            StreamingChatModel codeModel,
            String goal,
            ContextManager.TaskScope scope,
            Context initialContext,
            @Nullable CompletableFuture<List<TaskEntry>> compressedHistoryFuture,
            IConsoleIO io) {
        this.cm = contextManager;
        this.planningModel = planningModel;
        this.codeModel = codeModel;
        this.goal = goal;
        this.io = io;
        this.scope = scope;
        this.context = initialContext;
        this.compressedHistoryFuture = compressedHistoryFuture;
        this.verifyCommand = null;
    }

    public void setVerifyCommand(@Nullable String verifyCommand) {
        this.verifyCommand = verifyCommand;
    }

    public void setDeferBuildForInitialCodeAgentCall(boolean deferBuildForInitialCodeAgentCall) {
        this.deferBuildForInitialCodeAgentCall = deferBuildForInitialCodeAgentCall;
    }

    public void setAlwaysDeferBuild(boolean alwaysDeferBuild) {
        this.alwaysDeferBuild = alwaysDeferBuild;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public void setBuildToolsEnabled(boolean buildToolsEnabled) {
        this.buildToolsEnabled = buildToolsEnabled;
    }

    private StreamingChatModel delegatedSearchModel() {
        return ParallelSearch.usePlannerModelForSearchAgent()
                ? planningModel
                : cm.getService().getModel(ModelType.SEARCH);
    }

    public void setListener(@Nullable ArchitectListener listener) {
        this.listener = listener;
    }

    /**
     * A tool for finishing the plan with a final answer. Similar to 'answerSearch' in SearchAgent.
     */
    @Tool(
            "Provide a final answer to the multi-step project. Use this when you're done or have everything you need. Do not combine with other tools.")
    public String projectFinished(
            @P("A final explanation or summary addressing all tasks. Format it in Markdown if desired.")
                    String finalExplanation) {
        terminalCompletionReported = true;
        reportComplete(StopReason.SUCCESS, "Architect complete", finalExplanation);
        return finalExplanation;
    }

    /**
     * A tool to abort the plan if you cannot proceed or if it is irrelevant.
     */
    @Tool(
            "Abort the entire project. Use this if the tasks are impossible or out of scope. Do not combine with other tools.")
    public String abortProject(@P("Explain why the project must be aborted.") String reason) {
        terminalCompletionReported = true;
        reportComplete(StopReason.LLM_ABORTED, "Architect aborted", reason);
        return reason;
    }

    /**
     * A tool that invokes the CodeAgent to solve the current top task using the given instructions. The instructions
     * can incorporate the stack's current top task or anything else.
     */
    @Destructive
    @Tool(
            "Invoke the Code Agent to solve or implement the current task. Provide complete instructions. Only the Workspace and your instructions are visible to the Code Agent, NOT the entire chat history; you must therefore provide appropriate context for your instructions. If you expect your changes to temporarily break the build and plan to fix them in later steps, set 'deferBuild' to true to defer build/verification.")
    public String callCodeAgent(
            @P(
                            "Detailed instructions for the CodeAgent referencing the current project. Code Agent can figure out how to change the code at the syntax level but needs clear instructions of what exactly you want changed")
                    String instructions,
            @P(
                            "Defer build/verification for this CodeAgent call. Set to true when your changes are an intermediate step that will temporarily break the build")
                    boolean deferBuild)
            throws ToolRegistry.FatalLlmException, InterruptedException {
        if (readOnly) {
            throw new ToolRegistry.FatalLlmException(
                    "CodeAgent is disabled for this Architect run. Produce a plan or final answer without editing files.");
        }
        logger.debug("callCodeAgent invoked with instructions: {}, deferBuild={}", instructions, deferBuild);

        addPlanningToHistory();

        // Remove any stale diff from a previous failed CodeAgent attempt
        var existingChanges = context.getSpecial(SpecialTextType.CODE_AGENT_CHANGES);
        if (existingChanges.isPresent()) {
            context = context.removeFragments(List.of(existingChanges.get()));
        }

        // Record planning history before invoking CodeAgent
        var initialContext = context;
        // no-op if we haven't consumed compressedHistoryFuture yet -- there is nothing to compress
        // except what it's already compressing
        var historyFuture = compressedHistoryFuture == null
                ? cm.compressHistoryAsync(context)
                : CompletableFuture.completedFuture(context.getTaskHistory());

        io.llmOutput("**Code Agent** engaged:\n" + instructions, ChatMessageType.CUSTOM, LlmOutputMeta.newMessage());
        var agent = new CodeAgent(cm, codeModel);
        var effectiveDeferBuild = deferBuild || alwaysDeferBuild;
        var opts = new HashSet<CodeAgent.Option>();
        if (effectiveDeferBuild) {
            opts.add(CodeAgent.Option.DEFER_BUILD);
        }
        var result = agent.executeWithoutHistory(context, instructions, opts);
        var stopDetails = result.stopDetails();
        var reason = stopDetails.reason();

        // Update architect context with the CodeAgent's fragments, preserving the Architect history
        var codeContext = result.context();
        context = codeContext
                .withHistory(historyFuture.join())
                .addHistoryEntry(codeContext.getTaskHistory().getLast());

        var changedFragments =
                ContextDelta.between(initialContext, context).join().getChangedFragments();
        // we're done with the original result now, make sure we don't reuse it by accident instead of the
        // post-verifyCommand results
        result = null;

        // SUCCESS can also mean "Code Agent threw it back to us to add missing context";
        // checking for changes de-risks that
        if (reason == StopReason.SUCCESS && !changedFragments.isEmpty()) {
            logger.debug("callCodeAgent finished successfully");
            if (!effectiveDeferBuild && !changedFragments.isEmpty()) {
                codeAgentJustSucceeded = true;

                if (verifyCommand != null) {
                    BuildAgent.@Nullable BuildDetails override = cm.getProject().awaitBuildDetails();
                    context = cm.getProject().getBuildRunner().runExplicitCommand(context, verifyCommand, override);
                    if (!context.getBuildError().isBlank()) {
                        codeAgentJustSucceeded = false;
                        reason = StopReason.BUILD_ERROR;
                        stopDetails = new TaskResult.StopDetails(reason, "Broader verification step failed");
                        // let the planning loop continue to fix it
                    }
                }
            }

            // re-check in case verifyCommand failed
            if (reason == StopReason.SUCCESS) {
                if (listener != null) {
                    listener.onCodeAgentResult(context);
                }

                var fileList = changedFragments.stream()
                        .map(cf -> cf.shortDescription().join())
                        .sorted()
                        .collect(Collectors.joining(", "));
                context = context.withAppendedMopMessagesToLastEntry(io.getLlmRawMessages());
                scope.append(context);
                return """
            # Status
            %s

            # Changed fragments
            %s
            %s
            """
                        .formatted(
                                effectiveDeferBuild
                                        ? "CodeAgent finished with build deferred as requested."
                                        : "CodeAgent finished with a successful build.",
                                fileList.isEmpty() ? "None" : fileList,
                                fileList.isEmpty() ? "" : "\nThe changes made are reflected in the Workspace.");
            }
        }

        // For non-SUCCESS outcomes, format error feedback appropriately

        // Throw errors that should halt the architect
        if (reason == StopReason.INTERRUPTED) {
            context = context.withAppendedMopMessagesToLastEntry(io.getLlmRawMessages());
            scope.append(context);
            throw new InterruptedException();
        }
        if (reason == StopReason.LLM_ERROR || reason == StopReason.IO_ERROR) {
            context = context.withAppendedMopMessagesToLastEntry(io.getLlmRawMessages());
            scope.append(context);
            this.lastFatalReason = reason;
            logger.error("Fatal {} during CodeAgent execution: {}", reason, stopDetails.explanation());
            throw new ToolRegistry.FatalLlmException(stopDetails.explanation());
        }

        if (listener != null) {
            listener.onCodeAgentResult(context);
        }

        // Extract and compress reasoning
        var lastEntry = codeContext.getTaskHistory().getLast();
        var messages = new ArrayList<>(lastEntry.mopMessages());
        var summary = cm.compressHistory(CodeAgent.ConversationState.extractReasoning(messages));
        var reasoningSummarySuffix = "\n\n# CodeAgent reasoning summary\n\n" + summary;

        // Offer undo and attach diff if the CodeAgent failed and left changes behind
        if (!changedFragments.isEmpty()) {
            String combinedDiffText = CodeAgent.cumulativeDiffForChanges(initialContext, context);
            // FIXME the if here is working around a bug, ContextDelta should not return
            // changed fragments with an empty diff
            if (combinedDiffText.isBlank()) {
                context = context.withSpecial(SpecialTextType.CODE_AGENT_CHANGES, "Code Agent made no changes");
            } else {
                this.offerUndoToolNext = true;
                context = context.withSpecial(SpecialTextType.CODE_AGENT_CHANGES, combinedDiffText);
            }
        }

        // Format recoverable errors with clear guidance for the LLM
        var diffPresentation =
                context.getSpecial(SpecialTextType.CODE_AGENT_CHANGES).isPresent()
                        ? CodeAgent.DiffPresentation.WORKSPACE_FRAGMENT
                        : CodeAgent.DiffPresentation.NONE;
        String resultString =
                CodeAgent.formatPostFailureResponse(reason, stopDetails.explanation(), diffPresentation, null)
                        + reasoningSummarySuffix;
        logger.debug("CodeAgent failed with reason {}: {}", reason, stopDetails.explanation());

        context = context.withAppendedMopMessagesToLastEntry(io.getLlmRawMessages());
        scope.append(context);
        return resultString;
    }

    private void addPlanningToHistory() throws InterruptedException {
        var messages = io.getLlmRawMessages();
        if (messages.isEmpty()) {
            return;
        }
        context = context.addHistoryEntry(messages, TaskResult.Type.ARCHITECT, planningModel, goal);
        scope.append(context);
    }

    @Tool(
            "Undo the changes made by the most recent CodeAgent call. This should only be used if Code Agent left the project farther from the goal than when it started.")
    @Blocking
    public String undoLastChanges() throws InterruptedException {
        if (readOnly) {
            return "Undo is unavailable during a read-only Architect run.";
        }
        logger.debug("undoLastChanges invoked");
        io.showNotification(IConsoleIO.NotificationRole.INFO, "Undoing last CodeAgent changes...");
        if (cm.undoContext()) {
            var resultMsg = "Successfully reverted the last CodeAgent changes.";
            logger.debug(resultMsg);
            io.showNotification(IConsoleIO.NotificationRole.INFO, resultMsg);
            // Synchronize local context with latest global state after undo
            context = cm.liveContext();
            // Reset the offer; only re-offer if a subsequent failure makes changes again
            this.offerUndoToolNext = false;
            return resultMsg;
        } else {
            var resultMsg = "Nothing to undo (concurrency bug?)";
            logger.debug(resultMsg);
            io.showNotification(IConsoleIO.NotificationRole.INFO, resultMsg);
            return resultMsg;
        }
    }

    @Tool(
            "Set the project's build/test commands (build/lint, test-all, test-some) and excluded directories. Saves to project config. Optionally validates the build/lint command.")
    public String setBuildDetails(
            @P("Command to build/lint the project (e.g., 'mvn test', 'gradle test', 'npm test').")
                    String buildLintCommand,
            @P("Command to run all tests.") String testAllCommand,
            @P("Command to run a subset of tests (e.g., a single module/file/class).") String testSomeCommand,
            @P("Directories to exclude from analysis/build context.") List<String> excludedDirectories) {
        if (!buildToolsEnabled) {
            return "Build/test tools are disabled for this Architect run.";
        }
        var existingDetails = cm.getProject().awaitBuildDetails();
        var details = new BuildAgent.BuildDetails(
                buildLintCommand,
                existingDetails.buildLintEnabled(),
                testAllCommand,
                existingDetails.testAllEnabled(),
                testSomeCommand,
                true, // Enabled by default if set via tool
                new LinkedHashSet<>(excludedDirectories),
                existingDetails.environmentVariables(),
                existingDetails.maxBuildAttempts(),
                existingDetails.afterTaskListCommand(),
                existingDetails.modules());
        cm.getProject().saveBuildDetails(details);

        cm.getIo().showNotification(IConsoleIO.NotificationRole.INFO, "Saved build details.");
        if (!buildLintCommand.trim().isEmpty()) {
            return "Saved build details.\n\n" + verifyBuildCommand();
        }
        return "Saved build details.";
    }

    @Tool(
            "Verify the currently configured build/lint command by executing it and returning bounded output. Uses the project's saved build details and environment variables.")
    public String verifyBuildCommand() {
        if (!buildToolsEnabled) {
            return "Build/test tools are disabled for this Architect run.";
        }
        var project = cm.getProject();
        var details = project.awaitBuildDetails();
        var buildLintCommand = details.buildLintCommand();
        if (buildLintCommand.trim().isEmpty()) {
            return "No build/lint command is configured.";
        }

        var envVars = details.environmentVariables();
        var result = BuildVerifier.verify(project, buildLintCommand, envVars);

        var statusLine = result.success()
                ? "Build command succeeded (exit code " + result.exitCode() + ")."
                : "Build command failed (exit code " + result.exitCode() + ").";

        var output = result.output().isBlank() ? "(no output)" : result.output();

        return """
                %s

                Output (last %d lines):
                %s
                """
                .formatted(statusLine, BuildVerifier.MAX_OUTPUT_LINES, output);
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

        TaskResult tr;
        try {
            tr = executeInternal();
        } catch (InterruptedException e) {
            tr = resultWithMessages(StopReason.INTERRUPTED);
        } catch (Throwable th) {
            // FIXME this should not be fucking necessary
            GlobalExceptionHandler.handle(th, st -> io.showNotification(IConsoleIO.NotificationRole.ERROR, st));
            logger.error("Unexpected exception in ArchitectAgent.execute()", th);
            tr = resultWithMessages(
                    StopReason.LLM_ERROR,
                    "Architect execution failed: " + Objects.toString(th.getMessage(), "unknown error"));
        }

        if (!terminalCompletionReported) {
            var details = tr.stopDetails();
            var message =
                    switch (details.reason()) {
                        case SUCCESS -> goal;
                        case INTERRUPTED -> "Cancelled by user.";
                        default ->
                            details.explanation().isBlank() ? details.reason().name() : details.explanation();
                    };
            reportComplete(details.reason(), "Architect finished", message);
        }

        var finalMessages = io.getLlmRawMessages();
        if (!finalMessages.isEmpty()) {
            tr = tr.withAppendedMopMessagesToLastEntry(finalMessages);
        }

        return tr;
    }

    /**
     * Execute Architect with a ReferenceAgent pass first. The Architect
     * results are appended to the provided scope.
     */
    public TaskResult executeWithScan() throws InterruptedException {
        int pruneThreshold = min(40_000, (int) (cm.getService().getMaxInputTokens(planningModel) * 0.2));
        var prune = Messages.getApproximateTokens(context) > pruneThreshold;
        context = LutzAgent.setupContext(context, goal, prune).context();

        // Run Architect proper
        TaskResult archResult = this.execute();
        scope.append(archResult);
        return archResult;
    }

    /**
     * Run the multi-step project loop: plan, choose tools, execute, repeat.
     *
     * <p>
     * Strategy:
     * 1) Try CodeAgent first with the goal.
     * 2) Enter planning loop. If the workspace is critical, restrict tools to workspace-trimming set.
     * 3) If the planning LLM returns ContextTooLarge, attempt to recover by throwing out the largest fragment(s),
     *    appending a harness note to the final user message describing what was removed. If at any point we get
     *    CTL while the non-workspace conversation history is larger than the workspace itself, abort.
     */
    private TaskResult executeInternal() throws InterruptedException {
        if (!readOnly) {
            // run code agent first
            try {
                // Note: callCodeAgent(String, boolean) is a @Tool method on ArchitectAgent.
                // When ArchitectAgent calls it directly here, it bypasses ToolRegistry.executeTool
                // and thus bypasses the console hooks. However, since this is a direct method call
                // and we want these events in the headless log, we should manually emit them or
                // use the registry. Given the current structure, we manually instrument this call.

                var req = ToolExecutionRequest.builder()
                        .name("callCodeAgent")
                        .arguments("{\"instructions\": \"%s\", \"deferBuild\": false}".formatted(goal))
                        .build();

                io.beforeToolCall(req, true);
                var initialSummary = callCodeAgent(goal, deferBuildForInitialCodeAgentCall);
                io.afterToolOutput(ToolExecutionResult.success(req, initialSummary));
                architectMessages.add(new UserMessage(
                        "[HARNESS NOTE: Before you started, CodeAgent tried and failed to solve this task. Here's the result.]\n\n"
                                + initialSummary));
            } catch (ToolRegistry.FatalLlmException e) {
                var fatalReason = this.lastFatalReason != null ? this.lastFatalReason : StopReason.LLM_ERROR;
                this.lastFatalReason = null;
                var errorMessage = "Fatal error executing initial Code Agent: %s".formatted(e.getMessage());
                logger.warn(errorMessage, e);
                io.showNotification(IConsoleIO.NotificationRole.INFO, errorMessage);
                return resultWithMessages(fatalReason);
            }
        }

        if (compressedHistoryFuture != null) {
            context = IContextManager.mergeCompressedHistory(context, compressedHistoryFuture.join());
            compressedHistoryFuture = null;
        }

        if (codeAgentJustSucceeded) {
            return codeAgentSuccessResult();
        }

        var llm = cm.getLlm(new Llm.Options(planningModel, goal, TaskResult.Type.ARCHITECT).withEcho());
        var modelsService = cm.getService();

        Set<ContextFragment> protectedFromPruning = Set.of();
        for (int turnNumber = 1; turnNumber <= MAX_TURNS; turnNumber++) {
            boolean isFinalTurn = (turnNumber == MAX_TURNS);

            if (isFinalTurn) {
                io.showTransientMessage("Brokk Architect is preparing final turn (turn limit reached)");
            } else {
                io.showTransientMessage("Brokk Architect is preparing turn " + turnNumber);
            }

            // Determine active models and their maximum allowed input tokens
            var models = new ArrayList<StreamingChatModel>();
            models.add(this.planningModel);
            models.add(this.codeModel);
            int maxInputTokens = models.stream()
                    .mapToInt(modelsService::getMaxInputTokens)
                    .min()
                    .orElseThrow();

            var outcome =
                    runPlanningTurnWithContextTooLargeRecovery(llm, maxInputTokens, protectedFromPruning, isFinalTurn);
            if (outcome instanceof PlanningTurnOutcome.Terminal terminal) {
                return terminal.taskResult();
            }
            var turn = ((PlanningTurnOutcome.Success) outcome).turn();

            var tr = turn.toolRegistry();
            var parallelSearch = turn.parallelSearch();
            var result = turn.result();

            totalUsage = TokenUsage.sum(
                    totalUsage, castNonNull(result.originalResponse()).tokenUsage());
            // Add the request and response to message history.
            // We append a stub for the user message instead of the full instructions to keep history lean.
            var aiMessage = ToolRegistry.removeDuplicateToolRequests(result.aiMessage());
            architectMessages.add(new UserMessage(ArchitectPrompts.instructionsMarker()));
            architectMessages.add(aiMessage);

            var deduplicatedRequests = new LinkedHashSet<>(result.toolRequests());
            logger.debug("Unique tool requests are {}", deduplicatedRequests);

            // On the final turn, only callCodeAgent and abortProject are allowed;
            // filter out anything else the LLM may have attempted
            if (isFinalTurn) {
                var allowed = Set.copyOf(finalTurnAllowedTools());
                deduplicatedRequests.removeIf(req -> !allowed.contains(req.name()));
                if (deduplicatedRequests.isEmpty()) {
                    logger.info("Terminal turn: LLM did not call one of {}; ending.", allowed);
                    return resultWithMessages(StopReason.SUCCESS);
                }
            }

            // carry forward into outer loop
            ToolExecutionRequest answerReq = null, abortReq = null;
            var terminalPartition =
                    ToolRegistry.partitionByNames(deduplicatedRequests, Set.of("projectFinished", "abortProject"));
            for (var req : terminalPartition.matchingRequests()) {
                if ("projectFinished".equals(req.name())) {
                    answerReq = req;
                } else if ("abortProject".equals(req.name())) {
                    abortReq = req;
                }
            }

            var searchPartition =
                    ToolRegistry.partitionByNames(terminalPartition.otherRequests(), Set.of("callSearchAgent"));
            var codePartition = ToolRegistry.partitionByNames(searchPartition.otherRequests(), Set.of("callCodeAgent"));
            var searchAgentReqs = new ArrayList<>(searchPartition.matchingRequests());
            var codeAgentReqs = new ArrayList<>(codePartition.matchingRequests());
            var otherReqs = new ArrayList<>(codePartition.otherRequests());

            // If we see "projectFinished" or "abortProject", handle it and then exit.
            // If these final/abort calls are present together with other tool calls in the same LLM response,
            // do NOT execute them. Instead, create ToolExecutionResult entries indicating the call was ignored.
            boolean multipleRequests = deduplicatedRequests.size() > 1;

            if (answerReq != null) {
                if (multipleRequests) {
                    var ignoredMsg =
                            "Ignored 'projectFinished' because other tool calls were present in the same turn.";
                    var toolResult = ToolExecutionResult.requestError(answerReq, ignoredMsg);
                    // Record the ignored result in the architect message history so planning history reflects
                    // this.
                    architectMessages.add(toolResult.toMessage());
                    logger.info("projectFinished ignored due to other tool calls present: {}", ignoredMsg);
                } else {
                    logger.debug("LLM decided to projectFinished. We'll finalize and stop");

                    var executionRegistry = ToolRegistry.fromBase(tr)
                            .register(new WorkspaceTools(context))
                            .build();
                    var toolResult = ToolExecutionHelper.executeWithApproval(io, executionRegistry, answerReq);
                    llm.recordToolExecution(toolResult);

                    io.llmOutput(
                            "Project final answer: " + toolResult.resultText(),
                            ChatMessageType.AI,
                            LlmOutputMeta.DEFAULT);
                    return codeAgentSuccessResult();
                }
            }

            if (abortReq != null) {
                if (multipleRequests) {
                    var ignoredMsg = "Ignored 'abortProject' because other tool calls were present in the same turn.";
                    var toolResult = ToolExecutionResult.requestError(abortReq, ignoredMsg);
                    architectMessages.add(toolResult.toMessage());
                    logger.info("abortProject ignored due to other tool calls present: {}", ignoredMsg);
                } else {
                    logger.debug("LLM decided to abortProject. We'll finalize and stop");

                    var executionRegistry = ToolRegistry.fromBase(tr)
                            .register(new WorkspaceTools(context))
                            .build();
                    var toolResult = ToolExecutionHelper.executeWithApproval(io, executionRegistry, abortReq);
                    llm.recordToolExecution(toolResult);

                    io.llmOutput(
                            "Project aborted: " + toolResult.resultText(), ChatMessageType.AI, LlmOutputMeta.DEFAULT);
                    return resultWithMessages(StopReason.LLM_ABORTED);
                }
            }

            // Execute remaining tool calls in the desired order (all use the local registry)
            otherReqs.sort(Comparator.comparingInt(req -> getPriorityRank(req.name())));
            for (var req : otherReqs) {
                var executionRegistry = ToolRegistry.fromBase(tr)
                        .register(new WorkspaceTools(context))
                        .build();
                ToolExecutionResult toolResult = ToolExecutionHelper.executeWithApproval(io, executionRegistry, req);
                llm.recordToolExecution(toolResult);

                if (isWorkspaceTool(req, executionRegistry)
                        && toolResult.status() == ToolExecutionResult.Status.SUCCESS) {
                    if ("dropWorkspaceFragments".equals(req.name())) {
                        context = ((WorkspaceTools.DropWorkspaceOutput) toolResult.result()).context();
                    } else {
                        context = ((WorkspaceTools.WorkspaceMutationOutput) toolResult.result()).context();
                    }
                }
                architectMessages.add(toolResult.toMessage());
                logger.debug("Executed tool '{}' => result: {}", req.name(), toolResult.resultText());
            }

            // Handle search agent requests with batched history
            if (!searchAgentReqs.isEmpty()) {
                addPlanningToHistory();
                var searchResult = parallelSearch.execute(searchAgentReqs, tr);
                if (searchResult.stopDetails().reason() == StopReason.LLM_ERROR) {
                    return resultWithMessages(
                            StopReason.LLM_ERROR, searchResult.stopDetails().explanation());
                }

                context = context.addFragments(
                        searchResult.context().allFragments().toList());
                architectMessages.addAll(searchResult.toolExecutionMessages());
                context = context.addHistoryEntry(
                        searchResult.mopMessages(),
                        List.of(),
                        TaskResult.Type.SEARCH,
                        planningModel,
                        searchResult.historyDescription());
                scope.append(context);
            }

            // code agent calls are done serially
            var initialContext = context;
            for (var req : codeAgentReqs) {
                var executionRegistry = ToolRegistry.fromBase(tr)
                        .register(new WorkspaceTools(context))
                        .build();
                ToolExecutionResult toolResult = ToolExecutionHelper.executeWithApproval(io, executionRegistry, req);
                llm.recordToolExecution(toolResult);

                if (toolResult.status() == ToolExecutionResult.Status.FATAL) {
                    var fatalReason = this.lastFatalReason != null ? this.lastFatalReason : StopReason.LLM_ERROR;
                    this.lastFatalReason = null;
                    return resultWithMessages(fatalReason);
                }

                architectMessages.add(toolResult.toMessage());
                logger.debug("Executed tool '{}' => result: {}", req.name(), toolResult.resultText());
            }

            // If CodeAgent succeeded (after making edits), automatically declare victory and stop.
            if (codeAgentJustSucceeded) {
                return codeAgentSuccessResult();
            }
            protectedFromPruning =
                    ContextDelta.between(initialContext, context).join().getChangedFragments();
        }

        // All turns exhausted (including the terminal turn); return what we have
        return resultWithMessages(StopReason.TURN_LIMIT);
    }

    @Blocking
    private PlanningTurnOutcome runPlanningTurnWithContextTooLargeRecovery(
            Llm llm, int maxInputTokens, Set<ContextFragment> protectedFromPruning, boolean isFinalTurn)
            throws InterruptedException {
        var removedFragmentDescriptionsThisTurn = new ArrayList<String>();

        boolean emergencyToolMode = false;

        while (true) {
            var workspaceContentMessages =
                    new ArrayList<>(WorkspacePrompts.getMessagesGroupedByMutability(context, Set.of()));
            int workspaceTokenSize = Messages.getApproximateMessageTokens(workspaceContentMessages);

            var harnessNotes = new ArrayList<String>();
            if (!removedFragmentDescriptionsThisTurn.isEmpty()) {
                harnessNotes.add("Dropped very large fragments " + removedFragmentDescriptionsThisTurn
                        + " to reduce workspace size.");
            }
            if (isFinalTurn) {
                var allowedTools = finalTurnAllowedTools();
                harnessNotes.add("Turn limit reached. This is your FINAL turn. Tools are restricted to "
                        + allowedTools
                        + ". You MUST either call "
                        + (readOnly ? "projectFinished to deliver your plan" : "callCodeAgent to commit your plan")
                        + ", or abortProject to cancel.");
            }
            if (emergencyToolMode) {
                var allowedTools = criticalAllowedTools();
                harnessNotes.add("ContextTooLarge occurred; tools are restricted to " + allowedTools
                        + ". Use them to reduce Workspace size substantially before proceeding.");
            }

            @Nullable
            String harnessNote =
                    harnessNotes.isEmpty() ? null : "[HARNESS NOTE: " + String.join(" ", harnessNotes) + "]";

            int maxInputTokensForPrompt =
                    emergencyToolMode ? Math.max(1, (int) (workspaceTokenSize * 0.8)) : maxInputTokens;

            List<ChatMessage> messages =
                    buildPrompt(workspaceTokenSize, maxInputTokensForPrompt, workspaceContentMessages, harnessNote);

            WorkspaceTools wst = new WorkspaceTools(this.context);
            ParallelSearch parallelSearch = new ParallelSearch(context.forSearchAgent(), goal, delegatedSearchModel());

            var depTools = DependencyTools.isSupported(cm.getProject())
                    ? Optional.of(new DependencyTools(cm))
                    : Optional.<DependencyTools>empty();

            var builder =
                    cm.getToolRegistry().builder().register(this).register(wst).register(parallelSearch);
            depTools.ifPresent(builder::register);
            ToolRegistry tr = builder.build();

            var toolSpecs = new ArrayList<ToolSpecification>();
            ToolContext toolContext;
            if (isFinalTurn) {
                // Terminal turn: only allow committing work or aborting
                var allowed = finalTurnAllowedTools();
                toolSpecs.addAll(tr.getTools(allowed));
                toolContext = new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr);
            } else if (emergencyToolMode) {
                notifyCriticalWorkspaceRestriction(workspaceTokenSize, maxInputTokens);
                var allowed = criticalAllowedTools();
                allowed = WorkspaceTools.filterByAnalyzerAvailability(allowed, cm.getProject());
                toolSpecs.addAll(tr.getTools(allowed));
                toolContext = new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr);
            } else {
                List<String> allowed = new ArrayList<>();
                allowed.add("addFilesToWorkspace");
                allowed.add("addFileSummariesToWorkspace");
                allowed.add("addClassesToWorkspace");
                allowed.add("addClassSummariesToWorkspace");
                allowed.add("addMethodsToWorkspace");
                allowed.add("addUrlContentsToWorkspace");
                allowed.add("dropWorkspaceFragments");
                allowed.add("explainCommit");
                allowed.add("runShellCommand");

                if (!readOnly) {
                    allowed.add("callCodeAgent");
                }

                if (buildToolsEnabled
                        && cm.getProject()
                                .awaitBuildDetails()
                                .buildLintCommand()
                                .isBlank()
                        && !Objects.equals(System.getenv("BRK_ALLOW_SET_BUILD_DETAILS"), "false")) {
                    allowed.add("setBuildDetails");
                    allowed.add("verifyBuildCommand");
                }

                if (depTools.isPresent()) {
                    allowed.add("importDependency");
                }

                if (!readOnly && this.offerUndoToolNext) {
                    allowed.add("undoLastChanges");
                    allowed.add("callSearchAgent");
                }

                allowed.add("projectFinished");
                allowed.add("abortProject");

                allowed = WorkspaceTools.filterByAnalyzerAvailability(allowed, cm.getProject());

                toolSpecs.addAll(tr.getTools(allowed));
                toolContext = new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr);
            }

            io.showTransientMessage("Brokk Architect is preparing the next actions…");
            var result = llm.sendRequest(messages, toolContext);

            // happy path
            if (result.error() == null) {
                return new PlanningTurnOutcome.Success(new PlanningTurn(tr, wst, parallelSearch, messages, result));
            }

            // llm error
            if (!(result.error() instanceof ContextTooLargeException)) {
                logger.debug(
                        "Error from LLM while deciding next action: {}",
                        result.error().getMessage());
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "Error from LLM while deciding next action (see debug log for details)");
                return new PlanningTurnOutcome.Terminal(resultWithMessages(StopReason.LLM_ERROR));
            }

            // context too large
            int totalPromptTokens = Messages.getApproximateMessageTokens(messages);
            int conversationTokens = Math.max(0, totalPromptTokens - workspaceTokenSize);

            // 1. if history alone is too large, we can't recover
            if (conversationTokens > workspaceTokenSize) {
                var abortMessage =
                        "Architect aborting: ContextTooLarge while conversation history (" + conversationTokens
                                + " tokens) exceeds workspace (" + workspaceTokenSize
                                + " tokens). Please start a new session or reduce history.";
                io.showNotification(IConsoleIO.NotificationRole.INFO, abortMessage);
                return new PlanningTurnOutcome.Terminal(resultWithMessages(StopReason.LLM_ABORTED, abortMessage));
            }

            // 2. attempt to recover by removing the largest fragment
            var maybeLargest = findLargestFragmentToDrop(context, protectedFromPruning);
            if (maybeLargest.isEmpty()) {
                var abortMessage =
                        "Architect aborting: ContextTooLarge and no further workspace fragments could be dropped.";
                io.showNotification(IConsoleIO.NotificationRole.INFO, abortMessage);
                return new PlanningTurnOutcome.Terminal(resultWithMessages(StopReason.LLM_ERROR, abortMessage));
            }
            var largest = maybeLargest.get();
            var desc = largest.description().join();
            removedFragmentDescriptionsThisTurn.add(desc);
            var existingDiscardedMap = context.getDiscardedFragmentsNotes();
            Map<String, String> mergedDiscarded = new LinkedHashMap<>(existingDiscardedMap);
            mergedDiscarded.put(desc, "Dropped by Brokk Harness to reduce context size");
            String discardedJson = SpecialTextType.serializeDiscardedContext(mergedDiscarded);
            context = context.removeFragmentsByIds(List.of(largest.id()))
                    .withSpecial(SpecialTextType.DISCARDED_CONTEXT, discardedJson);

            // set local emergency mode and global has-entered flags
            emergencyToolMode = true;
            hasEnteredEmergencyMode = true;
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
        return allowed;
    }

    private TaskResult codeAgentSuccessResult() {
        // Capture any Architect planning output
        var messages = io.getLlmRawMessages();
        context = context.addHistoryEntry(messages, TaskResult.Type.ARCHITECT, planningModel, goal);
        return new TaskResult(context, new TaskResult.StopDetails(StopReason.SUCCESS));
    }

    private TaskResult.TaskMeta taskMeta() {
        return new TaskResult.TaskMeta(
                TaskResult.Type.ARCHITECT, AbstractService.ModelConfig.from(planningModel, cm.getService()));
    }

    private TaskResult resultWithMessages(StopReason reason, String message) {
        // include the messages we exchanged with the LLM for any planning steps since we ran a sub-agent
        context = context.addHistoryEntry(io.getLlmRawMessages(), TaskResult.Type.ARCHITECT, planningModel, message);
        return new TaskResult(context, new TaskResult.StopDetails(reason));
    }

    private TaskResult resultWithMessages(StopReason reason) {
        // include the messages we exchanged with the LLM for any planning steps since we ran a sub-agent
        return resultWithMessages(reason, "Architect: " + goal);
    }

    void reportComplete(StopReason reason, String heading, String message) {
        logger.debug("ArchitectAgent completed: {}: {}", reason, message);
        var badge = StatusBadge.badgeFor(reason);
        io.llmOutput(
                "\n# " + heading + "\n\n" + badge + "\n\n" + message, ChatMessageType.AI, LlmOutputMeta.newMessage());
    }

    private boolean isWorkspaceTool(ToolExecutionRequest request, ToolRegistry tr) {
        try {
            var vi = tr.validateTool(request);
            return vi.instance() instanceof WorkspaceTools;
        } catch (ToolRegistry.ToolValidationException e) {
            return false;
        }
    }

    /**
     * Helper method to get priority rank for tool names. Lower number means higher priority.
     */
    private int getPriorityRank(String toolName) {
        return switch (toolName) {
            case "dropWorkspaceFragments" -> 1;
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
            int workspaceTokenSize,
            int maxInputTokens,
            List<ChatMessage> precomputedWorkspaceMessages,
            @Nullable String appendedHarnessNote)
            throws InterruptedException {
        var messages = new ArrayList<ChatMessage>();

        var sys = new SystemMessage(
                """
                <instructions>
                %s
                </instructions>
                <goal>
                %s
                </goal>
                """
                        .formatted(ArchitectPrompts.instance.systemInstructions(), goal)
                        .trim());
        messages.add(sys);

        // Workspace contents are added directly
        messages.addAll(precomputedWorkspaceMessages);

        // History from previous tasks/sessions; we primarily want to avoid CODE and SEARCH sub-agents
        var safeTypes = EnumSet.of(TaskResult.Type.ASK, TaskResult.Type.REVIEW, TaskResult.Type.ARCHITECT);
        messages.addAll(WorkspacePrompts.getHistoryMessages(context, taskMeta(), safeTypes));

        // This agent's own conversational history for the current goal.
        messages.addAll(architectMessages);

        // Add related identifiers as a separate message/ack pair, unless we are/were in emergency mode
        var related = hasEnteredEmergencyMode
                ? Map.<ProjectFile, String>of()
                : context.buildRelatedSymbols(10, 20, presentedRelatedFiles);
        presentedRelatedFiles.addAll(related.keySet());
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

        // Final user message with the goal and specific instructions for this turn, including workspace warnings
        var finalInstructions =
                ArchitectPrompts.instance.getFinalInstructions(context, goal, workspaceTokenSize, maxInputTokens);

        if (cm.getProject().isEmptyProject()) {
            finalInstructions +=
                    """

                    <empty-project-notice>
                    This project appears to be empty (a new project with no existing source files).
                    Prefer starting by creating the minimal project structure needed to satisfy the goal, and ensure the Workspace contains the key new files you create.
                    </empty-project-notice>
                    """;
        }

        if (readOnly) {
            finalInstructions +=
                    """

                    <read-only-run>
                    This Architect run is read-only. Do not make file changes or call CodeAgent.
                    Produce a plan or final answer using only the current Workspace context.
                    </read-only-run>
                    """;
        }

        if (!buildToolsEnabled) {
            finalInstructions +=
                    """

                    <no-build-run>
                    Build/test tools are disabled for this run. Do not configure or execute build verification.
                    </no-build-run>
                    """;
        } else if (cm.getProject().awaitBuildDetails().buildLintCommand().isBlank()) {
            finalInstructions +=
                    """

                    <build-setup>
                    No build/test commands are configured for this project yet.
                    If you need to run builds/tests (or want verification after changes), call setBuildDetails(buildLintCommand, testAllCommand, testSomeCommand, excludedDirectories) to configure the project's build/test stack.
                    </build-setup>
                    """;
        }

        if (appendedHarnessNote != null && !appendedHarnessNote.isBlank()) {
            finalInstructions += "\n\n" + appendedHarnessNote;
        }

        messages.add(new UserMessage(finalInstructions));

        return messages;
    }

    private List<String> finalTurnAllowedTools() {
        return readOnly ? List.of("projectFinished", "abortProject") : List.of("callCodeAgent", "abortProject");
    }

    @Blocking
    private Optional<ContextFragment> findLargestFragmentToDrop(Context ctx, Set<ContextFragment> protectedFragments) {
        var candidates = ctx.allFragments()
                .filter(f -> !f.getType().isOutput())
                .filter(f -> !ctx.isPinned(f))
                .filter(f -> protectedFragments.stream().noneMatch(p -> p.hasSameSource(f)))
                .filter(ContextFragment::isText)
                .toList();

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        record Scored(ContextFragment fragment, int tokens) {}

        return candidates.stream()
                .map(f -> new Scored(f, Messages.getApproximateTokens(f.text().join())))
                .max(Comparator.comparingInt(Scored::tokens))
                .map(Scored::fragment);
    }
}
