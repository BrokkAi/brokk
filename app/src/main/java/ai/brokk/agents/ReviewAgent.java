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
import ai.brokk.ICodeReview.CodeExcerpt;
import ai.brokk.difftool.ui.FileComparisonInfo;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final List<FileComparisonInfo> fileComparisons;

    public ReviewAgent(
            String diff,
            IContextManager cm,
            IConsoleIO io,
            List<FileComparisonInfo> fileComparisons) {
        this.diff = diff;
        this.cm = cm;
        this.io = io;
        this.fileComparisons = fileComparisons;
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
            var turn1Messages = new ArrayList<ChatMessage>();
            turn1Messages.add(buildSystemMessage());
            turn1Messages.addAll(WorkspacePrompts.getMessagesInAddedOrder(finalContext, EnumSet.noneOf(SpecialTextType.class)));
            turn1Messages.add(buildAnalysisRequestMessage());

            var turn1Result = reviewLlm.sendRequest(turn1Messages);
            if (turn1Result.error() != null) {
                throw new RuntimeException(
                        "Failed to analyze diff for review: " + turn1Result.error().getMessage());
            }

            String analysisText = turn1Result.text();
            Map<Integer, CodeExcerpt> parsedExcerpts = ReviewExcerptParser.instance.parseExcerpts(analysisText);

            // --- Turn 1.5: Retry for missing files and non-matching excerpts ---
            Map<Integer, CodeExcerpt> validatedExcerpts = retryInStages(reviewLlm, turn1Messages, turn1Result, parsedExcerpts);

            // Resolve line numbers and side (OLD vs NEW)
            Map<Integer, CodeExcerpt> resolvedExcerpts = resolveExcerpts(validatedExcerpts);

            // --- Turn 2: Generate structured review via tool call ---
            var turn2Messages = new ArrayList<ChatMessage>(turn1Messages);
            turn2Messages.add(new AiMessage(turn1Result.text(),
                                       requireNonNullElse(requireNonNull(turn1Result.chatResponse()).reasoningContent(), "")));
            turn2Messages.add(buildReviewRequestMessage());

            var tr = cm.getToolRegistry().builder().register(this).build();
            var toolSpecs = tr.getTools(List.of("createReview"));
            var turn2Result = reviewLlm.sendRequest(turn2Messages, new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr));

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
            return ICodeReview.GuidedReview.fromRaw(rawReview, resolvedExcerpts);
        }
    }

    @Blocking
    Map<Integer, CodeExcerpt> resolveExcerpts(Map<Integer, CodeExcerpt> excerpts) {
        Map<Integer, CodeExcerpt> resolved = new HashMap<>();
        for (var entry : excerpts.entrySet()) {
            CodeExcerpt ce = entry.getValue();
            resolved.put(entry.getKey(), resolveSingleExcerpt(ce));
        }
        return resolved;
    }

    private CodeExcerpt resolveSingleExcerpt(CodeExcerpt excerpt) {
        String relPath = excerpt.file();
        FileComparisonInfo targetInfo = fileComparisons.stream()
                .filter(info -> (info.file() != null && relPath.equals(info.file().toString()))
                        || relPath.equals(info.rightSource().filename())
                        || relPath.equals(info.leftSource().filename()))
                .findFirst()
                .orElse(null);

        if (targetInfo == null) {
            return excerpt;
        }

        String[] excerptLines = excerpt.excerpt().split("\\R", -1);

        // 1. Try NEW content
        String newContent = targetInfo.rightSource().content();
        String[] newLines = newContent.split("\\R", -1);
        var newMatches = ai.brokk.util.WhitespaceMatch.findAll(newLines, excerptLines);
        if (!newMatches.isEmpty()) {
            return new CodeExcerpt(
                    excerpt.file(),
                    findBestMatch(newMatches, excerpt.line()).startLine() + 1,
                    ICodeReview.DiffSide.NEW,
                    excerpt.excerpt());
        }

        // 2. Try OLD content
        String oldContent = targetInfo.leftSource().content();
        String[] oldLines = oldContent.split("\\R", -1);
        var oldMatches = ai.brokk.util.WhitespaceMatch.findAll(oldLines, excerptLines);
        if (!oldMatches.isEmpty()) {
            return new CodeExcerpt(
                    excerpt.file(),
                    findBestMatch(oldMatches, excerpt.line()).startLine() + 1,
                    ICodeReview.DiffSide.OLD,
                    excerpt.excerpt());
        }

        return excerpt;
    }

    private ai.brokk.util.WhitespaceMatch findBestMatch(
            List<ai.brokk.util.WhitespaceMatch> matches, int targetLine) {
        ai.brokk.util.WhitespaceMatch best = matches.getFirst();
        int minDelta = Math.abs(best.startLine() + 1 - targetLine);
        for (int i = 1; i < matches.size(); i++) {
            int delta = Math.abs(matches.get(i).startLine() + 1 - targetLine);
            if (delta < minDelta) {
                minDelta = delta;
                best = matches.get(i);
            }
        }
        return best;
    }

    private record ExcerptData(Map<Integer, String> files, Map<Integer, String> contents) {}

    public static Map<Integer, String> validateFileExists(Map<Integer, CodeExcerpt> excerpts, IContextManager cm) {
        Map<Integer, String> errors = new HashMap<>();
        for (var entry : excerpts.entrySet()) {
            if (cm.toFile(entry.getValue().file()) == null) {
                errors.put(entry.getKey(), "File does not exist: " + entry.getValue().file());
            }
        }
        return errors;
    }

    static Map<Integer, String> validateExcerptInDiff(Map<Integer, CodeExcerpt> excerpts, String diff) {
        Map<Integer, String> errors = new HashMap<>();
        for (var entry : excerpts.entrySet()) {
            if (!diff.contains(entry.getValue().excerpt())) {
                errors.put(entry.getKey(), "Excerpt not found in diff");
            }
        }
        return errors;
    }

    @Blocking
    private Map<Integer, CodeExcerpt> retryInStages(
            Llm llm,
            List<ChatMessage> turn1Messages,
            Llm.StreamingResult turn1Result,
            Map<Integer, CodeExcerpt> initialExcerpts) throws InterruptedException {

        Map<Integer, CodeExcerpt> currentExcerpts = retryFileNotFound(llm, turn1Messages, turn1Result, initialExcerpts);
        return retryExcerptNotFound(llm, turn1Messages, turn1Result, currentExcerpts);
    }

    @Blocking
    Map<Integer, CodeExcerpt> retryFileNotFound(
            Llm llm,
            List<ChatMessage> turn1Messages,
            Llm.StreamingResult turn1Result,
            Map<Integer, CodeExcerpt> initialExcerpts) throws InterruptedException {

        Map<Integer, String> currentFiles = new HashMap<>();
        Map<Integer, String> currentContents = new HashMap<>();
        initialExcerpts.forEach((id, ce) -> {
            currentFiles.put(id, ce.file());
            currentContents.put(id, ce.excerpt());
        });

        List<ChatMessage> history = new ArrayList<>(turn1Messages);
        history.add(new AiMessage(turn1Result.text(),
                requireNonNullElse(requireNonNull(turn1Result.chatResponse()).reasoningContent(), "")));

        for (int i = 0; i < 2; i++) {
            Map<Integer, String> errors = new HashMap<>();
            for (var entry : currentFiles.entrySet()) {
                if (cm.toFile(entry.getValue()) == null) {
                    errors.put(entry.getKey(), "File does not exist: " + entry.getValue());
                }
            }
            if (errors.isEmpty()) break;

            String errorList = errors.entrySet().stream()
                    .map(e -> "- Excerpt " + e.getKey() + ": " + e.getValue())
                    .collect(java.util.stream.Collectors.joining("\n"));

            UserMessage retryMessage = new UserMessage("""
                    The following excerpts referenced invalid file paths.
                    Please provide corrected BRK_EXCERPT blocks with valid paths:

                    %s
                    """.formatted(errorList));

            history.add(retryMessage);
            var result = llm.sendRequest(history);
            if (result.error() != null) break;

            AiMessage aiResponse = new AiMessage(result.text(),
                    requireNonNullElse(requireNonNull(result.chatResponse()).reasoningContent(), ""));
            history.add(aiResponse);
            currentFiles.putAll(ReviewExcerptParser.instance.parseExcerptFiles(result.text()));
            currentContents.putAll(ReviewExcerptParser.instance.parseExcerptContents(result.text()));
        }

        Map<Integer, CodeExcerpt> out = new HashMap<>();
        currentFiles.forEach((id, f) -> {
            out.put(id, new CodeExcerpt(f, currentContents.getOrDefault(id, "")));
        });
        return out;
    }

    @Blocking
    Map<Integer, CodeExcerpt> retryExcerptNotFound(
            Llm llm,
            List<ChatMessage> turn1Messages,
            Llm.StreamingResult turn1Result,
            Map<Integer, CodeExcerpt> initialExcerpts) throws InterruptedException {

        Map<Integer, String> currentFiles = new HashMap<>();
        Map<Integer, String> currentContents = new HashMap<>();
        initialExcerpts.forEach((id, ce) -> {
            currentFiles.put(id, ce.file());
            currentContents.put(id, ce.excerpt());
        });

        List<ChatMessage> history = new ArrayList<>(turn1Messages);
        history.add(new AiMessage(turn1Result.text(),
                requireNonNullElse(requireNonNull(turn1Result.chatResponse()).reasoningContent(), "")));

        for (int i = 0; i < 2; i++) {
            Map<Integer, String> errors = new HashMap<>();
            for (var entry : currentContents.entrySet()) {
                if (!diff.contains(entry.getValue())) {
                    errors.put(entry.getKey(), "Excerpt not found in diff");
                }
            }
            if (errors.isEmpty()) break;

            List<ChatMessage> stage2Messages = new ArrayList<>();
            stage2Messages.add(buildSystemMessage());

            var filesToInclude = errors.keySet().stream()
                    .map(currentFiles::get)
                    .map(cm::toFile)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            Context filteredCtx = new Context(cm).addFragments(cm.toPathFragments(filesToInclude));
            stage2Messages.addAll(WorkspacePrompts.getMessagesInAddedOrder(filteredCtx, EnumSet.noneOf(SpecialTextType.class)));

            stage2Messages.addAll(history);

            String errorList = errors.entrySet().stream()
                    .map(e -> "- Excerpt " + e.getKey() + ": " + e.getValue())
                    .collect(java.util.stream.Collectors.joining("\n"));

            stage2Messages.add(new UserMessage("""
                    The following excerpts could not be found exactly in the diff.
                    Please provide corrected BRK_EXCERPT blocks that match the diff content exactly:

                    %s
                    """.formatted(errorList)));

            var result = llm.sendRequest(stage2Messages);
            if (result.error() != null) break;

            AiMessage aiResponse = new AiMessage(result.text(),
                    requireNonNullElse(requireNonNull(result.chatResponse()).reasoningContent(), ""));
            history.add(aiResponse);
            currentFiles.putAll(ReviewExcerptParser.instance.parseExcerptFiles(result.text()));
            currentContents.putAll(ReviewExcerptParser.instance.parseExcerptContents(result.text()));
        }

        Map<Integer, CodeExcerpt> out = new HashMap<>();
        currentFiles.forEach((id, f) -> {
            out.put(id, new CodeExcerpt(f, currentContents.getOrDefault(id, "")));
        });
        return out;
    }

    private SystemMessage buildSystemMessage() {
        return new SystemMessage("""
                You are an expert code reviewer. Your task is to analyze the proposed changes
                and identify the most important code excerpts that warrant discussion.
                
                Focus on:
                1. Design issues - architectural concerns, coupling, abstraction problems
                2. Tactical issues - local bugs, edge cases, error handling gaps
                3. Testing gaps - missing test coverage that would add significant value
                
                Be constructive and specific in your analysis.
                """);
    }

    private UserMessage buildAnalysisRequestMessage() {
        return new UserMessage("""
                Analyze the proposed changes in the diff against the gathered context.
                
                ### Step 1: Analysis
                Think step-by-step about the intent, design, and testing gaps:
                  - What is this code intended to do?
                  - Does it accomplish its goals in the simplest way possible?
                  - What parts are the trickiest and how could they be simplified?
                  - What additional tests, if any, would add the most value?
                
                ### Step 2: Code Excerpt Extraction
                Extract the subtle, tricky, or potentially incorrect blocks of code that you will
                reference in your review. Use this exact format, with sequentially numbered, 0-based IDs.
                IMPORTANT: Append @line_number to the filename to indicate where the excerpt starts.
                
                BRK_EXCERPT_0
                path/to/filename.java @42
                ```java
                // code here
                ```
                
                BRK_EXCERPT_1
                path/to/another_file.py @120
                ```python
                // code here
                ```
                
                Include ALL excerpts you plan to reference in your feedback.
                """);
    }

    private UserMessage buildReviewRequestMessage() {
        return new UserMessage("""
                Now call createReview to produce the final structured review.
                
                Reference the excerpts you extracted by their numeric ID (0, 1, 2, ...) in your
                designNotes and tacticalNotes fields.
                
                Remember:
                - overview: Explain what changes accomplish and whether they do so simply
                - designNotes: High-level architectural concerns with excerpt references
                - tacticalNotes: Local bugs/issues with excerpt references
                - additionalTests: High-value tests that should be added
                """);
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
