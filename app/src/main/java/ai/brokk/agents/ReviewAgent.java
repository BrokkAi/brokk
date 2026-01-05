package ai.brokk.agents;

import ai.brokk.ContextManager;
import ai.brokk.ICodeReview;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.TaskResult;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragments;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.prompts.SearchPrompts;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.model.chat.request.ToolChoice;
import java.util.List;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Blocking;
import org.jspecify.annotations.NullMarked;

/**
 * ReviewAgent wraps SearchAgent to perform a guided code review on a specific diff.
 */
@NullMarked
public class ReviewAgent {

    private final String diff;
    private final IContextManager cm;

    public ReviewAgent(String diff, IContextManager cm) {
        this.diff = diff;
        this.cm = cm;
    }

    @Blocking
    public ICodeReview.GuidedReview execute() throws InterruptedException {
        String goal = "Identify all code locations relevant to the provided diff to perform a comprehensive code review focusing on design, correctness, and simplicity.";

        // Prepare the initial context with the diff pinned
        var diffFragment = new ContextFragments.StringFragment(
                cm,
                diff,
                "Proposed Changes (Diff)",
                SyntaxConstants.SYNTAX_STYLE_NONE);

        Context initialContext = cm.liveContext()
                .addFragments(diffFragment)
                .withPinned(diffFragment, true);

        // Configure SearchAgent with ARCHITECT model and noAppend scan config
        var model = cm.getService().getModel(ModelType.ARCHITECT);
        var scanConfig = SearchAgent.ScanConfig.noAppend();

        try (ContextManager.TaskScope scope = cm.beginTask(goal, false, "Code Review")) {
            SearchAgent agent = new SearchAgent(
                    initialContext,
                    goal,
                    model,
                    scope,
                    cm.getIo(),
                    scanConfig);

            // Phase 1: Establish context using SearchAgent
            agent.scanContext();
            TaskResult searchResult = agent.execute();

            if (searchResult.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
                throw new RuntimeException("Review context gathering failed: " + searchResult.stopDetails().explanation());
            }

            // Phase 2: Perform the actual review using the gathered context
            var finalContext = searchResult.context();
            var reviewLlm = cm.getLlm(new Llm.Options(model, "Finalizing Code Review").withEcho());

            var promptResult = SearchPrompts.instance.buildPrompt(
                    finalContext,
                    model,
                    "Based on the gathered context and the proposed changes, provide a structured code review using the createReview tool.",
                    SearchPrompts.Objective.WORKSPACE_ONLY,
                    List.of(),
                    List.of());

            var tr = cm.getToolRegistry().builder().register(this).build();
            var toolSpecs = tr.getTools(List.of("createReview"));

            var result = reviewLlm.sendRequest(promptResult.messages(), new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr));

            if (result.error() != null || result.toolRequests().isEmpty()) {
                throw new RuntimeException("Failed to generate code review: " + (result.error() != null ? result.error().getMessage() : "No review generated"));
            }

            var reviewCall = result.toolRequests().getFirst();
            var executionResult = tr.executeTool(reviewCall);

            if (executionResult.status() != ai.brokk.tools.ToolExecutionResult.Status.SUCCESS) {
                throw new RuntimeException("Failed to process code review: " + executionResult.resultText());
            }

            return ICodeReview.GuidedReview.fromJson(executionResult.resultText());
        }
    }

    @Tool("Create a structured code review of the current changes or proposal.")
    public String createReview(
            @P("Explain your understanding of what these changes are intended to accomplish. Does it accomplish its goals in the simplest way possible? Use Markdown formatting.")
            String overview,
            @P("Explain the trickiest parts of the design and how they can be improved")
            List<ICodeReview.DesignFeedback> designNotes,
            @P("A list of local bugs or problems") List<ICodeReview.CodeExcerpt> tacticalNotes,
            @P("Describe additional tests with high benefit:cost, if any, formatted with Markdown.")
            List<String> additionalTests) {
        var review = new ICodeReview.GuidedReview(overview, designNotes, tacticalNotes, additionalTests);
        return review.toJson();
    }
}
