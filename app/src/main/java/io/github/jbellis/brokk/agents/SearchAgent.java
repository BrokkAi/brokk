package io.github.jbellis.brokk.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.tools.SearchTools;
import io.github.jbellis.brokk.tools.ToolExecutionResult;
import io.github.jbellis.brokk.tools.ToolRegistry;
import io.github.jbellis.brokk.util.LogDescription;
import io.github.jbellis.brokk.util.Messages;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

/**
 * SearchAgent implements an iterative, agentic approach to code search.
 * It uses multiple steps of searching and reasoning to find code elements relevant to a query.
 */
public class SearchAgent {
    private static final Logger logger = LogManager.getLogger(SearchAgent.class);
    // 64K context window for models like R1
    private static final int TOKEN_BUDGET = 64000;
    // Summarize tool responses longer than this (about 120 loc)
    private static final int SUMMARIZE_THRESHOLD = 1000;

    /**
     * Record representing a knowledge item with a name and content.
     */
    public record KnowledgeItem(String name, String content) {}

    private final IAnalyzer analyzer;
    // Architect can create multiple concurrent SearchAgents
    private final int ordinal;
    private final ContextManager contextManager;
    private final Llm llm;
    private final IConsoleIO io;
    private final ToolRegistry toolRegistry;

    // Budget and action control state
    private boolean allowSearch;
    private boolean allowInspect;
    private boolean allowPagerank;
    private boolean allowAnswer;
    private boolean allowTextSearch;
    private boolean symbolsFound;
    private boolean beastMode;

    // Search state
    private final String query;
    private final List<ToolHistoryEntry> actionHistory = new ArrayList<>();
    private final List<KnowledgeItem> knowledge = new ArrayList<>();
    private final Set<String> toolCallSignatures = new HashSet<>();
    private final Set<String> trackedClassNames = new HashSet<>();
    private @Nullable CompletableFuture<String> initialContextSummary;

    private TokenUsage totalUsage = new TokenUsage(0, 0);

    public SearchAgent(String query,
                      ContextManager contextManager,
                      StreamingChatModel model,
                      ToolRegistry toolRegistry,
                      int ordinal) throws InterruptedException
    {
        this.query = query;
        this.contextManager = contextManager;
        this.analyzer = contextManager.getAnalyzer();
        this.ordinal = ordinal;
        this.llm = contextManager.getLlm(model, "Search: " + query);
        this.io = contextManager.getIo();
        this.toolRegistry = toolRegistry;

        // Set initial state based on analyzer presence and capabilities
        allowSearch = analyzer.isCpg();      // Needs CPG for searchSymbols, getUsages
        allowInspect = analyzer.isCpg();     // Needs CPG for getSources, getCallGraph
        allowPagerank = analyzer.isCpg();    // Needs CPG for getRelatedClasses
        allowAnswer = true;                 // Can always answer/abort initially if context exists
        allowTextSearch = !analyzer.isCpg(); // Enable text search only if no CPG analyzer
        symbolsFound = false;
        beastMode = false;
    }

    /**
     * Finalizes any pending summarizations in the action history by waiting for
     * CompletableFutures to complete and replacing raw results with learnings.
     */
    private void waitForPenultimateSummary() {
        if (actionHistory.size() <= 1) {
            return;
        }

        var step = actionHistory.get(actionHistory.size() - 2);
        // Already summarized? skip
        if (step.learnings != null) return;

        // If this step has a summarizeFuture, block for result
        if (step.summarizeFuture != null) {
            try {
                // Block and assign learnings
                step.learnings = step.summarizeFuture.get();
                logger.debug("Summarization complete for step: {}", step.request.name());
            } catch (ExecutionException e) {
                Throwable cause = castNonNull(e.getCause());
                logger.error("Error waiting for summary for tool {}: {}", step.request.name(), cause.getMessage(), cause);
                // Store raw result as learnings on error
                step.learnings = requireNonNull(step.execResult).resultText();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting for summary for tool {}", step.request.name());
                // Store raw result if interrupted
                step.learnings = requireNonNull(step.execResult).resultText();
            } finally {
                step.summarizeFuture = null; // Clear the future once handled
            }
        }
    }

    /**
     * Asynchronously summarizes a raw result using the quick model
     */
    private CompletableFuture<String> summarizeResultAsync(String query, ToolHistoryEntry step) {
        return CompletableFuture.supplyAsync(() -> {
            logger.debug("Summarizing result ...");
            var messages = new ArrayList<ChatMessage>();
            messages.add(new SystemMessage("""
                                           You are a code expert that extracts ALL information from the input that is relevant to the given query.
                                           Your partner has included his reasoning about what he is looking for; your work will be the only knowledge
                                           about this tool call that he will have to work with, he will not see the full result, so make it comprehensive!
                                           Be particularly sure to include ALL relevant source code chunks so he can reference them in his final answer,
                                           but DO NOT speculate or guess: your answer must ONLY include information in this result!
                                           Here are examples of good and bad extractions:
                                             - Bad: Found several classes and methods related to the query
                                             - Good: Found classes org.foo.bar.X and org.foo.baz.Y, and methods org.foo.qux.Z.method1 and org.foo.fizz.W.method2
                                             - Bad: The Foo class implements the Bar algorithm
                                             - Good: The Foo class implements the Bar algorithm.  Here are all the relevant lines of code:
                                               ```
                                               public class Foo {
                                               ...
                                               }
                                               ```
                                           """.stripIndent()));
            var arguments = step.argumentsMap();
            var reasoning = arguments.getOrDefault("reasoning", "");
            messages.add(new UserMessage("""
                                         <query>
                                         %s
                                         </query>
                                         <reasoning>
                                         %s
                                         </reasoning>
                                         <tool name="%s" %s>
                                         %s
                                         </tool>
                                         """.formatted(
                    query,
                    reasoning,
                    step.request.name(),
                    getToolParameterInfo(step),
                    requireNonNull(step.execResult).resultText()
            )));

            Llm.StreamingResult result;
            try {
                result = llm.sendRequest(messages);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (result.error() != null) {
                logger.warn("Summarization failed or was cancelled. Error: {}", result.error().getMessage());
                return requireNonNull(step.execResult).resultText(); // Return raw result on failure
            }
            return result.text();
        });
    }

    /**
     * Execute the search process, iterating through queries until completion.
     *
     * @return The final set of discovered code units
     */
    public TaskResult execute() throws InterruptedException {
        io.systemOutput("Search Agent engaged: `%s...`".formatted(LogDescription.getShortDescription(query)));

        // If context exists, ask LLM to evaluate its relevance and kick off async summary
        var contextWithClasses = contextManager.liveContext().allFragments().map(f -> {
            String text;
            text = f.text();
            return """
                   <fragment description="%s" sources="%s">
                   %s
                   </fragment>
                   """.stripIndent().formatted(f.description(),
                                               f.sources().stream().map(CodeUnit::fqName).collect(Collectors.joining(", ")),
                                               text);
        }).collect(Collectors.joining("\n\n"));
        if (!contextWithClasses.isBlank()) {
            llmOutput("\nEvaluating context...");
            var messages = new ArrayList<ChatMessage>();
            messages.add(new SystemMessage("""
                       You are an expert software architect.
                       evaluating which code fragments are relevant to a user query.
                       Review the following list of code fragments and select the ones most relevant to the query.
                       Make sure to include the fully qualified source (class, method, etc) as well as the code.
                       """.stripIndent()));
            messages.add(new UserMessage("<query>%s</query>\n\n".formatted(query) + contextWithClasses));
            var result = llm.sendRequest(messages);
            if (result.error() != null) {
                String errorMsg = "LLM error evaluating context: " + result.getDescription() + "; stopping search";
                io.systemOutput(errorMsg);
                return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, errorMsg));
            }
            if (result.isEmpty()) {
                String errorMsg = "LLM returned empty response evaluating context; stopping search";
                io.systemOutput(errorMsg);
                return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.EMPTY_RESPONSE, errorMsg));
            }
            var contextText = result.text();
            knowledge.add(new KnowledgeItem("Initial context", contextText));
            // Start summarizing the initial context evaluation asynchronously
            initialContextSummary = summarizeInitialContextAsync(query, contextText);
        }

        while (true) {
            // If thread interrupted, trigger Beast Mode for a final attempt
            if (Thread.interrupted()) {
                if (beastMode || !isInteractive()) {
                    logger.debug("Search Agent interrupted effective immediately");
                    // caller will display to user
                    throw new InterruptedException();
                }
                var msg = "Search Agent interrupted, attempting to answer with already-gathered information";
                io.systemOutput(msg);
                beastMode = true;
            }

            // Check if action history is now more than our budget, trigger Beast Mode if so
            if (!beastMode && actionHistorySize() > 0.90 * TOKEN_BUDGET) {
                logger.debug("Token budget exceeded, attempting final answer (Beast Mode)...");
                llmOutput("Token budget reached, attempting final answer");
                beastMode = true;
            }

            // Finalize summaries before the just-returned result
            waitForPenultimateSummary();

            // Special handling based on previous steps
            updateActionControlsBasedOnContext();

            // This line was duplicated. Removed the duplicate.
            // updateActionControlsBasedOnContext();

            // Decide what action(s) to take for this query
            List<ToolExecutionRequest> toolRequests;
            try {
                toolRequests = determineNextActions();
            } catch (InterruptedException e) {
                logger.debug("Caught InterruptedException in determineNextActions", e);
                // let interrupted() check in next loop handle it
                Thread.currentThread().interrupt();
                continue;
            }

            if (toolRequests.isEmpty()) {
                if (beastMode) {
                    logger.warn("LLM failed to provide a final answer/abort in Beast Mode.");
                    return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, "LLM failed to finalize search in Beast Mode."));
                } else {
                    logger.error("LLM failed to determine next action.");
                    // Use errorResult for consistent SessionResult format
                    return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, "LLM failed to determine next action."));
                }
            }

            // Update signatures and track classes *before* execution
            for (var request : toolRequests) {
                List<String> signatures = createToolCallSignatures(request); // Takes ToolExecutionRequest
                logger.debug("Adding signatures for request {}: {}", request.name(), signatures);
                toolCallSignatures.addAll(signatures);
                trackClassNamesFromToolCall(request); // Takes ToolExecutionRequest
            }

            // Execute the requested tools via the registry
            List<ToolHistoryEntry> results;
            try {
                results = executeToolCalls(toolRequests);
            } catch (InterruptedException e) {
                logger.debug("Caught InterruptedException in executeToolCalls", e);
                Thread.currentThread().interrupt();
                continue;
            }

            // Add results to history BEFORE checking for termination
            actionHistory.addAll(results);

            // Check if we should terminate (based on the *first* tool call result)
            // This assumes answer/abort are always singular actions.
            assert !results.isEmpty() : "executeToolCalls should not return empty if toolRequests was not empty";
            var firstResult = results.getFirst(); // This is now a ToolHistoryEntry
            String firstToolName = firstResult.request.name();
            if (firstToolName.equals("answerSearch")) {
                logger.debug("Search complete");
                assert results.size() == 1 : "Answer action should be solitary";
                ToolExecutionResult execResult = requireNonNull(firstResult.execResult, "execResult should be non-null for answerSearch");
                // Validate explanation before creating fragment
                String explanation = execResult.resultText();
                if (explanation.isBlank() || explanation.split("\\s").length < 5) {
                    logger.error("LLM provided blank explanation for 'answer' tool.");
                    return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.SEARCH_INVALID_ANSWER));
                }
                return createFinalFragment(firstResult);
            } else if (firstToolName.equals("abortSearch")) {
                logger.debug("Search aborted by agent");
                assert results.size() == 1 : "Abort action should be solitary";
                ToolExecutionResult execResult = requireNonNull(firstResult.execResult, "execResult should be non-null for abortSearch");
                // Validate explanation before creating fragment
                String explanation = execResult.resultText();
                if (explanation.isBlank() || explanation.equals("Success")) {
                    explanation = "No explanation provided by agent.";
                    logger.warn("LLM provided blank explanation for 'abort' tool. Using default.");
                }
                // Return the abort explanation as a simple fragment
                return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.LLM_ABORTED, explanation));
            }

            // Wait for initial context summary if it's pending (before the second LLM call)
            if (initialContextSummary != null) {
                try {
                    String summary = initialContextSummary.get();
                    logger.debug("Initial context summary complete.");
                    // Find the initial context entry in knowledge (assuming it's the first one)
                    assert !knowledge.isEmpty() && knowledge.getFirst().name().equals("Initial context");
                    knowledge.set(0, new KnowledgeItem("Initial context summary", summary));
                } catch (ExecutionException e) {
                    logger.error("Error waiting for initial context summary", e);
                    // Keep the full context in knowledge if summary fails
                } finally {
                    initialContextSummary = null; // Ensure this only runs once
                }
            }
        }
    }

    private void llmOutput(String output) {
        var ordinalText = ordinal > 0 ? "`Search #%d` ".formatted(ordinal) : "";
        io.llmOutput("\n\n%s%s".formatted(ordinalText, output), ChatMessageType.AI);
    }

    private boolean isInteractive() {
        return ordinal == 0;
    }

    private TaskResult errorResult(TaskResult.StopDetails details) {
        String explanation = !details.explanation().isBlank() ? details.explanation() :
                             switch (details.reason()) {
                                 case INTERRUPTED -> "Search was interrupted.";
                                 case LLM_ERROR -> "An error occurred interacting with the language model.";
                                 case SEARCH_INVALID_ANSWER ->
                                         "The final answer provided by the language model was invalid.";
                                 case LLM_ABORTED -> "The agent determined the query could not be answered.";
                                 default -> "Search stopped for reason: " + details.reason();
                             };
        return errorResult(details, explanation);
    }

    private TaskResult errorResult(TaskResult.StopDetails details, String explanation) {
        return new TaskResult("Search: " + query,
                              new ContextFragment.TaskFragment(contextManager, List.of(new UserMessage(query), new AiMessage(explanation)), query), 
                              Set.of(),
                              details);
    }

    /**
     * Asynchronously summarizes the initial context evaluation result using the quick model.
     */
    private CompletableFuture<String> summarizeInitialContextAsync(String query, String initialContextResult) {
        return CompletableFuture.supplyAsync(() -> {
            logger.debug("Summarizing initial context relevance...");
            ArrayList<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage("""
                                                   You are a code expert that extracts ALL information from the input that is relevant to the given query.
                                                   The input is an evaluation of existing code context against the query. Your summary will represent
                                                   the relevant parts of the existing context for future reasoning steps.
                                                   Be particularly sure to include ALL relevant source code chunks so they can be referenced later,
                                                   but DO NOT speculate or guess: your answer must ONLY include information present in the input!
                                           """.stripIndent()));
            messages.add(new UserMessage("""
                                                 <query>
                                                 %s
                                                 </query>
                                                 <information>
                                                 %s
                                                 </information>
                                         """.stripIndent().formatted(query, initialContextResult)));

            try {
                return llm.sendRequest(messages).text();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private int actionHistorySize() {
        var historyString = actionHistory.stream()
                .map(h -> formatHistory(h, -1))
                .collect(Collectors.joining());
        return Messages.getApproximateTokens(historyString);
    }

    /**
     * Update action controls based on current search context.
     */
    private void updateActionControlsBasedOnContext() {
        // Allow answer if we have either previous actions OR initial knowledge
        allowAnswer = !actionHistory.isEmpty() || !knowledge.isEmpty();

        allowSearch = true;
        allowInspect = true;
        allowPagerank = true;
        // don't reset allowTextSearch
    }

    /**
     * Gets human-readable parameter information from a tool call
     */
    private String formatListParameter(Map<String, Object> arguments, String paramName) {
        @SuppressWarnings("unchecked")
        List<String> items = (List<String>) arguments.get(paramName);
        if (items != null && !items.isEmpty()) {
            // turn it back into a JSON list or the LLM will be lazy too
            var mapper = new ObjectMapper();
            try {
                return "%s=%s".formatted(paramName, mapper.writeValueAsString(items));
            } catch (IOException e) {
                logger.error("Error formatting list parameter", e);
            }
        }
        return "";
    }

    /**
     * Gets human-readable parameter information from a ToolHistoryEntry (which contains the request).
     */
    private String getToolParameterInfo(ToolHistoryEntry historyEntry) {
        var request = historyEntry.request; // Use request from history entry

        try {
            // We need the arguments map from the history entry helper
            var arguments = historyEntry.argumentsMap(); // Use helper from ToolHistoryEntry

            return switch (request.name()) { // Use request.name()
                case "searchSymbols", "searchSubstrings", "searchFilenames" -> formatListParameter(arguments, "patterns");
                case "getFileContents" -> formatListParameter(arguments, "filenames");
                case "getFileSummaries" -> formatListParameter(arguments, "filePaths");
                case "getUsages" -> formatListParameter(arguments, "symbols");
                case "getRelatedClasses", "getClassSkeletons", "getClassSources" -> formatListParameter(arguments, "classNames");
                case "getMethodSources" -> formatListParameter(arguments, "methodNames");
                case "getCallGraphTo", "getCallGraphFrom" ->
                        arguments.getOrDefault("methodName", "").toString(); // Added graph tools
                case "answerSearch", "abortSearch" -> "finalizing";
                default ->
                        throw new IllegalArgumentException("Unknown tool name " + request.name()); // Use request.name()
            };
        } catch (Exception e) {
            logger.error("Error getting parameter info", e);
            return "";
        }
    }

    /**
     * Creates a list of unique signatures for a tool execution request based on tool name and parameters.
     * Each signature is typically of the form toolName:paramName=paramValue.
     */
    private List<String> createToolCallSignatures(ToolExecutionRequest request) {
        String toolName = request.name();
        try {
            // Reuse the argument parsing logic from ToolHistoryEntry if possible,
            // but ToolHistoryEntry isn't created yet. Parse arguments directly here.
            var mapper = new ObjectMapper();
            var arguments = mapper.readValue(request.arguments(), new TypeReference<Map<String, Object>>() {
            });

            return switch (toolName) {
                case "searchSymbols", "searchSubstrings", "searchFilenames" ->
                        getParameterListSignatures(toolName, arguments, "patterns");
                case "getFileContents" -> getParameterListSignatures(toolName, arguments, "filenames");
                case "getFileSummaries" -> getParameterListSignatures(toolName, arguments, "filePaths");
                case "getUsages" -> getParameterListSignatures(toolName, arguments, "symbols");
                case "getRelatedClasses", "getClassSkeletons",
                     "getClassSources" -> getParameterListSignatures(toolName, arguments, "classNames");
                case "getMethodSources" -> getParameterListSignatures(toolName, arguments, "methodNames");
                case "answerSearch", "abortSearch" -> List.of(toolName + ":finalizing");
                default -> List.of(toolName + ":unknown");
            };
        } catch (Exception e) {
            logger.error("Error creating tool call signature", e);
            return List.of(toolName + ":error");
        }
    }

    /**
     * Helper method to extract parameter values from a list parameter and create signatures
     */
    private List<String> getParameterListSignatures(String toolName, Map<String, Object> arguments, String paramName) {
        @SuppressWarnings("unchecked")
        List<String> items = (List<String>) arguments.get(paramName);
        if (items != null && !items.isEmpty()) {
            return items.stream()
                    .map(item -> toolName + ":" + paramName + "=" + item)
                    .collect(Collectors.toList());
        }
        return List.of(toolName + ":" + paramName + "=empty");
    }

    /**
     * Tracks class names mentioned in the arguments of a tool execution request.
     */
    private void trackClassNamesFromToolCall(ToolExecutionRequest request) {
        try {
            var arguments = new ToolHistoryEntry(request, null).argumentsMap();
            String toolName = request.name();

            switch (toolName) {
                case "getClassSkeletons", "getClassSources", "getRelatedClasses" -> {
                    @SuppressWarnings("unchecked")
                    List<String> classNames = (List<String>) arguments.get("classNames");
                    if (classNames != null) {
                        trackedClassNames.addAll(classNames);
                    }
                }
                case "getMethodSources" -> {
                    @SuppressWarnings("unchecked")
                    List<String> methodNames = (List<String>) arguments.get("methodNames");
                    if (methodNames != null) {
                        methodNames.stream()
                                .map(this::extractClassNameFromMethod)
                                .filter(Objects::nonNull)
                                .forEach(trackedClassNames::add);
                    }
                }
                case "getUsages" -> {
                    @SuppressWarnings("unchecked")
                    List<String> symbols = (List<String>) arguments.get("symbols");
                    if (symbols != null) {
                        symbols.stream()
                                .map(this::extractClassNameFromSymbol)
                                .flatMap(Optional::stream) // Use flatMap to handle Optional and get value
                                .forEach(trackedClassNames::add);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error tracking class names", e);
        }
    }

    /**
     * Extracts class name from a fully qualified method name
     */
    private @Nullable String extractClassNameFromMethod(String methodName) {
        int lastDot = methodName.lastIndexOf('.');
        if (lastDot > 0) {
            return methodName.substring(0, lastDot);
        }
        return null;
    }

    /**
     * Extracts class name from a symbol
     */
    private Optional<String> extractClassNameFromSymbol(String symbol) {
        return analyzer.getDefinition(symbol)
                .flatMap(cu -> cu.classUnit().map(CodeUnit::fqName));
    }

    /**
     * Checks if a tool request is a duplicate and if so, returns a forged getRelatedClasses request instead.
     * Returns the original request if not a duplicate.
     * Returns null if the forged request would ALSO be a duplicate (signals caller to skip).
     */
    private ToolExecutionRequest handleDuplicateRequestIfNeeded(ToolExecutionRequest request) {
        if (!analyzer.isCpg()) {
            return request;
        }

        var requestSignatures = createToolCallSignatures(request);

        if (toolCallSignatures.stream().anyMatch(requestSignatures::contains)) {
            logger.debug("Duplicate tool call detected: {}. Forging a getRelatedClasses call instead.", requestSignatures);
            request = createRelatedClassesRequest();

            if (toolCallSignatures.containsAll(createToolCallSignatures(request))) {
                logger.debug("Pagerank would be duplicate too!  Switching to Beast Mode.");
                beastMode = true;
                return request;
            }
        }
        return request;
    }

    /**
     * Creates a ToolExecutionRequest for getRelatedClasses using all currently tracked class names.
     */
    private ToolExecutionRequest createRelatedClassesRequest() {
        var classList = new ArrayList<>(trackedClassNames);

        String argumentsJson = """
                               {
                                  "classNames": %s
                               }
                               """.formatted(toJsonArray(classList));

        return ToolExecutionRequest.builder()
                .name("getRelatedClasses")
                .arguments(argumentsJson)
                .build();
    }

    private String toJsonArray(List<String> items) {
        var mapper = new ObjectMapper();
        try {
            return mapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            logger.error("Error serializing array", e);
            return "[]";
        }
    }

    /**
     * Determine the next action(s) to take for the current query by calling the LLM.
     * Returns a list of ToolExecutionRequests.
     */
    private List<ToolExecutionRequest> determineNextActions() throws InterruptedException {
        List<ChatMessage> messages = buildPrompt();

        List<String> allowedToolNames = calculateAllowedToolNames();
        List<ToolSpecification> tools = new ArrayList<>(toolRegistry.getRegisteredTools(allowedToolNames));
        if (allowAnswer) {
            tools.addAll(toolRegistry.getTools(this, List.of("answerSearch", "abortSearch")));
        }
        var result = llm.sendRequest(messages, tools, ToolChoice.REQUIRED, false);

        if (result.error() != null || result.isEmpty()) {
            return List.of();
        }

        var response = castNonNull(result.originalResponse());
        totalUsage = TokenUsage.sum(totalUsage, castNonNull(response).tokenUsage());
        return parseResponseToRequests(ToolRegistry.removeDuplicateToolRequests(response.aiMessage()));
    }

    /**
     * Calculate which tool *names* are allowed based on current agent state.
     */
    private List<String> calculateAllowedToolNames() {
        List<String> names = new ArrayList<>();
        if (beastMode) return names;

        if (analyzer.isCpg()) {
            if (allowSearch) {
                names.add("searchSymbols");
                names.add("getUsages");
            }
            if (allowPagerank) names.add("getRelatedClasses");
            if (allowInspect) {
                names.add("getClassSkeletons");
                names.add("getClassSources");
                names.add("getMethodSources");
                names.add("getCallGraphTo");
                names.add("getCallGraphFrom");
            }
        }
        if (allowTextSearch) {
            names.add("searchSubstrings");
            names.add("searchFilenames");
            names.add("getFileContents");
        }
        logger.debug("Calculated allowed tool names: {}", names);
        return names;
    }


    /**
     * Build the system prompt for determining the next action.
     */
    private List<ChatMessage> buildPrompt() {
        List<ChatMessage> messages = new ArrayList<>();

        var systemPrompt = new StringBuilder();
        systemPrompt.append("""
                            You are a code search agent that helps find relevant code based on queries.
                            Even if not explicitly stated, the query should be understood to refer to the current codebase,
                            and not a general-knowledge question.
                            Your goal is to find code definitions, implementations, and usages that answer the user's query.
                            """);

        if (!knowledge.isEmpty()) {
            var collected = knowledge.stream().map(t -> systemPrompt.append("""
                                                                            <entry description="%s">
                                                                            %s
                                                                            </entry>
                                                                            """.formatted(t.name(), t.content())))
                    .collect(Collectors.joining("\n"));
            systemPrompt.append("\n<knowledge>\n%s\n</knowledge>\n".formatted(collected));
        }

        messages.add(new SystemMessage(systemPrompt.toString()));

        StringBuilder userActionHistory = new StringBuilder();
        if (!actionHistory.isEmpty()) {
            userActionHistory.append("\n<action-history>\n");
            for (int i = 0; i < actionHistory.size(); i++) {
                var step = actionHistory.get(i);
                userActionHistory.append(formatHistory(step, i + 1));
            }
            userActionHistory.append("</action-history>\n");
        }

        var instructions = """
                           Determine the next tool to call to search for code related to the query, or `answer` if you have enough
                           information to answer the query.
                           - Round trips are expensive! If you have multiple search terms to learn about, group them in a single call.
                           - Of course, `abort` and `answer` tools cannot be composed with others.
                           """;
        if (symbolsFound) {
            if (beastMode || actionHistorySize() > 0.8 * TOKEN_BUDGET) {
                instructions = """
                               <beast-mode>
                               🔥 MAXIMUM PRIORITY OVERRIDE! 🔥
                               - YOU MUST FINALIZE RESULTS NOW WITH AVAILABLE INFORMATION
                               - USE DISCOVERED CODE UNITS TO PROVIDE BEST POSSIBLE ANSWER,
                               - OR EXPLAIN WHY YOU DID NOT SUCCEED
                               </beast-mode>
                               """;
                allowAnswer = true;
                allowSearch = false;
                allowTextSearch = false;
                allowInspect = false;
                allowPagerank = false;
            }
        } else {
            instructions += """
                            Start with broad searches, and then explore more specific code units once you find a foothold.
                            For example, if the user is asking
                            [how do Cassandra reads prevent compaction from invalidating the sstables they are referencing]
                            then we should start with searchSymbols([".*SSTable.*", ".*Compaction.*", ".*reference.*"],
                            instead of a more specific pattern like ".*SSTable.*compaction.*" or ".*compaction.*invalidation.*".
                            But once you have found specific relevant classes or methods, you can ask for them directly, you don't
                            need to make another symbol request first.
                            Don't forget to review your previous steps -- the search results won't change so don't repeat yourself!
                            """;
        }
        instructions += """
                        <query>
                        %s>
                        </query>
                        """.formatted(query);
        messages.add(new UserMessage(userActionHistory + instructions));

        return messages;
    }

    private String formatHistory(ToolHistoryEntry step, int i) {
        return """
               <step sequence="%d" tool="%s" %s>
                %s
               </step>
               """.formatted(i,
                             step.request.name(),
                             getToolParameterInfo(step),
                             step.getDisplayResult());
    }

    /**
     * Parse the LLM response into a list of ToolExecutionRequest objects.
     * Handles duplicate detection and ensures answer/abort are singular.
     */
    private List<ToolExecutionRequest> parseResponseToRequests(AiMessage response) {
        if (!response.hasToolExecutionRequests()) {
            logger.warn("No tool execution requests found in LLM response.");
            return List.of();
        }

        // Process each request with duplicate detection
        var requests = response.toolExecutionRequests().stream()
                .map(this::handleDuplicateRequestIfNeeded)
                .filter(Objects::nonNull)
                .toList();

        // If we have an Answer or Abort action, it must be the only one
        var answerOrAbort = requests.stream()
                .filter(req -> req.name().equals("answerSearch") || req.name().equals("abortSearch"))
                .findFirst();

        if (answerOrAbort.isPresent()) {
            if (requests.size() > 1) {
                logger.warn("LLM returned answer/abort with other tools. Isolating answer/abort.");
            }
            // Return only the answer/abort request
            return List.of(answerOrAbort.get());
        }

        if (requests.isEmpty() && !response.toolExecutionRequests().isEmpty()) {
            logger.warn("All tool requests were filtered out (likely as duplicates ending in beast mode trigger).");
            // Return empty list, the main loop will handle the beastMode flag.
        }

        return requests;
    }

    // --- Helper methods for executing tools and managing state ---

    /**
     * Executes the tool calls via the registry and prepares history entries.
     */
    private List<ToolHistoryEntry> executeToolCalls(List<ToolExecutionRequest> toolRequests) throws InterruptedException {
        var history = new ArrayList<ToolHistoryEntry>(toolRequests.size());

        for (var request : toolRequests) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Thread interrupted before executing tool " + request.name());
            }

            var explanation = getExplanationForToolRequest(request);
            if (!explanation.isBlank()) {
                llmOutput("\n" + explanation);
            }

            ToolExecutionResult result = toolRegistry.executeTool(this, request);
            var entry = new ToolHistoryEntry(request, result);

            handlePostExecution(entry);
            handleToolExecutionResult(result);
            history.add(entry);
        }

        return history;
    }

    /**
     * Enum that defines display metadata for each tool
     */
    private enum ToolDisplayMeta {
        SEARCH_SYMBOLS("🔍", "Searching for symbols"),
        SEARCH_SUBSTRINGS("🔍", "Searching for substrings"),
        SEARCH_FILENAMES("🔍", "Searching for filenames"),
        GET_FILE_CONTENTS("🔍", "Getting file contents"),
        GET_FILE_SUMMARIES("🔍", "Getting file summaries"),
        GET_USAGES("🔍", "Finding usages"),
        GET_CLASS_SKELETONS("🔍", "Getting class overview"),
        GET_CLASS_SOURCES("🔍", "Fetching class source"),
        GET_METHOD_SOURCES("🔍", "Fetching method source"),
        GET_RELATED_CLASSES("🔍", "Finding related code"),
        CALL_GRAPH_TO("🔍", "Getting call graph TO"),
        CALL_GRAPH_FROM("🔍", "Getting call graph FROM"),
        ANSWER_SEARCH("", ""),
        ABORT_SEARCH("", ""),
        UNKNOWN("❓", "");

        private final String icon;
        private final String headline;

        ToolDisplayMeta(String icon, String headline) {
            this.icon = icon;
            this.headline = headline;
        }

        public String getIcon() {
            return icon;
        }

        public String getHeadline() {
            return headline;
        }

        public static ToolDisplayMeta fromToolName(String toolName) {
            return switch (toolName) {
                case "searchSymbols" -> SEARCH_SYMBOLS;
                case "searchSubstrings" -> SEARCH_SUBSTRINGS;
                case "searchFilenames" -> SEARCH_FILENAMES;
                case "getFileContents" -> GET_FILE_CONTENTS;
                case "getFileSummaries" -> GET_FILE_SUMMARIES;
                case "getUsages" -> GET_USAGES;
                case "getRelatedClasses" -> GET_RELATED_CLASSES;
                case "getClassSkeletons" -> GET_CLASS_SKELETONS;
                case "getClassSources" -> GET_CLASS_SOURCES;
                case "getMethodSources" -> GET_METHOD_SOURCES;
                case "getCallGraphTo" -> CALL_GRAPH_TO;
                case "getCallGraphFrom" -> CALL_GRAPH_FROM;
                case "answerSearch" -> ANSWER_SEARCH;
                case "abortSearch" -> ABORT_SEARCH;
                default -> {
                    logger.warn("Unknown tool name for display metadata: {}", toolName);
                    yield UNKNOWN;
                }
            };
        }
    }

    /**
     * Generates a user-friendly explanation for a tool request as a Markdown code fence with YAML formatting.
     */
    private String getExplanationForToolRequest(ToolExecutionRequest request) {
        try {
            // Get tool display metadata
            var displayMeta = ToolDisplayMeta.fromToolName(request.name());

            // Skip empty explanations for answer/abort
            if (request.name().equals("answerSearch") || request.name().equals("abortSearch")) {
                return "";
            }

            // Parse the arguments
            var mapper = new ObjectMapper();
            Map<String, Object> args = mapper.readValue(request.arguments(), new TypeReference<>() {
            });

            // Convert to YAML format
            StringBuilder yamlBuilder = new StringBuilder();

            // Process each argument entry
            for (var entry : args.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                // Handle different value types
                if (value instanceof List<?> list) {
                    yamlBuilder.append(key).append(":\n");
                    for (Object item : list) {
                        yamlBuilder.append("  - ").append(item).append("\n");
                    }
                } else if (value instanceof String str && str.contains("\n")) {
                    // Use YAML block scalar for multi-line strings
                    yamlBuilder.append(key).append(": |\n");
                    for (String line : com.google.common.base.Splitter.on('\n').splitToList(str)) { // Use Splitter fully qualified
                        yamlBuilder.append("  ").append(line).append("\n");
                    }
                } else {
                    yamlBuilder.append(key).append(": ").append(value).append("\n");
                }
            }

            // Create the Markdown code fence with icon and headline
            return """
                   ```%s %s
                   %s```
                   """.formatted(displayMeta.getIcon(), displayMeta.getHeadline(), yamlBuilder);
        } catch (Exception e) {
            logger.error("Error formatting tool request explanation", e);
            String paramInfo = getToolParameterInfoFromRequest(request);
            var displayMeta = ToolDisplayMeta.fromToolName(request.name());
            return paramInfo.isBlank() ? displayMeta.getHeadline() :
                   displayMeta.getHeadline() + " (" + paramInfo + ")";
        }
    }

    /**
     * Gets parameter info directly from a request for explanation purposes.
     */
    private String getToolParameterInfoFromRequest(ToolExecutionRequest request) {
        try {
            var mapper = new ObjectMapper();
            var arguments = mapper.readValue(request.arguments(), new TypeReference<Map<String, Object>>() {
            });

            return switch (request.name()) {
                case "searchSymbols", "searchSubstrings", "searchFilenames" ->
                        formatListParameter(arguments, "patterns");
                case "getFileContents" -> formatListParameter(arguments, "filenames");
                case "getFileSummaries" -> formatListParameter(arguments, "filePaths");
                case "getUsages" -> formatListParameter(arguments, "symbols");
                case "getRelatedClasses", "getClassSkeletons", "getClassSources" ->
                        formatListParameter(arguments, "classNames");
                case "getMethodSources" -> formatListParameter(arguments, "methodNames");
                case "getCallGraphTo", "getCallGraphFrom" -> arguments.getOrDefault("methodName", "").toString();
                case "answerSearch", "abortSearch" -> "";
                default -> "";
            };
        } catch (Exception e) {
            logger.error("Error getting parameter info for request {}: {}", request.name(), e);
            return "";
        }
    }


    /**
     * Handles summarization or compression after a tool has executed.
     */
    private void handlePostExecution(ToolHistoryEntry historyEntry) {
        var request = historyEntry.request;
        var execResult = requireNonNull(historyEntry.execResult);

        if (execResult.status() != ToolExecutionResult.Status.SUCCESS) {
            return;
        }

        String toolName = request.name();
        String resultText = execResult.resultText();
        var toolsRequiringSummaries = Set.of("searchSymbols", "getUsages", "getClassSources",
                                             "searchSubstrings", "searchFilenames", "getFileContents", "getRelatedClasses",
                                             "getFileSummaries");

        if (toolsRequiringSummaries.contains(toolName) && Messages.getApproximateTokens(resultText) > SUMMARIZE_THRESHOLD) {
            logger.debug("Queueing summarization for tool {} (length {})", toolName, Messages.getApproximateTokens(resultText));
            historyEntry.summarizeFuture = summarizeResultAsync(query, historyEntry);
        } else if (toolName.equals("searchSymbols") || toolName.equals("getRelatedClasses")) {
            if (!resultText.startsWith("No ") && !resultText.startsWith("[")) {
                try {
                    List<String> rawSymbols = Arrays.asList(resultText.split(",\\s*"));
                    String label = toolName.equals("searchSymbols") ? "Relevant symbols" : "Related classes";
                    historyEntry.setCompressedResult(formatCompressedSymbolsForDisplay(label, rawSymbols));
                    logger.debug("Applied compression for tool {}", toolName);
                } catch (Exception e) {
                    logger.error("Error during symbol compression for {}: {}", toolName, e.getMessage());
                }
            } else {
                logger.debug("Skipping compression for {} (result: '{}')", toolName, resultText);
            }
        }
    }

    /**
     * Formats symbols for display, applying compression.
     */
    private String formatCompressedSymbolsForDisplay(String label, List<String> symbols) {
        if (symbols.isEmpty()) {
            return label + ": None found";
        }
        var compressionResult = SearchTools.compressSymbolsWithPackagePrefix(symbols);
        String commonPrefix = compressionResult.prefix();
        List<String> compressedSymbols = compressionResult.symbols();

        if (commonPrefix.isEmpty()) {
            return label + ": " + String.join(", ", symbols.stream().sorted().toList());
        }
        return SearchTools.formatCompressedSymbolsInternal(label, compressedSymbols.stream().sorted().toList(), commonPrefix);
    }


    /**
     * Handles agent-specific state updates and logic after a tool executes.
     * This is where the "composition" happens.
     */
    private void handleToolExecutionResult(@Nullable ToolExecutionResult execResult) {
        if (execResult == null || execResult.status() == ToolExecutionResult.Status.FAILURE) {
            String toolName = execResult == null ? "Unknown tool (null execResult)" : execResult.toolName();
            String resultText = execResult == null ? "N/A (null execResult)" : execResult.resultText();
            logger.warn("Tool execution failed, returned error, or execResult was null: {} - {}", toolName, resultText);
            return;
        }

        String toolName = execResult.toolName();
        switch (toolName) {
            case "searchSymbols" -> {
                this.allowTextSearch = true;
                if (!execResult.resultText().startsWith("No definitions found")) {
                    this.symbolsFound = true;
                    trackClassNamesFromResult(execResult.resultText());
                }
            }
            case "getUsages", "getRelatedClasses", "getClassSkeletons", "getClassSources", "getMethodSources" ->
                    trackClassNamesFromResult(execResult.resultText());
            default -> {
            }
        }
    }

    /**
     * Parses a tool result text to find and track class/symbol names.
     * Implementation needs to be robust to different tool output formats.
     */
    private void trackClassNamesFromResult(String resultText) {
        if (resultText.isBlank() || resultText.startsWith("No ") || resultText.startsWith("Error:")) {
            return;
        }

        Set<String> potentialNames = new HashSet<>();

        // Attempt to parse comma-separated list (common for searchSymbols, getRelatedClasses)
        // Handle compressed format "[Common package prefix: 'prefix'] item1, item2"
        String effectiveResult = resultText;
        String prefix = "";
        if (resultText.startsWith("[") && resultText.contains("] ")) {
            int prefixEnd = resultText.indexOf("] ");
            if (prefixEnd > 0) {
                // Extract prefix carefully, handling potential nested quotes/brackets? Unlikely here.
                int prefixStart = resultText.indexOf("'");
                int prefixEndQuote = resultText.lastIndexOf("'", prefixEnd);
                if (prefixStart != -1 && prefixEndQuote != -1 && prefixStart < prefixEndQuote) {
                    prefix = resultText.substring(prefixStart + 1, prefixEndQuote);
                }
                effectiveResult = resultText.substring(prefixEnd + 2).trim();
            }
        }

        String finalPrefix = prefix;
        potentialNames.addAll(Arrays.stream(effectiveResult.split("[,\\s]+"))
                                      .map(String::trim)
                                      .filter(s -> !s.isEmpty())
                                      .map(s -> finalPrefix.isEmpty() ? s : finalPrefix + "." + s)
                                      .filter(s -> s.contains(".") && Character.isJavaIdentifierStart(s.charAt(0)))
                                      .toList());

        Pattern classPattern = Pattern.compile("(?:Source code of |class )([\\w.$]+)");
        var matcher = classPattern.matcher(resultText);
        while (matcher.find()) {
            potentialNames.add(matcher.group(1));
        }

        Pattern usagePattern = Pattern.compile("Usage in ([\\w.$]+)\\.");
        matcher = usagePattern.matcher(resultText);
        while (matcher.find()) {
            potentialNames.add(matcher.group(1));
        }

        if (!potentialNames.isEmpty()) {
            var validNames = potentialNames.stream()
                    .map(this::extractClassNameFromSymbol)
                    .filter(Optional::isPresent)
                    .map(Optional::get) // Safe due to filter
                    .collect(Collectors.toSet());
            if (!validNames.isEmpty()) {
                logger.debug("Tracking potential class names from result: {}", validNames);
                trackedClassNames.addAll(validNames);
                logger.debug("Total tracked class names: {}", trackedClassNames);
            }
        }
    }


    /**
     * Generates the final context fragment based on the last successful action (answer/abort).
     */
    private TaskResult createFinalFragment(ToolHistoryEntry finalStep) {
        var request = finalStep.request;
        var execResult = requireNonNull(finalStep.execResult, "execResult must not be null when creating final fragment from answerSearch");
        var explanationText = execResult.resultText();

        assert request.name().equals("answerSearch") : "createFinalFragment called with wrong tool: " + request.name();
        assert execResult.status() == ToolExecutionResult.Status.SUCCESS : "createFinalFragment called with failed step";
        assert !explanationText.isBlank() && !explanationText.equals("Success") : "createFinalFragment called with blank/default explanation";

        var arguments = finalStep.argumentsMap();
        List<String> classNames = Optional.ofNullable(arguments.get("classNames"))
                .filter(List.class::isInstance)
                .map(obj -> (List<?>) obj)
                .orElse(List.of())
                .stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();

        logger.debug("LLM-determined relevant classes for final answer are {}", classNames);

        Set<String> combinedNames = new HashSet<>(classNames);
        combinedNames.addAll(trackedClassNames);
        logger.debug("Combined tracked and LLM classes before normalize/coalesce: {}", combinedNames);

        Set<CodeUnit> coalesced;
        if (analyzer.isEmpty()) {
            coalesced = Set.of();
            logger.debug("Empty analyzer, no sources identified");
        } else {
            var codeUnits = combinedNames.stream()
                    .flatMap(name -> analyzer.getDefinition(name).stream())
                    .flatMap(cu -> cu.classUnit().stream())
                    .collect(Collectors.toSet());
            coalesced = AnalyzerUtil.coalesceInnerClasses(codeUnits);
            logger.debug("Final sources identified (files): {}", coalesced.stream().map(CodeUnit::source).toList());
        }

        io.llmOutput("\n# Answer\n%s".formatted(explanationText), ChatMessageType.AI);
        var sessionName = "Search: " + query;
        var fragment = new ContextFragment.SearchFragment(contextManager, sessionName, List.copyOf(io.getLlmRawMessages()), coalesced);
        return new TaskResult(sessionName,
                              fragment, 
                              Set.of(),
                              new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS));
    }

    @Tool(value = "Provide a final answer to the query. Use this when you have enough information to fully address the query.")
    public String answerSearch(
            @P("Comprehensive explanation that answers the query. Include relevant source code snippets and explain how they relate to the query. Format the entire explanation with Markdown.")
            String explanation,
            @P("List of fully qualified class names (FQCNs) of ALL classes relevant to the explanation. Do not skip even minor details!")
            List<String> classNames
    ) {
        logger.debug("Answer tool selected with explanation: {}", explanation);
        return explanation;
    }

    @Tool(value = """
                  Abort the search process when you determine the question is not relevant to this codebase or when an answer cannot be found.
                  Use this as a last resort when you're confident no useful answer can be provided.
                  """)
    public String abortSearch(
            @P("Explanation of why the question cannot be answered or is not relevant to this codebase")
            String explanation
    ) {
        logger.debug("Abort tool selected with explanation: {}", explanation);
        return explanation;
    }

    private static class ToolHistoryEntry {
        final ToolExecutionRequest request;
        final @Nullable ToolExecutionResult execResult;
        @Nullable String compressedResult; // For searchSymbols/getRelatedClasses non-summarized case
        @Nullable String learnings; // Summarization result
        @Nullable CompletableFuture<String> summarizeFuture;

        ToolHistoryEntry(ToolExecutionRequest request, @Nullable ToolExecutionResult execResult) {
            this.request = request;
            this.execResult = execResult;
        }

        // Determines what to display in the prompt history
        String getDisplayResult() {
            if (learnings != null) return "<learnings>\n%s\n</learnings>".formatted(learnings);
            if (compressedResult != null) return "<result>\n%s\n</result>".formatted(compressedResult);
            if (execResult == null) return "<error>\nTool execution result was null.\n</error>";
            // Use resultText which holds success output or error message
            String resultKind = (execResult.status() == ToolExecutionResult.Status.SUCCESS) ? "result" : "error";
            return "<%s>\n%s\n</%s>".formatted(resultKind, execResult.resultText(), resultKind);
        }

        void setCompressedResult(@Nullable String compressed) {
            this.compressedResult = compressed;
        }

        // Helper to get arguments map (parsing JSON)
        Map<String, Object> argumentsMap() {
            try {
                var mapper = new ObjectMapper();
                return mapper.readValue(request.arguments(), new TypeReference<>() {
                });
            } catch (JsonProcessingException e) {
                logger.error("Error parsing arguments for request {}: {}", request.name(), e.getMessage());
                return Map.of();
            }
        }
    }
}
