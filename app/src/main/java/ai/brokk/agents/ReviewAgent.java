package ai.brokk.agents;

import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

import ai.brokk.AbstractService;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.LlmOutputMeta;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.cli.MemoryConsole;
import ai.brokk.concurrent.LoggingFuture;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.DiffService.CumulativeChanges;
import ai.brokk.context.SpecialTextType;
import ai.brokk.git.GitRepoData.FileDiff;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.prompts.WorkspacePrompts;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.WorkspaceTools;
import ai.brokk.util.ContentDiffUtils;
import ai.brokk.util.ReviewParser;
import ai.brokk.util.ReviewParser.CodeExcerpt;
import ai.brokk.util.ReviewParser.RawExcerpt;
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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

    private final CumulativeChanges changes;
    private final ReviewScope.Metadata metadata;

    public record ReviewResult(ReviewParser.GuidedReview review, Context context) {}

    int progressOf100 = 0;
    int SETUP_PROGRESS = 5;

    @FunctionalInterface
    public interface ProgressUpdater {
        /**
         * @param stage description of current stage
         * @param progress value between 0 and 100
         */
        void updateProgress(String stage, int progress);
    }

    private final AbstractService.ModelConfig modelConfig;
    private final boolean optimizeForLatency;
    private final IContextManager cm;
    private @Nullable Context contextBeingBuilt;
    private boolean isComplex = false;

    private record ContextSetupResult(Context context, boolean isComplex) {}

    /**
     * Intended usage to review the changes on the current branch from `commit` [exclusive] to HEAD [inclusive]:
     * var scope = ReviewScope.fromBaseline(commit, cm);
     * var result = new ReviewAgent(scope, preferredModel, false, cm).execute();
     *
     * An example of determining `commit` using GitRepo::getMergeBase is in SessionChangesPanel.
     */
    public ReviewAgent(
            ReviewScope scope,
            AbstractService.ModelConfig modelConfig,
            boolean optimizeForLatency,
            IContextManager cm) {
        this.changes = scope.changes();
        this.metadata = scope.metadata();
        this.modelConfig = modelConfig;
        this.optimizeForLatency = optimizeForLatency;
        this.cm = cm;
    }

    @TestOnly
    ReviewAgent(CumulativeChanges changes, List<UUID> sessionIds, IContextManager cm) {
        this(
                new ReviewScope(changes, new ReviewScope.Metadata("HEAD~1", "HEAD", sessionIds)),
                ModelType.ARCHITECT.defaultConfig(),
                true,
                cm);
    }

    private @Nullable ProgressUpdater progressUpdater;

    /**
     * Optional
     */
    public void setProgressUpdater(@Nullable ProgressUpdater updater) {
        this.progressUpdater = updater;
    }

    @Blocking
    public ReviewResult execute() throws InterruptedException, ReviewGenerationException {
        long startTime = System.currentTimeMillis();

        // Prepare the initial context with the diff pinned
        String diff = changes.toDiff();
        var diffFragment = SpecialTextType.REVIEW_DIFF.create(cm, diff);

        try (var scope = cm.beginTask("Code Review", true, false, "Performing code review")) {
            // Turn 0: Context setup and determine complexity
            Context initialContext = new Context(cm)
                    .addFragments(diffFragment)
                    .addFragments(extractSessionContext(metadata.sessionIds()));

            updateProgress("Gathering context", 0);
            long contextStart = System.currentTimeMillis();
            var setupResult = setupContext(initialContext);
            var reviewContext = setupResult.context();
            logPhaseTime("Context selection", contextStart);
            updateProgress("Analyzing changes", SETUP_PROGRESS);

            // Publish the context as it stands after setup but before Turn 1
            scope.publish(reviewContext);

            var turn1ModelConfig = (optimizeForLatency && !setupResult.isComplex())
                    ? cm.getProject().getModelConfig(ModelType.SCAN)
                    : modelConfig;
            var turn1Model = requireNonNull(cm.getService().getModel(turn1ModelConfig));
            var turn1Llm = cm.getLlm(new Llm.Options(turn1Model, "Code Review", TaskResult.Type.REVIEW).withEcho());

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
                    public void llmOutput(String token, ChatMessageType type, LlmOutputMeta meta) {
                        super.llmOutput(token, type, meta);
                        if (token.contains("\n")) {
                            int lines = linesSeen.addAndGet(
                                    (int) token.chars().filter(ch -> ch == '\n').count());
                            int p = turn1Floor + (lines / 6);
                            updateProgress("Analyzing changes", min(100, p));
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

                    logger.debug(
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
                    new UserMessage("Please review this diff"), new AiMessage(mergedReviewText, mergedReasoning));

            Context contextWithMeta =
                    reviewContext.addFragments(SpecialTextType.REVIEW_METADATA.create(cm, metadata.toJson()));
            var result = new TaskResult(
                    cm,
                    "Code Review",
                    publishedMessages,
                    contextWithMeta,
                    new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS),
                    new TaskResult.TaskMeta(TaskResult.Type.REVIEW, turn1ModelConfig));
            var finalContext = scope.append(result);

            return new ReviewResult(review, finalContext);
        }
    }

    private void logPhaseTime(String phaseName, long startTimeMs) {
        double seconds = (System.currentTimeMillis() - startTimeMs) / 1000.0;
        logger.debug("{} completed in {}s", phaseName, String.format("%.1f", seconds));
    }

    private @NotNull ContextSetupResult setupContext(Context initialContext) throws InterruptedException {
        var model = requireNonNull(cm.getService()
                .getModel(optimizeForLatency ? cm.getProject().getModelConfig(ModelType.SCAN) : modelConfig));
        var llm = cm.getLlm(model, "Review Context Selection", TaskResult.Type.REVIEW);

        Set<Language> analyzerLanguages = cm.getProject().getAnalyzerLanguages();
        boolean hasAnalyzedLanguage = !analyzerLanguages.equals(Set.of(Languages.NONE));
        var wst = new WorkspaceTools(initialContext);
        var trBuilder = cm.getToolRegistry().builder();
        List<String> toolNames;

        if (hasAnalyzedLanguage) {
            trBuilder.register(this);
            toolNames = List.of("addReviewFragments");
        } else {
            trBuilder.register(wst);
            toolNames = List.of("addFilesToWorkspace");
        }

        var tr = trBuilder.build();
        var toolSpecs = tr.getTools(toolNames);

        var messages = new ArrayList<ChatMessage>();
        messages.add(buildSystemMessage());
        messages.addAll(
                WorkspacePrompts.getMessagesInAddedOrder(initialContext, EnumSet.noneOf(SpecialTextType.class)));

        String toolInstructions = hasAnalyzedLanguage
                ? """
                You can get API signatures when you do not need full implementation details using `filesForSummaries`.

                You can get complete implementation details with any of the following; use the narrowest scope that you need:
                - Use `methodNames` for specific method implementations (narrowest scope)
                - Use `classNames` for full class implementations
                - Use `filesForFullSource` only when you need the entire file

                Call addReviewFragments once with all needed context.
                """
                : "Identify full project paths for files you need to examine and call `addFilesToWorkspace`. Be judicious and only include files that contain important information you cannot infer from the diff.";

        String analyzerWarning = "";
        if (hasAnalyzedLanguage) {
            boolean allFilesAnalyzed = changes.perFileChanges().stream()
                    .flatMap(fd -> Stream.of(fd.oldFile(), fd.newFile()))
                    .filter(Objects::nonNull)
                    .allMatch(f -> analyzerLanguages.contains(Languages.fromExtension(f.extension())));

            if (!allFilesAnalyzed) {
                String supportedTypes =
                        analyzerLanguages.stream().map(Language::name).sorted().collect(Collectors.joining(", "));
                analyzerWarning =
                        """

                        Note: The code-aware inspection tools only support the following languages: %s.
                        For other file types, you must use `filesForFullSource` to get context. Be judicious and only include full files that contain important information you cannot infer from the diff.
                        """
                                .formatted(supportedTypes);
            }
        }

        messages.add(new UserMessage(
                """
                Examine the proposed diff and identify additional context needed for a thorough code review.

                Examine the proposed diff and determine the complexity level:
                1. TRIVIAL: very small changes, documentation updates, or simple boilerplate with no logic risk.
                2. STRAIGHTFORWARD: localized changes, simple refactors, easy to see if bugs were introduced.
                3. COMPLEX: changes spanning multiple subsystems, introducing new abstractions, or with subtle correctness concerns.

                Then identify additional context needed for a thorough code review. You will have access to the full
                diff during the review, so only add code that you need to perform the review, that you cannot infer from the diff alone.
                %s
                %s
                """
                        .formatted(analyzerWarning, toolInstructions)));

        this.contextBeingBuilt = initialContext;
        this.isComplex = false;
        var result = llm.sendRequest(messages, new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr));

        if (result.error() == null && !result.toolRequests().isEmpty()) {
            var toolRequest = result.toolRequests().getFirst();
            var toolResult = tr.executeTool(toolRequest);
            if (toolResult.status().equals(ToolExecutionResult.Status.SUCCESS)) {
                var completedContext = hasAnalyzedLanguage ? this.contextBeingBuilt : wst.getContext();
                return new ContextSetupResult(requireNonNull(completedContext).withHistory(List.of()), isComplex);
            } else {
                logger.warn("Tool execution failed: {}", toolResult);
            }
        } else {
            logger.warn("Tool request failed", result.error());
        }

        // Fallback
        var testFiles = cm.getTestFiles();
        var filesToContext = changes.perFileChanges().stream()
                .filter(fd -> {
                    var file = fd.newFile() != null ? fd.newFile() : fd.oldFile();
                    return file != null && !testFiles.contains(file);
                })
                .filter(fd -> !fd.oldText().isEmpty() && !fd.newText().isEmpty())
                .filter(fd -> {
                    var diffRes = ContentDiffUtils.computeDiffResult(fd.oldText(), fd.newText(), "old", "new");
                    return diffRes.diff().split("@@").length > 3; // > 2 hunks
                })
                .map(fd -> {
                    var file = requireNonNull(fd.newFile() != null ? fd.newFile() : fd.oldFile());
                    return new ContextFragments.GitFileFragment(file, "WORKING", fd.newText());
                })
                .collect(Collectors.toList());
        var ctx = initialContext.addFragments(filesToContext);
        ctx = ctx.addFragments(ctx.buildAutoContext(10));
        return new ContextSetupResult(ctx, true);
    }

    private void updateProgress(String stage, int progress) {
        progressOf100 = progress;
        if (progressUpdater != null) {
            progressUpdater.updateProgress(stage, progress);
        }
    }

    static @Nullable FileDiff findFileDiff(String relPath, List<FileDiff> fileDiffs) {
        return fileDiffs.stream()
                .filter(fd -> (fd.oldFile() != null
                                && relPath.equals(fd.oldFile().toString()))
                        || (fd.newFile() != null && relPath.equals(fd.newFile().toString())))
                .findFirst()
                .orElse(null);
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
            throws InterruptedException, ReviewGenerationException {
        // Stage 0: Note Validation (ensure required structure like Recommendations)
        String currentResponseText = turn1Result.text();
        var validationErrors = ReviewParser.instance.validateParsedNotes(currentResponseText);
        if (!validationErrors.isEmpty()) {
            currentResponseText = correctFailedNotes(llm, currentResponseText, validationErrors);
        }

        List<ChatMessage> retryFileMessages = new ArrayList<>(turn1Messages);
        Map<Integer, RawExcerpt> validPathExcerpts = new HashMap<>();
        int totalRetries = 0;

        // Stage 1: File Resolution (Validate file paths exist)
        Llm.StreamingResult currentResult = turn1Result;
        final int MAX_ATTEMPTS = 3;
        int stage1Attempts = 0;

        // Parse initial excerpts
        String textToValidate = currentResponseText;
        List<RawExcerpt> parsedList = ReviewParser.instance.parseExcerpts(textToValidate);
        Map<Integer, String> pendingFileErrors = new HashMap<>();

        // Initial pass: identify valid and invalid file paths
        for (int i = 0; i < parsedList.size(); i++) {
            RawExcerpt excerpt = parsedList.get(i);
            if (fileExists(excerpt.file())) {
                validPathExcerpts.put(i, excerpt);
            } else {
                pendingFileErrors.put(i, "File does not exist: " + excerpt.file());
            }
        }

        // Retry loop for file resolution
        while (!pendingFileErrors.isEmpty() && stage1Attempts < MAX_ATTEMPTS) {
            String errorList = pendingFileErrors.entrySet().stream()
                    .map(e -> "Excerpt " + e.getKey() + ": " + e.getValue())
                    .collect(Collectors.joining("\n"));

            String reasoning = (currentResult.chatResponse() != null)
                    ? requireNonNullElse(currentResult.chatResponse().reasoningContent(), "")
                    : "";

            String taggedText =
                    "[HARNESS NOTE: some code excerpts in this message were invalid or could not be matched. "
                            + "Your code excerpts have been tagged with [Excerpt N] markers below to help you identify and fix them.]\n\n"
                            + ReviewParser.instance.tagExcerpts(textToValidate);

            retryFileMessages.add(new AiMessage(taggedText, reasoning));
            retryFileMessages.add(new UserMessage(
                    """
                    The following excerpts referenced unknown file paths.
                    Please provide corrected excerpts for ONLY these excerpts.
                    All other excerpts have been recorded successfully and do not need to be repeated.

                    %s

                    Use this format:
                    Excerpt 0:
                    At `path/to/file.java` line 42:
                    ```
                    corrected code
                    ```
                    """
                            .formatted(errorList)));

            currentResult = llm.sendRequest(retryFileMessages);
            totalRetries++;
            if (currentResult.error() != null) {
                throw new ReviewGenerationException("LLM error during file resolution retry", currentResult.error());
            }

            String resultText = currentResult.text();
            Map<Integer, RawExcerpt> newlyFixed = ReviewParser.instance.parseNumberedExcerpts(resultText);

            Set<Integer> unexpectedIndices = newlyFixed.keySet().stream()
                    .filter(id -> !pendingFileErrors.containsKey(id))
                    .collect(Collectors.toSet());
            if (!unexpectedIndices.isEmpty()) {
                logger.warn(
                        "Discarding Stage 1 retry turn: unrequested excerpt corrections returned: {}",
                        unexpectedIndices);
                stage1Attempts++;
                continue;
            }

            // Apply corrections to excerpts, but only commit if merging keeps excerpt structure stable.
            Map<Integer, RawExcerpt> candidateValidPathExcerpts = new HashMap<>(validPathExcerpts);
            for (var entry : newlyFixed.entrySet()) {
                int id = entry.getKey();
                RawExcerpt fixed = entry.getValue();
                if (pendingFileErrors.containsKey(id) && fileExists(fixed.file())) {
                    candidateValidPathExcerpts.put(id, fixed);
                }
            }

            String candidateMerged = buildMergedResponseText(textToValidate, candidateValidPathExcerpts);
            if (ReviewParser.instance.parseExcerpts(candidateMerged).size() != parsedList.size()) {
                logger.warn("Discarding Stage 1 retry turn: excerpt structure changed after applying LLM corrections");
            } else {
                validPathExcerpts = candidateValidPathExcerpts;
                for (var entry : newlyFixed.entrySet()) {
                    int id = entry.getKey();
                    RawExcerpt fixed = entry.getValue();
                    if (pendingFileErrors.containsKey(id) && fileExists(fixed.file())) {
                        pendingFileErrors.remove(id);
                    }
                }
                textToValidate = candidateMerged;
            }

            stage1Attempts++;
        }

        // If we still have pending file errors after exhausting retries, log but continue
        // (Some excerpts may be unfixable, but we can still process the valid ones)
        if (!pendingFileErrors.isEmpty()) {
            logger.warn(
                    "Could not resolve file paths for excerpts after {} retries: {}",
                    MAX_ATTEMPTS,
                    pendingFileErrors.keySet());
        }

        // Build merged text by replacing excerpts in current text with corrected versions
        String mergedResponseText = buildMergedResponseText(currentResponseText, validPathExcerpts);
        if (ReviewParser.instance.parseExcerpts(mergedResponseText).size() != parsedList.size()) {
            logger.warn("Discarding excerpt merge: excerpt structure changed after Stage 1 resolution");
            mergedResponseText = currentResponseText;
        }

        // Stage 2: Text Resolution (Validate excerpts match file content)
        Map<Integer, CodeExcerpt> matchedExcerpts = new ConcurrentHashMap<>();
        Map<Integer, String> pendingTextErrors = new HashMap<>();
        int stage2Attempts = 0;

        // Initial pass: try to match all valid path excerpts
        for (var entry : validPathExcerpts.entrySet()) {
            int id = entry.getKey();
            RawExcerpt excerpt = entry.getValue();
            FileDiff fileDiff = findFileDiff(excerpt.file(), changes.perFileChanges());
            if (fileDiff == null) {
                pendingTextErrors.put(id, "File not in diff: " + excerpt.file());
                continue;
            }

            ReviewParser.ExcerptMatch match = ReviewParser.matchExcerptInFile(excerpt, fileDiff);
            if (match == null) {
                pendingTextErrors.put(id, "Excerpt text not found in " + excerpt.file());
            } else {
                var file = cm.toFile(excerpt.file());
                int lineCount = (int) match.matchedText().lines().count();
                CodeUnit unit = cm.getAnalyzerUninterrupted()
                        .enclosingCodeUnit(file, match.line(), match.line() + Math.max(0, lineCount - 1))
                        .orElse(null);
                matchedExcerpts.put(id, new CodeExcerpt(file, unit, match.line(), match.side(), match.matchedText()));
            }
        }

        // Retry loop for text resolution
        while (!pendingTextErrors.isEmpty() && stage2Attempts < MAX_ATTEMPTS) {
            String errorList = pendingTextErrors.entrySet().stream()
                    .map(e -> "Excerpt " + e.getKey() + ": " + e.getValue())
                    .collect(Collectors.joining("\n"));

            var filesToInclude = pendingTextErrors.keySet().stream()
                    .map(validPathExcerpts::get)
                    .filter(Objects::nonNull)
                    .map(RawExcerpt::file)
                    .filter(this::fileExists)
                    .map(cm::toFile)
                    .distinct()
                    .toList();

            Context filteredCtx = new Context(cm).addFragments(cm.toPathFragments(filesToInclude));

            var retryTextMessages = new ArrayList<ChatMessage>();
            retryTextMessages.add(buildSystemMessage());
            retryTextMessages.addAll(
                    WorkspacePrompts.getMessagesInAddedOrder(filteredCtx, EnumSet.noneOf(SpecialTextType.class)));
            retryTextMessages.add(buildAnalysisRequestMessage());
            String taggedText =
                    "[HARNESS NOTE: some code excerpts in this message were invalid or could not be matched. "
                            + "Your code excerpts have been tagged with [Excerpt N] markers below to help you identify and fix them.]\n\n"
                            + ReviewParser.instance.tagExcerpts(mergedResponseText);

            retryTextMessages.add(new AiMessage(taggedText));

            retryTextMessages.add(new UserMessage(
                    """
                    The following excerpts could not be matched in the file content.
                    Please provide corrected excerpts for ONLY these excerpts.
                    All other excerpts have been recorded successfully and do not need to be repeated.

                    %s

                    Use this format:
                    Excerpt 1:
                    At `path/to/file.java` line 42:
                    ```
                    corrected code
                    ```
                    """
                            .formatted(errorList)));

            Llm.StreamingResult textResult = llm.sendRequest(retryTextMessages);
            totalRetries++;
            if (textResult.error() != null) {
                throw new ReviewGenerationException("LLM error during text resolution retry", textResult.error());
            }

            String resultText = textResult.text();
            Map<Integer, RawExcerpt> newlyFixed = ReviewParser.instance.parseNumberedExcerpts(resultText);

            Set<Integer> unexpectedIndices = newlyFixed.keySet().stream()
                    .filter(id -> !pendingTextErrors.containsKey(id))
                    .collect(Collectors.toSet());
            if (!unexpectedIndices.isEmpty()) {
                logger.warn(
                        "Discarding Stage 2 retry turn: unrequested excerpt corrections returned: {}",
                        unexpectedIndices);
                stage2Attempts++;
                continue;
            }

            // Apply excerpt corrections, but only commit if merging keeps excerpt structure stable.
            Map<Integer, RawExcerpt> candidateValidPathExcerpts = new HashMap<>(validPathExcerpts);
            for (var entry : newlyFixed.entrySet()) {
                int id = entry.getKey();
                RawExcerpt fixed = entry.getValue();
                if (!pendingTextErrors.containsKey(id)) {
                    continue;
                }
                if (!fileExists(fixed.file())) {
                    continue;
                }
                candidateValidPathExcerpts.put(id, fixed);
            }

            String candidateMerged = buildMergedResponseText(mergedResponseText, candidateValidPathExcerpts);
            if (ReviewParser.instance.parseExcerpts(candidateMerged).size() != parsedList.size()) {
                logger.warn("Discarding Stage 2 retry turn: excerpt structure changed after applying LLM corrections");
                stage2Attempts++;
                continue;
            }

            validPathExcerpts = candidateValidPathExcerpts;
            mergedResponseText = candidateMerged;

            // Attempt to resolve errors based on committed corrections
            for (var entry : newlyFixed.entrySet()) {
                int id = entry.getKey();
                RawExcerpt fixed = entry.getValue();
                if (!pendingTextErrors.containsKey(id)) {
                    continue;
                }
                if (!fileExists(fixed.file())) {
                    continue;
                }

                FileDiff fileDiff = findFileDiff(fixed.file(), changes.perFileChanges());
                if (fileDiff == null) {
                    continue;
                }

                ReviewParser.ExcerptMatch match = ReviewParser.matchExcerptInFile(fixed, fileDiff);
                if (match != null) {
                    var file = cm.toFile(fixed.file());
                    int lineCount = (int) match.matchedText().lines().count();
                    CodeUnit unit = cm.getAnalyzerUninterrupted()
                            .enclosingCodeUnit(file, match.line(), match.line() + Math.max(0, lineCount - 1))
                            .orElse(null);
                    matchedExcerpts.put(
                            id, new CodeExcerpt(file, unit, match.line(), match.side(), match.matchedText()));
                    pendingTextErrors.remove(id);
                }
            }

            stage2Attempts++;
        }

        // Log any remaining unresolved excerpts
        if (!pendingTextErrors.isEmpty()) {
            logger.warn("Could not match excerpt text after {} retries: {}", MAX_ATTEMPTS, pendingTextErrors.keySet());
        }

        return new RetryResult(matchedExcerpts, totalRetries, mergedResponseText);
    }

    private String correctFailedNotes(Llm llm, String responseText, List<ReviewParser.NoteValidationError> errors) {
        Map<String, List<String>> errorsByNote = errors.stream()
                .collect(Collectors.groupingBy(
                        ReviewParser.NoteValidationError::title,
                        Collectors.mapping(ReviewParser.NoteValidationError::message, Collectors.toList())));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, CompletableFuture<String>> futures = new HashMap<>();

            for (var entry : errorsByNote.entrySet()) {
                String title = entry.getKey();
                List<String> initialIssues = entry.getValue();
                String noteSection = ReviewParser.extractNoteSection(responseText, title);

                if (noteSection == null) {
                    logger.warn("Could not find note section for title: {}", title);
                    continue;
                }

                futures.put(
                        title,
                        LoggingFuture.supplyAsync(
                                () -> {
                                    Llm correctionLlm = llm.copy("Note Correction");
                                    String currentNote = noteSection;
                                    List<String> currentIssues = initialIssues;

                                    for (int attempt = 1; attempt <= 3; attempt++) {
                                        List<ChatMessage> messages = List.of(
                                                buildSystemMessage(),
                                                buildAnalysisRequestMessage(),
                                                new UserMessage(
                                                        """
                                        I could not parse the following note because: %s.
                                        Please review the format instructions and give the corrected Markdown text for this note only.
                                        If you prefer to skip this note entirely rather than fixing it, return empty text.

                                        The note title was: "%s"

                                        Existing malformed note:
                                        %s
                                        """
                                                                .formatted(
                                                                        String.join(", ", currentIssues),
                                                                        title,
                                                                        currentNote)));

                                        try {
                                            Llm.StreamingResult result = correctionLlm.sendRequest(messages);
                                            String correctionText = result.text();

                                            // Treat responses with no header as empty/skipped
                                            if (!correctionText.contains("#") || correctionText.isBlank()) {
                                                return "";
                                            }

                                            // Re-validate the correction
                                            var newErrors =
                                                    ReviewParser.instance.validateParsedNotes(correctionText).stream()
                                                            .filter(e ->
                                                                    e.title().equalsIgnoreCase(title)
                                                                            || correctionText
                                                                                    .toLowerCase(Locale.ROOT)
                                                                                    .contains(("### " + e.title())
                                                                                            .toLowerCase(Locale.ROOT)))
                                                            .toList();

                                            if (newErrors.isEmpty()) {
                                                return correctionText;
                                            }

                                            // Update state for next retry
                                            currentNote = correctionText;
                                            currentIssues = newErrors.stream()
                                                    .map(ReviewParser.NoteValidationError::message)
                                                    .toList();
                                            logger.warn(
                                                    "Correction attempt {} for '{}' failed validation: {}",
                                                    attempt,
                                                    title,
                                                    currentIssues);

                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            throw new RuntimeException(e);
                                        }
                                    }

                                    logger.warn(
                                            "Note '{}' still invalid after 3 correction attempts. Skipping.", title);
                                    return "";
                                },
                                executor));
            }

            String mergedText = responseText.replace("\r\n", "\n").replace("\r", "\n");
            for (var entry : futures.entrySet()) {
                String requestedTitle = entry.getKey();
                String correction = entry.getValue().join();
                logger.debug("Correction for '{}': {}", requestedTitle, correction);

                // Robustness: the LLM might have changed the title slightly or returned the wrong one in tests.
                // We try to find which section in the original text this correction actually belongs to.
                String actualTitle = requestedTitle;
                if (ReviewParser.extractNoteSection(mergedText, requestedTitle) == null) {
                    // If requested title isn't found, try to see if the correction contains a known title
                    for (String title : errorsByNote.keySet()) {
                        if (correction.toLowerCase(Locale.ROOT).contains(("### " + title).toLowerCase(Locale.ROOT))) {
                            actualTitle = title;
                            break;
                        }
                    }
                }

                mergedText = replaceNoteSection(mergedText, actualTitle, correction);
            }
            return mergedText;
        }
    }

    private static String replaceNoteSection(String markdown, String noteTitle, String replacement) {
        // Normalize line endings to \n for consistent matching
        String normalized = markdown.replace("\r\n", "\n").replace("\r", "\n");
        String section = ReviewParser.extractNoteSection(normalized, noteTitle);
        if (section == null) {
            logger.warn("Could not find section for note '{}' to replace", noteTitle);
            return normalized;
        }
        String normalizedReplacement =
                replacement.replace("\r\n", "\n").replace("\r", "\n").trim();
        // If the original section ended with a newline, preserve it in the replacement
        if (section.endsWith("\n") && !normalizedReplacement.endsWith("\n")) {
            normalizedReplacement += "\n";
        }
        String result = normalized.replace(section, normalizedReplacement);
        if (result.equals(normalized)) {
            logger.warn(
                    "Replacement had no effect for note '{}'. Section length: {}, sample: [{}]",
                    noteTitle,
                    section.length(),
                    section.substring(0, Math.min(50, section.length())));
        }
        return result;
    }

    private String buildMergedResponseText(String originalText, Map<Integer, RawExcerpt> correctedExcerpts) {
        List<ReviewParser.Segment> segments = ReviewParser.instance.parseToSegments(originalText);
        logger.debug("Original response segments: {}", segments);

        List<ReviewParser.Segment> mergedSegments = new ArrayList<>();
        int excerptIndex = 0;
        for (var segment : segments) {
            if (segment instanceof ReviewParser.ExcerptSegment) {
                RawExcerpt corrected = correctedExcerpts.get(excerptIndex);
                if (corrected != null) {
                    mergedSegments.add(
                            new ReviewParser.ExcerptSegment(corrected.file(), corrected.line(), corrected.excerpt()));
                } else {
                    mergedSegments.add(segment);
                }
                excerptIndex++;
            } else {
                mergedSegments.add(segment);
            }
        }

        String merged = ReviewParser.instance.serializeSegments(mergedSegments);
        logger.debug("Merged response text: {}", merged);
        return merged;
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

                Your goal is to surface the most important changes and call attention to tricky, subtle, or simply incorrect choices in the diff.
                - Key changes: highlight the most important changes, especially to data structures.
                - Design notes: Higher level concerns (architectural issues, coupling, abstraction problems).
                - Tactical notes: Simple issues localized to a single method or file.
                - Each design note MUST have AT LEAST ONE excerpt block illustrating the subject, and may include as many excerpts as are relevant
                - Each Tactical note must include EXACTLY ONE excerpt block.

                All titles should be 3-6 words.

                Make your recommendations with confidence; if there are multiple options to remediate, give only the best one.
                Don't equivocate; if it's too minor to address then leave it out entirely.

                Overview comes LAST, after you've had time to think through the design.

                Every section except Overview is optional; omit them if there is nothing important to say.
                </instructions>
                <review_content>
                Unless there is strong evidence to the contrary, you should assume that the code compiles and runs.
                You should be especially cautious about drawing conclusions of compile errors from diffs alone.

                If you have Patch Instructions available, call out important incomplete or unimplemented functionality that
                was asked for but not delivered, but be aware that instructions may be neither complete nor authoritative;
                the instructions may include false starts, and the patch may include external changes.

                You should NOT assume that more tests exist besides what you see.
                </review_content>
                <excerpt_format>
                When referencing code, use the following format with the file path and line number on a separate line before the code block:

                At `path/to/file.java` line $line:
                ```
                $code
                ```

                I will look for EXACT matches for your excerpt, so avoid ellipsis or commentary in the block.
                </excerpt_format>
                <review_format>
                ## Key Changes
                ### [Title of first key change]
                [Description of what changed and why it matters. Include code blocks showing the key code.]

                ### [Title of second key change, etc.]
                ...

                ## Design Notes
                ### [Title of first design note]
                [Description text. Include code blocks as needed.]
                **Recommendation:** [Detailed instructions for fixing the design issue. Must be actionable by a developer without reference to your review outside of this note's description.]

                ### [Title of second design note, etc.]
                ...

                ## Tactical Notes
                ### [Title of first tactical note]
                [Description text. Include exactly one code block.]
                **Recommendation:** [Detailed instructions for the fix.]

                ## Additional Tests [Omit this if no additional tests are needed]
                ### [Title of first test suggestion]
                **Recommendation:** [Detailed instructions for what to test and how.]

                ### [Title of second test suggestion, etc.]
                ...

                ## Overview
                [One or more paragraphs describing what the changes accomplish and big-picture analysis]
                </review_format>
                """);
    }

    @SuppressWarnings("UnusedMethod") // Called via reflection by ToolRegistry
    @Tool(
            "Add context fragments to help perform a thorough code review. Call this once with all the files, summaries, classes, and methods needed.")
    public String addReviewFragments(
            @P("The categorized complexity of the change: TRIVIAL, STRAIGHTFORWARD, or COMPLEX") String complexity,
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
        this.isComplex = !complexity.equalsIgnoreCase("TRIVIAL");
        var wst = new WorkspaceTools(requireNonNull(contextBeingBuilt));
        wst.addFilesToWorkspace(filesForFullSource);
        wst.addFileSummariesToWorkspace(filesForSummaries);
        wst.addClassesToWorkspace(classNames);
        wst.addMethodsToWorkspace(methodNames);

        this.contextBeingBuilt = wst.getContext();
        return "Context updated with requested fragments.";
    }

    @Blocking
    private List<ContextFragments.StringFragment> extractSessionContext(List<UUID> sessionIds) {
        Set<ProjectFile> editedFiles = changes.perFileChanges().stream()
                .flatMap(fd -> Stream.of(fd.oldFile(), fd.newFile()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        var sessionContext = ReviewScope.extractSessionContext(cm, sessionIds, editedFiles);
        if (sessionContext.isEmpty()) {
            return List.of();
        }

        var results = new ArrayList<ContextFragments.StringFragment>();

        if (!sessionContext.patchInstructions().isEmpty()) {
            String mergedInstructions =
                    """
                    These are the instructions given to the Code Agent to generate this patch, separated by `-----`.

                    -----
                    %s
                    """
                            .formatted(String.join("\n-----\n", sessionContext.patchInstructions()));
            results.add(new ContextFragments.StringFragment(
                    cm, mergedInstructions, "Patch Instructions", SyntaxConstants.SYNTAX_STYLE_NONE));
        }

        if (!sessionContext.sourceHints().isEmpty()) {
            String mergedHints = sessionContext.sourceHints().stream()
                    .map(hint -> "- " + hint)
                    .collect(Collectors.joining("\n"));
            results.add(new ContextFragments.StringFragment(
                    cm, mergedHints, "Sources Used During Patch Creation", SyntaxConstants.SYNTAX_STYLE_NONE));
        }

        return List.copyOf(results);
    }
}
