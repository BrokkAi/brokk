package ai.brokk.agents;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.SpecialTextType;
import ai.brokk.difftool.ui.FileComparisonInfo;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.prompts.WorkspacePrompts;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.util.ReviewParser;
import ai.brokk.util.ReviewParser.CodeExcerpt;
import ai.brokk.util.ReviewParser.RawExcerpt;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * ReviewAgent wraps SearchAgent to perform a guided code review on a specific diff.
 */
@NullMarked
public class ReviewAgent {

    int progressOf100 = 0;
    @FunctionalInterface
    public interface ProgressUpdater {
        /**
         * @param progress value between 0 and 100
         */
        void updateProgress(int progress);
    }

    private final String diff;
    private final IContextManager cm;
    private final IConsoleIO io;
    private final List<FileComparisonInfo> fileComparisons;

    public ReviewAgent(String diff, IContextManager cm, IConsoleIO io, List<FileComparisonInfo> fileComparisons) {
        this.diff = diff;
        this.cm = cm;
        this.io = io;
        this.fileComparisons = fileComparisons;
    }

    private @Nullable ProgressUpdater progressUpdater;

    public void setProgressUpdater(@Nullable ProgressUpdater updater) {
        this.progressUpdater = updater;
    }

    @Blocking
    public ReviewParser.GuidedReview execute() throws InterruptedException, ReviewGenerationException {
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
            var searchTools = List.of(
                    "addSymbolUsagesToWorkspace",
                    "addClassesToWorkspace",
                    "addClassSummariesToWorkspace",
                    "addMethodsToWorkspace",
                    "addFileSummariesToWorkspace",
                    "addFilesToWorkspace");
            SearchAgent agent = new SearchAgent(initialContext, goal, model, scope, io, scanConfig, searchTools);
            java.util.concurrent.atomic.AtomicInteger searchTurnCount = new java.util.concurrent.atomic.AtomicInteger(0);
            agent.setTurnListener(() -> {
                int turns = searchTurnCount.incrementAndGet();
                updateProgress(Math.min(10, turns * 2));
            });

            // Phase 1: Establish context using SearchAgent
            TaskResult searchResult = agent.execute();

            if (searchResult.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
                throw new ReviewGenerationException("Review context gathering failed", searchResult.stopDetails());
            }

            // Phase 2: Two-turn review process
            var finalContext = searchResult.context().withHistory(List.of());
            var reviewLlm = cm.getLlm(new Llm.Options(model, "Code Review").withEcho());
            reviewLlm.setOutput(io);

            // --- Turn 1: Analyze the diff and extract code excerpts ---
            var turn1Messages = new ArrayList<ChatMessage>();
            turn1Messages.add(buildSystemMessage());
            turn1Messages.addAll(
                    WorkspacePrompts.getMessagesInAddedOrder(finalContext, EnumSet.noneOf(SpecialTextType.class)));
            turn1Messages.add(buildAnalysisRequestMessage());

            int turn1Floor = progressOf100;
            java.util.concurrent.atomic.AtomicInteger linesSeen = new java.util.concurrent.atomic.AtomicInteger(0);
            ai.brokk.cli.MemoryConsole progressConsole = new ai.brokk.cli.MemoryConsole() {
                @Override
                public void llmOutput(String token, dev.langchain4j.data.message.ChatMessageType type, boolean explicitNewMessage, boolean isReasoning) {
                    super.llmOutput(token, type, explicitNewMessage, isReasoning);
                    if (token.contains("\n") && progressUpdater != null) {
                        int lines = linesSeen.addAndGet((int) token.chars().filter(ch -> ch == '\n').count());
                        int p = turn1Floor + (lines / 10);
                        progressUpdater.updateProgress(Math.min(75, p));
                    }
                }
            };

            reviewLlm.setOutput(progressConsole);
            var turn1Result = reviewLlm.sendRequest(turn1Messages);
            if (turn1Result.error() != null) {
                throw new ReviewGenerationException("Failed to analyze diff for review", turn1Result.error());
            }

            // --- Turn 1.5: Retry for missing files and non-matching excerpts ---
            // Returns fully resolved excerpts with line numbers and sides
            Map<Integer, CodeExcerpt> resolvedExcerpts = retryInStages(reviewLlm, turn1Messages, turn1Result);

            // --- Turn 2: Generate structured review via tool call ---
            var turn2Messages = new ArrayList<ChatMessage>(turn1Messages);
            turn2Messages.add(new AiMessage(
                    turn1Result.text(),
                    requireNonNullElse(
                            requireNonNull(turn1Result.chatResponse()).reasoningContent(), "")));
            turn2Messages.add(buildReviewRequestMessage());

            var tr = cm.getToolRegistry().builder().register(this).build();
            var toolSpecs = tr.getTools(List.of("createReview"));

            int turn2Floor = progressOf100;
            javax.swing.Timer turn2Timer = new javax.swing.Timer(1000, null);
            java.util.concurrent.atomic.AtomicInteger turn2Seconds = new java.util.concurrent.atomic.AtomicInteger(0);
            turn2Timer.addActionListener(e -> {
                if (progressUpdater != null) {
                    int p = turn2Floor + turn2Seconds.incrementAndGet();
                    progressUpdater.updateProgress(Math.min(99, p));
                }
            });
            turn2Timer.start();

            Llm.StreamingResult turn2Result;
            try {
                turn2Result = reviewLlm.sendRequest(turn2Messages, new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr));
            } finally {
                turn2Timer.stop();
            }

            if (turn2Result.error() != null) {
                throw new ReviewGenerationException("Failed to generate code review", turn2Result.error());
            }
            if (turn2Result.toolRequests().isEmpty()) {
                throw new ReviewGenerationException("No review generated by model");
            }

            var reviewCall = turn2Result.toolRequests().getFirst();
            var executionResult = tr.executeTool(reviewCall);

            if (executionResult.status() != ToolExecutionResult.Status.SUCCESS) {
                throw new ReviewGenerationException("Failed to process code review: " + executionResult.resultText());
            }

            var rawReview = ReviewParser.RawReview.fromJson(executionResult.resultText());
            return ReviewParser.GuidedReview.fromRaw(
                    rawReview,
                    resolvedExcerpts.entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey, e -> e.getValue().excerpt())),
                    resolvedExcerpts.entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey, e -> e.getValue().file().toString())),
                    (file, content) -> {
                        var fileObj = (file != null) ? cm.toFile(file) : cm.toFile("unknown");
                        return resolvedExcerpts.values().stream()
                                .filter(e ->
                                        e.file().equals(fileObj) && e.excerpt().equals(content))
                                .findFirst()
                                .orElse(new ReviewParser.CodeExcerpt(
                                        fileObj,
                                        null,
                                        1, // Default to line 1 if unresolved
                                        ReviewParser.DiffSide.NEW,
                                        content));
                    });
        }
    }

    private void updateProgress(int min) {
        progressOf100 = min;
        if (progressUpdater != null) {
            progressUpdater.updateProgress(min);
        }
    }

    static @Nullable FileComparisonInfo findFileComparison(String relPath, List<FileComparisonInfo> fileComparisons) {
        return fileComparisons.stream()
                .filter(info ->
                        (info.file() != null && relPath.equals(info.file().toString()))
                                || relPath.equals(info.rightSource().filename())
                                || relPath.equals(info.leftSource().filename()))
                .findFirst()
                .orElse(null);
    }

    record ExcerptMatch(int line, ReviewParser.DiffSide side, String matchedText) {}

    static @Nullable ExcerptMatch matchExcerptInFile(RawExcerpt excerpt, FileComparisonInfo fileInfo) {
        String[] excerptLines = excerpt.excerpt().split("\\R", -1);

        // Try NEW content first
        String newContent = fileInfo.rightSource().content();
        String[] newLines = newContent.split("\\R", -1);
        var newMatches = ai.brokk.util.WhitespaceMatch.findAll(newLines, excerptLines);
        if (!newMatches.isEmpty()) {
            var best = ReviewParser.findBestMatch(newMatches, excerpt.line());
            return new ExcerptMatch(best.startLine() + 1, ReviewParser.DiffSide.NEW, best.matchedText());
        }

        // Try OLD content
        String oldContent = fileInfo.leftSource().content();
        String[] oldLines = oldContent.split("\\R", -1);
        var oldMatches = ai.brokk.util.WhitespaceMatch.findAll(oldLines, excerptLines);
        if (!oldMatches.isEmpty()) {
            var best = ReviewParser.findBestMatch(oldMatches, excerpt.line());
            return new ExcerptMatch(best.startLine() + 1, ReviewParser.DiffSide.OLD, best.matchedText());
        }

        return null;
    }

    boolean fileExists(String text) {
        try {
            return cm.toFile(text).exists();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Blocking
    Map<Integer, CodeExcerpt> retryInStages(Llm llm, List<ChatMessage> turn1Messages, Llm.StreamingResult turn1Result)
            throws InterruptedException {
        // we split up "validate filenames" and "validate text" into two stages so that
        // we can tailor the Context to each

        List<ChatMessage> history = new ArrayList<>(turn1Messages);
        Map<Integer, RawExcerpt> validPathExcerpts = new HashMap<>();

        // Stage 1: Validate file paths exist
        Llm.StreamingResult currentResult = turn1Result;
        int attempts = 0;

        while (true) {
            String text = currentResult.text();
            String reasoning = requireNonNullElse(
                    requireNonNull(currentResult.chatResponse()).reasoningContent(), "");
            history.add(new AiMessage(text, reasoning));

            Map<Integer, RawExcerpt> parsed = ReviewParser.instance.parseExcerpts(text);
            Map<Integer, String> stage1Errors = new HashMap<>();

            for (var entry : parsed.entrySet()) {
                RawExcerpt excerpt = entry.getValue();
                if (fileExists(excerpt.file())) {
                    validPathExcerpts.put(entry.getKey(), excerpt);
                } else {
                    stage1Errors.put(entry.getKey(), "File does not exist: " + excerpt.file());
                }
            }

            if (stage1Errors.isEmpty() || attempts++ == 2) break;

            String errorList = stage1Errors.entrySet().stream()
                    .map(e -> "- Excerpt " + e.getKey() + ": " + e.getValue())
                    .collect(java.util.stream.Collectors.joining("\n"));

            history.add(new UserMessage(
                    """
                    The following excerpts referenced unknown file paths.
                    Please provide corrected BRK_EXCERPT blocks with paths in the diff:

                    %s
                    """
                            .formatted(errorList)));

            currentResult = llm.sendRequest(history);
            if (currentResult.error() != null) break;
        }

        // Stage 2: Validate excerpts match file content
        Map<Integer, CodeExcerpt> resolvedExcerpts = new HashMap<>();
        attempts = 0;
        while (true) {
            Map<Integer, String> stage2Errors = new HashMap<>();
            for (var entry : validPathExcerpts.entrySet()) {
                int id = entry.getKey();
                if (resolvedExcerpts.containsKey(id)) continue;

                RawExcerpt excerpt = entry.getValue();
                FileComparisonInfo fileInfo = findFileComparison(excerpt.file(), fileComparisons);
                if (fileInfo == null) {
                    stage2Errors.put(id, "File not in diff: " + excerpt.file());
                    continue;
                }

                ExcerptMatch match = matchExcerptInFile(excerpt, fileInfo);
                if (match == null) {
                    stage2Errors.put(id, "Excerpt text not found in file content");
                } else {
                    var file = cm.toFile(excerpt.file());
                    int lineCount = (int) match.matchedText().lines().count();
                    @Nullable
                    CodeUnit unit = cm.getAnalyzerUninterrupted()
                            .enclosingCodeUnit(file, match.line(), match.line() + Math.max(0, lineCount - 1))
                            .orElse(null);

                    resolvedExcerpts.put(
                            id, new CodeExcerpt(file, unit, match.line(), match.side(), match.matchedText()));
                }
            }

            if (stage2Errors.isEmpty() || attempts++ == 2) break;

            String errorList = stage2Errors.entrySet().stream()
                    .map(e -> "- Excerpt " + e.getKey() + ": " + e.getValue())
                    .collect(java.util.stream.Collectors.joining("\n"));

            List<ChatMessage> stage2Messages = new ArrayList<>();
            stage2Messages.add(buildSystemMessage());

            var filesToInclude = stage2Errors.keySet().stream()
                    .map(validPathExcerpts::get)
                    .map(RawExcerpt::file)
                    .filter(this::fileExists)
                    .map(cm::toFile)
                    .distinct()
                    .toList();

            Context filteredCtx = new Context(cm).addFragments(cm.toPathFragments(filesToInclude));
            stage2Messages.addAll(
                    WorkspacePrompts.getMessagesInAddedOrder(filteredCtx, EnumSet.noneOf(SpecialTextType.class)));
            stage2Messages.addAll(history);
            stage2Messages.add(new UserMessage(
                    """
                    The following excerpts could not be matched in the file content.
                    Please provide corrected BRK_EXCERPT blocks:

                    %s
                    """
                            .formatted(errorList)));

            currentResult = llm.sendRequest(stage2Messages);
            if (currentResult.error() != null) break;

            String text = currentResult.text();
            String reasoning = requireNonNullElse(
                    requireNonNull(currentResult.chatResponse()).reasoningContent(), "");
            history.add(new AiMessage(text, reasoning));

            // Accumulate newly parsed excerpts into validPathExcerpts only if they pass file check
            Map<Integer, RawExcerpt> newlyParsed = ReviewParser.instance.parseExcerpts(text);
            for (var entry : newlyParsed.entrySet()) {
                if (fileExists(entry.getValue().file())) {
                    validPathExcerpts.put(entry.getKey(), entry.getValue());
                }
            }
        }

        return resolvedExcerpts;
    }

    private SystemMessage buildSystemMessage() {
        return new SystemMessage(
                """
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
        return new UserMessage(
                """
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
        return new UserMessage(
                """
                Now call createReview to produce the final structured review.

                For each item [except Overview], provide a `title` which is a short 4-5 word label summarizing the feedback,
                a `description` explaining the problem in detail, and
                a `recommendation` for remediation detailed enough to give to Code Agent.
                DesignFeedback may have multiple CodeExcerpts associated with it, while
                TacticalFeedback should each have a single CodeExcerpt.

                Reference the excerpts you extracted by their numeric ID (0, 1, 2, ...) in your
                designNotes and tacticalNotes fields.

                Use Markdown formatting in description and recommendation fields.

                Remember:
                - overview: Explain what changes accomplish and whether they do so simply
                - designNotes: High-level architectural concerns with excerpt references
                - tacticalNotes: Local bugs/issues with excerpt references
                - additionalTests: High-value tests that should be added

                Be opinionated in your recommendations: pick the best solution instead of giving multiple options.
                Your recommendations should include enough context that they can be added as standalone
                tasks for the Code Agent to execute.
                """);
    }

    @Tool("Create a structured code review of the current changes or proposal.")
    public String createReview(
            @P(
                            "Explain your understanding of what these changes are intended to accomplish. Does it accomplish its goals in the simplest way possible?")
                    String overview,
            @P(
                            """
                    Explain the trickiest parts of the design and how they can be improved.
                    Remember that you can give multiple excerpts per RawDesignNote!
                    """)
                    List<ReviewParser.RawDesignFeedback> designNotes,
            @P(
                            "A list of local bugs or problems. `recommendation` should be detailed enough to give to Code Agent for remediation.")
                    List<ReviewParser.RawTacticalFeedback> tacticalNotes,
            @P(
                            "A list of additional tests with high benefit:cost. For each item, provide a `title` (short 4-5 word label), a `description` explaining the test, and a `recommendation` detailed enough to give to Code Agent for implementation. Use Markdown formatting.")
                    List<ReviewParser.ReviewFeedback> additionalTests) {
        var review = new ReviewParser.RawReview(overview, designNotes, tacticalNotes, additionalTests);
        return review.toJson();
    }
}
