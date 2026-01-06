package ai.brokk.agents;

import ai.brokk.ContextManager;
import ai.brokk.ICodeReview;
import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.TaskResult;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.SpecialTextType;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.prompts.WorkspacePrompts;
import ai.brokk.util.ReviewExcerptParser;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ToolChoice;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
    private final IConsoleIO io;

    public ReviewAgent(String diff, IContextManager cm, IConsoleIO io) {
        this.diff = diff;
        this.cm = cm;
        this.io = io;
    }

    @Blocking
    public ICodeReview.GuidedReview execute() throws InterruptedException {
        String goal =
                "Identify all code locations relevant to the provided diff to perform a comprehensive code review focusing on design, correctness, and simplicity.";

        // Prepare the initial context with the diff pinned
        var diffFragment = new ContextFragments.StringFragment(
                cm, diff, "Proposed Changes (Diff)", SyntaxConstants.SYNTAX_STYLE_NONE);

        Context initialContext = new Context(cm).addFragments(diffFragment).withPinned(diffFragment, true);

        // Configure SearchAgent with ARCHITECT model and noAppend scan config
        var model = cm.getService().getModel(ModelType.ARCHITECT);
        var scanConfig = SearchAgent.ScanConfig.noAppend();

        try (ContextManager.TaskScope scope = cm.beginTask(goal, false, "Code Review")) {
            SearchAgent agent = new SearchAgent(initialContext, goal, model, scope, io, scanConfig);

            // Phase 1: Establish context using SearchAgent
            agent.scanContext();
            TaskResult searchResult = agent.execute();

            if (searchResult.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
                throw new RuntimeException("Review context gathering failed: "
                        + searchResult.stopDetails().explanation());
            }

            // Phase 2: Perform the actual review using the gathered context
            var finalContext = searchResult.context().withHistory(List.of());
            var reviewLlm = cm.getLlm(new Llm.Options(model, "Finalizing Code Review").withEcho());
            reviewLlm.setOutput(io);

            var messages = new ArrayList<ChatMessage>();

            // System message with high-level instructions
            messages.add(new SystemMessage("""
                    You are an expert code reviewer. Your task is to provide a structured, actionable code review
                    of the proposed changes. Focus on design quality, correctness, and simplicity.
                    
                    You will analyze the diff and the surrounding context to identify:
                    1. Design issues - architectural concerns, coupling, abstraction problems
                    2. Tactical issues - local bugs, edge cases, error handling gaps
                    3. Testing gaps - missing test coverage that would add significant value
                    
                    Be constructive and specific. Each piece of feedback should be actionable.
                    """));

            // Add workspace content (the context gathered by SearchAgent)
            var workspaceMessages =
                    WorkspacePrompts.getMessagesInAddedOrder(finalContext, EnumSet.noneOf(SpecialTextType.class));
            messages.addAll(workspaceMessages);

            // User message with specific review directions
            messages.add(new UserMessage("""
                    Review the proposed changes in the diff against the gathered context.
                    
                    When reviewing the code, think about the following points:
                    - What is this code intended to do?
                    - Does it accomplish its goals in the simplest way possible?
                    - What parts are the trickiest and how could they be simplified?
                    - What additional tests, if any, would add the most value?
                    
                    ## Response Format
                    
                    You MUST respond using the `createReview` tool with your structured feedback.
                    
                    ## Code Excerpts (IMPORTANT)
                    
                    When referencing code in your review, you must use the BRK_EXCERPT mechanism:
                    
                    1. In the tool call, provide excerpts as a list of `CodeExcerpt` objects. Each excerpt needs:
                       - `file`: The filename (use "BRK_EXCERPT" as a placeholder)
                       - `excerpt`: Use a placeholder like "BRK_EXCERPT_0", "BRK_EXCERPT_1", etc.
                    
                    2. In the `designNotes` and `tacticalNotes`, reference excerpts by their integer `id`.
                    
                    3. In your text response OUTSIDE the tool call, provide the actual code blocks using this EXACT format:
                    
                    ```
                    BRK_EXCERPT_0
                    path/to/file.java
                    ```java
                    // actual code here
                    ```
                    
                    BRK_EXCERPT_1
                    path/to/another/file.java
                    ```java
                    // more code here
                    ```
                    
                    This separation allows code excerpts to be displayed properly while keeping the tool call clean.
                    
                    Now analyze the diff and provide your structured review.
                    """));

            var tr = cm.getToolRegistry().builder().register(this).build();
            var toolSpecs = tr.getTools(List.of("createReview"));

            var result = reviewLlm.sendRequest(messages, new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr));

            if (result.error() != null || result.toolRequests().isEmpty()) {
                throw new RuntimeException("Failed to generate code review: "
                        + (result.error() != null ? result.error().getMessage() : "No review generated"));
            }

            var reviewCall = result.toolRequests().getFirst();
            var executionResult = tr.executeTool(reviewCall);

            if (executionResult.status() != ai.brokk.tools.ToolExecutionResult.Status.SUCCESS) {
                throw new RuntimeException("Failed to process code review: " + executionResult.resultText());
            }

            ICodeReview.GuidedReview rawReview = ICodeReview.GuidedReview.fromJson(executionResult.resultText());

            // Post-process to resolve out-of-band excerpts
            Map<String, ICodeReview.CodeExcerpt> parsedExcerpts =
                    ReviewExcerptParser.instance.parseExcerpts(result.text());

            return resolveReviewExcerpts(rawReview, parsedExcerpts);
        }
    }

    private ICodeReview.GuidedReview resolveReviewExcerpts(
            ICodeReview.GuidedReview review, Map<String, ICodeReview.CodeExcerpt> resolvedMap) {
        List<ICodeReview.DesignFeedback> resolvedDesign = review.designNotes().stream()
                .map(design -> {
                    List<ICodeReview.CodeExcerpt> resolvedExcerpts = design.excerpts().stream()
                            .map(ex -> resolvedMap.getOrDefault(ex.excerpt(), ex))
                            .toList();
                    return new ICodeReview.DesignFeedback(
                            design.title(), design.description(), resolvedExcerpts, design.recommendation());
                })
                .toList();

        List<ICodeReview.TacticalFeedback> resolvedTactical = review.tacticalNotes().stream()
                .map(tactical -> {
                    ICodeReview.CodeExcerpt resolvedEx = resolvedMap.getOrDefault(tactical.excerpt().excerpt(), tactical.excerpt());
                    return new ICodeReview.TacticalFeedback(tactical.title(), resolvedEx, tactical.recommendation());
                })
                .toList();

        return new ICodeReview.GuidedReview(
                review.overview(), resolvedDesign, resolvedTactical, review.additionalTests());
    }

    @Tool("Create a structured code review of the current changes or proposal.")
    public String createReview(
            @P(
                            "A list of code excerpts referenced in the review. Each has a file path placeholder and excerpt placeholder.")
                    List<ICodeReview.CodeExcerpt> excerpts,
            @P(
                            "Explain your understanding of what these changes are intended to accomplish. Does it accomplish its goals in the simplest way possible? Use Markdown formatting.")
                    String overview,
            @P(
                            "Explain the trickiest parts of the design and how they can be improved. "
                                    + "For each item, provide a 'title' which is a short 5-7 word label summarizing the feedback.")
                    List<ICodeReview.RawDesignFeedback> designNotes,
            @P("A list of local bugs or problems.")
                    List<ICodeReview.RawTacticalFeedback> tacticalNotes,
            @P("Describe additional tests with high benefit:cost, if any, formatted with Markdown.")
                    List<String> additionalTests) {
        var rawReview = new ICodeReview.RawReview(overview, designNotes, tacticalNotes, additionalTests);
        var guidedReview = ICodeReview.GuidedReview.fromRaw(rawReview, excerpts);
        return guidedReview.toJson();
    }
}
