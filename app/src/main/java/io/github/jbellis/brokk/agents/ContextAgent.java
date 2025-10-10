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
    private final Llm filesLlm;
    private final String goal;
    private final IAnalyzer analyzer;
    private final boolean deepScan;
    private final StreamingChatModel model;

    // if the entire project fits in the Skip Pruning budget, just include it all and call it good
    private final int skipPruningBudget;
    // if our to-prune context is larger than the Pruning budget then we give up
    private int budgetPruning;

    // Rule 1: Use all available summaries if they fit the smallest budget and meet the limit (if not deepScan)
    private static final int QUICK_TOPK = 10;

    public ContextAgent(ContextManager contextManager, StreamingChatModel model, String goal, boolean deepScan)
            throws InterruptedException {
        this.cm = contextManager;
        this.llm = contextManager.getLlm(model, "ContextAgent (%s): %s".formatted(deepScan ? "Deep" : "Quick", goal));
        this.filesLlm = contextManager.getLlm(
                contextManager.getService().quickestModel(),
                "ContextAgent Files (%s): %s".formatted(deepScan ? "Deep" : "Quick", goal));
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
        this.budgetPruning = min(100_000, (int) (actualInputTokens * 0.65));

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
     * @param allowSkipPruning If true, allows the agent to skip LLM-based pruning if the initial context fits
     *     `skipPruningBudget`.
     * @return A RecommendationResult containing success status, fragments, and reasoning.
     */
    public RecommendationResult getRecommendations(boolean allowSkipPruning) throws InterruptedException {
        Collection<ChatMessage> workspaceRepresentation = deepScan
                ? CodePrompts.instance.getWorkspaceContentsMessages(cm.liveContext())
                : CodePrompts.instance.getWorkspaceSummaryMessages(cm.liveContext());

        budgetPruning -= Messages.getApproximateMessageTokens(workspaceRepresentation);
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

        if (!deepScan) {
            // Quick mode: filenames only, ignore analyzer entirely
            var recs = askLlmQuickRecommendContext(
                    allFiles.stream().map(ProjectFile::toString).toList(), workspaceRepresentation);
            return createResult(recs, existingFiles);
        }

        // Deep mode: partition into analyzed / not-analyzed; process groups in parallel
        RecommendationResult firstPassResult = null;
        try {
            firstPassResult = executeDeepMixed(allFiles, existingFiles, workspaceRepresentation, allowSkipPruning);
            if (firstPassResult.success) {
                return firstPassResult;
            }
        } catch (ContextTooLargeException e) {
            // fall back to pruning by filenames first
        }

        @Nullable var cumulativeUsage = firstPassResult != null ? firstPassResult.tokenUsage() : null;

        // Fallback 1: filename-based pruning
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

        // Fallback 2: deep mixed again on the pruned set
        try {
            var finalResult = executeDeepMixed(prunedFiles, existingFiles, workspaceRepresentation, false);
            cumulativeUsage = addTokenUsage(cumulativeUsage, finalResult.tokenUsage());
            return new RecommendationResult(
                    finalResult.success(), finalResult.fragments(), finalResult.reasoning(), cumulativeUsage);
        } catch (ContextTooLargeException e) {
            // Second pruning and still too large: prune filenames again and try once more
            debug("Second pass still too large; performing a second filename-based prune.");
            var secondFilenameResult = createResult(
                    askLlmDeepPruneFilenamesWithChunking(
                            prunedFiles.stream().map(ProjectFile::toString).toList(), workspaceRepresentation),
                    existingFiles);
            cumulativeUsage = addTokenUsage(cumulativeUsage, secondFilenameResult.tokenUsage());

            if (!secondFilenameResult.success) {
                return new RecommendationResult(false, List.of(), secondFilenameResult.reasoning(), cumulativeUsage);
            }
            if (secondFilenameResult.fragments.isEmpty()) {
                return new RecommendationResult(true, List.of(), secondFilenameResult.reasoning(), cumulativeUsage);
            }

            var prunedFiles2 = secondFilenameResult.fragments().stream()
                    .flatMap(f -> f.files().stream())
                    .toList();
            try {
                var finalResult2 = executeDeepMixed(prunedFiles2, existingFiles, workspaceRepresentation, false);
                cumulativeUsage = addTokenUsage(cumulativeUsage, finalResult2.tokenUsage());
                return new RecommendationResult(
                        finalResult2.success(), finalResult2.fragments(), finalResult2.reasoning(), cumulativeUsage);
            } catch (ContextTooLargeException fatal) {
                logGiveUp("second pruning pass");
                return new RecommendationResult(
                        false, List.of(), "Context still too large after second pruning pass", cumulativeUsage);
            }
        }
    }

    // --- Deep mixed processing (summaries + contents) ---

    private RecommendationResult executeDeepMixed(
            List<ProjectFile> filesToConsider,
            Set<ProjectFile> existingFiles,
            Collection<ChatMessage> workspaceRepresentation,
            boolean allowSkipPruning)
            throws InterruptedException, ContextTooLargeException {

        List<ProjectFile> candidates;
        if (!existingFiles.isEmpty()) {
            var seeds = existingFiles.stream().collect(Collectors.toMap(f -> f, f -> 1.0, (v1, v2) -> v1));
            candidates = AnalyzerUtil.combinedRankingFor(cm.getProject(), seeds).stream()
                    .filter(f -> !existingFiles.contains(f))
                    .toList();
            debug("Non-empty workspace; using Git-based distance candidates (target FQNs: {})", candidates);
        } else {
            // Scan all the files
            candidates = filesToConsider;
        }
        Map<CodeUnit, String> summaries = analyzer.as(SkeletonProvider.class)
                .map(skp -> candidates.parallelStream()
                        .map(skp::getSkeletons)
                        .map(Map::entrySet)
                        .flatMap(Set::stream)
                        .filter(e -> !e.getValue().isEmpty())
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1)))
                .orElseGet(Map::of);

        // Any file without a produced summary becomes non-analyzable for content purposes
        var summarizedFiles = summaries.keySet().stream().map(CodeUnit::source).collect(Collectors.toSet());

        var nonAnalyzableFiles = filesToConsider.stream()
                .filter(f -> !summarizedFiles.contains(f))
                .toList();

        var contentsMap = readFileContents(nonAnalyzableFiles);

        int summaryTokens = Messages.getApproximateTokens(summaries.values());
        int contentTokens = Messages.getApproximateTokens(contentsMap.values());
        int combinedTokens = summaryTokens + contentTokens;

        debug(
                "Deep mixed: {} summaries (~{} tokens), {} files content (~{} tokens); combined ~{} tokens",
                summaries.size(),
                summaryTokens,
                contentsMap.size(),
                contentTokens,
                combinedTokens);

        if (allowSkipPruning && combinedTokens <= skipPruningBudget) {
            // Include everything without LLM
            var allFiles = new ArrayList<>(nonAnalyzableFiles);
            var classes = new ArrayList<>(summaries.keySet());
            return createResult(new LlmRecommendation(allFiles, classes, "Under skip-pruning budget"), existingFiles);
        }

        if (combinedTokens > budgetPruning) {
            throw new ContextTooLargeException();
        }

        // Ask LLM once over both sections using tool-calls
        var llmRec = askLlmDeepRecommendContext(summaries, contentsMap, workspaceRepresentation);
        return createResult(llmRec, existingFiles);
    }

    private RecommendationResult createResult(LlmRecommendation llmRecommendation, Set<ProjectFile> existingFiles) {
        var originalFiles = llmRecommendation.recommendedFiles();
        var filteredFiles =
                originalFiles.stream().filter(f -> !existingFiles.contains(f)).toList();
        if (filteredFiles.size() != originalFiles.size()) {
            debug(
                    "Post-filtered LLM recommended files from {} to {} by excluding files already in the workspace",
                    originalFiles.size(),
                    filteredFiles.size());
        }

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
        var recommendedSummaries = getSummaries(recommendedClasses);

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
        debug("Found {} classes", coalescedClasses.size());

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

    // --- Deep Scan: filename pruning utilities ---

    private LlmRecommendation askLlmDeepPruneFilenamesWithChunking(
            List<String> filenames, Collection<ChatMessage> workspaceRepresentation) throws InterruptedException {
        int filenameTokens = Messages.getApproximateTokens(filenames);
        if (filenameTokens > budgetPruning) {
            return deepPruneFilenamesInChunks(filenames, filenameTokens, workspaceRepresentation);
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
        int promptTokens = Messages.getApproximateMessageTokens(messages);
        debug("Invoking LLM to prune filenames (prompt size ~{} tokens)", promptTokens);
        var result = filesLlm.sendRequest(messages, deepScan);
        if (result.error() != null) {
            var error = result.error();
            boolean contextError = error != null
                    && error.getMessage() != null
                    && error.getMessage().toLowerCase(Locale.ROOT).contains("context");
            if (contextError && filenames.size() >= 20) {
                debug("LLM context-window error with {} filenames; splitting and retrying", filenames.size());
                int mid = filenames.size() / 2;
                var left = filenames.subList(0, mid);
                var right = filenames.subList(mid, filenames.size());

                var rec1 = askLlmDeepPruneFilenamesWithChunking(left, workspaceRepresentation);
                var rec2 = askLlmDeepPruneFilenamesWithChunking(right, workspaceRepresentation);

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

    private LlmRecommendation deepPruneFilenamesInChunks(
            List<String> filenames, int filenameTokens, Collection<ChatMessage> workspaceRepresentation)
            throws InterruptedException {
        debug("Chunking {} filenames for parallel pruning", filenames.size());

        // Assume each filename has roughly equal token cost and split into N+1 chunks,
        // where N = floor(filenameTokens / budgetPruning)
        int chunksCount = max(2, (filenameTokens / budgetPruning) + 1);

        // Integer ceil division to distribute filenames as evenly as possible
        int perChunk = 1 + filenames.size() / chunksCount;

        List<List<String>> chunks = new ArrayList<>(chunksCount);
        for (int i = 0; i < filenames.size(); i += perChunk) {
            int end = Math.min(i + perChunk, filenames.size());
            chunks.add(filenames.subList(i, end));
        }

        debug("Created {} chunks for pruning (target per chunk ~{} items)", chunks.size(), perChunk);
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

        // Build mixed deep prompt: can include both summaries and files content
        var contextTool = new ContextRecommendationTool();
        var toolSpecs = ToolSpecifications.toolSpecificationsFrom(contextTool);
        assert toolSpecs.size() == 1 : "Expected exactly one tool specification from ContextRecommendationTool";

        var deepPromptTemplate =
                """
                        You are an assistant that identifies relevant code context based on a goal and the existing relevant information.
                        You are given a goal, the current workspace contents (if any), and the following optional sections:
                        - <available_summaries>: a list of class summaries
                        - <available_files_content>: a list of files and their contents
                        Analyze the provided information and determine which items are most relevant to achieving the goal.
                        """
                        .stripIndent();

        var finalSystemMessage = new SystemMessage(deepPromptTemplate);
        var userMessageText = new StringBuilder();

        if (!summaries.isEmpty()) {
            var summariesText = summaries.entrySet().stream()
                    .map(entry -> {
                        var cn = entry.getKey();
                        var body = entry.getValue();
                        var filename = analyzer.getFileFor(cn.fqName())
                                .map(ProjectFile::toString)
                                .orElse("unknown");
                        return "<class fqcn='%s' file='%s'>\n%s\n</class>".formatted(cn.fqName(), filename, body);
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
                        - Compare this combined list against the classes in <available_summaries> and the files in <available_files_content> if present.

                        Then call the `recommendContext` tool with the appropriate entries:

                        - Populate the `filesToAdd` argument with full (relative) paths of files that will need to be edited, or whose implementation details are necessary.
                        - Populate the `classesToSummarize` argument with fully-qualified names of classes whose APIs will be used.

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
        debug("Invoking LLM to recommend context via tool call (prompt size ~{} tokens)", promptTokens);

        var result = llm.sendRequest(messages, new ToolContext(toolSpecs, ToolChoice.REQUIRED, contextTool), deepScan);
        var tokenUsage = result.tokenUsage();
        if (result.error() != null) {
            var error = result.error();
            if (error.getMessage() != null
                    && error.getMessage().toLowerCase(Locale.ROOT).contains("context")) {
                throw new ContextTooLargeException();
            }
            logger.warn("Error from LLM during context recommendation: {}. Returning empty", error.getMessage());
            return LlmRecommendation.EMPTY;
        }
        var toolRequests = result.toolRequests();
        debug("LLM ToolRequests: {}", toolRequests);
        for (var request : toolRequests) {
            cm.getToolRegistry().executeTool(contextTool, request);
        }

        var projectFiles = toProjectFiles(contextTool.getRecommendedFiles());
        var projectClasses = contextTool.getRecommendedClasses().stream()
                .map(analyzer::getDefinition)
                .flatMap(Optional::stream)
                .filter(CodeUnit::isClass)
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
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .filter(ProjectFile::exists)
                .collect(Collectors.toSet());
    }

    private static class ContextTooLargeException extends Exception {}

    // --- Quick Scan (Simple Prompt) Recommendation ---

    private enum ContextInputType {
        SUMMARIES("available_summaries", "code summaries", "classes", "fully qualified names"),
        FILES_CONTENT("available_files_content", "files with their content", "files", "full paths"),
        FILE_PATHS("available_file_paths", "file paths", "files", "full paths");

        final String xmlTag;
        final String description;
        final String itemTypePlural;
        final String identifierDescription;

        ContextInputType(String xmlTag, String description, String itemTypePlural, String identifierDescription) {
            this.xmlTag = xmlTag;
            this.description = description;
            this.itemTypePlural = itemTypePlural;
            this.identifierDescription = identifierDescription;
        }
    }

    private LlmRecommendation askLlmQuickRecommendContext(
            List<String> filenames, Collection<ChatMessage> workspaceRepresentation) throws InterruptedException {

        // Quick mode: filenames only, ignore analyzer entirely
        String reasoning = "LLM recommended via simple prompt (filenames only).";
        List<ProjectFile> recommendedFiles = List.of();
        List<CodeUnit> recommendedClasses = List.of();

        // Short-circuit to avoid LLM if small
        if (!filenames.isEmpty() && filenames.size() < QUICK_TOPK) {
            recommendedFiles = new ArrayList<>(toProjectFiles(filenames));
            debug("Fewer than QUICK_TOPK filenames ({}); skipping LLM.", filenames.size());
            return new LlmRecommendation(recommendedFiles, recommendedClasses, "Fewer than QUICK_TOPK; selected all");
        }

        var filenameString = String.join("\n", filenames);
        var responseLines =
                simpleRecommendItems(ContextInputType.FILE_PATHS, filenameString, null, workspaceRepresentation);

        recommendedFiles = new ArrayList<>(toProjectFiles(responseLines));
        debug("LLM simple suggested {} relevant files after pruning", recommendedFiles.size());

        debug(
                "Quick scan recommended files: {}",
                recommendedFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(", ")));
        return new LlmRecommendation(recommendedFiles, recommendedClasses, reasoning);
    }

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
                        Output only the %s of the relevant %s, one per line, ordered from most to least relevant.
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
        int promptTokens = Messages.getApproximateMessageTokens(messages);
        debug(
                "Invoking LLM (Quick) to select relevant {} (prompt size ~{} tokens)",
                inputType.itemTypePlural,
                promptTokens);
        var result = llm.sendRequest(messages, deepScan);

        if (result.error() != null) {
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

    private void logGiveUp(String itemDescription) {
        debug("Budget exceeded even for {}. Giving up on automatic context population.", itemDescription);
    }

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
