package io.github.jbellis.brokk.agents;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.Llm;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.tools.ToolExecutionResult;
import io.github.jbellis.brokk.tools.ToolRegistry;
import io.github.jbellis.brokk.tools.ToolRegistry.SignatureUnit;
import io.github.jbellis.brokk.tools.WorkspaceTools;
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
import org.jetbrains.annotations.NotNull;
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

    public enum Terminal {
        TASK_LIST,
        ANSWER,
        WORKSPACE
    }

    // Keep thresholds consistent with other agents
    private static final int SUMMARIZE_THRESHOLD = 1_000; // ~120 LOC equivalent
    private static final double WORKSPACE_CRITICAL = 0.80; // 90% of input limit

    private final ContextManager cm;
    private final StreamingChatModel model;
    private final Llm llm;
    private final ToolRegistry toolRegistry;
    private final IConsoleIO io;
    private final String goal;
    private final Set<Terminal> allowedTerminals;

    // Session-local conversation for this agent
    private final List<ChatMessage> sessionMessages = new ArrayList<>();

    // Duplicate detection and linkage discovery
    private final Set<SignatureUnit> toolCallSignatures = new HashSet<>();
    private final Set<String> trackedClassNames = new HashSet<>();

    // State toggles
    private boolean beastMode;

    public SearchAgent(
            String goal, ContextManager contextManager, StreamingChatModel model, Set<Terminal> allowedTerminals) {
        this.goal = goal;
        this.cm = contextManager;
        this.model = model;
        this.toolRegistry = contextManager.getToolRegistry();

        this.io = contextManager.getIo();
        this.llm = contextManager.getLlm(model, "Search: " + goal);
        this.llm.setOutput(io);

        this.beastMode = false;
        this.allowedTerminals = Set.copyOf(allowedTerminals);
    }

    /** Entry point. Runs until answer/abort or interruption. */
    public TaskResult execute() {
        try {
            return executeInternal();
        } catch (InterruptedException e) {
            logger.debug("Search interrupted", e);
            return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED));
        }
    }

    private @NotNull TaskResult executeInternal() throws InterruptedException {
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

            var terminalToolNames = new ArrayList<String>();
            if (allowedTerminals.contains(Terminal.ANSWER)) {
                terminalToolNames.add("answer");
            }
            if (allowedTerminals.contains(Terminal.TASK_LIST)) {
                terminalToolNames.add("createTaskList");
            }
            if (allowedTerminals.contains(Terminal.WORKSPACE)) {
                terminalToolNames.add("workspaceComplete");
            }
            // Always allow abort
            terminalToolNames.add("abortSearch");
            toolSpecs.addAll(toolRegistry.getTools(this, terminalToolNames));

            // Decide next action(s)
            io.llmOutput("\n# Planning", ChatMessageType.AI, true, false);
            var result = llm.sendRequest(messages, new ToolContext(toolSpecs, ToolChoice.REQUIRED, this), true);
            if (result.error() != null || result.isEmpty()) {
                var details =
                        result.error() != null ? requireNonNull(result.error().getMessage()) : "Empty response";
                io.systemOutput("LLM error planning next step: " + details);
                return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, details));
            }

            // Record turn
            sessionMessages.add(new UserMessage("What tools do you want to use next?"));
            sessionMessages.add(result.aiMessage());

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

            // If the first is an immediate finalizing action (answer/abort), execute it alone and finalize
            var first = next.getFirst();
            if (first.name().equals("answer") || first.name().equals("abortSearch")) {
                // Enforce singularity
                if (next.size() > 1) {
                    io.systemOutput("Final action returned with other tools; ignoring others and finalizing.");
                }
                var exec = toolRegistry.executeTool(this, first);
                sessionMessages.add(ToolExecutionResultMessage.from(first, exec.resultText()));
                if (first.name().equals("answer")) {
                    return createResult();
                } else {
                    var explain = exec.resultText().isBlank() ? "No explanation provided by agent." : exec.resultText();
                    return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.LLM_ABORTED, explain));
                }
            }

            // Execute all tool calls in a deterministic order (Workspace ops before exploration helps pruning)
            var sortedCalls = next.stream()
                    .sorted(Comparator.comparingInt(req -> priority(req.name())))
                    .toList();
            boolean executedDeferredTerminal = false;
            for (var req : sortedCalls) {
                // Duplicate guard and class tracking before execution
                var signatures = createToolCallSignatures(req);
                toolCallSignatures.addAll(signatures);
                trackClassNamesFromToolCall(req);

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

                // Light composition: update discovery and flow
                handleStateAfterTool(exec);

                // Track if we executed a deferred terminal
                if (req.name().equals("createTaskList") || req.name().equals("workspaceComplete")) {
                    executedDeferredTerminal = true;
                }
            }

            // If we executed a deferred terminal, finalize
            if (executedDeferredTerminal) {
                return createResult();
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
                          - aggressively curate the Workspace so a Code Agent has all the needed resources to implement next without confusion,
                          - never write code yourself.

                        Critical rules:
                          1) PRUNE FIRST at every turn.
                             - Remove fragments that are not directly useful for the goal.
                             - Prefer concise, goal-focused summaries over full files.
                             - When you pull information from a long fragment, first add your extraction, then drop the original.
                             - Keep the Workspace focused on answering/solving the goal.
                          2) Use search and inspection tools to discover relevant code, including classes/methods/usages/call graphs.
                          3) The symbol-based tools only have visibility into the following file types: %s
                             Use text-based tools if you need to search other file types.
                          4) Group related lookups into a single call when possible.
                          5) Make multiple tool calls at once when searching for different types of code.

                        Output discipline:
                          - Start each turn by pruning and summarizing before any new exploration.
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

        var finals = new ArrayList<String>();
        if (allowedTerminals.contains(Terminal.ANSWER)) {
            finals.add(
                    "- Use answer(String) when the request is purely informational and you have enough information to answer.");
        }
        if (allowedTerminals.contains(Terminal.TASK_LIST)) {
            finals.add(
                    "- Use createTaskList(List<String>) when the request involves code changes; produce a clear, minimal, incremental, and testable sequence of tasks that an Architect/Code agent can execute, once you understand where all the necessary pieces live.");
        }
        if (allowedTerminals.contains(Terminal.WORKSPACE)) {
            finals.add(
                    "- Use workspaceComplete() when the Workspace contains all the information necessary to accomplish the goal.");
        }
        finals.add(
                "- If we cannot find the answer or the request is out of scope for this codebase, use abortSearch with a clear explanation.");

        String finalsStr = finals.stream().collect(Collectors.joining("\n"));

        String directive =
                """
                        <goal>
                        %s
                        </goal>

                        Decide the next tool action(s) to make progress toward answering the question and preparing the Workspace
                        for follow-on code changes.

                        Pruning mandate:
                          - Before any new exploration, prune the Workspace.
                          - Replace full text with concise, goal-focused summaries and drop the originals.
                          - Expand the Workspace only after pruning; avoid re-adding irrelevant content.

                        Finalization options:
                        %s

                        You can call multiple tools in a single turn. To do so, provide a list of separate tool calls, each with its own name and arguments (add summaries, drop fragments, etc).
                        Do NOT invoke multiple final actions. Do NOT write code.

                        Task list guidance:
                          - Each task should be self-contained, verifiable, and as small as practical.
                          - Prefer a sequence that keeps the project buildable and testable after each step.
                          - Include writing tests, where appropriate.

                        %s
                        """
                        .stripIndent()
                        .formatted(goal, finalsStr, warning);

        // Beast mode directive
        if (beastMode) {
            directive = directive
                    + """
                    <beast-mode>
                    The Workspace is full or execution was interrupted.
                    Finalize now using the best available information.
                    Prefer answer(String) for informational requests; for code-change requests, provide a concise createTaskList(List<String>) if feasible; otherwise use abortSearch with reasons.
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

        // Any Analyzer at all provides these
        if (!cm.getProject().getAnalyzerLanguages().equals(Set.of(Languages.NONE))) {
            names.add("searchSymbols");
            names.add("getFiles");
        }

        // Fine-grained Analyzer capabilities
        var analyzerWrapper = cm.getAnalyzerWrapper();
        if (analyzerWrapper.providesSummaries()) {
            names.add("getClassSkeletons");
        }
        if (analyzerWrapper.providesSourceCode()) {
            names.add("getClassSources");
            names.add("getMethodSources");
        }
        if (analyzerWrapper.providesInterproceduralAnalysis()) {
            names.add("getUsages");
            names.add("getRelatedClasses");
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
        names.add("addMethodsToWorkspace");
        names.add("addFileSummariesToWorkspace");
        names.add("addSymbolUsagesToWorkspace");
        names.add("addCallGraphInToWorkspace");
        names.add("addCallGraphOutToWorkspace");
        names.add("addTextToWorkspace");
        names.add("dropWorkspaceFragments");

        logger.debug("Allowed tool names: {}", names);
        return names;
    }

    private List<ToolExecutionRequest> parseResponseToRequests(AiMessage response) {
        if (!response.hasToolExecutionRequests()) {
            return List.of();
        }

        // 1) Isolate terminator tools BEFORE running any dedupe logic.
        //    Terminal tools like answer/createTaskList/workspaceComplete/abortSearch do not have a list parameter
        //    and must not be deduplicated; doing so can throw during signature extraction.
        var firstFinal = response.toolExecutionRequests().stream()
                .filter(r -> r.name().equals("answer")
                        || r.name().equals("createTaskList")
                        || r.name().equals("workspaceComplete")
                        || r.name().equals("abortSearch"))
                .findFirst();
        if (firstFinal.isPresent()) {
            return List.of(firstFinal.get());
        }

        // 2) Otherwise, dedupe non-terminator tool requests and forge getRelatedClasses for duplicates.
        var raw = response.toolExecutionRequests().stream()
                .map(this::handleDuplicateRequestIfNeeded)
                .toList();

        return raw;
    }

    private int priority(String toolName) {
        // Prioritize workspace pruning and adding summaries before deeper exploration.
        return switch (toolName) {
            case "dropWorkspaceFragments" -> 1;
            case "addTextToWorkspace" -> 2;
            case "addClassSummariesToWorkspace", "addFileSummariesToWorkspace", "addMethodsToWorkspace" -> 3;
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
        var contextAgent = new ContextAgent(cm, cm.getService().getScanModel(), goal, true);
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
            cm.addVirtualFragment(new ContextFragment.StringFragment(
                    cm,
                    summaries,
                    "Summary of Scan Results",
                    recommendation.fragments().stream()
                            .findFirst()
                            .orElseThrow()
                            .syntaxStyle()));
        } else {
            WorkspaceTools.addToWorkspace(cm, recommendation);
            io.llmOutput("\n\nScan complete; added recommendations to the Workspace.", ChatMessageType.CUSTOM);
        }
    }

    // =======================
    // Answer/abort tools
    // =======================

    @Tool("Provide a final answer to a purely informational request. Use this when no code changes are required.")
    public String answer(
            @P(
                            "Comprehensive explanation that answers the query. Include relevant code snippets and how they relate, formatted in Markdown.")
                    String explanation) {
        io.llmOutput("# Answer\n\n" + explanation, ChatMessageType.AI);
        return explanation;
    }

    @Tool(
            "Signal that the Workspace now contains all the information necessary to accomplish the goal. Call this when you have finished gathering and pruning context.")
    public String workspaceComplete() {
        logger.debug("workspaceComplete selected");
        return "Workspace marked complete for the current goal.";
    }

    @Tool("Abort when you determine the question is not answerable from this codebase or is out of scope.")
    public String abortSearch(
            @P("Clear explanation of why the question cannot be answered from this codebase.") String explanation) {
        logger.debug("abortSearch selected with explanation length {}", explanation.length());
        return explanation;
    }

    // =======================
    // Finalization and errors
    // =======================

    private TaskResult createResult() {
        // Build final messages from already-streamed transcript; fallback to session-local messages if empty
        List<ChatMessage> finalMessages = new ArrayList<>(io.getLlmRawMessages());
        if (finalMessages.isEmpty()) {
            finalMessages = new ArrayList<>(sessionMessages);
        }

        String action = "Search: " + goal;

        var stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
        var fragment = new ContextFragment.TaskFragment(cm, finalMessages, goal);

        return new TaskResult(action, fragment, Set.of(), stopDetails);
    }

    private TaskResult errorResult(TaskResult.StopDetails details) {
        // Build final messages from already-streamed transcript; fallback to session-local messages if empty
        List<ChatMessage> finalMessages = new ArrayList<>(io.getLlmRawMessages());
        if (finalMessages.isEmpty()) {
            finalMessages = new ArrayList<>(sessionMessages);
        }

        String action = "Search: " + goal + " [" + details.reason().name() + "]";
        var fragment = new ContextFragment.TaskFragment(cm, finalMessages, goal);

        return new TaskResult(action, fragment, Set.of(), details);
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
        if (sr.error() != null) {
            return rawResult; // fallback to raw
        }
        return sr.text();
    }

    // =======================
    // Duplicate forging & tracking
    // =======================

    private ToolExecutionRequest handleDuplicateRequestIfNeeded(ToolExecutionRequest request) {
        if (!cm.getAnalyzerWrapper().providesInterproceduralAnalysis()) {
            return request;
        }

        // Workspace mutation tools are never deduplicated
        if (toolRegistry.isWorkspaceMutationTool(request.name())) {
            return request;
        }

        var requestUnits = createToolCallSignatures(request);
        if (requestUnits.isEmpty()) {
            // Could not determine units; conservatively allow execution
            return request;
        }

        var newUnits = requestUnits.stream()
                .filter(u -> !toolCallSignatures.contains(u))
                .toList();

        if (newUnits.isEmpty()) {
            // Nothing new in this request: treat as duplicate and consider forging getRelatedClasses
            logger.debug("Duplicate call detected for {}; forging getRelatedClasses", request.name());
            request = createRelatedClassesRequest();
            var forgedUnits = createToolCallSignatures(request);
            if (toolCallSignatures.containsAll(forgedUnits)) {
                logger.debug("Forged getRelatedClasses would also be duplicate; switching to beast mode");
                beastMode = true;
            }
            return request;
        }

        // Some items are new: rewrite the request to contain only the new items (avoid losing new work)
        if (newUnits.size() < requestUnits.size()) {
            var rewritten = toolRegistry.buildRequestFromUnits(request, newUnits);
            return rewritten;
        }

        // All units are new: execute original request
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

    private List<SignatureUnit> createToolCallSignatures(ToolExecutionRequest request) {
        // Delegate to ToolRegistry which has validation/typing knowledge.
        try {
            return toolRegistry.signatureUnits(this, request);
        } catch (Exception e) {
            logger.error("Error creating signature units for {}: {}", request.name(), e.getMessage(), e);
            return List.of();
        }
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
