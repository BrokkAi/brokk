package io.github.jbellis.brokk.agents;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.output.TokenUsage;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.prompts.ArchitectPrompts;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.tools.ToolExecutionResult;
import io.github.jbellis.brokk.tools.ToolRegistry;
import io.github.jbellis.brokk.util.LogDescription;
import io.github.jbellis.brokk.util.Messages;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


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
            boolean includeSearchAgent
    ) {
        /** Default options (all enabled). */
        public static final ArchitectOptions DEFAULTS = new ArchitectOptions(true, true, true, true, true, true);
    }

    private final IConsoleIO io;

    // Helper record to associate a SearchAgent task Future with its request
    private record SearchTask(ToolExecutionRequest request, Future<ToolExecutionResult> future) {
    }

    private final ContextManager contextManager;
    private final StreamingChatLanguageModel model;
    private final ToolRegistry toolRegistry;
    private final String goal;
    private final ArchitectOptions options; // Store the options
    // History of this agent's interactions
    private final List<ChatMessage> architectMessages = new ArrayList<>();

    private TokenUsage totalUsage = new TokenUsage(0, 0);
    private final AtomicInteger searchAgentId = new AtomicInteger(1);

    /**
     * Constructs a BrokkAgent that can handle multi-step tasks and sub-tasks.
     * @param goal The initial user instruction or goal for the agent.
     * @param options Configuration for which tools the agent can use.
     */
    public ArchitectAgent(ContextManager contextManager, StreamingChatLanguageModel model, ToolRegistry toolRegistry, String goal, ArchitectOptions options) {
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
    public void projectFinished(
            @P("A final explanation or summary addressing all tasks. Format it in Markdown if desired.")
            String finalExplanation
    )
    {
        logger.debug("Architect complete: %s".formatted(finalExplanation));
        io.llmOutput(finalExplanation, ChatMessageType.AI);
    }

    /**
     * A tool to abort the plan if you cannot proceed or if it is irrelevant.
     */
    @Tool("Abort the entire project. Use this if the tasks are impossible or out of scope.")
    public void abortProject(
            @P("Explain why the project must be aborted.")
            String reason
    )
    {
        var msg = "Architect Agent project aborted: %s".formatted(reason);
        io.systemOutput(msg);
    }

    private class FatalLlmException extends RuntimeException {
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

        // TODO label this Architect
        io.llmOutput("\n" + instructions, ChatMessageType.CUSTOM);
        var result = new CodeAgent(contextManager, contextManager.getEditModel()).runSession(instructions, true);
        var stopDetails = result.stopDetails();
        var reason = stopDetails.reason();
        // always add to history
        var entry = contextManager.addToHistory(result, true); // Keep changes on success

        if (reason == SessionResult.StopReason.SUCCESS) {
            String summary = """
                    CodeAgent success!
                    <summary>
                    %s
                    </summary>
                    """.stripIndent().formatted(entry.summary(), stopDetails);
            logger.debug("Summary for successful callCodeAgent: {}", summary);
            return summary;
        }

        // Revert changes for all non-SUCCESS outcomes by restoring original contents
        result.originalContents().forEach((file, contents) -> {
            try {
                file.write(contents);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        logger.debug("Reverted changes from CodeAgent due to stop reason: {}", reason);

        // throw errors that should halt the architect
        if (reason == SessionResult.StopReason.INTERRUPTED) {
            throw new InterruptedException();
        }
        if (reason == SessionResult.StopReason.LLM_ERROR) {
            logger.error("Fatal LLM error during CodeAgent execution: {}", stopDetails.explanation());
            throw new FatalLlmException(stopDetails.explanation());
        }

        // For other failures (PARSE_ERROR, APPLY_ERROR, BUILD_ERROR, etc.), return the summary with failure details
        String summary = """
                CodeAgent was not successful; changes have been reverted.
                <summary>
                %s
                </summary>
                
                <stop-details>
                %s
                </stop-details>
                """.stripIndent().formatted(entry.summary(), stopDetails);
        logger.debug("Summary for failed callCodeAgent (changes reverted): {}", summary);
        return summary;
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
        var searchAgent = new SearchAgent(query, contextManager, model, toolRegistry, searchAgentId.getAndIncrement());
        var result = searchAgent.execute();
        if (result.stopDetails().reason() == SessionResult.StopReason.LLM_ERROR) {
            throw new FatalLlmException(result.stopDetails().explanation());
        }

        if (result.stopDetails().reason() != SessionResult.StopReason.SUCCESS) {
            logger.debug("SearchAgent returned non-success for query {}: {}", query, result.stopDetails());
            return result.stopDetails().toString();
        }

        var relevantClasses = result.output().sources(contextManager.getAnalyzer()).stream()
                .map(CodeUnit::fqName)
                .collect(Collectors.joining(","));
        var stringResult = """
                %s
                
                Full list of potentially relevant classes:
                %s
                """.stripIndent().formatted(TaskEntry.formatMessages(result.output().messages()), relevantClasses);
        logger.debug(stringResult);

        return stringResult;
    }

    /**
     * Run the multi-step project until we either produce a final answer, abort, or run out of tasks.
     * This uses an iterative approach, letting the LLM decide which tool to call each time.
     */
    public void execute() throws ExecutionException, InterruptedException {
        io.systemOutput("Architect Agent engaged: `%s...`".formatted(LogDescription.getShortDescription(goal)));

        // Check if ContextAgent is enabled in options before using it
        if (options.includeContextAgent()) {
            var contextAgent = new ContextAgent(contextManager, contextManager.getEditModel(), goal, true);
            contextAgent.execute();
        }

        var llm = contextManager.getLlm(model, "Architect: " + goal);
        var modelsService = contextManager.getService();

        while (true) {
            io.llmOutput("\n# Planning", ChatMessageType.AI);

            // Determine active models and their minimum input token limit
            var models = new ArrayList<StreamingChatLanguageModel>();
            models.add(this.model);
            if (options.includeCodeAgent) {
                models.add(contextManager.getEditModel());
            }
            if (options.includeSearchAgent) {
                models.add(contextManager.getSearchModel());;
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
            List<ChatMessage> workspaceContentMessages = new ArrayList<>(contextManager.getWorkspaceContentsMessages(true));
            int workspaceTokenSize = Messages.getApproximateTokens(workspaceContentMessages);

            // Build the prompt messages, including history and conditional warnings
            var messages = buildPrompt(workspaceTokenSize, minInputTokenLimit, workspaceContentMessages);

            // Figure out which tools are allowed in this step
            var toolSpecs = new ArrayList<ToolSpecification>();
            boolean criticalWorkspaceSize = minInputTokenLimit < Integer.MAX_VALUE && workspaceTokenSize > (ArchitectPrompts.WORKSPACE_CRITICAL_THRESHOLD * minInputTokenLimit);

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
                toolSpecs.addAll(toolRegistry.getTools(this, List.of("projectFinished", "abortProject")));
            }

            // Ask the LLM for the next step
            Llm.StreamingResult result;
            result = llm.sendRequest(messages, toolSpecs, ToolChoice.REQUIRED, false);

            if (result.error() != null) {
                logger.debug("Error from LLM while deciding next action: {}", result.error().getMessage());
                io.systemOutput("Error from LLM while deciding next action (see debug log for details)");
                return;
            }
            if (result.chatResponse() == null || result.chatResponse().aiMessage() == null) {
                var msg = "Empty LLM response. Stopping project now";
                io.systemOutput(msg);
                return;
            }
            // show thinking
            if (result.chatResponse().aiMessage().text() != null && !result.chatResponse().aiMessage().text().isBlank()) {
                io.llmOutput("\n" + result.chatResponse().aiMessage().text(), ChatMessageType.AI);
            }

            totalUsage = TokenUsage.sum(totalUsage, result.chatResponse().tokenUsage());
            // Add the request and response to message history
            var aiMessage = ToolRegistry.removeDuplicateToolRequests(result.chatResponse().aiMessage());
            architectMessages.add(messages.getLast());
            architectMessages.add(aiMessage);

            var deduplicatedRequests = new LinkedHashSet<>(aiMessage.toolExecutionRequests());
            logger.debug("Unique tool requests are {}", deduplicatedRequests);
            io.llmOutput("\nTool calls: [%s]".formatted(deduplicatedRequests.stream().map(ToolExecutionRequest::name).collect(Collectors.joining(", "))), ChatMessageType.AI);

            // execute tool calls in the following order:
            // 1. projectFinished
            // 2. abortProject
            // 4. (everything else)
            // 5. searchAgent
            // 6. codeAgent
            ToolExecutionRequest answerReq = null, abortReq = null;
            var searchAgentReqs = new ArrayList<ToolExecutionRequest>();
            var codeAgentReqs = new ArrayList<ToolExecutionRequest>();
            var otherReqs = new ArrayList<ToolExecutionRequest>();
            for (var req : deduplicatedRequests) {
                if (req.name().equals("projectFinished")) {
                    answerReq = req;
                } else if (req.name().equals("abortProject")) {
                    abortReq = req;
                } else if (req.name().equals("callSearchAgent")) {
                    searchAgentReqs.add(req);
                } else if (req.name().equals("callCodeAgent")) {
                    codeAgentReqs.add(req);
                } else {
                    otherReqs.add(req);
                }
            }

            // 6) If we see "projectFinished" or "abortProject", handle it and then exit
            if (answerReq != null) {
                logger.debug("LLM decided to projectFinished. We'll finalize and stop");
                var toolResult = toolRegistry.executeTool(this, answerReq);
                logger.debug("Project final answer: {}", toolResult.resultText());
                return;
            }
            if (abortReq != null) {
                logger.debug("LLM decided to abortProject. We'll finalize and stop");
                var toolResult = toolRegistry.executeTool(this, abortReq);
                logger.debug("Project aborted: {}", toolResult.resultText());
                return;
            }

            // 7) Execute remaining tool calls in the desired order:
            otherReqs.sort((req1, req2) -> {
                int rank1 = getPriorityRank(req1.name());
                int rank2 = getPriorityRank(req2.name());
                return Integer.compare(rank1, rank2);
            });
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
            boolean interrupted = false;
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
                        var errorMessage = "Fatal LLM error executing Search Agent: %s".formatted(e.getCause().getMessage());
                        io.systemOutput(errorMessage);
                        break;
                    }
                    var errorMessage = "Error executing Search Agent: %s".formatted(e.getCause().getMessage());
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
                    return;
                }

                architectMessages.add(ToolExecutionResultMessage.from(req, toolResult.resultText()));
                logger.debug("Executed tool '{}' => result: {}", req.name(), toolResult.resultText());
            }
        }
    }

    /**
     * Helper method to get priority rank for tool names.
     * Lower number means higher priority.
     */
    private int getPriorityRank(String toolName) {
        if (toolName.equals("dropFragments")) return 1;
        if (toolName.equals("addReadOnlyFiles")) return 2;
        if (toolName.equals("addEditableFilesToWorkspace")) return 3;
        return 4; // all other tools have lowest priority
    }

    /**
     * Build the system/user messages for the LLM.
     * This includes the standard system prompt, workspace contents, history, agent's session messages,
     * and the final user message with the goal and conditional workspace warnings.
     */
    private List<ChatMessage> buildPrompt(int workspaceTokenSize, int minInputTokenLimit, List<ChatMessage> precomputedWorkspaceMessages) {
        var messages = new ArrayList<ChatMessage>();
        // System message defines the agent's role and general instructions
        messages.add(ArchitectPrompts.instance.systemMessage(contextManager, CodePrompts.ARCHITECT_REMINDER));
        // Workspace contents are added directly
        messages.addAll(precomputedWorkspaceMessages);
        // History from previous tasks/sessions
        messages.addAll(contextManager.getHistoryMessages());
        // This agent's own conversational history for the current goal
        messages.addAll(architectMessages);
        // Final user message with the goal and specific instructions for this turn, including workspace warnings
        messages.add(new UserMessage(ArchitectPrompts.instance.getFinalInstructions(contextManager, goal, workspaceTokenSize, minInputTokenLimit)));
        return messages;
    }
}
