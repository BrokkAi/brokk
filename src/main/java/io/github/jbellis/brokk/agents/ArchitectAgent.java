package io.github.jbellis.brokk.agents;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.output.TokenUsage;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.GitHubAuth;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.GitWorkflowService;
import io.github.jbellis.brokk.prompts.ArchitectPrompts;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.tools.ToolExecutionResult;
import io.github.jbellis.brokk.tools.ToolRegistry;
import io.github.jbellis.brokk.util.LogDescription;
import io.github.jbellis.brokk.util.Messages;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.dialogs.AskHumanDialog;
import io.github.jbellis.brokk.gui.SwingUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;


public class ArchitectAgent {
    private static final Logger logger = LogManager.getLogger(ArchitectAgent.class);

    /**
     * Configuration options for the ArchitectAgent, determining which tools it can use.
     */
    public record ArchitectOptions(
            boolean includeContextAgent,
            boolean includeValidationAgent,
            boolean includeAnalyzerTools,
            boolean includeWorkspaceTools,
            boolean includeCodeAgent,
            boolean includeSearchAgent,
            boolean includeAskHuman,
            boolean includeGitCommit,
            boolean includeGitCreatePr
    ) {
        /** Default options (all enabled, except Git tools). */
        public static final ArchitectOptions DEFAULTS = new ArchitectOptions(true, true, true, true, true, true, true, false, false);
    }

    private final IConsoleIO io;

    // Helper record to associate a SearchAgent task Future with its request
    private record SearchTask(ToolExecutionRequest request, Future<ToolExecutionResult> future) {
    }

    private final ContextManager contextManager;
    private final StreamingChatModel model;
    private final ToolRegistry toolRegistry;
    private final String goal;
    private final ArchitectOptions options; // Store the options
    // History of this agent's interactions
    private final List<ChatMessage> architectMessages = new ArrayList<>();

    private TokenUsage totalUsage = new TokenUsage(0, 0);
    private final AtomicInteger searchAgentId = new AtomicInteger(1);
    private final AtomicInteger planningStep = new AtomicInteger(1);
    private boolean offerUndoToolNext = false;

    /**
     * Constructs a BrokkAgent that can handle multi-step tasks and sub-tasks.
     * @param goal The initial user instruction or goal for the agent.
     * @param options Configuration for which tools the agent can use.
     */
    public ArchitectAgent(ContextManager contextManager,
                          StreamingChatModel model,
                          ToolRegistry toolRegistry,
                          String goal,
                          ArchitectOptions options)
    {
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager cannot be null");
        this.model = Objects.requireNonNull(model, "model cannot be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry cannot be null");
        this.goal = Objects.requireNonNull(goal, "goal cannot be null");
        this.options = Objects.requireNonNull(options, "options cannot be null");
        this.io = contextManager.getIo();
    }

    /**
     * A tool for finishing the plan with a final answer. Similar to 'answerSearch' in SearchAgent.
     */
    @Tool("Provide a final answer to the multi-step project. Use this when you're done or have everything you need.")
    public String projectFinished(
            @P("A final explanation or summary addressing all tasks. Format it in Markdown if desired.")
            String finalExplanation
    )
    {
        var msg = "# Architect complete\n\n%s".formatted(finalExplanation);
        logger.debug(msg);
        io.llmOutput(msg, ChatMessageType.AI, true);

        return finalExplanation;
    }

    /**
     * A tool to abort the plan if you cannot proceed or if it is irrelevant.
     */
    @Tool("Abort the entire project. Use this if the tasks are impossible or out of scope.")
    public String abortProject(
            @P("Explain why the project must be aborted.")
            String reason
    )
    {
        var msg = "# Architect aborted\n\n%s".formatted(reason);
        logger.debug(msg);
        io.llmOutput(msg, ChatMessageType.AI, true);

        return reason;
    }

    private static class FatalLlmException extends RuntimeException { // Made static
        public FatalLlmException(String message) {
            super(message);
        }
    }

    /**
     * A tool that invokes the CodeAgent to solve the current top task using the given instructions.
     * The instructions can incorporate the stack's current top task or anything else.
     */
    @Tool("Invoke the Code Agent to solve or implement the current task. Provide complete instructions. Only the Workspace and your instructions are visible to the Code Agent, NOT the entire chat history; you must therefore provide appropriate context for your instructions.")
    public String callCodeAgent(
            @P("Detailed instructions for the CodeAgent referencing the current project. Code Agent can figure out how to change the code at the syntax level but needs clear instructions of what exactly you want changed")
            String instructions
    ) throws FatalLlmException, InterruptedException
    {
        logger.debug("callCodeAgent invoked with instructions: {}", instructions);

        // Check if ValidationAgent is enabled in options before using it
        if (options.includeValidationAgent()) {
            logger.debug("Invoking ValidationAgent to find relevant tests..");
            var testAgent = new ValidationAgent(contextManager);
            var relevantTests = testAgent.execute(instructions);
            if (!relevantTests.isEmpty()) {
                logger.debug("Adding relevant test files found by ValidationAgent to workspace: {}", relevantTests);
                contextManager.editFiles(relevantTests);
            } else {
                logger.debug("ValidationAgent found no relevant test files to add");
            }
        }

        var cursor = messageCursor();
        // TODO label this Architect
        io.llmOutput("Code Agent engaged: " + instructions, ChatMessageType.CUSTOM, true);
        var agent = new CodeAgent(contextManager, contextManager.getCodeModel());
        var result = agent.runTask(instructions, true);
        var stopDetails = result.stopDetails();
        var reason = stopDetails.reason();

        var newMessages = messagesSince(cursor);
        var historyResult = new TaskResult(result, newMessages, contextManager);
        var entry = contextManager.addToHistory(historyResult, true);

        if (reason == TaskResult.StopReason.SUCCESS) {
            var entrySummary = entry.summary();
            var summary = """
                    CodeAgent success!
                    <summary>
                    %s
                    </summary>
                    """.stripIndent().formatted(entrySummary); // stopDetails may be redundant for success
            logger.debug("Summary for successful callCodeAgent: {}", summary);
            return summary;
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

        // For other failures (PARSE_ERROR, APPLY_ERROR, BUILD_ERROR, etc.),
        // set flag to offer undo and return the summary with failure details.
        this.offerUndoToolNext = true;
        var summary = """
                CodeAgent was not successful. Changes were made but can be undone with 'undoLastChanges'.
                <summary>
                %s
                </summary>
                
                <stop-details>
                %s
                </stop-details>
                """.stripIndent().formatted(entry.summary(), stopDetails);
        logger.debug("Summary for failed callCodeAgent (undo will be offered): {}", summary);
        return summary;
    }

    @Tool("Create a local commit containing ALL current changes. " +
          "If the message is empty, a message will be generated.")
    public String commitChanges(
            @Nullable @P("Commit message in imperative form (≤ 80 chars). " +
               "Leave blank to auto-generate.") String message
    ) {
        var cursor = messageCursor();
        io.llmOutput("Git committing changes...\n", ChatMessageType.CUSTOM, true);
        try {
            // --- Guards ----------------------------------------------------------
            var project = contextManager.getProject();
            if (!project.hasGit()) {
                throw new IllegalStateException("Project is not a Git repository.");
            }


            // --------------------------------------------------------------------
            var gws = new GitWorkflowService(contextManager);
            var result = gws.commit(List.of(), message == null ? "" : message.trim());

            var summary = "Committed %s - \"%s\"".formatted(result.commitId(), result.firstLine());
            io.llmOutput(summary, ChatMessageType.CUSTOM);
            logger.info(summary);

            var newMessages = messagesSince(cursor);
            var tr = new TaskResult(contextManager,
                                    "Git commit",
                                    newMessages,
                                    Set.of(),
                                    TaskResult.StopReason.SUCCESS);
            contextManager.addToHistory(tr, false);

            return summary;
        } catch (Exception e) {
            var errorMessage = "Commit failed: " + e.getMessage();
            logger.error(errorMessage, e);
            io.llmOutput("Commit failed. See the build log for details.", ChatMessageType.CUSTOM);
            var newMessages = messagesSince(cursor);
            var tr = new TaskResult(contextManager,
                                    "Git commit",
                                    newMessages,
                                    Set.of(),
                                    TaskResult.StopReason.TOOL_ERROR);
            contextManager.addToHistory(tr, false);
            return errorMessage;
        }
    }

    @Tool("Create a GitHub pull-request for the current branch. " +
          "This implicitly pushes the branch and sets upstream when needed.")
    public String createPullRequest(
            @P("PR title.") String title,
            @P("PR description in Markdown.") String body
    )
    {
        var cursor = messageCursor();
        io.llmOutput("Creating pull request…\n", ChatMessageType.CUSTOM, true);

        try {
            var project = contextManager.getProject();
            if (!project.hasGit()) {
                throw new IllegalStateException("Not a Git repository.");
            }

            var repo = (GitRepo) project.getRepo();
            var defaultBranch = repo.getDefaultBranch();
            var currentBranch = repo.getCurrentBranch();
            if (Objects.equals(currentBranch, defaultBranch)) {
                throw new IllegalStateException("Refusing to open PR from default branch (" + defaultBranch + ")");
            }

            if (!repo.getModifiedFiles().isEmpty()) {
                throw new IllegalStateException("Uncommitted changes present; commit first.");
            }

            if (!GitHubAuth.tokenPresent(project)) {
                throw new IllegalStateException("No GitHub credentials configured (e.g. GITHUB_TOKEN environment variable).");
            }

            if (repo.getRemoteUrl("origin") == null) {
                throw new IllegalStateException("No 'origin' remote configured for this repository.");
            }

            var gws = new GitWorkflowService(contextManager);

            // Auto-generate title/body if blank
            if (title.isBlank() || body.isBlank()) {
                var suggestion = gws.suggestPullRequestDetails(currentBranch, defaultBranch);
                if (title.isBlank()) {
                    title = suggestion.title();
                }
                if (body.isBlank()) {
                    body = suggestion.description();
                }
            }

            var prUrl = gws.createPullRequest(currentBranch, defaultBranch, title.trim(), body.trim());
            var msg = "Opened PR: \"%s\" \n[%s](%s)".formatted(title.trim(), prUrl, prUrl);
            io.llmOutput(msg, ChatMessageType.CUSTOM);
            logger.info(msg);

            // Persist result to history
            var newMessages = messagesSince(cursor);
            var tr = new TaskResult(contextManager,
                                    "Git create PR",
                                    newMessages,
                                    Set.of(),
                                    TaskResult.StopReason.SUCCESS);
            contextManager.addToHistory(tr, false);

            return msg;
        } catch (Exception e) {
            var err = "Create PR failed: " + e.getMessage();
            io.llmOutput(err, ChatMessageType.CUSTOM);
            logger.error(err, e);

            var newMessages = messagesSince(cursor);
            var tr = new TaskResult(contextManager,
                                    "Git create PR",
                                    newMessages,
                                    Set.of(),
                                    TaskResult.StopReason.TOOL_ERROR);
            contextManager.addToHistory(tr, false);
            return err;
        }
    }

    @Tool("Undo the changes made by the most recent CodeAgent call. This should only be used if Code Agent left the project farther from the goal than when it started.")
    public String undoLastChanges() {
        logger.debug("undoLastChanges invoked");
        io.systemOutput("Undoing last CodeAgent changes...");
        if (contextManager.undoContext()) {
            var resultMsg = "Successfully reverted the last CodeAgent changes.";
            logger.debug(resultMsg);
            io.systemOutput(resultMsg);
            return resultMsg;
        } else {
            var resultMsg = "Nothing to undo (concurrency bug?)";
            logger.debug(resultMsg);
            io.systemOutput(resultMsg);
            return resultMsg;
        }
    }

    /**
     * A tool that invokes the SearchAgent to perform searches and analysis based on a query.
     * The SearchAgent will decide which specific search/analysis tools to use (e.g., searchSymbols, getFileContents).
     * The results are added as a context fragment.
     */
    @Tool("Invoke the Search Agent to find information relevant to the given query. The Workspace is visible to the Search Agent. Searching is much slower than adding content to the Workspace directly if you know what you are looking for, but the Agent can find things that you don't know the exact name of. ")
    public String callSearchAgent(
            @P("The search query or question for the SearchAgent. Query in English (not just keywords)")
            String query
    ) throws FatalLlmException, InterruptedException
    {
        logger.debug("callSearchAgent invoked with query: {}", query);

        // Instantiate and run SearchAgent
        var cursor = messageCursor();
        io.llmOutput("Search Agent engaged: " +query, ChatMessageType.CUSTOM);
        var searchAgent = new SearchAgent(query,
                                    contextManager,
                                    model,
                                    toolRegistry,
                                    searchAgentId.getAndIncrement());
        var result = searchAgent.execute();

        var newMessages = messagesSince(cursor);
        var historyResult = new TaskResult(result, newMessages, contextManager);
        contextManager.addToHistory(historyResult, false);

        if (result.stopDetails().reason() == TaskResult.StopReason.LLM_ERROR) {
            throw new FatalLlmException(result.stopDetails().explanation());
        }

        if (result.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
            logger.debug("SearchAgent returned non-success for query {}: {}", query, result.stopDetails());
            return result.stopDetails().toString();
        }

        var relevantClasses = result.output().sources().stream()
                .map(CodeUnit::fqName)
                .collect(Collectors.joining(","));
        var stringResult = """
                %s
                
                Full list of potentially relevant classes:
                %s
                """.stripIndent().formatted(TaskEntry.formatMessages(historyResult.output().messages()), relevantClasses);
        logger.debug(stringResult);

        return stringResult;
    }

    @Tool("Escalate to a human for guidance. The model should call this " +
            "when it is stuck or unsure how to proceed. The argument is a question to show the human.")
    @Nullable
    public String askHumanQuestion(
            @P("The question you would like the human to answer. Make sure to provide any necessary background for the human to quickly and completely understand what you need and why. Use Markdown formatting where appropriate.") String question
    ) throws InterruptedException {
        var cursor = messageCursor();
        logger.debug("askHumanQuestion invoked with question: {}", question);
        io.llmOutput("Ask the user: " + question, ChatMessageType.CUSTOM, true);

        String answer = SwingUtil.runOnEdt(() -> AskHumanDialog.ask((Chrome) this.io, question), null);

        if (answer == null) {
            logger.info("Human cancelled the dialog for question: {}", question);
            io.systemOutput("Human interaction cancelled.");
            var newMessages = messagesSince(cursor);
            var tr = new TaskResult(contextManager,
                                    "Ask human",
                                    newMessages,
                                    Set.of(),
                                    TaskResult.StopReason.INTERRUPTED);
            contextManager.addToHistory(tr, false);
            throw new InterruptedException();
        } else {
            logger.debug("Human responded: {}", answer);
            io.llmOutput(answer, ChatMessageType.USER, true);
            var newMessages = messagesSince(cursor);
            var tr = new TaskResult(contextManager,
                                    "Ask human",
                                    newMessages,
                                    Set.of(),
                                    TaskResult.StopReason.SUCCESS);
            contextManager.addToHistory(tr, false);
            return answer;
        }
    }

    /**
     * Run the multi-step project until we either produce a final answer, abort, or run out of tasks.
     * This uses an iterative approach, letting the LLM decide which tool to call each time.
     */
    public TaskResult execute() throws ExecutionException, InterruptedException {
        io.systemOutput("Architect Agent engaged: `%s...`".formatted(LogDescription.getShortDescription(goal)));

        // Check if ContextAgent is enabled in options before using it
        if (options.includeContextAgent()) {
            var contextAgent = new ContextAgent(contextManager,
                                                contextManager.getSearchModel(),
                                                goal,
                                                true);
            contextAgent.execute();
        }

        var llm = contextManager.getLlm(model, "Architect: " + goal);
        var modelsService = contextManager.getService();

        while (true) {
            var planningCursor = messageCursor();
            io.llmOutput("\n# Planning", ChatMessageType.AI, true);

            // Determine active models and their minimum input token limit
            var models = new ArrayList<StreamingChatModel>();
            models.add(this.model);
            if (options.includeCodeAgent) {
                models.add(contextManager.getCodeModel());
            }
            if (options.includeSearchAgent) {
                models.add(contextManager.getSearchModel());
            }
            int minInputTokenLimit = models.stream()
                    .filter(Objects::nonNull)
                    .mapToInt(modelsService::getMaxInputTokens)
                    .filter(limit -> limit > 0)
                    .min()
                    .orElse(64_000);

            if (minInputTokenLimit == 64_000) {
                logger.warn("Could not determine a valid minimum input token limit from active models {}", models);
            }

            // Calculate current workspace token size
            var workspaceContentMessages = new ArrayList<>(CodePrompts.instance.getWorkspaceContentsMessages(contextManager.liveContext()));
            int workspaceTokenSize = Messages.getApproximateTokens(workspaceContentMessages);

            // Build the prompt messages, including history and conditional warnings
            var messages = buildPrompt(workspaceTokenSize, minInputTokenLimit, workspaceContentMessages);

            // Figure out which tools are allowed in this step
            var toolSpecs = new ArrayList<ToolSpecification>();
            var criticalWorkspaceSize = minInputTokenLimit < Integer.MAX_VALUE && workspaceTokenSize > (ArchitectPrompts.WORKSPACE_CRITICAL_THRESHOLD * minInputTokenLimit);

            if (criticalWorkspaceSize) {
                io.systemOutput(String.format("Workspace size (%,d tokens) is %.0f%% of limit %,d. Tool usage restricted to workspace modification.",
                                              workspaceTokenSize,
                                              (double) workspaceTokenSize / minInputTokenLimit * 100,
                                              minInputTokenLimit));
                toolSpecs.addAll(toolRegistry.getTools(this, List.of("projectFinished", "abortProject")));

                var allowedWorkspaceModTools = new ArrayList<String>();
                if (options.includeWorkspaceTools()) {
                    allowedWorkspaceModTools.add("dropWorkspaceFragments");
                    allowedWorkspaceModTools.add("addFileSummariesToWorkspace");
                    allowedWorkspaceModTools.add("addTextToWorkspace");
                }
                if (options.includeAnalyzerTools()) { // addClassSummariesToWorkspace is conceptually analyzer-related but provided by WorkspaceTools
                    allowedWorkspaceModTools.add("addClassSummariesToWorkspace");
                }
                toolSpecs.addAll(toolRegistry.getRegisteredTools(allowedWorkspaceModTools.stream().distinct().toList()));
            } else {
                // Default tool population logic
                var analyzerTools = List.of("addClassesToWorkspace",
                                            "addSymbolUsagesToWorkspace",
                                            "addClassSummariesToWorkspace",
                                            "addMethodSourcesToWorkspace",
                                            "addCallGraphInToWorkspace",
                                            "addCallGraphOutToWorkspace",
                                            "getFiles");
                if (options.includeAnalyzerTools()) {
                    toolSpecs.addAll(toolRegistry.getRegisteredTools(analyzerTools));
                }
                var workspaceTools = List.of("addFilesToWorkspace",
                                             "addFileSummariesToWorkspace",
                                             "addUrlContentsToWorkspace",
                                             "addTextToWorkspace",
                                             "dropWorkspaceFragments");
                if (options.includeWorkspaceTools()) {
                    toolSpecs.addAll(toolRegistry.getRegisteredTools(workspaceTools));
                }
                if (options.includeCodeAgent()) {
                    toolSpecs.addAll(toolRegistry.getTools(this, List.of("callCodeAgent")));
                }
                if (options.includeSearchAgent()) {
                    toolSpecs.addAll(toolRegistry.getTools(this, List.of("callSearchAgent")));
                }
                if (options.includeAskHuman()) {
                    toolSpecs.addAll(toolRegistry.getTools(this, List.of("askHumanQuestion")));
                }
                if (options.includeGitCommit()) {
                    toolSpecs.addAll(toolRegistry.getTools(this, List.of("commitChanges")));
                }
                if (options.includeGitCreatePr()) {
                    toolSpecs.addAll(toolRegistry.getTools(this, List.of("createPullRequest")));
                }
                toolSpecs.addAll(toolRegistry.getTools(this, List.of("projectFinished", "abortProject")));
            }

            // Add undo tool if the last CodeAgent call failed and made changes
            if (this.offerUndoToolNext) {
                logger.debug("Offering undoLastChanges tool for this turn.");
                toolSpecs.addAll(toolRegistry.getTools(this, List.of("undoLastChanges")));
                this.offerUndoToolNext = false; // Reset the flag, offer is for this turn only
            }

            // Ask the LLM for the next step
            var result = llm.sendRequest(messages, toolSpecs, ToolChoice.REQUIRED, false);

            if (result.error() != null) {
                logger.debug("Error from LLM while deciding next action: {}", result.error().getMessage());
                io.systemOutput("Error from LLM while deciding next action (see debug log for details)");
                return llmErrorResult();
            }
            if (result.isEmpty()) {
                var msg = "Empty LLM response. Stopping project now";
                io.systemOutput(msg);
                return llmErrorResult();
            }
            // show thinking
            if (!result.text().isBlank()) {
                io.llmOutput("\n" + result.text(), ChatMessageType.AI);
            }

            totalUsage = TokenUsage.sum(totalUsage, castNonNull(result.originalResponse()).tokenUsage());
            // Add the request and response to message history
            var aiMessage = ToolRegistry.removeDuplicateToolRequests(result.aiMessage());
            architectMessages.add(messages.getLast());
            architectMessages.add(aiMessage);

            var deduplicatedRequests = new LinkedHashSet<>(result.toolRequests());
            logger.debug("Unique tool requests are {}", deduplicatedRequests);
            io.llmOutput("\nTool call(s): %s".formatted(deduplicatedRequests.stream().map(req -> "`" + req.name() + "`").collect(Collectors.joining(", "))), ChatMessageType.AI);

            var planningMessages = messagesSince(planningCursor);
            contextManager.addToHistory(new TaskResult(contextManager,
                                                        "Architect planning step " + planningStep.getAndIncrement(),
                                                        planningMessages,
                                                        Set.of(),
                                                        TaskResult.StopReason.SUCCESS),
                                        false);

            // execute tool calls in the following order:
            // 1. projectFinished
            // 2. abortProject
            // 4. (everything else)
            // 5. searchAgent
            // 6. codeAgent
            ToolExecutionRequest answerReq = null, abortReq = null, askReq = null;
            var searchAgentReqs = new ArrayList<ToolExecutionRequest>();
            var codeAgentReqs = new ArrayList<ToolExecutionRequest>();
            var otherReqs = new ArrayList<ToolExecutionRequest>();
            for (var req : deduplicatedRequests) {
                switch (req.name()) {
                    case "projectFinished" -> answerReq = req;
                    case "abortProject" -> abortReq = req;
                    case "askHumanQuestion" -> askReq = req;
                    case "callSearchAgent" -> searchAgentReqs.add(req);
                    case "callCodeAgent" -> codeAgentReqs.add(req);
                    default -> otherReqs.add(req);
                }
            }

            // If we see "projectFinished" or "abortProject", handle it and then exit
            if (answerReq != null) {
                logger.debug("LLM decided to projectFinished. We'll finalize and stop");
                var toolResult = toolRegistry.executeTool(this, answerReq);
                logger.debug("Project final answer: {}", toolResult.resultText());
                var fragment = new ContextFragment.TaskFragment(contextManager,
                                                                List.of(new AiMessage(toolResult.resultText())),
                                                                goal);
                var stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS, toolResult.resultText());
                return new TaskResult("Architect: " + goal, fragment, Set.of(), stopDetails);
            }
            if (abortReq != null) {
                logger.debug("LLM decided to abortProject. We'll finalize and stop");
                var toolResult = toolRegistry.executeTool(this, abortReq);
                logger.debug("Project aborted: {}", toolResult.resultText());
                var fragment = new ContextFragment.TaskFragment(contextManager,
                                                                  List.of(new AiMessage(toolResult.resultText())),
                                                                  goal);
                var stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.LLM_ABORTED, toolResult.resultText());
                return new TaskResult("Architect: " + goal, fragment, Set.of(), stopDetails);
            }

            // Execute askHumanQuestion if present
            if (askReq != null) {
                var toolResult = toolRegistry.executeTool(this, askReq);
                architectMessages.add(ToolExecutionResultMessage.from(askReq, toolResult.resultText()));
                logger.debug("Executed tool '{}' => result: {}", askReq.name(), toolResult.resultText());
            }

            // Execute remaining tool calls in the desired order:
            otherReqs.sort(Comparator.comparingInt(req -> getPriorityRank(req.name())));
            for (var req : otherReqs) {
                var toolResult = toolRegistry.executeTool(this, req);
                architectMessages.add(ToolExecutionResultMessage.from(req, toolResult.resultText()));
                logger.debug("Executed tool '{}' => result: {}", req.name(), toolResult.resultText());
            }

            // Submit search agent tasks to run in the background
            var searchAgentTasks = new ArrayList<SearchTask>();
            for (var req : searchAgentReqs) {
                Callable<ToolExecutionResult> task = () -> {
                    var toolResult = toolRegistry.executeTool(this, req);
                    logger.debug("Finished SearchAgent task for request: {}", req.name());
                    return toolResult;
                };
                var taskDescription = "SearchAgent: " + LogDescription.getShortDescription(req.arguments());
                var future = contextManager.submitBackgroundTask(taskDescription, task);
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
                    architectMessages.add(ToolExecutionResultMessage.from(toolResult.request(), toolResult.resultText()));
                    logger.debug("Collected result for tool '{}' => result: {}", toolResult.request().name(), toolResult.resultText());
                } catch (InterruptedException e) {
                    logger.warn("SearchAgent task for request '{}' was cancelled", request.name());
                    future.cancel(true);
                    interrupted = true;
                } catch (ExecutionException e) {
                    logger.warn("Error executing SearchAgent task '{}'", request.name(), e.getCause());
                    if (e.getCause() instanceof FatalLlmException) {
                        var errorMessage = "Fatal LLM error executing Search Agent: %s".formatted(Objects.toString(e.getCause().getMessage(), "Unknown error"));
                        io.systemOutput(errorMessage);
                        break; 
                    }
                    var errorMessage = "Error executing Search Agent: %s".formatted(Objects.toString(e.getCause() != null ? e.getCause().getMessage() : "Unknown error", "Unknown execution error"));
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
                    var errorMessage = "Fatal LLM error executing Code Agent: %s".formatted(e.getMessage());
                    io.systemOutput(errorMessage);
                    return llmErrorResult();
                }

                architectMessages.add(ToolExecutionResultMessage.from(req, toolResult.resultText()));
                logger.debug("Executed tool '{}' => result: {}", req.name(), toolResult.resultText());
            }
        }
    }

    private TaskResult llmErrorResult() {
        return new TaskResult(contextManager,
                              "Architect: " + goal,
                              List.of(),
                              Set.of(),
                              new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR));
    }

    /**
     * Helper method to get priority rank for tool names.
     * Lower number means higher priority.
     */
    private int getPriorityRank(String toolName) {
        return switch (toolName) {
            case "dropFragments" -> 1;
            case "addReadOnlyFiles" -> 2;
            case "addEditableFilesToWorkspace" -> 3;
            case "commitChanges" -> 4;
            case "createPullRequest" -> 5;
            default -> 6; // all other tools have lowest priority
        };
    }

    /** Returns a cursor that represents the current end of the LLM output message list. */
    private int messageCursor() {
        return io.getLlmRawMessages().size();
    }

    /** Returns a copy of new messages added to the LLM output after the given cursor. */
    private List<ChatMessage> messagesSince(int cursor) {
        var raw = io.getLlmRawMessages();
        return List.copyOf(raw.subList(cursor, raw.size()));
    }

    /**
     * Build the system/user messages for the LLM.
     * This includes the standard system prompt, workspace contents, history, agent's session messages,
     * and the final user message with the goal and conditional workspace warnings.
     */
    private List<ChatMessage> buildPrompt(int workspaceTokenSize,
                                          int minInputTokenLimit,
                                          List<ChatMessage> precomputedWorkspaceMessages)
    throws InterruptedException
    {
        var messages = new ArrayList<ChatMessage>();
        // System message defines the agent's role and general instructions
        messages.add(ArchitectPrompts.instance.systemMessage(contextManager, CodePrompts.ARCHITECT_REMINDER));
        // Workspace contents are added directly
        messages.addAll(precomputedWorkspaceMessages);

        // Add auto-context as a separate message/ack pair
        var acFragment = contextManager.liveContext().buildAutoContext(10);
        String topClassesRaw = acFragment.text();
        if (!topClassesRaw.isBlank()) {
            var topClassesText = """
                           <related_classes>
                           Here are some classes that may be related to what is in your Workspace. They are not yet part of the Workspace!
                           If relevant, you should explicitly add them with addClassSummariesToWorkspace or addClassesToWorkspace so they are
                           visible to Code Agent. If they are not relevant, just ignore them.
                           
                           %s
                           </related_classes>
                           """.stripIndent().formatted(topClassesRaw);
            messages.add(new UserMessage(topClassesText));
            messages.add(new AiMessage("Okay, I will consider these related classes."));
        }

        // History from previous tasks/sessions
        messages.addAll(contextManager.getHistoryMessages());
        // This agent's own conversational history for the current goal
        messages.addAll(architectMessages);
        // Final user message with the goal and specific instructions for this turn, including workspace warnings
        messages.add(new UserMessage(ArchitectPrompts.instance.getFinalInstructions(contextManager, goal, workspaceTokenSize, minInputTokenLimit)));
        return messages;
    }
}
