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
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.util.ReviewExcerptParser;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.data.message.AiMessage;
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

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

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
                """
                Identify all code locations relevant to the provided diff to perform a comprehensive code review,
                focusing on design, correctness, and simplicity.
                """;

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

            // Phase 2: Two-turn review process
            var finalContext = searchResult.context().withHistory(List.of());
            var reviewLlm = cm.getLlm(new Llm.Options(model, "Code Review").withEcho());
            reviewLlm.setOutput(io);

            // --- Turn 1: Analyze the diff and extract code excerpts ---
            var messages = new ArrayList<ChatMessage>();

            messages.add(new SystemMessage("""
                    You are an expert code reviewer. Your task is to analyze the proposed changes
                    and identify the most important code excerpts that warrant discussion.
                    
                    Focus on:
                    1. Design issues - architectural concerns, coupling, abstraction problems
                    2. Tactical issues - local bugs, edge cases, error handling gaps
                    3. Testing gaps - missing test coverage that would add significant value
                    
                    Be constructive and specific in your analysis.
                    """));

            var workspaceMessages =
                    WorkspacePrompts.getMessagesInAddedOrder(finalContext, EnumSet.noneOf(SpecialTextType.class));
            messages.addAll(workspaceMessages);

            messages.add(new UserMessage("""
                    Analyze the proposed changes in the diff against the gathered context.
                    
                    ### Step 1: Analysis
                    Think step-by-step about the intent, design, and testing gaps:
                      - What is this code intended to do?
                      - Does it accomplish its goals in the simplest way possible?
                      - What parts are the trickiest and how could they be simplified?
                      - What additional tests, if any, would add the most value?
                    
                    ### Step 2: Code Excerpt Extraction
                    Extract the subtle, tricky, or potentially incorrect blocks of code that you will
                    reference in your review. Use this exact format, with sequentially numbered, 0-based IDs:
                    
                    BRK_EXCERPT_0
                    path/to/filename.java
                    ```java
                    // code here
                    ```
                    
                    BRK_EXCERPT_1
                    path/to/another_file.py
                    ```python
                    // code here
                    ```
                    
                    Include ALL excerpts you plan to reference in your feedback.
                    """));

            var turn1Result = reviewLlm.sendRequest(messages);
            if (turn1Result.error() != null) {
                throw new RuntimeException(
                        "Failed to analyze diff for review: " + turn1Result.error().getMessage());
            }

            String analysisText = turn1Result.text();
            Map<Integer, ICodeReview.CodeExcerpt> parsedExcerpts =
                    ReviewExcerptParser.instance.parseExcerpts(analysisText);

            // --- Turn 2: Generate structured review via tool call ---
            messages.add(new AiMessage(turn1Result.text(),
                                       requireNonNullElse(requireNonNull(turn1Result.chatResponse()).reasoningContent(), "")));
            messages.add(new UserMessage("""
                    Now call createReview to produce the final structured review.
                    
                    Reference the excerpts you extracted by their numeric ID (0, 1, 2, ...) in your
                    designNotes and tacticalNotes fields.
                    
                    Remember:
                    - overview: Explain what changes accomplish and whether they do so simply
                    - designNotes: High-level architectural concerns with excerpt references
                    - tacticalNotes: Local bugs/issues with excerpt references
                    - additionalTests: High-value tests that should be added
                    """));

            var tr = cm.getToolRegistry().builder().register(this).build();
            var toolSpecs = tr.getTools(List.of("createReview"));
            var turn2Result = reviewLlm.sendRequest(messages, new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr));

            if (turn2Result.error() != null || turn2Result.toolRequests().isEmpty()) {
                throw new RuntimeException("Failed to generate code review: "
                        + (turn2Result.error() != null ? turn2Result.error().getMessage() : "No review generated"));
            }

            var reviewCall = turn2Result.toolRequests().getFirst();
            var executionResult = tr.executeTool(reviewCall);

            if (executionResult.status() != ToolExecutionResult.Status.SUCCESS) {
                throw new RuntimeException("Failed to process code review: " + executionResult.resultText());
            }

            var rawReview = ICodeReview.RawReview.fromJson(executionResult.resultText());
            return ICodeReview.GuidedReview.fromRaw(rawReview, parsedExcerpts);
        }
    }

    @Tool("Create a structured code review of the current changes or proposal.")
    public String createReview(
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
        var review = new ICodeReview.RawReview(overview, designNotes, tacticalNotes, additionalTests);
        return review.toJson();
    }
}
