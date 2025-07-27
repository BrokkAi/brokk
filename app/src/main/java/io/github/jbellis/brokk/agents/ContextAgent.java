package io.github.jbellis.brokk.agents;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.prompts.CodePrompts;
import org.jetbrains.annotations.Nullable;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.util.AdaptiveExecutor;
import io.github.jbellis.brokk.util.Messages;
import io.github.jbellis.brokk.util.TokenAware;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

/**
 * Agent responsible for populating the initial workspace context for the ArchitectAgent.
 * It uses different strategies based on project size, available analysis data, and token limits.
 */
public class ContextAgent {
    private static final Logger logger = LogManager.getLogger(ContextAgent.class);

    private final ContextManager cm;
    private final StreamingChatModel model;
    private final Llm llm;
    private final String goal;
    private final IAnalyzer analyzer;
    private final boolean deepScan;

    // if the entire project fits in the Skip Pruning budget, just include it all and call it good
    private final int skipPruningBudget;
    // if our to-prune context is larger than the Pruning budget then we give up
    private int budgetPruning;

    // Rule 1: Use all available summaries if they fit the smallest budget and meet the limit (if not deepScan)
    private int QUICK_TOPK = 10;

    public ContextAgent(ContextManager contextManager, StreamingChatModel model, String goal, boolean deepScan) throws InterruptedException {
        this.cm = contextManager;
        this.model = model;
        this.llm = contextManager.getLlm(model, "ContextAgent: " + goal); // Coder for LLM interactions
        this.goal = goal;
        this.analyzer = contextManager.getAnalyzer();
        this.deepScan = deepScan;

        int maxInputTokens = contextManager.getService().getMaxInputTokens(model);
        this.skipPruningBudget = min(32_000, maxInputTokens / 4);

        // non-openai models often use more tokens than our estimation so cap this conservatively
        int outputTokens = model.defaultRequestParameters().maxOutputTokens(); // TODO override this when we can
        int actualInputTokens = contextManager.getService().getMaxInputTokens(model) - outputTokens;
        // god, our estimation is so bad (yes we do observe the ratio being this far off)
        this.budgetPruning = (int) (actualInputTokens * 0.65);

        debug("ContextAgent initialized. Budgets: SkipPruning={}, Pruning={}", skipPruningBudget, budgetPruning);
    }

    private void debug(String format, Object... args) {
        String prefix = deepScan ? "[Deep] " : "[Quick] ";
        logger.debug(prefix + format, args);
    }

    /**
     * Result record for context recommendation attempts.
     */
    public record RecommendationResult(boolean success, List<ContextFragment> fragments, String reasoning) {
        static final RecommendationResult FAILED_SINGLE_PASS = new RecommendationResult(false, List.of(), "Project too large to quickly determine context; try Deep Scan");
    }

    /**
     * Result record for the LLM tool call, holding recommended files, class names, and the LLM's reasoning.
     */
    private record LlmRecommendation(List<ProjectFile> recommendedFiles, List<CodeUnit> recommendedClasses, String reasoning) {
        static final LlmRecommendation EMPTY = new LlmRecommendation(List.of(), List.of(), "");
    }

    /**
     * Inner class representing the tool that the LLM can call to recommend context.
     */
    public static class ContextRecommendationTool {
        private final List<String> recommendedFiles = new ArrayList<>();
        private final List<String> recommendedClasses = new ArrayList<>();

        @Tool("Recommend relevant files and classes needed to achieve the user's goal.")
        public void recommendContext(
                @P("List of full paths of files to edit or whose full text is necessary.")
                List<String> filesToAdd,
                @P("List of fully-qualified class names for classes whose APIs are relevant to the goal but which do not need to be edited.")
                List<String> classesToSummarize)
        {
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

    /**
     * Calculates the approximate token count for a list of ContextFragments.
     */
    int calculateFragmentTokens(List<ContextFragment> fragments) {
        int totalTokens = 0;

        for (var fragment : fragments) {
            if (fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                Optional<ProjectFile> fileOpt = fragment.files().stream()
                        .findFirst();

                if (fileOpt.isPresent()) {
                    var file = fileOpt.get();
                    String content;
                    try {
                        content = file.read();
                    } catch (IOException e) {
                        debug("IOException reading file for token calculation: {}", file, e);
                        content = ""; // Ensure content is not null for token calculation
                    }
                    totalTokens += Messages.getApproximateTokens(content);
                } else {
                    debug("PROJECT_PATH fragment {} did not yield a ProjectFile for token calculation.", fragment.description());
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
     * Determines the best initial context based on project size and token budgets,
     * potentially using LLM inference, and returns recommended context fragments.
     * <p>
     * potentially using LLM inference, and returns recommended context fragments.
     *
     * @param allowSkipPruning If true, allows the agent to skip LLM-based pruning if the initial context fits `skipPruningBudget`.
     * @return A RecommendationResult containing success status, fragments, and reasoning.
     */
    public RecommendationResult getRecommendations(boolean allowSkipPruning) throws InterruptedException {
        Collection<ChatMessage> workspaceRepresentation;
        workspaceRepresentation = deepScan 
                                  ? CodePrompts.instance.getWorkspaceContentsMessages(cm.liveContext()) 
                                  : CodePrompts.instance.getWorkspaceSummaryMessages(cm.liveContext());
        budgetPruning -= Messages.getApproximateTokens(workspaceRepresentation);
        var allFiles = cm.getProject().getAllFiles().stream().sorted().toList();

        // try single-pass mode first
        if (analyzer.isEmpty()) {
            // If no analyzer, try full file contents directly
            var contentResult = executeWithFileContents(allFiles, workspaceRepresentation, allowSkipPruning);
            if (contentResult.success) {
                return contentResult;
            }
            // If contents failed, fall through to filename-based pruning
        } else {
            var summaryResult = executeWithSummaries(allFiles, workspaceRepresentation, allowSkipPruning);
            if (summaryResult.success) {
                return summaryResult;
            }
            // If summaries failed (e.g., too large even for pruning), fall through to filename-based pruning
        }

        if (!deepScan) {
            // In quick mode, if we can't process in a single pass, exit immediately
            logGiveUp("summaries (too large for pruning budget in quick mode)");
            return RecommendationResult.FAILED_SINGLE_PASS;
        }

        // Fallback 1: Prune based on filenames only
        debug("Falling back to filename-based pruning.");
        var filenameResult = executeWithFilenamesOnly(allFiles, workspaceRepresentation);
        if (!filenameResult.success) {
            logGiveUp("filename list (too large for LLM pruning)");
            return new RecommendationResult(false, List.of(), filenameResult.reasoning()); // Return failure with reasoning
        }

        var prunedFiles = filenameResult.fragments.stream()
                .flatMap(f -> f.files().stream()) // No analyzer
                .toList();

        if (prunedFiles.isEmpty()) {
            debug("Filename pruning resulted in an empty list. No context found.");
            // Return success=true, but empty list, include reasoning from filename pruning step
            return new RecommendationResult(true, List.of(), filenameResult.reasoning());
        }
        debug("LLM pruned to {} files based on names. Reasoning: {}", prunedFiles.size(), filenameResult.reasoning());

        // Fallback 2: After pruning filenames, try again with summaries or contents using the pruned list
        if (!analyzer.isEmpty()) {
            debug("Attempting context recommendation with summaries of pruned files.");
            // When using pruned files, skipPruning is not relevant as we've already decided to prune.
            var summaryResult = executeWithSummaries(prunedFiles, workspaceRepresentation, false);
            // Return the result directly (success or failure, with fragments and reasoning)
            if (!summaryResult.success) {
                logGiveUp("summaries of pruned files");
            }
            return summaryResult;
        } else {
            debug("Attempting context recommendation with contents of pruned files.");
            // When using pruned files, skipPruning is not relevant as we've already decided to prune.
            var contentResult = executeWithFileContents(prunedFiles, workspaceRepresentation, false);
            // Return the result directly (success or failure, with fragments and reasoning)
            if (!contentResult.success) {
                logGiveUp("contents of pruned files");
            }
            return contentResult;
        }
    }

    // --- Logic branch for using class summaries ---

    private RecommendationResult executeWithSummaries(List<ProjectFile> filesToConsider,
                                                      Collection<ChatMessage> workspaceRepresentation,
                                                      boolean allowSkipPruning) throws InterruptedException
    {
        Map<CodeUnit, String> rawSummaries;
        var ctx = cm.liveContext();

        if (isCodeInWorkspace(ctx) && !deepScan) {
            // If the workspace isn't empty, use pagerank candidates for Quick context
            var ac = cm.liveContext().buildAutoContext(50);
            // fetchSkeletons() is private in SkeletonFragment. We need to use its sources() or text().
            // For now, let's get the target FQNs and then fetch summaries for them.
            List<String> targetFqns = ac.getTargetIdentifiers();
            debug("Non-empty workspace; using pagerank candidates (target FQNs: {})", String.join(",", targetFqns));

            // Create a temporary map for rawSummaries from these targetFqns
            Map<CodeUnit, String> tempSummaries = new HashMap<>();
            for (String fqn : targetFqns) {
                analyzer.getDefinition(fqn).ifPresent(cu -> {
                    if (cu.isClass()) {
                        analyzer.getSkeleton(fqn).ifPresent(skel -> tempSummaries.put(cu, skel));
                    }
                });
            }
            rawSummaries = tempSummaries;
        } else {
            // Scan all the files
            requireNonNull(analyzer);
            rawSummaries = filesToConsider.stream().parallel()
                    .flatMap(f -> analyzer.getSkeletons(f).entrySet().stream())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1));
        }

        int summaryTokens = Messages.getApproximateTokens(String.join("\n", rawSummaries.values()));
        debug("Total tokens for {} summaries (from {} files): {}", rawSummaries.size(), filesToConsider.size(), summaryTokens);

        boolean withinLimit = deepScan || rawSummaries.size() <= QUICK_TOPK;
        if (allowSkipPruning && summaryTokens <= skipPruningBudget && withinLimit) {
            var codeUnits = rawSummaries.keySet().stream().toList();
            return createResult(new LlmRecommendation(List.of(), codeUnits, "All summaries are under budget"));
        }

        // Ask LLM to pick relevant summaries/files if all summaries fit the Pruning budget
        if (summaryTokens <= budgetPruning) {
            var llmRecommendation = askLlmToRecommendContext(List.of(), rawSummaries, Map.of(), workspaceRepresentation);
            return createResult(llmRecommendation);
        }

        if (!deepScan) {
            String reason = "Summaries too large for quick context pruning budget.";
            logGiveUp(reason);
            return new RecommendationResult(false, List.of(), reason);
        }

        // Else, split the recommendation task across multiple calls
        debug("Summaries too large for a single call ({} tokens), splitting into chunks.", summaryTokens);
        return createResult(recommendInChunks(rawSummaries, workspaceRepresentation, budgetPruning));
    }

    private static boolean isCodeInWorkspace(Context ctx) {
        return ctx.allFragments().flatMap(f -> f.sources().stream()).findAny().isPresent();
    }

    private LlmRecommendation recommendInChunks(Map<CodeUnit, String> rawSummaries,
                                                   Collection<ChatMessage> workspaceRepresentation, 
                                                   int tokensPerMessage) 
            throws InterruptedException
    {
        // A record to hold a chunk of summaries and its token count
        record SummaryChunk(Map<CodeUnit, String> summaries, int tokenCount) {}

        // Partition summaries into chunks that fit within the pruning budget
        List<SummaryChunk> chunks = new ArrayList<>();
        var currentChunkSummaries = new LinkedHashMap<CodeUnit, String>();
        int currentChunkTokens = 0;
        for (var entry : rawSummaries.entrySet()) {
            String summaryText = entry.getValue();
            int summaryTokensCount = Messages.getApproximateTokens(summaryText);
            if (!currentChunkSummaries.isEmpty() && currentChunkTokens + summaryTokensCount > tokensPerMessage) {
                chunks.add(new SummaryChunk(new LinkedHashMap<>(currentChunkSummaries), currentChunkTokens));
                currentChunkSummaries = new LinkedHashMap<>();
                currentChunkTokens = 0;
            }
            currentChunkSummaries.put(entry.getKey(), entry.getValue());
            currentChunkTokens += summaryTokensCount;
        }
        if (!currentChunkSummaries.isEmpty()) {
            chunks.add(new SummaryChunk(new LinkedHashMap<>(currentChunkSummaries), currentChunkTokens));
        }
        debug("Split into {} chunks.", chunks.size());

        var recommendations = new ArrayList<LlmRecommendation>();
        int parallelStartIndex = 0;

        // If workspace is not empty, process the first chunk synchronously to warm up the prefix cache.
        if (isCodeInWorkspace(cm.liveContext()) && !chunks.isEmpty()) {
            debug("Warming up prefix cache with first chunk.");
            var firstChunk = chunks.getFirst();
            var firstRecommendation = askLlmToRecommendContext(List.of(), firstChunk.summaries(), Map.of(), workspaceRepresentation);
            recommendations.add(firstRecommendation);
            parallelStartIndex = 1;
            if (Thread.currentThread().isInterrupted()) {
                return new LlmRecommendation(List.of(), List.of(), "Interrupted during context recommendation.");
            }
        }

        if (chunks.size() > parallelStartIndex) {
            var service = cm.getService();
            var executorService = AdaptiveExecutor.create(service, model, chunks.size() - parallelStartIndex);

            interface TokenAwareLlmRecommendationCallable extends Callable<LlmRecommendation>, TokenAware {}

            List<Future<LlmRecommendation>> futures = new ArrayList<>();
            try {
                for (int i = parallelStartIndex; i < chunks.size(); i++) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    var chunk = chunks.get(i);
                    futures.add(executorService.submit(new TokenAwareLlmRecommendationCallable() {
                        @Override
                        public int tokens() {
                            return chunk.tokenCount();
                        }

                        @Override
                        public LlmRecommendation call() throws Exception {
                            return askLlmToRecommendContext(List.of(), chunk.summaries(), Map.of(), workspaceRepresentation);
                        }
                    }));
                }

                for (var future : futures) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    try {
                        LlmRecommendation result = future.get();
                        recommendations.add(result);
                    } catch (ExecutionException e) {
                        logger.error("Error recommending context for a chunk", e.getCause());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                executorService.shutdownNow();
            }
        }

        if (Thread.currentThread().isInterrupted()) {
            return new LlmRecommendation(List.of(), List.of(), "Interrupted during context recommendation.");
        }

        // Merge recommendations from all chunks
        var mergedFiles = recommendations.stream()
                .flatMap(r -> r.recommendedFiles().stream())
                .distinct()
                .toList();
        var mergedClasses = recommendations.stream()
                .flatMap(r -> r.recommendedClasses().stream())
                .distinct()
                .toList();
        var mergedReasoning = recommendations.stream()
                .map(LlmRecommendation::reasoning)
                .filter(r -> !r.isBlank())
                .collect(Collectors.joining("\n\n---\n\n"));

        var finalRecommendation = new LlmRecommendation(mergedFiles, mergedClasses, mergedReasoning);
        debug("Merged recommendations from all chunks. Files: {}, Classes: {}", finalRecommendation.recommendedFiles().size(), finalRecommendation.recommendedClasses().size());

        return finalRecommendation;
    }

    private RecommendationResult createResult(LlmRecommendation llmRecommendation) {
        var recommendedFiles = llmRecommendation.recommendedFiles();
        // LLM might recommend both a file and a summary of a class in that file. r/m any such redundant classes
        var recommendedClasses = llmRecommendation.recommendedClasses().stream()
                .filter(cu -> !llmRecommendation.recommendedFiles.contains(cu.source()))
                .toList();

        var reasoning = llmRecommendation.reasoning();

        // Get summaries for recommended classes
        var recommendedSummaries = getSummaries(recommendedClasses, false);

        // Calculate combined token size
        int recommendedSummaryTokens = Messages.getApproximateTokens(String.join("\n", recommendedSummaries.values()));
        var recommendedContentsMap = readFileContents(recommendedFiles);
        int recommendedContentTokens = Messages.getApproximateTokens(String.join("\n", recommendedContentsMap.values()));
        int totalRecommendedTokens = recommendedSummaryTokens + recommendedContentTokens;

        debug("LLM recommended {} classes ({} tokens) and {} files ({} tokens). Total: {} tokens",
              recommendedSummaries.size(), recommendedSummaryTokens,
              recommendedFiles.size(), recommendedContentTokens, totalRecommendedTokens);

        // Create fragments
        var skeletonFragments = skeletonPerSummary(cm, recommendedSummaries);
        var pathFragments = recommendedFiles.stream()
                .map(f -> (ContextFragment) new ContextFragment.ProjectPathFragment(f, cm))
                .toList();
        // Remove fragments that correspond to in-Workspace context
        var existingFiles = cm.liveContext().allFragments()
                .flatMap(f -> f.files().stream())
                .collect(Collectors.toSet());
        var combinedFragments = Stream.concat(skeletonFragments.stream(), pathFragments.stream())
                .filter(f -> {
                    Set<ProjectFile> fragmentFiles = f.files();
                    return fragmentFiles.stream().noneMatch(existingFiles::contains);
                }).toList();
        if (combinedFragments.size() < skeletonFragments.size() + pathFragments.size()) {
            logger.debug("After removing fragments already in Workspace {}, remaining are {}",
                         ContextFragment.getSummary(cm.liveContext().allFragments()), combinedFragments);
        }

        return new RecommendationResult(true, combinedFragments, reasoning);
    }

    /**
     * one SkeletonFragment per summary so ArchitectAgent can easily ask user which ones to include
     */
    private static List<ContextFragment> skeletonPerSummary(IContextManager
                                                                    contextManager, Map<CodeUnit, String> relevantSummaries)
    {
        return relevantSummaries.keySet().stream()
                .map(s -> (ContextFragment) new ContextFragment.SkeletonFragment(contextManager, List.of(s.fqName()), ContextFragment.SummaryType.CLASS_SKELETON))
                .toList();
    }

    private Map<CodeUnit, String> getSummaries(Collection<CodeUnit> classes, boolean parallel) {
        var coalescedClasses = AnalyzerUtil.coalesceInnerClasses(Set.copyOf(classes));
        debug("Found {} classes", coalescedClasses.size());

        var stream = coalescedClasses.stream();
        if (parallel) {
            stream = stream.parallel();
        }
        return stream
                .map(cu -> {
                    var skeletonOpt = analyzer.getSkeleton(cu.fqName());
                    String skeleton = skeletonOpt.orElse("");
                    return Map.entry(cu, skeleton);
                })
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private LlmRecommendation askLlmToRecommendContext(List<String> filenames,
                                                       Map<CodeUnit, String> summaries,
                                                       Map<ProjectFile, String> contentsMap,
                                                       Collection<ChatMessage> workspaceRepresentation) throws InterruptedException
    {
        if (deepScan) {
            return askLlmDeepRecommendContext(filenames, summaries, contentsMap, workspaceRepresentation);
        } else {
            return askLlmQuickRecommendContext(filenames, summaries, contentsMap, workspaceRepresentation);
        }
    }

    // --- Deep Scan (Tool-based) Recommendation ---

    private LlmRecommendation askLlmDeepRecommendContext(List<String> filenames,
                                                         Map<CodeUnit, String> summaries,
                                                         Map<ProjectFile, String> contentsMap,
                                                         Collection<ChatMessage> workspaceRepresentation) throws InterruptedException
    {
        // Determine the type of context being provided
        String contextTypeElement;
        String contextTypeDescription;
        if (!summaries.isEmpty()) {
            contextTypeElement = "available_summaries";
            contextTypeDescription = "a list of class summaries";
        } else if (!contentsMap.isEmpty()) {
            contextTypeElement = "available_files_content";
            contextTypeDescription = "a list of file paths and their contents";
        } else {
            contextTypeElement = "available_file_paths";
            contextTypeDescription = "a list of file paths";
        }
        assert !summaries.isEmpty() || !contentsMap.isEmpty() || !filenames.isEmpty();

        // exactly one of filesToConsider, summaries, and contentsMap should be non-empty, depending on whether
        // we're recommending based on filenames, summaries, or full file contents, respectively
        var contextTool = new ContextRecommendationTool();
        // Generate specifications from the annotated tool instance
        var toolSpecs = ToolSpecifications.toolSpecificationsFrom(contextTool);
        assert toolSpecs.size() == 1 : "Expected exactly one tool specification from ContextRecommendationTool";

        var deepPromptTemplate = """
                                 You are an assistant that identifies relevant code context based on a goal and available information.
                                 You are given a goal, the current workspace contents (if any), and %s (within <%s> tags).
                                 Analyze the provided information and determine which items are most relevant to achieving the goal.
                                 You MUST call the `recommendContext` tool to provide your recommendations.
                                 DO NOT recommend files or classes that are already in the Workspace.
                                 Populate the `filesToAdd` argument with the full (relative) paths of files that will need to be edited as part of the goal,
                                 or whose implementation details are necessary. Put these files in `filesToAdd` (even if you are only shown a summary).
                                 
                                 Populate the `classesToSummarize` argument with the fully-qualified names of classes whose APIs will be used.
                                 
                                 Either or both of `filesToAdd` and `classesToSummarize` may be empty.
                                 """.formatted(contextTypeDescription, contextTypeElement).stripIndent();

        var finalSystemMessage = new SystemMessage(deepPromptTemplate);
        var userMessageText = new StringBuilder();

        // Add context data based on type (Deep Scan version)
        if (!summaries.isEmpty()) {
            var summariesText = summaries.entrySet().stream()
                    .map(entry -> {
                        var cn = entry.getKey();
                        var body = entry.getValue();
                        // Map Optional<ProjectFile> to String filename, defaulting if not present
                        var filename = analyzer.getFileFor(cn.fqName()).map(ProjectFile::toString).orElse("unknown");
                        // Always include filename for deep scan
                        return "<class fqcn='%s' file='%s'>\n%s\n</class>".formatted(cn.fqName(), filename, body);
                    })
                    .collect(Collectors.joining("\n\n"));
            userMessageText.append("<%s>\n%s\n</%s>\n\n".formatted(contextTypeElement, summariesText, contextTypeElement));
        } else if (!contentsMap.isEmpty()) {
            var filesText = contentsMap.entrySet().stream()
                    .map(entry -> "<file path='%s'>\n%s\n</file>".formatted(entry.getKey().toString(), entry.getValue()))
                    .collect(Collectors.joining("\n\n"));
            userMessageText.append("<%s>\n%s\n</%s>\n\n".formatted(contextTypeElement, filesText, contextTypeElement));
        } else { // Only provide paths if contents/summaries aren't already there
            var filesText = String.join("\n", filenames);
            userMessageText.append("<%s>\n%s\n</%s>\n\n".formatted(contextTypeElement, filesText, contextTypeElement));
        }

        userMessageText.append("Call the `recommendContext` tool with the appropriate entries from %s based on the goal and available context.".formatted(contextTypeElement));
        userMessageText.append("\n<goal>\n%s\n</goal>\n".formatted(goal));

        // Construct final message list including workspace representation
        List<ChatMessage> messages = Stream.concat(Stream.of(finalSystemMessage),
                                                   Stream.concat(workspaceRepresentation.stream(), Stream.of(new UserMessage(userMessageText.toString()))))
                .toList();

        int promptTokens = Messages.getApproximateTokens(messages);
        debug("Invoking LLM to recommend context via tool call (prompt size ~{} tokens)", promptTokens);

        // *** Execute LLM call with required tool ***
        var result = llm.sendRequest(messages, toolSpecs, ToolChoice.REQUIRED, false);
        if (result.error() != null || result.isEmpty()) {
            var error = result.error();
            // litellm does an inconsistent job translating into ContextWindowExceededError. https://github.com/BrokkAi/brokk/issues/540
            if (error != null && error.getMessage() != null && error.getMessage().contains("context")) {
                // we don't have the original raw budget available here, and we don't want to split into more than
                // two messages, so 0.6 is a reasonable value
                debug("Context window exceeded, splitting recursively");
                return recommendInChunks(summaries, workspaceRepresentation, (int) (promptTokens * 0.6));
            }
            logger.warn("Error or empty response from LLM during context recommendation: {}. Returning empty.",
                        error != null ? error.getMessage() : "Empty response");
            return LlmRecommendation.EMPTY;
        }
        var toolRequests = result.toolRequests();
        debug("LLM ToolRequests: {}", toolRequests);
        // only one call is necessary but handle LLM making multiple calls
        for (var request : toolRequests) {
            cm.getToolRegistry().executeTool(contextTool, request);
        }

        String reasoning = result.text();
        // Filter out files/classes already in workspace
        var projectFiles = contextTool.getRecommendedFiles().stream()
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
                .toList();

        var projectClasses = contextTool.getRecommendedClasses().stream()
                // Use getDefinition to get the CodeUnit directly, which handles file lookup
                .map(analyzer::getDefinition)
                .flatMap(Optional::stream) // Convert java.util.Optional to Stream
                .filter(CodeUnit::isClass) // Ensure it's actually a class
                .toList();

        debug("Tool recommended files: {}", projectFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(", ")));
        debug("Tool recommended classes: {}", projectClasses.stream().map(CodeUnit::identifier).collect(Collectors.joining(", ")));
        return new LlmRecommendation(projectFiles, projectClasses, reasoning);
    }

    // --- Quick Scan (Simple Prompt) Recommendation ---

    /**
     * Enum to define the type of context input being provided to the simple LLM prompt.
     */
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

    private LlmRecommendation askLlmQuickRecommendContext(List<String> filenames,
                                                          Map<CodeUnit, String> summaries,
                                                          Map<ProjectFile, String> contentsMap,
                                                          Collection<ChatMessage> workspaceRepresentation) throws InterruptedException
    {
        String reasoning = "LLM recommended via simple prompt.";
        List<ProjectFile> recommendedFiles = List.of();
        List<CodeUnit> recommendedClasses = List.of();
        List<String> responseLines;

        if (!summaries.isEmpty()) {
            var summariesText = summaries.entrySet().stream()
                    .map(entry -> "<class fqcn='%s'>\n%s\n</class>".formatted(entry.getKey().fqName(), entry.getValue()))
                    .collect(Collectors.joining("\n\n"));
            responseLines = simpleRecommendItems(ContextInputType.SUMMARIES, summariesText, QUICK_TOPK, workspaceRepresentation);

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
                    .map(entry -> "<file path='%s'>\n%s\n</file>".formatted(entry.getKey().toString(), entry.getValue()))
                    .collect(Collectors.joining("\n\n"));
            responseLines = simpleRecommendItems(ContextInputType.FILES_CONTENT, filesText, QUICK_TOPK, workspaceRepresentation);

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
            responseLines = simpleRecommendItems(ContextInputType.FILE_PATHS, filenameString, null, workspaceRepresentation); // No topK for pruning

            // Convert response strings back to ProjectFile objects
            var allFilesMap = cm.getProject().getAllFiles().stream()
                    .collect(Collectors.toMap(ProjectFile::toString, pf -> pf));
            recommendedFiles = responseLines.stream()
                    .map(allFilesMap::get)
                    .filter(Objects::nonNull)
                    .toList();
            debug("LLM simple suggested {} relevant files after pruning", recommendedFiles.size());
        }

        debug("Quick scan recommended files: {}", recommendedFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(", ")));
        debug("Quick scan recommended classes: {}", recommendedClasses.stream().map(CodeUnit::identifier).collect(Collectors.joining(", ")));
        return new LlmRecommendation(recommendedFiles, recommendedClasses, reasoning);
    }


    /**
     * Generic method to ask the LLM (Quick mode) to select relevant items (classes or files) based on input data.
     *
     * @param inputType               The type of input being provided (summaries, file contents, or file paths).
     * @param inputBlob               A string containing the formatted input data (e.g., XML-like structure of summaries or file contents).
     * @param topK                    Optional limit on the number of items to return.
     * @param workspaceRepresentation Messages representing the current workspace state.
     * @return A list of strings representing the identifiers (FQNs or paths) recommended by the LLM.
     */
    private List<String> simpleRecommendItems(ContextInputType inputType,
                                              String inputBlob,
                                              @Nullable Integer topK,
                                              Collection<ChatMessage> workspaceRepresentation) throws InterruptedException
    {
        var systemMessage = new StringBuilder("""
                                              You are an assistant that identifies relevant %s based on a goal.
                                              Given a list of %s and a goal, identify which %s are most relevant to achieving the goal.
                                              Output *only* the %s of the relevant %s, one per line, ordered from most to least relevant.
                                              Do not include any other text, explanations, or formatting.
                                              """.formatted(inputType.itemTypePlural, inputType.description, inputType.itemTypePlural, inputType.identifierDescription, inputType.itemTypePlural).stripIndent());

        if (topK != null) {
            systemMessage.append("\nLimit your response to the top %d most relevant %s.".formatted(topK, inputType.itemTypePlural));
        }

        var finalSystemMessage = new SystemMessage(systemMessage.toString());
        var userPrompt = """
                         <goal>
                         %s
                         </goal>
                         
                         <%s>
                         %s
                         </%s>
                         
                         Which of these %s are most relevant to the goal? Take into consideration what is already in the workspace (provided as prior messages), and list their %s, one per line.
                         """.formatted(goal, inputType.xmlTag, inputBlob, inputType.xmlTag, inputType.itemTypePlural, inputType.identifierDescription).stripIndent();

        List<ChatMessage> messages = Stream.concat(Stream.of(finalSystemMessage),
                                                   Stream.concat(workspaceRepresentation.stream(), Stream.of(new UserMessage(userPrompt))))
                .toList();
        int promptTokens = Messages.getApproximateTokens(messages);
        debug("Invoking LLM (Quick) to select relevant {} (prompt size ~{} tokens)", inputType.itemTypePlural, promptTokens);
        var result = llm.sendRequest(messages); // No tools

        if (result.error() != null || result.isEmpty()) {
            logger.warn("Error or empty response from LLM during quick %s selection: {}. Returning empty.",
                        inputType.itemTypePlural, result.error() != null ? result.error().getMessage() : "Empty response");
            return List.of();
        }

        var responseLines = result.text().lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
        debug("LLM simple response lines ({}): {}", inputType.itemTypePlural, responseLines);
        return responseLines;
    }

    // --- Logic branch for using full file contents (when analyzer is not available or summaries failed) ---

    private RecommendationResult executeWithFileContents(List<ProjectFile> filesToConsider,
                                                         Collection<ChatMessage> workspaceRepresentation,
                                                         boolean allowSkipPruning) throws InterruptedException
    {
        // If the workspace isn't empty and we have no analyzer, don't suggest adding whole files.
        if (analyzer.isEmpty() && !cm.getEditableFiles().isEmpty()) {
            debug("Non-empty context and no analyzer present, skipping file content suggestions");
            return new RecommendationResult(true, List.of(), "Skipping file content suggestions for non-empty context without analyzer.");
        }

        var contentsMap = readFileContents(filesToConsider);
        int contentTokens = Messages.getApproximateTokens(String.join("\n", contentsMap.values()));
        debug("Total tokens for {} files' content: {}", contentsMap.size(), contentTokens);

        // Rule 1: Use all available files if content fits the smallest budget and meet the limit (if not deepScan)
        boolean withinLimit = deepScan || filesToConsider.size() <= QUICK_TOPK; // Use QUICK_TOPK here
        if (allowSkipPruning && contentTokens <= skipPruningBudget && withinLimit) {
            return createResult(new LlmRecommendation(filesToConsider, List.of(), "All file contents are within token budget"));
        }

        // Rule 2: Ask LLM to pick relevant files if all content fits the Pruning budget
        if (contentTokens <= budgetPruning) {
            var llmRecommendation = askLlmToRecommendContext(List.of(), Map.of(), contentsMap, workspaceRepresentation);
            return createResult(llmRecommendation);
        }

        String reason = "File contents too large for LLM pruning budget.";
        logGiveUp("file contents");
        return new RecommendationResult(false, List.of(), reason);
    }

    private Map<ProjectFile, String> readFileContents(Collection<ProjectFile> files) {
        return files.stream().parallel()
                .map(file -> {
                    try {
                        return Map.entry(file, file.read());
                    } catch (IOException e) {
                        logger.warn("Could not read content of file: {}. Skipping.", file, e);
                        return Map.entry(file, ""); // Return empty string on error
                    } catch (UncheckedIOException e) {
                        logger.warn("Could not read content of file (Unchecked): {}. Skipping.", file, e);
                        return Map.entry(file, "");
                    }
                })
                .filter(entry -> !entry.getValue().isEmpty()) // Filter out files that couldn't be read
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // --- Logic for pruning based on filenames only (fallback) ---

    private RecommendationResult executeWithFilenamesOnly
            (List<ProjectFile> allFiles, Collection<ChatMessage> workspaceRepresentation) throws InterruptedException
    {
        int filenameTokens = Messages.getApproximateTokens(getFilenameString(allFiles));
        debug("Total tokens for filename list: {}", filenameTokens);

        if (filenameTokens > budgetPruning) {
            // Too large even for the LLM to prune based on names
            return new RecommendationResult(false, List.of(), "Filename list too large for LLM pruning budget.");
        }

        // Ask LLM to recommend files based *only* on paths
        var llmRecommendation = askLlmToRecommendContext(allFiles.stream().map(ProjectFile::toString).toList(), Map.of(), Map.of(), workspaceRepresentation);
        // For filename-only, the result is created inside askLlm (both deep and quick)
        // If quick mode failed inside askLlm (e.g. empty response), it returns EMPTY, createResult handles this.
        // If deep mode failed, it also returns EMPTY.
        // createResult now handles the final budget check implicitly.
        return createResult(llmRecommendation);
    }


    // --- Helper methods ---

    private String getFilenameString(List<ProjectFile> files) {
        return files.stream()
                .map(ProjectFile::toString)
                .collect(Collectors.joining("\n"));
    }

    private void logGiveUp(String itemDescription) {
        debug("Budget exceeded even for {}. Giving up on automatic context population.", itemDescription);
    }
}
