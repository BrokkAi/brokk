package io.github.jbellis.brokk.agents;

import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import io.github.jbellis.brokk.AnalyzerUtil;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.Llm;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.analyzer.SkeletonProvider;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.util.AdaptiveExecutor;
import io.github.jbellis.brokk.util.Messages;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Agent responsible for populating the initial workspace context for the ArchitectAgent. It uses different strategies
 * based on project size, available analysis data, and token limits.
 */
public class ContextAgent {
    private static final Logger logger = LogManager.getLogger(ContextAgent.class);

    private final ContextManager cm;
    private final Llm llm;
    private final String goal;
    private final IAnalyzer analyzer;
    private final boolean deepScan;
    private final StreamingChatModel model;

    // if the entire project fits in the Skip Pruning budget, just include it all and call it good
    private final int skipPruningBudget;
    // if our to-prune context is larger than the Pruning budget then we give up
    private int budgetPruning;

    // Rule 1: Use all available summaries if they fit the smallest budget and meet the limit (if not deepScan)
    private final int QUICK_TOPK = 10;

    public ContextAgent(ContextManager contextManager, StreamingChatModel model, String goal, boolean deepScan)
            throws InterruptedException {
        this.cm = contextManager;
        this.llm = contextManager.getLlm(model, "ContextAgent: " + goal); // Coder for LLM interactions
        this.goal = goal;
        this.analyzer = contextManager.getAnalyzer();
        this.deepScan = deepScan;
        this.model = model;

        int maxInputTokens = contextManager.getService().getMaxInputTokens(model);
        this.skipPruningBudget = min(32_000, maxInputTokens / 4);

        // non-openai models often use more tokens than our estimation so cap this conservatively
        int outputTokens = model.defaultRequestParameters().maxCompletionTokens(); // TODO override this when we can
        int actualInputTokens = contextManager.getService().getMaxInputTokens(model) - outputTokens;
        // god, our estimation is so bad (yes we do observe the ratio being this far off)
        this.budgetPruning = (int) (actualInputTokens * 0.65);

        debug("ContextAgent initialized. Budgets: SkipPruning={}, Pruning={}", skipPruningBudget, budgetPruning);
    }

    private void debug(String format, Object... args) {
        String prefix = deepScan ? "[Deep] " : "[Quick] ";
        logger.debug(prefix + format, args);
    }

    /** Result record for context recommendation attempts, including token usage of the LLM call (nullable). */
    public record RecommendationResult(
            boolean success,
            List<ContextFragment> fragments,
            String reasoning,
            @Nullable Llm.RichTokenUsage tokenUsage) {
        static final RecommendationResult FAILED_SINGLE_PASS = new RecommendationResult(
                false, List.of(), "Project too large to quickly determine context; try Deep Scan", null);

        public RecommendationResult(boolean success, List<ContextFragment> fragments, String reasoning) {
            this(success, fragments, reasoning, null);
        }
    }

    /** Result record for the LLM tool call, holding recommended files, class names, and the LLM's reasoning. */
    private record LlmRecommendation(
            Set<ProjectFile> recommendedFiles,
            Set<CodeUnit> recommendedClasses,
            String reasoning,
            @Nullable Llm.RichTokenUsage tokenUsage) {
        static final LlmRecommendation EMPTY = new LlmRecommendation(Set.of(), Set.of(), "", null);

        public LlmRecommendation(List<ProjectFile> files, List<CodeUnit> classes, String reasoning) {
            this(new HashSet<>(files), new HashSet<>(classes), reasoning, null);
        }
    }

    /** Inner class representing the tool that the LLM can call to recommend context. */
    public static class ContextRecommendationTool {
        private final List<String> recommendedFiles = new ArrayList<>();
        private final List<String> recommendedClasses = new ArrayList<>();

        @Tool("Recommend relevant files and classes needed to achieve the user's goal.")
        public void recommendContext(
                @P("List of full paths of files to edit or whose full text is necessary.") List<String> filesToAdd,
                @P(
                                "List of fully-qualified class names for classes whose APIs are relevant to the goal but which do not need to be edited.")
                        List<String> classesToSummarize) {
            this.recommendedFiles.addAll(filesToAdd);
            this.recommendedClasses.addAll(classesToSummarize);
            // Note: This debug call is inside a static inner class, so it cannot use the instance `debug` method.
            // It will remain a direct logger call.
            logger.debug("ContextTool called recommendContext: files={}, classes={}", filesToAdd, classesToSummarize);
        }

        public List<String> getRecommendedFiles() {
            return recommendedFiles;
        }

        public List<String> getRecommendedClasses() {
            return recommendedClasses;
        }
    }

    /** Calculates the approximate token count for a list of ContextFragments. */
    public int calculateFragmentTokens(List<ContextFragment> fragments) {
        int totalTokens = 0;

        for (var fragment : fragments) {
            if (fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                Optional<ProjectFile> fileOpt = fragment.files().stream().findFirst();

                if (fileOpt.isPresent()) {
                    var file = fileOpt.get();
                    String content;
                    content = file.read().orElse("");
                    totalTokens += Messages.getApproximateTokens(content);
                } else {
                    debug(
                            "PROJECT_PATH fragment {} did not yield a ProjectFile for token calculation.",
                            fragment.description());
                }
            } else if (fragment.getType() == ContextFragment.FragmentType.SKELETON) {
                var skeletonFragment = (ContextFragment.SkeletonFragment) fragment;
                // SkeletonFragment.text() computes the combined skeletons.
                // This might re-fetch if called multiple times, but for token calculation it's acceptable once.
                // ContextFragment.SkeletonFragment.text() does not throw IOException or InterruptedException
                // as per its current implementation (it catches InterruptedException internally from getAnalyzer).
                totalTokens += Messages.getApproximateTokens(skeletonFragment.text());
            } else {
                logger.warn("Unhandled ContextFragment type for token calculation: {}", fragment.getClass());
            }
        }
        return totalTokens;
    }

    /**
     * Determines the best initial context based on project size and token budgets, potentially using LLM inference, and
     * returns recommended context fragments.
     *
     * <p>potentially using LLM inference, and returns recommended context fragments.
     *
     * @param allowSkipPruning If true, allows the agent to skip LLM-based pruning if the initial context fits
     *     `skipPruningBudget`.
     * @return A RecommendationResult containing success status, fragments, and reasoning.
     */
    public RecommendationResult getRecommendations(boolean allowSkipPruning) throws InterruptedException {
        Collection<ChatMessage> workspaceRepresentation;
        workspaceRepresentation = deepScan
                ? CodePrompts.instance.getWorkspaceContentsMessages(cm.liveContext())
                : CodePrompts.instance.getWorkspaceSummaryMessages(cm.liveContext());
        budgetPruning -= Messages.getApproximateTokens(workspaceRepresentation);
        debug("Budget for pruning is {} after workspace contents", budgetPruning);
        if (budgetPruning < 1000) {
            // can't do anything useful here
            return new RecommendationResult(false, List.of(), "Workspace is too large", null);
        }
        var existingFiles = cm.liveContext()
                .allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.PROJECT_PATH
                        || f.getType() == ContextFragment.FragmentType.SKELETON)
                .flatMap(f -> f.files().stream())
                .collect(Collectors.toSet());
        var allFiles = cm.getProject().getAllFiles().stream()
                .filter(f -> !existingFiles.contains(f))
                .sorted()
                .toList();
        debug(
                "Filtered out {} existing files from a total of {}. Considering {} files for context recommendation.",
                existingFiles.size(),
                cm.getProject().getAllFiles().size(),
                allFiles.size());

        // Attempt single-pass mode first
        RecommendationResult firstPassResult = RecommendationResult.FAILED_SINGLE_PASS;
        try {
            if (analyzer.isEmpty()) {
                firstPassResult =
                        executeWithFileContents(allFiles, existingFiles, workspaceRepresentation, allowSkipPruning);
            } else {
                firstPassResult =
                        executeWithSummaries(allFiles, existingFiles, workspaceRepresentation, allowSkipPruning);
            }
            if (firstPassResult.success) {
                return firstPassResult;
            }
        } catch (ContextTooLargeException e) {
            // fall back to pruning by filenames first
        }

        // At this point, we have too much content to do a single pass using full contents or summaries.
        // In Quick mode, that means we recommend based on filenames. For Deep Scan we will prune by
        // filename, and then go back to full contents or summaries of those.
        if (!deepScan) {
            var recs = askLlmQuickRecommendContext(
                    allFiles.stream().map(ProjectFile::toString).toList(), Map.of(), Map.of(), workspaceRepresentation);
            return createResult(recs, existingFiles);
        }

        // Initialize cumulative usage with any tokens spent on the failed first pass.
        @Nullable var cumulativeUsage = firstPassResult.tokenUsage();

        // Fallback 1: Prune based on filenames only
        debug("Falling back to filename-based pruning.");
        var filenameResult = createResult(
                askLlmDeepPruneFilenamesWithChunking(
                        allFiles.stream().map(ProjectFile::toString).toList(), workspaceRepresentation),
                existingFiles);
        cumulativeUsage = addTokenUsage(cumulativeUsage, filenameResult.tokenUsage());

        if (!filenameResult.success) {
            logGiveUp("filename list (too large for LLM pruning)");
            return new RecommendationResult(false, List.of(), filenameResult.reasoning(), cumulativeUsage);
        }

        var prunedFiles = filenameResult.fragments.stream()
                .flatMap(f -> f.files().stream())
                .toList();
        if (prunedFiles.isEmpty()) {
            debug("Filename pruning resulted in an empty list. No context found.");
            return new RecommendationResult(true, List.of(), filenameResult.reasoning(), cumulativeUsage);
        }
        debug("LLM pruned to {} files based on names. Reasoning: {}", prunedFiles.size(), filenameResult.reasoning());

        // Fallback 2: After pruning filenames, try again with summaries or contents using the pruned list
        RecommendationResult finalResult;
        try {
            if (analyzer.isEmpty()) {
                finalResult = executeWithFileContents(prunedFiles, existingFiles, workspaceRepresentation, false);
            } else {
                finalResult = executeWithSummaries(prunedFiles, existingFiles, workspaceRepresentation, false);
            }
        } catch (ContextTooLargeException e) {
            // The set is still too large; prune once more on filenames only
            debug("Second pass still too large; performing a second filename-based prune.");
            var secondFilenameResult = createResult(
                    askLlmDeepPruneFilenamesWithChunking(
                            prunedFiles.stream().map(ProjectFile::toString).toList(), workspaceRepresentation),
                    existingFiles);
            cumulativeUsage = addTokenUsage(cumulativeUsage, secondFilenameResult.tokenUsage());

            if (!secondFilenameResult.success) {
                // Even pruning on filenames failed
                return new RecommendationResult(false, List.of(), secondFilenameResult.reasoning(), cumulativeUsage);
            }
            if (secondFilenameResult.fragments.isEmpty()) {
                // Nothing left after the second prune
                return new RecommendationResult(true, List.of(), secondFilenameResult.reasoning(), cumulativeUsage);
            }

            var prunedFiles2 = secondFilenameResult.fragments().stream()
                    .flatMap(f -> f.files().stream())
                    .toList();
            try {
                if (analyzer.isEmpty()) {
                    finalResult = executeWithFileContents(prunedFiles2, existingFiles, workspaceRepresentation, false);
                } else {
                    finalResult = executeWithSummaries(prunedFiles2, existingFiles, workspaceRepresentation, false);
                }
                cumulativeUsage = addTokenUsage(cumulativeUsage, filenameResult.tokenUsage());
            } catch (ContextTooLargeException fatal) {
                // Third failure is fatal
                logGiveUp("second pruning pass");
                return new RecommendationResult(
                        false, List.of(), "Context still too large after second pruning pass", cumulativeUsage);
            }
        }

        // return the final result
        return new RecommendationResult(
                finalResult.success(), finalResult.fragments(), finalResult.reasoning(), cumulativeUsage);
    }

    // --- Logic branch for using class summaries ---

    private RecommendationResult executeWithSummaries(
            List<ProjectFile> filesToConsider,
            Set<ProjectFile> existingFiles,
            Collection<ChatMessage> workspaceRepresentation,
            boolean allowSkipPruning)
            throws InterruptedException, ContextTooLargeException {
        Map<CodeUnit, String> rawSummaries;
        var ctx = cm.liveContext();

        if (isCodeInWorkspace(ctx) && !deepScan && cm.getAnalyzerWrapper().isReady()) {
            // If the workspace isn't empty, use Git distance candidates for Quick context
            var ac = cm.liveContext().buildAutoContext(50);
            // fetchSkeletons() is private in SkeletonFragment. We need to use its sources() or text().
            // For now, let's get the target FQNs and then fetch summaries for them.
            List<String> targetFqns = ac.getTargetIdentifiers();
            debug(
                    "Non-empty workspace; using Git-based distance candidates (target FQNs: {})",
                    String.join(",", targetFqns));

            // Create a temporary map for rawSummaries from these targetFqns
            Map<CodeUnit, String> tempSummaries = new HashMap<>();
            for (String fqn : targetFqns) {
                analyzer.getDefinition(fqn).ifPresent(cu -> {
                    boolean inWorkspace = analyzer.getFileFor(cu.fqName())
                            .map(existingFiles::contains)
                            .orElse(false);
                    if (cu.isClass() && !inWorkspace) {
                        analyzer.as(SkeletonProvider.class)
                                .flatMap(skp -> skp.getSkeleton(fqn))
                                .ifPresent(skel -> tempSummaries.put(cu, skel));
                    }
                });
            }
            rawSummaries = tempSummaries;
        } else {
            // Scan all the files
            requireNonNull(analyzer);
            rawSummaries = filesToConsider.stream()
                    .parallel()
                    .flatMap(f -> analyzer.as(SkeletonProvider.class)
                            .map(skp -> skp.getSkeletons(f).entrySet().stream())
                            .orElse(Stream.empty()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1));
        }

        int summaryTokens = Messages.getApproximateTokens(String.join("\n", rawSummaries.values()));
        debug(
                "Total tokens for {} summaries (from {} files): {}",
                rawSummaries.size(),
                filesToConsider.size(),
                summaryTokens);

        boolean withinLimit = deepScan || rawSummaries.size() <= QUICK_TOPK;
        if (rawSummaries.isEmpty() || (allowSkipPruning && summaryTokens <= skipPruningBudget && withinLimit)) {
            var codeUnits = rawSummaries.keySet().stream().toList();
            return createResult(
                    new LlmRecommendation(List.of(), codeUnits, "All summaries are under budget"), existingFiles);
        }

        if (summaryTokens > budgetPruning) {
            throw new ContextTooLargeException();
        }

        // Ask LLM to pick relevant summaries/files if all summaries fit the Pruning budget
        var llmRecommendation = askLlmToRecommendContext(List.of(), rawSummaries, Map.of(), workspaceRepresentation);
        return createResult(llmRecommendation, existingFiles);
    }

    private static boolean isCodeInWorkspace(Context ctx) {
        return ctx.allFragments().flatMap(f -> f.sources().stream()).findAny().isPresent();
    }

    private RecommendationResult createResult(LlmRecommendation llmRecommendation, Set<ProjectFile> existingFiles) {
        // Filter out files already in the workspace
        var originalFiles = llmRecommendation.recommendedFiles();
        var filteredFiles =
                originalFiles.stream().filter(f -> !existingFiles.contains(f)).toList();
        if (filteredFiles.size() != originalFiles.size()) {
            debug(
                    "Post-filtered LLM recommended files from {} to {} by excluding files already in the workspace",
                    originalFiles.size(),
                    filteredFiles.size());
        }

        // LLM might recommend both a file and a summary of a class in that file; remove any redundant classes.
        // Also remove classes whose source is already in the workspace.
        var originalClassCount = llmRecommendation.recommendedClasses().size();
        var recommendedClasses = llmRecommendation.recommendedClasses().stream()
                .filter(cu -> !filteredFiles.contains(cu.source()))
                .filter(cu -> !existingFiles.contains(cu.source()))
                .toList();
        if (recommendedClasses.size() != originalClassCount) {
            debug(
                    "Post-filtered LLM recommended classes from {} to {} by excluding classes whose source files are already present or recommended",
                    originalClassCount,
                    recommendedClasses.size());
        }

        var reasoning = llmRecommendation.reasoning();

        // Get summaries for recommended classes
        var recommendedSummaries = getSummaries(recommendedClasses, false);

        // Calculate combined token size
        int recommendedSummaryTokens = Messages.getApproximateTokens(String.join("\n", recommendedSummaries.values()));
        var recommendedContentsMap = readFileContents(filteredFiles);
        int recommendedContentTokens =
                Messages.getApproximateTokens(String.join("\n", recommendedContentsMap.values()));
        int totalRecommendedTokens = recommendedSummaryTokens + recommendedContentTokens;

        debug(
                "LLM recommended {} classes ({} tokens) and {} files ({} tokens). Total: {} tokens",
                recommendedSummaries.size(),
                recommendedSummaryTokens,
                filteredFiles.size(),
                recommendedContentTokens,
                totalRecommendedTokens);

        // Create fragments
        var skeletonFragments = skeletonPerSummary(cm, recommendedSummaries);
        var pathFragments = filteredFiles.stream()
                .map(f -> (ContextFragment) new ContextFragment.ProjectPathFragment(f, cm))
                .toList();
        var combinedFragments = Stream.concat(skeletonFragments.stream(), pathFragments.stream())
                .toList();

        return new RecommendationResult(true, combinedFragments, reasoning, llmRecommendation.tokenUsage());
    }

    /** one SkeletonFragment per summary so ArchitectAgent can easily ask user which ones to include */
    private static List<ContextFragment> skeletonPerSummary(
            IContextManager contextManager, Map<CodeUnit, String> relevantSummaries) {
        return relevantSummaries.keySet().stream()
                .map(s -> (ContextFragment) new ContextFragment.SkeletonFragment(
                        contextManager, List.of(s.fqName()), ContextFragment.SummaryType.CODEUNIT_SKELETON))
                .toList();
    }

    private Map<CodeUnit, String> getSummaries(Collection<CodeUnit> classes, boolean parallel) {
        var coalescedClasses = AnalyzerUtil.coalesceInnerClasses(Set.copyOf(classes));
        debug("Found {} classes", coalescedClasses.size());

        var stream = coalescedClasses.stream();
        if (parallel) {
            stream = stream.parallel();
        }
        return stream.map(cu -> {
                    final String skeleton = analyzer.as(SkeletonProvider.class)
                            .flatMap(skp -> skp.getSkeleton(cu.fqName()))
                            .orElse("");
                    return Map.entry(cu, skeleton);
                })
                .filter(entry -> !entry.getValue().isEmpty())
                // Use merge function (v1, v2) -> v1 to handle potential duplicate keys by keeping the first value
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1));
    }

    /** Exactly one of {filenames, summaries, contentsMap} must be non-empty. */
    private LlmRecommendation askLlmToRecommendContext(
            List<String> filenames,
            Map<CodeUnit, String> summaries,
            Map<ProjectFile, String> contentsMap,
            Collection<ChatMessage> workspaceRepresentation)
            throws InterruptedException, ContextTooLargeException {
        boolean hasFilenames = !filenames.isEmpty();
        boolean hasSummaries = !summaries.isEmpty();
        boolean hasContents = !contentsMap.isEmpty();
        assert (hasFilenames ? 1 : 0) + (hasSummaries ? 1 : 0) + (hasContents ? 1 : 0) == 1
                : "Exactly one of filenames (%s), summaries (%s), or contentsMap (%s) must be non-empty"
                        .formatted(filenames.size(), summaries.size(), contentsMap.size());

        if (deepScan) {
            return askLlmDeepRecommendContext(summaries, contentsMap, workspaceRepresentation);
        } else {
            return askLlmQuickRecommendContext(filenames, summaries, contentsMap, workspaceRepresentation);
        }
    }

    // --- Deep Scan Recommendation ---

    private LlmRecommendation askLlmDeepPruneFilenamesWithChunking(
            List<String> filenames, Collection<ChatMessage> workspaceRepresentation) throws InterruptedException {
        // If the filename list is too large for one request, process it in parallel chunks
        var filenameString = String.join("\n", filenames);
        int filenameTokens = Messages.getApproximateTokens(filenameString);
        if (filenameTokens > budgetPruning) {
            return deepPruneFilenamesInChunks(filenames, workspaceRepresentation);
        }

        return askLlmDeepPruneFilenames(filenames, workspaceRepresentation);
    }

    private @NotNull LlmRecommendation askLlmDeepPruneFilenames(
            List<String> filenames, Collection<ChatMessage> workspaceRepresentation) throws InterruptedException {
        var systemPrompt =
                """
                        You are an assistant that performs a first pass of identifying relevant files based on a goal and the existing Workspace contents.
                        A second pass will be made using your recommended files, so the top priority is to make sure you
                        identify ALL potentially relevant files without leaving any out, even at the cost of some false positives.
                        """;
        var filenamePrompt =
                """
                        <instructions>
                        Given the above goal and Workspace contents (if any), evaluate the following list of filenames
                        while thinking carefully about how they may be relevant to the goal.

                        Reason step-by-step:
                         - Identify all files corresponding to class names explicitly mentioned in the <goal>.
                         - Identify all files corresponding to class types used in the <workspace> code.
                         - Think about how you would solve the <goal>, and identify additional files potentially relevant to your plan.
                           For example, if the plan involves instantiating class Foo, or calling a method of class Bar,
                           then Foo.java and Bar.java are relevant files.
                         - Compare this combined list against the filenames available.

                        Then, list the full path of each relevant filename, one per line.
                        </instructions>
                        <filenames>
                        %s
                        </filenames>
                        """
                        .formatted(String.join("\n", filenames));
        var finalSystemMessage = new SystemMessage(systemPrompt);
        var userPrompt =
                """
                        <goal>
                        %s
                        </goal>

                        %s
                        """
                        .formatted(goal, filenamePrompt)
                        .stripIndent();
        List<ChatMessage> messages = Stream.concat(
                        Stream.of(finalSystemMessage),
                        Stream.concat(workspaceRepresentation.stream(), Stream.of(new UserMessage(userPrompt))))
                .toList();
        int promptTokens = Messages.getApproximateTokens(messages);
        debug("Invoking LLM to prune filenames (prompt size ~{} tokens)", promptTokens);
        var result = llm.sendRequest(messages);
        if (result.error() != null || result.isEmpty()) {
            var error = result.error();
            // litellm does an inconsistent job translating into ContextWindowExceededError.
            // https://github.com/BrokkAi/brokk/issues/540
            boolean contextError = error != null
                    && error.getMessage() != null
                    && error.getMessage().toLowerCase(Locale.ROOT).contains("context");
            if (contextError && filenames.size() >= 20) {
                // Estimation was off; split the request and retry recursively
                debug("LLM context-window error with {} filenames; splitting and retrying", filenames.size());
                int mid = filenames.size() / 2;
                var left = filenames.subList(0, mid);
                var right = filenames.subList(mid, filenames.size());

                var rec1 = askLlmDeepPruneFilenamesWithChunking(left, workspaceRepresentation);
                var rec2 = askLlmDeepPruneFilenamesWithChunking(right, workspaceRepresentation);

                // Merge the two halves
                var mergedFiles = new ArrayList<>(rec1.recommendedFiles());
                rec2.recommendedFiles().stream()
                        .filter(pf -> !mergedFiles.contains(pf))
                        .forEach(mergedFiles::add);

                var mergedReasoning = (rec1.reasoning() + "\n" + rec2.reasoning()).strip();
                var mergedUsage = addTokenUsage(rec1.tokenUsage(), rec2.tokenUsage());

                return new LlmRecommendation(new HashSet<>(mergedFiles), Set.of(), mergedReasoning, mergedUsage);
            }

            logger.warn(
                    "Error ({}) from LLM during filename pruning: {}. Returning empty",
                    error != null ? error.getMessage() : "empty response",
                    error);
            return LlmRecommendation.EMPTY;
        }
        var tokenUsage = result.tokenUsage();
        var selected = filenames.stream()
                .parallel()
                .filter(f -> result.text().contains(f))
                .toList();
        return new LlmRecommendation(toProjectFiles(selected), Set.of(), result.text(), tokenUsage);
    }

    /** Execute filename pruning in parallel when the input exceeds the per-request limit. */
    private LlmRecommendation deepPruneFilenamesInChunks(
            List<String> filenames, Collection<ChatMessage> workspaceRepresentation) throws InterruptedException {
        debug("Chunking {} filenames for parallel pruning", filenames.size());

        // Build chunks that fit within the remaining per-request budget
        List<List<String>> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentTokens = 0;

        for (var fn : filenames) {
            int t = Messages.getApproximateTokens(fn);
            if (!current.isEmpty() && currentTokens + t > budgetPruning) {
                chunks.add(current);
                current = new ArrayList<>();
                currentTokens = 0;
            }
            current.add(fn);
            currentTokens += t;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }

        debug("Created {} chunks for pruning", chunks.size());
        if (chunks.size() > 100) {
            debug("Too many chunks: " + chunks.size());
            return new LlmRecommendation(List.of(), List.of(), "Unable to prune filenames");
        }

        List<Future<LlmRecommendation>> futures;
        try (var executor = AdaptiveExecutor.create(cm.getService(), model, chunks.size())) {
            List<Callable<LlmRecommendation>> tasks = new ArrayList<>(chunks.size());
            for (var chunk : chunks) {
                tasks.add(() -> {
                    try {
                        return askLlmDeepPruneFilenames(chunk, workspaceRepresentation);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ie);
                    }
                });
            }

            futures = executor.invokeAll(tasks);
        }

        var combinedFiles = new HashSet<ProjectFile>();
        var combinedReasoning = new StringBuilder();
        @Nullable Llm.RichTokenUsage combinedUsage = null;

        for (var f : futures) {
            try {
                var rec = f.get();
                rec.recommendedFiles().stream()
                        .filter(pf -> !combinedFiles.contains(pf))
                        .forEach(combinedFiles::add);
                if (!rec.reasoning().isBlank())
                    combinedReasoning.append(rec.reasoning()).append('\n');
                combinedUsage = addTokenUsage(combinedUsage, rec.tokenUsage());
            } catch (Exception e) {
                logger.warn("Failed to retrieve chunk result", e);
            }
        }
        return new LlmRecommendation(
                combinedFiles, Set.of(), combinedReasoning.toString().strip(), combinedUsage);
    }

    private LlmRecommendation askLlmDeepRecommendContext(
            Map<CodeUnit, String> summaries,
            Map<ProjectFile, String> contentsMap,
            Collection<ChatMessage> workspaceRepresentation)
            throws InterruptedException, ContextTooLargeException {
        assert !summaries.isEmpty() ^ !contentsMap.isEmpty();

        // Determine the type of context being provided
        String contextTypeElement;
        String contextTypeDescription;
        if (!summaries.isEmpty()) {
            contextTypeElement = "available_summaries";
            contextTypeDescription = "a list of class summaries";
        } else {
            contextTypeElement = "available_files_content";
            contextTypeDescription = "a list of file paths and their contents";
        }

        // exactly one of filesToConsider, summaries, and contentsMap should be non-empty, depending on whether
        // we're recommending based on filenames, summaries, or full file contents, respectively
        var contextTool = new ContextRecommendationTool();
        // Generate specifications from the annotated tool instance
        var toolSpecs = ToolSpecifications.toolSpecificationsFrom(contextTool);
        assert toolSpecs.size() == 1 : "Expected exactly one tool specification from ContextRecommendationTool";

        var deepPromptTemplate =
                """
                        You are an assistant that identifies relevant code context based on a goal and the existing relevant information.
                        You are given a goal, the current workspace contents (if any), and %s (within <%s> tags).
                        Analyze the provided information and determine which items are most relevant to achieving the goal.
                        """
                        .formatted(contextTypeDescription, contextTypeElement)
                        .stripIndent();

        var finalSystemMessage = new SystemMessage(deepPromptTemplate);
        var userMessageText = new StringBuilder();

        // Add context data based on type (Deep Scan version)
        if (!summaries.isEmpty()) {
            var summariesText = summaries.entrySet().stream()
                    .map(entry -> {
                        var cn = entry.getKey();
                        var body = entry.getValue();
                        // Map Optional<ProjectFile> to String filename, defaulting if not present
                        var filename = analyzer.getFileFor(cn.fqName())
                                .map(ProjectFile::toString)
                                .orElse("unknown");
                        // Always include filename for deep scan
                        return "<class fqcn='%s' file='%s'>\n%s\n</class>".formatted(cn.fqName(), filename, body);
                    })
                    .collect(Collectors.joining("\n\n"));
            userMessageText.append(
                    "<%s>\n%s\n</%s>\n\n".formatted(contextTypeElement, summariesText, contextTypeElement));
        } else if (!contentsMap.isEmpty()) {
            var filesText = contentsMap.entrySet().stream()
                    .map(entry -> "<file path='%s'>\n%s\n</file>"
                            .formatted(entry.getKey().toString(), entry.getValue()))
                    .collect(Collectors.joining("\n\n"));
            userMessageText.append("<%s>\n%s\n</%s>\n\n".formatted(contextTypeElement, filesText, contextTypeElement));
        }

        userMessageText.append("\n<goal>\n%s\n</goal>\n".formatted(goal));
        var userPrompt =
                """
                        Identify code context relevant to the goal by calling `recommendContext`.

                        Before calling `recommendContext`, reason step-by-step:
                        - Identify all class names explicitly mentioned in the <goal>.
                        - Identify all class types used in the <workspace> code.
                        - Think about how you would solve the <goal>, and identify additional classes relevant to your plan.
                          For example, if the plan involves instantiating class Foo, or calling a method of class Bar,
                          then Foo and Bar are relevant classes.
                        - Compare this combined list against the classes in <%s>.

                        Then call the `recommendContext` tool with the appropriate entries:

                        Populate the `filesToAdd` argument with the full (relative) paths of files that will need to be edited as part of the goal,
                        or whose implementation details are necessary. Put these files in `filesToAdd` (even if you are only shown a summary).

                        Populate the `classesToSummarize` argument with the fully-qualified names of classes whose APIs will be used.

                        Either or both of `filesToAdd` and `classesToSummarize` may be empty.
                        """
                        .formatted(contextTypeElement);
        userMessageText.append(userPrompt);

        // Construct final message list including workspace representation
        List<ChatMessage> messages = Stream.concat(
                        Stream.of(finalSystemMessage),
                        Stream.concat(
                                workspaceRepresentation.stream(),
                                Stream.of(new UserMessage(userMessageText.toString()))))
                .toList();

        int promptTokens = Messages.getApproximateTokens(messages);
        debug("Invoking LLM to recommend context via tool call (prompt size ~{} tokens)", promptTokens);

        // *** Execute LLM call with required tool ***
        var result = llm.sendRequest(messages, toolSpecs, ToolChoice.REQUIRED, false);
        var tokenUsage = result.tokenUsage();
        if (result.error() != null || result.isEmpty()) {
            var error = result.error();

            // litellm does an inconsistent job translating into ContextWindowExceededError.
            // https://github.com/BrokkAi/brokk/issues/540
            if (error != null
                    && error.getMessage() != null
                    && error.getMessage().contains("context")) {
                throw new ContextTooLargeException();
            }

            // not a context problem
            logger.warn(
                    "Error or empty response from LLM during context recommendation: {}. Returning empty",
                    error != null ? error.getMessage() : "Empty response");
            return LlmRecommendation.EMPTY;
        }
        var toolRequests = result.toolRequests();
        debug("LLM ToolRequests: {}", toolRequests);
        // only one call is necessary but handle LLM making multiple calls
        for (var request : toolRequests) {
            cm.getToolRegistry().executeTool(contextTool, request);
        }

        // turn Strings into ProjectFile and CodeUnit objects
        var projectFiles = toProjectFiles(contextTool.getRecommendedFiles());
        var projectClasses = contextTool.getRecommendedClasses().stream()
                // Use getDefinition to get the CodeUnit directly, which handles file lookup
                .map(analyzer::getDefinition)
                .flatMap(Optional::stream) // Convert java.util.Optional to Stream
                .filter(CodeUnit::isClass) // Ensure it's actually a class
                .collect(Collectors.toSet());

        debug(
                "Tool recommended files: {}",
                projectFiles.stream().map(ProjectFile::getFileName).collect(Collectors.joining(", ")));
        debug(
                "Tool recommended classes: {}",
                projectClasses.stream().map(CodeUnit::identifier).collect(Collectors.joining(", ")));
        return new LlmRecommendation(projectFiles, projectClasses, result.text(), tokenUsage);
    }

    private Set<ProjectFile> toProjectFiles(List<String> filenames) {
        return filenames.stream()
                .map(fname -> {
                    try {
                        return cm.toFile(fname);
                    } catch (IllegalArgumentException e) {
                        // absolute or malformed path
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .filter(ProjectFile::exists)
                .collect(Collectors.toSet());
    }

    private static class ContextTooLargeException extends Exception {}

    // --- Quick Scan (Simple Prompt) Recommendation ---

    /** Enum to define the type of context input being provided to the simple LLM prompt. */
    private enum ContextInputType {
        SUMMARIES("available_summaries", "code summaries", "classes", "fully qualified names"),
        FILES_CONTENT("available_files_content", "files with their content", "files", "full paths"),
        FILE_PATHS("available_file_paths", "file paths", "files", "full paths");

        final String xmlTag;
        final String description; // e.g., "code summaries"
        final String itemTypePlural; // e.g., "classes" or "files"
        final String identifierDescription; // e.g., "fully qualified names" or "full paths"

        ContextInputType(String xmlTag, String description, String itemTypePlural, String identifierDescription) {
            this.xmlTag = xmlTag;
            this.description = description;
            this.itemTypePlural = itemTypePlural;
            this.identifierDescription = identifierDescription;
        }
    }

    private LlmRecommendation askLlmQuickRecommendContext(
            List<String> filenames,
            Map<CodeUnit, String> summaries,
            Map<ProjectFile, String> contentsMap,
            Collection<ChatMessage> workspaceRepresentation)
            throws InterruptedException {
        String reasoning = "LLM recommended via simple prompt.";
        List<ProjectFile> recommendedFiles = List.of();
        List<CodeUnit> recommendedClasses = List.of();
        List<String> responseLines;

        if (!summaries.isEmpty()) {
            var summariesText = summaries.entrySet().stream()
                    .map(entry -> "<class fqcn='%s'>\n%s\n</class>"
                            .formatted(entry.getKey().fqName(), entry.getValue()))
                    .collect(Collectors.joining("\n\n"));
            responseLines = simpleRecommendItems(
                    ContextInputType.SUMMARIES, summariesText, QUICK_TOPK, workspaceRepresentation);

            // Find original CodeUnit objects matching the response lines
            recommendedClasses = summaries.keySet().stream()
                    .filter(cu -> responseLines.contains(cu.fqName()))
                    .toList();
            debug("LLM simple suggested {} relevant classes", recommendedClasses.size());

            // Apply topK limit *after* matching
            if (recommendedClasses.size() > QUICK_TOPK) {
                recommendedClasses = recommendedClasses.subList(0, QUICK_TOPK);
            }
        } else if (!contentsMap.isEmpty()) {
            var filesText = contentsMap.entrySet().stream()
                    .map(entry -> "<file path='%s'>\n%s\n</file>"
                            .formatted(entry.getKey().toString(), entry.getValue()))
                    .collect(Collectors.joining("\n\n"));
            responseLines = simpleRecommendItems(
                    ContextInputType.FILES_CONTENT, filesText, QUICK_TOPK, workspaceRepresentation);

            // Find original ProjectFile objects matching the response lines
            recommendedFiles = contentsMap.keySet().stream()
                    .filter(pf -> responseLines.contains(pf.toString()))
                    .toList();
            debug("LLM simple suggested {} relevant files", recommendedFiles.size());

            // Apply topK limit *after* matching
            if (recommendedFiles.size() > QUICK_TOPK) {
                recommendedFiles = recommendedFiles.subList(0, QUICK_TOPK);
            }
        } else { // Filenames only
            var filenameString = String.join("\n", filenames);
            responseLines = simpleRecommendItems(
                    ContextInputType.FILE_PATHS, filenameString, null, workspaceRepresentation); // No topK for pruning

            // Convert response strings back to ProjectFile objects
            recommendedFiles = new ArrayList<>(toProjectFiles(responseLines));
            debug("LLM simple suggested {} relevant files after pruning", recommendedFiles.size());
        }

        debug(
                "Quick scan recommended files: {}",
                recommendedFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(", ")));
        debug(
                "Quick scan recommended classes: {}",
                recommendedClasses.stream().map(CodeUnit::identifier).collect(Collectors.joining(", ")));
        return new LlmRecommendation(recommendedFiles, recommendedClasses, reasoning);
    }

    /**
     * Generic method to ask the LLM (Quick mode) to select relevant items (classes or files) based on input data.
     *
     * @param inputType The type of input being provided (summaries, file contents, or file paths).
     * @param inputBlob A string containing the formatted input data (e.g., XML-like structure of summaries or file
     *     contents).
     * @param topK Optional limit on the number of items to return.
     * @param workspaceRepresentation Messages representing the current workspace state.
     * @return A list of strings representing the identifiers (FQNs or paths) recommended by the LLM.
     */
    private List<String> simpleRecommendItems(
            ContextInputType inputType,
            String inputBlob,
            @Nullable Integer topK,
            Collection<ChatMessage> workspaceRepresentation)
            throws InterruptedException {
        var systemMessage = new StringBuilder(
                """
                        You are an assistant that identifies relevant %s based on a goal.
                        Given a list of %s and a goal, identify which %s are most relevant to achieving the goal.
                        Output *only* the %s of the relevant %s, one per line, ordered from most to least relevant.
                        Do not include any other text, explanations, or formatting.
                        """
                        .formatted(
                                inputType.itemTypePlural,
                                inputType.description,
                                inputType.itemTypePlural,
                                inputType.identifierDescription,
                                inputType.itemTypePlural)
                        .stripIndent());

        if (topK != null) {
            systemMessage.append(
                    "\nLimit your response to the top %d most relevant %s.".formatted(topK, inputType.itemTypePlural));
        }

        var finalSystemMessage = new SystemMessage(systemMessage.toString());
        var userPrompt =
                """
                        <goal>
                        %s
                        </goal>

                        <%s>
                        %s
                        </%s>

                        Which of these %s are most relevant to the goal? Take into consideration what is already in the workspace (provided as prior messages), and list their %s, one per line.
                        """
                        .formatted(
                                goal,
                                inputType.xmlTag,
                                inputBlob,
                                inputType.xmlTag,
                                inputType.itemTypePlural,
                                inputType.identifierDescription)
                        .stripIndent();

        List<ChatMessage> messages = Stream.concat(
                        Stream.of(finalSystemMessage),
                        Stream.concat(workspaceRepresentation.stream(), Stream.of(new UserMessage(userPrompt))))
                .toList();
        int promptTokens = Messages.getApproximateTokens(messages);
        debug(
                "Invoking LLM (Quick) to select relevant {} (prompt size ~{} tokens)",
                inputType.itemTypePlural,
                promptTokens);
        var result = llm.sendRequest(messages); // No tools

        if (result.error() != null || result.isEmpty()) {
            logger.warn(
                    "Error ({}) from LLM during quick %s selection: {}. Returning empty",
                    result.error() != null ? result.error().getMessage() : "empty response", inputType.itemTypePlural);
            return List.of();
        }

        var responseLines = result.text()
                .lines()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
        debug("LLM simple response lines ({}): {}", inputType.itemTypePlural, responseLines);
        return responseLines;
    }

    // --- Logic branch for using full file contents (when analyzer is not available or summaries failed) ---

    private RecommendationResult executeWithFileContents(
            List<ProjectFile> filesToConsider,
            Set<ProjectFile> existingFiles,
            Collection<ChatMessage> workspaceRepresentation,
            boolean allowSkipPruning)
            throws InterruptedException, ContextTooLargeException {
        // If the workspace isn't empty and we have no analyzer, don't suggest adding whole files.
        if (analyzer.isEmpty() && !cm.getEditableFiles().isEmpty()) {
            debug("Non-empty context and no analyzer present, skipping file content suggestions");
            return new RecommendationResult(
                    true, List.of(), "Skipping file content suggestions for non-empty context without analyzer.");
        }

        var contentsMap = readFileContents(filesToConsider);
        int contentTokens = Messages.getApproximateTokens(String.join("\n", contentsMap.values()));
        debug("Total tokens for {} files' content: {}", contentsMap.size(), contentTokens);

        // Rule 1: Use all available files if content fits the smallest budget and meet the limit (if not deepScan)
        boolean withinLimit = deepScan || filesToConsider.size() <= QUICK_TOPK; // Use QUICK_TOPK here
        if (allowSkipPruning && contentTokens <= skipPruningBudget && withinLimit) {
            return createResult(
                    new LlmRecommendation(filesToConsider, List.of(), "All file contents are within token budget"),
                    existingFiles);
        }

        if (contentTokens > budgetPruning) {
            throw new ContextTooLargeException();
        }

        // Rule 2: Ask LLM to pick relevant files if all content fits the Pruning budget
        LlmRecommendation llmRecommendation =
                askLlmToRecommendContext(List.of(), Map.of(), contentsMap, workspaceRepresentation);
        return createResult(llmRecommendation, existingFiles);
    }

    private Map<ProjectFile, String> readFileContents(Collection<ProjectFile> files) {
        return files.stream()
                .distinct()
                .parallel()
                .map(file -> {
                    var content = file.read().orElse("");
                    return Map.entry(file, content);
                })
                .filter(entry -> !entry.getValue().isEmpty()) // Filter out files that couldn't be read
                // Use merge function (v1, v2) -> v1 to handle potential duplicate keys by keeping the first value
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1));
    }

    // --- Helper methods ---

    private void logGiveUp(String itemDescription) {
        debug("Budget exceeded even for {}. Giving up on automatic context population.", itemDescription);
    }

    /**
     * Combines two Llm.RichTokenUsage instances by summing their respective token counts. Handles null inputs by
     * returning the non-null one, or null if both are null.
     */
    private static @Nullable Llm.RichTokenUsage addTokenUsage(
            @Nullable Llm.RichTokenUsage a, @Nullable Llm.RichTokenUsage b) {
        if (a == null) return b;
        if (b == null) return a;
        return new Llm.RichTokenUsage(
                a.inputTokens() + b.inputTokens(),
                a.cachedInputTokens() + b.cachedInputTokens(),
                a.thinkingTokens() + b.thinkingTokens(),
                a.outputTokens() + b.outputTokens());
    }
}
