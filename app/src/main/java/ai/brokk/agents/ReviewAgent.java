package ai.brokk.agents;

import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.TaskEntry;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.cli.MemoryConsole;
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
import ai.brokk.util.WhitespaceMatch;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.ContextTooLargeException;
import dev.langchain4j.model.chat.request.ToolChoice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jspecify.annotations.NullMarked;

/**
 * ReviewAgent wraps SearchAgent to perform a guided code review on a specific diff.
 */
@NullMarked
public class ReviewAgent {
    private static final Logger logger = LogManager.getLogger(ReviewAgent.class);

    private final DiffService.CumulativeChanges changes;
    private final List<UUID> sessionIds;

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
            List<UUID> sessionIds,
            IContextManager cm,
            IConsoleIO io,
            List<FileComparisonInfo> fileComparisons) {
        this.changes = changes;
        this.sessionIds = List.copyOf(sessionIds);
        this.cm = cm;
        this.io = io;
        this.fileComparisons = fileComparisons;
    }

    @TestOnly
    ReviewAgent(IContextManager cm, IConsoleIO io, List<FileComparisonInfo> fileComparisons) {
        this(new DiffService.CumulativeChanges(0, 0, 0, List.of(), List.of()), List.of(), cm, io, fileComparisons);
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
                .collect(Collectors.joining("\n\n"));
        var diffFragment = new ContextFragments.StringFragment(
                cm, diff, "Proposed Changes (Diff)", SyntaxConstants.SYNTAX_STYLE_NONE);

        // Configure model types
        var architectModel = requireNonNull(cm.getService().getModel(ModelType.ARCHITECT.defaultConfig()));
        var scanModel = cm.getService().getModel(ModelType.SCAN);

        try (var scope = cm.anonymousScope()) {
            // Turn 0: Context setup and determine complexity
            Context initialContext = new Context(cm).addFragments(diffFragment).withPinned(diffFragment, true);
            var instructionsOpt = extractInstructionsFragment(sessionIds);
            if (instructionsOpt.isPresent()) {
                var instructionsFragment = instructionsOpt.get();
                initialContext = initialContext.addFragments(instructionsFragment).withPinned(instructionsFragment, true);
            }

            updateProgress("Gathering context", 0);
            long contextStart = System.currentTimeMillis();
            var setupResult = setupContext(initialContext);
            var reviewContext = setupResult.context();
            logPhaseTime("Context selection", contextStart);
            updateProgress("Analyzing changes", SETUP_PROGRESS);

            var turn1Model = setupResult.isComplex() ? architectModel : scanModel;
            var turn1Llm = cm.getLlm(turn1Model, "Code Review");
            turn1Llm.setOutput(io);

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
                MemoryConsole progressConsole = new MemoryConsole() {
                    @Override
                    public void llmOutput(
                            String token, ChatMessageType type, boolean explicitNewMessage, boolean isReasoning) {
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

                if (turn1Result.error() instanceof ContextTooLargeException) {
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

            // --- Excerpt Resolution: Retry for missing files and non-matching excerpts ---
            long retryStart = System.currentTimeMillis();
            RetryResult retryResult = retryInStages(turn1Llm, turn1Messages, turn1Result);
            Map<Integer, CodeExcerpt> resolvedExcerpts = retryResult.resolvedExcerpts();
            logPhaseTime("Excerpt resolution", retryStart);

            // --- Directly parse the Markdown review from Turn 1 (with fixes) ---
            String mergedReviewText = retryResult.mergedResponseText();
            String mergedReasoning = requireNonNullElse(
                    requireNonNull(turn1Result.chatResponse()).reasoningContent(), "");

            var review = ReviewParser.instance.parseMarkdownReview(mergedReviewText, resolvedExcerpts);

            logPhaseTime("Total review generation", startTime);

            var publishedMessages = List.of(
                    new UserMessage("Please review this diff"),
                    new AiMessage(ReviewParser.instance.stripExcerpts(mergedReviewText), mergedReasoning));
            var te = TaskEntry.from(cm, publishedMessages, "Guided Description");
            reviewContext = reviewContext.addHistoryEntry(te, null);

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
        var newMatches = WhitespaceMatch.findAll(newLines, excerptLines);
        if (!newMatches.isEmpty()) {
            var best = ReviewParser.findBestMatch(newMatches, excerpt.line());
            return new ExcerptMatch(best.startLine() + 1, ReviewParser.DiffSide.NEW, best.matchedText());
        }

        // Try OLD content
        String oldContent = fileInfo.leftSource().content();
        String[] oldLines = oldContent.split("\\R", -1);
        var oldMatches = WhitespaceMatch.findAll(oldLines, excerptLines);
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
                    .collect(Collectors.joining("\n"));

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
        Map<Integer, CodeExcerpt> matchedExcerpts = new ConcurrentHashMap<>();
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
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            if (textResolutionErrors.isEmpty() || attempts++ == 2) break;

            String errorList = textResolutionErrors.entrySet().stream()
                    .map(e -> "- Excerpt " + e.getKey() + ": " + e.getValue())
                    .collect(Collectors.joining("\n"));

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


    private UserMessage buildAnalysisRequestMessage() {
        return new UserMessage(
                """
                <instructions>
                Write a complete code review in Markdown of the proposed diff, informed by the gathered workspace context.
                This response will be MACHINE-PARSED. Exact conformance to the header levels and structure in <review_format> is CRITICAL.

                Your goal is to call attention to tricky, subtle, or simply incorrect choices in the diff.
                - Tactical notes: Simple issues localized to a single method or file.
                - Design notes: Higher level concerns (architectural issues, coupling, abstraction problems).
                - Design notes may have multiple BRK_EXCERPT blocks; tactical notes must have exactly one.
                </instructions>
                <excerpt_format>
                When referencing code, use BRK_EXCERPT blocks with a unique numeric ID. Do not use standard Markdown code fences for code being reviewed; use this specific format:

                BRK_EXCERPT_$N
                path/to/file.java @$line
                ```
                $code
                ```
                </excerpt_format>
                <review_format>
                ## Overview
                [One or more paragraphs describing what the changes accomplish and big-picture analysis]

                ## Design Notes
                ### [Title of first design note]
                [Description text. Use BRK_EXCERPT_$N markers inline to reference code.]
                **Recommendation:** [Detailed instructions for fixing the design issue. Must be actionable by a developer without further context.]

                ### [Title of second design note, etc.]
                ...

                ## Tactical Notes
                ### [Title of first tactical note]
                [Description text. Use exactly one BRK_EXCERPT_$N marker inline.]
                **Recommendation:** [Detailed instructions for the fix.]

                ## Additional Tests [Omit this if no additional tests are needed]
                - [Test description 1]
                - [Test description 2]
                - [etc]
                </review_format>
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

    @Blocking
    public Optional<ContextFragments.StringFragment> extractInstructionsFragment(
            List<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Optional.empty();
        }

        var sessionManager = cm.getProject().getSessionManager();

        // Extract instructions from all matching histories
        List<String> instructions = sessionIds.stream()
                .map(sessionId -> sessionManager.loadHistory(sessionId, cm))
                .filter(Objects::nonNull)
                .flatMap(h -> h.getHistory().stream())  // Stream<Context>
                .flatMap(ctx -> ctx.getTaskHistory().stream())  // Stream<TaskEntry>
                .filter(te -> te.log() != null)
                .sorted(Comparator.comparing(te -> requireNonNull(te.log()).id()))
                .map(te -> requireNonNull(te.log()).description().join())
                .filter(desc -> !desc.isBlank())
                .distinct()
                .toList();

        logger.debug("Extracted {} instruction fragments", instructions.size());

        if (instructions.isEmpty()) {
            return Optional.empty();
        }

        String mergedText = instructions.stream()
                .map(desc -> "- " + desc)
                .collect(Collectors.joining("\n"));

        return Optional.of(new ContextFragments.StringFragment(
                cm,
                mergedText,
                "User Instructions",
                SyntaxConstants.SYNTAX_STYLE_NONE));
    }

}
