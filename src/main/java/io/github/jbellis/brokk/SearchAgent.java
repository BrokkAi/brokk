package io.github.jbellis.brokk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.output.TokenUsage;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.tools.SearchTools;
import io.github.jbellis.brokk.tools.ToolExecutionResult;
import io.github.jbellis.brokk.tools.ToolRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scala.Option;
import scala.Tuple2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.Math.min;

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

    private final IAnalyzer analyzer;
    private final ContextManager contextManager;
    private final Coder coder;
    private final IConsoleIO io;
    private final StreamingChatLanguageModel model;
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
    private final List<Tuple2<String, String>> knowledge = new ArrayList<>();
    private final Set<String> toolCallSignatures = new HashSet<>();
    private final Set<String> trackedClassNames = new HashSet<>();
    private CompletableFuture<String> initialContextSummary = null;

    private TokenUsage totalUsage = new TokenUsage(0, 0);

    public SearchAgent(String query,
                       ContextManager contextManager,
                       Coder coder,
                       IConsoleIO io,
                       StreamingChatLanguageModel model,
                       ToolRegistry toolRegistry) {
        this.query = query;
        this.contextManager = contextManager;
        this.analyzer = contextManager.getProject().getAnalyzer();
        this.coder = coder;
        this.io = io;
        this.model = model;
        this.toolRegistry = toolRegistry;

        // Set initial state based on analyzer presence
        allowSearch = !analyzer.isEmpty();
        allowInspect = !analyzer.isEmpty();
        allowPagerank = !analyzer.isEmpty();
        allowAnswer = true; // Can always answer/abort initially if context exists
        allowTextSearch = analyzer.isEmpty(); // Enable text search only if no analyzer
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
                step.learnings = step.summarizeFuture.get(); // Directly access fields of ToolHistoryEntry
                logger.debug("Summarization complete for step: {}", step.request.name());
            } catch (ExecutionException e) {
                logger.error("Error waiting for summary for tool {}: {}", step.request.name(), e.getCause().getMessage(), e.getCause());
                // Store raw result as learnings on error
                step.learnings = step.execResult.resultText(); // Use text from execResult
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting for summary for tool {}", step.request.name());
                // Store raw result if interrupted
                step.learnings = step.execResult.resultText(); // Use text from execResult
            } finally {
                 step.summarizeFuture = null; // Clear the future once handled
            }
        }
    }

    /**
     * Asynchronously summarizes a raw result using the quick model
     */
    private CompletableFuture<String> summarizeResultAsync(String query, ToolHistoryEntry step)
    {
        // Use supplyAsync to run in background
        return CompletableFuture.supplyAsync(() -> {
            logger.debug("Summarizing result ...");
            // Build short system prompt or messages
            ArrayList<ChatMessage> messages = new ArrayList<>();
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
            """.stripIndent().formatted(
                    query,
                    reasoning,
                    step.request.name(),
                    getToolParameterInfo(step), // Pass ToolHistoryEntry
                    step.execResult.resultText()
            )));

            // TODO can we use a different model for summarization?
            var result = coder.sendMessage(model, messages);
            if (result.error() != null) {
                 logger.warn("Summarization failed or was cancelled.");
                 return step.execResult.resultText(); // Return raw result on failure
            }
            return result.chatResponse().aiMessage().text();
        });
    }

    /**
     * Execute the search process, iterating through queries until completion.
     * @return The final set of discovered code units
     */
    public ContextFragment.VirtualFragment execute() {
        // Initialize
        var contextWithClasses = contextManager.selectedContext().allFragments().map(f -> {
            String text;
            try {
                text = f.text();
            } catch (IOException e) {
                contextManager.removeBadFragment(f, e);
                return null;
            }
            return """
            <fragment description="%s" sources="%s">
            %s
            </fragment>
            """.stripIndent().formatted(f.description(),
                                        (f.sources(contextManager.getProject()).stream().map(CodeUnit::fqName).collect(Collectors.joining(", "))),
                                        text);
        }).filter(Objects::nonNull).collect(Collectors.joining("\n\n")); // Separate fragments better

        // If context exists, ask LLM to evaluate its relevance and kick off async summary
        if (!contextWithClasses.isBlank()) { 
            io.systemOutput("Evaluating context..."); // Updated log message
            var messages = new ArrayList<ChatMessage>();
            messages.add(new SystemMessage("""
            You are an expert software architect.
            evaluating which code fragments are relevant to a user query.
            Review the following list of code fragments and select the ones most relevant to the query.
            Make sure to include the fully qualified source (class, method, etc) as well as the code.
            """.stripIndent()));
            messages.add(new UserMessage("<query>%s</query>\n\n".formatted(query) + contextWithClasses));
            var result = coder.sendMessage(model, messages);
            if (result.cancelled()) {
                io.systemOutput("Cancelled context evaluation; stopping search");
                return null; // Propagate cancellation
            }
            if (result.error() != null) {
                io.systemOutput("LLM error evaluating context: " + result.error().getMessage() + "; stopping search");
                 // Propagate cancellation or error
                return null;
            }
            if (result.chatResponse() == null || result.chatResponse().aiMessage() == null) {
                 io.systemOutput("LLM returned empty response evaluating context; stopping search");
                 return null;
            }
            var contextText = result.chatResponse().aiMessage().text();
            knowledge.add(new Tuple2<>("Initial context", contextText));
            // Start summarizing the initial context evaluation asynchronously
            initialContextSummary = summarizeInitialContextAsync(query, contextText);
        }

        while (true) {
            // Wait for initial context summary if it's pending (before the second LLM call)
            if (initialContextSummary != null) {
                try {
                    String summary = initialContextSummary.get();
                    logger.debug("Initial context summary complete.");
                    // Find the initial context entry in knowledge (assuming it's the first one)
                    assert !knowledge.isEmpty() && knowledge.getFirst()._1.equals("Initial context");
                    knowledge.set(0, new Tuple2<>("Initial context summary", summary));
                } catch (ExecutionException e) {
                    logger.error("Error waiting for initial context summary", e);
                    // Keep the full context in knowledge if summary fails
                } catch (InterruptedException e) {
                    logger.error("Interrupted while waiting for initial context summary", e);
                    Thread.currentThread().interrupt();
                } finally {
                    initialContextSummary = null; // Ensure this only runs once
                }
            }

            // If thread interrupted, bail out
            if (Thread.interrupted()) {
                io.systemOutput("Interrupted; stopping search");
                return null;
            }

            // Check if action history is now more than our budget
            if (actionHistorySize() > 0.95 * TOKEN_BUDGET) {
                logger.debug("Stopping search because action history exceeds context window size");
                break;
            }

            // Finalize summaries before the just-returned result
            waitForPenultimateSummary();

            // Special handling based on previous steps
            updateActionControlsBasedOnContext();

            updateActionControlsBasedOnContext();

            // Decide what action(s) to take for this query
            var toolRequests = determineNextActions();
            if (Thread.interrupted()) {
                io.systemOutput("Interrupted; stopping search");
                return null;
            }
            if (toolRequests.isEmpty()) {
                io.systemOutput("Unable to get a response from the LLM; giving up search");
                return null;
            }

            // Update signatures and track classes *before* execution
            for (var request : toolRequests) {
                 List<String> signatures = createToolCallSignatures(request); // Takes ToolExecutionRequest
                 logger.debug("Adding signatures for request {}: {}", request.name(), signatures);
                 toolCallSignatures.addAll(signatures);
                 trackClassNamesFromToolCall(request); // Takes ToolExecutionRequest
            }

            // Execute the requested tools via the registry
            var results = executeToolCalls(toolRequests); // Returns List<ToolHistoryEntry>

            // Add results to history BEFORE checking for termination
            actionHistory.addAll(results);

            // Check if we should terminate (based on the *first* tool call result)
            // This assumes answer/abort are always singular actions.
            if (results.isEmpty()) {
                // Should not happen if toolRequests was non-empty, but guard anyway
                logger.warn("executeToolCalls returned empty results despite non-empty requests?");
                continue;
            }
            var firstResult = results.getFirst(); // This is now a ToolHistoryEntry
            String firstToolName = firstResult.request.name();
            if (firstToolName.equals("answerSearch")) {
                 logger.debug("Search complete");
                 assert results.size() == 1 : "Answer action should be solitary";
                 // Validate explanation before creating fragment
                 String explanation = firstResult.execResult.resultText();
                 if (explanation == null || explanation.isBlank() || explanation.equals("Success")) {
                     logger.error("LLM provided blank explanation for 'answer' tool.");
                     return new ContextFragment.StringFragment("Error: Agent failed to generate a valid answer explanation.", "Search Error: " + query);
                 }
                 return createFinalFragment(firstResult); // Takes ToolHistoryEntry
            } else if (firstToolName.equals("abortSearch")) {
                 logger.debug("Search aborted by agent");
                 assert results.size() == 1 : "Abort action should be solitary";
                 // Validate explanation before creating fragment
                 String explanation = firstResult.execResult.resultText();
                 if (explanation == null || explanation.isBlank() || explanation.equals("Success")) {
                     explanation = "No explanation provided by agent.";
                     logger.warn("LLM provided blank explanation for 'abort' tool. Using default.");
                 }
                 // Return the abort explanation as a simple fragment
                 return new ContextFragment.StringFragment("Search Aborted: " + explanation, "Search Aborted: " + query);
            }
        }

        logger.debug("Search ended because we hit the tokens cutoff or we were interrupted");
        return new ContextFragment.SearchFragment(query,
                                                  "No final answer provided, check the debug log for details",
                                                  Set.of());
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

            var response = coder.sendMessage(model, messages).chatResponse();
            return response.aiMessage().text();
        });
    }

    private int actionHistorySize() {
        var toIndex = min(actionHistory.size() - 1, 0);
        var historyString = IntStream.range(0, toIndex)
                .mapToObj(actionHistory::get)
                .map(h -> formatHistory(h, -1))
                .collect(Collectors.joining());
        return Models.getApproximateTokens(historyString); // Static method
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
            var mapper =  new ObjectMapper();
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
         if (historyEntry == null) return "";
         var request = historyEntry.request; // Use request from history entry

         try {
            // We need the arguments map from the history entry helper
            var arguments = historyEntry.argumentsMap(); // Use helper from ToolHistoryEntry

            return switch (request.name()) { // Use request.name()
                case "searchSymbols", "searchSubstrings", "searchFilenames" -> formatListParameter(arguments, "patterns");
                case "getFileContents" -> formatListParameter(arguments, "filenames");
                case "getUsages" -> formatListParameter(arguments, "symbols");
                case "getRelatedClasses", "getClassSkeletons",
                     "getClassSources" -> formatListParameter(arguments, "classNames");
                 case "getMethodSources" -> formatListParameter(arguments, "methodNames");
                 case "getCallGraphTo", "getCallGraphFrom" -> arguments.getOrDefault("methodName", "").toString(); // Added graph tools
                 case "answerSearch", "abortSearch" -> "finalizing";
                default -> throw new IllegalArgumentException("Unknown tool name " + request.name()); // Use request.name()
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
             var arguments = mapper.readValue(request.arguments(), new TypeReference<Map<String, Object>>() {});

             return switch (toolName) {
                case "searchSymbols", "searchSubstrings", "searchFilenames" -> getParameterListSignatures(toolName, arguments, "patterns");
                case "getFileContents" -> getParameterListSignatures(toolName, arguments, "filenames");
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
             var mapper = new ObjectMapper();
             var arguments = mapper.readValue(request.arguments(), new TypeReference<Map<String, Object>>() {});
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
    private String extractClassNameFromMethod(String methodName) {
        int lastDot = methodName.lastIndexOf('.');
        if (lastDot > 0) {
            return methodName.substring(0, lastDot);
        }
        return null;
    }

    /**
     * Extracts class name from a symbol
     */
    private String extractClassNameFromSymbol(String symbol) {
        // If the symbol contains a method or field reference
        int lastDot = symbol.lastIndexOf('.');
        if (lastDot > 0) {
            return symbol.substring(0, lastDot);
        }
        // Otherwise assume it's a class
        return symbol;
    }

    /**
     * Checks if a tool request is a duplicate and if so, returns a forged getRelatedClasses request instead.
     * Returns the original request if not a duplicate.
     * Returns null if the forged request would ALSO be a duplicate (signals caller to skip).
     */
     private ToolExecutionRequest handleDuplicateRequestIfNeeded(ToolExecutionRequest request) { // Takes/returns ToolExecutionRequest
         if (analyzer.isEmpty()) {
            // No duplicate detection without analyzer
            return request;
         }

        // Get signatures for this request
         var requestSignatures = createToolCallSignatures(request);

        // If we already have seen any of these signatures, forge a replacement call
        if (toolCallSignatures.stream().anyMatch(requestSignatures::contains)) {
            logger.debug("Duplicate tool call detected: {}. Forging a getRelatedClasses call instead.", requestSignatures);
            request = createRelatedClassesRequest();

            // if the forged call is itself a duplicate, use the original request but force Beast Mode next
            if (toolCallSignatures.containsAll(createToolCallSignatures(request))) {
                logger.debug("Pagerank would be duplicate too!  Switching to Beast Mode.");
                beastMode = true;
                return request;
            }
        }

        // Return the request (original if no duplication, or the replacement)
        return request;
    }

    /**
     * Creates a ToolExecutionRequest for getRelatedClasses using all currently tracked class names.
     */
     private ToolExecutionRequest createRelatedClassesRequest() { // Returns ToolExecutionRequest
        // Build the arguments for the getRelatedClasses call.
        // We'll pass all currently tracked class names, so that the agent
        // sees "related classes" from everything discovered so far.
        var classList = new ArrayList<>(trackedClassNames);

        // Construct JSON arguments for the call
        String argumentsJson = """
        {
           "classNames": %s
        }
        """.formatted(toJsonArray(classList));

        // Create a new ToolExecutionRequest
         return ToolExecutionRequest.builder()
                 .name("getRelatedClasses")
                 .arguments(argumentsJson)
                 .build();
    }

    private String toJsonArray(List<String> items)
    {
        // Create a JSON array from the list. e.g. ["Foo","Bar"]
        var mapper = new ObjectMapper();
        try {
            return mapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            logger.error("Error serializing array", e);
            // fallback to an empty array
            return "[]";
        }
    }
    
    /**
     * Determine the next action(s) to take for the current query by calling the LLM.
     * Returns a list of ToolExecutionRequests.
     */
     private List<ToolExecutionRequest> determineNextActions() {
         List<ChatMessage> messages = buildPrompt();

         // Ask LLM for next action with tools
         List<String> allowedToolNames = calculateAllowedToolNames();
         List<ToolSpecification> tools = new ArrayList<>(toolRegistry.getRegisteredTools(allowedToolNames));
         if (allowAnswer) {
             tools.addAll(toolRegistry.getTools(this.getClass(), List.of("answerSearch", "abortSearcH"));
         }
         var result = coder.sendMessage(model, messages, tools, ToolChoice.REQUIRED, false);

         if (result.cancelled()) {
             Thread.currentThread().interrupt();
             return List.of();
         }
         if (result.error() != null) {
             // Coder logs the error, return empty list to signal failure
              io.toolError("LLM error determining next action: " + result.error().getMessage());
             return List.of();
         }
         var response = result.chatResponse();
         if (response == null || response.aiMessage() == null) {
              io.toolError("LLM returned empty response when determining next action.");
              return List.of(); // Should not happen if Coder handled error correctly
         }

         totalUsage = TokenUsage.sum(totalUsage, response.tokenUsage());

         // Parse response into potentially multiple actions
         // This returns ToolExecutionRequest objects now
         return parseResponseToRequests(response.aiMessage());
     }

     /**
      * Calculate which tool *names* are allowed based on current agent state.
      */
     private List<String> calculateAllowedToolNames() {
         List<String> names = new ArrayList<>();
         if (beastMode) return names; // Only answer/abort in beast mode

         // Add names based on analyzer presence and state flags
         if (!analyzer.isEmpty()) {
             if (allowSearch) {
                 names.add("searchSymbols");
                 names.add("getUsages");
             }
             if (allowPagerank) names.add("getRelatedClasses");
             if (allowInspect) {
                 names.add("getClassSkeletons");
                 names.add("getClassSources");
                 names.add("getMethodSources");
                 names.add("getCallGraphTo"); // Keep or remove based on toolset
                 names.add("getCallGraphFrom");
             }
         }
         if (allowTextSearch) { // Text search tools don't depend on analyzer
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

        // System prompt outlining capabilities
        var systemPrompt = new StringBuilder();
        systemPrompt.append("""
        You are a code search agent that helps find relevant code based on queries.
        Even if not explicitly stated, the query should be understood to refer to the current codebase,
        and not a general-knowledge question.
        Your goal is to find code definitions, implementations, and usages that answer the user's query.
        """.stripIndent());

        // Add knowledge gathered during search
        if (!knowledge.isEmpty()) {
            var collected = knowledge.stream().map(t -> systemPrompt.append("""
            <entry description="%s">
            %s
            </entry>
            """.stripIndent().formatted(t._1, t._2)))
                    .collect(Collectors.joining("\n"));
            systemPrompt.append("\n<knowledge>\n%s\n</knowledge>\n".formatted(collected));
        }

        var sysPromptStr = systemPrompt.toString();
        messages.add(new SystemMessage(sysPromptStr));

        // Add action history to user message
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
            // Switch to beast mode if we're running out of time
            if (beastMode || actionHistorySize() > 0.8 * TOKEN_BUDGET) {
                instructions = """
                <beast-mode>
                🔥 MAXIMUM PRIORITY OVERRIDE! 🔥
                - YOU MUST FINALIZE RESULTS NOW WITH AVAILABLE INFORMATION
                - USE DISCOVERED CODE UNITS TO PROVIDE BEST POSSIBLE ANSWER,
                - OR EXPLAIN WHY YOU DID NOT SUCCEED
                </beast-mode>
                """.stripIndent();
                // Force finalize only
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
        """.stripIndent().formatted(query);
        messages.add(new UserMessage(userActionHistory + instructions.stripIndent()));

        return messages;
    }

    private String formatHistory(ToolHistoryEntry step, int i) {
        return """
        <step sequence="%d" tool="%s" %s>
         %s
        </step>
        """.stripIndent().formatted(i,
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
             logger.debug("No tool execution requests found in LLM response.");
              // This might happen if the LLM just returns text despite ToolChoice.REQUIRED
              // Coder.sendMessage has logic to retry in this case, so this path might indicate
              // a deeper issue or the LLM ignoring instructions. Return empty for now.
              io.toolError("LLM response did not contain expected tool calls.");
             return List.of();
         }

         // Process each request with duplicate detection
         var requests = response.toolExecutionRequests().stream()
                 .map(this::handleDuplicateRequestIfNeeded)
                 .filter(Objects::nonNull) // Filter out skipped duplicates if handleDuplicate decided to skip
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

     /** Executes the tool calls via the registry and prepares history entries. */
     private List<ToolHistoryEntry> executeToolCalls(List<ToolExecutionRequest> toolRequests) {
         // Execute sequentially for now, as parallel execution might complicate
         // state updates (e.g., allowTextSearch) and summarization logic.
         // Could revisit parallel execution later if performance becomes an issue.
         return toolRequests.stream()
                 .map(request -> {
                     io.systemOutput(getExplanationForToolRequest(request)); // Show user what's happening
                     ToolExecutionResult execResult = toolRegistry.executeTool(request); // CALL REGISTRY
                     var historyEntry = new ToolHistoryEntry(request, execResult);

                     // Handle post-execution logic like summarization/compression
                     handlePostExecution(historyEntry);

                     // Agent-specific state updates based on the *result*
                     handleToolExecutionResult(execResult); // Updates agent state like allowTextSearch

                     return historyEntry;
                 })
                 .collect(Collectors.toList()); // Ensure collection happens to force execution
     }

     /** Generates a user-friendly explanation for a tool request. */
     private String getExplanationForToolRequest(ToolExecutionRequest request) {
        String paramInfo = getToolParameterInfoFromRequest(request); // Need a version for requests
        String baseExplanation = switch (request.name()) {
             case "searchSymbols" -> "Searching for symbols";
             case "searchSubstrings" -> "Searching for substrings";
             case "searchFilenames" -> "Searching for filenames";
             case "getFileContents" -> "Getting file contents";
             case "getUsages" -> "Finding usages";
             case "getRelatedClasses" -> "Finding related code";
             case "getClassSkeletons" -> "Getting class overview";
             case "getClassSources" -> "Fetching class source";
             case "getMethodSources" -> "Fetching method source";
             case "getCallGraphTo" -> "Getting call graph TO";
             case "getCallGraphFrom" -> "Getting call graph FROM";
             case "answerSearch" -> "Answering the question";
             case "abortSearch" -> "Aborting the search";
             default -> {
                 logger.warn("Unknown tool name for explanation: {}", request.name());
                 yield "Processing request";
             }
         };
         return paramInfo.isBlank() ? baseExplanation : baseExplanation + " (" + paramInfo + ")";
     }

     /** Gets parameter info directly from a request for explanation purposes. */
     private String getToolParameterInfoFromRequest(ToolExecutionRequest request) {
         try {
             var mapper = new ObjectMapper();
             var arguments = mapper.readValue(request.arguments(), new TypeReference<Map<String, Object>>() {});

             return switch (request.name()) {
                 case "searchSymbols", "searchSubstrings", "searchFilenames" -> formatListParameter(arguments, "patterns");
                 case "getFileContents" -> formatListParameter(arguments, "filenames");
                 case "getUsages" -> formatListParameter(arguments, "symbols");
                 case "getRelatedClasses", "getClassSkeletons",
                      "getClassSources" -> formatListParameter(arguments, "classNames");
                 case "getMethodSources" -> formatListParameter(arguments, "methodNames");
                 case "getCallGraphTo", "getCallGraphFrom" -> arguments.getOrDefault("methodName", "").toString();
                 case "answerSearch", "abortSearch" -> "finalizing";
                 default -> ""; // Avoid exception for unknown tools
             };
         } catch (Exception e) {
             logger.error("Error getting parameter info for request {}: {}", request.name(), e);
             return "";
         }
     }


    /** Handles summarization or compression after a tool has executed. */
    private void handlePostExecution(ToolHistoryEntry historyEntry) {
        var request = historyEntry.request;
        var execResult = historyEntry.execResult;

        // Only process successful executions
        if (execResult.status() != ToolExecutionResult.Status.SUCCESS) {
            return;
        }

        String toolName = request.name();
        String resultText = execResult.resultText(); // Null check done in factory/constructor
        var toolsRequiringSummaries = Set.of("searchSymbols", "getUsages", "getClassSources", "searchSubstrings", "searchFilenames", "getFileContents", "getRelatedClasses");

        if (toolsRequiringSummaries.contains(toolName) && Models.getApproximateTokens(resultText) > SUMMARIZE_THRESHOLD) {
             logger.debug("Queueing summarization for tool {}", toolName);
            historyEntry.summarizeFuture = summarizeResultAsync(query, historyEntry);
        } else if (toolName.equals("searchSymbols") || toolName.equals("getRelatedClasses")) {
            // Apply prefix compression if not summarizing for searchSymbols and getRelatedClasses
             if (!resultText.startsWith("No ") && !resultText.startsWith("[")) { // Check if not empty/error/already compressed
                 // AnalysisTools now returns raw comma-separated list for these.
                 try {
                     List<String> rawSymbols = Arrays.asList(resultText.split(",\\s*"));
                     String label = toolName.equals("searchSymbols") ? "Relevant symbols" : "Related classes";
                     historyEntry.setCompressedResult(formatCompressedSymbolsForDisplay(label, rawSymbols));
                     logger.debug("Applied compression for tool {}", toolName);
                 } catch (Exception e) {
                     logger.error("Error during symbol compression for {}: {}", toolName, e.getMessage());
                     // Fallback: use raw result text - already stored in execResult
                 }
             } else {
                  logger.debug("Skipping compression for {} (result: '{}')", toolName, resultText);
             }
        }
    }

     /** Formats symbols for display, applying compression. */
     private String formatCompressedSymbolsForDisplay(String label, List<String> symbols) {
         if (symbols == null || symbols.isEmpty()) {
             return label + ": None found";
         }
         var compressionResult = SearchTools.compressSymbolsWithPackagePrefix(symbols); // Use static helper
         String commonPrefix = compressionResult._1();
         List<String> compressedSymbols = compressionResult._2();

         if (commonPrefix.isEmpty()) {
             return label + ": " + String.join(", ", symbols.stream().sorted().toList());
         }
         return SearchTools.formatCompressedSymbolsInternal(label, compressedSymbols.stream().sorted().toList(), commonPrefix); // Use static helper
     }


    /**
     * Handles agent-specific state updates and logic after a tool executes.
     * This is where the "composition" happens.
     */
    private void handleToolExecutionResult(ToolExecutionResult execResult) {
        // Check for execution errors first
         if (execResult.status() == ToolExecutionResult.Status.FAILURE) {
             logger.warn("Tool execution failed or returned error: {} - {}", execResult.toolName(), execResult.resultText());
             // Potentially update agent state based on error (e.g., disable a tool?)
             return;
         }

        // Tool-specific state updates
        switch (execResult.toolName()) {
            case "searchSymbols":
                // Logic specific to AFTER searchSymbols runs successfully
                this.allowTextSearch = true; // Enable text search capability
                // Check if the result indicates symbols were actually found
                if (!execResult.resultText().startsWith("No definitions found")) {
                    this.symbolsFound = true;
                    // Track names ONLY if symbols were found
                    trackClassNamesFromResult(execResult.resultText());
                }
                break;

            // Track class names from results of various tools
            case "getUsages":
            case "getRelatedClasses":
            case "getClassSkeletons":
            case "getClassSources":
            case "getMethodSources":
                 trackClassNamesFromResult(execResult.resultText());
                 break;

            // Add cases for other tools needing specific post-execution logic

            default:
                // No specific post-execution logic for this tool
                break;
        }

        // Common logic after any successful tool execution?
        // e.g., update token counts, log generic success
    }

    /**
     * Parses a tool result text to find and track class/symbol names.
     * Implementation needs to be robust to different tool output formats.
     */
    private void trackClassNamesFromResult(String resultText) {
         if (resultText == null || resultText.isBlank() || resultText.startsWith("No ") || resultText.startsWith("Error:")) return;

         Set<String> potentialNames = new HashSet<>();

         // Attempt to parse comma-separated list (common for searchSymbols, getRelatedClasses)
         // Handle compressed format "[Common package prefix: 'prefix'] item1, item2"
         String effectiveResult = resultText;
         String prefix = "";
         if (resultText.startsWith("[")) {
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

         // Add prefix back if needed
         String finalPrefix = prefix; // Final for lambda
         potentialNames.addAll(Arrays.stream(effectiveResult.split("[,\\s]+"))
                 .map(String::trim)
                 .filter(s -> !s.isEmpty())
                 .map(s -> finalPrefix.isEmpty() ? s : finalPrefix + s) // Re-add prefix
                 .filter(s -> s.contains(".") && Character.isJavaIdentifierStart(s.charAt(0))) // Basic FQCN check
                 .toList());

         // Regex for "Source code of X:" or "class X"
         Pattern classPattern = Pattern.compile("(?:Source code of |class )([\\w.$]+)"); // Include $ for inner classes
         var matcher = classPattern.matcher(resultText);
         while (matcher.find()) {
             potentialNames.add(matcher.group(1));
         }

         // Add FQCNs extracted from symbols found in usage reports
         Pattern usagePattern = Pattern.compile("Usage in ([\\w.$]+)\\."); // Match FQCN before method name
         matcher = usagePattern.matcher(resultText);
         while (matcher.find()) {
              potentialNames.add(matcher.group(1));
         }

         if (!potentialNames.isEmpty()) {
             // Filter again for plausible FQCNs before adding
             var validNames = potentialNames.stream()
                                            .map(this::extractClassNameFromSymbol) // Normalize FQCNs
                                            .collect(Collectors.toSet());
             if (!validNames.isEmpty()) {
                 logger.debug("Tracking potential class names from result: {}", validNames);
                 trackedClassNames.addAll(validNames);
                 logger.debug("Total tracked class names: {}", trackedClassNames);
             }
         }
     }


    /** Generates the final context fragment based on the last successful action (answer/abort). */
    private ContextFragment.VirtualFragment createFinalFragment(ToolHistoryEntry finalStep) {
        var request = finalStep.request;
        var execResult = finalStep.execResult;
        var explanationText = execResult.resultText();

        // Basic validation (should have been caught earlier, but assert for safety)
        assert request.name().equals("answerSearch") : "createFinalFragment called with wrong tool: " + request.name();
        assert execResult.status() == ToolExecutionResult.Status.SUCCESS : "createFinalFragment called with failed step";
        assert explanationText != null && !explanationText.isBlank() && !explanationText.equals("Success") : "createFinalFragment called with blank/default explanation";

        try {
            var arguments = finalStep.argumentsMap(); // Use helper
             // Ensure classNames is treated as List<String>
             Object classNamesObj = arguments.get("classNames");
             List<String> classNames = new ArrayList<>();
             if (classNamesObj instanceof List<?> list) {
                 list.forEach(item -> {
                     if (item instanceof String s) {
                         classNames.add(s);
                     }
                 });
             } else if (classNamesObj != null) {
                  logger.warn("Expected 'classNames' to be a List<String>, but got: {}", classNamesObj.getClass());
             }

            logger.debug("LLM-determined relevant classes for final answer are {}", classNames);

            // Combine LLM list with all tracked names for broader context, then coalesce
            Set<String> combinedNames = new HashSet<>(classNames);
            combinedNames.addAll(trackedClassNames);
            logger.debug("Combined tracked and LLM classes before coalesce: {}", combinedNames);

            var coalesced = combinedNames.stream()
                    .map(this::extractClassNameFromSymbol) // Normalize before filtering
                    .distinct() // Ensure uniqueness after normalization
                    .filter(c -> combinedNames.stream() // Coalesce inner classes
                                    .map(this::extractClassNameFromSymbol)
                                    .noneMatch(c2 -> !c.equals(c2) && c.startsWith(c2 + "$")))
                    .sorted() // Consistent order
                    .toList();
             logger.debug("Coalesced relevant classes: {}", coalesced);

            // Map final classes to CodeUnits representing the files they are in
            var sources = coalesced.stream()
                    .map(analyzer::getFileFor) // Get Option<ProjectFile>
                    .filter(Option::isDefined) // Filter out classes where file couldn't be found
                    .map(Option::get)          // Get ProjectFile
                    .distinct()                // Get unique files
                    .map(pf -> CodeUnit.cls(pf, pf.toString())) // Create CodeUnit representing the file
                    .collect(Collectors.toSet());

             logger.debug("Final sources identified (files): {}", sources.stream().map(CodeUnit::source).toList());
            // Use the validated explanation from the result text
            return new ContextFragment.SearchFragment(query, explanationText, sources);
        } catch (Exception e) {
            logger.error("Error creating final SearchFragment", e);
            // Fallback to string fragment with the explanation
            return new ContextFragment.StringFragment(explanationText, "Search Result (error processing sources): " + query);
        }
    }

    // --- Inner Class for History Storage ---
    // Restoring the definition of ToolHistoryEntry
    private static class ToolHistoryEntry {
         final ToolExecutionRequest request;
         final ToolExecutionResult execResult;
         String compressedResult; // For searchSymbols/getRelatedClasses non-summarized case
         String learnings; // Summarization result
         CompletableFuture<String> summarizeFuture;

         ToolHistoryEntry(ToolExecutionRequest request, ToolExecutionResult execResult) {
             this.request = request;
             this.execResult = execResult;
         }

         // Determines what to display in the prompt history
         String getDisplayResult() {
             if (learnings != null) return "<learnings>\n%s\n</learnings>".formatted(learnings);
             if (compressedResult != null) return "<result>\n%s\n</result>".formatted(compressedResult);
             // Use resultText which holds success output or error message
             String resultKind = (execResult.status() == ToolExecutionResult.Status.SUCCESS) ? "result" : "error";
             return "<%s>\n%s\n</%s>".formatted(resultKind, execResult.resultText(), resultKind);
         }

         void setCompressedResult(String compressed) {
             this.compressedResult = compressed;
         }

         // Helper to get arguments map (parsing JSON)
         Map<String, Object> argumentsMap() {
              try {
                 var mapper = new ObjectMapper();
                 return mapper.readValue(request.arguments(), new TypeReference<>() {});
              } catch (JsonProcessingException e) {
                 logger.error("Error parsing arguments for request {}: {}", request.name(), e.getMessage());
                  return Map.of(); // Return empty map on error
               }
          }
      }
 }
