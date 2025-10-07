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
import io.github.jbellis.brokk.mcp.McpUtils;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.prompts.McpPrompts;
import io.github.jbellis.brokk.tools.ToolExecutionResult;
import io.github.jbellis.brokk.tools.ToolRegistry;
import io.github.jbellis.brokk.tools.WorkspaceTools;
import io.github.jbellis.brokk.util.Messages;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final List<McpPrompts.McpTool> mcpTools;

    // Session-local conversation for this agent
    private final List<ChatMessage> sessionMessages = new ArrayList<>();

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

        var mcpConfig = cm.getProject().getMcpConfig();
        List<McpPrompts.McpTool> tools = new ArrayList<>();
        for (var server : mcpConfig.servers()) {
            if (server.tools() != null) {
                for (var toolName : server.tools()) {
                    tools.add(new McpPrompts.McpTool(server, toolName));
                }
            }
        }
        this.mcpTools = List.copyOf(tools);
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
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "Search interrupted; attempting to finalize with available information");
                beastMode = true;
            }
            var inputLimit = cm.getService().getMaxInputTokens(model);
            var workspaceMessages =
                    new ArrayList<>(CodePrompts.instance.getWorkspaceContentsMessages(cm.liveContext()));
            var workspaceTokens = Messages.getApproximateTokens(workspaceMessages);
            if (!beastMode && inputLimit > 0 && workspaceTokens > WORKSPACE_CRITICAL * inputLimit) {
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "Workspace is near the context limit; attempting finalization based on current knowledge");
                beastMode = true;
            }

            // Build prompt and allowed tools
            var messages = buildPrompt(workspaceTokens, inputLimit, workspaceMessages);
            var allowedToolNames = calculateAllowedToolNames();
            var toolSpecs = new ArrayList<>(toolRegistry.getRegisteredTools(allowedToolNames));

            // Agent-owned terminal tools (instance methods)
            var agentTerminalTools = new ArrayList<String>();
            if (allowedTerminals.contains(Terminal.ANSWER)) {
                agentTerminalTools.add("answer");
            }
            if (allowedTerminals.contains(Terminal.WORKSPACE)) {
                agentTerminalTools.add("workspaceComplete");
            }
            // Always allow abort
            agentTerminalTools.add("abortSearch");
            toolSpecs.addAll(toolRegistry.getTools(this, agentTerminalTools));

            // Global terminal tool(s) implemented outside SearchAgent (e.g., in SearchTools)
            if (allowedTerminals.contains(Terminal.TASK_LIST)) {
                toolSpecs.addAll(toolRegistry.getRegisteredTools(List.of("createTaskList")));
            }

            // Decide next action(s)
            io.llmOutput("\n**Brokk** is preparing the next actions…", ChatMessageType.AI, true, false);
            var result = llm.sendRequest(messages, new ToolContext(toolSpecs, ToolChoice.REQUIRED, this), true);
            if (result.error() != null || result.isEmpty()) {
                var details =
                        result.error() != null ? requireNonNull(result.error().getMessage()) : "Empty response";
                io.showNotification(IConsoleIO.NotificationRole.INFO, "LLM error planning next step: " + details);
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
                // If everything got filtered (e.g., only terminal tool kept), force beast mode next turn if needed
                beastMode = true;
                continue;
            }

            // Deferred-final behavior: do not short-circuit for final tools; they will be executed after non-final
            // tools.

            // Execute all tool calls in a deterministic order (Workspace ops before exploration helps pruning)
            var sortedCalls = next.stream()
                    .sorted(Comparator.comparingInt(req -> priority(req.name())))
                    .toList();
            String executedFinalTool = null;
            String executedFinalText = "";
            for (var req : sortedCalls) {
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

                // Track if we executed a final tool; finalize after the loop
                if (req.name().equals("answer")
                        || req.name().equals("createTaskList")
                        || req.name().equals("workspaceComplete")
                        || req.name().equals("abortSearch")) {
                    executedFinalTool = req.name();
                    executedFinalText = display;
                }
            }

            // If we executed a final tool, finalize appropriately
            if (executedFinalTool != null) {
                if (executedFinalTool.equals("abortSearch")) {
                    var explain = executedFinalText.isBlank() ? "No explanation provided by agent." : executedFinalText;
                    return errorResult(new TaskResult.StopDetails(TaskResult.StopReason.LLM_ABORTED, explain));
                } else {
                    return createResult();
                }
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

        // Describe available MCP tools
        var mcpToolPrompt = McpPrompts.mcpToolPrompt(mcpTools);
        if (mcpToolPrompt != null) {
            messages.add(new SystemMessage(mcpToolPrompt));
        }

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
                    """
                    - Use createTaskList(List<String>) when the request involves code changes; produce a clear, minimal, incremental, and testable sequence of tasks that an Architect/Code agent can execute, once you understand where all the necessary pieces live.
                      Guidance:
                        - Each task should be self-contained and verifiable via code review or automated tests.
                        - Prefer adding or updating automated tests to demonstrate behavior; if automation is not a good fit, it is acceptable to omit tests rather than prescribe manual steps.
                        - Keep the project buildable and testable after each step.
                        - The executing agent may adjust task scope/order based on more up-to-date information discovered during implementation.
                    """
                            .stripIndent());
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

                        You can call multiple tools in a single turn. Provide a list of separate tool calls, each with its own name and arguments (add summaries, drop fragments, etc).
                        You may include at most one final action. The final action will be executed after all non-final tools in the same turn. Do NOT write code.


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
        names.add("appendNote");
        names.add("dropWorkspaceFragments");

        if (!mcpTools.isEmpty()) {
            names.add("callMcpTool");
        }

        logger.debug("Allowed tool names: {}", names);
        return names;
    }

    private List<ToolExecutionRequest> parseResponseToRequests(AiMessage response) {
        if (!response.hasToolExecutionRequests()) {
            return List.of();
        }

        // Allow mixed plans: keep all non-terminals; keep only the FIRST final
        ToolExecutionRequest firstFinal = null;
        var nonTerminals = new ArrayList<ToolExecutionRequest>();
        for (var r : response.toolExecutionRequests()) {
            var name = r.name();
            boolean isFinal = name.equals("answer")
                    || name.equals("createTaskList")
                    || name.equals("workspaceComplete")
                    || name.equals("abortSearch");
            if (isFinal) {
                if (firstFinal == null) {
                    firstFinal = r; // take the first final; ignore subsequent finals
                }
                continue;
            }
            nonTerminals.add(r);
        }

        if (firstFinal != null) {
            var combined = new ArrayList<ToolExecutionRequest>(nonTerminals);
            combined.add(firstFinal); // ensure the final runs after non-terminals
            return combined;
        }

        // No final: return all non-terminal tool requests in the order provided
        return nonTerminals;
    }

    private int priority(String toolName) {
        // Prioritize workspace pruning and adding summaries before deeper exploration.
        return switch (toolName) {
            case "dropWorkspaceFragments" -> 1;
            case "addTextToWorkspace", "appendNote" -> 2;
            case "addClassSummariesToWorkspace", "addFileSummariesToWorkspace", "addMethodsToWorkspace" -> 3;
            case "addFilesToWorkspace", "addClassesToWorkspace", "addSymbolUsagesToWorkspace" -> 4;
            case "getRelatedClasses" -> 5;
            case "searchSymbols", "getUsages", "searchSubstrings", "searchFilenames", "searchGitCommitMessages" -> 6;
            case "getClassSkeletons", "getClassSources", "getMethodSources" -> 7;
            case "getCallGraphTo", "getCallGraphFrom", "getFileContents", "getFileSummaries", "getFiles" -> 8;
            case "answer", "createTaskList", "workspaceComplete", "abortSearch" -> 100;
            default -> 9;
        };
    }

    // =======================
    // Initial context seeding
    // =======================

    private void addInitialContextToWorkspace() throws InterruptedException {
        var contextAgent = new ContextAgent(cm, cm.getService().getScanModel(), goal, true);
        io.llmOutput("\n**Brokk Context Engine** analyzing repository context…", ChatMessageType.AI, true, false);

        var recommendation = contextAgent.getRecommendations(true);
        if (!recommendation.reasoning().isEmpty()) {
            io.llmOutput(
                    "\n\nReasoning for contextual insights: " + recommendation.reasoning(), ChatMessageType.CUSTOM);
        }
        if (!recommendation.success() || recommendation.fragments().isEmpty()) {
            io.llmOutput("\n\nNo additional context insights found", ChatMessageType.CUSTOM);
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
            io.llmOutput(
                    "\n\n**Brokk Context Engine** complete — contextual insights added to Workspace.",
                    ChatMessageType.CUSTOM);
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
        io.llmOutput(explanation, ChatMessageType.AI);
        return explanation;
    }

    @Tool("Calls a remote tool using the MCP (Model Context Protocol).")
    public String callMcpTool(
            @P("The name of the tool to call. This must be one of the configured MCP tools.") String toolName,
            @P("A map of argument names to values for the tool. Can be null or empty if the tool takes no arguments.")
                    @Nullable
                    Map<String, Object> arguments) {
        Map<String, Object> args = java.util.Objects.requireNonNullElseGet(arguments, HashMap::new);
        var mcpToolOptional =
                mcpTools.stream().filter(t -> t.toolName().equals(toolName)).findFirst();

        if (mcpToolOptional.isEmpty()) {
            var err = "Error: MCP tool '" + toolName + "' not found in configuration.";
            if (toolName.contains("(") || toolName.contains("{")) {
                err = err
                        + " Possible arguments found in the tool name. Hint: The first argument, 'toolName', is the tool name only. Any arguments must be defined as a map in the second argument, named 'arguments'.";
            }
            logger.warn(err);
            return err;
        }

        var server = mcpToolOptional.get().server();
        try {
            var projectRoot = this.cm.getProject().getRoot();
            var result = McpUtils.callTool(server, toolName, args, projectRoot);
            var preamble = McpPrompts.mcpToolPreamble();
            var msg = preamble + "\n\n" + "MCP tool '" + toolName + "' output:\n" + result;
            logger.info("MCP tool '{}' executed successfully via server '{}'", toolName, server.name());
            return msg;
        } catch (IOException | RuntimeException e) {
            var err = "Error calling MCP tool '" + toolName + "': " + e.getMessage();
            logger.error(err, e);
            return err;
        }
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

    private String summarizeResult(
            String query, ToolExecutionRequest request, String rawResult, @Nullable String reasoning)
            throws InterruptedException {
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
        Llm.StreamingResult sr = llm.sendRequest(List.of(sys, user));
        if (sr.error() != null) {
            return rawResult; // fallback to raw
        }
        return sr.text();
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
