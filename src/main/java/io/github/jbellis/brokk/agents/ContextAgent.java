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
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.util.Messages;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.function.Function;
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

    public ContextAgent(ContextManager contextManager, StreamingChatLanguageModel model, String goal, boolean deepScan) throws InterruptedException {
        this.contextManager = contextManager;
        this.llm = contextManager.getLlm(model, "ContextAgent: " + goal); // Coder for LLM interactions
        this.goal = goal;
        this.io = contextManager.getIo();
        this.analyzer = contextManager.getAnalyzer();
        this.deepScan = deepScan;

        int maxInputTokens = contextManager.getModels().getMaxInputTokens(model);
        this.skipPruningBudget = min(32_000, maxInputTokens / 4);
        this.finalBudget = maxInputTokens / 2;
        this.budgetPruning = (int) (maxInputTokens * 0.9);

        logger.debug("ContextAgent initialized. Budgets: SkipPruning={}, Final={}, Pruning={}", skipPruningBudget, finalBudget, budgetPruning);
    }

    /**
     * Result record for context recommendation attempts.
     */
    public record RecommendationResult(boolean success, List<ContextFragment> fragments, String reasoning) {
        static final RecommendationResult FAILED = new RecommendationResult(false, List.of(), "Failed to determine context.");
    }

    /**
     * Result record for the LLM tool call, holding recommended files, class names, and the LLM's reasoning.
     */
    private record LlmRecommendation(List<String> recommendedFiles, List<String> recommendedClasses, String reasoning) {
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

        // Execute without a specific limit on recommendations
        var recommendationResult = getRecommendations();
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
        logger.debug("Total tokens for recommended context: {}", totalTokens);

        if (totalTokens > finalBudget) {
            logger.warn("Recommended context ({} tokens) exceeds final budget ({} tokens). Skipping context addition.", totalTokens, finalBudget);
            logGiveUp("recommended context (exceeded final budget)");
            // Optionally provide reasoning about exceeding budget
            io.llmOutput("\nWarning: Recommended context exceeded the final budget and could not be added automatically.", ChatMessageType.CUSTOM);
            return false; // Indicate failure due to budget
        }

        logger.debug("Recommended context fits within final budget.");
        addSelectedFragments(recommendationResult.fragments);

        return true;
    }

    /**
     * Calculates the approximate token count for a list of ContextFragments.
     */
    private int calculateFragmentTokens(List<ContextFragment> fragments) {
        int totalTokens = 0;

        for (var fragment : fragments) {
            if (fragment instanceof ContextFragment.ProjectPathFragment pathFragment) {
                var file = pathFragment.file();
                String content = null;
                try {
                    content = file.read();
                } catch (IOException e) {
                    logger.debug(e);
                }
                totalTokens += Messages.getApproximateTokens(content);
            } else if (fragment instanceof ContextFragment.SkeletonFragment skeletonFragment) {
                String skeletonsText = String.join("\n", skeletonFragment.skeletons().values());
                totalTokens += Messages.getApproximateTokens(skeletonsText);
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
        var grouped = selected.stream().collect(Collectors.groupingBy(ContextFragment::getClass));

        // Process ProjectPathFragments
        var pathFragments = grouped.getOrDefault(ContextFragment.ProjectPathFragment.class, List.of()).stream()
                .map(ContextFragment.ProjectPathFragment.class::cast)
                .toList();
        if (!pathFragments.isEmpty()) {
            logger.debug("Adding selected ProjectPathFragments: {}", pathFragments.stream().map(ContextFragment.ProjectPathFragment::shortDescription).collect(Collectors.joining(", ")));
            contextManager.editFiles(pathFragments);
        }

        // Process SkeletonFragments
        var skeletonFragments = grouped.getOrDefault(ContextFragment.SkeletonFragment.class, List.of()).stream()
                .map(ContextFragment.SkeletonFragment.class::cast)
                .toList();
        if (!skeletonFragments.isEmpty()) {
            // Merge multiple SkeletonFragments into one before adding
            var combinedSkeletons = skeletonFragments.stream()
                    .flatMap(sf -> sf.skeletons().entrySet().stream())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1)); // Keep first on duplicate key
            if (!combinedSkeletons.isEmpty()) {
                logger.debug("Adding combined SkeletonFragment for classes: {}", combinedSkeletons.keySet().stream().map(CodeUnit::identifier).collect(Collectors.joining(", ")));
                contextManager.addVirtualFragment(new ContextFragment.SkeletonFragment(combinedSkeletons));
            }
        }

        // Handle any unexpected fragment types (should not happen with current logic)
        grouped.keySet().stream()
                .filter(cls -> cls != ContextFragment.ProjectPathFragment.class && cls != ContextFragment.SkeletonFragment.class)
                .findFirst()
                .ifPresent(unexpectedClass -> {
                    throw new AssertionError("Unexpected fragment type selected: " + unexpectedClass.getName() + " in " + selected);
                });
    }

    /**
     * Determines the best initial context based on project size and token budgets,
     * potentially using LLM inference, and returns recommended context fragments.
     * <p>
     * potentially using LLM inference, and returns recommended context fragments.
     *
     * @return A RecommendationResult containing success status, fragments, and reasoning.
     */
    public RecommendationResult getRecommendations() throws InterruptedException {
        var workspaceRepresentation = getWorkspaceRepresentation();
        var allFiles = contextManager.getRepo().getTrackedFiles().stream().sorted().toList();

        // Try summaries first if analyzer is available
        if (!analyzer.isEmpty()) {
            var summaryResult = executeWithSummaries(allFiles, workspaceRepresentation);
            if (summaryResult.success) {
                return summaryResult;
            }
            // If summaries failed (e.g., too large even for pruning), fall through to filename-based pruning
        } else {
            // If no analyzer, try full file contents directly
            var contentResult = executeWithFileContents(allFiles, workspaceRepresentation);
            if (contentResult.success) {
                return contentResult;
            }
            // If contents failed, fall through to filename-based pruning
        }

        // Fallback 1: Prune based on filenames only
        logger.debug("Falling back to filename-based pruning.");
        var filenameResult = executeWithFilenamesOnly(allFiles, workspaceRepresentation);
        if (!filenameResult.success) {
            logGiveUp("filename list (too large for LLM pruning)");
            return new RecommendationResult(false, List.of(), filenameResult.reasoning()); // Return failure with reasoning
        }

        var prunedFiles = filenameResult.fragments.stream()
                .map(ContextFragment.ProjectPathFragment.class::cast)
                .map(ContextFragment.ProjectPathFragment::file)
                .toList();

        if (prunedFiles.isEmpty()) {
            logger.debug("Filename pruning resulted in an empty list. No context found.");
            // Return success=true, but empty list, include reasoning from filename pruning step
            return new RecommendationResult(true, List.of(), filenameResult.reasoning());
        }
        logger.debug("LLM pruned to {} files based on names. Reasoning: {}", prunedFiles.size(), filenameResult.reasoning());


        // Fallback 2: After pruning filenames, try again with summaries or contents using the pruned list
        if (!analyzer.isEmpty()) {
            logger.debug("Attempting context recommendation with summaries of pruned files.");
            var summaryResult = executeWithSummaries(prunedFiles, workspaceRepresentation);
            // Return the result directly (success or failure, with fragments and reasoning)
            if (!summaryResult.success) {
                logGiveUp("summaries of pruned files");
            }
            return summaryResult;
        } else {
            logger.debug("Attempting context recommendation with contents of pruned files.");
            var contentResult = executeWithFileContents(prunedFiles, workspaceRepresentation);
            // Return the result directly (success or failure, with fragments and reasoning)
            if (!contentResult.success) {
                logGiveUp("contents of pruned files");
            }
            return contentResult;
        }
    }

    private Object getWorkspaceRepresentation() {
        if (deepScan) {
            // Return full messages if requested
            return contextManager.getWorkspaceContentsMessages();
        } else {
            // Return summary string otherwise
            return CodePrompts.formatWorkspaceSummary(contextManager, false);
        }
    }


    // --- Logic branch for using class summaries ---

    private RecommendationResult executeWithSummaries(List<ProjectFile> filesToConsider, Object workspaceRepresentation) throws InterruptedException {
        Map<CodeUnit, String> rawSummaries;
        // If the workspace isn't empty, use pagerank candidates for initial summaries
        if ((!contextManager.getEditableFiles().isEmpty() || !contextManager.getReadonlyFiles().isEmpty())
                && analyzer.isCpg()) {
            var ac = contextManager.topContext().buildAutoContext(100);
            logger.debug("Non-empty context, using pagerank candidates {} for ContextAgent",
                         ac.fragment().skeletons().keySet().stream().map(CodeUnit::identifier).collect(Collectors.joining(",")));
            rawSummaries = ac.isEmpty() ? Map.of() : ac.fragment().skeletons();
        } else {
            // If workspace is empty, get summaries for the provided files (all or pruned)
            rawSummaries = getProjectSummaries(filesToConsider);
        }

        int summaryTokens = Messages.getApproximateTokens(String.join("\n", rawSummaries.values()));
        logger.debug("Total tokens for {} summaries (from {} files): {}", rawSummaries.size(), filesToConsider.size(), summaryTokens);

        // Rule 1: Use all available summaries if they fit the smallest budget and meet the limit (if not deepScan)
        boolean withinLimit = deepScan || rawSummaries.size() <= 10;
        if (summaryTokens <= skipPruningBudget && withinLimit) {
            var fragments = skeletonPerSummary(rawSummaries);
            return new RecommendationResult(true, fragments, "Using all summaries within budget and limits.");
        }

        // Rule 2: Ask LLM to pick relevant summaries/files if all summaries fit the Pruning budget
        if (summaryTokens <= budgetPruning) {
            var llmRecommendation = askLlmToRecommendContext(filesToConsider, rawSummaries, null, workspaceRepresentation);

            // Map recommended class names back to CodeUnit -> Summary map
            var recommendedSummaries = rawSummaries.entrySet().stream()
                    .filter(entry -> llmRecommendation.recommendedClasses.contains(entry.getKey().fqName()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            // Map recommended file paths back to ProjectFile list
            var filesMap = filesToConsider.stream().collect(Collectors.toMap(ProjectFile::toString, Function.identity()));
            var recommendedFiles = llmRecommendation.recommendedFiles.stream()
                    .map(filesMap::get)
                    .filter(Objects::nonNull)
                    .toList();

            // Calculate combined token size
            int recommendedSummaryTokens = Messages.getApproximateTokens(String.join("\n", recommendedSummaries.values()));
            var recommendedContentsMap = readFileContents(recommendedFiles);
            int recommendedContentTokens = Messages.getApproximateTokens(String.join("\n", recommendedContentsMap.values()));
            int totalRecommendedTokens = recommendedSummaryTokens + recommendedContentTokens;

            logger.debug("LLM recommended {} classes ({} tokens) and {} files ({} tokens). Total: {} tokens",
                         recommendedSummaries.size(), recommendedSummaryTokens,
                         recommendedFiles.size(), recommendedContentTokens, totalRecommendedTokens);

            var skeletonFragments = skeletonPerSummary(recommendedSummaries);
            var pathFragments = recommendedFiles.stream()
                    .map(f -> (ContextFragment) new ContextFragment.ProjectPathFragment(f))
                    .toList();
            var combinedFragments = Stream.concat(skeletonFragments.stream(), pathFragments.stream()).toList();
            return new RecommendationResult(true, combinedFragments, llmRecommendation.reasoning);
        }

        // If summaries are too large even for pruning, signal failure for this branch
        String reason = "Summaries too large for LLM pruning budget.";
        logGiveUp("summaries (too large for pruning budget)");
        return new RecommendationResult(false, List.of(), reason);
    }

    /**
     * one SkeletonFragment per summary so ArchitectAgent can easily ask user which ones to include
     */
    private static @NotNull List<ContextFragment> skeletonPerSummary(Map<CodeUnit, String> relevantSummaries) {
        return relevantSummaries.entrySet().stream()
                .map(entry -> (ContextFragment) new ContextFragment.SkeletonFragment(Map.of(entry.getKey(), entry.getValue())))
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
        // turn file list into class list
        var projectClasses = files.stream().parallel()
                .flatMap(f -> analyzer.getClassesInFile(f).stream())
                .collect(Collectors.toSet());
        var coalescedClasses = AnalyzerUtil.coalesceInnerClasses(projectClasses);
        logger.debug("Found {} top-level classes in {} files.",
                     coalescedClasses.size(), files.size());

        // grab skeletons in parallel
        return coalescedClasses.stream().parallel()
                .map(cu -> {
                    var skeletonOpt = analyzer.getSkeleton(cu.fqName());
                    String skeleton = skeletonOpt.isDefined() ? skeletonOpt.get() : "";
                    return Map.entry(cu, skeleton);
                })
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Asks the LLM to recommend relevant context (files and/or classes) using a tool call.
     *
     * @param filesToConsider         List of candidate files.
     * @param summaries               Optional map of CodeUnit to summary string.
     * @param contentsMap             Optional map of ProjectFile to content string.
     * @param workspaceRepresentation String summary or List<ChatMessage> of workspace contents.
     * @return LlmRecommendation containing lists of recommended file paths and class FQNs.
     */
    private LlmRecommendation askLlmToRecommendContext(List<ProjectFile> filesToConsider,
                                                       @Nullable Map<CodeUnit, String> summaries,
                                                       @Nullable Map<ProjectFile, String> contentsMap,
                                                       Object workspaceRepresentation) throws InterruptedException
    {
        var contextTool = new ContextRecommendationTool();
        // Generate specifications from the annotated tool instance
        var toolSpecs = ToolSpecifications.toolSpecificationsFrom(contextTool);
        assert toolSpecs.size() == 1 : "Expected exactly one tool specification from ContextRecommendationTool";

        var deepPrompt = """
                         You are an assistant that identifies relevant code context based on a goal and available information.
                         You are given a goal, the current workspace contents (if any), and either a list of class summaries, or a list of file paths and contents.
                         Analyze the provided information and determine which items are most relevant to achieving the goal.
                         You MUST call the `recommendContext` tool to provide your recommendations.
                         
                         Populate the `filesToAdd` argument with the full paths of files that will need to be edited as part of the goal,
                         or whose implementation details are necessary. Put these files in `filesToAdd` (even if you are only shown a summary.
                         
                         Populate the `classesToSummarize` argument with the fully-qualified names of classes whose APIs will be used.
                         
                         Either of both of `filesToAdd` and `classesToSummarize` may be empty.
                         """;
        var quikPrompt = """
                         You are an assistant that identifies relevant code context based on a goal and available information.
                         You are given a goal, the current workspace contents (if any), and either a list of class summaries, or a list of file paths and contents.
                         Analyze the provided information and determine which items are most relevant to achieving the goal.
                         You MUST call the `recommendContext` tool to provide your recommendations.
                         
                         If you are given full file contents, populate the `filesToAdd` argument with the full paths of 
                         10 the most relevant files.
                         
                         If you are given class summaries, populate the `classesToSummarize` argument with the 
                         fully-qualified names of the 10 most relevant classes.
                         
                         Exactly one of `recommendContext` or `classesToSummarize` should be non-empty.
                         """;

        var finalSystemMessage = new SystemMessage(deepScan ? deepPrompt : quikPrompt);
        var userMessageText = new StringBuilder("<goal>\n%s\n</goal>\n\n".formatted(goal));
        // Add workspace representation
        if (workspaceRepresentation instanceof String workspaceSummary && !workspaceSummary.isBlank()) {
            userMessageText.append("<workspace_summary>\n%s\n</workspace_summary>\n\n".formatted(workspaceSummary));
        }

        // Add summaries if available
        if (summaries != null && !summaries.isEmpty()) {
            var summariesText = summaries.entrySet().stream()
                    .map(entry -> {
                        var cn = entry.getKey();
                        var body = entry.getValue();
                        return deepScan
                               ? "<class fqcn='%s' file='%s'>\n%s\n</class>".formatted(cn.fqName(), analyzer.getFileFor(cn.fqName()), body)
                               // avoid confusing quick model by giving it the filename
                               : "<class fqcn='%s'>\n%s\n</class>".formatted(cn.fqName(), body);
                    })
                    .collect(Collectors.joining("\n\n"));
            userMessageText.append("<available_summaries>\n%s\n</available_summaries>\n\n".formatted(summariesText));
        }

        // Add file contents or paths if available
        if (contentsMap != null && !contentsMap.isEmpty()) {
            var filesText = contentsMap.entrySet().stream()
                    .map(entry -> "<file path='%s'>\n%s\n</file>".formatted(entry.getKey().toString(), entry.getValue()))
                    .collect(Collectors.joining("\n\n"));
            userMessageText.append("<available_files_content>\n%s\n</available_files_content>\n\n".formatted(filesText));
        } else if (contentsMap == null && summaries == null) { // Only provide paths if contents/summaries aren't already there
            var filesText = filesToConsider.stream()
                    .map(ProjectFile::toString)
                    .collect(Collectors.joining("\n"));
            userMessageText.append("<available_file_paths>\n%s\n</available_file_paths>\n\n".formatted(filesText));
        }

        userMessageText.append("Call the `recommendContext` tool with the most relevant items based on the goal and available context.");

        // Construct final message list
        List<ChatMessage> messages;
        if (workspaceRepresentation instanceof List workspaceMessages) {
            @SuppressWarnings("unchecked") // Checked by instanceof
            List<ChatMessage> castedMessages = (List<ChatMessage>) workspaceMessages;
            messages = Stream.concat(Stream.of(finalSystemMessage),
                                     Stream.concat(castedMessages.stream(), Stream.of(new UserMessage(userMessageText.toString()))))
                    .toList();
        } else {
            messages = List.of(finalSystemMessage, new UserMessage(userMessageText.toString()));
        }

        int promptTokens = messages.stream().mapToInt(m -> Messages.getApproximateTokens(Messages.getText(m))).sum();
        logger.debug("Invoking LLM to recommend context via tool call (prompt size ~{} tokens)", promptTokens);

        // *** Execute LLM call with required tool ***
        var result = llm.sendRequest(messages, toolSpecs, ToolChoice.REQUIRED, false);
        if (result.error() != null || result.chatResponse() == null || result.chatResponse().aiMessage() == null) {
            logger.warn("Error or empty response from LLM during context recommendation: {}. Returning empty.",
                        result.error() != null ? result.error().getMessage() : "Empty response");
            return LlmRecommendation.EMPTY;
        }
        var aiMessage = result.chatResponse().aiMessage();
        var toolRequests = aiMessage.toolExecutionRequests();
        logger.trace(toolRequests);
        // only one call is necessary but handle LLM making multiple calls
        for (var request : toolRequests) {
            contextManager.getToolRegistry().executeTool(contextTool, request);
        }

        String reasoning = aiMessage.text() != null ? aiMessage.text().strip() : "LLM provided recommendations via tool call.";
        return new LlmRecommendation(contextTool.getRecommendedFiles(),
                                     contextTool.getRecommendedClasses(),
                                     reasoning);
    }

// --- Logic branch for using full file contents (when analyzer is not available or summaries failed) ---

    private RecommendationResult executeWithFileContents(List<ProjectFile> filesToConsider, Object workspaceRepresentation) throws InterruptedException {
        // If the workspace isn't empty and we have no analyzer, don't suggest adding whole files.
        // Allow proceeding if analyzer *is* present but summary step failed (e.g., too large).
        if (analyzer.isEmpty() && (!contextManager.getEditableFiles().isEmpty() || !contextManager.getReadonlyFiles().isEmpty())) {
            logger.debug("Non-empty context and no analyzer present, skipping file content suggestions");
            return new RecommendationResult(true, List.of(), "Skipping file content suggestions for non-empty context without analyzer.");
        }

        var contentsMap = readFileContents(filesToConsider);
        int contentTokens = Messages.getApproximateTokens(String.join("\n", contentsMap.values()));
        logger.debug("Total tokens for {} files' content: {}", contentsMap.size(), contentTokens);

        // Rule 1: Use all available files if content fits the smallest budget and meet the limit (if not deepScan)
        boolean withinLimit = deepScan || filesToConsider.size() <= 10;
        if (contentTokens <= skipPruningBudget && withinLimit) {
            var fragments = filesToConsider.stream()
                    .map(f -> (ContextFragment) new ContextFragment.ProjectPathFragment(f))
                    .toList();
            return new RecommendationResult(true, fragments, "Using all file contents within budget and limits.");
        }

        // Rule 2: Ask LLM to pick relevant files if all content fits the Pruning budget
        if (contentTokens <= budgetPruning) {
            // Call the unified recommender, expecting only files back in this path
            var llmRecommendation = askLlmToRecommendContext(filesToConsider, null, contentsMap, workspaceRepresentation);

            if (!llmRecommendation.recommendedClasses.isEmpty()) {
                // This shouldn't happen if we didn't provide summaries
                logger.warn("LLM recommended classes ({}) unexpectedly during file content analysis.", llmRecommendation.recommendedClasses);
            }

            // Map recommended file paths back to ProjectFile list
            var filesMap = filesToConsider.stream().collect(Collectors.toMap(ProjectFile::toString, Function.identity()));
            var recommendedFiles = llmRecommendation.recommendedFiles.stream()
                    .map(filesMap::get)
                    .filter(Objects::nonNull)
                    .toList();


            var recommendedContentsMap = readFileContents(recommendedFiles);
            int recommendedContentTokens = Messages.getApproximateTokens(String.join("\n", recommendedContentsMap.values()));

            logger.debug("LLM recommended {} files ({} tokens). Total: {} tokens",
                         recommendedFiles.size(), recommendedContentTokens, recommendedContentTokens);

            var fragments = recommendedFiles.stream()
                    .map(f -> (ContextFragment) new ContextFragment.ProjectPathFragment(f))
                    .toList();
            return new RecommendationResult(true, fragments, llmRecommendation.reasoning);
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

    private RecommendationResult executeWithFilenamesOnly(List<ProjectFile> allFiles, Object workspaceRepresentation) throws InterruptedException {
        var filenameString = getFilenameString(allFiles);
        int filenameTokens = Messages.getApproximateTokens(filenameString);
        logger.debug("Total tokens for filename list: {}", filenameTokens);

        if (filenameTokens > budgetPruning) {
            // Too large even for the LLM to prune based on names
            return new RecommendationResult(false, List.of(), "Filename list too large for LLM pruning budget.");
        }

        // Ask LLM to recommend files based *only* on paths
        var llmRecommendation = askLlmToRecommendContext(allFiles, null, null, workspaceRepresentation);

        if (!llmRecommendation.recommendedClasses.isEmpty()) {
            logger.warn("LLM recommended classes ({}) unexpectedly during filename-only analysis.", llmRecommendation.recommendedClasses);
        }

        // Map recommended file paths back to ProjectFile list
        var filesMap = allFiles.stream().collect(Collectors.toMap(ProjectFile::toString, Function.identity()));
        var recommendedFiles = llmRecommendation.recommendedFiles.stream()
                .map(filesMap::get)
                .filter(Objects::nonNull)
                .toList();

        var fragments = recommendedFiles.stream()
                .map(f -> (ContextFragment) new ContextFragment.ProjectPathFragment(f))
                .toList();
        return new RecommendationResult(true, fragments, llmRecommendation.reasoning);
    }


// --- Helper methods ---

    private String getFilenameString(List<ProjectFile> files) {
        return files.stream()
                .map(ProjectFile::toString)
                .collect(Collectors.joining("\n"));
    }

    private void logGiveUp(String itemDescription) {
        logger.debug("Budget exceeded even for {}. Giving up on automatic context population.", itemDescription);
    }
}
