package ai.brokk.agents;

import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.TaskEntry;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.DiffService;
import ai.brokk.context.SpecialTextType;
import ai.brokk.difftool.ui.FileComparisonInfo;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.prompts.WorkspacePrompts;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.WorkspaceTools;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * ReviewAgent wraps SearchAgent to perform a guided code review on a specific diff.
 */
@NullMarked
public class ReviewAgent {
    private static final Logger logger = LogManager.getLogger(ReviewAgent.class);

    private final DiffService.CumulativeChanges changes;

    public record ReviewResult(ReviewParser.GuidedReview review, Context context) {}

    int progressOf100 = 0;
    int SETUP_PROGRESS = 5;
    int REVIEW_PROGRESS = 90;

    @FunctionalInterface
    public interface ProgressUpdater {
        /**
         * @param stage description of current stage
         * @param progress value between 0 and 100
         */
        void updateProgress(String stage, int progress);
    }

    private final IContextManager cm;
    private final IConsoleIO io;
    private final List<FileComparisonInfo> fileComparisons;
    private @Nullable Context contextBeingBuilt;
    private boolean isComplex = false;

    private record ContextSetupResult(Context context, boolean isComplex) {}

    public ReviewAgent(
            DiffService.CumulativeChanges changes,
            IContextManager cm,
            IConsoleIO io,
            List<FileComparisonInfo> fileComparisons) {
        this.changes = changes;
        this.cm = cm;
        this.io = io;
        this.fileComparisons = fileComparisons;
    }

    /**
     * Legacy constructor for tests or cases where only a raw diff string is available.
     */
    public ReviewAgent(String diff, IContextManager cm, IConsoleIO io, List<FileComparisonInfo> fileComparisons) {
        this(new DiffService.CumulativeChanges(0, 0, 0, List.of()), cm, io, fileComparisons);
    }

    private @Nullable ProgressUpdater progressUpdater;

    public void setProgressUpdater(@Nullable ProgressUpdater updater) {
        this.progressUpdater = updater;
    }

    @Blocking
    public ReviewResult execute() throws InterruptedException, ReviewGenerationException {
        long startTime = System.currentTimeMillis();

        // Prepare the initial context with the diff pinned
        var diff = changes.perFileChanges().stream()
                .map(de -> "File: " + de.title() + "\n" + de.diff())
                .collect(java.util.stream.Collectors.joining("\n\n"));
        var diffFragment = new ContextFragments.StringFragment(
                cm, diff, "Proposed Changes (Diff)", SyntaxConstants.SYNTAX_STYLE_NONE);

        // Configure model types
        var architectModel = requireNonNull(cm.getService().getModel(ModelType.ARCHITECT.defaultConfig()));
        var scanModel = cm.getService().getModel(ModelType.SCAN);

        try (var scope = cm.anonymousScope()) {
            // Turn 0: Context setup and determine complexity
            Context initialContext = new Context(cm).addFragments(diffFragment).withPinned(diffFragment, true);

            updateProgress("Gathering context", 0);
            long contextStart = System.currentTimeMillis();
            var setupResult = setupContext(initialContext);
            var reviewContext = setupResult.context();
            logPhaseTime("Context selection", contextStart);
            updateProgress("Analyzing changes", SETUP_PROGRESS);

            var turn1Model = setupResult.isComplex() ? architectModel : scanModel;
            var turn1Llm = cm.getLlm(turn1Model, "Code Review");
            turn1Llm.setOutput(io);

            var turn2Llm = cm.getLlm(scanModel, "Code Review (Structuring)");
            turn2Llm.setOutput(io);

            // --- Turn 1: Full Markdown review + excerpt extraction ---
            long turn1Start = System.currentTimeMillis();
            Llm.StreamingResult turn1Result;
            var turn1Messages = new ArrayList<ChatMessage>();
            while (true) {
                turn1Messages.clear();
                turn1Messages.add(buildSystemMessage());
                turn1Messages.addAll(
                        WorkspacePrompts.getMessagesInAddedOrder(reviewContext, EnumSet.noneOf(SpecialTextType.class)));
                turn1Messages.add(buildAnalysisRequestMessage());

                int turn1Floor = progressOf100;
                AtomicInteger linesSeen = new AtomicInteger(0);
                ai.brokk.cli.MemoryConsole progressConsole = new ai.brokk.cli.MemoryConsole() {
                    @Override
                    public void llmOutput(
                            String token,
                            dev.langchain4j.data.message.ChatMessageType type,
                            boolean explicitNewMessage,
                            boolean isReasoning) {
                        super.llmOutput(token, type, explicitNewMessage, isReasoning);
                        if (token.contains("\n")) {
                            int lines = linesSeen.addAndGet(
                                    (int) token.chars().filter(ch -> ch == '\n').count());
                            int p = turn1Floor + (lines / 10);
                            updateProgress("Analyzing changes", min(REVIEW_PROGRESS, p));
                        }
                    }
                };

                turn1Llm.setOutput(progressConsole);
                turn1Result = turn1Llm.sendRequest(turn1Messages);
                if (turn1Result.error() == null) {
                    break;
                }

                if (turn1Result.error() instanceof dev.langchain4j.exception.ContextTooLargeException) {
                    // Get non-diff fragments and their files
                    Context currentContext = reviewContext;
                    var nonDiffFragments = currentContext
                            .allFragments()
                            .filter(f -> !currentContext.isPinned(f))
                            .toList();

                    if (nonDiffFragments.isEmpty()) {
                        throw new ReviewGenerationException(
                                "Context too large even with no additional fragments", turn1Result.error());
                    }

                    // Reduce context by dropping the second half of additional fragments
                    int halfSize = Math.max(1, nonDiffFragments.size() / 2);
                    var fragmentsToKeep = nonDiffFragments.subList(0, halfSize);

                    // Rebuild context: start fresh, add diff fragment pinned, then add kept fragments
                    reviewContext = new Context(cm)
                            .addFragments(diffFragment)
                            .withPinned(diffFragment, true)
                            .addFragments(fragmentsToKeep);

                    logger.info(
                            "Context too large, reduced non-diff fragments from {} to {}",
                            nonDiffFragments.size(),
                            fragmentsToKeep.size());
                    continue;
                }

                // unrecoverable error
                throw new ReviewGenerationException("Failed to analyze diff for review", turn1Result.error());
            }
            logPhaseTime("Analysis (Turn 1)", turn1Start);

            // --- Turn 1.5: Retry for missing files and non-matching excerpts ---
            // Returns fully resolved excerpts with line numbers and sides
            long retryStart = System.currentTimeMillis();
            RetryResult retryResult = retryInStages(turn1Llm, turn1Messages, turn1Result);
            Map<Integer, CodeExcerpt> resolvedExcerpts = retryResult.resolvedExcerpts();
            logPhaseTime("Excerpt resolution (Turn 1.5)", retryStart);

            // --- Turn 2: Convert Markdown review into structured tool call (fresh context) ---
            long turn2Start = System.currentTimeMillis();
            String mergedReviewText = retryResult.mergedResponseText();
            String mergedReasoning = requireNonNullElse(
                    requireNonNull(turn1Result.chatResponse()).reasoningContent(), "");

            Context turn2Context = new Context(cm).addFragments(diffFragment).withPinned(diffFragment, true);

            var turn2Messages = new ArrayList<ChatMessage>();
            turn2Messages.add(buildTurn2SystemMessage());
            turn2Messages.addAll(
                    WorkspacePrompts.getMessagesInAddedOrder(turn2Context, EnumSet.noneOf(SpecialTextType.class)));
            turn2Messages.add(new AiMessage(mergedReviewText, mergedReasoning));
            turn2Messages.add(buildReviewRequestMessage());

            var tr = cm.getToolRegistry().builder().register(this).build();
            var toolSpecs = tr.getTools(List.of("createReview"));

            int turn2Floor = progressOf100;
            javax.swing.Timer turn2Timer = new javax.swing.Timer(1000, null);
            AtomicInteger turn2Seconds = new AtomicInteger(0);
            turn2Timer.addActionListener(e -> {
                int p = turn2Floor + turn2Seconds.incrementAndGet();
                updateProgress("Generating review", min(100, p));
            });
            turn2Timer.start();

            Llm.StreamingResult turn2Result;
            try {
                turn2Result = turn2Llm.sendRequest(turn2Messages, new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr));
            } finally {
                turn2Timer.stop();
            }

            if (turn2Result.error() != null) {
                throw new ReviewGenerationException("Failed to generate code review", turn2Result.error());
            }
            if (turn2Result.toolRequests().isEmpty()) {
                throw new ReviewGenerationException("No review generated by model");
            }

            // Build messages list for the task entry log
            turn2Messages.add(new AiMessage(
                    turn2Result.text(),
                    requireNonNullElse(
                            requireNonNull(turn2Result.chatResponse()).reasoningContent(), "")));
            // clunky way to get a context but that's what it looks like I guess
            var te = TaskEntry.from(cm, turn2Messages, "Guided Description");
            reviewContext = reviewContext.addHistoryEntry(te, null);

            var reviewCall = turn2Result.toolRequests().getFirst();
            var executionResult = tr.executeTool(reviewCall);

            if (executionResult.status() != ToolExecutionResult.Status.SUCCESS) {
                throw new ReviewGenerationException("Failed to process code review: " + executionResult.resultText());
            }

            var rawReview = ReviewParser.RawReview.fromJson(executionResult.resultText());
            var review = ReviewParser.GuidedReview.fromRaw(rawReview, resolvedExcerpts);

            logPhaseTime("Structuring (Turn 2)", turn2Start);
            logPhaseTime("Total review generation", startTime);

            return new ReviewResult(review, reviewContext);
        }
    }

    private void logPhaseTime(String phaseName, long startTimeMs) {
        double seconds = (System.currentTimeMillis() - startTimeMs) / 1000.0;
        logger.info("{} completed in {}s", phaseName, String.format("%.1f", seconds));
    }

    private @NotNull ContextSetupResult setupContext(Context initialContext) throws InterruptedException {
        var scanModel = cm.getService().getModel(ModelType.SCAN);
        var llm = cm.getLlm(scanModel, "Review Context Selection");
        llm.setOutput(io);

        var messages = new ArrayList<ChatMessage>();
        messages.add(buildSystemMessage());
        messages.addAll(
                WorkspacePrompts.getMessagesInAddedOrder(initialContext, EnumSet.noneOf(SpecialTextType.class)));
        messages.add(
                new UserMessage(
                        """
                Examine the proposed diff and identify additional context needed for a thorough code review.

                Examine the proposed diff and determine:
                1. Is this a complex change requiring deep architectural analysis, or a straightforward change?
                   - Complex: changes spanning multiple subsystems, introducing new abstractions, or with subtle correctness concerns
                   - Straightforward: localized changes, simple refactors, or changes with obvious correctness

                Then identify additional context needed for a thorough code review.

                Add files, summaries, classes, or methods that are:
                1. Referenced in or closely related to the changes
                2. Important for understanding correctness and design implications
                3. Difficult to infer from the diff alone

                Space is limited, so prioritize:
                - Use `filesForFullSource` only when you need complete implementation details
                - Use `filesForSummaries` when you only need API signatures
                - Use `classNames` for full class implementations
                - Use `methodNames` for specific method implementations (narrowest scope)

                Call addFragments once with all needed context.
                """));

        var tr = cm.getToolRegistry().builder().register(this).build();
        var toolSpecs = tr.getTools(List.of("addFragments"));

        this.contextBeingBuilt = initialContext;
        this.isComplex = false;
        var result = llm.sendRequest(messages, new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr));

        if (result.error() == null && !result.toolRequests().isEmpty()) {
            var toolRequest = result.toolRequests().getFirst();
            tr.executeTool(toolRequest);
            return new ContextSetupResult(requireNonNull(contextBeingBuilt).withHistory(List.of()), isComplex);
        }

        // Fallback
        var testFiles = cm.getTestFiles();
        var filesToContext = changes.perFileChanges().stream()
                .filter(de -> !testFiles.contains(
                        de.fragment().files().join().iterator().next()))
                .filter(de -> !de.oldContent().isEmpty() && !de.newContent().isEmpty())
                .filter(de -> de.diff().split("@@").length > 3) // > 2 hunks
                .map(DiffService.DiffEntry::fragment)
                .toList();
        var ctx = initialContext.addFragments(filesToContext);
        ctx = ctx.addFragments(ctx.buildAutoContext(10));
        return new ContextSetupResult(ctx, false);
    }

    private void updateProgress(String stage, int progress) {
        progressOf100 = progress;
        if (progressUpdater != null) {
            progressUpdater.updateProgress(stage, progress);
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

    record RetryResult(Map<Integer, CodeExcerpt> resolvedExcerpts, int retryCount, String mergedResponseText) {}

    @Blocking
    RetryResult retryInStages(Llm llm, List<ChatMessage> turn1Messages, Llm.StreamingResult turn1Result)
            throws InterruptedException {
        // we split up "file resolution" (resolving filenames) and "text resolution" (validating excerpt content)
        // into two stages so that we can tailor the Context to each

        List<ChatMessage> fileResolutionMessages = new ArrayList<>(turn1Messages);
        Map<Integer, RawExcerpt> validPathExcerpts = new HashMap<>();
        int totalRetries = 0;

        // Stage 1: File Resolution (Validate file paths exist)
        Llm.StreamingResult currentResult = turn1Result;
        int attempts = 0;

        while (true) {
            String text = currentResult.text();
            String reasoning = requireNonNullElse(
                    requireNonNull(currentResult.chatResponse()).reasoningContent(), "");
            fileResolutionMessages.add(new AiMessage(text, reasoning));

            Map<Integer, RawExcerpt> parsed = ReviewParser.instance.parseExcerpts(text);
            Map<Integer, String> fileResolutionErrors = new HashMap<>();

            for (var entry : parsed.entrySet()) {
                RawExcerpt excerpt = entry.getValue();
                if (fileExists(excerpt.file())) {
                    validPathExcerpts.put(entry.getKey(), excerpt);
                } else {
                    fileResolutionErrors.put(entry.getKey(), "File does not exist: " + excerpt.file());
                }
            }

            if (fileResolutionErrors.isEmpty() || attempts++ == 2) break;

            String errorList = fileResolutionErrors.entrySet().stream()
                    .map(e -> "- Excerpt " + e.getKey() + ": " + e.getValue())
                    .collect(java.util.stream.Collectors.joining("\n"));

            boolean someSucceeded = !validPathExcerpts.isEmpty();
            String successNote = someSucceeded
                    ? " for ONLY these excerpts.\nAll other excerpts have been recorded successfully and do not need to be repeated."
                    : ":";

            fileResolutionMessages.add(new UserMessage(
                    """
                    The following excerpts referenced unknown file paths.
                    Please provide corrected BRK_EXCERPT blocks%s

                    %s
                    """
                            .formatted(successNote, errorList)));

            currentResult = llm.sendRequest(fileResolutionMessages);
            totalRetries++;
            if (currentResult.error() != null) break;
        }

        // Build merged text by replacing excerpts in original with corrected versions
        String mergedResponseText = buildMergedResponseText(turn1Result.text(), validPathExcerpts);

        // Stage 2: Text Resolution (Validate excerpts match file content)
        Map<Integer, CodeExcerpt> matchedExcerpts = new java.util.concurrent.ConcurrentHashMap<>();
        List<ChatMessage> textResolutionMessages = new ArrayList<>();
        attempts = 0;
        while (true) {
            Map<Integer, String> textResolutionErrors = validPathExcerpts.entrySet().parallelStream()
                    .filter(entry -> !matchedExcerpts.containsKey(entry.getKey()))
                    .map(entry -> {
                        int id = entry.getKey();
                        RawExcerpt excerpt = entry.getValue();
                        FileComparisonInfo fileInfo = findFileComparison(excerpt.file(), fileComparisons);
                        if (fileInfo == null) {
                            return Map.entry(id, "File not in diff: " + excerpt.file());
                        }

                        ExcerptMatch match = matchExcerptInFile(excerpt, fileInfo);
                        if (match == null) {
                            return Map.entry(id, "Excerpt text not found in " + excerpt.file());
                        } else {
                            var file = cm.toFile(excerpt.file());
                            int lineCount = (int) match.matchedText().lines().count();
                            CodeUnit unit = cm.getAnalyzerUninterrupted()
                                    .enclosingCodeUnit(file, match.line(), match.line() + Math.max(0, lineCount - 1))
                                    .orElse(null);
                            logger.debug("Enclosing CodeUnit for excerpt {} is {}", id, unit);
                            matchedExcerpts.put(
                                    id, new CodeExcerpt(file, unit, match.line(), match.side(), match.matchedText()));
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            if (textResolutionErrors.isEmpty() || attempts++ == 2) break;

            String errorList = textResolutionErrors.entrySet().stream()
                    .map(e -> "- Excerpt " + e.getKey() + ": " + e.getValue())
                    .collect(java.util.stream.Collectors.joining("\n"));

            var filesToInclude = textResolutionErrors.keySet().stream()
                    .map(validPathExcerpts::get)
                    .map(RawExcerpt::file)
                    .filter(this::fileExists)
                    .map(cm::toFile)
                    .distinct()
                    .toList();

            Context filteredCtx = new Context(cm).addFragments(cm.toPathFragments(filesToInclude));

            // Build messages: system, turn1 prompt, merged result as AiMessage, workspace, text resolution prompt
            textResolutionMessages.clear();
            textResolutionMessages.add(buildSystemMessage());
            textResolutionMessages.add(buildAnalysisRequestMessage());
            textResolutionMessages.add(new AiMessage(mergedResponseText));
            textResolutionMessages.addAll(
                    WorkspacePrompts.getMessagesInAddedOrder(filteredCtx, EnumSet.noneOf(SpecialTextType.class)));

            boolean someMatched = !matchedExcerpts.isEmpty();
            String successNote = someMatched
                    ? " for ONLY these excerpts.\nAll other excerpts have been recorded successfully and do not need to be repeated."
                    : ":";

            textResolutionMessages.add(new UserMessage(
                    """
                    The following excerpts could not be matched in the file content.
                    Please provide corrected BRK_EXCERPT blocks%s

                    %s
                    """
                            .formatted(successNote, errorList)));

            currentResult = llm.sendRequest(textResolutionMessages);
            totalRetries++;
            if (currentResult.error() != null) break;

            String text = currentResult.text();

            // Accumulate newly parsed excerpts into validPathExcerpts only if they pass file check
            Map<Integer, RawExcerpt> newlyParsed = ReviewParser.instance.parseExcerpts(text);
            for (var entry : newlyParsed.entrySet()) {
                if (fileExists(entry.getValue().file())) {
                    validPathExcerpts.put(entry.getKey(), entry.getValue());
                }
            }

            // Update merged text with newly fixed excerpts
            mergedResponseText = buildMergedResponseText(turn1Result.text(), validPathExcerpts);
        }

        return new RetryResult(matchedExcerpts, totalRetries, mergedResponseText);
    }

    private String buildMergedResponseText(String originalText, Map<Integer, RawExcerpt> correctedExcerpts) {
        List<ReviewParser.Segment> segments = ReviewParser.instance.parseToSegments(originalText);
        logger.debug("Original response segments: {}", segments);

        List<ReviewParser.Segment> mergedSegments = new ArrayList<>();
        for (var segment : segments) {
            if (segment instanceof ReviewParser.ExcerptSegment excerptSeg) {
                RawExcerpt corrected = correctedExcerpts.get(excerptSeg.id());
                if (corrected != null) {
                    mergedSegments.add(new ReviewParser.ExcerptSegment(
                            excerptSeg.id(), corrected.file(), corrected.line(), corrected.excerpt()));
                } else {
                    mergedSegments.add(segment);
                }
            } else {
                mergedSegments.add(segment);
            }
        }
        logger.debug("Merged response segments: {}", segments);

        return ReviewParser.instance.serializeSegments(mergedSegments);
    }

    private SystemMessage buildSystemMessage() {
        return new SystemMessage(
                """
                You are an expert code reviewer. Your task is to review the proposed changes based on the diff and the gathered workspace context.

                Focus on:
                1. Design issues - architectural concerns, coupling, abstraction problems
                2. Tactical issues - local bugs, edge cases, error handling gaps
                3. Testing gaps - missing test coverage that would add significant value

                Be constructive and specific.
                """);
    }

    private SystemMessage buildTurn2SystemMessage() {
        return new SystemMessage(
                """
                You are a review transcription assistant.

                You will be given:
                - The proposed diff
                - A complete Markdown code review produced by a separate (smarter) model, including BRK_EXCERPT blocks

                Your job is ONLY to convert that existing Markdown review into a single createReview tool call.
                Do NOT editorialize, expand, paraphrase, add new findings, remove findings, or change priorities.
                Preserve the wording and intent of the provided review exactly.

                The only exception: where the Markdown review contains excerpt blocks like BRK_EXCERPT_3,
                remove block (including the BRK_EXCERPT_ marker) and supply the numeric IDs (e.g., 3) in the tool call excerpts fields.
                """);
    }

    private UserMessage buildAnalysisRequestMessage() {
        return new UserMessage(
                """
                <instructions>
                Write a complete code review in Markdown of the proposed diff, informed by the gathered workspace context.

                Your goal is to call attention to tricky, subtle, or simply incorrect choices in the diff.

                Tactical notes are simple issues localized to a single method.

                Design notes should focus on a specific higher level area of concern, such as architectural issues,
                coupling, abstraction problems),

                Design notes may have multiple excerpts highlighting the most important parts of the issue; tactical notes should have exactly one.
                </instructions>
                <excerpt_format>
                When referencing code, use BRK_EXCERPT blocks with a unique numeric ID:

                BRK_EXCERPT_$N
                path/to/file.java @$line
                ```
                $code
                ```
                </excerpt_format>
                <review_format>
                ## Overview
                [What the changes accomplish and big-picture analysis]

                ## Design Notes
                ### [Title]
                [Description with multiple inline BRK_EXCERPT blocks]

                **Recommendation:** [What to change, if anything. If included, recommendation must be detailed enough to give to Code Agent as a task, without needing to refer to this diff.]

                ## Tactical Notes
                ### [Title]
                [Description with a single inline BRK_EXCERPT block]

                **Recommendation:** [What to fix. Must be detailed enough to give to Code Agent as a task, without needing to refer to this diff]

                ## Additional Tests
                - [Test descriptions in a bulleted list]
                </review_format>
                """);
    }

    private UserMessage buildReviewRequestMessage() {
        return new UserMessage(
                """
                Convert the provided Markdown review into a single createReview tool call.

                Critical rules:
                - Do NOT editorialize or alter the review content.
                - Do NOT add new findings, remove findings, or change priorities.
                - Preserve titles, descriptions, recommendations, and test suggestions as closely as possible (copy, do not rewrite).
                - Only perform the minimal transformation required to populate createReview fields.

                Excerpt handling:
                The input review contains BRK_EXCERPT_$id blocks embedded inline within Design Notes and Tactical Notes. These
                must be referenced by id in the review parameters. DO NOT INCLUDE raw excerpt blocks (including the BRK_EXCERPT markers)
                anywhere in your createReview tool call.

                Mapping rules:
                - Use the Markdown "## Overview" content as the createReview `overview` argument.
                - Each "### <Title>" under "## Design Notes" becomes one RawDesignFeedback with:
                  - title: the "### <Title>" text
                  - description: the "Description:" content (with BRK_EXCERPT blocks replaced by BRK_EXCERPT_N references)
                  - recommendation: the "Recommendation:" content
                  - excerptIds: list of the numeric IDs assigned to excerpts that were embedded in this note
                - Each "### <Title>" under "## Tactical Notes" becomes one RawTacticalFeedback with:
                  - title: the "### <Title>" text
                  - description: the "Description:" content (with BRK_EXCERPT block replaced by BRK_EXCERPT_N reference)
                  - recommendation: the "Recommendation:" content
                  - excerptId: the numeric ID assigned to the single excerpt embedded in this note
                - Use the Markdown "## Additional Tests" bullet list as `additionalTests` (one string per bullet).

                Now call createReview.
                """);
    }

    @Tool(
            "Add context fragments to help perform a thorough code review. Call this once with all the files, summaries, classes, and methods needed.")
    public String addFragments(
            @P(
                            "True if this is a complex change requiring deep architectural analysis; false for straightforward changes")
                    boolean isComplex,
            @P("Full project paths for files where you need to see complete implementation details")
                    List<String> filesForFullSource,
            @P("Full project paths for files where you only need to see API signatures/skeletons")
                    List<String> filesForSummaries,
            @P(
                            "Fully qualified class names (e.g., 'com.example.MyClass') for classes where you need implementation details")
                    List<String> classNames,
            @P(
                            "Fully qualified method names (e.g., 'com.example.MyClass.myMethod') for specific methods you need to examine")
                    List<String> methodNames) {
        this.isComplex = isComplex;
        var wst = new WorkspaceTools(requireNonNull(contextBeingBuilt));
        wst.addFilesToWorkspace(filesForFullSource);
        wst.addFileSummariesToWorkspace(filesForSummaries);
        wst.addClassesToWorkspace(classNames);
        wst.addMethodsToWorkspace(methodNames);

        this.contextBeingBuilt = wst.getContext();
        return "Context updated with requested fragments.";
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
            @P("A list of local bugs or problems.") List<ReviewParser.RawTacticalFeedback> tacticalNotes,
            @P(
                            "A list of additional tests that would add significant value. Each string should describe a test to add, referencing specific methods or classes.")
                    List<String> additionalTests) {
        var review = new ReviewParser.RawReview(overview, designNotes, tacticalNotes, additionalTests);
        return review.toJson();
    }
}
