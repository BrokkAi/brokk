package ai.brokk.agents;

import static java.lang.Math.max;
import static java.lang.Math.min;

import ai.brokk.AnalyzerUtil;
import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SkeletonProvider;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.git.GitDistance;
import ai.brokk.prompts.CodePrompts;
import ai.brokk.util.AdaptiveExecutor;
import ai.brokk.util.Messages;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolSpecifications;
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

    private final IContextManager cm;
    private final String goal;
    private final IAnalyzer analyzer;
    private final StreamingChatModel model;

    /** Budget for the evaluate-for-relevance stage (uncapped *0.65 of input). */
    private final int evaluationBudget;

    /** Budget for the files-pruning stage (evaluationBudget capped at 100k). */
    private final int filesPruningBudget;

    public ContextAgent(IContextManager contextManager, StreamingChatModel model, String goal)
            throws InterruptedException {
        this.cm = contextManager;
        this.goal = goal;
        this.model = model;
        this.analyzer = contextManager.getAnalyzer();

        // Token budgets
        int outputTokens = model.defaultRequestParameters().maxCompletionTokens();
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
            boolean success,
            List<ContextFragment> fragments,
            String reasoning,
            @Nullable Llm.ResponseMetadata metadata) {}

    /** Result record for the LLM tool call, holding recommended files, class names, and the LLM's reasoning. */
    private record LlmRecommendation(
            Set<ProjectFile> recommendedFiles,
            Set<CodeUnit> recommendedClasses,
            String reasoning,
            @Nullable Llm.ResponseMetadata tokenUsage) {
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
                totalTokens += Messages.getApproximateTokens(fragment.text());
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
     * @return A RecommendationResult containing success status, fragments, and reasoning.
     */
    public RecommendationResult getRecommendations(Context context) throws InterruptedException {
        var workspaceRepresentation = CodePrompts.instance.getWorkspaceContentsMessages(context);

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
        var existingFiles = context.allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.PROJECT_PATH
                        || f.getType() == ContextFragment.FragmentType.SKELETON)
                .flatMap(f -> f.files().stream())
                .collect(Collectors.toSet());
        List<ProjectFile> candidates;
        if (existingFiles.isEmpty()) {
            candidates = cm.getProject().getAllFiles().stream().sorted().toList();
            logger.debug("Empty workspace; using all files ({}) for context recommendation.", candidates.size());
        } else {
            candidates = context.getMostRelevantFiles(Context.MAX_AUTO_CONTEXT_FILES).stream()
                    .filter(f -> !existingFiles.contains(f))
                    .sorted()
                    .toList();
            logger.debug("Non-empty workspace; using Git-based distance candidates (count: {}).", candidates.size());
        }

        Set<ProjectFile> analyzedFileSet = candidates.stream()
                .filter(pf -> !analyzer.getTopLevelDeclarations(pf).isEmpty())
                .collect(Collectors.toSet());
        List<ProjectFile> analyzedFiles =
                candidates.stream().filter(analyzedFileSet::contains).sorted().toList();
        List<ProjectFile> unAnalyzedFiles = candidates.stream()
                .filter(f -> !analyzedFileSet.contains(f))
                .sorted()
                .toList();
        logger.debug("Grouped candidates: analyzed={}, unAnalyzed={}", analyzedFiles.size(), unAnalyzedFiles.size());

        // Create Llm instances - only analyzed group streams to UI
        var filesLlmAnalyzed = cm.getLlm(
                new Llm.Options(cm.getService().quickestModel(), "ContextAgent Files (Analyzed): %s".formatted(goal))
                        .withForceReasoningEcho()
                        .withEcho());
        var filesLlmUnanalyzed = cm.getLlm(new Llm.Options(
                cm.getService().quickestModel(), "ContextAgent Files (Unanalyzed): %s".formatted(goal)));

        var llmAnalyzed = cm.getLlm(new Llm.Options(model, "ContextAgent (Analyzed): %s".formatted(goal))
                .withForceReasoningEcho()
                .withEcho());
        var llmUnanalyzed = cm.getLlm(new Llm.Options(model, "ContextAgent (Unanalyzed): %s".formatted(goal)));

        // Show status message based on which groups have work
        int groupCount = (analyzedFiles.isEmpty() ? 0 : 1) + (unAnalyzedFiles.isEmpty() ? 0 : 1);
        var io = cm.getIo();
        switch (groupCount) {
            case 0 -> {}
            case 1 ->
                io.llmOutput(
                        "\nProcessing " + (analyzedFiles.isEmpty() ? "**unanalyzed**" : "**analyzed**") + " files…\n\n",
                        ChatMessageType.AI,
                        false,
                        true);
            case 2 ->
                io.llmOutput(
                        "\nProcessing **analyzed** and **unanalyzed** files in parallel\nAnalyzed files reasoning:\n\n",
                        ChatMessageType.AI,
                        false,
                        true);
        }

        // Process each group in parallel
        LlmRecommendation[] results = new LlmRecommendation[2];
        Throwable[] errors = new Throwable[2];

        Thread t1 = Thread.ofVirtual().start(() -> {
            try {
                results[0] = processGroup(
                        GroupType.ANALYZED,
                        analyzedFiles,
                        workspaceRepresentation,
                        evalBudgetRemaining,
                        pruneBudgetRemaining,
                        filesLlmAnalyzed,
                        llmAnalyzed);
            } catch (Throwable t) {
                errors[0] = t;
            }
        });

        Thread t2 = Thread.ofVirtual().start(() -> {
            try {
                results[1] = processGroup(
                        GroupType.UNANALYZED,
                        unAnalyzedFiles,
                        workspaceRepresentation,
                        evalBudgetRemaining,
                        pruneBudgetRemaining,
                        filesLlmUnanalyzed,
                        llmUnanalyzed);
            } catch (Throwable t) {
                errors[1] = t;
            }
        });

        t1.join();
        t2.join();

        if (errors[0] != null) throw new RuntimeException(errors[0]);
        if (errors[1] != null) throw new RuntimeException(errors[1]);

        var analyzedRec = results[0];
        var unAnalyzedRec = results[1];

        boolean success = !analyzedRec.recommendedFiles().isEmpty()
                || !analyzedRec.recommendedClasses().isEmpty()
                || !unAnalyzedRec.recommendedFiles().isEmpty()
                || !unAnalyzedRec.recommendedClasses().isEmpty();

        // Union files and classes from both groups. Since both groups are processed independently,
        // the LLM may recommend the same file/class in both. We use HashSet to automatically deduplicate
        // at the semantic level (canonical ProjectFile and CodeUnit objects).
        var mergedFiles = new HashSet<>(analyzedRec.recommendedFiles());
        mergedFiles.addAll(unAnalyzedRec.recommendedFiles());

        var mergedClasses = new HashSet<>(analyzedRec.recommendedClasses());
        mergedClasses.addAll(unAnalyzedRec.recommendedClasses());

        // we streamed the analyzed reasoning; we'll append this afterwards so they don't get mixed together
        if (!unAnalyzedRec.reasoning().isBlank()) {
            io.llmOutput(
                    "\n\nUnanalyzed files reasoning:\n\n" + unAnalyzedRec.reasoning(), ChatMessageType.AI, false, true);
        }

        var combinedReasoning = Stream.of(analyzedRec.reasoning(), unAnalyzedRec.reasoning())
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n"));
        var combinedUsage = Llm.ResponseMetadata.sum(analyzedRec.tokenUsage(), unAnalyzedRec.tokenUsage());

        var unifiedRec = new LlmRecommendation(mergedFiles, mergedClasses, combinedReasoning, combinedUsage);
        var result = createResult(unifiedRec, existingFiles);

        return new RecommendationResult(success, result, combinedReasoning, combinedUsage);
    }

    private int estimateAnalyzedTokens(Collection<ProjectFile> files) {
        var summariesByFile = getCachedIdentifiers(files);
        return Messages.getApproximateTokens(summariesByFile.values());
    }

    // --- Group processing ---

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
            String msg = "No " + type.name().toLowerCase(Locale.ROOT) + " items to process.";
            return new LlmRecommendation(Set.of(), Set.of(), msg, null);
        }

        // Build initial payload preview for token estimation
        int initialTokens;
        if (type == GroupType.ANALYZED) {
            initialTokens = estimateAnalyzedTokens(groupFiles);
        } else {
            var contentsMap = readFileContents(groupFiles);
            initialTokens = Messages.getApproximateTokens(contentsMap.values());
        }

        logger.debug("{} group initial token estimate: ~{}", type, initialTokens);

        List<ProjectFile> workingFiles = groupFiles;

        // If too large for evaluation, ask for interesting files (files-pruning stage with 100k cap)
        StringBuilder reasoning = new StringBuilder();
        Llm.ResponseMetadata usage = null;

        if (initialTokens > evalBudgetRemaining) {
            logger.debug(
                    "{} group exceeds evaluation budget ({} > {}); pruning filenames first.",
                    type,
                    initialTokens,
                    evalBudgetRemaining);
            var filenames = workingFiles.stream().map(ProjectFile::toString).toList();
            var pruneRec = askLlmDeepPruneFilenamesWithChunking(
                    filenames, workspaceRepresentation, pruneBudgetRemaining, filesLlm, type == GroupType.ANALYZED);
            usage = Llm.ResponseMetadata.sum(usage, pruneRec.tokenUsage());

            workingFiles = pruneRec.recommendedFiles().stream().sorted().toList();
            if (!pruneRec.reasoning().isBlank())
                reasoning.append(pruneRec.reasoning()).append('\n');

            int postPruneTokens;
            if (type == GroupType.ANALYZED) {
                postPruneTokens = estimateAnalyzedTokens(workingFiles);
            } else {
                var contentsMap = readFileContents(workingFiles);
                postPruneTokens = Messages.getApproximateTokens(contentsMap.values());
            }
            logger.debug("{} group post-prune token estimate: ~{}", type, postPruneTokens);

            if (workingFiles.isEmpty()) {
                logger.debug("{} group: filename pruning produced an empty set.", type);
                return new LlmRecommendation(
                        Set.of(), Set.of(), (reasoning + "\nNo files selected after pruning.").strip(), usage);
            }
        }

        // Evaluate-for-relevance stage: call LLM with a context window containing ONLY this group's data.
        // If we still get a context-window error, iteratively cut off the least important half.
        LlmRecommendation evalRec = evaluateWithHalving(type, workingFiles, workspaceRepresentation, llm);
        usage = Llm.ResponseMetadata.sum(usage, evalRec.tokenUsage());
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

        return evalRec;
    }

    /**
     * Evaluates relevance for the current file set, halving on context overflow.
     */
    private LlmRecommendation evaluateWithHalving(
            GroupType type, List<ProjectFile> files, Collection<ChatMessage> workspaceRepresentation, Llm llm)
            throws InterruptedException {

        List<ProjectFile> current = new ArrayList<>(files);
        while (true) {
            Map<ProjectFile, String> fileText;
            fileText = type == GroupType.ANALYZED ? getCachedIdentifiers(current) : readFileContents(current);

            try {
                return askLlmDeepRecommendContext(fileText, workspaceRepresentation, llm);
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
     */
    private Map<ProjectFile, String> getCachedIdentifiers(Collection<ProjectFile> candidates) {
        return candidates.parallelStream()
                .distinct()
                .collect(Collectors.toMap(
                        f -> f,
                        f -> identifiersByFile.computeIfAbsent(f, pf -> Context.buildRelatedIdentifiers(analyzer, pf)),
                        (v1, v2) -> v1));
    }

    // --- Result assembly ---

    private List<ContextFragment> createResult(LlmRecommendation llmRecommendation, Set<ProjectFile> existingFiles) {
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

        var summaryFragments = summaryPerCodeUnit(cm, recommendedSummaries);
        var pathFragments = filteredFiles.stream()
                .map(f -> (ContextFragment) new ContextFragment.ProjectPathFragment(f, cm))
                .toList();
        return Stream.concat(summaryFragments.stream(), pathFragments.stream()).toList();
    }

    /** one SummaryFragment per code unit so ArchitectAgent can easily ask user which ones to include */
    private static List<ContextFragment> summaryPerCodeUnit(
            IContextManager contextManager, Map<CodeUnit, String> relevantSummaries) {
        return relevantSummaries.keySet().stream()
                .map(cu -> (ContextFragment) new ContextFragment.SummaryFragment(
                        contextManager, cu.fqName(), ContextFragment.SummaryType.CODEUNIT_SKELETON))
                .toList();
    }

    private Map<CodeUnit, String> getSummaries(Collection<CodeUnit> classes) {
        var coalescedClasses = AnalyzerUtil.coalesceInnerClasses(Set.copyOf(classes));
        logger.debug("Found {} classes", coalescedClasses.size());

        return coalescedClasses.parallelStream()
                .map(cu -> {
                    final String skeleton = analyzer.as(SkeletonProvider.class)
                            .flatMap(skp -> skp.getSkeleton(cu))
                            .orElse("");
                    return Map.entry(cu, skeleton);
                })
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1));
    }

    // --- Files-pruning utilities (budget-capped at 100k) ---

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

        var systemPrompt =
                """
                You are an assistant that performs a first pass of identifying relevant files based on a goal and the existing Workspace contents.
                A second pass will be made using your recommended files, so the top priority is to make sure you
                identify ALL potentially relevant files without leaving any out, even at the cost of some false positives.
                You MUST ONLY select files from the provided <filenames> list. Do NOT invent or include any file that is not exactly present in <filenames>.
                """;
        var filenamePrompt =
                """
                <instructions>
                Given the above goal and Workspace contents (if any), evaluate the following list of filenames
                while thinking carefully about how they may be relevant to the goal.

                Constraints:
                 - You MUST select zero or more files EXCLUSIVELY from the provided <filenames> list.
                 - Do NOT include any filename that is not exactly present in <filenames>.
                 - If no files apply, return an empty selection (i.e., no lines).

                Reason step-by-step:
                 - Identify all files corresponding to class names explicitly mentioned in the <goal>.
                 - Identify all files corresponding to class types used in the <workspace> code.
                 - Think about how you would solve the <goal>, and identify additional files potentially relevant to your plan.
                   For example, if the plan involves instantiating class Foo, or calling a method of class Bar,
                   then Foo.java and Bar.java are relevant files.
                 - Compare this combined list against the filenames available.
                 - It's possible that files that were previously discarded are newly relevant, but when in doubt,
                   do not recommend files that are listed in the <discarded_context> section.

                Output format (strict):
                 - Return ONLY the selected filenames, EXACTLY as they appear in <filenames>, one per line.
                 - No quotes, no bullets, no markdown, and no extra commentary outside of the filenames list.

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

                var rec1 = askLlmDeepPruneFilenamesWithChunking(
                        left, workspaceRepresentation, Integer.MAX_VALUE, filesLlm, false);
                var rec2 = askLlmDeepPruneFilenamesWithChunking(
                        right, workspaceRepresentation, Integer.MAX_VALUE, filesLlm, false);

                var mergedFiles = new ArrayList<>(rec1.recommendedFiles());
                rec2.recommendedFiles().stream()
                        .filter(pf -> !mergedFiles.contains(pf))
                        .forEach(mergedFiles::add);

                var mergedReasoning = (rec1.reasoning() + "\n" + rec2.reasoning()).strip();
                var mergedUsage = Llm.ResponseMetadata.sum(rec1.tokenUsage(), rec2.tokenUsage());

                return new LlmRecommendation(new HashSet<>(mergedFiles), Set.of(), mergedReasoning, mergedUsage);
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
            return new LlmRecommendation(List.of(), List.of(), "Unable to prune filenames");
        }

        if (showBatch1Reasoning) {
            cm.getIo()
                    .llmOutput(
                            "Processing " + chunks.size() + " batches in parallel (showing batch 1)…",
                            ChatMessageType.AI,
                            false,
                            true);
        }

        Llm filesLlmWithEcho = showBatch1Reasoning
                ? cm.getLlm(new Llm.Options(
                                cm.getService().quickestModel(), "ContextAgent Files Unanalyzed %s".formatted(goal))
                        .withForceReasoningEcho())
                : filesLlm;

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
        var combinedReasoning = new StringBuilder();
        @Nullable Llm.ResponseMetadata combinedUsage = null;

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
            combinedUsage = Llm.ResponseMetadata.sum(combinedUsage, rec.tokenUsage());
        }

        if (showBatch1Reasoning) {
            cm.getIo()
                    .llmOutput(
                            "All batches complete. " + combinedFiles.size() + " files selected.",
                            ChatMessageType.AI,
                            false,
                            true);
        }

        return new LlmRecommendation(
                combinedFiles, Set.of(), combinedReasoning.toString().strip(), combinedUsage);
    }

    // --- Evaluate-for-relevance (single-group context window) ---

    private LlmRecommendation askLlmDeepRecommendContext(
            Map<ProjectFile, String> filesMap, Collection<ChatMessage> workspaceRepresentation, Llm llm)
            throws InterruptedException, ContextTooLargeException {

        var contextTool = new ContextRecommendationTool();
        var tr = cm.getToolRegistry().builder().register(contextTool).build();
        var toolSpecs = ToolSpecifications.toolSpecificationsFrom(contextTool);
        assert toolSpecs.size() == 1 : "Expected exactly one tool specification from ContextRecommendationTool";

        var deepPromptTemplate =
                """
                You are an assistant that identifies relevant code context based on a goal and the existing relevant information.
                You are given a goal, the current workspace contents (if any), and a list of files, each with either a summary of its classes or its full content.

                Analyze the provided information and determine which items are most relevant to achieving the goal.
                """;

        var finalSystemMessage = new SystemMessage(deepPromptTemplate);
        var userMessageText = new StringBuilder();

        if (!filesMap.isEmpty()) {
            var filesText = filesMap.entrySet().stream()
                    .map(entry -> "<file path='%s'>\n%s\n</file>"
                            .formatted(entry.getKey().toString(), entry.getValue()))
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

        var result = llm.sendRequest(messages, new ToolContext(toolSpecs, ToolChoice.REQUIRED, tr));
        var tokenUsage = result.metadata();
        if (result.error() != null) {
            var error = result.error();
            // Special case: propagate ContextTooLargeException so caller can retry with halving
            if (error instanceof ContextTooLargeException) {
                throw (ContextTooLargeException) error;
            }
            logger.warn("Error from LLM during context recommendation: {}. Returning empty", error.getMessage());
            return LlmRecommendation.EMPTY;
        }
        var toolRequests = result.toolRequests();
        logger.debug("LLM ToolRequests: {}", toolRequests);
        for (var request : toolRequests) {
            tr.executeTool(request);
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
}
