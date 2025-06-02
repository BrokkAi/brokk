package io.github.jbellis.brokk.agents;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.util.Messages;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Math.min;

/**
 * Agent responsible for populating the initial workspace context for the ArchitectAgent.
 * It uses different strategies based on project size, available analysis data, and token limits.
 */
public class ContextAgent {
    private static final Logger logger = LogManager.getLogger(ContextAgent.class);

    private final ContextManager contextManager;
    private final Llm llm;
    private final String goal;
    private final IConsoleIO io;
    private final IAnalyzer analyzer;
    private final boolean deepScan;

    // if the entire project fits in the Skip Pruning budget, just include it all and call it good
    private final int skipPruningBudget;
    // if our to-prune context is larger than the Pruning budget then we give up
    private final int budgetPruning;
    // if our pruned context is larger than the Final target budget then we also give up
    private final int finalBudget;

    // Rule 1: Use all available summaries if they fit the smallest budget and meet the limit (if not deepScan)
    private int QUICK_TOPK = 10;

    public ContextAgent(ContextManager contextManager, StreamingChatLanguageModel model, String goal, boolean deepScan) throws InterruptedException {
        this.contextManager = contextManager;
        this.llm = contextManager.getLlm(model, "ContextAgent: " + goal); // Coder for LLM interactions
        this.goal = goal;
        this.io = contextManager.getIo();
        this.analyzer = contextManager.getAnalyzer();
        this.deepScan = deepScan;

        int maxInputTokens = contextManager.getService().getMaxInputTokens(model);
        this.skipPruningBudget = min(32_000, maxInputTokens / 4);
        this.finalBudget = maxInputTokens / 2;
        this.budgetPruning = (int) (maxInputTokens * 0.9);

        debug("ContextAgent initialized. Budgets: SkipPruning={}, Final={}, Pruning={}", skipPruningBudget, finalBudget, budgetPruning);
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
            this.recommendedFiles.addAll(filesToAdd == null ? List.of() : filesToAdd);
            this.recommendedClasses.addAll(classesToSummarize == null ? List.of() : classesToSummarize);
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
     * Executes the context population logic, obtains context recommendations,
     * presents them for selection (if workspace is not empty), and adds them to the workspace.
     *
     * @return true if context fragments were successfully added, false otherwise
     */
    public boolean execute() throws InterruptedException {
        io.llmOutput("\nExamining initial workspace", ChatMessageType.CUSTOM);

        // Execute without a specific limit on recommendations, allowing skip-pruning
        var recommendationResult = getRecommendations(true);
        if (!recommendationResult.success || recommendationResult.fragments.isEmpty()) {
            io.llmOutput("\nNo additional recommended context found", ChatMessageType.CUSTOM);
            // Display reasoning even if no fragments were found, if available
            if (!recommendationResult.reasoning.isBlank()) {
                io.llmOutput("\nReasoning: " + recommendationResult.reasoning, ChatMessageType.CUSTOM);
            }
            return false;
        }
        io.llmOutput("\nReasoning for recommendations: " + recommendationResult.reasoning, ChatMessageType.CUSTOM);

        // Final budget check
        int totalTokens = calculateFragmentTokens(recommendationResult.fragments());
        debug("Total tokens for recommended context: {}", totalTokens);

        if (totalTokens > finalBudget) {
            logger.warn("Recommended context ({} tokens) exceeds final budget ({} tokens). Skipping context addition.", totalTokens, finalBudget);
            logGiveUp("recommended context (exceeded final budget)");
            // Optionally provide reasoning about exceeding budget
            io.llmOutput("\nWarning: Recommended context exceeded the final budget and could not be added automatically.", ChatMessageType.CUSTOM);
            return false; // Indicate failure due to budget
        }

        debug("Recommended context fits within final budget.");
        addSelectedFragments(recommendationResult.fragments);

        return true;
    }

    /**
     * Calculates the approximate token count for a list of ContextFragments.
     */
    private int calculateFragmentTokens(List<ContextFragment> fragments) {
        int totalTokens = 0;

        for (var fragment : fragments) {
            if (fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                Optional<ProjectFile> fileOpt = fragment.files().stream()
                    .findFirst()
                    .filter(ProjectFile.class::isInstance)
                    .map(ProjectFile.class::cast);

                if (fileOpt.isPresent()) {
                    var file = fileOpt.get();
                    String content = null;
                    try {
                        content = file.read();
                    } catch (IOException e) {
                        debug("IOException reading file for token calculation: {}", file, e);
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
     * ContextAgent returns fragment-per-file to make it easy for DeepScanDialog to get user input, but
     * when adding to the Workspace we want to merge the summaries into a singleSkeletonFragment. This
     * takes care of that.
     */
    public void addSelectedFragments(List<ContextFragment> selected) {
        // Group selected fragments by type
        var groupedByType = selected.stream().collect(Collectors.groupingBy(ContextFragment::getType));

        // Process ProjectPathFragments
        var pathFragments = groupedByType.getOrDefault(ContextFragment.FragmentType.PROJECT_PATH, List.of()).stream()
                .map(ContextFragment.ProjectPathFragment.class::cast)
                .toList();
        if (!pathFragments.isEmpty()) {
            debug("Adding selected ProjectPathFragments: {}", pathFragments.stream().map(ContextFragment.ProjectPathFragment::shortDescription).collect(Collectors.joining(", ")));
            contextManager.editFiles(pathFragments);
        }

        // Process SkeletonFragments
        var skeletonFragments = groupedByType.getOrDefault(ContextFragment.FragmentType.SKELETON, List.of()).stream()
                .map(ContextFragment.SkeletonFragment.class::cast)
                .toList();

        if (!skeletonFragments.isEmpty()) {
            // For CLASS_SKELETON, collect all target FQNs.
            // For FILE_SKELETONS, collect all target file paths.
            // Create one fragment per type.
            List<String> classTargetFqns = skeletonFragments.stream()
                    .filter(sf -> sf.getSummaryType() == ContextFragment.SummaryType.CLASS_SKELETON)
                    .flatMap(sf -> sf.getTargetIdentifiers().stream())
                    .distinct()
                    .toList();

            List<String> fileTargetPaths = skeletonFragments.stream()
                    .filter(sf -> sf.getSummaryType() == ContextFragment.SummaryType.FILE_SKELETONS)
                    .flatMap(sf -> sf.getTargetIdentifiers().stream())
                    .distinct()
                    .toList();

            if (!classTargetFqns.isEmpty()) {
                debug("Adding combined SkeletonFragment for classes: {}", classTargetFqns);
                contextManager.addVirtualFragment(new ContextFragment.SkeletonFragment(contextManager, classTargetFqns, ContextFragment.SummaryType.CLASS_SKELETON));
            }
            if (!fileTargetPaths.isEmpty()) {
                debug("Adding combined SkeletonFragment for files: {}", fileTargetPaths);
                contextManager.addVirtualFragment(new ContextFragment.SkeletonFragment(contextManager, fileTargetPaths, ContextFragment.SummaryType.FILE_SKELETONS));
            }
        }

        // Handle any unexpected fragment types (should not happen with current logic)
        groupedByType.keySet().stream()
                .filter(type -> type != ContextFragment.FragmentType.PROJECT_PATH && type != ContextFragment.FragmentType.SKELETON)
                .findFirst()
                .ifPresent(unexpectedType -> {
                    throw new AssertionError("Unexpected fragment type selected: " + unexpectedType + " in " + selected);
                });
    }

    /**
     * Determines the best initial context based on project size and token budgets,
     * potentially using LLM inference, and returns recommended context fragments.
     * <p>
     * potentially using LLM inference, and returns recommended context fragments.
     * @param allowSkipPruning If true, allows the agent to skip LLM-based pruning if the initial context fits `skipPruningBudget`.
     * @return A RecommendationResult containing success status, fragments, and reasoning.
     */
    public RecommendationResult getRecommendations(boolean allowSkipPruning) throws InterruptedException {
        Collection<ChatMessage> workspaceRepresentation = deepScan
                                                          ? contextManager.getWorkspaceContentsMessages()
                                                          : contextManager.getWorkspaceSummaryMessages();
        var allFiles = contextManager.getProject().getAllFiles().stream().sorted().toList();

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
                .filter(ProjectFile.class::isInstance) // Ensure it's a ProjectFile
                .map(ProjectFile.class::cast)       // Cast to ProjectFile
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
                                                      boolean allowSkipPruning) throws InterruptedException {
        Map<CodeUnit, String> rawSummaries;
        var ctx = contextManager.liveContext();
        var codeInWorkspace = ctx.allFragments().flatMap(f -> f.sources().stream()).findAny().isPresent();

        if (codeInWorkspace && !deepScan) {
            // If the workspace isn't empty, use pagerank candidates for Quick context
            var ac = contextManager.liveContext().buildAutoContext(50);
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
            rawSummaries = getProjectSummaries(filesToConsider);
        }

        int summaryTokens = Messages.getApproximateTokens(String.join("\n", rawSummaries.values()));
        debug("Total tokens for {} summaries (from {} files): {}", rawSummaries.size(), filesToConsider.size(), summaryTokens);

        boolean withinLimit = deepScan || rawSummaries.size() <= QUICK_TOPK;
        if (allowSkipPruning && summaryTokens <= skipPruningBudget && withinLimit) {
            var fragments = skeletonPerSummary(contextManager, rawSummaries);
            return new RecommendationResult(true, fragments, "Using all summaries within budget and limits (skip pruning).");
        }

        // Rule 2: Ask LLM to pick relevant summaries/files if all summaries fit the Pruning budget
        if (summaryTokens <= budgetPruning) {
            var llmRecommendation = askLlmToRecommendContext(List.of(), rawSummaries, Map.of(), workspaceRepresentation);
            return createResult(llmRecommendation);
        }

        // If summaries are too large even for pruning, signal failure for this branch
        String reason = "Summaries too large for LLM pruning budget.";
        logGiveUp("summaries (too large for pruning budget)");
        return new RecommendationResult(false, List.of(), reason);
    }

    private @NotNull RecommendationResult createResult(@NotNull LlmRecommendation llmRecommendation) {
        var recommendedFiles = llmRecommendation.recommendedFiles();
        var recommendedClasses = llmRecommendation.recommendedClasses();
        var reasoning = llmRecommendation.reasoning();

        // We filter out duplicates in different ways depending on the request type:
        // for Deep Scan, we filter out only exact matches, so if we have a full file and LLM recommends
        // summary, we allow it, and vice versa; for Quick, we filter out anything that's already in the workspace in any form
        if (deepScan) {
            recommendedFiles = recommendedFiles.stream()
                    .filter(f -> !isFileInWorkspace(f))
                    .toList();
            recommendedClasses = recommendedClasses.stream()
                    .filter(c -> !isClassInWorkspace(c))
                    .toList();
        }

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
        var skeletonFragments = skeletonPerSummary(contextManager, recommendedSummaries);
        var pathFragments = recommendedFiles.stream()
                .map(f -> (ContextFragment) new ContextFragment.ProjectPathFragment(f, contextManager))
                .toList();
        var combinedStream = Stream.concat(skeletonFragments.stream(), pathFragments.stream());
        // deduplicate for Quick context
        if (!deepScan) {
            var project = contextManager.getProject();
            var existingFiles = contextManager.topContext().allFragments()
                    .flatMap(f -> f.files().stream()) 
                    .collect(Collectors.toSet());
            combinedStream = combinedStream
                    .filter(f -> {
                        Set<ProjectFile> fragmentFiles = f.files();
                        return fragmentFiles.stream().noneMatch(existingFiles::contains);
                    });
        }
        var combinedFragments = combinedStream.toList();

        return new RecommendationResult(true, combinedFragments, reasoning);
    }

    /**
     * one SkeletonFragment per summary so ArchitectAgent can easily ask user which ones to include
     */
    private static @NotNull List<ContextFragment> skeletonPerSummary(IContextManager contextManager, Map<CodeUnit, String> relevantSummaries) {
        return relevantSummaries.entrySet().stream()
                .map(entry -> (ContextFragment) new ContextFragment.SkeletonFragment(contextManager, List.of(entry.getKey().fqName()), ContextFragment.SummaryType.CLASS_SKELETON))
                .toList();
    }

    /**
     * Collect a structural “skeleton” for every top-level class whose source file
     * is in the supplied collection.
     * <p>
     * We:
     * 1. Grab the CodeUnits belonging to those files (via the analyzer).
     * 2. Collapse inner-classes so we only consider the outermost declaration.
     * 3. Ask the analyzer for a skeleton of each class and keep the non-empty ones.
     */
    private Map<CodeUnit, String> getProjectSummaries(Collection<ProjectFile> files) {
        return files.stream().parallel()
                .flatMap(f -> analyzer.getSkeletons(f).entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1));
    }

    private @NotNull Map<CodeUnit, @NotNull String> getSummaries(Collection<CodeUnit> classes, boolean parallel) {
        var coalescedClasses = AnalyzerUtil.coalesceInnerClasses(Set.copyOf(classes));
        debug("Found {} classes", coalescedClasses.size());

        // grab skeletons in parallel
        var stream = coalescedClasses.stream();
        if (parallel) {
            stream = stream.parallel();
        }
        return stream
                .map(cu -> {
                    var skeletonOpt = analyzer.getSkeleton(cu.fqName());
                    String skeleton = skeletonOpt.isPresent() ? skeletonOpt.get() : "";
                    return Map.entry(cu, skeleton);
                })
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private boolean isFileInWorkspace(ProjectFile file) {
        return contextManager.getEditableFiles().contains(file) ||
                contextManager.getReadonlyFiles().contains(file);
    }

    private boolean isClassInWorkspace(CodeUnit cls) {
        return contextManager.topContext().allFragments()
                .anyMatch(f -> {
                    if (f.getType() == ContextFragment.FragmentType.SKELETON) {
                        var sf = (ContextFragment.SkeletonFragment) f;
                        return sf.getTargetIdentifiers().contains(cls.fqName()) && // Check if class FQN is among targets
                               sf.getSummaryType() == ContextFragment.SummaryType.CLASS_SKELETON; // Ensure it's a class summary
                    }
                    return false;
                });
    }

    private LlmRecommendation askLlmToRecommendContext(List<String> filenames,
                                                       @NotNull Map<CodeUnit, String> summaries,
                                                         @NotNull Map<ProjectFile, String> contentsMap,
                                                         @NotNull Collection<ChatMessage> workspaceRepresentation) throws InterruptedException
    {
        if (deepScan) {
            return askLlmDeepRecommendContext(filenames, summaries, contentsMap, workspaceRepresentation);
        } else {
            return askLlmQuickRecommendContext(filenames, summaries, contentsMap, workspaceRepresentation);
        }
    }

    // --- Deep Scan (Tool-based) Recommendation ---

    private LlmRecommendation askLlmDeepRecommendContext(List<String> filenames,
                                                         @NotNull Map<CodeUnit, String> summaries,
                                                         @NotNull Map<ProjectFile, String> contentsMap,
                                                         @NotNull Collection<ChatMessage> workspaceRepresentation) throws InterruptedException
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
        if (result.error() != null || result.chatResponse() == null || result.chatResponse().aiMessage() == null) {
            logger.warn("Error or empty response from LLM during context recommendation: {}. Returning empty.",
                        result.error() != null ? result.error().getMessage() : "Empty response");
            return LlmRecommendation.EMPTY;
        }
        var aiMessage = result.chatResponse().aiMessage();
        var toolRequests = aiMessage.toolExecutionRequests();
        debug("LLM ToolRequests: {}", toolRequests);
        // only one call is necessary but handle LLM making multiple calls
        for (var request : toolRequests) {
            contextManager.getToolRegistry().executeTool(contextTool, request);
        }

        String reasoning = aiMessage.text() != null ? aiMessage.text().strip() : "LLM provided recommendations via tool call.";
        // Filter out files/classes already in workspace
        var projectFiles = contextTool.getRecommendedFiles().stream()
                .map(fname -> {
                    try {
                        return contextManager.toFile(fname);
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
                                                          @NotNull Map<CodeUnit, String> summaries,
                                                          @NotNull Map<ProjectFile, String> contentsMap,
                                                          @NotNull Collection<ChatMessage> workspaceRepresentation) throws InterruptedException
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
            var allFilesMap = contextManager.getProject().getAllFiles().stream()
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
     * @param inputType The type of input being provided (summaries, file contents, or file paths).
     * @param inputBlob A string containing the formatted input data (e.g., XML-like structure of summaries or file contents).
     * @param topK      Optional limit on the number of items to return.
     * @param workspaceRepresentation Messages representing the current workspace state.
     * @return A list of strings representing the identifiers (FQNs or paths) recommended by the LLM.
     */
    private List<String> simpleRecommendItems(ContextInputType inputType,
                                              String inputBlob,
                                              Integer topK,
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

        if (result.error() != null || result.chatResponse() == null || result.chatResponse().aiMessage() == null) {
            logger.warn("Error or empty response from LLM during quick %s selection: {}. Returning empty.",
                        inputType.itemTypePlural, result.error() != null ? result.error().getMessage() : "Empty response");
            return List.of();
        }

        var responseText = result.chatResponse().aiMessage().text();
        if (responseText == null || responseText.isBlank()) {
            logger.warn("Empty text response from LLM during quick %s selection. Returning empty.", inputType.itemTypePlural);
            return List.of();
        }

        var responseLines = responseText.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
        debug("LLM simple response lines ({}): {}", inputType.itemTypePlural, responseLines);
        return responseLines;
    }

    // --- Logic branch for using full file contents (when analyzer is not available or summaries failed) ---

    private RecommendationResult executeWithFileContents(List<ProjectFile> filesToConsider,
                                                         Collection<ChatMessage> workspaceRepresentation,
                                                         boolean allowSkipPruning) throws InterruptedException {
        // If the workspace isn't empty and we have no analyzer, don't suggest adding whole files.
        // Allow proceeding if analyzer *is* present but summary step failed (e.g., too large).
        if (analyzer.isEmpty() && (!contextManager.getEditableFiles().isEmpty() || !contextManager.getReadonlyFiles().isEmpty())) {
            debug("Non-empty context and no analyzer present, skipping file content suggestions");
            return new RecommendationResult(true, List.of(), "Skipping file content suggestions for non-empty context without analyzer.");
        }

        var contentsMap = readFileContents(filesToConsider);
        int contentTokens = Messages.getApproximateTokens(String.join("\n", contentsMap.values()));
        debug("Total tokens for {} files' content: {}", contentsMap.size(), contentTokens);

        // Rule 1: Use all available files if content fits the smallest budget and meet the limit (if not deepScan)
        boolean withinLimit = deepScan || filesToConsider.size() <= QUICK_TOPK; // Use QUICK_TOPK here
        if (allowSkipPruning && contentTokens <= skipPruningBudget && withinLimit) {
            var fragments = filesToConsider.stream()
                    .map(f -> (ContextFragment) new ContextFragment.ProjectPathFragment(f, contextManager))
                    .toList();
            // Need to filter here too for quick mode if skipping LLM
            if (!deepScan) {
                 var project = contextManager.getProject();
                 var existingFiles = contextManager.topContext().allFragments()
                         .flatMap(f -> f.files().stream()) 
                         .collect(Collectors.toSet());
                 fragments = fragments.stream()
                         .filter(frag -> {
                             // Ensure frag is ProjectPathFragment and then get its file
                             if (frag.getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                                 var ppf = (ContextFragment.ProjectPathFragment) frag;
                                 return !existingFiles.contains(ppf.file());
                             }
                             return true; // Or handle other fragment types if necessary
                         })
                         .toList();
            }
            return new RecommendationResult(true, fragments, "Using all file contents within budget and limits (skip pruning).");
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

    private RecommendationResult executeWithFilenamesOnly(List<ProjectFile> allFiles, Collection<ChatMessage> workspaceRepresentation) throws InterruptedException {
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
