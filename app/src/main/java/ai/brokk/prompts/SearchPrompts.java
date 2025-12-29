package ai.brokk.prompts;

import ai.brokk.agents.BuildAgent;
import ai.brokk.agents.SearchAgent;
import ai.brokk.analyzer.Language;
import ai.brokk.context.Context;
import ai.brokk.context.SpecialTextType;
import ai.brokk.util.Messages;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates prompts for the Search Agent and Ask requests.
 */
public class SearchPrompts {
    public static final SearchPrompts instance = new SearchPrompts();

    private static final double WORKSPACE_CRITICAL = 0.80;

    /**
     * Result of building a prompt, including messages and whether beast mode should be engaged.
     */
    public record PromptResult(List<ChatMessage> messages, boolean engageBeastMode) {}

    public String searchAgentIdentity() {
        return """
                You are the Search Agent.
                Your job is to be the **Code Agent's preparer**. You are a researcher and librarian, not a developer.
                  Your responsibilities are:
                    1.  **Find & Discover:** Use search and inspection tools to locate all relevant files, classes, and methods.
                    2.  **Curate & Prepare:** Aggressively prune the Workspace to leave *only* the essential context (files, summaries, notes) that the Code Agent will need.
                    3.  **Handoff:** Your final output is a clean workspace ready for the Code Agent to begin implementation.

                  Remember: **You must never write, create, or modify code.**
                  Your purpose is to *find* existing code, not *create* new code.
                  The Code Agent is solely responsible for all code generation and modification.
                """;
    }

    public final List<ChatMessage> buildAskPrompt(Context ctx, String input) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(new SystemMessage(
                "Act as an expert software developer when answering the user's question based on the code in the Workspace.\n\n"
                        + SystemPrompts.MARKDOWN_REMINDER));
        messages.addAll(WorkspacePrompts.getMessagesInAddedOrder(ctx, EnumSet.of(SpecialTextType.TASK_LIST)));
        messages.addAll(CodePrompts.instance.getHistoryMessages(ctx));
        messages.add(askRequest(input));
        return messages;
    }

    public UserMessage askRequest(String input) {
        var text =
                """
                <instructions>
                Answer this question about the supplied code thoroughly and accurately.

                Provide insights, explanations, and analysis; do not implement changes.
                While you can suggest high-level approaches and architectural improvements, remember that:
                - You should focus on understanding and clarifying the code
                - The user will make other requests when he wants to actually implement changes
                - You are being asked here for conceptual understanding and problem diagnosis

                Be concise but complete in your explanations. If you need more information to answer a question,
                don't hesitate to ask for clarification. If you notice references to code in the Workspace that
                you need to see to answer accurately, do your best to take educated guesses but clarify that
                it IS an educated guess and ask the user to add the relevant code.

                Format your answer with Markdown for readability. It's particularly important to signal
                changes in subject with appropriate headings.
                </instructions>

                <question>
                %s
                </question>
                """
                        .formatted(input);
        return new UserMessage(text);
    }

    public SystemMessage searchSystemPrompt(Context context) {
        var supportedTypes = context.getContextManager().getProject().getAnalyzerLanguages().stream()
                .map(Language::name)
                .collect(Collectors.joining(", "));

        return new SystemMessage(
                """
                <instructions>
                %s

                Critical rules:
                  1) PRUNE FIRST at every turn.
                     - Remove fragments that are not directly useful for the goal (add a reason).
                     - Prefer concise, goal-focused summaries over full files when possible.
                     - When you pull information from a long fragment, first add your extraction/summary, then drop the original from workspace.
                     - Keep the Workspace focused on answering/solving the goal.
                  2) Use search and inspection tools to discover relevant code, including classes/methods/usages/call graphs.
                  3) The symbol-based tools only have visibility into the following file types: %s
                     Use text-based tools if you need to search other file types.
                  4) Group related lookups into a single tool call when possible.
                  5) Make multiple tool calls at once when searching for different types of code.
                  6) Your responsibility ends at providing context.
                     Do not attempt to write the solution or pseudocode for the solution.
                     Your job is to *gather* the materials; the Code Agent's job is to *use* them.
                     Where code changes are needed, add the *target files* to the workspace using `addFilesToWorkspace`
                     and let the Code Agent write the code. (But when refactoring, it is usually sufficient to call `addSymbolUsagesToWorkspace`
                     and let Code Agent edit those fragments directly, instead of adding each call site's entire file.)
                     Note: Code Agent will also take care of creating new files, you only need to add existing files
                     to the Workspace.

                Output discipline:
                  - Start each turn by pruning and summarizing before any new exploration.
                  - Think before calling tools.
                  - If you already know what to add, use Workspace tools directly; do not search redundantly.
                </instructions>
                """
                        .formatted(searchAgentIdentity(), supportedTypes));
    }

    /**
     * Builds the pruning prompt for the Janitor (Workspace Reviewer) logic.
     */
    public List<ChatMessage> buildPruningPrompt(Context context, String goal) {
        var messages = new ArrayList<ChatMessage>();

        var sys = new SystemMessage(
                """
                <instructions>
                You are the Janitor Agent (Workspace Reviewer). Single-shot cleanup: one response, then done.

                Scope:
                - Workspace curation ONLY. No code, no answers, no plans.

                Tools (call exactly one):
                - performedInitialReview(): use ONLY when ALL fragments are short, focused, clean, and directly relevant.
                - dropWorkspaceFragments(fragments: {fragmentId, explanation}[]): batch ALL drops in a single call.

                Default behavior:
                - If a fragment is large, noisy, or mixed → write a short summary in the drop explanation → DROP it.
                  Large/noisy/mixed = long, multi-file, logs/traces/issues, big diffs, UI/test noise, unfocused content.

                Keep rule:
                - KEEP only if it is short, focused, directly relevant, AND keeping it is clearer than summarizing (i.e. to much information loss on summary).

                fragment.explanation (string) format:
                - Summary: information needed to solve the goal (e.g. descriptions, file paths, class names, method names, code snippets, stack traces)
                - Reason: one short sentence why dropped.
                - No implementation instructions.

                Response rule:
                - Tool call only; return exactly ONE tool call (performedInitialReview OR a single batched dropWorkspaceFragments).
                </instructions>
                """);
        messages.add(sys);

        // Current Workspace contents (use default viewing policy)
        var suppressed = EnumSet.of(SpecialTextType.TASK_LIST);
        messages.addAll(WorkspacePrompts.getMessagesInAddedOrder(context, suppressed));

        // Goal and project context
        messages.add(new UserMessage(
                """
                <goal>
                %s
                </goal>

                Review the Workspace above. Use the dropWorkspaceFragments tool to remove ALL fragments that are not directly useful for accomplishing the goal.
                If the workspace is already well-curated, you're done!
                """
                        .formatted(goal)));

        return messages;
    }

    /**
     * Builds the prompt messages for SearchAgent and determines if beast mode should be engaged.
     *
     * @param context the current context
     * @param model the model to use for token limit calculation
     * @param goal the search goal
     * @param objective the search objective
     * @param mcpTools the list of MCP tools available
     * @param sessionMessages the session-local conversation messages
     * @return PromptResult containing the messages and whether beast mode should now be engaged
     */
    public PromptResult buildPrompt(
            Context context,
            StreamingChatModel model,
            String goal,
            SearchAgent.Objective objective,
            List<McpPrompts.McpTool> mcpTools,
            List<ChatMessage> sessionMessages)
            throws InterruptedException {

        var cm = context.getContextManager();
        var inputLimit = cm.getService().getMaxInputTokens(model);

        // Determine viewing policy based on search objective
        boolean useTaskList = objective == SearchAgent.Objective.LUTZ || objective == SearchAgent.Objective.TASKS_ONLY;
        var suppressed = useTaskList ? EnumSet.noneOf(SpecialTextType.class) : EnumSet.of(SpecialTextType.TASK_LIST);

        // Build workspace messages in insertion order with viewing policy applied
        var workspaceMessages = WorkspacePrompts.getMessagesInAddedOrder(context, suppressed);
        var workspaceTokens = Messages.getApproximateMessageTokens(workspaceMessages);

        // Determine if beast mode should be engaged
        boolean engageBeastMode = (inputLimit > 0 && workspaceTokens > WORKSPACE_CRITICAL * inputLimit);

        var messages = new ArrayList<ChatMessage>();

        messages.add(searchSystemPrompt(context));

        // Describe available MCP tools
        var mcpToolPrompt = McpPrompts.mcpToolPrompt(mcpTools);
        if (mcpToolPrompt != null) {
            messages.add(new SystemMessage(mcpToolPrompt));
        }

        // Current Workspace contents (apply viewing policy for visibility filtering)
        messages.addAll(workspaceMessages);

        // Conversation history plus this agent's messages
        messages.addAll(cm.getHistoryMessages());
        messages.addAll(sessionMessages);

        // Related identifiers from nearby files (Discovery suggestions after history)
        var related = context.buildRelatedIdentifiers(10);
        if (!related.isEmpty()) {
            var relatedBlock = ArchitectPrompts.formatRelatedFiles(related);
            messages.add(new UserMessage(
                    """
                    <related_files>
                    These files (with the identifiers they declare) MAY be relevant. They are NOT in the Workspace yet.
                    Add summaries or sources if needed; otherwise ignore them.

                    %s
                    </related_files>
                    """
                            .formatted(relatedBlock)));
            messages.add(new AiMessage("Acknowledged. I will explicitly add only what is relevant."));
        }

        // Workspace size warning and final instruction
        String warning = "";
        if (inputLimit > 0) {
            double pct = (double) workspaceTokens / inputLimit * 100.0;
            if (pct > 90.0) {
                warning =
                        """
                                CRITICAL: Workspace is using %.0f%% of input budget (%d tokens of %d).
                                You MUST reduce Workspace size immediately before any further exploration.
                                Replace full text with summaries and drop non-essential fragments first.
                                """
                                .formatted(pct, workspaceTokens, inputLimit);
            } else if (pct > 60.0) {
                warning =
                        """
                                NOTICE: Workspace is using %.0f%% of input budget (%d tokens of %d).
                                Prefer summaries and prune aggressively before expanding further.
                                """
                                .formatted(pct, workspaceTokens, inputLimit);
            }
        }

        var allowedTerminals = objective.terminals();
        var finals = new ArrayList<String>();
        if (allowedTerminals.contains(Terminal.ANSWER)) {
            finals.add(
                    "- Use answer(String) when the request is purely informational and you have enough information to answer. The answer needs to be Markdown-formatted (see <persistence>).");
            finals.add(
                    "- Use askForClarification(String queryForUser) when the goal is unclear or you cannot find the necessary information; this will ask the user directly and stop.");
        }
        if (allowedTerminals.contains(Terminal.TASK_LIST)) {
            finals.add(
                    """
                    - Use createOrReplaceTaskList(String explanation, List<String> tasks) to replace the entire task list when the request involves code changes. Titles are summarized automatically from task text; pass task texts only. Completed tasks from the previous list are implicitly dropped. Produce a clear, minimal, incremental, and testable sequence of tasks that an Architect/Code agent can execute, once you understand where all the necessary pieces live.
                      Guidance:
                        - Each task should be self-contained and verifiable via code review or automated tests.
                        - Prefer adding or updating automated tests to demonstrate behavior; if automation is not a good fit, it is acceptable to omit tests rather than prescribe manual steps.
                        - Keep the project buildable and testable after each step.
                        - The executing agent may adjust task scope/order based on more up-to-date information discovered during implementation.
                        - Each task needs to be Markdown-formatted, use `inline code` (for file, directory, function, class names and other symbols).
                    """);
        }
        if (allowedTerminals.contains(Terminal.WORKSPACE)) {
            finals.add(
                    "- Use workspaceComplete() when the Workspace contains all the information necessary to accomplish the goal.");
        }
        if (allowedTerminals.contains(Terminal.CODE)) {
            finals.add(
                    "- Use callCodeAgent(String instructions, boolean deferBuild) to attempt implementation now in a single shot. If it succeeds, we finish; otherwise, continue with search/planning. Only use this when the goal is small enough to not need decomposition into a task list, and after you have added all the necessary context to the Workspace.");
        }
        finals.add(
                "- If we cannot find the answer or the request is out of scope for this codebase, use abortSearch with a clear explanation.");

        String finalsStr = String.join("\n", finals);

        String testsGuidance = "";
        if (allowedTerminals.contains(Terminal.WORKSPACE)) {
            var toolHint =
                    "- To locate tests, prefer getUsages to find tests referencing relevant classes and methods.";
            testsGuidance =
                    """
                    Tests:
                      - Code Agent will run the tests in the Workspace to validate its changes.
                        These can be full files (if it also needs to edit or understand test implementation details),
                        or simple summaries if they just need to be run for validation.
                      %s
                    """
                            .formatted(toolHint);
        }

        var terminalObjective = buildTerminalObjective(objective);

        String emptyProjectGuidance = "";
        if (cm.getProject().isEmptyProject()) {
            emptyProjectGuidance =
                    """
                    <empty-project-notice>
                    The project appears to be empty or uninitialized (few or no source files).
                    Adapt your approach:
                      - Prefer searching the repository structure first (e.g., `skimDirectory`, `searchFilenames`) to confirm what exists.
                      - If the user's request requires new code, your role is still to prepare context and produce tasks, not to write code.
                      - For code-change requests, prefer producing a task list that starts with creating the minimal project skeleton and build/test setup.
                    </empty-project-notice>
                    """;
        }

        String buildSetupTaskGuidance = "";
        boolean tasksObjective =
                objective == SearchAgent.Objective.LUTZ || objective == SearchAgent.Objective.TASKS_ONLY;
        if (tasksObjective && cm.getProject().loadBuildDetails().equals(BuildAgent.BuildDetails.EMPTY)) {
            buildSetupTaskGuidance =
                    """
                    <build-setup-task-guidance>
                    If you produce a task list, the FIRST task MUST configure the build and test stack (and any required environment variables)
                    so that subsequent tasks can run `build/lint` and tests.
                    </build-setup-task-guidance>
                    """;
        }

        String directive =
                """
                        <%s>
                        %s
                        </%s>

                        <search-objective>
                        %s
                        </search-objective>

                        %s
                        %s

                        Decide the next tool action(s) to make progress toward the objective in service of the goal.

                        Pruning mandate:
                          - Before any new exploration, prune the Workspace.
                          - Replace full text with concise, goal-focused summaries and drop the originals.
                          - Expand the Workspace only after pruning; avoid re-adding irrelevant content.

                        %s

                        Finalization options:
                        %s

                        You can call multiple non-final tools in a single turn. Provide a list of separate tool calls,
                        each with its own name and arguments (add summaries, drop fragments, etc).
                        Final actions (answer, createOrReplaceTaskList, workspaceComplete, abortSearch) must be the ONLY tool in a turn.
                        If you include a final together with other tools, the final will be ignored for this turn.
                        It is NOT your objective to write code.

                        %s

                        %s

                        Reminder: here is a list of the full contents of the Workspace that you can refer to above:
                        %s
                        """
                        .formatted(
                                terminalObjective.type(),
                                goal,
                                terminalObjective.type(),
                                terminalObjective.text(),
                                emptyProjectGuidance,
                                buildSetupTaskGuidance,
                                testsGuidance,
                                finalsStr,
                                warning,
                                SystemPrompts.MARKDOWN_REMINDER,
                                WorkspacePrompts.formatToc(context, suppressed));

        // Beast mode directive
        if (engageBeastMode) {
            directive = directive
                    + """
                    <beast-mode>
                    The Workspace is full or execution was interrupted.
                    Finalize now using the best available information.
                    Prefer answer(String) when no code changes are needed.
                    For code-change requests, use createOrReplaceTaskList(String explanation, List<String> tasks) to replace the entire list (completed tasks will be dropped). Titles are summarized automatically from task text; pass task texts only. Otherwise use abortSearch with reasons.
                    </beast-mode>
                    """;
        }

        messages.add(new UserMessage(directive));
        return new PromptResult(messages, engageBeastMode);
    }

    public enum Terminal {
        TASK_LIST,
        ANSWER,
        WORKSPACE,
        CODE
    }

    private record TerminalObjective(String type, String text) {}

    private TerminalObjective buildTerminalObjective(SearchAgent.Objective objective) {
        return switch (objective) {
            case ANSWER_ONLY ->
                new TerminalObjective(
                        "query",
                        """
                    Deliver a written answer using the answer(String) tool.
                    """);
            case TASKS_ONLY ->
                new TerminalObjective(
                        "instructions",
                        """
                    Deliver a task list using the createOrReplaceTaskList(String explanation, List<String> tasks) tool.
                    """);
            case WORKSPACE_ONLY ->
                new TerminalObjective(
                        "task",
                        """
                    Deliver a curated Workspace containing everything required for the follow-on Code Agent
                    to solve the given task.
                    """);
            case LUTZ ->
                new TerminalObjective(
                        "query_or_instructions",
                        """
                    Either deliver a written answer, solve the problem by invoking Code Agent, or decompose the problem into a task list.
                    In all cases, find and add appropriate source context to the Workspace so that you do not have to guess. Then,
                      - Prefer answer(String) when no code changes are needed.
                      - Prefer callCodeAgent(String) if the requested change is small.
                      - Otherwise, decompose the problem with createOrReplaceTaskList(String explanation, List<String> tasks); do not attempt to write code yet.
                    """);
        };
    }
}
