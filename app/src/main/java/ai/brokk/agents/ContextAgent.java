package ai.brokk.agents;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

import ai.brokk.*;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.concurrent.AdaptiveExecutor;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.SpecialTextType;
import ai.brokk.git.GitDistance;
import ai.brokk.project.ModelProperties.ModelType;
import ai.brokk.prompts.WorkspacePrompts;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolOutput;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.util.Lines;
import ai.brokk.util.Messages;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.ContextTooLargeException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * ContextAgent looks for code in the current Project relevant to the given query/goal.
 *
 * It does this by
 * 1. Identifying candidates
 * 2. Asking the LLM to select relevant candidates
 *
 * Candidate identification is done as follows:
 * 1. If there are files in the Workspace, ask GitDistance for the most relevant related files.
 * 2. Otherwise, all Project files are candidates.
 *
 * Candidate selection is done by partitioning files into groups:
 * 1. Primary Files (Analyzed): Deep evaluation using symbol summaries/skeletons.
 * 2. Primary Files (Unanalyzed): Deep evaluation using capped/truncated source text.
 * 3. Test Files: Lightweight filename-only pruning to identify relevant tests.
 *
 * For groups 1 & 2, if the total candidate set exceeds the evaluation budget, an initial
 * filename-pruning pass (budgeted at 100k tokens) is used to narrow the set before deep evaluation.
 * Test files (group 3) are always processed via filename-only pruning to conserve budget.
 *
 * Finally, if there are files that the Analyzer does not know how to summarize, ContextAgent will do
 * full-content analysis (but since these are so much larger, necessarily we will be able to fit much
 * fewer into the window).
 */
public class ContextAgent {
    private static final Logger logger = LogManager.getLogger(ContextAgent.class);

    private static final int DESIRED_CANDIDATES = 100;

    private static final int UNANALYZED_MAX_LINES = 50;
    private static final int UNANALYZED_TOP_SHOWN = 25;
    private static final int UNANALYZED_BOTTOM_SHOWN = 25;

    enum GroupType {
        ANALYZED,
        UNANALYZED
    }

    private final IContextManager cm;
    private final String goal;
    private final IAnalyzer analyzer;
    private final StreamingChatModel model;
    private final IConsoleIO io;

    /** Budget for the evaluate-for-relevance stage (uncapped *0.65 of input context). */
    private final int evaluationBudget;

    /**
     * Budget for the filename-only pruning stage.
     * This is capped at 100k tokens to ensure even very large file sets
     * can be narrowed down efficiently without context window overflow.
     */
    private final int filesPruningBudget;

    public ContextAgent(IContextManager contextManager, StreamingChatModel model, String goal)
            throws InterruptedException {
        this(contextManager, model, goal, contextManager.getIo());
    }

    public ContextAgent(IContextManager contextManager, StreamingChatModel model, String goal, IConsoleIO io)
            throws InterruptedException {
        this.cm = contextManager;
        this.goal = goal;
        this.model = model;
        this.io = io;
        this.analyzer = contextManager.getAnalyzer();

        // Token budgeting: estimate the usable input-context budget by reserving an output (completion) budget.
        // Some model implementations don't provide a default maxCompletionTokens (it may be null), so fall back
        // to a conservative default to avoid NPEs and to keep prompt budgeting deterministic.
        Integer maxCompletionTokens = model.defaultRequestParameters().maxCompletionTokens();
        int outputTokens = maxCompletionTokens != null ? maxCompletionTokens : 4096;
        int actualInputTokens = contextManager.getService().getMaxInputTokens(model) - outputTokens;
        // god, our estimation is so bad (yes we do observe the ratio being this far off)
        this.evaluationBudget = (int) (actualInputTokens * 0.65);
        this.filesPruningBudget = min(100_000, evaluationBudget);
        logger.debug(
                "ContextAgent initialized. Budgets: FilesPruning={}, Evaluation={}",
                filesPruningBudget,
                evaluationBudget);
    }

    /** Result record for context recommendation attempts, including token usage of the LLM call (nullable). */
    public record RecommendationResult(
            boolean success, List<ContextFragment> fragments, @Nullable Llm.ResponseMetadata metadata) {}

    /** Result record for the LLM tool call, holding recommended files, class names, and token usage. */
    record LlmRecommendation(
            Set<ProjectFile> recommendedFiles,
            Set<CodeUnit> recommendedClasses,
            @Nullable Llm.ResponseMetadata tokenUsage) {
        static final LlmRecommendation EMPTY = new LlmRecommendation(Set.of(), Set.of(), null);

        public LlmRecommendation(List<ProjectFile> files, List<CodeUnit> classes) {
            this(new HashSet<>(files), new HashSet<>(classes), null);
        }

        LlmRecommendation withUsage(@Nullable Llm.ResponseMetadata newUsage) {
            return new LlmRecommendation(recommendedFiles, recommendedClasses, newUsage);
        }
    }

    record RecommendationToolOutput(String llmText, List<String> filesToAdd, List<String> classesToSummarize)
            implements ToolOutput {
        RecommendationToolOutput {
            filesToAdd = List.copyOf(filesToAdd);
            classesToSummarize = List.copyOf(classesToSummarize);
        }
    }

    @Tool(
            "Recommend relevant files and classes needed to achieve the user's goal. All file paths must exactly match a file path provided in the request; do not invent paths.")
    public RecommendationToolOutput recommendContext(
            @P(
                            "List of full paths of files to edit or whose full text is necessary. Must exactly match one of the file paths provided in the request.")
                    List<String> filesToAdd,
            @P(
                            "List of fully-qualified class names for classes whose APIs are relevant to the goal but which do not need to be edited.")
                    List<String> classesToSummarize) {
        logger.debug("ContextAgent called recommendContext: files={}, classes={}", filesToAdd, classesToSummarize);
        return new RecommendationToolOutput("Recommended files and classes.", filesToAdd, classesToSummarize);
    }

    @Tool(
            "Recommend relevant files needed to achieve the user's goal. Use this when class summaries are not available. All file paths must exactly match a file path provided in the request; do not invent paths.")
    public RecommendationToolOutput recommendFiles(
            @P(
                            "List of full paths of files to edit or whose full text is necessary. Must exactly match one of the file paths provided in the request.")
                    List<String> filesToAdd) {
        logger.debug("ContextAgent called recommendFiles: files={}", filesToAdd);
        return new RecommendationToolOutput("Recommended files.", filesToAdd, List.of());
    }

    /** Calculates the approximate token count for a list of ContextFragments. */
    @Blocking
    public int calculateFragmentTokens(List<ContextFragment> fragments) {
        int totalTokens = 0;
        for (var fragment : fragments) {
            if (fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                Optional<ProjectFile> fileOpt =
                        fragment.sourceFiles().join().stream().findFirst();
                if (fileOpt.isPresent()) {
                    var file = fileOpt.get();
                    String content = file.read().orElse("");
                    totalTokens += Messages.getApproximateTokens(content);
                } else {
                    logger.debug(
                            "PROJECT_PATH fragment {} did not yield a ProjectFile for token calculation.",
                            fragment.description());
                }
            } else if (fragment.getType() == ContextFragment.FragmentType.SKELETON) {
                totalTokens += Messages.getApproximateTokens(fragment.text().join());
            } else {
                logger.warn("Unhandled ContextFragment type for token calculation: {}", fragment.getClass());
            }
        }
        return totalTokens;
    }

    /**
     * Overload for {@link #getRecommendations(Context, boolean)} with turbo disabled.
     */
    @Blocking
    public RecommendationResult getRecommendations(Context context) throws InterruptedException {
        return getRecommendations(context, false);
    }

    /**
     * Determines the best initial context based on project size and budgets.
     *
     * This method partitions candidates into analyzed primary files, unanalyzed primary files,
     * and test files. Primary files are processed in parallel groups for deep relevance
     * evaluation. Test files are processed via a lightweight filename-only pruning pass
     * and are always returned as summaries (skeletons) to stay within token budgets.
     *
     * @param turbo if true, uses a symbolic workspace overview instead of full fragment contents.
     * @return A RecommendationResult containing success status, fragments, and metadata.
     */
    @Blocking
    public RecommendationResult getRecommendations(Context context, boolean turbo) throws InterruptedException {
        List<ChatMessage> workspaceRepresentation;
        if (turbo) {
            workspaceRepresentation = List.of(
                    UserMessage.from("<workspace_summary>\n" + context.overview() + "\n</workspace_summary>"),
                    new AiMessage("Thank you for the workspace summary."));
        } else {
            workspaceRepresentation = WorkspacePrompts.getMessagesInAddedOrder(
                    context, EnumSet.of(SpecialTextType.TASK_LIST, SpecialTextType.CODE_AGENT_CHANGES));
        }

        // Subtract workspace tokens from both budgets.
        int workspaceTokens = Messages.getApproximateMessageTokens(workspaceRepresentation);
        int evalBudgetRemaining = evaluationBudget - workspaceTokens;
        int pruneBudgetRemaining = filesPruningBudget - workspaceTokens;
        logger.debug(
                "Budgets after workspace: evalRemaining={}, pruneRemaining={}",
                evalBudgetRemaining,
                pruneBudgetRemaining);
        // If there's no budget left after we include the Workspace, quit
        if (evalBudgetRemaining < 1000) {
            return new RecommendationResult(false, List.of(), null);
        }

        // Candidates are most-relevant files to the Workspace, or entire Project if Workspace is empty
        var existingFiles = context.allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.PROJECT_PATH
                        || f.getType() == ContextFragment.FragmentType.SKELETON)
                .flatMap(f -> f.sourceFiles().join().stream())
                .collect(Collectors.toSet());
        List<ProjectFile> candidates;
        if (existingFiles.isEmpty()) {
            candidates = cm.getProject().getAllFiles().stream().sorted().toList();
            logger.debug("Empty workspace; using all files ({}) for context recommendation.", candidates.size());
        } else {
            int maxNeighbors = DESIRED_CANDIDATES;
            var envMaxNeighbors = System.getenv("BRK_MAX_NEIGHBORS");
            if (envMaxNeighbors != null) {
                try {
                    maxNeighbors = Integer.parseInt(envMaxNeighbors);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid BRK_MAX_NEIGHBORS value '{}', using default", envMaxNeighbors);
                }
            }
            candidates = context.getMostRelevantFiles(maxNeighbors).stream()
                    .filter(f -> !existingFiles.contains(f))
                    .sorted()
                    .toList();
            if (candidates.size() >= 10) {
                logger.debug(
                        "Non-empty workspace; using Git-based distance candidates (count: {}).", candidates.size());
            } else {
                logger.debug(
                        "Non-empty workspace but few/no Git-based candidates found; escalating to full project scan");
                candidates = cm.getProject().getAllFiles().stream()
                        .filter(f -> !existingFiles.contains(f))
                        .sorted()
                        .toList();
            }
        }

        // Partition candidates into non-test (primary) and test files.
        // We treat tests differently to optimize token budget: primary files get full evaluation,
        // while tests are filtered by filename and included as skeletons.
        var partitionedCandidates =
                candidates.stream().collect(Collectors.partitioningBy(f -> ContextManager.isTestFile(f, analyzer)));
        List<ProjectFile> primaryCandidates =
                requireNonNull(partitionedCandidates.get(false), "partitioningBy must contain key false");
        List<ProjectFile> testCandidates =
                requireNonNull(partitionedCandidates.get(true), "partitioningBy must contain key true");

        Set<ProjectFile> analyzedFileSet = primaryCandidates.stream()
                .filter(pf -> !analyzer.getTopLevelDeclarations(pf).isEmpty())
                .collect(Collectors.toSet());
        List<ProjectFile> analyzedFiles = primaryCandidates.stream()
                .filter(analyzedFileSet::contains)
                .sorted()
                .toList();
        boolean skipUnanalyzed = "true".equalsIgnoreCase(System.getenv("BRK_SKIP_UNANALYZED"));
        List<ProjectFile> unAnalyzedFiles = skipUnanalyzed
                ? List.of()
                : primaryCandidates.stream()
                        .filter(f -> !analyzedFileSet.contains(f))
                        .sorted()
                        .toList();
        logger.debug(
                "Grouped candidates: analyzed={}, unAnalyzed={}, tests={} (skipped unanalyzed={})",
                analyzedFiles.size(),
                unAnalyzedFiles.size(),
                testCandidates.size(),
                skipUnanalyzed);

        var filesModel = cm.getService().getModel(ModelType.SUMMARIZE);

        // Create Llm instances - only analyzed group streams to UI
        var filesOpts = new Llm.Options(filesModel, "ContextAgent Files (Analyzed): " + goal, TaskResult.Type.SCAN)
                .withForceReasoningEcho()
                .withEcho();
        var filesLlmAnalyzed = cm.getLlm(filesOpts);
        filesLlmAnalyzed.setOutput(io);

        var filesLlmUnanalyzed = cm.getLlm(
                new Llm.Options(filesModel, "ContextAgent Files (Unanalyzed): " + goal, TaskResult.Type.SCAN));
        filesLlmUnanalyzed.setOutput(io);

        var filesLlmTests =
                cm.getLlm(new Llm.Options(filesModel, "ContextAgent Files (Tests): " + goal, TaskResult.Type.SCAN));
        filesLlmTests.setOutput(io);

        var analyzedOpts = new Llm.Options(model, "ContextAgent (Analyzed): " + goal, TaskResult.Type.SCAN)
                .withForceReasoningEcho()
                .withEcho();
        var llmAnalyzed = cm.getLlm(analyzedOpts);
        llmAnalyzed.setOutput(io);

        var llmUnanalyzed =
                cm.getLlm(new Llm.Options(model, "ContextAgent (Unanalyzed): " + goal, TaskResult.Type.SCAN));
        llmUnanalyzed.setOutput(io);

        // Process non-test groups in parallel first (tests depend on their results).
        LlmRecommendation[] nonTestResults = new LlmRecommendation[2];
        Throwable[] nonTestErrors = new Throwable[2];

        Thread t1 = Thread.ofVirtual().start(() -> {
            try {
                nonTestResults[0] = processGroup(
                        GroupType.ANALYZED,
                        analyzedFiles,
                        workspaceRepresentation,
                        evalBudgetRemaining,
                        pruneBudgetRemaining,
                        filesLlmAnalyzed,
                        llmAnalyzed);
            } catch (Throwable t) {
                nonTestErrors[0] = t;
            }
        });

        Thread t2 = Thread.ofVirtual().start(() -> {
            try {
                nonTestResults[1] = processGroup(
                        GroupType.UNANALYZED,
                        unAnalyzedFiles,
                        workspaceRepresentation,
                        evalBudgetRemaining,
                        pruneBudgetRemaining,
                        filesLlmUnanalyzed,
                        llmUnanalyzed);
            } catch (Throwable t) {
                nonTestErrors[1] = t;
            }
        });

        t1.join();
        t2.join();
        for (var err : nonTestErrors) {
            // array entries are initialized to null, null check is not redundant
            if (err != null) {
                throw new RuntimeException(err);
            }
        }

        var analyzedRec = nonTestResults[0];
        var unAnalyzedRec = nonTestResults[1];

        var mergedFiles = new HashSet<>(analyzedRec.recommendedFiles());
        mergedFiles.addAll(unAnalyzedRec.recommendedFiles());

        Context rankingContext = context.addFragments(cm.toPathFragments(mergedFiles));
        List<ChatMessage> rankingOverview = List.of(
                UserMessage.from("<workspace_summary>\n" + rankingContext.overview() + "\n</workspace_summary>"),
                new AiMessage("Thank you for the workspace summary."));

        int rankingWorkspaceTokens = Messages.getApproximateMessageTokens(rankingOverview);
        int testPruneBudgetRemaining = filesPruningBudget - rankingWorkspaceTokens;

        LlmRecommendation testsRec = LlmRecommendation.EMPTY;
        if (!testCandidates.isEmpty()) {
            logger.debug("Processing {} test candidates via filename-only pass.", testCandidates.size());
            var testFilenames =
                    testCandidates.stream().map(ProjectFile::toString).toList();
            var testRec = askLlmDeepPruneFilenamesWithChunking(
                    testFilenames, rankingOverview, min(50_000, testPruneBudgetRemaining), filesLlmTests, false);

            testsRec = new LlmRecommendation(testRec.recommendedFiles(), Set.of(), testRec.tokenUsage());
        }
        List<ProjectFile> inferredTests = testsRec.recommendedFiles().stream().toList();

        boolean success = !analyzedRec.recommendedFiles().isEmpty()
                || !analyzedRec.recommendedClasses().isEmpty()
                || !unAnalyzedRec.recommendedFiles().isEmpty()
                || !unAnalyzedRec.recommendedClasses().isEmpty()
                || !inferredTests.isEmpty();

        // Union files and classes from analyzed/unanalyzed groups.
        var mergedClasses = new HashSet<>(analyzedRec.recommendedClasses());
        mergedClasses.addAll(unAnalyzedRec.recommendedClasses());

        var combinedUsage = Llm.ResponseMetadata.sum(
                Llm.ResponseMetadata.sum(analyzedRec.tokenUsage(), unAnalyzedRec.tokenUsage()), testsRec.tokenUsage());

        // Rank inferred tests by relevance to (input context + non-test recommendations) and cap at 3.
        Set<ProjectFile> mergedTests = capRecommendedTests(rankingContext, new HashSet<>(inferredTests));

        var unifiedRec = new LlmRecommendation(mergedFiles, mergedClasses, combinedUsage);
        var result = createResult(unifiedRec, mergedTests.stream().toList(), existingFiles);

        return new RecommendationResult(success, result, combinedUsage);
    }

    private int estimateAnalyzedTokens(Collection<ProjectFile> files) {
        var summariesByFile = getCachedIdentifiers(files);
        return Messages.getApproximateTokens(summariesByFile.values());
    }

    // --- Group processing ---

    /**
     * Processes a group of primary candidates (Analyzed or Unanalyzed) for relevance.
     * If the group is too large, it performs an initial filename-pruning pass
     * before the final evaluate-with-halving stage.
     */
    private LlmRecommendation processGroup(
            GroupType type,
            List<ProjectFile> groupFiles,
            Collection<ChatMessage> workspaceRepresentation,
            int evalBudgetRemaining,
            int pruneBudgetRemaining,
            Llm filesLlm,
            Llm llm)
            throws InterruptedException {

        if (groupFiles.isEmpty()) {
            return new LlmRecommendation(Set.of(), Set.of(), null);
        }

        // Build initial payload preview for token estimation
        int initialTokens;
        if (type == GroupType.ANALYZED) {
            initialTokens = estimateAnalyzedTokens(groupFiles);
        } else {
            var contentsMap = readFileContentsCappedForPrompt(groupFiles);
            initialTokens = Messages.getApproximateTokens(contentsMap.values().stream()
                    .map(Lines.HeadTail::promptText)
                    .toList());
        }

        logger.debug("{} group initial token estimate: ~{}", type, initialTokens);

        List<ProjectFile> workingFiles = groupFiles;
        @Nullable Llm.ResponseMetadata usage = null;
        // If too large for evaluation, ask for interesting files (files-pruning stage with 100k cap)
        boolean forcePrune = "true".equalsIgnoreCase(System.getenv("BRK_FORCE_FILENAME_PRUNE"));
        if (forcePrune || initialTokens > evalBudgetRemaining) {
            logger.debug(
                    "{} group exceeds evaluation budget ({} > {}); pruning filenames first.",
                    type,
                    initialTokens,
                    evalBudgetRemaining);
            var filenames = groupFiles.stream().map(ProjectFile::toString).toList();
            var pruneRec = askLlmDeepPruneFilenamesWithChunking(
                    filenames, workspaceRepresentation, pruneBudgetRemaining, filesLlm, type == GroupType.ANALYZED);
            usage = Llm.ResponseMetadata.sum(usage, pruneRec.tokenUsage());

            var prunedFiles = pruneRec.recommendedFiles();
            if (prunedFiles.isEmpty()) {
                logger.debug("{} group: filename pruning produced an empty set.", type);
                return new LlmRecommendation(Set.of(), Set.of(), usage);
            }

            // Expand to include most-relevant neighbors
            if (type == GroupType.ANALYZED && prunedFiles.size() < DESIRED_CANDIDATES) {
                workingFiles = expandCandidates(prunedFiles, groupFiles);
            } else {
                workingFiles = List.copyOf(prunedFiles);
            }

            int postExpansionTokens;
            if (type == GroupType.ANALYZED) {
                postExpansionTokens = estimateAnalyzedTokens(workingFiles);
            } else {
                var contentsMap = readFileContentsCappedForPrompt(workingFiles);
                postExpansionTokens = Messages.getApproximateTokens(contentsMap.values().stream()
                        .map(Lines.HeadTail::promptText)
                        .toList());
            }
            logger.debug("{} group post-expansion token estimate: ~{}", type, postExpansionTokens);
        }

        // Evaluate-for-relevance stage: call LLM with a context window containing ONLY this group's data.
        // If we still get a context-window error, iteratively cut off the least important half.
        LlmRecommendation evalRec =
                evaluateWithHalving(type, workingFiles, workspaceRepresentation, llm, type == GroupType.ANALYZED);
        usage = Llm.ResponseMetadata.sum(usage, evalRec.tokenUsage());
        return evalRec.withUsage(usage);
    }

    private List<ProjectFile> expandCandidates(Set<ProjectFile> seedFiles, List<ProjectFile> eligiblePool)
            throws InterruptedException {
        // Create a temporary context containing the pruned files
        Context tempContext = new Context(cm).addFragments(cm.toPathFragments(seedFiles));

        // 2* b/c we're going to filter to eligiblePool
        var relevantNeighbors = tempContext.getMostRelevantFiles(2 * DESIRED_CANDIDATES);

        // Union seeds and neighbors, restricted to the eligible group pool
        var expandedSet = new HashSet<>(seedFiles);
        var eligibleSet = new HashSet<>(eligiblePool);
        relevantNeighbors.stream()
                .filter(eligibleSet::contains)
                .limit(DESIRED_CANDIDATES - seedFiles.size())
                .forEach(expandedSet::add);

        logger.debug("Expanded candidates to {}", expandedSet);
        return expandedSet.stream().sorted().toList();
    }

    /**
     * Evaluates relevance for the current file set, halving on context overflow.
     */
    LlmRecommendation evaluateWithHalving(
            GroupType type,
            List<ProjectFile> files,
            Collection<ChatMessage> workspaceRepresentation,
            Llm llm,
            boolean allowClassSummaries)
            throws InterruptedException {

        List<ProjectFile> current = new ArrayList<>(files);
        while (true) {
            Map<ProjectFile, Lines.HeadTail> fileText = type == GroupType.ANALYZED
                    ? getCachedIdentifiers(current).entrySet().stream()
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey, e -> Lines.HeadTail.full(e.getValue()), (v1, v2) -> v1))
                    : readFileContentsCappedForPrompt(current);

            try {
                return askLlmDeepRecommendContext(fileText, workspaceRepresentation, llm, allowClassSummaries);
            } catch (ContextTooLargeException e) {
                if (current.size() <= 1) {
                    logger.debug("{} group still too large with a single file; returning empty.", type);
                    return LlmRecommendation.EMPTY;
                }
                // Sort by importance and cut off least-important half
                var sorted = GitDistance.sortByImportance(current, cm.getRepo());
                int keep = Math.max(1, (sorted.size() + 1) / 2);
                current = new ArrayList<>(sorted.subList(0, keep));
                logger.debug("{} group context too large; halving to {} files and retrying.", type, current.size());
            }
        }
    }

    private final ConcurrentMap<ProjectFile, String> identifiersByFile = new ConcurrentHashMap<>();

    /**
     * Returns a map of ProjectFile -> identifiers text (summaries of classes in the file).
     * Files with empty identifiers are omitted from the result.
     */
    private Map<ProjectFile, String> getCachedIdentifiers(Collection<ProjectFile> candidates) {
        return candidates.parallelStream()
                .distinct()
                .map(f -> Map.entry(f, identifiersByFile.computeIfAbsent(f, pf -> analyzer.summarizeSymbols(pf))))
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1));
    }

    // --- Result assembly ---

    List<ContextFragment> createResult(
            LlmRecommendation llmRecommendation, List<ProjectFile> recommendedTests, Set<ProjectFile> existingFiles) {
        var originalFiles = llmRecommendation.recommendedFiles();
        var filteredFiles =
                originalFiles.stream().filter(f -> !existingFiles.contains(f)).toList();
        if (filteredFiles.size() != originalFiles.size()) {
            logger.debug(
                    "Post-filtered LLM recommended files from {} to {} by excluding already-present files",
                    originalFiles.size(),
                    filteredFiles.size());
        }

        var filteredTests = recommendedTests.stream()
                .filter(f -> !existingFiles.contains(f))
                .filter(f -> !filteredFiles.contains(f))
                .toList();
        if (filteredTests.size() != recommendedTests.size()) {
            logger.debug(
                    "Post-filtered LLM recommended tests from {} to {} by excluding already-present or selected files",
                    recommendedTests.size(),
                    filteredTests.size());
        }

        var originalClassCount = llmRecommendation.recommendedClasses().size();
        var recommendedClasses = llmRecommendation.recommendedClasses().stream()
                .filter(cu -> !filteredFiles.contains(cu.source()))
                .filter(cu -> !filteredTests.contains(cu.source()))
                .filter(cu -> !existingFiles.contains(cu.source()))
                .toList();
        if (recommendedClasses.size() != originalClassCount) {
            logger.debug(
                    "Post-filtered LLM recommended classes from {} to {} by excluding those whose source files are already present or selected",
                    originalClassCount,
                    recommendedClasses.size());
        }

        var recommendedSummaries = getSummaries(recommendedClasses);

        int recommendedSummaryTokens = Messages.getApproximateTokens(String.join("\n", recommendedSummaries.values()));
        var recommendedContentsMap = readFileContents(filteredFiles);
        int recommendedContentTokens =
                Messages.getApproximateTokens(String.join("\n", recommendedContentsMap.values()));

        var recommendedTestContentsMap = getCachedIdentifiers(filteredTests);
        int recommendedTestTokens =
                Messages.getApproximateTokens(String.join("\n", recommendedTestContentsMap.values()));

        int totalRecommendedTokens = recommendedSummaryTokens + recommendedContentTokens + recommendedTestTokens;

        logger.debug(
                "LLM recommended {} classes ({} tokens), {} files ({} tokens), and {} tests (as summaries, {} tokens). Total: {} tokens",
                recommendedSummaries.size(),
                recommendedSummaryTokens,
                filteredFiles.size(),
                recommendedContentTokens,
                filteredTests.size(),
                recommendedTestTokens,
                totalRecommendedTokens);

        var summaryFragments = summaryPerCodeUnit(cm, recommendedSummaries);

        var testFragments = filteredTests.stream()
                .map(f -> (ContextFragment) new ContextFragments.SummaryFragment(
                        cm, f.toString(), ContextFragment.SummaryType.FILE_SKELETONS))
                .toList();

        var pathFragments = filteredFiles.stream()
                .map(f -> (ContextFragment) new ContextFragments.ProjectPathFragment(f, cm))
                .toList();
        return Stream.concat(Stream.concat(summaryFragments.stream(), testFragments.stream()), pathFragments.stream())
                .toList();
    }

    /** one SummaryFragment per code unit so ArchitectAgent can easily ask user which ones to include */
    private static List<ContextFragment> summaryPerCodeUnit(
            IContextManager contextManager, Map<CodeUnit, String> relevantSummaries) {
        return relevantSummaries.keySet().stream()
                .map(cu -> (ContextFragment) new ContextFragments.SummaryFragment(
                        contextManager, cu.fqName(), ContextFragment.SummaryType.CODEUNIT_SKELETON))
                .toList();
    }

    private Map<CodeUnit, String> getSummaries(Collection<CodeUnit> classes) {
        var coalescedClasses = AnalyzerUtil.coalesceInnerClasses(Set.copyOf(classes));
        logger.debug("Found {} classes", coalescedClasses.size());

        return coalescedClasses.parallelStream()
                .map(cu -> {
                    final String skeleton = analyzer.getSkeleton(cu).orElse("");
                    return Map.entry(cu, skeleton);
                })
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1));
    }

    // --- Files-pruning utilities (budget-capped at 100k) ---

    /**
     * Prunes a list of filenames based on relevance to the goal.
     * This is a lightweight pass that only looks at paths, not contents.
     * If the filename list exceeds pruningBudgetTokens, it splits into chunks.
     */
    private LlmRecommendation askLlmDeepPruneFilenamesWithChunking(
            List<String> filenames,
            Collection<ChatMessage> workspaceRepresentation,
            int pruningBudgetTokens,
            Llm filesLlm,
            boolean showBatch1Reasoning)
            throws InterruptedException {

        int filenameTokens = Messages.getApproximateTokens(filenames);
        if (pruningBudgetTokens <= 0) {
            // Degenerate case: fall back to coarse chunking
            return deepPruneFilenamesInChunks(
                    filenames, filenameTokens, workspaceRepresentation, 4096, filesLlm, showBatch1Reasoning);
        }
        if (filenameTokens > pruningBudgetTokens) {
            return deepPruneFilenamesInChunks(
                    filenames,
                    filenameTokens,
                    workspaceRepresentation,
                    pruningBudgetTokens,
                    filesLlm,
                    showBatch1Reasoning);
        }
        return askLlmDeepPruneFilenames(filenames, workspaceRepresentation, filesLlm);
    }

    private LlmRecommendation askLlmDeepPruneFilenames(
            List<String> filenames, Collection<ChatMessage> workspaceRepresentation, Llm filesLlm)
            throws InterruptedException {

        var filenamePrompt =
                """
                <instructions>
                Given the above goal and Workspace contents (if any), evaluate the following list of filenames
                while thinking carefully about how they may be relevant to the goal.

                Constraints:
                 - You MUST select zero or more files EXCLUSIVELY from the provided <filenames> list.
                 - Do NOT include any filename that is not exactly present in <filenames>.
                 - If no files apply, return `BRK_NO_SUITABLE_CANDIDATES`

                Reason step-by-step:
                 - Identify all files corresponding to class names explicitly mentioned in the <goal>.
                 - Identify all files corresponding to class types used in the <workspace> code.
                 - Think about how you would solve the <goal>, and identify additional files potentially relevant to your plan.
                   For example, if the plan involves instantiating class Foo, or calling a method of class Bar,
                   then Foo.java and Bar.java are relevant files.
                 - Identify test, spec, e2e, or integration files that may verify behavior related to the goal.
                 - Compare this combined list against the filenames available.
                 - It's possible that files that were previously discarded are newly relevant, but when in doubt,
                   do not recommend files that are listed in the <discarded_context> section.

                Output format (strict):
                 - Either:
                   1) one or more exact filenames (one per line), OR
                   2) exactly `BRK_NO_SUITABLE_CANDIDATES`
                 - No quotes, no bullets, no markdown, and no extra commentary outside of the filenames list.

                </instructions>
                <filenames>
                %s
                </filenames>
                """
                        .formatted(String.join("\n", filenames));

        var discardedNote = getDiscardedContextNote();
        var userPrompt = new StringBuilder().append("<goal>\n").append(goal).append("\n</goal>\n\n");
        if (!discardedNote.isEmpty()) {
            userPrompt.append("<discarded_context>\n").append(discardedNote).append("\n</discarded_context>\n\n");
        }
        userPrompt.append(filenamePrompt);

        var sys = new SystemMessage(
                "You are the Context Agent, a code researcher focused on searching and analyzing large codebases.");
        List<ChatMessage> messages = Stream.concat(
                        Stream.of(sys),
                        Stream.concat(
                                workspaceRepresentation.stream(), Stream.of(new UserMessage(userPrompt.toString()))))
                .toList();

        int promptTokens = Messages.getApproximateMessageTokens(messages);
        logger.debug("Invoking LLM to prune filenames (prompt size ~{} tokens)", promptTokens);

        var result = filesLlm.sendRequest(messages);
        if (result.error() != null) {
            if (isContextError(result.error()) && filenames.size() >= 20) {
                logger.debug("LLM context-window error with {} filenames; splitting and retrying", filenames.size());
                int mid = filenames.size() / 2;
                var left = filenames.subList(0, mid);
                var right = filenames.subList(mid, filenames.size());

                var rec1 = askLlmDeepPruneFilenamesWithChunking(
                        left, workspaceRepresentation, Integer.MAX_VALUE, filesLlm, false);
                var rec2 = askLlmDeepPruneFilenamesWithChunking(
                        right, workspaceRepresentation, Integer.MAX_VALUE, filesLlm, false);

                var mergedFiles = new HashSet<>(rec1.recommendedFiles());
                mergedFiles.addAll(rec2.recommendedFiles());

                var mergedClasses = new HashSet<>(rec1.recommendedClasses());
                mergedClasses.addAll(rec2.recommendedClasses());

                var mergedUsage = Llm.ResponseMetadata.sum(rec1.tokenUsage(), rec2.tokenUsage());

                return new LlmRecommendation(mergedFiles, mergedClasses, mergedUsage);
            }

            logger.warn(
                    "Error from LLM during filename pruning: {}. Returning empty",
                    result.error().getMessage(),
                    result.error());
            return LlmRecommendation.EMPTY;
        }

        var tokenUsage = result.metadata();
        var selected = filenames.stream()
                .parallel()
                .filter(f -> Lines.containsBareToken(result.text(), f))
                .toList();
        return new LlmRecommendation(toProjectFiles(selected), Set.of(), tokenUsage);
    }

    private boolean isContextError(Throwable error) {
        return error.getMessage() != null
                && (error.getMessage().toLowerCase(Locale.ROOT).contains("context")
                        || error.getMessage().toLowerCase(Locale.ROOT).contains("token"));
    }

    private LlmRecommendation deepPruneFilenamesInChunks(
            List<String> filenames,
            int filenameTokens,
            Collection<ChatMessage> workspaceRepresentation,
            int pruningBudgetTokens,
            Llm filesLlm,
            boolean showBatch1Reasoning)
            throws InterruptedException {

        logger.debug("Chunking {} filenames for parallel pruning", filenames.size());

        int chunksCount = max(2, (pruningBudgetTokens <= 0) ? 8 : (filenameTokens / pruningBudgetTokens) + 1);

        int perChunk = 1 + filenames.size() / chunksCount;

        List<List<String>> chunks = new ArrayList<>(chunksCount);
        for (int i = 0; i < filenames.size(); i += perChunk) {
            int end = Math.min(i + perChunk, filenames.size());
            chunks.add(filenames.subList(i, end));
        }

        logger.debug("Created {} chunks for pruning (target per chunk ~{} items)", chunks.size(), perChunk);
        if (chunks.size() > 100) {
            logger.debug("Too many chunks: " + chunks.size());
            return LlmRecommendation.EMPTY;
        }

        if (showBatch1Reasoning) {
            this.io.llmOutput(
                    "Processing " + chunks.size() + " batches in parallel (showing batch 1)…\n\n",
                    ChatMessageType.AI,
                    LlmOutputMeta.reasoning());
        }

        Llm filesLlmWithEcho = showBatch1Reasoning
                ? cm.getLlm(new Llm.Options(
                                cm.getService().quickestModel(),
                                "ContextAgent Files Unanalyzed " + goal,
                                TaskResult.Type.SCAN)
                        .withForceReasoningEcho())
                : filesLlm;
        if (showBatch1Reasoning) {
            filesLlmWithEcho.setOutput(this.io);
        }

        List<Future<LlmRecommendation>> futures;
        try (var executor = AdaptiveExecutor.create(cm.getService(), model, chunks.size())) {
            List<Callable<LlmRecommendation>> tasks = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                int batchIndex = i;
                Llm llmForBatch = (showBatch1Reasoning && batchIndex == 0) ? filesLlm : filesLlmWithEcho;
                tasks.add(() -> {
                    try {
                        return askLlmDeepPruneFilenames(chunks.get(batchIndex), workspaceRepresentation, llmForBatch);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ie);
                    }
                });
            }
            futures = executor.invokeAll(tasks);
        }

        var combinedFiles = new HashSet<ProjectFile>();
        var combinedClasses = new HashSet<CodeUnit>();
        @Nullable Llm.ResponseMetadata combinedUsage = null;

        for (var f : futures) {
            LlmRecommendation rec;
            try {
                rec = f.get();
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
            combinedFiles.addAll(rec.recommendedFiles());
            combinedClasses.addAll(rec.recommendedClasses());
            combinedUsage = Llm.ResponseMetadata.sum(combinedUsage, rec.tokenUsage());
        }

        if (showBatch1Reasoning) {
            this.io.llmOutput(
                    "All batches complete. " + combinedFiles.size() + " files selected.\n\n",
                    ChatMessageType.AI,
                    LlmOutputMeta.reasoning());
        }

        return new LlmRecommendation(combinedFiles, combinedClasses, combinedUsage);
    }

    // --- Evaluate-for-relevance (single-group context window) ---

    /**
     * Calls the LLM to perform deep relevance evaluation on a group of files.
     *
     * The LLM is provided with either class summaries (analyzed) or capped text (unanalyzed)
     * and identifies which should be added as full files, summaries, or tests.
     */
    private LlmRecommendation askLlmDeepRecommendContext(
            Map<ProjectFile, Lines.HeadTail> filesMap,
            Collection<ChatMessage> workspaceRepresentation,
            Llm llm,
            boolean allowClassSummaries)
            throws InterruptedException, ContextTooLargeException {

        ToolRegistry tr = cm.getToolRegistry().builder().register(this).build();
        String toolName = allowClassSummaries ? "recommendContext" : "recommendFiles";
        var toolSpecs = tr.getTools(List.of(toolName));

        ToolContext toolContext = new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr);

        var deepPromptTemplate =
                """
                You are an assistant that identifies relevant code context based on a goal and the existing relevant information.
                You are given a goal, the current workspace contents (if any), and an <available_files> list.

                Interpreting <available_files>:
                - For this stage, <available_files> contains primary non-test files only.
                - Analyzed source files are represented as symbol/class summaries (APIs/skeletons), not full file text.
                - Unanalyzed files may be represented as:
                  - Full text (for small files), or
                  - Truncated/excerpted text (for large files).

                Truncated file entries:
                - A truncated file is indicated by: <file ... truncated="true" total_lines="..." top_shown="..." bottom_shown="...">.
                  - total_lines: the total number of lines in the original file.
                  - top_shown/bottom_shown: how many lines from the beginning/end of the file are included in the excerpt.
                  - Truncated file text includes an omission marker line like:
                  ----- OMITTED N LINES -----

                Use the available information to determine which items are most relevant to achieving the goal.
                """;

        var finalSystemMessage = new SystemMessage(deepPromptTemplate);
        var userMessageText = new StringBuilder();

        if (!filesMap.isEmpty()) {
            var filesText = filesMap.entrySet().stream()
                    .map(entry -> renderFileForPrompt(entry.getKey(), entry.getValue()))
                    .collect(Collectors.joining("\n\n"));
            userMessageText.append("<available_files>\n").append(filesText).append("\n</available_files>\n\n");
        }

        var discardedNote = getDiscardedContextNote();
        if (!discardedNote.isEmpty()) {
            userMessageText
                    .append("<discarded_context>\n")
                    .append(discardedNote)
                    .append("\n</discarded_context>\n\n");
        }

        userMessageText.append("\n<goal>\n").append(goal).append("\n</goal>\n");
        var userPrompt = allowClassSummaries
                ? """
                Identify code context relevant to the goal by calling `recommendContext`.

                Before calling `recommendContext`, reason step-by-step:
                - Identify all class names explicitly mentioned in the <goal>.
                - Identify all class types used in the <workspace> code.
                - Think about how you would solve the <goal>, and identify additional classes and files relevant to your plan.
                  For example, if the plan involves instantiating class Foo, or calling a method of class Bar,
                  then Foo and Bar are relevant classes, and their source files may be relevant files.
                - It's possible that files that were previously discarded are newly relevant, but when in doubt,
                  do not recommend files that are listed in the <discarded_context> section.
                - Compare this combined list against the items in the provided section (either summaries OR files content).

                Selection rubric (important):
                - Prefer `classesToSummarize` when you need navigational context (APIs, types, call sites) and are not confident the file will be edited.
                - Use `filesToAdd` for files you expect to edit or where exact implementation details are required.
                - If a file entry is truncated/excerpted (truncated="true") and you need details that might be in the omitted portion to decide or to implement the goal correctly, you SHOULD still recommend that file in `filesToAdd`.

                Then call the `recommendContext` tool with the appropriate entries:

                - Populate `filesToAdd` with full (relative) paths of files to edit or whose full text is necessary. Must exactly match one of the file paths provided in this message.
                - Populate `classesToSummarize` with fully-qualified names of classes whose APIs or structure are relevant but which do not need to be edited.

                Either or all arguments may be empty. Do NOT invent or guess file paths. Only use file paths that are present in the file list provided in this message. If no file list is provided, call `recommendContext` with empty lists.
                """
                : """
                Identify code context relevant to the goal by calling `recommendFiles`.

                Before calling `recommendFiles`, reason step-by-step:
                - Identify all class names explicitly mentioned in the <goal>.
                - Identify all class types used in the <workspace> code.
                - Think about how you would solve the <goal>, and identify additional files relevant to your plan.
                  For example, if the plan involves instantiating class Foo, or calling a method of class Bar,
                  then the files containing Foo and Bar are relevant files.
                - It's possible that files that were previously discarded are newly relevant, but when in doubt,
                  do not recommend files that are listed in the <discarded_context> section.
                - Compare this combined list against the items in the provided section (file content entries only).

                Selection rubric (important):
                - Use `filesToAdd` for files you expect to edit or where exact implementation details are required.
                - If a file entry is truncated/excerpted (truncated="true") and you need details that might be in the omitted portion to decide or to implement the goal correctly, you SHOULD recommend that file in `filesToAdd`.

                Then call the `recommendFiles` tool with the appropriate entries:

                - Populate `filesToAdd` with full (relative) paths of files to edit or whose full text is necessary. Must exactly match one of the file paths provided in this message.

                Either or all arguments may be empty. Do NOT invent or guess file paths. Only use file paths that are present in the file list provided in this message. If no file list is provided, call `recommendFiles` with empty lists.
                """;
        userMessageText.append(userPrompt);

        List<ChatMessage> messages = Stream.concat(
                        Stream.of(finalSystemMessage),
                        Stream.concat(
                                workspaceRepresentation.stream(),
                                Stream.of(new UserMessage(userMessageText.toString()))))
                .toList();

        int promptTokens = Messages.getApproximateMessageTokens(messages);
        logger.debug("Invoking LLM to recommend context via tool call (prompt size ~{} tokens)", promptTokens);

        var result = llm.sendRequest(messages, toolContext);
        var tokenUsage = result.metadata();
        if (result.error() != null) {
            var error = result.error();
            // Special case: propagate ContextTooLargeException so caller can retry with halving
            if (error instanceof ContextTooLargeException ctlException) {
                throw ctlException;
            }
            logger.warn("Error from LLM during context recommendation: {}. Returning empty", error.getMessage());
            return LlmRecommendation.EMPTY;
        }
        var toolRequests = result.toolRequests();
        logger.debug("LLM ToolRequests: {}", toolRequests);

        Set<String> recommendedFilePaths = new HashSet<>();
        Set<String> recommendedClassNames = new HashSet<>();

        for (var request : toolRequests) {
            ToolExecutionResult toolResult = tr.executeTool(request);
            if (toolResult.status() != ToolExecutionResult.Status.SUCCESS) {
                logger.debug("Tool execution failed for {} with status {}", request.name(), toolResult.status());
                continue;
            }

            var output = toolResult.result();
            if (output instanceof RecommendationToolOutput recommendationToolOutput) {
                recommendedFilePaths.addAll(recommendationToolOutput.filesToAdd());
                recommendedClassNames.addAll(recommendationToolOutput.classesToSummarize());
            }
        }

        var projectFiles = toProjectFiles(recommendedFilePaths.stream().toList());
        var projectClasses = recommendedClassNames.stream()
                .flatMap(name -> analyzer.getDefinitions(name).stream())
                .filter(CodeUnit::isClass)
                .collect(Collectors.toSet());

        logger.debug(
                "Tool recommended files: {}",
                projectFiles.stream().map(ProjectFile::getFileName).collect(Collectors.joining(", ")));
        logger.debug(
                "Tool recommended classes: {}",
                projectClasses.stream().map(CodeUnit::identifier).collect(Collectors.joining(", ")));
        return new LlmRecommendation(projectFiles, projectClasses, tokenUsage);
    }

    private Set<ProjectFile> toProjectFiles(List<String> filenames) {
        return filenames.stream()
                .map(fname -> {
                    try {
                        return cm.toFile(fname);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .filter(ProjectFile::exists)
                .collect(Collectors.toSet());
    }

    // --- Discarded context helper ---

    private String getDiscardedContextNote() {
        var discardedMap = cm.liveContext().getDiscardedFragmentsNotes();
        if (discardedMap.isEmpty()) {
            return "";
        }
        return discardedMap.entrySet().stream()
                .map(e -> "- " + e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("\n"));
    }

    // --- File content helpers ---

    private Map<ProjectFile, String> readFileContents(Collection<ProjectFile> files) {
        return files.stream()
                .distinct()
                .parallel()
                .map(file -> {
                    var content = file.read().orElse("");
                    return Map.entry(file, content);
                })
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1));
    }

    private Map<ProjectFile, Lines.HeadTail> readFileContentsCappedForPrompt(Collection<ProjectFile> files) {
        return files.stream()
                .distinct()
                .parallel()
                .map(file -> {
                    var content = file.read().orElse("");
                    return Map.entry(file, capUnanalyzedTextForPrompt(content));
                })
                .filter(entry -> !entry.getValue().promptText().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1));
    }

    static Lines.HeadTail capUnanalyzedTextForPrompt(String content) {
        return Lines.cap(content, UNANALYZED_MAX_LINES, UNANALYZED_TOP_SHOWN, UNANALYZED_BOTTOM_SHOWN);
    }

    @Blocking
    Set<ProjectFile> capRecommendedTests(Context rankingContext, Set<ProjectFile> candidateTests)
            throws InterruptedException {
        if (candidateTests.size() <= 3) {
            return candidateTests;
        }

        var ranked = rankingContext.getMostRelevantFiles(100);
        Set<ProjectFile> mergedTests = ranked.stream()
                .filter(candidateTests::contains)
                .limit(3)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Fallback: if context-aware ranking found fewer than 3, add from candidates deterministically
        if (mergedTests.size() < 3) {
            GitDistance.sortByImportance(candidateTests, cm.getRepo()).stream()
                    .filter(f -> !mergedTests.contains(f))
                    .limit(3 - mergedTests.size())
                    .forEach(mergedTests::add);
        }
        logger.debug("Capped test recommendations from {} to 3 based on relevance", candidateTests.size());

        return mergedTests;
    }

    static String renderFileForPrompt(ProjectFile file, Lines.HeadTail content) {
        // Normalize to forward slashes for consistent LLM prompts across platforms.
        // Safe on Windows: Java's Path.of() accepts '/' on all OSes, so paths returned
        // by the LLM can be parsed back to ProjectFile via IContextManager.toFile().
        String unixPath = file.toString().replace('\\', '/');
        if (!content.truncated()) {
            return "<file path='%s'>\n%s\n</file>".formatted(unixPath, content.promptText());
        }
        return "<file path='%s' truncated=\"true\" total_lines=\"%d\" top_shown=\"%d\" bottom_shown=\"%d\">\n%s\n</file>"
                .formatted(
                        unixPath,
                        content.totalLines(),
                        content.topShown(),
                        content.bottomShown(),
                        content.promptText());
    }
}
