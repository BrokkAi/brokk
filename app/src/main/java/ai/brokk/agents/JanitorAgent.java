package ai.brokk.agents;

import static ai.brokk.tools.WorkspaceTools.DROP_EXPLANATION_GUIDANCE;
import static java.util.Objects.requireNonNull;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.LlmOutputMeta;
import ai.brokk.TaskResult;
import ai.brokk.context.Context;
import ai.brokk.context.SpecialTextType;
import ai.brokk.prompts.WorkspacePrompts;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.tools.WorkspaceTools;
import com.github.jknack.handlebars.EscapingStrategy;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.helper.ConditionalHelpers;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ToolChoice;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JanitorAgent:
 * - Performs a single-shot cleanup of the Workspace context.
 * - Uses LLM to decide which fragments are irrelevant to the current goal.
 */
public class JanitorAgent {
    public static final Template PRUNING_TEMPLATE;

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
    }

    private final IContextManager cm;
    private final String goal;
    private final IConsoleIO io;
    private final Llm llm;
    private Context context;

    public JanitorAgent(IContextManager cm, IConsoleIO io, String goal, Context initialContext) {
        this.cm = cm;
        this.io = io;
        this.goal = goal;
        this.context = initialContext;

        var scanModel = cm.getService().getScanModel();
        var janitorOpts = new Llm.Options(scanModel, "Janitor: " + goal, TaskResult.Type.JANITOR).withEcho();
        this.llm = cm.getLlm(janitorOpts);
        llm.setOutput(io);
    }

    /**
     * Builds the pruning prompt for the Janitor (Workspace Reviewer) logic.
     */
    static List<ChatMessage> buildPruningPrompt(Context context, String goal) {
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

    public TaskResult execute() throws InterruptedException {
        var wst = new WorkspaceTools(context);
        var tr = cm.getToolRegistry().builder().register(wst).register(this).build();

        var toolNames = new ArrayList<String>();
        toolNames.add("performedInitialReview");
        toolNames.add("dropWorkspaceFragments");

        var toolSpecs = tr.getTools(toolNames);

        io.llmOutput(
                "\n**Brokk** performing initial workspace review...", ChatMessageType.AI, LlmOutputMeta.newMessage());

        TaskResult.StopDetails stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
        String retryNote = "";

        for (int attempt = 1; attempt <= 3; attempt++) {
            wst.setContext(context);
            wst.clearLastDropReport();

            var messages = new ArrayList<>(buildPruningPrompt(context, goal));
            if (!retryNote.isBlank()) {
                messages.add(new UserMessage(retryNote));
            }

            var result = llm.sendRequest(
                    messages, new dev.langchain4j.agent.tool.ToolContext(toolSpecs, ToolChoice.REQUIRED, tr));

            if (result.error() != null) {
                stopDetails = TaskResult.StopDetails.fromResponse(result);
                break;
            }

            var ai = ToolRegistry.removeDuplicateToolRequests(result.aiMessage());

            stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);

            var toolRequests = ai.toolExecutionRequests();
            var dropRequest = toolRequests.stream()
                    .filter(req -> req.name().equals("dropWorkspaceFragments"))
                    .findFirst();
            var performedInitialRequest = toolRequests.stream()
                    .filter(req -> req.name().equals("performedInitialReview"))
                    .findFirst();
            var req = dropRequest
                    .or(() -> performedInitialRequest)
                    .orElseThrow(() -> new IllegalStateException("Llm tool call REQUIRED should prevent this"));
            io.beforeToolCall(req);
            var toolResult = tr.executeTool(req);
            io.afterToolOutput(toolResult);

            // always done on fatal/internal error
            if (Set.of(ToolExecutionResult.Status.FATAL, ToolExecutionResult.Status.INTERNAL_ERROR)
                    .contains(toolResult.status())) {
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.TOOL_ERROR, toolResult.resultText());
                break;
            }

            if (toolResult.status() == ToolExecutionResult.Status.REQUEST_ERROR) {
                String errorMsg = "The tool request was invalid:\n" + toolResult.resultText();
                retryNote = buildRetryNote(errorMsg, context);
                continue;
            }

            // Sync context if it was a workspace tool
            if (dropRequest.isPresent()) {
                this.context = wst.getContext();
            } else {
                // performedInitialReview called, nothing to drop
                break;
            }

            var dropReport = requireNonNull(wst.getLastDropReport());
            wst.clearLastDropReport();
            if (dropReport.unknownFragmentIds().isEmpty()) {
                // it worked!
                break;
            }

            // if the model asked us to drop non-existent ids, retry
            String unknownMsg = "I was unable to identify the following fragmentId(s):\n"
                    + dropReport.unknownFragmentIds().stream()
                            .sorted()
                            .map(id -> "  - " + id)
                            .collect(Collectors.joining("\n"));
            retryNote = buildRetryNote(unknownMsg, context);
        }

        Context historyContext =
                context.addHistoryEntry(io.getLlmRawMessages(), TaskResult.Type.JANITOR, llm.getModel(), goal);
        return new TaskResult(historyContext, stopDetails);
    }

    private static String buildRetryNote(String errorMessage, Context context) {
        String droppable = WorkspacePrompts.formatTocForJanitor(context).trim();
        if (droppable.isBlank()) {
            droppable = "(none)";
        }

        return """
                There was an error with your dropWorkspaceFragments tool call.
                %s

                IMPORTANT:
                - Use the "fragmentid" (e.g., "12345678-abcd-...") as the identifier.
                - DO NOT use the filename or description as the fragmentId.
                - Ensure the fragmentId exists in the list below.

                Current droppable fragments:
                %s

                Please call dropWorkspaceFragments again with corrected parameters, or call performedInitialReview if the workspace is now well-curated.
                """
                .stripIndent()
                .formatted(errorMessage, droppable);
    }

    @Tool("Signal that the initial workspace review is complete and all fragments are relevant.")
    @SuppressWarnings("UnusedMethod")
    public String performedInitialReview() {
        return "Initial review complete; workspace is well-curated.";
    }
}
