package io.github.jbellis.brokk.agents;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import io.github.jbellis.brokk.AnalyzerUtil;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.Llm;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.tools.ToolExecutionResult;
import io.github.jbellis.brokk.tools.ToolRegistry;
import io.github.jbellis.brokk.tools.WorkspaceTools;
import io.github.jbellis.brokk.util.LogDescription;
import io.github.jbellis.brokk.util.Messages;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * SearchAgent: - Uses tools to both answer questions AND curate Workspace context for follow-on coding. - Starts by
 * calling ContextAgent to add recommended fragments to the Workspace. - Adds every learning step to Context history (no
 * hidden state). - Summarizes very large tool outputs before recording them. - Forges getRelatedClasses for duplicate
 * requests (like SearchAgent). - Enters "beast mode" to finalize with existing info if interrupted or context is near
 * full. - Never writes code itself; it prepares the Workspace for a later Code Agent run.
 */
public class SearchAgent {
    private static final Logger logger = LogManager.getLogger(SearchAgent.class);

    // Keep thresholds consistent with other agents
    private static final int SUMMARIZE_THRESHOLD = 1_000; // ~120 LOC equivalent
    private static final double WORKSPACE_CRITICAL = 0.80; // 90% of input limit

    private final ContextManager cm;
    private final StreamingChatModel model;
    private final Llm llm;
    private final ToolRegistry toolRegistry;
    private final IConsoleIO io;
    // private final int ordinal; // TODO use this to disambiguate different search agents spawned by Architect
    private final String goal;

    // Session-local conversation for this agent
    private final List<ChatMessage> sessionMessages = new ArrayList<>();

    // Duplicate detection and linkage discovery
    private final Set<String> toolCallSignatures = new HashSet<>();
    private final Set<String> trackedClassNames = new HashSet<>();

    // State toggles
    private boolean beastMode;

    public SearchAgent(
            String goal,
            ContextManager contextManager,
            StreamingChatModel model,
            ToolRegistry toolRegistry,
            int ordinal) {
        this.goal = goal;
        this.cm = contextManager;
        this.model = model;
        this.toolRegistry = toolRegistry;

        this.io = contextManager.getIo();
        this.llm = contextManager.getLlm(model, "Search: " + goal);

        this.beastMode = false;
    }

    /** Entry point. Runs until answer/abort or interruption. */
    public TaskResult execute() throws InterruptedException {
        io.systemOutput("Search Agent engaged: `%s...`".formatted(LogDescription.getShortDescription(goal)));

        // Seed Workspace with ContextAgent recommendations (same pattern as ArchitectAgent)
        addInitialContextToWorkspace();

        // Main loop: propose actions, execute, record, repeat until finalization
        while (true) {
            // Beast mode triggers
            if (Thread.interrupted()) {
                io.systemOutput("Search interrupted; attempting to finalize with available information");
                beastMode = true;
            }
            var inputLimit = cm.getService().getMaxInputTokens(model);
            var workspaceMessages =
                    new ArrayList<>(CodePrompts.instance.getWorkspaceContentsMessages(cm.liveContext()));
            var workspaceTokens = Messages.getApproximateTokens(workspaceMessages);
            if (!beastMode && inputLimit > 0 && workspaceTokens > WORKSPACE_CRITICAL * inputLimit) {
                io.systemOutput(
                        "Workspace is near the context limit; attempting finalization based on current knowledge");
                beastMode = true;
            }

            // Build prompt and allowed tools
            var messages = buildPrompt(workspaceTokens, inputLimit, workspaceMessages);
            var allowedToolNames = calculateAllowedToolNames();
            var toolSpecs = new ArrayList<>(toolRegistry.getRegisteredTools(allowedToolNames));
            toolSpecs.addAll(toolRegistry.getTools(this, List.of("answerSearch", "abortSearch")));

            // Decide next action(s)
            io.llmOutput("\n# Planning", ChatMessageType.AI, true, false);
            var result = llm.sendRequest(messages, toolSpecs, ToolChoice.REQUIRED, false);
            if (result.error() != null || result.isEmpty()) {
                var details =
                        result.error() != null ? requireNonNull(result.error().getMessage()) : "Empty response";
                io.systemOutput("LLM error planning next step: " + details);
                return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, details));
            }

            // Record turn
            sessionMessages.add(new UserMessage("What tools do you want to use next?"));
            sessionMessages.add(result.aiMessage());
            if (!result.text().isBlank()) {
                io.llmOutput("\n" + result.text(), ChatMessageType.AI);
            }

            // De-duplicate requested tools and handle answer/abort isolation
            var ai = ToolRegistry.removeDuplicateToolRequests(result.aiMessage());
            if (!ai.hasToolExecutionRequests()) {
                return errorResult(new TaskResult.StopDetails(
                        TaskResult.StopReason.LLM_ERROR, "No tool requests found in LLM response."));
            }
            var next = parseResponseToRequests(ai);
            if (next.isEmpty()) {
                // If everything got filtered (e.g., duplicate -> forged -> still duplicate), force beast mode
                beastMode = true;
                continue;
            }

            // If the first is an answer/abort, execute it alone and finalize
            var first = next.getFirst();
            if (first.name().equals("answerSearch") || first.name().equals("abortSearch")) {
                // Enforce singularity
                if (next.size() > 1) {
                    io.systemOutput("Answer/abort returned with other tools; ignoring others and finalizing.");
                }
                var exec = toolRegistry.executeTool(this, first);
                sessionMessages.add(ToolExecutionResultMessage.from(first, exec.resultText()));
                if (first.name().equals("answerSearch")) {
                    return createFinalFragment(first, exec);
                } else {
                    var explain = exec.resultText().isBlank() ? "No explanation provided by agent." : exec.resultText();
                    return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.LLM_ABORTED, explain));
                }
            }

            // Otherwise execute all tool calls in a deterministic order (Workspace ops before exploration helps
            // pruning)
            var sortedCalls = next.stream()
                    .sorted(Comparator.comparingInt(req -> priority(req.name())))
                    .toList();
            for (var req : sortedCalls) {
                // Duplicate guard and class tracking before execution
                var signatures = createToolCallSignatures(req);
                toolCallSignatures.addAll(signatures);
                trackClassNamesFromToolCall(req);

                var explanation = ToolRegistry.getExplanationForToolRequest(req);
                if (!explanation.isBlank()) {
                    io.llmOutput("\n" + explanation, ChatMessageType.AI);
                }

                ToolExecutionResult exec;
                try {
                    exec = toolRegistry.executeTool(this, req);
                } catch (Exception e) {
                    logger.warn("Tool execution failed for {}: {}", req.name(), e.getMessage(), e);
                    exec = ToolExecutionResult.failure(req, "Error: " + e.getMessage());
                }

                // Summarize large results
                var display = exec.resultText();
                boolean summarize = exec.status() == ToolExecutionResult.Status.SUCCESS
                        && Messages.getApproximateTokens(display) > SUMMARIZE_THRESHOLD
                        && shouldSummarize(req.name());
                if (summarize) {
                    var reasoning =
                            getArgumentsMap(req).getOrDefault("reasoning", "").toString();
                    display = summarizeResult(goal, req, display, reasoning);
                }

                // Write to visible transcript and to Context history
                sessionMessages.add(ToolExecutionResultMessage.from(req, display));
                if (display.isEmpty()) {
                    logger.warn("Empty tool call result {} from {}", display, req);
                } else {
                    io.llmOutput(display, ChatMessageType.AI);
                }

                // Light composition: update discovery and flow
                handleStateAfterTool(exec);
            }
        }
    }

    // =======================
    // Prompt and planning
    // =======================

    private List<ChatMessage> buildPrompt(
            int workspaceTokens, int minInputLimit, List<ChatMessage> precomputedWorkspaceMessages)
            throws InterruptedException {
        var messages = new ArrayList<ChatMessage>();

        // System role: similar to Architect, with stronger emphasis on pruning
        var sys = new SystemMessage(
                """
            You are the Search Agent.
            Your job:
              - find and organize code relevant to the user's question or implementation goal,
              - aggressively curate the Workspace so a Code Agent can implement next without confusion,
              - never write code yourself.

            Critical rules:
              1) At EVERY TURN, drop irrelevant fragments from the Workspace.
                 Prefer summaries over full files. Replace long fragments with concise summaries of content related to the goal first,
                 then drop the originals.
              2) Use search and inspection tools to discover relevant code, including classes/methods/usages/call graphs.
              3) The symbol-based tools only have visibility into the following file types: %s
                 Use text-based tools if you need to search other file types.
              4) Group related lookups into a single call when possible.
              5) Make multiple tool calls at once when searching for different types of code.

            Output discipline:
              - Think before calling tools.
              - If you already know what to add, use Workspace tools directly; do not search redundantly.
            """
                        .formatted(cm.getProject().getAnalyzerLanguages().stream()
                                .map(Language::name)
                                .collect(Collectors.joining(", "))));
        messages.add(sys);

        // Current Workspace contents
        messages.addAll(precomputedWorkspaceMessages);

        // Related classes (auto-context) like Architect
        var auto = cm.liveContext().buildAutoContext(10);
        var ac = auto.text();
        if (!ac.isBlank()) {
            messages.add(new UserMessage(
                    """
                <related_classes>
                These MAY be relevant. They are NOT in the Workspace yet.
                Add summaries or sources if needed; otherwise ignore them.

                %s
                </related_classes>
                """
                            .stripIndent()
                            .formatted(ac)));
            messages.add(new AiMessage("Acknowledged. I will explicitly add only what is relevant."));
        }

        // Recent project history plus this agent's messages
        messages.addAll(cm.getHistoryMessages());
        messages.addAll(sessionMessages);

        // Workspace size warning and final instruction
        String warning = "";
        if (minInputLimit > 0) {
            double pct = (double) workspaceTokens / minInputLimit * 100.0;
            if (pct > 90.0) {
                warning =
                        """
                    CRITICAL: Workspace is using %.0f%% of input budget (%d tokens of %d).
                    You MUST reduce Workspace size immediately before any further exploration.
                    Replace full text with summaries and drop non-essential fragments first.
                    """
                                .stripIndent()
                                .formatted(pct, workspaceTokens, minInputLimit);
            } else if (pct > 60.0) {
                warning =
                        """
                    NOTICE: Workspace is using %.0f%% of input budget (%d tokens of %d).
                    Prefer summaries and prune aggressively before expanding further.
                    """
                                .stripIndent()
                                .formatted(pct, workspaceTokens, minInputLimit);
            }
        }

        String directive =
                """
            <goal>
            %s
            </goal>

            Decide the next tool action(s) to make progress toward answering the question and preparing the Workspace
            for follow-on code changes. If you already have enough to answer, use answerSearch. If we cannot answer,
            use abortSearch with a clear explanation.

            You are encouraged to invoke multiple Workspace tools at once (add summaries, drop fragments, etc).
            Do NOT invoke multiple answer/abort actions. Do NOT write code.

            %s
            """
                        .stripIndent()
                        .formatted(goal, warning);

        // Beast mode directive
        if (beastMode) {
            directive = directive
                    + """
                <beast-mode>
                The Workspace is full or execution was interrupted.
                Finalize now using the best available information.
                Prefer answerSearch; otherwise use abortSearch with reasons.
                </beast-mode>
                """
                            .stripIndent();
        }

        messages.add(new UserMessage(directive));
        return messages;
    }

    private List<String> calculateAllowedToolNames() {
        if (beastMode) {
            // Only answer/abort will be exposed alongside this when we build tools list
            return List.of();
        }

        var names = new ArrayList<String>();

        // Analyzer-backed exploration
        if (cm.getAnalyzerWrapper().isCpg()) {
            names.add("searchSymbols");

            names.add("getUsages");
            names.add("getRelatedClasses");

            names.add("getClassSkeletons");
            names.add("getClassSources");
            names.add("getMethodSources");
            names.add("getCallGraphTo");
            names.add("getCallGraphFrom");
        }

        // Text-based search
        names.add("searchSubstrings");
        names.add("searchGitCommitMessages");
        names.add("searchFilenames");
        names.add("getFileContents");
        names.add("getFileSummaries");

        // Workspace curation
        names.add("addFilesToWorkspace");
        names.add("addClassesToWorkspace");
        names.add("addClassSummariesToWorkspace");
        names.add("addMethodSourcesToWorkspace");
        names.add("addFileSummariesToWorkspace");
        names.add("addSymbolUsagesToWorkspace");
        names.add("addCallGraphInToWorkspace");
        names.add("addCallGraphOutToWorkspace");
        names.add("addTextToWorkspace");
        names.add("dropWorkspaceFragments");
        names.add("getFiles");

        logger.debug("Allowed tool names: {}", names);
        return names;
    }

    private List<ToolExecutionRequest> parseResponseToRequests(AiMessage response) {
        if (!response.hasToolExecutionRequests()) {
            return List.of();
        }
        // Forge getRelatedClasses for duplicates (like SearchAgent)
        var raw = response.toolExecutionRequests().stream()
                .map(this::handleDuplicateRequestIfNeeded)
                .toList();

        // If an answer/abort is present, isolate it
        var firstFinal = raw.stream()
                .filter(r -> r.name().equals("answerSearch") || r.name().equals("abortSearch"))
                .findFirst();
        return firstFinal.map(List::of).orElse(raw);
    }

    private int priority(String toolName) {
        // Prioritize workspace pruning and adding summaries before deeper exploration.
        return switch (toolName) {
            case "dropWorkspaceFragments" -> 1;
            case "addTextToWorkspace" -> 2;
            case "addClassSummariesToWorkspace", "addFileSummariesToWorkspace", "addMethodSourcesToWorkspace" -> 3;
            case "addFilesToWorkspace", "addClassesToWorkspace", "addSymbolUsagesToWorkspace" -> 4;
            case "getRelatedClasses" -> 5;
            case "searchSymbols", "getUsages", "searchSubstrings", "searchFilenames", "searchGitCommitMessages" -> 6;
            case "getClassSkeletons", "getClassSources", "getMethodSources" -> 7;
            case "getCallGraphTo", "getCallGraphFrom", "getFileContents", "getFileSummaries", "getFiles" -> 8;
            default -> 9;
        };
    }

    // =======================
    // Initial context seeding
    // =======================

    private void addInitialContextToWorkspace() throws InterruptedException {
        var contextAgent = new ContextAgent(cm, model, goal, true);
        io.llmOutput("\nPerforming initial project scan", ChatMessageType.CUSTOM);

        var recommendation = contextAgent.getRecommendations(true);
        if (!recommendation.reasoning().isEmpty()) {
            io.llmOutput("\n\nReasoning for recommendations: " + recommendation.reasoning(), ChatMessageType.CUSTOM);
        }
        if (!recommendation.success() || recommendation.fragments().isEmpty()) {
            io.llmOutput("\n\nNo additional recommended context found", ChatMessageType.CUSTOM);
            return;
        }

        var totalTokens = contextAgent.calculateFragmentTokens(recommendation.fragments());
        int finalBudget = cm.getService().getMaxInputTokens(model) / 2;
        if (totalTokens > finalBudget) {
            var summaries = ContextFragment.getSummary(recommendation.fragments());
            var msgs = new ArrayList<>(List.of(
                    new UserMessage("Scan for relevant files"),
                    new AiMessage("Potentially relevant files:\n" + summaries)));
            cm.addToHistory(
                    new TaskResult(cm, "Scan for relevant files", msgs, Set.of(), TaskResult.StopReason.SUCCESS),
                    false);
        } else {
            WorkspaceTools.addToWorkspace(cm, recommendation);
            io.llmOutput("\n\nScan complete; added recommendations to the Workspace.", ChatMessageType.CUSTOM);
        }
    }

    // =======================
    // Answer/abort tools
    // =======================

    @Tool(value = "Provide a final answer to the user's question or goal. Use this when you have enough information.")
    public String answerSearch(
            @P(
                            "Comprehensive explanation that answers the query. Include relevant code snippets and how they relate, formatted in Markdown.")
                    String explanation,
            @P("List of fully qualified class names (FQCNs) relevant to the explanation (exhaustive).")
                    List<String> classNames) {
        logger.debug("answerSearch selected with explanation length {}", explanation.length());
        return explanation;
    }

    @Tool(value = "Abort when you determine the question is not answerable from this codebase or is out of scope.")
    public String abortSearch(
            @P("Clear explanation of why the question cannot be answered from this codebase.") String explanation) {
        logger.debug("abortSearch selected with explanation length {}", explanation.length());
        return explanation;
    }

    // =======================
    // Finalization and errors
    // =======================

    private TaskResult createFinalFragment(ToolExecutionRequest request, ToolExecutionResult execResult)
            throws InterruptedException {
        var explanation = execResult.resultText();
        if (explanation.isBlank() || explanation.split("\\s+").length < 5) {
            return errorResult(new TaskResult.StopDetails(
                    TaskResult.StopReason.SEARCH_INVALID_ANSWER, "Final answer was blank or too short."));
        }

        // Pull any classNames provided explicitly, union with classes we tracked from discovery
        var args = getArgumentsMap(request);
        @SuppressWarnings("unchecked")
        List<String> classNames = (List<String>) args.getOrDefault("classNames", List.of());

        var combined = new HashSet<String>(trackedClassNames);
        combined.addAll(classNames);

        Set<CodeUnit> coalesced;
        var analyzer = cm.getAnalyzer();
        if (analyzer.isEmpty()) {
            coalesced = Set.of();
        } else {
            var units = combined.stream()
                    .flatMap(name -> analyzer.getDefinition(name).stream())
                    .flatMap(cu -> cu.classUnit().stream())
                    .collect(Collectors.toSet());
            coalesced = AnalyzerUtil.coalesceInnerClasses(units);
        }

        io.llmOutput("\n# Answer\n" + explanation, ChatMessageType.AI);
        var sessionName = "Search: " + goal;
        var fragment = new ContextFragment.SearchFragment(
                cm, sessionName, List.copyOf(io.getLlmRawMessages(false)), coalesced);
        return new TaskResult(
                sessionName, fragment, Set.of(), new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS));
    }

    private TaskResult errorResult(TaskResult.StopDetails details) {
        String explanation = !details.explanation().isBlank()
                ? details.explanation()
                : switch (details.reason()) {
                    case INTERRUPTED -> "Execution was interrupted.";
                    case LLM_ERROR -> "An error occurred with the language model.";
                    case SEARCH_INVALID_ANSWER -> "The final answer provided by the model was invalid.";
                    case LLM_ABORTED -> "The agent determined the query could not be answered.";
                    default -> "Stopped: " + details.reason();
                };
        return new TaskResult(
                "Search: " + goal,
                new ContextFragment.TaskFragment(cm, List.of(new UserMessage(goal), new AiMessage(explanation)), goal),
                Set.of(),
                details);
    }

    // =======================
    // State and summarization
    // =======================

    private boolean shouldSummarize(String toolName) {
        return Set.of(
                        "searchSymbols",
                        "getUsages",
                        "getRelatedClasses",
                        "getClassSources",
                        "searchSubstrings",
                        "searchFilenames",
                        "searchGitCommitMessages",
                        "getFileContents",
                        "getFileSummaries")
                .contains(toolName);
    }

    private void handleStateAfterTool(@Nullable ToolExecutionResult exec) throws InterruptedException {
        if (exec == null || exec.status() != ToolExecutionResult.Status.SUCCESS) {
            return;
        }
        switch (exec.toolName()) {
            case "searchSymbols" -> {
                if (!exec.resultText().startsWith("No definitions found")) {
                    trackClassNamesFromResult(exec.resultText());
                }
            }
            case "getUsages", "getRelatedClasses", "getClassSkeletons", "getClassSources", "getMethodSources" ->
                trackClassNamesFromResult(exec.resultText());
            default -> {}
        }
    }

    private String summarizeResult(
            String query, ToolExecutionRequest request, String rawResult, @Nullable String reasoning)
            throws RuntimeException {
        var sys = new SystemMessage(
                """
            You are a code expert extracting ALL information relevant to the given goal
            from the provided tool call result.

            Your output will be given to the agent running the search, and replaces the raw result.
            Thus, you must include every relevant class/method name and any
            relevant code snippets that may be needed later. DO NOT speculate; only use the provided content.
            """
                        .stripIndent());

        var user = new UserMessage(
                """
            <goal>
            %s
            </goal>
            <reasoning>
            %s
            </reasoning>
            <tool name="%s">
            %s
            </tool>
            """
                        .stripIndent()
                        .formatted(query, reasoning == null ? "" : reasoning, request.name(), rawResult));
        Llm.StreamingResult sr;
        try {
            sr = llm.sendRequest(List.of(sys, user));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (sr.error() != null || sr.isEmpty()) {
            return rawResult; // fallback to raw
        }
        return sr.text();
    }

    // =======================
    // Duplicate forging & tracking
    // =======================

    private ToolExecutionRequest handleDuplicateRequestIfNeeded(ToolExecutionRequest request) {
        if (!cm.getAnalyzerWrapper().isCpg()) return request;

        var requestSignatures = createToolCallSignatures(request);
        if (toolCallSignatures.stream().anyMatch(requestSignatures::contains)) {
            logger.debug("Duplicate call detected for {}; forging getRelatedClasses", request.name());
            request = createRelatedClassesRequest();
            if (toolCallSignatures.containsAll(createToolCallSignatures(request))) {
                logger.debug("Forged getRelatedClasses would also be duplicate; switching to beast mode");
                beastMode = true;
            }
        }
        return request;
    }

    private ToolExecutionRequest createRelatedClassesRequest() {
        var classList = new ArrayList<>(trackedClassNames);
        String args = toJsonArrayArg("classNames", classList);
        return ToolExecutionRequest.builder()
                .name("getRelatedClasses")
                .arguments(args)
                .build();
    }

    private String toJsonArrayArg(String param, List<String> values) {
        var mapper = new ObjectMapper();
        try {
            return """
                   { "%s": %s }
                   """
                    .stripIndent()
                    .formatted(param, mapper.writeValueAsString(values));
        } catch (JsonProcessingException e) {
            logger.error("Error serializing array for {}", param, e);
            return """
                   { "%s": [] }
                   """
                    .stripIndent()
                    .formatted(param);
        }
    }

    private List<String> createToolCallSignatures(ToolExecutionRequest request) {
        String toolName = request.name();
        try {
            var args = getArgumentsMap(request);
            return switch (toolName) {
                case "searchSymbols", "searchSubstrings", "searchFilenames" ->
                    listParamSignatures(toolName, args, "patterns");
                case "getFileContents" -> listParamSignatures(toolName, args, "filenames");
                case "getFileSummaries" -> listParamSignatures(toolName, args, "filePaths");
                case "getUsages" -> listParamSignatures(toolName, args, "symbols");
                case "getRelatedClasses", "getClassSkeletons", "getClassSources" ->
                    listParamSignatures(toolName, args, "classNames");
                case "getMethodSources" -> listParamSignatures(toolName, args, "methodNames");
                case "searchGitCommitMessages" -> List.of(toolName + ":pattern=" + args.getOrDefault("pattern", ""));
                case "answerSearch", "abortSearch" -> List.of(toolName + ":finalizing");
                default -> List.of(toolName + ":unknown");
            };
        } catch (Exception e) {
            logger.error("Error creating signature for {}: {}", toolName, e.getMessage());
            return List.of(toolName + ":error");
        }
    }

    private List<String> listParamSignatures(String toolName, Map<String, Object> args, String listParam) {
        @SuppressWarnings("unchecked")
        List<String> items = (List<String>) args.get(listParam);
        if (items != null && !items.isEmpty()) {
            return items.stream().map(i -> toolName + ":" + listParam + "=" + i).toList();
        }
        return List.of(toolName + ":" + listParam + "=empty");
    }

    private void trackClassNamesFromToolCall(ToolExecutionRequest request) {
        try {
            var args = getArgumentsMap(request);
            switch (request.name()) {
                case "getClassSkeletons", "getClassSources", "getRelatedClasses" -> {
                    @SuppressWarnings("unchecked")
                    List<String> cs = (List<String>) args.get("classNames");
                    if (cs != null) trackedClassNames.addAll(cs);
                }
                case "getMethodSources" -> {
                    @SuppressWarnings("unchecked")
                    List<String> ms = (List<String>) args.get("methodNames");
                    if (ms != null) {
                        ms.stream()
                                .map(this::extractClassNameFromMethod)
                                .filter(Objects::nonNull)
                                .forEach(trackedClassNames::add);
                    }
                }
                case "getUsages" -> {
                    @SuppressWarnings("unchecked")
                    var symbols = (List<String>) args.get("symbols");
                    if (symbols != null) {
                        for (var sym : symbols) {
                            var cn = extractClassNameFromSymbol(sym);
                            cn.ifPresent(trackedClassNames::add);
                        }
                    }
                }
                default -> {}
            }
        } catch (Exception e) {
            logger.error("Error tracking class names from tool call", e);
        }
    }

    private void trackClassNamesFromResult(String resultText) throws InterruptedException {
        if (resultText.isBlank() || resultText.startsWith("No ") || resultText.startsWith("Error:")) return;

        // Handle compressed "[Common package prefix: 'x.y'] a, b"
        String effective = resultText;
        String prefix = "";
        if (resultText.startsWith("[") && resultText.contains("] ")) {
            int end = resultText.indexOf("] ");
            int startQuote = resultText.indexOf("'");
            int endQuote = resultText.lastIndexOf("'", end);
            if (end > 0 && startQuote >= 0 && endQuote > startQuote) {
                prefix = resultText.substring(startQuote + 1, endQuote);
            }
            effective = resultText.substring(end + 2).trim();
        }

        String fPrefix = prefix;
        Set<String> potential = new HashSet<>(Arrays.stream(effective.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> fPrefix.isEmpty() ? s : fPrefix + "." + s)
                .filter(s -> s.contains(".") && Character.isJavaIdentifierStart(s.charAt(0)))
                .toList());

        var classMatcher =
                Pattern.compile("(?:Source code of |class )([\\w.$]+)").matcher(resultText);
        while (classMatcher.find()) potential.add(classMatcher.group(1));

        var usageMatcher = Pattern.compile("Usage in ([\\w.$]+)\\.").matcher(resultText);
        while (usageMatcher.find()) potential.add(usageMatcher.group(1));

        if (!potential.isEmpty()) {
            Set<String> valid = new HashSet<>();
            for (String p : potential) {
                var className = extractClassNameFromSymbol(p);
                className.ifPresent(valid::add);
            }
            if (!valid.isEmpty()) {
                trackedClassNames.addAll(valid);
            }
        }
    }

    private @Nullable String extractClassNameFromMethod(String methodName) {
        int lastDot = methodName.lastIndexOf('.');
        if (lastDot > 0) return methodName.substring(0, lastDot);
        return null;
    }

    private Optional<String> extractClassNameFromSymbol(String symbol) throws InterruptedException {
        return cm.getAnalyzer().getDefinition(symbol).flatMap(cu -> cu.classUnit()
                .map(CodeUnit::fqName));
    }

    private static Map<String, Object> getArgumentsMap(ToolExecutionRequest request) {
        try {
            var mapper = new ObjectMapper();
            return mapper.readValue(request.arguments(), new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            logger.error("Error parsing request args for {}: {}", request.name(), e.getMessage());
            return Map.of();
        }
    }
}
