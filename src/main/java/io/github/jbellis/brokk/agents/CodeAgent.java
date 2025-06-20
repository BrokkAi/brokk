package io.github.jbellis.brokk.agents;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.FinishReason;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.Llm.StreamingResult;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.prompts.QuickEditPrompts;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.LogDescription;
import io.github.jbellis.brokk.util.Messages;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
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

    private LlmOutcome handleLlmInteraction(EditState currentState, Llm coder, MessageProvider messageProvider) {
        StreamingResult streamingResult;
        try {
            var allMessages = messageProvider.getMessages(currentState);
            streamingResult = coder.sendRequest(allMessages, true);
        } catch (InterruptedException e) {
            logger.debug("CodeAgent interrupted during sendRequest in handleLlmInteraction");
            throw new EditStopException(TaskResult.StopReason.INTERRUPTED);
        }

        var llmResponse = streamingResult.chatResponse();
        var llmError = streamingResult.error();

        boolean hasUsableContent = llmResponse != null && !Messages.getText(llmResponse.aiMessage()).isBlank();
        if (!hasUsableContent) {
            String message;
            TaskResult.StopDetails stopDetails;
            if (llmError != null) {
                message = "LLM returned an error even after retries: " + Objects.toString(llmError.getMessage(), "Unknown LLM error") + ". Ending task";
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, Objects.toString(llmError.getMessage(), "Unknown LLM error"));
            } else {
                message = "Empty LLM response even after retries. Ending task";
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.EMPTY_RESPONSE);
            }
            io.toolError(message);
            throw new EditStopException(stopDetails);
        }
        // Assertions were here, using requireNonNull for NullAway
        var nonNullLlmResponse = requireNonNull(llmResponse, "llmResponse should not be null if hasUsableContent is true");
        var nonNullAiMessage = requireNonNull(nonNullLlmResponse.aiMessage(), "llmResponse.aiMessage() should not be null if hasUsableContent is true");

        var updatedTaskMessages = new ArrayList<>(currentState.taskMessages());
        updatedTaskMessages.add(currentState.nextRequest());
        updatedTaskMessages.add(nonNullAiMessage);

        EditState newState = currentState.afterLlmInteraction(updatedTaskMessages);
        return new LlmOutcome(newState, streamingResult);
    }

    private ParseOutcome handleParsing(EditState currentState, StreamingResult streamingResult, EditBlockParser parser) {
        // Assertions were here, using requireNonNull for NullAway
        var nonNullChatResponse = requireNonNull(streamingResult.chatResponse(), "chatResponse should not be null at parsing stage unless there was a severe prior error");
        var nonNullAiMessage = requireNonNull(nonNullChatResponse.aiMessage(), "aiMessage should not be null");
        String llmText = nonNullAiMessage.text();
        logger.debug("Got response (potentially partial if LLM connection was cut off)");

        var parseResult = parser.parseEditBlocks(llmText, contextManager.getRepo().getTrackedFiles());
        var newlyParsedBlocks = parseResult.blocks();

        var updatedBlocks = new ArrayList<>(currentState.blocks());
        updatedBlocks.addAll(newlyParsedBlocks);

        int parseFailures = currentState.parseFailures();
        UserMessage nextRequest = currentState.nextRequest(); // Keep current by default

        var isPartialResponse = streamingResult.error() != null || streamingResult.chatResponse().finishReason() == FinishReason.LENGTH;
        UserMessage messageForRetry = null;
        String consoleLogForRetry = null;

        if (parseResult.parseError() != null) {
            if (newlyParsedBlocks.isEmpty()) {
                parseFailures++;
                if (parseFailures > MAX_PARSE_ATTEMPTS) {
                    io.systemOutput("Parse error limit reached; ending task");
                    throw new EditStopException(TaskResult.StopReason.PARSE_ERROR);
                }
                messageForRetry = new UserMessage(parseResult.parseError());
                consoleLogForRetry = "Failed to parse LLM response; retrying";
            } else {
                parseFailures = 0; // Reset, as we got some good blocks.
                messageForRetry = new UserMessage(getContinueFromLastBlockPrompt(newlyParsedBlocks.getLast()));
                consoleLogForRetry = "Malformed or incomplete response after %d blocks parsed; asking LLM to continue/fix".formatted(newlyParsedBlocks.size());
            }
        } else {
            parseFailures = 0; // Current segment is clean.
            if (isPartialResponse) {
                if (newlyParsedBlocks.isEmpty()) {
                    messageForRetry = new UserMessage("It looks like the response was cut off before you provided any code blocks. Please continue with your response.");
                    consoleLogForRetry = "LLM indicated response was partial before any blocks (no parse error); asking to continue";
                } else {
                    messageForRetry = new UserMessage(getContinueFromLastBlockPrompt(newlyParsedBlocks.getLast()));
                    consoleLogForRetry = "LLM indicated response was partial after %d clean blocks; asking to continue".formatted(newlyParsedBlocks.size());
                }
            }
        }

        boolean continueToApplyPhase = true;
        if (messageForRetry != null) {
            nextRequest = messageForRetry;
            logger.debug(consoleLogForRetry);
            if (consoleLogForRetry != null) { // Guard against potential null if logic paths change
                io.llmOutput(consoleLogForRetry, ChatMessageType.CUSTOM);
            }
            continueToApplyPhase = false; // Signal to loop in runTask for retry
        }

        EditState newState = currentState.afterParsing(parseFailures, updatedBlocks, nextRequest);
        return new ParseOutcome(newState, continueToApplyPhase);
    }

    private EditState handleApplyEdits(EditState currentState, EditBlockParser parser) {
        // Now that we're done with incomplete response processing, redact SEARCH/REPLACE blocks
        // from all AI messages to reduce bloat in subsequent requests
        var taskMessages = currentState.taskMessages();
        for (int i = taskMessages.size() - 1; i >= 0; i--) {
            if (taskMessages.get(i) instanceof AiMessage aiMessage) {
                var redactedMessage = ContextManager.redactAiMessage(aiMessage, parser);
                if (redactedMessage.isPresent()) {
                    taskMessages.set(i, redactedMessage.get());
                } else {
                    taskMessages.remove(i);
                }
            }
        }

        var blocks = new ArrayList<>(currentState.blocks()); // mutable copy for this method scope
        int blocksAppliedWithoutBuild = currentState.blocksAppliedWithoutBuild();
        int applyFailures = currentState.applyFailures();
        var originalContentsOfChangedFiles = new HashMap<>(currentState.originalContentsOfChangedFiles());
        UserMessage nextRequest = currentState.nextRequest();

        if (blocks.isEmpty() && blocksAppliedWithoutBuild == 0) {
            io.systemOutput("No edits found in response, and no changes since last build; ending task");
            TaskResult.StopDetails stopDetails;
            if (!currentState.currentTaskInstructions().equals(currentState.initialGoal())) { // implies build error
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.BUILD_ERROR, currentState.currentTaskInstructions());
            } else {
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
            }
            throw new EditStopException(stopDetails);
        }

        var readOnlyFiles = findConflicts(blocks, contextManager);
        if (!readOnlyFiles.isEmpty()) {
            var filenames = readOnlyFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(","));
            throw new EditStopException(new TaskResult.StopDetails(TaskResult.StopReason.READ_ONLY_EDIT, filenames));
        }

        // Pre-create empty files for any new files before context updates
        // This prevents UI race conditions with file existence checks
        preCreateNewFiles(blocks);

        EditBlock.EditResult editResult;
        try {
            editResult = EditBlock.applyEditBlocks(contextManager, io, blocks);
        } catch (IOException e) {
            io.toolError(Objects.toString(e.getMessage(), "IO error during edit application"));
            throw new EditStopException(new TaskResult.StopDetails(TaskResult.StopReason.IO_ERROR, Objects.toString(e.getMessage(), "IO error during edit application")));
        }

        if (editResult.hadSuccessfulEdits()) {
            int succeeded = blocks.size() - editResult.failedBlocks().size();
            io.llmOutput("\n" + succeeded + " SEARCH/REPLACE blocks applied.", ChatMessageType.CUSTOM);
        }
        editResult.originalContents().forEach(originalContentsOfChangedFiles::putIfAbsent);
        int succeededCount = (blocks.size() - editResult.failedBlocks().size());
        blocksAppliedWithoutBuild += succeededCount;
        blocks.clear(); // Clear them out: either successful or moved to editResult.failed

        if (Thread.currentThread().isInterrupted()) {
            logger.debug("CodeAgent interrupted after applying edits in handleApplyEdits.");
            throw new EditStopException(TaskResult.StopReason.INTERRUPTED);
        }

        if (!editResult.failedBlocks().isEmpty()) {
            if (editResult.hadSuccessfulEdits()) {
                applyFailures = 0;
            } else {
                applyFailures++;
            }

            var parseRetryPrompt = CodePrompts.getApplyFailureMessage(editResult.failedBlocks(), parser, succeededCount, contextManager);
            if (!parseRetryPrompt.isEmpty()) {
                if (applyFailures >= MAX_PARSE_ATTEMPTS) {
                    logger.debug("Apply failure limit reached ({}), attempting full file replacement fallback.", applyFailures);
                    try {
                        // Pass initialGoal for context, and current task messages
                        attemptFullFileReplacements(editResult.failedBlocks(), originalContentsOfChangedFiles, currentState.initialGoal(), currentState.taskMessages());
                        logger.debug("Full file replacement fallback successful.");
                        applyFailures = 0;
                    } catch (EditStopException e) {
                        io.systemOutput("Code Agent stopping after failing to apply edits: " + e.getStopDetails().explanation());
                        throw e;
                    } catch (InterruptedException e) {
                        logger.debug("CodeAgent interrupted during full file replacement fallback in handleApplyEdits.");
                        Thread.currentThread().interrupt(); // Preserve interrupt status
                        throw new EditStopException(TaskResult.StopReason.INTERRUPTED);
                    }
                } else {
                    io.llmOutput("\nFailed to apply %s block(s), asking LLM to retry".formatted(editResult.failedBlocks().size()), ChatMessageType.CUSTOM);
                    nextRequest = new UserMessage(parseRetryPrompt);
                }
            }
        } else {
            applyFailures = 0;
        }

        return currentState.afterApplyingEdits(taskMessages, applyFailures, blocks, blocksAppliedWithoutBuild, nextRequest, originalContentsOfChangedFiles);
    }

    private EditState requestEdits(EditState currentState,
                                   Llm coder,
                                   EditBlockParser parser,
                                   MessageProvider messageProvider)
    {
        LlmOutcome llmOutcome = handleLlmInteraction(currentState, coder, messageProvider);
        ParseOutcome parseOutcome = handleParsing(llmOutcome.newState(), llmOutcome.streamingResult(), parser);

        if (!parseOutcome.continueToApply()) {
            return parseOutcome.newState(); // Return for next iteration of runTask's loop (retry for parsing)
        }

        // Parsing was successful or a partial parse yielded blocks and no immediate retry message.
        return handleApplyEdits(parseOutcome.newState(), parser);
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
        var coder = contextManager.getLlm(model, "Code: " + userInput, true);
        var parser = contextManager.getParserForWorkspace();

        var originalWorkspaceEditableMessages = CodePrompts.instance.getOriginalWorkspaceEditableMessages(contextManager);
        MessageProvider messageProvider = state -> {
            try {
                return CodePrompts.instance.collectCodeMessages(contextManager,
                                                                 model,
                                                                 parser,
                                                                 state.taskMessages(),
                                                                 state.nextRequest(),
                                                                 state.originalContentsOfChangedFiles().keySet(),
                                                                 originalWorkspaceEditableMessages);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Preserve interrupt status
                throw new RuntimeException("Message collection interrupted", e);
            }
        };

        UserMessage initialRequest = CodePrompts.instance.codeRequest(userInput.trim(),
                                                                      CodePrompts.reminderForModel(contextManager.getService(), model),
                                                                      parser);
        EditState editState = EditState.initialState(initialRequest, userInput);

        var msg = "Code Agent engaged: `%s...`".formatted(LogDescription.getShortDescription(userInput));
        io.systemOutput(msg);
        TaskResult.StopDetails stopDetails;

        try {
            while (true) {
                editState = requestEdits(editState, coder, parser, messageProvider);

                // Attempt build/verification
                var verificationCommand = BuildAgent.determineVerificationCommand(contextManager);
                if (verificationCommand == null) {
                    stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
                    break;
                }
                try {
                    String currentBuildError = checkBuild(verificationCommand, contextManager, io);
                    if (currentBuildError.isEmpty()) {
                        stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
                        break;
                    }
                    // Build failed, prepare for next LLM request
                    String buildFailureMessage = formatBuildErrorsForLLM(currentBuildError);
                    UserMessage nextRequestForBuildFix = new UserMessage(buildFailureMessage);
                    editState = editState.afterBuildVerification(nextRequestForBuildFix, buildFailureMessage);
                } catch (InterruptedException e) {
                    logger.debug("CodeAgent interrupted during build verification.");
                    stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
                    break;
                }
            }
        } catch (EditStopException e) {
            logger.debug("CodeAgent task stopped for {}", e.getStopDetails());
            stopDetails = e.getStopDetails();
        }


        // Conclude task
        assert stopDetails != null; // Ensure a stop reason was set

        String finalActionDescription = (stopDetails.reason() == TaskResult.StopReason.SUCCESS)
                                        ? userInput
                                        : userInput + " [" + stopDetails.reason().name() + "]";
        var messagesForLog = forArchitect
                             ? List.copyOf(io.getLlmRawMessages())
                             : prepareMessagesForTaskEntryLog();
        return new TaskResult("Code: " + finalActionDescription,
                              new ContextFragment.TaskFragment(contextManager, messagesForLog, userInput),
                              editState.originalContentsOfChangedFiles(),
                              stopDetails);
    }

    public TaskResult runSingleFileEdit(ProjectFile file,
                                        String instructions,
                                        List<ChatMessage> readOnlyMessages)
    {
        var coder = contextManager.getLlm(model, "Code: " + instructions, true);

        String text;
        try {
            text = file.read();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var parser = EditBlockParser.getParserFor(text);

        var originalWorkspaceEditableMessages = CodePrompts.instance.getSingleFileEditableMessage(file);
        MessageProvider messageProvider = state -> {
            try {
                // readOnlyMessages is now passed in, no need to construct it here with getWorkspaceReadOnlyMessages
                return CodePrompts.instance.getSingleFileMessages(contextManager.getProject().getStyleGuide(),
                                                          parser,
                                                          readOnlyMessages,
                                                          state.taskMessages(),
                                                          state.nextRequest(),
                                                          state.originalContentsOfChangedFiles().keySet(),
                                                          originalWorkspaceEditableMessages);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Preserve interrupt status
                throw new RuntimeException("Message collection interrupted", e);
            }
        };

        UserMessage initialRequest = CodePrompts.instance.codeRequest(instructions,
                                                                      CodePrompts.reminderForModel(contextManager.getService(), model),
                                                                      parser);
        EditState editState = EditState.initialState(initialRequest, instructions);

        var msg = "Code Agent engaged (single file mode for %s): `%s...`".formatted(file, LogDescription.getShortDescription(instructions));
        io.systemOutput(msg);
        TaskResult.StopDetails stopDetails;

        try {
            //noinspection InfiniteLoopStatement
            while (true) {
                editState = requestEdits(editState, coder, parser, messageProvider);
            }
        } catch (EditStopException e) {
            logger.debug("CodeAgent task stopped for {}", e.getStopDetails());
            stopDetails = e.getStopDetails();
        }

        // Conclude task
        assert stopDetails != null; // Ensure a stop reason was set

        String finalActionDescription = (stopDetails.reason() == TaskResult.StopReason.SUCCESS)
                                        ? instructions
                                        : instructions + " [" + stopDetails.reason().name() + "]";
        var messagesForLog = prepareMessagesForTaskEntryLog();
        return new TaskResult("Code: " + finalActionDescription,
                              new ContextFragment.TaskFragment(contextManager, messagesForLog, instructions),
                              editState.originalContentsOfChangedFiles(),
                              stopDetails);
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
        var io = contextManager.getIo();
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
     * @param originalContents  Map to record original content before replacement.
     * @param originalUserInput The initial user goal for context.
 * @param taskMessages
 * @throws EditStopException if the fallback fails or is interrupted.
 */
    private void attemptFullFileReplacements(List<EditBlock.FailedBlock> failedBlocks,
                                             Map<ProjectFile, String> originalContents,
                                             String originalUserInput,
                                             List<ChatMessage> taskMessages) throws EditStopException, InterruptedException {
        var failuresByFile = failedBlocks.stream()
                .map(fb -> fb.block().filename())
                .filter(Objects::nonNull)
                .distinct()
                .map(contextManager::toFile)
                .toList();

        if (failuresByFile.isEmpty()) {
            logger.debug("Fatal: no filenames present in failed blocks");
            throw new EditStopException(new TaskResult.StopDetails(TaskResult.StopReason.APPLY_ERROR, "No filenames present in failed blocks"));
        }

        io.systemOutput("Attempting full file replacement for: " + failuresByFile.stream().map(ProjectFile::toString).collect(Collectors.joining(", ")));

        // Ensure original content is available for all files before parallel processing
        var filesToProcess = new ArrayList<ProjectFile>();
        for (var file : failuresByFile) {
            if (originalContents.containsKey(file)) {
                filesToProcess.add(file);
                continue;
            }

            try {
                originalContents.put(file, file.read());
                filesToProcess.add(file);
            } catch (IOException e) {
                logger.error("Failed to read content of {} for full replacement fallback", file, e);
                // Skip this file if we can't read its content
            }
        }

        if (filesToProcess.isEmpty()) {
            logger.debug("No files eligible for full file replacement after checking/reading content.");
            // Throw error indicating failure, as the initial failures couldn't be addressed by fallback
            throw new EditStopException(new TaskResult.StopDetails(TaskResult.StopReason.APPLY_ERROR, "Could not read content for any files needing full replacement."));
        }

        // Process files in parallel using streams
        var futures = filesToProcess.stream().parallel().map(file -> CompletableFuture.supplyAsync(() -> {
             try {
                 assert originalContents.containsKey(file); // Should now always be true for files in filesToProcess

                 // Prepare request
                 var goal = "The previous attempt to modify this file using SEARCH/REPLACE failed repeatedly. Original goal: " + originalUserInput;
                 var messages = CodePrompts.instance.collectFullFileReplacementMessages(contextManager, file, goal, taskMessages);
                 // Use a model known to be available and suitable for this type of task, like quickModel
                 var modelToUse = contextManager.getService().quickModel();
                 var coder = contextManager.getLlm(modelToUse, "Full File Replacement: " + file.getFileName());

                 return executeReplace(file, coder, messages);
             } catch (InterruptedException e) {
                 throw new CancellationException();
             } catch (IOException e) {
                 throw new UncheckedIOException(e);
             }
         }, contextManager.getBackgroundTasks())
        ).toList();

        // Wait for all parallel tasks submitted via supplyAsync; if any is cancelled, cancel the others immediately
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CancellationException e) {
            Thread.currentThread().interrupt();
        } catch (CompletionException e) {
            // log this later
        }
        if (Thread.currentThread().isInterrupted()) {
            logger.debug("Interrupted during or after waiting for full file replacement tasks. Cancelling pending tasks.");
            futures.forEach(f -> f.cancel(true)); // Attempt to cancel ongoing tasks
            throw new EditStopException(TaskResult.StopReason.INTERRUPTED);
        }

        // Not cancelled -- collect results
        var actualFailureMessages = futures.stream()
                .map(f -> {
                    assert f.isDone();
                    try {
                        return f.getNow(Optional.of("Should never happen")); // Should not block here
                    } catch (CancellationException ce) {
                        // This implies it was cancelled by the interruption block above, or by a timeout not handled here.
                        // The interruption exception should have been thrown already.
                        logger.warn("Task was cancelled but not caught by interruption check", ce);
                        return Optional.of("Task cancelled for file."); // Provide a generic message
                    } catch (CompletionException ce) {
                        logger.error("Unexpected error applying change during full file replacement", ce);
                        Throwable cause = ce.getCause();
                        if (cause instanceof InterruptedException) { // Check if the cause was an interruption
                             Thread.currentThread().interrupt(); // Re-interrupt
                             // This case should ideally be caught by the main interruption check,
                             // but good to handle if CompletionException wraps InterruptedException.
                             return Optional.of("Full file replacement interrupted for file.");
                        }
                        return Optional.of("Unexpected error: " + (cause != null ? cause.getMessage() : ce.getMessage()));
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
        if (actualFailureMessages.size() < filesToProcess.size()) { // Compare with filesToProcess which is the actual count attempted
            int succeeded = filesToProcess.size() - actualFailureMessages.size();
            combinedError = "%d/%d files succeeded.\n".formatted(succeeded, filesToProcess.size()) + combinedError;
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
        var response = result.chatResponse();
        if (response == null || response.aiMessage() == null || response.aiMessage().text() == null || response.aiMessage().text().isBlank()) {
            return Optional.of("Empty LLM response for %s".formatted(file));
        }

        // for Upgrade Agent
        if (response.aiMessage().text().contains("BRK_NO_CHANGES_REQUIRED")) {
            return Optional.empty();
        }

        // Extract and apply
        var newContent = EditBlock.extractCodeFromTripleBackticks(response.aiMessage().text());
        if (newContent == null || newContent.isBlank()) {
            // Allow empty if response wasn't just ``` ```
            if (response.aiMessage().text().strip().equals("```\n```") || response.aiMessage().text().strip().equals("``` ```")) {
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

        // Record the original content so we can undo if necessary
        var originalContents = Map.of(file, fileContents);

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
        } else if (result.chatResponse() == null || result.chatResponse().aiMessage() == null || result.chatResponse().aiMessage().text() == null || result.chatResponse().aiMessage().text().isBlank()) {
            stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.EMPTY_RESPONSE);
            io.toolError("LLM returned empty response for quick edit.");
        } else {
            // Success from LLM perspective
            String responseText = result.chatResponse().aiMessage().text();
            pendingHistory.add(new AiMessage(responseText));
            stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
        }

        // Return TaskResult containing conversation and original content
        return new TaskResult("Quick Edit: " + file.getFileName(),
                              new ContextFragment.TaskFragment(contextManager, pendingHistory, "Quick Edit: " + file.getFileName()),
                              originalContents,
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

        public TaskResult.StopDetails getStopDetails() {
            return stopDetails;
        }
    }

    @FunctionalInterface
    private interface MessageProvider {
        List<ChatMessage> getMessages(EditState state);
    }

    private record LlmOutcome(EditState newState, StreamingResult streamingResult) { }
    private record ParseOutcome(EditState newState, boolean continueToApply) { }

    private record EditState(int parseFailures,
                             int applyFailures,
                             List<EditBlock.SearchReplaceBlock> blocks,
                             int blocksAppliedWithoutBuild,
                             UserMessage nextRequest,
                             String currentTaskInstructions,
                             String initialGoal,
                             Map<ProjectFile, String> originalContentsOfChangedFiles,
                             List<ChatMessage> taskMessages)
    {
        public static EditState initialState(UserMessage initialRequest, String userInput) {
            return new EditState(0, // parseFailures
                                 0, // applyFailures
                                 new ArrayList<>(), // blocks
                                 0, // blocksAppliedWithoutBuild
                                 initialRequest, // nextRequest
                                 userInput, // currentTaskInstructions
                                 userInput, // initialGoal
                                 new HashMap<>(), // originalContentsOfChangedFiles
                                 new ArrayList<>() // taskMessages
            );
        }

        public EditState afterLlmInteraction(List<ChatMessage> updatedTaskMessages) {
            return new EditState(this.parseFailures(),
                                 this.applyFailures(),
                                 this.blocks(),
                                 this.blocksAppliedWithoutBuild(),
                                 this.nextRequest(),
                                 this.currentTaskInstructions(),
                                 this.initialGoal(),
                                 this.originalContentsOfChangedFiles(),
                                 updatedTaskMessages);
        }

        public EditState afterParsing(int parseFailures, List<EditBlock.SearchReplaceBlock> updatedBlocks, UserMessage nextRequest) {
            return new EditState(parseFailures,
                                 this.applyFailures(),
                                 updatedBlocks,
                                 this.blocksAppliedWithoutBuild(),
                                 nextRequest,
                                 this.currentTaskInstructions(),
                                 this.initialGoal(),
                                 this.originalContentsOfChangedFiles(),
                                 this.taskMessages());
        }

        public EditState afterApplyingEdits(List<ChatMessage> taskMessages, int applyFailures, List<EditBlock.SearchReplaceBlock> blocks, int blocksAppliedWithoutBuild, UserMessage nextRequest, Map<ProjectFile, String> originalContentsOfChangedFiles) {
            return new EditState(this.parseFailures(),
                                 applyFailures,
                                 blocks,
                                 blocksAppliedWithoutBuild,
                                 nextRequest,
                                 this.currentTaskInstructions(),
                                 this.initialGoal(),
                                 originalContentsOfChangedFiles,
                                 taskMessages);
        }

        public EditState afterBuildVerification(UserMessage nextRequest, String buildFailureMessage) {
            return new EditState(this.parseFailures(),
                                 this.applyFailures(),
                                 this.blocks(),
                                 0, // reset blocksAppliedWithoutBuild
                                 nextRequest,
                                 buildFailureMessage, // currentTaskInstructions is now the build error
                                 this.initialGoal(), // initialGoal remains the same
                                 this.originalContentsOfChangedFiles(),
                                 this.taskMessages());
        }
    }
}
