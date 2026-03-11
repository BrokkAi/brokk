package ai.brokk.prompts;

import ai.brokk.TaskResult;
import ai.brokk.agents.BuildAgent;
import ai.brokk.agents.LutzAgent;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.SpecialTextType;
import ai.brokk.util.Messages;
import com.github.jknack.handlebars.EscapingStrategy;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.helper.ConditionalHelpers;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

/**
 * Generates prompts for the Search Agent and Ask requests.
 */
public class SearchPrompts {
    public static final SearchPrompts instance = new SearchPrompts();

    public enum Objective {
        ANSWER_ONLY(
                "query",
                "You are the Search Agent, a code researcher focused on answering questions about this codebase.",
                "Your goal is to gather enough context to answer the user's question accurately and cite evidence from the repo.",
                "a comprehensive Markdown answer (via answer(String))",
                "",
                false) {
            @Override
            public Set<Terminal> terminals() {
                return EnumSet.of(Terminal.ANSWER);
            }
        },
        TASKS_ONLY(
                "instructions",
                "You are the Search Agent, a code researcher focused on turning goals into implementation tasks.",
                "Your goal is to gather enough context to produce a clear, minimal, incremental task list for the Code Agent.",
                "a task list for the Code Agent (via createOrReplaceTaskList(...))",
                "",
                true) {
            @Override
            public Set<Terminal> terminals() {
                return EnumSet.of(Terminal.TASK_LIST);
            }
        },
        LUTZ(
                "query_or_instructions",
                "You are the Search Agent, a code researcher that can answer, plan, or hand off implementation.",
                "Your goal is to gather enough context to either answer the question, produce a task list, or invoke the Code Agent for a small change.",
                "one of: answer, task list, or Code Agent invocation",
                """
                - Prefer answer(String) when no code changes are needed and the Workspace already justifies the answer (or the question is codebase-independent).
                - Prefer callCodeAgent(String instructions, boolean deferBuild) if the requested change is small.
                - Otherwise, decompose the problem with createOrReplaceTaskList(String explanation, List<TaskListEntry> tasks); do not attempt to write code yet.
                """,
                true) {
            @Override
            public Set<Terminal> terminals() {
                return EnumSet.of(Terminal.ANSWER, Terminal.CODE, Terminal.TASK_LIST);
            }
        },
        WORKSPACE_ONLY(
                "task",
                "You are the Search Agent, a code researcher and librarian.",
                "Your goal is to prepare the Workspace for the Code Agent by finding and curating the minimum sufficient context.",
                "a curated Workspace ready for the Code Agent",
                "",
                true) {
            @Override
            public Set<Terminal> terminals() {
                return EnumSet.of(Terminal.WORKSPACE);
            }
        },
        ISSUE_DESCRIPTION(
                "problem_report",
                "You are the Search Agent, a code researcher focused on describing issues with precision.",
                "Your goal is to gather enough context to describe the issue and produce a formal issue report with evidence from the repo.",
                "a high-quality GitHub issue (via describeIssue(String, String))",
                """
                Deliver a high-quality GitHub issue using the describeIssue(String title, String body) tool.

                Requirements:
                  - "title": concise, specific issue title.
                  - "body": GitHub-flavored Markdown describing the problem and impact.
                    It MUST include evidence/references to code, such as:
                      - file paths
                      - identifiers/symbol names
                      - fragment ids when available
                    It MAY include a section like "## Agent Instructions" inside the body as well.
                """,
                false) {
            @Override
            public Set<Terminal> terminals() {
                return EnumSet.of(Terminal.DESCRIBE_ISSUE);
            }
        },
        CODE_ONLY(
                "task",
                "You are the Search Agent, a code researcher.",
                "Your goal is to gather enough context for the Code Agent to implement the requested change.",
                "a curated Workspace ready for the Code Agent",
                "",
                true) {
            @Override
            public Set<Terminal> terminals() {
                return EnumSet.of(Terminal.CODE);
            }
        };

        private final String tag;
        private final String identity;
        private final String mission;
        private final String deliverable;
        private final String taskInstructions;
        private final boolean includeHandoff;

        Objective(
                String tag,
                String identity,
                String mission,
                String deliverable,
                String taskInstructions,
                boolean includeHandoff) {
            this.tag = tag;
            this.identity = identity;
            this.mission = mission;
            this.deliverable = deliverable;
            this.taskInstructions = taskInstructions;
            this.includeHandoff = includeHandoff;
        }

        public String tag() {
            return tag;
        }

        public String identity() {
            return identity;
        }

        public String mission() {
            return mission;
        }

        public String deliverable() {
            return deliverable;
        }

        public String taskInstructions() {
            return taskInstructions;
        }

        public boolean includeHandoff() {
            return includeHandoff;
        }

        public abstract Set<Terminal> terminals();
    }

    public final List<ChatMessage> buildAskPrompt(Context ctx, String input, TaskResult.TaskMeta meta) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(new SystemMessage(
                "Act as an expert software developer when answering the user's question based on the code in the Workspace.\n\n"
                        + SystemPrompts.MARKDOWN_REMINDER));
        messages.addAll(WorkspacePrompts.getMessagesInAddedOrder(ctx, EnumSet.of(SpecialTextType.TASK_LIST)));
        messages.addAll(WorkspacePrompts.getHistoryMessages(ctx, meta));
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

    public SystemMessage lutzSystemPrompt(Context context, Objective objective) {
        var supportedTypes = supportedTypesForPrompt(context);

        record SearchSystemData(
                String identity,
                String deliverable,
                String mission,
                boolean includeHandoff,
                @Nullable String supportedTypes) {}

        var data = new SearchSystemData(
                objective.identity(),
                objective.deliverable(),
                objective.mission(),
                objective.includeHandoff(),
                supportedTypes);

        try {
            return new SystemMessage(LUTZ_SYSTEM_TEMPLATE.apply(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public SystemMessage searchSystemPrompt(Context context, Objective objective, List<String> allowedTools) {
        var terminals = objective.terminals();
        var data = new SearchAgentSystemData(
                objective.deliverable(),
                objective.mission(),
                supportedTypesForPrompt(context),
                hasAnyTool(allowedTools, SYNTAX_AWARE_SEARCH_TOOLS),
                hasAnyTool(allowedTools, STRUCTURED_DATA_TOOLS),
                hasAnyTool(allowedTools, GIT_HISTORY_TOOLS),
                terminals.contains(Terminal.ANSWER),
                terminals.contains(Terminal.WORKSPACE));

        try {
            return new SystemMessage(SEARCH_AGENT_SYSTEM_TEMPLATE.apply(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String supportedTypesForPrompt(Context context) {
        var languages = context.getContextManager().getProject().getAnalyzerLanguages();
        if (languages.isEmpty()) {
            return "";
        }
        return languages.stream().map(Language::name).collect(Collectors.joining(", "));
    }

    private static boolean hasAnyTool(List<String> allowedTools, Set<String> toolNames) {
        return allowedTools.stream().anyMatch(toolNames::contains);
    }

    public record SpecialTurnTooling(String turnType, List<String> allowedTools) {}

    private static final Set<String> SYNTAX_AWARE_SEARCH_TOOLS =
            Set.of("searchSymbols", "scanUsages", "getSymbolLocations");
    private static final Set<String> STRUCTURED_DATA_TOOLS = Set.of("jq", "xmlSkim", "xmlSelect");
    private static final Set<String> GIT_HISTORY_TOOLS =
            Set.of("searchGitCommitMessages", "getGitLog", "explainCommit");

    private record SearchAgentSystemData(
            String deliverable,
            String mission,
            @Nullable String supportedTypes,
            boolean hasSyntaxAwareTools,
            boolean hasStructuredDataTools,
            boolean hasGitHistoryTools,
            boolean answerObjective,
            boolean workspaceObjective) {}

    private record DirectiveData(
            String goal,
            String objectiveTag,
            String taskInstructions,
            boolean isEmptyProject,
            boolean needsBuildSetup,
            String warning,
            int turnsLeftAfterThisTurn,
            String workspaceToc,
            boolean isWorkspaceObjective,
            boolean isIssueDiagnosis,
            boolean terminalAnswer,
            boolean terminalTasks,
            boolean terminalWorkspace,
            boolean terminalCode,
            boolean terminalIssue,
            boolean finalTurnOnly,
            @Nullable SpecialTurnTooling specialTooling) {}

    private static final Template LUTZ_SYSTEM_TEMPLATE;
    private static final Template SEARCH_AGENT_SYSTEM_TEMPLATE;
    private static final Template DIRECTIVE_TEMPLATE;

    static {
        Handlebars handlebars = new Handlebars().with(EscapingStrategy.NOOP);
        handlebars.registerHelpers(ConditionalHelpers.class);
        handlebars.registerHelpers(com.github.jknack.handlebars.helper.StringHelpers.class);

        String lutzSystemTemplateText =
                """
                <instructions>
                {{identity}}

                {{mission}}

                Your responsibilities are:
                  1.  **Find & Discover:** Use search and inspection tools to locate relevant code (files, classes, methods).
                  2.  Deliverable: {{deliverable}}

                Remember: **You must never write, create, or modify code.** Your purpose is to *find* existing code, not to *create* or *modify* code.

                Memory model (reliability):
                  - Durable memory is ONLY the Workspace (fragments + SpecialText such as Discarded Context).
                  - Chat history (including tool outputs) will not be visible to other agents; make sure you capture
                    important details in the Workspace.
                  - If you might need something later, persist it into the Workspace:
                      - For structure/types/navigation: add class/file summaries.
                      - For behavior: add method sources; escalate to class source or full files only when needed.
                      - When dropping, record breadcrumbs in Discarded Context via dropWorkspaceFragments (keyFacts + dropReason).
                  - Summaries can serve as an index: add a summary to see the API/structure, then selectively add method sources or full files only if implementation details are needed.

                Critical rules:
                  1) Use search and inspection tools to discover relevant code, including classes/methods/usages/call graphs.
                     Prefer syntax-aware tools{{#if supportedTypes}} in {{supportedTypes}} files{{/if}} because they return higher-precision results with less noise.
                     - Search tool selection:
                          Definitions / declarations only?
                          -> searchSymbols
                          How known symbols are used, accessed, obtained, injected, or called?
                          -> scanUsages
                          JSON or XML?
                          -> jq or xml tools
                          String literals, config keys, markdown, comments, reflection sites, environment variables, SQL fragments, or other strings that don't show up in searchSymbols?
                          -> findFilesContaining / searchFileContents
                     - NB: you can still use searchSymbols with broad regex patterns even if you only know a concept or partial identifier.
                     - Summary limitations: Summaries only include declared symbols (classes, methods, fields).
                       They do NOT surface local variables or hardcoded strings like environment variable names,
                       system properties, or comments. If findFilesContaining finds a hit in a file but the summary
                       doesn't reveal the match, you MUST load the full file or method source to see the actual content.
                  2) Group related lookups into a single tool call when possible.
                  3) Your responsibility is to gather and curate the minimum sufficient context, then take the appropriate next step.
                     Do not write code, and do not attempt to write the solution or pseudocode for the solution.
                     Your job is to *gather* the materials; the Code Agent's job is to *use* them.
                     Where code changes are needed, add the *target files* to the workspace using `addFilesToWorkspace`
                     and let the Code Agent write the code. (For more localized changes, you can use `addMethodsToWorkspace`
                     or `addClassesToWorkspace`, instead of adding entire files.)
                     Note: Code Agent will also take care of creating new files; you only need to add existing files to the Workspace.
                  4) When you have enough information to take a final action, do so.
                     There are no bonus points for grooming the perfect Workspace.

                Working efficiently:
                  - Think before calling tools.
                  - Make multiple tool calls at once when searching for different types of code. Dropping
                    fragments should always be done in conjunction with other tools, since you will gain
                    no new information from the drop result.
                  - If you already know what to add, use Workspace tools directly; do not search redundantly.

                External library discovery:
                  - When the goal requires using an external library, search for its key classes/modules first
                  - If NOT found in Code Intelligence, use `importDependency` to import it:
                    * Java: `importDependency("com.fasterxml.jackson.core:jackson-databind")`
                    * Python: `importDependency("requests")` or `importDependency("numpy 2.0.0")`
                    * Rust: `importDependency("serde")` or `importDependency("tokio 1.0")`
                    * Node.js: `importDependency("lodash")` or `importDependency("@types/node")`
                  - Once imported, the library becomes searchable and can be added to the Workspace.
                  - This helps Code Agent see actual API signatures and write more accurate code.
                </instructions>
                """;
        try {
            LUTZ_SYSTEM_TEMPLATE = handlebars.compileInline(lutzSystemTemplateText);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String searchAgentSystemTemplateText =
                """
                <instructions>
                Your role is EXCLUSIVELY to search and analyze existing code.

                {{mission}}

                Your strengths:
                    - Rapidly finding files using glob patterns
                    - Searching code and text with powerful regex patterns
                    - Reading and analyzing file contents

                Guidelines:
                    - Use addFilesToWorkspace or addLineRangeToWorkspace when you know the specific file path or range you need to read.
                    - When you identify a specific class or method, prefer adding its summary or source (addClassSummariesToWorkspace, addMethodsToWorkspace) to keep the Workspace lean.
                {{#if hasSyntaxAwareTools}}
                    - Prefer syntax-aware tools (searchSymbols, scanUsages, getSymbolLocations){{#if supportedTypes}} for {{supportedTypes}} files{{/if}} for higher-signal symbol and usage discovery.
                {{/if}}
                {{#if hasStructuredDataTools}}
                    - Prefer structured query tools (jq, xmlSelect) for JSON or XML when structure matters.
                {{/if}}
                {{#if hasGitHistoryTools}}
                    - Use Git-history tools only when repository history is relevant to the request.
                {{/if}}
                    - Preserve project-relative paths and fully-qualified symbols in your final response.
                    - For clear communication, avoid using emojis.
                {{#if answerObjective}}
                    - Finalize with `answer(String)` once you have enough evidence; if you hit a dead end, use `abortSearch(String)` instead of guessing.
                {{/if}}
                {{#if workspaceObjective}}
                    - Finalize with `workspaceComplete()` once the Workspace contains the minimum sufficient context; use `abortSearch(String)` if the request cannot be satisfied.
                {{/if}}

                NOTE: You are meant to be a fast agent that returns output as quickly as possible. In order to achieve this you must:
                    - Make efficient use of the tools that you have at your disposal: be smart about how you search for symbols and implementations.
                    - Wherever possible you should try to spawn multiple parallel tool calls for searching and reading code.

                Complete the user's search request efficiently and report your findings clearly.
                </instructions>
                """
                        .stripIndent();
        try {
            SEARCH_AGENT_SYSTEM_TEMPLATE = handlebars.compileInline(searchAgentSystemTemplateText);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String templateText =
                """
                <{{objectiveTag}}>
                {{goal}}
                </{{objectiveTag}}>

                <search-objective>
                {{#if taskInstructions~}}
                {{taskInstructions}}
                {{~/if}}

                {{#unless specialTooling~}}
                Invariant: Before any final action, make reasonable efforts to first add the minimum sufficient, decision-relevant context
                to the Workspace. If you cannot find relevant context, say so instead of guessing.

                Workspace context guidance:
                  - If you know where to find what you're looking for, just add it, you don't need to keep searching "just in case".
                  - If you don't know where to find a piece of information, use search tools or skimDirectory to identify specific files/classes/methods instead of guessing.
                  - The add*ToWorkspace tools do not work with directories or globs or wildcards as parameters;
                    skimDirectory can help you narrow down your search, after which you should add only those specific items to the Workspace.
                When to prefer the different content types:
                  - Summaries: when you only need API signatures/types/constants.
                  - Method sources: when you need implementation details for specific methods.
                  - Full sources: when you need complete implementation details.
                {{/unless~}}
                </search-objective>

                {{#if isEmptyProject~}}
                <empty-project-notice>
                The project appears to be empty or uninitialized (few or no source files).
                Adapt your approach:
                  - Prefer searching the repository structure first (e.g., `skimDirectory`, `findFilenames`) to confirm what exists.
                  - If the user's request requires new code, your role is still to prepare context and produce tasks, not to write code.
                  - For code-change requests, prefer producing a task list that starts with creating the minimal project skeleton and build/test setup.
                </empty-project-notice>
                {{~/if}}

                {{#if needsBuildSetup~}}
                <build-setup-task-guidance>
                If you produce a task list, the FIRST task MUST configure the build and test stack (and any required environment variables)
                so that subsequent tasks can run `build/lint` and tests.
                </build-setup-task-guidance>
                {{~/if}}

                <tool-instructions>
                {{#unless specialTooling~}}
                Decide the next tool action(s) to make progress toward the objective in service of the goal.

                {{#if (eq turnsLeftAfterThisTurn 1)~}}
                [HARNESS NOTE This is the penultimate turn. If you need any final information from non-terminal tools, request it now because you will only have one more turn after this.]
                {{~/if }}

                Pruning mandate (do this now):
                  - Prune in parallel with exploration.
                  - Drop irrelevant/noise fragments now with dropWorkspaceFragments.
                  - Replace large fragments with smaller artifacts (addFileSummariesToWorkspace, addClassSummariesToWorkspace, addMethodsToWorkspace) when possible; drop superseded originals.
                  - Check Discarded Context before re-adding content; you may not drop pinned fragments.

                {{#if isWorkspaceObjective~}}
                Tests:
                  - Code Agent will run the tests in the Workspace to validate its changes.
                    These can be full files (if it also needs to edit or understand test implementation details),
                    or simple summaries if they just need to be run for validation. Thus, you should
                    convert tests whose full source you don't need to summaries by dropping the file and
                    adding the summary. In general, you should avoid dropping test summaries.
                {{~/if}}
                {{/unless}}

                {{#if finalTurnOnly~}}
                This is the final turn. You must call one of the following tools:
                {{else~}}
                Finalization options:
                {{/if}}
                {{#if isIssueDiagnosis}}
                - describeIssue(String title, String body): finalize with this tool. abortSearch is the only other allowed final tool.
                {{/if}}
                {{#if terminalAnswer}}
                - answer(String): call this once the Workspace contains sufficient context to justify the answer, OR when the question is explicitly codebase-independent. Must be Markdown-formatted (see <markdown-reminder>).
                - askForClarification(String queryForUser): when the goal is unclear or you cannot find the necessary information; asks the user directly and stops.
                {{/if}}
                {{#if terminalTasks}}
                - createOrReplaceTaskList(String explanation, List<TaskListEntry> tasks): replace the entire task list when the request involves code changes. Titles are summarized automatically from task text; pass task texts only. Completed tasks from the previous list are implicitly dropped. Produce a clear, minimal, incremental, and testable sequence of tasks that a Code Agent can execute, once you understand where all the necessary pieces live.
                  Guidance:
                    - Each task must be self-contained; the Code Agent will not have access to your instructions or conversation history.
                    - It is CRITICAL to keep the project buildable and testable after each task; in the VERY RARE case where breaking the build
                      temporarily is necessary, YOU MUST BE EXPLICIT about this to avoid confusing the Code Agent.
                    - Given the above, DO NOT create pure "testing" tasks.
                {{/if}}
                {{#if terminalWorkspace}}
                - workspaceComplete(): when the Workspace contains all the information necessary to accomplish the goal.
                {{/if}}
                {{#if terminalCode}}
                - callCodeAgent(String instructions, boolean deferBuild): the task is simple enough to attempt implementation now in a single shot without creating a formal task list.
                {{/if}}
                - abortSearch(String explanation): the answer cannot be found or the request is out of scope for this codebase. Provide a clear explanation of your decision.

                {{#unless finalTurnOnly~}}
                You CAN call multiple non-terminal tools in a single turn, and you SHOULD whenever you can
                usefully do so.

                Terminal actions ({{#if terminalAnswer}}answer, {{/if}}{{#if terminalTasks}}createOrReplaceTaskList, {{/if}}{{#if terminalWorkspace}}workspaceComplete, {{/if}}{{#if terminalCode}}callCodeAgent, {{/if}}{{#if terminalIssue}}describeIssue, {{/if}}abortSearch)
                must be the ONLY tool in a turn, other than final cleanup via dropWorkspaceFragments.
                If you include a terminal together with other tools, the terminal will be ignored for this turn.
                {{~/unless}}

                Remember: it is NOT your objective to write code.

                {{#unless specialTooling~}}
                {{#if warning~}}
                {{warning}}
                {{~/if}}
                {{~/unless}}
                </tool-instructions>

                {{#if specialTooling~}}
                When a special-turn tool whitelist is provided, it is strict and overrides everything else.
                You must only select from the specified tools.
                <special-turn-tools turn_type="{{specialTooling.turnType}}">
                  {{join specialTooling.allowedTools ", "}}
                </special-turn-tools>
                {{~/if}}

                {{#unless isIssueDiagnosis~}}
                <markdown-reminder>
                IMPORTANT: When providing explanations, thoughts, or answers, ALWAYS use Markdown for readability.
                - Use `inline code` for identifiers, file paths, and short snippets.
                - Use code blocks for longer snippets.
                - Use headers, lists, and bold text to structure your response.
                </markdown-reminder>
                {{~/unless}}

                {{workspaceToc}}
                """
                        .stripIndent();
        try {
            DIRECTIVE_TEMPLATE = handlebars.compileInline(templateText);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Builds the prompt messages for SearchAgent.
     *
     * @param context the current context
     * @param model the model to use for token limit calculation
     * @param taskMeta the task metadata
     * @param goal the search goal
     * @param objective the search objective
     * @param mcpTools the list of MCP tools available
     * @param sessionMessages the session-local conversation messages
     * @return PromptResult containing the messages
     */
    public List<ChatMessage> buildPrompt(
            Context context,
            StreamingChatModel model,
            TaskResult.TaskMeta taskMeta,
            String goal,
            SearchPrompts.Objective objective,
            List<McpPrompts.McpTool> mcpTools,
            List<ChatMessage> sessionMessages,
            Map<ProjectFile, String> relatedSymbols,
            LutzAgent.DropMode dropMode,
            int turnsLeftAfterThisTurn,
            @Nullable SpecialTurnTooling specialTooling) {

        var cm = context.getContextManager();

        // Determine viewing policy based on search objective
        boolean useTaskList = objective == Objective.LUTZ || objective == Objective.TASKS_ONLY;
        var suppressed = useTaskList ? EnumSet.noneOf(SpecialTextType.class) : EnumSet.of(SpecialTextType.TASK_LIST);

        // Build workspace messages in insertion order with viewing policy applied
        var workspaceMessages = WorkspacePrompts.getMessagesInAddedOrder(context, suppressed);
        long workspaceTokens = Messages.getApproximateMessageTokens(workspaceMessages);

        var messages = new ArrayList<ChatMessage>();

        messages.add(lutzSystemPrompt(context, objective));

        // Describe available MCP tools
        var mcpToolPrompt = McpPrompts.mcpToolPrompt(mcpTools);
        if (mcpToolPrompt != null) {
            messages.add(new SystemMessage(mcpToolPrompt));
        }

        // Current Workspace contents (apply viewing policy for visibility filtering)
        messages.addAll(workspaceMessages);

        // Conversation history plus this agent's messages
        messages.addAll(WorkspacePrompts.getHistoryMessages(context, taskMeta));
        messages.addAll(sessionMessages);

        // Related identifiers from nearby files (Discovery suggestions after history)
        if (!relatedSymbols.isEmpty()) {
            var relatedBlock = ArchitectPrompts.formatRelatedFiles(relatedSymbols);
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

        // Workspace size warning (and drop-only recovery notice)
        var maxInputTokens = cm.getService().getMaxInputTokens(model);
        double pct = (double) workspaceTokens / maxInputTokens * 100.0;
        String warning = "";

        switch (dropMode) {
            case DROP_ONLY ->
                warning =
                        """
                    <workspace-size-warning>
                    CRITICAL: Workspace is using %.0f%% of input budget (%d tokens of %d).
                    You MUST reduce Workspace size immediately before any further exploration.
                    Replace full text with summaries and drop non-essential fragments first.
                    </workspace-size-warning>
                    """
                                .formatted(pct, workspaceTokens, maxInputTokens);
            case DROP_ENCOURAGED ->
                warning =
                        """
                    <workspace-size-warning>
                    NOTICE: Workspace is using %.0f%% of input budget (%d tokens of %d).
                    Prefer summaries and prune aggressively before expanding further.
                    </workspace-size-warning>
                    """
                                .formatted(pct, workspaceTokens, maxInputTokens);
            case NORMAL -> {}
        }

        boolean needsBuildSetup = (objective == Objective.LUTZ || objective == Objective.TASKS_ONLY)
                && cm.getProject().awaitBuildDetails().equals(BuildAgent.BuildDetails.EMPTY);

        var terminals = objective.terminals();
        var data = new DirectiveData(
                goal,
                objective.tag(),
                objective.taskInstructions().strip(),
                cm.getProject().isEmptyProject(),
                needsBuildSetup,
                warning,
                turnsLeftAfterThisTurn,
                WorkspacePrompts.formatToc(context, suppressed),
                objective == Objective.WORKSPACE_ONLY,
                objective == Objective.ISSUE_DESCRIPTION,
                terminals.contains(Terminal.ANSWER),
                terminals.contains(Terminal.TASK_LIST),
                terminals.contains(Terminal.WORKSPACE),
                terminals.contains(Terminal.CODE),
                terminals.contains(Terminal.DESCRIBE_ISSUE),
                turnsLeftAfterThisTurn == 0,
                specialTooling);

        String directive;
        try {
            directive = DIRECTIVE_TEMPLATE.apply(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        messages.add(new UserMessage(directive));
        return messages;
    }

    public List<ChatMessage> buildPromptWorkspaceOnly(
            Context context,
            StreamingChatModel model,
            String goal,
            SearchPrompts.Objective objective,
            List<McpPrompts.McpTool> mcpTools,
            LutzAgent.DropMode dropMode,
            int turnsLeftAfterThisTurn,
            SpecialTurnTooling specialTooling) {

        var cm = context.getContextManager();

        boolean useTaskList = objective == Objective.LUTZ || objective == Objective.TASKS_ONLY;
        var suppressed = useTaskList ? EnumSet.noneOf(SpecialTextType.class) : EnumSet.of(SpecialTextType.TASK_LIST);

        var workspaceMessages = WorkspacePrompts.getMessagesInAddedOrder(context, suppressed);

        var messages = new ArrayList<ChatMessage>();
        messages.add(lutzSystemPrompt(context, objective));

        var mcpToolPrompt = McpPrompts.mcpToolPrompt(mcpTools);
        if (mcpToolPrompt != null) {
            messages.add(new SystemMessage(mcpToolPrompt));
        }

        messages.addAll(workspaceMessages);

        boolean needsBuildSetup = (objective == Objective.LUTZ || objective == Objective.TASKS_ONLY)
                && cm.getProject().awaitBuildDetails().equals(BuildAgent.BuildDetails.EMPTY);

        var terminals = objective.terminals();
        var data = new DirectiveData(
                goal,
                objective.tag(),
                objective.taskInstructions().strip(),
                cm.getProject().isEmptyProject(),
                needsBuildSetup,
                "",
                turnsLeftAfterThisTurn,
                WorkspacePrompts.formatToc(context, suppressed),
                objective == Objective.WORKSPACE_ONLY,
                objective == Objective.ISSUE_DESCRIPTION,
                terminals.contains(Terminal.ANSWER),
                terminals.contains(Terminal.TASK_LIST),
                terminals.contains(Terminal.WORKSPACE),
                terminals.contains(Terminal.CODE),
                terminals.contains(Terminal.DESCRIBE_ISSUE),
                turnsLeftAfterThisTurn == 0,
                specialTooling);

        String directive;
        try {
            directive = DIRECTIVE_TEMPLATE.apply(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        messages.add(new UserMessage(directive));
        return messages;
    }

    public enum Terminal {
        TASK_LIST,
        ANSWER,
        WORKSPACE,
        CODE,
        REVIEW,
        DESCRIBE_ISSUE
    }
}
