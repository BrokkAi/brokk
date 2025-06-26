package io.github.jbellis.brokk.agents;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.Llm.StreamingResult;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.prompts.QuickEditPrompts;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.LogDescription;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import static java.util.Objects.requireNonNull;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * Manages interactions with a Language Model (LLM) to generate and apply code modifications
 * based on user instructions. It handles parsing LLM responses, applying edits to files,
 * verifying changes through build/test commands, and managing the conversation history.
 * It supports both iterative coding tasks (potentially involving multiple LLM interactions
 * and build attempts) and quick, single-shot edits.
 */
public class CodeAgent {
    private static final Logger logger = LogManager.getLogger(CodeAgent.class);
    private static final int MAX_PARSE_ATTEMPTS = 3;
    private static final int MAX_APPLY_FAILURES_BEFORE_FALLBACK = 3;
    private static final int MAX_BUILD_FAILURES = 3;

    // private record ApplyResult(EditBlock.EditResult editResult, int updatedApplyFailures) {} // ApplyResult is no longer returned by applyBlocksAndHandleErrors

    private record LoopContext(
        ConversationState conversationState,
        WorkspaceState workspaceState,
        String userGoal
    ) {}

    private sealed interface Step permits Step.Continue, Step.Retry, Step.Fatal {
        record Continue(LoopContext loopContext, List<EditBlock.SearchReplaceBlock> newlyParsedBlocksInThisSegment) implements Step {}
        record Retry(LoopContext loopContext, String consoleLogMessage) implements Step {}
        record Fatal(TaskResult.StopDetails stopDetails) implements Step {}
    }

    private record ConversationState(
        List<ChatMessage> taskMessages,
        UserMessage nextRequest,
        List<ChatMessage> originalWorkspaceEditableMessages
    ) {}

    private record WorkspaceState(
        List<EditBlock.SearchReplaceBlock> pendingBlocks,
        int consecutiveParseFailures,
        int consecutiveApplyFailures,
        int blocksAppliedWithoutBuild,
        String lastBuildError,
        Set<ProjectFile> changedFiles,
        Map<ProjectFile, String> originalFileContents,
        int consecutiveBuildFailures // New field
    ) {}

    private final IContextManager contextManager;
    private final StreamingChatLanguageModel model;
    private final IConsoleIO io;

    public CodeAgent(IContextManager contextManager, StreamingChatLanguageModel model) {
        this(contextManager, model, contextManager.getIo());
    }

    public CodeAgent(IContextManager contextManager, StreamingChatLanguageModel model, IConsoleIO io) {
        this.contextManager = contextManager;
        this.model = model;
        this.io = io;
    }

    /**
     * Implementation of the LLM task that runs in a separate thread.
     * Uses the provided model for the initial request and potentially switches for fixes.
     *
     * @param userInput The user's goal/instructions.
     * @return A TaskResult containing the conversation history and original file contents
     */
    public TaskResult runTask(String userInput, boolean forArchitect) {
        var io = contextManager.getIo();
        // Create Coder instance with the user's input as the task description
        var coder = contextManager.getLlm(model, "Code: " + userInput, true);
        coder.setOutput(io);

        // Track original contents of files before any changes
        var changedFiles = new HashSet<ProjectFile>();

        // Keep original workspace editable messages at the start of the task
        var originalWorkspaceEditableMessages = CodePrompts.instance.getOriginalWorkspaceEditableMessages(contextManager);

        // Retry-loop state tracking
        int applyFailures = 0;
        int blocksAppliedWithoutBuild = 0;

        String buildError = "";
        var blocks = new ArrayList<EditBlock.SearchReplaceBlock>(); // This will be part of WorkspaceState
        Map<ProjectFile, String> originalFileContents = new HashMap<>();

        var msg = "Code Agent engaged: `%s...`".formatted(LogDescription.getShortDescription(userInput));
        io.systemOutput(msg);
        TaskResult.StopDetails stopDetails = null;

        var parser = contextManager.getParserForWorkspace();
        // We'll collect the conversation as ChatMessages to store in context history.
        var taskMessages = new ArrayList<ChatMessage>();
        UserMessage nextRequest = CodePrompts.instance.codeRequest(userInput.trim(),
                                                                   CodePrompts.reminderForModel(contextManager.getService(), model),
                                                                   parser);

        var conversationState = new ConversationState(taskMessages, nextRequest, originalWorkspaceEditableMessages);
        var workspaceState = new WorkspaceState(blocks, 0 /* initial parseFailures */, applyFailures, blocksAppliedWithoutBuild, buildError, changedFiles, originalFileContents, 0 /* initial consecutiveBuildFailures */);
        var loopContext = new LoopContext(conversationState, workspaceState, userInput);

        while (true) {
            // Variables needed across phase calls if not passed via Step results
            StreamingResult streamingResult = null; // Will be set before requestPhase

            // --- REQUEST PHASE --- (Implicitly, getting the streamingResult)
            try {
                // Inlined sendLlmRequest logic
                var allMessagesForLlm = CodePrompts.instance.collectCodeMessages(contextManager,
                                                                                 model,
                                                                                 parser,
                                                                                 loopContext.conversationState().taskMessages(),
                                                                                 loopContext.conversationState().nextRequest(),
                                                                                 loopContext.workspaceState().changedFiles(),
                                                                                 loopContext.conversationState().originalWorkspaceEditableMessages());
                streamingResult = coder.sendRequest(allMessagesForLlm, true);
            } catch (InterruptedException e) {
                logger.debug("CodeAgent interrupted during LLM request in runTask");
                Thread.currentThread().interrupt();
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
                break; // Break main loop
            }

            // Actual requestPhase handles the result of sendLlmRequest
            var requestOutcome = requestPhase(loopContext, streamingResult);
            switch (requestOutcome) {
                case Step.Continue(var newLoopContext, var _ignoredBlocks) -> loopContext = newLoopContext;
                case Step.Fatal(var details) -> stopDetails = details;
                // No Retry from requestPhase, but a default is needed for exhaustive switch
                default -> throw new IllegalStateException("requestPhase returned unexpected Step type: " + requestOutcome.getClass());
            }
            if (stopDetails != null) break; // If requestPhase was Fatal

            // --- PARSE PHASE ---
            var parseOutcome = parsePhase(loopContext, streamingResult.text(), streamingResult.isPartial(), parser);
            switch (parseOutcome) {
                case Step.Continue(var newLoopContext, var _ignoredBlocks) -> loopContext = newLoopContext;
                case Step.Retry(var newLoopContext, var consoleMsg) -> {
                    loopContext = newLoopContext;
                    logger.debug(requireNonNull(consoleMsg));
                    io.llmOutput(requireNonNull(consoleMsg), ChatMessageType.CUSTOM);
                    continue; // Restart main loop
                }
                case Step.Fatal(var details) -> stopDetails = details;
            }
            if (stopDetails != null) break;

            // --- APPLY PHASE ---
            var applyOutcome = applyPhase(loopContext, parser);
            switch (applyOutcome) {
                case Step.Continue(var newLoopContext, var _ignoredBlocks) -> loopContext = newLoopContext;
                case Step.Retry(var newLoopContext, var consoleMsg) -> {
                    loopContext = newLoopContext;
                    logger.debug(requireNonNull(consoleMsg));
                    io.llmOutput(requireNonNull(consoleMsg), ChatMessageType.CUSTOM);
                    continue; // Restart main loop
                }
                case Step.Fatal(var details) -> stopDetails = details;
            }
            if (stopDetails != null) break;

            // --- VERIFY PHASE ---
            var verifyOutcome = verifyPhase(loopContext);
            switch (verifyOutcome) {
                case Step.Continue(var newLoopContext, var _ignoredBlocks) -> loopContext = newLoopContext;
                case Step.Retry(var newLoopContext, var consoleMsg) -> {
                    loopContext = newLoopContext;
                    logger.debug(requireNonNull(consoleMsg));
                    io.llmOutput(requireNonNull(consoleMsg), ChatMessageType.CUSTOM);
                    continue; // Restart main loop
                }
                case Step.Fatal(var details) -> stopDetails = details;
            }
            if (stopDetails != null) break;

            // --- POST-VERIFY CHECKS (from old loop) ---
            if (verifyOutcome instanceof Step.Continue && loopContext.workspaceState().lastBuildError().isEmpty()) {
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
                break;
            }
            if (loopContext.workspaceState().pendingBlocks().isEmpty() && loopContext.workspaceState().blocksAppliedWithoutBuild() == 0) {
                io.systemOutput("No edits found or applied in response, and no changes since last build; ending task");
                if (!loopContext.workspaceState().lastBuildError().isEmpty()) {
                    stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.BUILD_ERROR, loopContext.workspaceState().lastBuildError());
                } else {
                    stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS, streamingResult.text());
                }
                break;
            }

            // Check for interruption before next iteration (if not continuing or breaking)
            if (Thread.currentThread().isInterrupted()) {
                logger.debug("CodeAgent interrupted at end of loop iteration.");
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
                break;
            }
        } // End of while(true)

        // Conclude task
        assert stopDetails != null; // Ensure a stop reason was set before exiting the loop
        // create the Result for history
        String finalActionDescription = (stopDetails.reason() == TaskResult.StopReason.SUCCESS)
                                        ? loopContext.userGoal()
                                        : loopContext.userGoal() + " [" + stopDetails.reason().name() + "]";
        // architect auto-compresses the task entry so let's give it the full history to work with, quickModel is cheap
        // Prepare messages for TaskEntry log: filter raw messages and keep S/R blocks verbatim
        var finalMessages = forArchitect ? List.copyOf(io.getLlmRawMessages()) : prepareMessagesForTaskEntryLog();
        return new TaskResult("Code: " + finalActionDescription,
                              new ContextFragment.TaskFragment(contextManager, finalMessages, loopContext.userGoal()),
                              loopContext.workspaceState().changedFiles(),
                              stopDetails);
    }

    private Step parsePhase(LoopContext currentLoopContext, String llmText, boolean isPartialResponse, EditBlockParser parser) {
        var currentConversationState = currentLoopContext.conversationState();
        var currentWorkspaceState = currentLoopContext.workspaceState();

        logger.debug("Got response (potentially partial if LLM connection was cut off)");

        var parseResult = parser.parseEditBlocks(llmText, contextManager.getRepo().getTrackedFiles());
        var newlyParsedBlocks = parseResult.blocks();

        UserMessage messageForRetry = null;
        String consoleLogForRetry = null;
        int updatedConsecutiveParseFailures = currentWorkspaceState.consecutiveParseFailures();

        if (parseResult.parseError() != null) {
            if (newlyParsedBlocks.isEmpty()) { // Pure parse failure
                updatedConsecutiveParseFailures++;
                if (updatedConsecutiveParseFailures > MAX_PARSE_ATTEMPTS) {
                    io.systemOutput("Parse error limit reached; ending task");
                    return new Step.Fatal(new TaskResult.StopDetails(TaskResult.StopReason.PARSE_ERROR, "Parse error limit reached after " + updatedConsecutiveParseFailures + " attempts."));
                }
                messageForRetry = new UserMessage(parseResult.parseError());
                consoleLogForRetry = "Failed to parse LLM response; retrying";
            } else { // Partial parse - some blocks parsed, then error
                updatedConsecutiveParseFailures = 0; // Reset on partial success
                // Prompt to continue from the last successfully parsed block of this segment.
                // These partially parsed blocks won't carry over to the next state for retry (pendingBlocks will be cleared).
                messageForRetry = new UserMessage(getContinueFromLastBlockPrompt(newlyParsedBlocks.getLast()));
                consoleLogForRetry = "Malformed or incomplete response after %d blocks parsed; asking LLM to continue/fix".formatted(newlyParsedBlocks.size());
            }

            var nextConversationState = new ConversationState(currentConversationState.taskMessages(), messageForRetry, currentConversationState.originalWorkspaceEditableMessages());
            var pendingBlocksForRetry = new ArrayList<>(currentWorkspaceState.pendingBlocks());
            if (!newlyParsedBlocks.isEmpty() && parseResult.parseError() != null) { // Partial parse with error
                pendingBlocksForRetry.addAll(newlyParsedBlocks);
            }
            // For pure parse failure (newlyParsedBlocks is empty), pendingBlocksForRetry remains as currentWorkspaceState.pendingBlocks()
            // Keep existing blocksAppliedWithoutBuild for Retry
            var nextWorkspaceState = new WorkspaceState(pendingBlocksForRetry, updatedConsecutiveParseFailures, currentWorkspaceState.consecutiveApplyFailures(), currentWorkspaceState.blocksAppliedWithoutBuild(), currentWorkspaceState.lastBuildError(), currentWorkspaceState.changedFiles(), currentWorkspaceState.originalFileContents(), currentWorkspaceState.consecutiveBuildFailures());
            return new Step.Retry(new LoopContext(nextConversationState, nextWorkspaceState, currentLoopContext.userGoal()), requireNonNull(consoleLogForRetry));
        } else { // No Parse Error
            updatedConsecutiveParseFailures = 0; // Reset on successful parse segment
            var mutablePendingBlocks = new ArrayList<>(currentWorkspaceState.pendingBlocks());
            mutablePendingBlocks.addAll(newlyParsedBlocks);

            if (isPartialResponse) {
                if (newlyParsedBlocks.isEmpty()) {
                    messageForRetry = new UserMessage("It looks like the response was cut off before you provided any code blocks. Please continue with your response.");
                    consoleLogForRetry = "LLM indicated response was partial before any blocks (no parse error); asking to continue";
                } else {
                    messageForRetry = new UserMessage(getContinueFromLastBlockPrompt(newlyParsedBlocks.getLast()));
                    consoleLogForRetry = "LLM indicated response was partial after %d clean blocks; asking to continue".formatted(newlyParsedBlocks.size());
                }
                var nextConversationState = new ConversationState(currentConversationState.taskMessages(), messageForRetry, currentConversationState.originalWorkspaceEditableMessages());
                var pendingBlocksForRetry = new ArrayList<>(currentWorkspaceState.pendingBlocks());
                pendingBlocksForRetry.addAll(newlyParsedBlocks); // Add successfully parsed blocks before retry
                // Keep existing blocksAppliedWithoutBuild for Retry even on clean partial response
                var nextWorkspaceState = new WorkspaceState(pendingBlocksForRetry, updatedConsecutiveParseFailures, currentWorkspaceState.consecutiveApplyFailures(), currentWorkspaceState.blocksAppliedWithoutBuild(), currentWorkspaceState.lastBuildError(), currentWorkspaceState.changedFiles(), currentWorkspaceState.originalFileContents(), currentWorkspaceState.consecutiveBuildFailures());
                return new Step.Retry(new LoopContext(nextConversationState, nextWorkspaceState, currentLoopContext.userGoal()), requireNonNull(consoleLogForRetry));
            } else { // Full successful parse of this segment
                // Token Redaction:
                List<ChatMessage> originalTaskMessages = currentConversationState.taskMessages();
                List<ChatMessage> redactedTaskMessages = new ArrayList<>();
                for (ChatMessage message : originalTaskMessages) {
                    if (message instanceof AiMessage aiMessage) {
                        Optional<AiMessage> redacted = ContextManager.redactAiMessage(aiMessage, parser);
                        redacted.ifPresent(redactedTaskMessages::add); // Only add if redaction is successful and non-empty
                    } else {
                        redactedTaskMessages.add(message); // Keep other message types
                    }
                }

                var nextConversationStateWithRedaction = new ConversationState(
                    redactedTaskMessages,
                    currentConversationState.nextRequest(), // nextRequest will be updated by subsequent phases if they retry/fail
                    currentConversationState.originalWorkspaceEditableMessages()
                );

                var nextWorkspaceState = new WorkspaceState(mutablePendingBlocks, updatedConsecutiveParseFailures, currentWorkspaceState.consecutiveApplyFailures(), currentWorkspaceState.blocksAppliedWithoutBuild(), currentWorkspaceState.lastBuildError(), currentWorkspaceState.changedFiles(), currentWorkspaceState.originalFileContents(), currentWorkspaceState.consecutiveBuildFailures());
                return new Step.Continue(new LoopContext(nextConversationStateWithRedaction, nextWorkspaceState, currentLoopContext.userGoal()), List.copyOf(newlyParsedBlocks));
            }
        }
    }

    /**
     * Pre-creates empty files for SearchReplaceBlocks representing new files
     * (those with empty beforeText). This ensures files exist on disk before
     * they are added to the context, preventing race conditions with UI updates.
     *
     * @param blocks         Collection of SearchReplaceBlocks potentially containing new file creations
     */
    @VisibleForTesting
    public List<ProjectFile> preCreateNewFiles(Collection<EditBlock.SearchReplaceBlock> blocks) {
        List<ProjectFile> newFiles = new ArrayList<>();
        for (EditBlock.SearchReplaceBlock block : blocks) {
            // Skip blocks that aren't for new files (new files have empty beforeText)
            if (block.filename() == null || !block.beforeText().trim().isEmpty()) {
                continue;
            }

            // We're creating a new file so resolveProjectFile is complexity we don't need, just use the filename
            ProjectFile file = contextManager.toFile(block.filename());
            newFiles.add(file);

            // Create the empty file if it doesn't exist yet
            if (!file.exists()) {
                try {
                    file.write(""); // Using ProjectFile.write handles directory creation internally
                    logger.debug("Pre-created empty file: {}", file);
                } catch (IOException e) {
                    io.toolError("Failed to create empty file " + file + ": " + e.getMessage(), "Error");
                }
            }
        }

        // add new files to git and the Workspace
        if (!newFiles.isEmpty()) {
            try {
                contextManager.getRepo().add(newFiles);
            } catch (GitAPIException e) {
                io.toolError("Failed to add %s to git".formatted(newFiles), "Error");
            }
            contextManager.editFiles(newFiles);
        }
        return newFiles;
    }

    /**
     * Fallback mechanism when standard SEARCH/REPLACE fails repeatedly.
     * Attempts to replace the entire content of each failed file using QuickEdit prompts.
     * Runs replacements in parallel.
     *
     * @param failedBlocks      The list of blocks that failed to apply.
     * @param originalUserInput The initial user goal for context.
     * @param taskMessages      The list of task messages for context.
     * @throws EditStopException if the fallback fails or is interrupted.
     */
    private void attemptFullFileReplacements(List<EditBlock.FailedBlock> failedBlocks,
                                             String originalUserInput,
                                             List<ChatMessage> taskMessages) throws EditStopException
    {
        var failuresByFile = failedBlocks.stream()
                .filter(fb -> fb.block().filename() != null)
                .collect(Collectors.groupingBy(fb -> contextManager.toFile(fb.block().filename())));

        if (failuresByFile.isEmpty()) {
            logger.debug("Fatal: no filenames present in failed blocks");
            throw new EditStopException(new TaskResult.StopDetails(TaskResult.StopReason.APPLY_ERROR, "No filenames present in failed blocks"));
        }

        io.systemOutput("Attempting full file replacement for: " + failuresByFile.keySet().stream().map(ProjectFile::toString).collect(Collectors.joining(", ")));
        
        // Prepare tasks for parallel execution
        var tasks = failuresByFile.entrySet().stream().map(entry -> (Callable<Optional<String>>) () -> {
            var file = entry.getKey();
            var failuresForFile = entry.getValue();
            try {
                // Prepare request
                var goal = "The previous attempt to modify this file using SEARCH/REPLACE failed repeatedly. Original goal: " + originalUserInput;
                var messages = CodePrompts.instance.collectFullFileReplacementMessages(contextManager, file, failuresForFile, goal, taskMessages);
                var model = requireNonNull(contextManager.getService().getModel(Service.GROK_3_MINI, Service.ReasoningLevel.DEFAULT));
                var coder = contextManager.getLlm(model, "Full File Replacement: " + file.getFileName());
                coder.setOutput(io);
                return executeReplace(file, coder, messages);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).toList();

        // Execute tasks with ExecutorService.invokeAll for interrupt handling
        var executor = Executors.newFixedThreadPool(10);
        List<Future<Optional<String>>> futures;
        try {
            futures = executor.invokeAll(tasks);
        } catch (InterruptedException e) {
            logger.debug("Interrupted during full file replacement tasks execution.");
            Thread.currentThread().interrupt();
            throw new EditStopException(TaskResult.StopReason.INTERRUPTED);
        } finally {
            executor.shutdownNow();
        }

        // Collect results
        var actualFailureMessages = futures.stream()
                .map(future -> {
                    try {
                        return future.get(); // This should not block long since invokeAll already waited
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.debug("Interrupted while collecting results for full file replacement.");
                        return Optional.of("Interrupted during result collection.");
                    } catch (ExecutionException e) {
                        logger.error("Error during full file replacement task", e.getCause());
                        return Optional.of("Error: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
                    }
                })
                .flatMap(Optional::stream)
                .toList();

        if (actualFailureMessages.isEmpty()) {
            // All replacements succeeded
            return;
        }

        // Report combined errors
        var combinedError = String.join("\n", actualFailureMessages);
        if (actualFailureMessages.size() < failuresByFile.size()) {
            int succeeded = failuresByFile.size() - actualFailureMessages.size();
            combinedError = "%d/%d files succeeded.\n".formatted(succeeded, failuresByFile.size()) + combinedError;
        }
        logger.debug("Full file replacement fallback finished with issues for {} file(s): {}", actualFailureMessages.size(), combinedError);
        throw new EditStopException(new TaskResult.StopDetails(TaskResult.StopReason.APPLY_ERROR, "Full replacement failed or was cancelled for %d file(s). Details:\n%s".formatted(actualFailureMessages.size(), combinedError)));
    }

    /**
     * @return an error message, or empty if successful
     */
    public static Optional<String> executeReplace(ProjectFile file, Llm coder, List<ChatMessage> messages)
    throws InterruptedException, IOException
    {
        // Send request
        StreamingResult result = coder.sendRequest(messages, false);

        // Process response
        if (result.error() != null) {
            return Optional.of("LLM error for %s: %s".formatted(file, result.error().getMessage()));
        }
        // If no error, result.text() is available.
        if (result.text().isBlank()) {
            return Optional.of("Empty LLM response for %s".formatted(file));
        }

        // for Upgrade Agent
        if (result.text().contains("BRK_NO_CHANGES_REQUIRED")) {
            return Optional.empty();
        }

        // Extract and apply
        var newContent = EditBlock.extractCodeFromTripleBackticks(result.text());
        if (newContent.isBlank()) {
            // Allow empty if response wasn't just ``` ```
            if (result.text().strip().equals("```\n```") || result.text().strip().equals("``` ```")) {
                // Treat explicitly empty fenced block as success
                newContent = "";
            } else {
                return Optional.of("Could not extract fenced code block from response for %s".formatted(file));
            }
        }

        file.write(newContent);
        logger.debug("Successfully applied full file replacement for {}", file);
        return Optional.empty();
    }

    /**
     * Prepares messages for storage in a TaskEntry.
     * This involves filtering raw LLM I/O to keep USER, CUSTOM, and AI messages.
     * AI messages containing SEARCH/REPLACE blocks will have their raw text preserved,
     * rather than converting blocks to HTML placeholders or summarizing block-only messages.
     */
    private List<ChatMessage> prepareMessagesForTaskEntryLog() {
        var rawMessages = io.getLlmRawMessages();

        return rawMessages.stream()
                .flatMap(message -> {
                    return switch (message.type()) {
                        case USER, CUSTOM -> Stream.of(message);
                        case AI -> {
                            var aiMessage = (AiMessage) message;
                            // Pass through AI messages with their original text.
                            // Raw S/R blocks are preserved.
                            // If the text is blank, effectively filter out the message.
                            yield aiMessage.text().isBlank() ? Stream.empty() : Stream.of(aiMessage);
                        }
                        // Ignore SYSTEM/TOOL messages for TaskEntry log purposes
                        default -> Stream.empty();
                    };
                })
                .toList();
    }

    /**
     * @return A list of ProjectFile objects representing read-only files the LLM attempted to edit,
     * or an empty list if no read-only files were targeted.
     */
    private static List<ProjectFile> findConflicts(List<EditBlock.SearchReplaceBlock> blocks,
                                                   IContextManager cm)
    {
        // Identify files referenced by blocks that are not already editable
        var filesToAdd = blocks.stream()
                .map(EditBlock.SearchReplaceBlock::filename)
                .filter(Objects::nonNull)
                .distinct()
                .map(cm::toFile) // Convert filename string to ProjectFile
                .filter(file -> !cm.getEditableFiles().contains(file))
                .toList();

        // Check for conflicts with read-only files
        var readOnlyFiles = filesToAdd.stream()
                .filter(file -> cm.getReadonlyFiles().contains(file))
                .toList();
        if (!readOnlyFiles.isEmpty()) {
            cm.getIo().systemOutput(
                    "LLM attempted to edit read-only file(s): %s.\nNo edits applied. Mark the file(s) editable or clarify the approach."
                            .formatted(readOnlyFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(","))));
        }
        return readOnlyFiles;
    }


    /**
     * Runs a quick-edit task where we:
     * 1) Gather the entire file content plus related context (buildAutoContext)
     * 2) Use QuickEditPrompts to ask for a single fenced code snippet
     * 3) Replace the old text with the new snippet in the file
     *
     * @return A TaskResult containing the conversation and original content.
     */
    public TaskResult runQuickTask(ProjectFile file,
                                   String oldText,
                                   String instructions) throws InterruptedException
    {
        var coder = contextManager.getLlm(model, "QuickEdit: " + instructions);
        coder.setOutput(io);

        // Use up to 5 related classes as context
        // buildAutoContext is an instance method on Context, or a static helper on ContextFragment for SkeletonFragment directly
        var relatedCode = contextManager.liveContext().buildAutoContext(5);

        String fileContents;
        try {
            fileContents = file.read();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }

        var styleGuide = contextManager.getProject().getStyleGuide();

        // Build the prompt messages
        var messages = QuickEditPrompts.instance.collectMessages(fileContents, relatedCode, styleGuide);

        // The user instructions
        var instructionsMsg = QuickEditPrompts.instance.formatInstructions(oldText, instructions);
        messages.add(new UserMessage(instructionsMsg));

        // Initialize pending history with the instruction
        var pendingHistory = new ArrayList<ChatMessage>();
        pendingHistory.add(new UserMessage(instructionsMsg));

        // No echo for Quick Edit, use instance quickModel
        var result = coder.sendRequest(messages, false);

        // Determine stop reason based on LLM response
        TaskResult.StopDetails stopDetails;
        if (result.error() != null) {
            String errorMessage = Objects.toString(result.error().getMessage(), "Unknown LLM error during quick edit");
            stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, errorMessage);
            io.toolError("Quick edit failed: " + errorMessage);
        } else if (result.text().isBlank()) {
            stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.EMPTY_RESPONSE);
            io.toolError("LLM returned empty response for quick edit.");
        } else {
            // Success from LLM perspective (no error, text is not blank)
            pendingHistory.add(result.aiMessage());
            stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
        }

        // Return TaskResult containing conversation and original content
        return new TaskResult("Quick Edit: " + file.getFileName(),
                              new ContextFragment.TaskFragment(contextManager, pendingHistory, "Quick Edit: " + file.getFileName()),
                              Set.of(file),
                              stopDetails);
    }

    /**
     * Formats the most recent build error for the LLM retry prompt.
     */
    private static String formatBuildErrorsForLLM(String latestBuildError) {
        return """
               The build failed with the following error:
               
               %s
               
               Please analyze the error message, review the conversation history for previous attempts, and provide SEARCH/REPLACE blocks to fix the error.
               
               IMPORTANT: If you determine that the build errors are not improving or are going in circles after reviewing the history,
               do your best to explain the problem but DO NOT provide any edits.
               Otherwise, provide the edits as usual.
               """.stripIndent().formatted(latestBuildError);
    }

    /**
     * Executes the verification command and updates build error history.
     *
     * @return empty string if the build was successful or skipped, error message otherwise.
     */
    private static String checkBuild(String verificationCommand, IContextManager cm, IConsoleIO io) throws InterruptedException {
        if (verificationCommand == null || verificationCommand.isBlank()) {
            io.llmOutput("\nNo verification command specified, skipping build/check.", ChatMessageType.CUSTOM);
            return "";
        }

        io.llmOutput("\nRunning verification command: " + verificationCommand, ChatMessageType.CUSTOM);
        io.llmOutput("\n```bash\n", ChatMessageType.CUSTOM);
        try {
            var output = Environment.instance.runShellCommand(verificationCommand,
                                                              cm.getProject().getRoot(),
                                                              line -> io.llmOutput(line + "\n", ChatMessageType.CUSTOM));
            logger.debug("Verification command successful. Output: {}", output);
            io.llmOutput("\n```", ChatMessageType.CUSTOM);
            io.llmOutput("\n**Verification successful**", ChatMessageType.CUSTOM);
            return "";
        } catch (Environment.SubprocessException e) {
            io.llmOutput("\n```", ChatMessageType.CUSTOM); // Close the markdown block
            io.llmOutput("\n**Verification failed**", ChatMessageType.CUSTOM);
            logger.warn("Verification command failed: {} Output: {}", e.getMessage(), e.getOutput(), e);
            // Add the combined error and output to the history for the next request
            return e.getMessage() + "\n\n" + e.getOutput();
        }
    }

    /**
     * Generates a user message to ask the LLM to continue when a response appears to be cut off.
     * @param lastBlock The last successfully parsed block from the incomplete response.
     * @return A formatted string to be used as a UserMessage.
     */
    private static String getContinueFromLastBlockPrompt(EditBlock.SearchReplaceBlock lastBlock) {
        return """
               It looks like we got cut off. The last block I successfully parsed was:
               
               <block>
               %s
               </block>
               
               Please continue from there (WITHOUT repeating that one).
               """.stripIndent().formatted(lastBlock);
    }

    private static class EditStopException extends RuntimeException {
        private final TaskResult.StopDetails stopDetails;

        public EditStopException(TaskResult.StopDetails stopDetails) {
            super(stopDetails.reason().name() + (stopDetails.explanation() != null ? ": " + stopDetails.explanation() : ""));
            this.stopDetails = stopDetails;
        }

        public EditStopException(TaskResult.StopReason stopReason) {
            this(new TaskResult.StopDetails(stopReason));
        }
    }

    private Step requestPhase(LoopContext currentLoopContext, StreamingResult streamingResultFromLlm) {
        var cs = currentLoopContext.conversationState();
        // var ws = currentLoopContext.workspaceState(); // ws not directly used in this phase's logic other than for sendLlmRequest, which is now outside

        // streamingResultFromLlm is passed in, no try-catch for InterruptedException for sendLlmRequest here

        var llmError = streamingResultFromLlm.error();
        if (streamingResultFromLlm.isEmpty()) {
            String message;
            TaskResult.StopDetails fatalDetails;
            if (llmError != null) {
                message = "LLM returned an error even after retries: " + llmError.getMessage() + ". Ending task";
                fatalDetails = new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, requireNonNull(llmError.getMessage()));
            } else {
                message = "Empty LLM response even after retries. Ending task";
                fatalDetails = new TaskResult.StopDetails(TaskResult.StopReason.EMPTY_RESPONSE, message);
            }
            io.toolError(message);
            return new Step.Fatal(fatalDetails);
        }

        // Append request and AI message to taskMessages (which is part of ConversationState in LoopContext)
        cs.taskMessages().add(cs.nextRequest());
        cs.taskMessages().add(streamingResultFromLlm.aiMessage());

        // LoopContext's taskMessages have been updated
        return new Step.Continue(currentLoopContext, Collections.emptyList());
    }

    //Removed handleParsingAndPrepareRetry method

    private EditBlock.EditResult applyBlocksAndHandleErrors(List<EditBlock.SearchReplaceBlock> blocksToApply,
                                                            Set<ProjectFile> changedFilesCollector)
            throws EditStopException, InterruptedException
    {
        // Abort if LLM tried to edit read-only files
        var readOnlyFiles = findConflicts(blocksToApply, contextManager);
        if (!readOnlyFiles.isEmpty()) {
            var filenames = readOnlyFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(","));
            // findConflicts already sends a systemOutput message
            throw new EditStopException(new TaskResult.StopDetails(TaskResult.StopReason.READ_ONLY_EDIT, filenames));
        }

        // Pre-create empty files for any new files from the current LLM response segment
        // (and add to git + workspace). This prevents UI race conditions.
        // As newlyParsedBlocksFromCurrentIteration is removed, this now operates on all blocksToApply.
        preCreateNewFiles(blocksToApply);


        EditBlock.EditResult editResult;
        try {
            editResult = EditBlock.applyEditBlocks(contextManager, io, blocksToApply);
        } catch (IOException e) {
            var eMessage = requireNonNull(e.getMessage());
            // io.toolError is handled by caller if this exception propagates
            throw new EditStopException(new TaskResult.StopDetails(TaskResult.StopReason.IO_ERROR, eMessage));
        }

        changedFilesCollector.addAll(editResult.originalContents().keySet());
        // Internal applyFailures counter and fallback logic removed from this helper.
        // It now simply returns the result of EditBlock.applyEditBlocks.
        return editResult;
    }

    private Step verifyPhase(LoopContext currentLoopContext) {
        var cs = currentLoopContext.conversationState();
        var ws = currentLoopContext.workspaceState();

        // Plan Invariant 3: Verify only runs when editsSinceLastBuild > 0.
        // blocksAppliedWithoutBuild acts as editsSinceLastBuild here.
        if (ws.blocksAppliedWithoutBuild() == 0) {
            // LoopContext is not changed here, so no new instance needed for Step.Continue
            return new Step.Continue(currentLoopContext, Collections.emptyList()); // No build needed, pass empty list for newlyParsedBlocks
        }

        String latestBuildError;
        try {
            latestBuildError = performBuildVerification(); // Uses existing helper
        } catch (InterruptedException e) {
            logger.debug("CodeAgent interrupted during build verification.");
            Thread.currentThread().interrupt(); // Preserve interrupt status
            return new Step.Fatal(new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED));
        }

        if (latestBuildError.isEmpty()) { // Build succeeded or was skipped by performBuildVerification
            var newWs = new WorkspaceState(
                ws.pendingBlocks(), // Should be empty if applyPhase did its job
                ws.consecutiveParseFailures(),
                ws.consecutiveApplyFailures(), // Should be 0 if applyPhase was successful
                0, // blocksAppliedWithoutBuild is reset
                "", // lastBuildError is empty
                ws.changedFiles(),
                ws.originalFileContents(),
                0 // consecutiveBuildFailures is reset
            );
            // On successful build, the loopContext for Step.Continue should reflect this clean state.
            // The userGoal is still the same, and conversation history is preserved.
            // The nextRequest in cs might be a placeholder from applyPhase fallback; it will be ignored if runTask terminates due to SUCCESS.
            return new Step.Continue(new LoopContext(cs, newWs, currentLoopContext.userGoal()), Collections.emptyList());
        } else { // Build failed
            int updatedConsecutiveBuildFailures = ws.consecutiveBuildFailures() + 1;

            if (updatedConsecutiveBuildFailures >= MAX_BUILD_FAILURES) {
                String fatalMessage = "Build failed after " + updatedConsecutiveBuildFailures + " attempts. Last error:\n" + latestBuildError;
                io.systemOutput(fatalMessage);
                return new Step.Fatal(new TaskResult.StopDetails(TaskResult.StopReason.BUILD_ERROR, fatalMessage));
            }

            // Prepare for retry
            UserMessage nextRequestForBuildFailure = new UserMessage(formatBuildErrorsForLLM(latestBuildError));
            var newCs = new ConversationState(
                cs.taskMessages(),
                nextRequestForBuildFailure,
                cs.originalWorkspaceEditableMessages()
            );
            var newWs = new WorkspaceState(
                ws.pendingBlocks(), // Should be empty
                ws.consecutiveParseFailures(),
                ws.consecutiveApplyFailures(), // Should be 0
                0, // blocksAppliedWithoutBuild is reset
                latestBuildError, // Store the new build error
                ws.changedFiles(),
                ws.originalFileContents(),
                updatedConsecutiveBuildFailures // Store updated build failure count
            );
            return new Step.Retry(new LoopContext(newCs, newWs, currentLoopContext.userGoal()), "Build failed with: " + latestBuildError.lines().findFirst().orElse("") + "... Asking LLM to fix.");
        }
    }

    private Step applyPhase(LoopContext currentLoopContext, EditBlockParser parser) {
        var cs = currentLoopContext.conversationState();
        var ws = currentLoopContext.workspaceState();

        if (ws.pendingBlocks().isEmpty() && ws.blocksAppliedWithoutBuild() == 0) {
            // This case will be handled by runTask's main loop structure to check if LLM provided no fixes.
            // If lastBuildError is present, runTask will make it a FATAL BUILD_ERROR.
            // If no lastBuildError, runTask will make it SUCCESS (no edits, no prior error).
            // So, applyPhase can just continue if no blocks are pending.
            // Note: LoopContext is not changed here, so no new instance needed for Step.Continue
            return new Step.Continue(currentLoopContext, Collections.emptyList()); // Pass empty list for newlyParsedBlocksInThisSegment as it's not relevant here
        }
        if (ws.pendingBlocks().isEmpty() && ws.blocksAppliedWithoutBuild() > 0) {
            // Blocks were applied in a previous iteration of applyPhase, and now pending is empty.
            // This means we should proceed to build verification.
            // Note: LoopContext is not changed here, so no new instance needed for Step.Continue
            return new Step.Continue(currentLoopContext, Collections.emptyList()); // Pass empty list for newlyParsedBlocksInThisSegment
        }

        EditBlock.EditResult editResult;
        int updatedConsecutiveApplyFailures = ws.consecutiveApplyFailures();
        WorkspaceState wsForStep = ws; // Will be updated
        ConversationState csForStep = cs; // Will be updated

        try {
            editResult = applyBlocksAndHandleErrors(
                ws.pendingBlocks(),
                ws.changedFiles() // Helper mutates this set
            );

            int attemptedBlockCount = ws.pendingBlocks().size();
            int succeededCount = attemptedBlockCount - editResult.failedBlocks().size();
            int newBlocksAppliedWithoutBuild = ws.blocksAppliedWithoutBuild() + succeededCount;

            // Update originalFileContents in the workspace state being built for the next step
            Map<ProjectFile, String> nextOriginalFileContents = new HashMap<>(ws.originalFileContents());
            if (editResult.originalContents() != null) {
                editResult.originalContents().forEach(nextOriginalFileContents::putIfAbsent);
            }

            List<EditBlock.SearchReplaceBlock> nextPendingBlocks = new ArrayList<>(); // Blocks are processed, so clear for next step's ws

            if (!editResult.failedBlocks().isEmpty()) { // Some blocks failed the direct apply
                if (succeededCount == 0) { // Total failure for this batch of pendingBlocks
                    updatedConsecutiveApplyFailures++;
                } else { // Partial success
                    updatedConsecutiveApplyFailures = 0;
                }

                if (updatedConsecutiveApplyFailures >= MAX_APPLY_FAILURES_BEFORE_FALLBACK) {
                    io.systemOutput("Apply failure limit reached (%d), attempting full file replacement fallback.".formatted(updatedConsecutiveApplyFailures));
                    List<EditBlock.FailedBlock> blocksForFallback = List.copyOf(editResult.failedBlocks());
                    attemptFullFileReplacements(blocksForFallback, currentLoopContext.userGoal(), cs.taskMessages());
                    // If attemptFullFileReplacements succeeds, it doesn't throw. If it fails, it throws EditStopException caught below.
                    io.systemOutput("Full file replacement fallback successful.");

                    // Update changedFiles with files modified by fallback
                    Set<ProjectFile> updatedChangedFiles = new HashSet<>(ws.changedFiles());
                    blocksForFallback.stream()
                        .filter(fb -> fb.block().filename() != null) // Ensure filename is not null
                        .map(fb -> contextManager.toFile(requireNonNull(fb.block().filename()))) // Now safe to call requireNonNull
                        .forEach(updatedChangedFiles::add);
                    
                    UserMessage placeholderPrompt = new UserMessage("[Placeholder: Build errors will be inserted here by verifyPhase if build fails after this full file replacement]");
                    csForStep = new ConversationState(cs.taskMessages(), placeholderPrompt, cs.originalWorkspaceEditableMessages());
                    
                    wsForStep = new WorkspaceState(
                        nextPendingBlocks, // empty
                        ws.consecutiveParseFailures(),
                        0, // Reset consecutive apply failures after successful fallback
                        1, // Force build as per plan (at least one "edit" happened via fallback)
                        ws.lastBuildError(), // Keep last build error for now
                        updatedChangedFiles, // Use updated set
                        nextOriginalFileContents,
                        ws.consecutiveBuildFailures()
                    );
                    return new Step.Continue(new LoopContext(csForStep, wsForStep, currentLoopContext.userGoal()), Collections.emptyList());
                } else { // Apply failed, but not yet time for full fallback -> ask LLM to retry
                    String retryPromptText = CodePrompts.getApplyFailureMessage(editResult.failedBlocks(), parser, succeededCount, contextManager);
                    io.llmOutput("\nFailed to apply %s block(s), asking LLM to retry".formatted(editResult.failedBlocks().size()), ChatMessageType.CUSTOM);
                    UserMessage retryRequest = new UserMessage(retryPromptText);
                    csForStep = new ConversationState(cs.taskMessages(), retryRequest, cs.originalWorkspaceEditableMessages());
                    wsForStep = new WorkspaceState(
                        nextPendingBlocks, // empty
                        ws.consecutiveParseFailures(),
                        updatedConsecutiveApplyFailures,
                        newBlocksAppliedWithoutBuild,
                        ws.lastBuildError(),
                        ws.changedFiles(),
                        nextOriginalFileContents,
                        ws.consecutiveBuildFailures()
                    );
                    return new Step.Retry(new LoopContext(csForStep, wsForStep, currentLoopContext.userGoal()), "Retrying apply failures for %d blocks.".formatted(editResult.failedBlocks().size()));
                }
            } else { // All blocks from ws.pendingBlocks() applied successfully
                updatedConsecutiveApplyFailures = 0; // Reset on success
                wsForStep = new WorkspaceState(
                    nextPendingBlocks, // empty
                    ws.consecutiveParseFailures(),
                    updatedConsecutiveApplyFailures, // now 0
                    newBlocksAppliedWithoutBuild,
                        ws.lastBuildError(),
                        ws.changedFiles(),
                        nextOriginalFileContents,
                        ws.consecutiveBuildFailures()
                    );
                return new Step.Continue(new LoopContext(csForStep, wsForStep, currentLoopContext.userGoal()), Collections.emptyList());
            }

        } catch (EditStopException e) {
            // Handle exceptions from findConflicts, preCreateNewFiles (if it threw that), applyEditBlocks (IO), or attemptFullFileReplacements failure
            // Log appropriate messages based on e.stopDetails.reason()
            if (e.stopDetails.reason() == TaskResult.StopReason.READ_ONLY_EDIT) {
                // Message already sent by findConflicts
            } else if (e.stopDetails.reason() == TaskResult.StopReason.IO_ERROR) {
                io.toolError(requireNonNull(e.stopDetails.explanation()));
            } else if (e.stopDetails.reason() == TaskResult.StopReason.APPLY_ERROR) {
                io.systemOutput("Code Agent stopping: " + e.stopDetails.explanation());
            }
            return new Step.Fatal(e.stopDetails);
        } catch (InterruptedException e) {
            logger.debug("CodeAgent interrupted during applyPhase");
            Thread.currentThread().interrupt(); // Preserve interrupt status
            return new Step.Fatal(new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED));
        }
    }

    private String performBuildVerification() throws InterruptedException {
        var verificationCommand = BuildAgent.determineVerificationCommand(contextManager);
        if (verificationCommand == null) { // Also handles blank/empty string from determineVerificationCommand
            return ""; // No command, no error.
        }
        // checkBuild will call io.llmOutput itself.
        return checkBuild(verificationCommand, contextManager, io);
    }
}
