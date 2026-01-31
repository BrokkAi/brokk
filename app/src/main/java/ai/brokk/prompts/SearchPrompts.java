package ai.brokk.prompts;

import static ai.brokk.tools.WorkspaceTools.DROP_EXPLANATION_GUIDANCE;

import ai.brokk.TaskResult;
import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.Language;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generates prompts for the Search Agent and Ask requests.
 */
public class SearchPrompts {
    public static final SearchPrompts instance = new SearchPrompts();

    public enum Objective {
        ANSWER_ONLY("query") {
            @Override
            public Set<Terminal> terminals() {
                return EnumSet.of(Terminal.ANSWER);
            }
        },
        TASKS_ONLY("instructions") {
            @Override
            public Set<Terminal> terminals() {
                return EnumSet.of(Terminal.TASK_LIST);
            }
        },
        LUTZ("query_or_instructions") {
            @Override
            public Set<Terminal> terminals() {
                return EnumSet.of(Terminal.ANSWER, Terminal.CODE, Terminal.TASK_LIST);
            }
        },
        WORKSPACE_ONLY("task") {
            @Override
            public Set<Terminal> terminals() {
                return EnumSet.of(Terminal.WORKSPACE);
            }
        },
        ISSUE_DIAGNOSIS("issue_diagnosis") {
            @Override
            public Set<Terminal> terminals() {
                return EnumSet.of(Terminal.ISSUE_JSON);
            }
        },
        PROMPT_ENRICHMENT("prompt_enrichment") {
            @Override
            public Set<Terminal> terminals() {
                return EnumSet.of(Terminal.ANSWER);
            }
        };

        private final String tag;

        Objective(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }

        public abstract Set<Terminal> terminals();
    }

    public String searchAgentIdentity() {
        return """
                You are the Search Agent.
                Your job is to be the **Code Agent's preparer**. You are a researcher and librarian, not a developer.
                Your responsibilities are:
                  1.  **Find & Discover:** Use search and inspection tools to locate the context (files, classes, and methods) necessary to solve the problem or answer the question.
                  2.  **Curate & Prepare:** Aggressively prune the Workspace to leave *only* the essential context that the Code Agent will need.
                  3.  **Handoff:** Your final output is a clean workspace ready for the Code Agent to begin implementation.

                Remember: **You must never write, create, or modify code.**
                Your purpose is to *find* existing code, not *create* new code.
                The Code Agent is solely responsible for all code generation and modification.
                """;
    }

    public final List<ChatMessage> buildAskPrompt(Context ctx, String input, TaskResult.TaskMeta meta) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(new SystemMessage(
                "Act as an expert software developer when answering the user's question based on the code in the Workspace.\n\n"
                        + SystemPrompts.MARKDOWN_REMINDER));
        messages.addAll(WorkspacePrompts.getMessagesInAddedOrder(ctx, EnumSet.of(SpecialTextType.TASK_LIST)));
        messages.addAll(CodePrompts.instance.getHistoryMessages(ctx, meta));
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

        record SearchSystemData(String identity, String dropExplanationGuidance, String supportedTypes) {}
        var data = new SearchSystemData(
                searchAgentIdentity(), DROP_EXPLANATION_GUIDANCE.indent(12).stripTrailing(), supportedTypes);
        try {
            return new SystemMessage(SEARCH_SYSTEM_TEMPLATE.apply(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final Template PRUNING_TEMPLATE;

    /**
     * Builds the pruning prompt for the Janitor (Workspace Reviewer) logic.
     */
    public List<ChatMessage> buildPruningPrompt(Context context, String goal) {
        var messages = new ArrayList<ChatMessage>();

        record PruningData(String dropExplanationGuidance, String goal) {}
        var data = new PruningData(DROP_EXPLANATION_GUIDANCE.indent(4).stripTrailing(), goal);
        String prompt;
        try {
            prompt = PRUNING_TEMPLATE.apply(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        messages.add(new SystemMessage(prompt));

        // Current Workspace contents (use default viewing policy)
        var suppressed = EnumSet.of(SpecialTextType.TASK_LIST);
        messages.addAll(WorkspacePrompts.getMessagesInAddedOrder(context, suppressed));

        // Goal and project context
        var userText =
                """
                <goal>
                %s
                </goal>

                Review the Workspace above. Use the dropWorkspaceFragments tool to remove ALL fragments that are not directly useful for accomplishing the goal.
                If the workspace is already well-curated, you're done!
                """
                        .formatted(goal);
        messages.add(new UserMessage(userText));

        return messages;
    }

    private record DirectiveData(
            String goal,
            String objective,
            String objectiveTag,
            boolean isEmptyProject,
            boolean needsBuildSetup,
            String warning,
            String workspaceToc,
            boolean isWorkspaceObjective,
            boolean isIssueDiagnosis,
            boolean terminalAnswer,
            boolean terminalTasks,
            boolean terminalWorkspace,
            boolean terminalCode,
            boolean terminalIssueJson) {}

    private static final Template SEARCH_SYSTEM_TEMPLATE;
    private static final Template DIRECTIVE_TEMPLATE;

    static {
        Handlebars handlebars = new Handlebars().with(EscapingStrategy.NOOP);
        handlebars.registerHelpers(ConditionalHelpers.class);
        handlebars.registerHelpers(com.github.jknack.handlebars.helper.StringHelpers.class);

        String pruningTemplateText =
                """
                <instructions>
                You are the Janitor Agent (Workspace Reviewer). Single-shot cleanup: one response, then done.

                Scope:
                - Workspace curation ONLY. No code, no answers, no plans.

                Curation guidelines:
                - KEEP any fragment that contains logic, UI components, or utility methods
                  related to the search goal.
                - DROP if the fragment is irrelevant OR if a concise summary provides
                  100% of the value with 0% information loss.

                Tools (call exactly one):
                - performedInitialReview(): Signals that ALL unpinned fragments are relevant to the search goal.
                - dropWorkspaceFragments(fragments: {fragmentId, keyFacts, dropReason}[]): batch ALL drops in a single call.
                  Include ONLY the irrelevant fragments to drop in this call.

                drop explanation format:
                {{dropExplanationGuidance}}

                Response rules:
                - Tool call only; return exactly ONE tool call (performedInitialReview OR a single batched dropWorkspaceFragments).
                - Don't give up: if the number of irrelevant fragments is overwhelming, do your best. It's okay to not get everything, but it's not okay to call performedInitialReview without trying to clean up.
                </instructions>
                """;
        try {
            PRUNING_TEMPLATE = handlebars.compileInline(pruningTemplateText);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String searchSystemTemplateText =
                """
                <instructions>
                {{identity}}

                Memory model (reliability):
                  - Durable memory is ONLY the Workspace (fragments + SpecialText such as Discarded Context).
                  - Chat history (including tool outputs) may be summarized or truncated; do NOT rely on it to retain details.
                  - If you might need something later, persist it into the Workspace:
                      - For structure/types/navigation: add class/file summaries.
                      - For behavior: add method sources; escalate to class source or full files only when needed.
                      - When dropping, record breadcrumbs in Discarded Context via dropWorkspaceFragments (keyFacts + dropReason).
                  - Summaries can serve as an index: add a summary to see the API/structure, then selectively add method sources or full files only if implementation details are needed.

                Critical rules:
                  1) PRUNE the Workspace continuously.
                     - You may drop a fragment only when it is:
                         (a) unrelated to the goal, OR
                         (b) adequately replaced by smaller Workspace artifacts (method sources and/or class/file summaries).
                     - When using dropWorkspaceFragments, provide:
                {{dropExplanationGuidance}}
                     - Workspace granularity (Prefer the smallest sufficient unit of context):
                         - Structure/types/navigation: class or file summary is usually sufficient.
                         - Behavior/implementation: method source > class source > full file.
                  2) Use search and inspection tools to discover relevant code, including classes/methods/usages/call graphs.
                     - Search tool selection:
                          Definitions / declarations only?
                          -> searchSymbols
                          How is something used, accessed, obtained, injected, or called?
                          -> addSymbolUsagesToWorkspace
                          Strings, configs, markdown, comments, reflection, or unknown names?
                          -> searchSubstrings
                     - Summary limitations: Summaries only include declared symbols (classes, methods, fields).
                       They do NOT surface local variables or hardcoded strings like environment variable names,
                       system properties, or comments. If searchSubstrings finds a hit in a file but the summary
                       doesn't reveal the match, you MUST load the full file or method source to see the actual content.
                  3) The symbol-based tools only have visibility into the following file types: {{supportedTypes}}
                     Use text-based tools if you need to search other file types.
                  4) Group related lookups into a single tool call when possible.
                  5) Your responsibility is to gather and curate the minimum sufficient context, then take the appropriate next step.
                     Do not write code, and do not attempt to write the solution or pseudocode for the solution.
                     Your job is to *gather* the materials; the Code Agent's job is to *use* them.
                     Where code changes are needed, add the *target files* to the workspace using `addFilesToWorkspace`
                     and let the Code Agent write the code. (But when refactoring, it is usually sufficient to call `addSymbolUsagesToWorkspace`
                     and let Code Agent edit those fragments directly, instead of adding each call site's entire file.)
                     Note: Code Agent will also take care of creating new files; you only need to add existing files to the Workspace.
                  6) When you have enough information to take a final action, do so.
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
            SEARCH_SYSTEM_TEMPLATE = handlebars.compileInline(searchSystemTemplateText);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String templateText =
                """
                <{{objectiveTag}}>
                {{goal}}
                </{{objectiveTag}}>

                <search-objective>
                {{#if (eq objective "ISSUE_DIAGNOSIS")}}
                Deliver ONLY a single JSON object using the issueWriterOutput(String json) tool.

                Required output schema (STRICT):
                  { "title": "...", "bodyMarkdown": "..." }

                Requirements:
                  - Output MUST be a single JSON object (no fences, no preamble, no trailing text).
                  - "title": concise, specific issue title.
                  - "bodyMarkdown": GitHub-flavored Markdown describing the problem and impact.
                    It MUST include evidence/references to code, such as:
                      - file paths
                      - identifiers/symbol names
                      - fragment ids when available
                    It MAY include a section like "## Agent Instructions" but it must be inside bodyMarkdown.
                {{else if (eq objective "PROMPT_ENRICHMENT")}}
                Write an execution-ready enrichment of the user's request. Output ONLY the enriched prompt text via answer(String).

                Rules:
                  - Restate the request and preserve ALL explicit facts/constraints from the input.
                  - Do NOT invent. Do NOT guess. Do NOT add new tech, requirements, or details not stated.
                  - Ambiguities/missing info must become questions under **Open Questions** (no assumptions).
                  - Identify the primary code changes needed in this repo to implement the request (what to edit/add/remove at a high level).
                  - If input names files/functions/symbols, cite them; otherwise do NOT invent paths/symbols.
                  - Put test/verification expectations in **Acceptance Criteria** and/or **Verification**.

                Output (REQUIRED; exact labels, in order):
                **Summary**
                **Context**
                **Requirements**
                **Constraints**
                **Edge Cases**
                **Acceptance Criteria**
                **Open Questions**
                **Verification**
                **Plan** (explicit step-by-step; in **Plan**, name the key files/modules/classes/methods to change only if supported by the input or discovered from the repo; otherwise ask in **Open Questions**)
                {{else if (eq objective "ANSWER_ONLY")}}
                Deliver a written answer using the answer(String) tool.
                {{else if (eq objective "LUTZ")}}
                Either deliver a written answer, solve the problem by invoking Code Agent, or decompose the problem into a task list.
                {{else if (eq objective "TASKS_ONLY")}}
                Deliver a task list using the createOrReplaceTaskList(String explanation, List<String> tasks) tool.
                {{else if (eq objective "WORKSPACE_ONLY")}}
                Deliver a curated Workspace containing everything required for the follow-on Code Agent
                to solve the given task.
                {{/if}}

                {{#if terminalTasks}}
                Invariant: Before any final action:
                  1. Prune fragments that are no longer needed (superseded by summaries or irrelevant to the goal).
                     Do not finalize while the Workspace still contains obvious noise or superseded large fragments.
                  2. Add the minimum sufficient, decision-relevant context to remove guesswork.
                An unchanged or empty Workspace is a failure unless the question is explicitly independent of this codebase.

                Workspace context guidance:
                  - If you know where to find what you're looking for, just add it, you don't need to keep searching "just in case".
                  - If you don't know where to find a piece of information, use search tools or skimDirectory to identify specific files/classes/methods instead of guessing.
                  - The add*ToWorkspace tools do not work with directories or globs or wildcards as parameters;
                    skimDirectory can help you narrow down your search, after which you should add only those specific items to the Workspace.
                When to prefer the different content types:
                  - Summaries: when you only need API signatures/types/constants.
                  - Method sources: when you need implementation details for specific methods.
                  - Full sources: when you need complete implementation details.
                {{/if}}

                {{#if (eq objective "LUTZ")}}
                Then:
                  - Prefer answer(String) when no code changes are needed and the Workspace already justifies the answer (or the question is codebase-independent).
                  - Prefer callCodeAgent(String instructions, boolean deferBuild) if the requested change is small.
                  - Otherwise, decompose the problem with createOrReplaceTaskList(String explanation, List<String> tasks); do not attempt to write code yet.
                {{/if}}
                </search-objective>

                {{#if isEmptyProject}}
                <empty-project-notice>
                The project appears to be empty or uninitialized (few or no source files).
                Adapt your approach:
                  - Prefer searching the repository structure first (e.g., `skimDirectory`, `searchFilenames`) to confirm what exists.
                  - If the user's request requires new code, your role is still to prepare context and produce tasks, not to write code.
                  - For code-change requests, prefer producing a task list that starts with creating the minimal project skeleton and build/test setup.
                </empty-project-notice>
                {{/if}}

                {{#if needsBuildSetup}}
                <build-setup-task-guidance>
                If you produce a task list, the FIRST task MUST configure the build and test stack (and any required environment variables)
                so that subsequent tasks can run `build/lint` and tests.
                </build-setup-task-guidance>
                {{/if}}

                <tool-instructions>
                Decide the next tool action(s) to make progress toward the objective in service of the goal.

                Pruning mandate (do this now):
                  - Prune in parallel with exploration.
                  - Drop irrelevant/noise fragments now with dropWorkspaceFragments.
                  - Replace large fragments with smaller artifacts (addFileSummariesToWorkspace, addClassSummariesToWorkspace, addMethodsToWorkspace) when possible; drop superseded originals.
                  - Check Discarded Context before re-adding content; you may not drop pinned fragments.

                {{#if isWorkspaceObjective}}
                Tests:
                  - Code Agent will run the tests in the Workspace to validate its changes.
                    These can be full files (if it also needs to edit or understand test implementation details),
                    or simple summaries if they just need to be run for validation. Thus, you should
                    convert tests whose full source you don't need to summaries by dropping the file and
                    adding the summary. In general, you should avoid dropping test summaries.
                {{/if}}

                Finalization options:
                {{#if isIssueDiagnosis}}
                - Use issueWriterOutput(String json) to finalize. Output MUST be ONLY a single JSON object (no fences, no preamble, no trailing text). abortSearch(explanation) is the only other allowed final tool.
                {{else}}
                {{#if terminalAnswer}}
                - Use answer(String) ONLY when the Workspace already contains sufficient context to justify the answer, OR when the question is explicitly codebase-independent. The answer needs to be Markdown-formatted (see <markdown-reminder>).
                - Use askForClarification(String queryForUser) when the goal is unclear or you cannot find the necessary information; this will ask the user directly and stop.
                {{/if}}
                {{#if terminalTasks}}
                - Use createOrReplaceTaskList(String explanation, List<String> tasks) to replace the entire task list when the request involves code changes. Titles are summarized automatically from task text; pass task texts only. Completed tasks from the previous list are implicitly dropped. Produce a clear, minimal, incremental, and testable sequence of tasks that a Code Agent can execute, once you understand where all the necessary pieces live.
                  Guidance:
                    - Each task must be self-contained; the Code Agent will not have access to your instructions or conversation history.
                    - Each task is a single Markdown-formatted string that MUST contain these labeled sections:
                      **Task**: Describe what to do. Be specific and self-contained.
                      **Acceptance**: State how to verify success. You MAY omit Acceptance only for purely mechanical refactors with no behavior change.
                      **Touch points**: List concrete file paths (required when known, in `inline code`) and symbols (optional but helpful, in `inline code`).
                    - Wherever possible, include automated tests in Acceptance; if automation is not a good fit, it is acceptable to omit tests rather than prescribe manual steps.
                    - It is CRITICAL to keep the project buildable and testable after each task; in the VERY RARE case where breaking the build
                      temporarily is necessary, YOU MUST BE EXPLICIT about this to avoid confusing the Code Agent.
                {{/if}}
                {{#if terminalWorkspace}}
                - Use workspaceComplete() when the Workspace contains all the information necessary to accomplish the goal.
                {{/if}}
                {{#if terminalCode}}
                - Use callCodeAgent(String instructions, boolean deferBuild) to attempt implementation now in a single shot. If it succeeds, we finish; otherwise, continue with search/planning. Only use this when the goal is small enough to not need decomposition into a task list, and after you have added all the necessary context to the Workspace.
                {{/if}}
                - If we cannot find the answer or the request is out of scope for this codebase, use abortSearch with a clear explanation.
                {{/if}}

                You CAN call multiple non-terminal tools in a single turn, and you SHOULD whenever you can
                usefully do so.

                Terminal actions (answer, createOrReplaceTaskList, workspaceComplete, callCodeAgent, issueWriterOutput, abortSearch)
                must be the ONLY tool in a turn. If final cleanup is needed (for example, dropWorkspaceFragments), do it first,
                then finalize on the next turn. If you include a terminal together with other tools, the terminal will be ignored for this turn.

                Remember: it is NOT your objective to write code.

                {{#if warning}}
                {{warning}}
                {{/if}}
                </tool-instructions>

                {{#unless isIssueDiagnosis}}
                <markdown-reminder>
                IMPORTANT: When providing explanations, thoughts, or answers, ALWAYS use Markdown for readability.
                - Use `inline code` for identifiers, file paths, and short snippets.
                - Use code blocks for longer snippets.
                - Use headers, lists, and bold text to structure your response.
                </markdown-reminder>
                {{/unless}}

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
            List<ChatMessage> sessionMessages)
            throws InterruptedException {

        var cm = context.getContextManager();
        var inputLimit = cm.getService().getMaxInputTokens(model);

        // Determine viewing policy based on search objective
        boolean useTaskList = objective == Objective.LUTZ || objective == Objective.TASKS_ONLY;
        var suppressed = useTaskList ? EnumSet.noneOf(SpecialTextType.class) : EnumSet.of(SpecialTextType.TASK_LIST);

        // Build workspace messages in insertion order with viewing policy applied
        var workspaceMessages = WorkspacePrompts.getMessagesInAddedOrder(context, suppressed);
        // Fudge factor for langchain4j's tokenizer undercounts most modern models. Use ceil to avoid undercounting.
        long workspaceTokens = (long) Math.ceil(1.2 * Messages.getApproximateMessageTokens(workspaceMessages));

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
        messages.addAll(CodePrompts.instance.getHistoryMessages(context, taskMeta));
        messages.addAll(sessionMessages);

        // Related identifiers from nearby files (Discovery suggestions after history)
        var related = context.buildRelatedSymbols(10);
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

        // Workspace size warning
        String warning = "";
        if (inputLimit > 0) {
            double pct = (double) workspaceTokens / inputLimit * 100.0;
            if (pct > 90.0) {
                warning =
                        """
                        <workspace-size-warning>
                        CRITICAL: Workspace is using %.0f%% of input budget (%d tokens of %d).
                        You MUST reduce Workspace size immediately before any further exploration.
                        Replace full text with summaries and drop non-essential fragments first.
                        </workspace-size-warning>
                        """
                                .formatted(pct, workspaceTokens, inputLimit);
            } else if (pct > 60.0) {
                warning =
                        """
                        <workspace-size-warning>
                        NOTICE: Workspace is using %.0f%% of input budget (%d tokens of %d).
                        Prefer summaries and prune aggressively before expanding further.
                        </workspace-size-warning>
                        """
                                .formatted(pct, workspaceTokens, inputLimit);
            }
        }

        boolean needsBuildSetup = (objective == Objective.LUTZ || objective == Objective.TASKS_ONLY)
                && cm.getProject().awaitBuildDetails().equals(BuildAgent.BuildDetails.EMPTY);

        var terminals = objective.terminals();
        var data = new DirectiveData(
                goal,
                objective.name(),
                objective.tag(),
                cm.getProject().isEmptyProject(),
                needsBuildSetup,
                warning,
                WorkspacePrompts.formatToc(context, suppressed),
                objective == Objective.WORKSPACE_ONLY,
                objective == Objective.ISSUE_DIAGNOSIS,
                terminals.contains(Terminal.ANSWER),
                terminals.contains(Terminal.TASK_LIST),
                terminals.contains(Terminal.WORKSPACE),
                terminals.contains(Terminal.CODE),
                terminals.contains(Terminal.ISSUE_JSON));

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
        ISSUE_JSON
    }
}
