package io.github.jbellis.brokk.agents;

import static java.lang.Math.max;
import static java.lang.Math.min;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
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
import io.github.jbellis.brokk.git.GitDistance;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.util.AdaptiveExecutor;
import io.github.jbellis.brokk.util.Messages;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
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
 * Candidate selection is done by
 * 1. If we have few enough candidates that we can fit all summaries into the model's context window,
 *    just throw them all in.
 * 2. Otherwise, first filter by filename, then select by summaries.
 * 3. If filtering by filename still results in too many candidates to fit summaries in the context window,
 *    use GitDistance to narrow down to the most popular files.
 *
 * Finally, if there are files that the Analyzer does not know how to summarize, ContextAgent will do
 * full-content analysis (but since these are so much larger, necessarily we will be able to fit much
 * fewer into the window).
 */
public class ContextAgent {
    private static final Logger logger = LogManager.getLogger(ContextAgent.class);

    private enum GroupType {
        ANALYZED,
        UNANALYZED
    }

    private final ContextManager cm;
    private final Llm llm;
    private final Llm filesLlm;
    private final String goal;
    private final IAnalyzer analyzer;
    private final StreamingChatModel model;

    /** If the entire group fits under this, we can skip LLM pruning/eval and include everything. */
    private final int skipPruningBudget;

    /** Budget for the evaluate-for-relevance stage (uncapped *0.65 of input). */
    private final int evaluationBudget;

    /** Budget for the files-pruning stage (evaluationBudget capped at 100k). */
    private final int filesPruningBudget;

    public ContextAgent(ContextManager contextManager, StreamingChatModel model, String goal)
            throws InterruptedException {
        this.cm = contextManager;
        this.goal = goal;
        this.model = model;
        this.analyzer = contextManager.getAnalyzer();

        // Files-pruning LLM
        var options = new Llm.Options(
                        contextManager.getService().quickestModel(),
                        "ContextAgent Files (%s): %s".formatted("Deep", goal))
                .withForceReasoningEcho()
                .withEcho();
        this.filesLlm = contextManager.getLlm(options);
        // Evaluation LLM
        options = new Llm.Options(model, "ContextAgent (%s): %s".formatted("Deep", goal))
                .withForceReasoningEcho()
                .withEcho();
        this.llm = contextManager.getLlm(options);

        // Token budgets
        int maxInputTokens = contextManager.getService().getMaxInputTokens(model);
        this.skipPruningBudget = min(32_000, maxInputTokens / 4);
        int outputTokens = model.defaultRequestParameters().maxCompletionTokens();
        int actualInputTokens = contextManager.getService().getMaxInputTokens(model) - outputTokens;
        // god, our estimation is so bad (yes we do observe the ratio being this far off)
        this.evaluationBudget = (int) (actualInputTokens * 0.65);
        this.filesPruningBudget = min(100_000, evaluationBudget);
        logger.debug(
                "ContextAgent initialized. Budgets: SkipPruning={}, FilesPruning={}, Evaluation={}",
                skipPruningBudget,
                filesPruningBudget,
                evaluationBudget);
    }

    /** Result record for context recommendation attempts, including token usage of the LLM call (nullable). */
    public record RecommendationResult(
            boolean success,
            List<ContextFragment> fragments,
            String reasoning,
            @Nullable Llm.RichTokenUsage tokenUsage) {}

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
                    String content = file.read().orElse("");
                    totalTokens += Messages.getApproximateTokens(content);
                } else {
                    logger.debug(
                            "PROJECT_PATH fragment {} did not yield a ProjectFile for token calculation.",
                            fragment.description());
                }
            } else if (fragment.getType() == ContextFragment.FragmentType.SKELETON) {
                var skeletonFragment = (ContextFragment.SkeletonFragment) fragment;
                totalTokens += Messages.getApproximateTokens(skeletonFragment.text());
            } else {
                logger.warn("Unhandled ContextFragment type for token calculation: {}", fragment.getClass());
            }
        }
        return totalTokens;
    }

    /**
     * Determines the best initial context based on project size and budgets, splitting analyzed vs un-analyzed into
     * separate LLM context windows and processing them in parallel.
     *
     * @param allowSkipPruning If true, allows the agent to skip LLM-based pruning if a group's data fits
     *     {@code skipPruningBudget}.
     * @return A RecommendationResult containing success status, fragments, and reasoning.
     */
    public RecommendationResult getRecommendations(boolean allowSkipPruning) throws InterruptedException {
        var workspaceRepresentation = CodePrompts.instance.getWorkspaceContentsMessages(cm.liveContext());

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
            return new RecommendationResult(false, List.of(), "Workspace is too large", null);
        }

        // Candidates are most-relevant files to the Workspace, or entire Project if Workspace is empty
        var existingFiles = cm.liveContext()
                .allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.PROJECT_PATH
                        || f.getType() == ContextFragment.FragmentType.SKELETON)
                .flatMap(f -> f.files().stream())
                .collect(Collectors.toSet());
        List<ProjectFile> candidates;
        if (existingFiles.isEmpty()) {
            candidates = cm.getProject().getAllFiles().stream().sorted().toList();
            logger.debug("Empty workspace; using all files ({}) for context recommendation.", candidates.size());
        } else {
            candidates = cm.topContext().getMostRelevantFiles(Context.MAX_AUTO_CONTEXT_FILES).stream()
                    .filter(f -> !existingFiles.contains(f))
                    .sorted()
                    .toList();
            logger.debug("Non-empty workspace; using Git-based distance candidates (count: {}).", candidates.size());
        }

        // Group by analyzed (summarizable via SkeletonProvider) vs un-analyzed (need full content)
        var skpOpt = analyzer.as(SkeletonProvider.class);
        Map<CodeUnit, String> allSummaries = skpOpt.map(skp -> candidates.parallelStream()
                        .map(skp::getSkeletons)
                        .map(Map::entrySet)
                        .flatMap(Set::stream)
                        .filter(e -> !e.getValue().isEmpty())
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1)))
                .orElseGet(Map::of);
        Set<ProjectFile> analyzedFileSet =
                allSummaries.keySet().stream().map(CodeUnit::source).collect(Collectors.toSet());
        List<ProjectFile> analyzedFiles =
                candidates.stream().filter(analyzedFileSet::contains).sorted().toList();
        List<ProjectFile> unAnalyzedFiles = candidates.stream()
                .filter(f -> !analyzedFileSet.contains(f))
                .sorted()
                .toList();
        logger.debug("Grouped candidates: analyzed={}, unAnalyzed={}", analyzedFiles.size(), unAnalyzedFiles.size());

        // Process each group in parallel
        RecommendationResult[] results = new RecommendationResult[2];
        Throwable[] errors = new Throwable[2];

        Thread t1 = Thread.ofVirtual().start(() -> {
            try {
                results[0] = processGroup(
                        GroupType.ANALYZED,
                        analyzedFiles,
                        allSummaries,
                        workspaceRepresentation,
                        allowSkipPruning,
                        evalBudgetRemaining,
                        pruneBudgetRemaining,
                        existingFiles);
            } catch (Throwable t) {
                errors[0] = t;
            }
        });

        Thread t2 = Thread.ofVirtual().start(() -> {
            try {
                results[1] = processGroup(
                        GroupType.UNANALYZED,
                        unAnalyzedFiles,
                        Map.of(),
                        workspaceRepresentation,
                        allowSkipPruning,
                        evalBudgetRemaining,
                        pruneBudgetRemaining,
                        existingFiles);
            } catch (Throwable t) {
                errors[1] = t;
            }
        });

        t1.join();
        t2.join();

        if (errors[0] != null) throw new RuntimeException(errors[0]);
        if (errors[1] != null) throw new RuntimeException(errors[1]);

        var analyzedResult = results[0];
        var unAnalyzedResult = results[1];

        boolean success = analyzedResult.success || unAnalyzedResult.success;
        var combinedFragments = Stream.concat(analyzedResult.fragments.stream(), unAnalyzedResult.fragments.stream())
                .toList();
        var combinedReasoning = Stream.of(analyzedResult.reasoning, unAnalyzedResult.reasoning)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n"));
        var combinedUsage = addTokenUsage(analyzedResult.tokenUsage, unAnalyzedResult.tokenUsage);

        return new RecommendationResult(success, combinedFragments, combinedReasoning, combinedUsage);
    }

    // --- Group processing ---

    private RecommendationResult processGroup(
            GroupType type,
            List<ProjectFile> groupFiles,
            Map<CodeUnit, String> allSummariesForAnalyzed,
            Collection<ChatMessage> workspaceRepresentation,
            boolean allowSkipPruning,
            int evalBudgetRemaining,
            int pruneBudgetRemaining,
            Set<ProjectFile> existingFiles)
            throws InterruptedException {

        if (groupFiles.isEmpty()) {
            String msg = "No " + type.name().toLowerCase(Locale.ROOT) + " items to process.";
            return new RecommendationResult(true, List.of(), msg, null);
        }

        // Build initial payload preview for token estimation
        int initialTokens;
        if (type == GroupType.ANALYZED) {
            var groupSummaries = filterSummariesByFiles(allSummariesForAnalyzed, groupFiles);
            initialTokens = Messages.getApproximateTokens(groupSummaries.values());
        } else {
            var contentsMap = readFileContents(groupFiles);
            initialTokens = Messages.getApproximateTokens(contentsMap.values());
        }

        logger.debug("{} group initial token estimate: ~{}", type, initialTokens);

        // If small, include everything without LLM
        if (allowSkipPruning && initialTokens <= skipPruningBudget) {
            LlmRecommendation rec = (type == GroupType.ANALYZED)
                    ? new LlmRecommendation(
                            List.of(),
                            new ArrayList<>(filterSummariesByFiles(allSummariesForAnalyzed, groupFiles)
                                    .keySet()),
                            "Under skip-pruning budget (" + type + ")")
                    : new LlmRecommendation(groupFiles, List.of(), "Under skip-pruning budget (" + type + ")");
            return createResult(rec, existingFiles);
        }

        List<ProjectFile> workingFiles = groupFiles;

        // If too large for evaluation, ask for interesting files (files-pruning stage with 100k cap)
        StringBuilder reasoning = new StringBuilder();
        Llm.RichTokenUsage usage = null;

        if (initialTokens > evalBudgetRemaining) {
            logger.debug(
                    "{} group exceeds evaluation budget ({} > {}); pruning filenames first.",
                    type,
                    initialTokens,
                    evalBudgetRemaining);
            var filenames = workingFiles.stream().map(ProjectFile::toString).toList();
            var pruneRec =
                    askLlmDeepPruneFilenamesWithChunking(filenames, workspaceRepresentation, pruneBudgetRemaining);
            usage = addTokenUsage(usage, pruneRec.tokenUsage());

            workingFiles = pruneRec.recommendedFiles().stream().sorted().toList();
            if (!pruneRec.reasoning().isBlank())
                reasoning.append(pruneRec.reasoning()).append('\n');

            if (workingFiles.isEmpty()) {
                logger.debug("{} group: filename pruning produced an empty set.", type);
                return new RecommendationResult(
                        true, List.of(), (reasoning + "\nNo files selected after pruning.").strip(), usage);
            }
        }

        // Evaluate-for-relevance stage: call LLM with a context window containing ONLY this group's data.
        // If we still get a context-window error, iteratively cut off the least important half.
        LlmRecommendation evalRec =
                evaluateWithHalving(type, workingFiles, allSummariesForAnalyzed, workspaceRepresentation);
        usage = addTokenUsage(usage, evalRec.tokenUsage());
        if (!reasoning.isEmpty()) {
            evalRec = new LlmRecommendation(
                    evalRec.recommendedFiles(),
                    evalRec.recommendedClasses(),
                    (reasoning + "\n" + evalRec.reasoning()).strip(),
                    usage);
        } else if (usage != null && !usage.equals(evalRec.tokenUsage())) {
            evalRec = new LlmRecommendation(
                    evalRec.recommendedFiles(), evalRec.recommendedClasses(), evalRec.reasoning(), usage);
        }

        return createResult(evalRec, existingFiles);
    }

    private Map<CodeUnit, String> filterSummariesByFiles(Map<CodeUnit, String> allSummaries, List<ProjectFile> files) {
        Set<ProjectFile> fileSet = new HashSet<>(files);
        return allSummaries.entrySet().stream()
                .filter(e -> fileSet.contains(e.getKey().source()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private LlmRecommendation evaluateWithHalving(
            GroupType type,
            List<ProjectFile> files,
            Map<CodeUnit, String> allSummariesForAnalyzed,
            Collection<ChatMessage> workspaceRepresentation)
            throws InterruptedException {

        List<ProjectFile> current = new ArrayList<>(files);
        while (true) {
            Map<CodeUnit, String> summaries =
                    (type == GroupType.ANALYZED) ? filterSummariesByFiles(allSummariesForAnalyzed, current) : Map.of();

            Map<ProjectFile, String> contents = (type == GroupType.UNANALYZED) ? readFileContents(current) : Map.of();

            try {
                return askLlmDeepRecommendContext(summaries, contents, workspaceRepresentation);
            } catch (ContextTooLargeException e) {
                if (current.size() <= 1) {
                    logger.debug("{} group still too large with a single file; returning empty.", type);
                    return LlmRecommendation.EMPTY;
                }
                // Sort by importance and cut off least-important half
                var sorted = GitDistance.sortByImportance(current, cm.getRepo());
                int keep = max(1, (sorted.size() + 1) / 2); // keep top half (round up)
                current = new ArrayList<>(sorted.subList(0, keep));
                logger.debug("{} group context too large; halving to {} files and retrying.", type, current.size());
            }
        }
    }

    // --- Result assembly ---

    private RecommendationResult createResult(LlmRecommendation llmRecommendation, Set<ProjectFile> existingFiles) {
        var originalFiles = llmRecommendation.recommendedFiles();
        var filteredFiles =
                originalFiles.stream().filter(f -> !existingFiles.contains(f)).toList();
        if (filteredFiles.size() != originalFiles.size()) {
            logger.debug(
                    "Post-filtered LLM recommended files from {} to {} by excluding already-present files",
                    originalFiles.size(),
                    filteredFiles.size());
        }

        var originalClassCount = llmRecommendation.recommendedClasses().size();
        var recommendedClasses = llmRecommendation.recommendedClasses().stream()
                .filter(cu -> !filteredFiles.contains(cu.source()))
                .filter(cu -> !existingFiles.contains(cu.source()))
                .toList();
        if (recommendedClasses.size() != originalClassCount) {
            logger.debug(
                    "Post-filtered LLM recommended classes from {} to {} by excluding those whose source files are already present or selected",
                    originalClassCount,
                    recommendedClasses.size());
        }

        var reasoning = llmRecommendation.reasoning();
        var recommendedSummaries = getSummaries(recommendedClasses);

        int recommendedSummaryTokens = Messages.getApproximateTokens(String.join("\n", recommendedSummaries.values()));
        var recommendedContentsMap = readFileContents(filteredFiles);
        int recommendedContentTokens =
                Messages.getApproximateTokens(String.join("\n", recommendedContentsMap.values()));
        int totalRecommendedTokens = recommendedSummaryTokens + recommendedContentTokens;

        logger.debug(
                "LLM recommended {} classes ({} tokens) and {} files ({} tokens). Total: {} tokens",
                recommendedSummaries.size(),
                recommendedSummaryTokens,
                filteredFiles.size(),
                recommendedContentTokens,
                totalRecommendedTokens);

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

    private Map<CodeUnit, String> getSummaries(Collection<CodeUnit> classes) {
        var coalescedClasses = AnalyzerUtil.coalesceInnerClasses(Set.copyOf(classes));
        logger.debug("Found {} classes", coalescedClasses.size());

        return coalescedClasses.parallelStream()
                .map(cu -> {
                    final String skeleton = analyzer.as(SkeletonProvider.class)
                            .flatMap(skp -> skp.getSkeleton(cu.fqName()))
                            .orElse("");
                    return Map.entry(cu, skeleton);
                })
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1));
    }

    // --- Files-pruning utilities (budget-capped at 100k) ---

    private LlmRecommendation askLlmDeepPruneFilenamesWithChunking(
            List<String> filenames, Collection<ChatMessage> workspaceRepresentation, int pruningBudgetTokens)
            throws InterruptedException {

        int filenameTokens = Messages.getApproximateTokens(filenames);
        if (pruningBudgetTokens <= 0) {
            // Degenerate case: fall back to coarse chunking
            return deepPruneFilenamesInChunks(filenames, filenameTokens, workspaceRepresentation, 4096);
        }
        if (filenameTokens > pruningBudgetTokens) {
            return deepPruneFilenamesInChunks(filenames, filenameTokens, workspaceRepresentation, pruningBudgetTokens);
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
                 - It's possible that files that were previously discarded are newly relevant, but when in doubt,
                   do not recommend files that are listed in the <discarded_context> section.

                Then, list the full path of each relevant filename, one per line.
                </instructions>
                <filenames>
                %s
                </filenames>
                """
                        .formatted(String.join("\n", filenames));

        var finalSystemMessage = new SystemMessage(systemPrompt);
        var discardedNote = getDiscardedContextNote();
        var userPrompt = new StringBuilder().append("<goal>\n").append(goal).append("\n</goal>\n\n");
        if (!discardedNote.isEmpty()) {
            userPrompt.append("<discarded_context>\n").append(discardedNote).append("\n</discarded_context>\n\n");
        }
        userPrompt.append(filenamePrompt);

        List<ChatMessage> messages = Stream.concat(
                        Stream.of(finalSystemMessage),
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

                var rec1 = askLlmDeepPruneFilenamesWithChunking(left, workspaceRepresentation, Integer.MAX_VALUE);
                var rec2 = askLlmDeepPruneFilenamesWithChunking(right, workspaceRepresentation, Integer.MAX_VALUE);

                var mergedFiles = new ArrayList<>(rec1.recommendedFiles());
                rec2.recommendedFiles().stream()
                        .filter(pf -> !mergedFiles.contains(pf))
                        .forEach(mergedFiles::add);

                var mergedReasoning = (rec1.reasoning() + "\n" + rec2.reasoning()).strip();
                var mergedUsage = addTokenUsage(rec1.tokenUsage(), rec2.tokenUsage());

                return new LlmRecommendation(new HashSet<>(mergedFiles), Set.of(), mergedReasoning, mergedUsage);
            }

            logger.warn(
                    "Error from LLM during filename pruning: {}. Returning empty",
                    result.error().getMessage(),
                    result.error());
            return LlmRecommendation.EMPTY;
        }

        var tokenUsage = result.tokenUsage();
        var selected = filenames.stream()
                .parallel()
                .filter(f -> result.text().contains(f))
                .toList();
        return new LlmRecommendation(toProjectFiles(selected), Set.of(), result.text(), tokenUsage);
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
            int pruningBudgetTokens)
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
            LlmRecommendation rec;
            try {
                rec = f.get();
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
            rec.recommendedFiles().stream()
                    .filter(pf -> !combinedFiles.contains(pf))
                    .forEach(combinedFiles::add);
            if (!rec.reasoning().isBlank())
                combinedReasoning.append(rec.reasoning()).append('\n');
            combinedUsage = addTokenUsage(combinedUsage, rec.tokenUsage());
        }
        return new LlmRecommendation(
                combinedFiles, Set.of(), combinedReasoning.toString().strip(), combinedUsage);
    }

    // --- Evaluate-for-relevance (single-group context window) ---

    private LlmRecommendation askLlmDeepRecommendContext(
            Map<CodeUnit, String> summaries,
            Map<ProjectFile, String> contentsMap,
            Collection<ChatMessage> workspaceRepresentation)
            throws InterruptedException, ContextTooLargeException {

        var contextTool = new ContextRecommendationTool();
        var toolSpecs = ToolSpecifications.toolSpecificationsFrom(contextTool);
        assert toolSpecs.size() == 1 : "Expected exactly one tool specification from ContextRecommendationTool";

        var deepPromptTemplate =
                """
                You are an assistant that identifies relevant code context based on a goal and the existing relevant information.
                You are given a goal, the current workspace contents (if any), and ONE of the following optional sections:
                - <available_summaries>: a list of class summaries (analyzed group)
                - <available_files_content>: a list of files and their contents (un-analyzed group)

                IMPORTANT: The provided section contains only one of those categories; do not assume the other category is present.
                Analyze the provided information and determine which items are most relevant to achieving the goal.
                """;

        var finalSystemMessage = new SystemMessage(deepPromptTemplate);
        var userMessageText = new StringBuilder();

        if (!summaries.isEmpty()) {
            var summariesText = summaries.entrySet().stream()
                    .map(entry -> {
                        var cu = entry.getKey();
                        var body = entry.getValue();
                        return "<class fqcn='%s' file='%s'>\n%s\n</class>".formatted(cu.fqName(), cu.source(), body);
                    })
                    .collect(Collectors.joining("\n\n"));
            userMessageText
                    .append("<available_summaries>\n")
                    .append(summariesText)
                    .append("\n</available_summaries>\n\n");
        }

        if (!contentsMap.isEmpty()) {
            var filesText = contentsMap.entrySet().stream()
                    .map(entry -> "<file path='%s'>\n%s\n</file>"
                            .formatted(entry.getKey().toString(), entry.getValue()))
                    .collect(Collectors.joining("\n\n"));
            userMessageText
                    .append("<available_files_content>\n")
                    .append(filesText)
                    .append("\n</available_files_content>\n\n");
        }

        var discardedNote = getDiscardedContextNote();
        if (!discardedNote.isEmpty()) {
            userMessageText
                    .append("<discarded_context>\n")
                    .append(discardedNote)
                    .append("\n</discarded_context>\n\n");
        }

        userMessageText.append("\n<goal>\n%s\n</goal>\n".formatted(goal));
        var userPrompt =
                """
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

                Then call the `recommendContext` tool with the appropriate entries:

                - Populate `filesToAdd` with full (relative) paths of files that will need to be edited, or whose implementation details are necessary.
                - Populate `classesToSummarize` with fully-qualified names of classes whose APIs will be used.

                Either or both arguments may be empty.
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

        var result = llm.sendRequest(messages, new ToolContext(toolSpecs, ToolChoice.REQUIRED, contextTool));
        var tokenUsage = result.tokenUsage();
        if (result.error() != null) {
            var error = result.error();
            if (isContextError(error)) {
                throw new ContextTooLargeException();
            }
            logger.warn("Error from LLM during context recommendation: {}. Returning empty", error.getMessage());
            return LlmRecommendation.EMPTY;
        }
        var toolRequests = result.toolRequests();
        logger.debug("LLM ToolRequests: {}", toolRequests);
        for (var request : toolRequests) {
            cm.getToolRegistry().executeTool(contextTool, request);
        }

        var projectFiles = toProjectFiles(contextTool.getRecommendedFiles());
        var projectClasses = contextTool.getRecommendedClasses().stream()
                .map(analyzer::getDefinition)
                .flatMap(Optional::stream)
                .filter(CodeUnit::isClass)
                .collect(Collectors.toSet());

        logger.debug(
                "Tool recommended files: {}",
                projectFiles.stream().map(ProjectFile::getFileName).collect(Collectors.joining(", ")));
        logger.debug(
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
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .filter(ProjectFile::exists)
                .collect(Collectors.toSet());
    }

    private static class ContextTooLargeException extends Exception {}

    // --- Discarded context helper ---

    private String getDiscardedContextNote() {
        var discardedMap = cm.liveContext().getDiscardedFragmentsNote();
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

    // --- Helper methods ---

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
