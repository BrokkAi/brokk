package io.github.jbellis.brokk.agents;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.FinishReason;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.Llm.StreamingResult;
import io.github.jbellis.brokk.agents.BuildAgent.BuildDetails;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.prompts.CodePrompts;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
        this.contextManager = contextManager;
        this.model = model;
        this.io = contextManager.getIo();
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

        // Track original contents of files before any changes
        var originalContents = new HashMap<ProjectFile, String>();

        // Start verification command inference concurrently
        var verificationCommandFuture = BuildAgent.determineVerificationCommandAsync(contextManager);

        // Retry-loop state tracking
        int parseFailures = 0;
        int applyFailures = 0;
        int blocksAppliedWithoutBuild = 0;

        String buildError = "";
        var blocks = new ArrayList<EditBlock.SearchReplaceBlock>();

        var msg = "Code Agent engaged: `%s...`".formatted(LogDescription.getShortDescription(userInput));
        io.systemOutput(msg);
        TaskResult.StopDetails stopDetails;

        var parser = contextManager.getParserForWorkspace();
        // We'll collect the conversation as ChatMessages to store in context history.
        var taskMessages = new ArrayList<ChatMessage>();
        UserMessage nextRequest = CodePrompts.instance.codeRequest(userInput.trim(),
                                                                   CodePrompts.reminderForModel(contextManager.getService(), model),
                                                                   parser);

        while (true) {
            // Prepare and send request to LLM
            StreamingResult streamingResult;
            try {
                var allMessages = CodePrompts.instance.collectCodeMessages(contextManager,
                                                                           model,
                                                                           parser,
                                                                           taskMessages,
                                                                           nextRequest);
                streamingResult = coder.sendRequest(allMessages, true);
            } catch (InterruptedException e) {
                logger.debug("CodeAgent interrupted during sendRequest");
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
                break;
            }

            var llmResponse = streamingResult.chatResponse();
            var llmError = streamingResult.error();

            boolean hasUsableContent = llmResponse != null && !Messages.getText(llmResponse.aiMessage()).isBlank();
            if (!hasUsableContent) {
                String message;
                if (llmError != null) {
                    message = "LLM returned an error even after retries: " + llmError.getMessage() + ". Ending task";
                    stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, llmError.getMessage());
                } else {
                    message = "Empty LLM response even after retries. Ending task";
                    stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.EMPTY_RESPONSE, message);
                }
                io.toolError(message);
                break;
            }

            // Append request/response to task history
            taskMessages.add(nextRequest);
            taskMessages.add(llmResponse.aiMessage());

            String llmText = llmResponse.aiMessage().text();
            logger.debug("Got response (potentially partial if LLM connection was cut off)");

            // Parse any edit blocks from LLM response
            var parseResult = parser.parseEditBlocks(llmText, contextManager.getRepo().getTrackedFiles());
            var newlyParsedBlocks = parseResult.blocks();
            blocks.addAll(newlyParsedBlocks);

            var isPartialResponse = llmError != null || streamingResult.chatResponse().finishReason() == FinishReason.LENGTH;
            UserMessage messageForRetry = null;
            String consoleLogForRetry = null;

            // handle parse errors and incomplete responses
            if (parseResult.parseError() != null) {
                if (newlyParsedBlocks.isEmpty()) {
                    // Pure parse failure (no blocks parsed from this segment)
                    parseFailures++;
                    if (parseFailures > MAX_PARSE_ATTEMPTS) {
                        stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.PARSE_ERROR);
                        io.systemOutput("Parse error limit reached; ending task");
                        break; // Exit main loop
                    }
                    messageForRetry = new UserMessage(parseResult.parseError());
                    consoleLogForRetry = "Failed to parse LLM response; retrying";
                } else {
                    // Some blocks parsed, then a parse error (partial parse)
                    parseFailures = 0; // Reset, as we got some good blocks.
                    messageForRetry = new UserMessage(getContinueFromLastBlockPrompt(newlyParsedBlocks.getLast()));
                    consoleLogForRetry = "Malformed or incomplete response after %d blocks parsed; asking LLM to continue/fix".formatted(newlyParsedBlocks.size());
                }
            } else {
                parseFailures = 0; // Current segment is clean.

                if (isPartialResponse) {
                    // LLM indicated its response was cut short (e.g., length limit),
                    // BUT the part received so far is syntactically valid.
                    if (newlyParsedBlocks.isEmpty()) {
                        // No blocks parsed yet from this segment (e.g., LLM sent introductory text and then got cut off)
                        messageForRetry = new UserMessage("It looks like the response was cut off before you provided any code blocks. Please continue with your response.");
                        consoleLogForRetry = "LLM indicated response was partial before any blocks (no parse error); asking to continue";
                    } else {
                        // We have valid blocks from the partial response.
                        messageForRetry = new UserMessage(getContinueFromLastBlockPrompt(newlyParsedBlocks.getLast()));
                        consoleLogForRetry = "LLM indicated response was partial after %d clean blocks; asking to continue".formatted(newlyParsedBlocks.size());
                    }
                }
            }
            if (messageForRetry != null) {
                nextRequest = messageForRetry;
                logger.debug(consoleLogForRetry);
                io.llmOutput(consoleLogForRetry, ChatMessageType.CUSTOM);
                continue;
            }

            // If we reach here, it means the LLM segment was considered complete and correct for now.
            // Proceed to apply accumulated `blocks`.
            logger.debug("{} total unapplied blocks", blocks.size());

            // If no blocks are pending and we haven't applied anything yet, we're done
            if (blocks.isEmpty() && blocksAppliedWithoutBuild == 0) {
                io.systemOutput("No edits found in response, and no changes since last build; ending task");
                if (!buildError.isEmpty()) {
                    // Previous build failed and LLM provided no fixes
                    stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.BUILD_ERROR, buildError);
                } else {
                    stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS, llmText);
                }
                break;
            }

            // Abort if LLM tried to edit read-only files
            var readOnlyFiles = findConflicts(blocks, contextManager);
            if (!readOnlyFiles.isEmpty()) {
                var filenames = readOnlyFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(","));
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.READ_ONLY_EDIT, filenames);
                break;
            }

            // Pre-create empty files for any new files (and add to git + workspace)
            // This prevents UI race conditions with file existence checks
            var createdFiles = preCreateNewFiles(newlyParsedBlocks);
            if (createdFiles.stream().anyMatch(ContextManager::isTestFile)) {
                logger.debug("New test files created, re-determining verification command.");
                verificationCommandFuture = BuildAgent.determineVerificationCommandAsync(contextManager);
            }

            // Apply all accumulated blocks
            EditBlock.EditResult editResult;
            try {
                editResult = EditBlock.applyEditBlocks(contextManager, io, blocks);
            } catch (IOException e) {
                io.toolError(e.getMessage());
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.IO_ERROR, e.getMessage());
                break;
            }
            if (editResult.hadSuccessfulEdits()) {
                int succeeded = blocks.size() - editResult.failedBlocks().size();
                io.llmOutput("\n" + succeeded + " SEARCH/REPLACE blocks applied.", ChatMessageType.CUSTOM);
            }
            editResult.originalContents().forEach(originalContents::putIfAbsent);
            int succeededCount = (blocks.size() - editResult.failedBlocks().size());
            blocksAppliedWithoutBuild += succeededCount;
            blocks.clear(); // Clear them out: either successful or moved to editResult.failed

            // Check for interruption before potentially blocking build verification
            if (Thread.currentThread().isInterrupted()) {
                logger.debug("CodeAgent interrupted after applying edits.");
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
                break;
            }

            // Handle any failed blocks
            if (!editResult.failedBlocks().isEmpty()) {
                // If all blocks failed => increment applyErrors
                if (editResult.hadSuccessfulEdits()) {
                    applyFailures = 0;
                } else {
                    applyFailures++;
                }

                var parseRetryPrompt = CodePrompts.getApplyFailureMessage(editResult.failedBlocks(),
                                                                          parser,
                                                                          succeededCount,
                                                                          contextManager);
                if (!parseRetryPrompt.isEmpty()) {
                    if (applyFailures >= MAX_PARSE_ATTEMPTS) {
                        logger.debug("Apply failure limit reached ({}), attempting full file replacement fallback.", applyFailures);
                        stopDetails = attemptFullFileReplacements(editResult.failedBlocks(), originalContents, userInput, taskMessages);
                        if (stopDetails != null) {
                            // Full replacement also failed or was interrupted
                            io.systemOutput("Code Agent stopping after failing to apply edits to " + stopDetails.explanation());
                            break;
                        } else {
                            // Full replacement succeeded, reset failures and continue loop (will likely rebuild)
                            logger.debug("Full file replacement fallback successful.");
                            applyFailures = 0; // Reset since we made progress via fallback
                            // fall past else blocks to build check
                        }
                    } else {
                        // Normal retry with corrected blocks
                        io.llmOutput("\nFailed to apply %s block(s), asking LLM to retry".formatted(editResult.failedBlocks().size()), ChatMessageType.CUSTOM);
                        nextRequest = new UserMessage(parseRetryPrompt);
                        continue;
                    }
                }
            } else {
                // If we had successful apply, reset applyErrors
                applyFailures = 0;
            }

            // Attempt build/verification
            try {
                buildError = attemptBuildVerification(verificationCommandFuture, contextManager, io);
                blocksAppliedWithoutBuild = 0; // reset after each build attempt
            } catch (InterruptedException e) {
                logger.debug("CodeAgent interrupted during build verification.");
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
                break;
            }

            if (buildError.isEmpty()) {
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
                break;
            }

            // If the build failed after applying edits, create the next request for the LLM
            // (formatBuildErrorsForLLM includes instructions to stop if not progressing)
            nextRequest = new UserMessage(formatBuildErrorsForLLM(buildError));
        }

        // Conclude task
        assert stopDetails != null; // Ensure a stop reason was set before exiting the loop
        // create the Result for history
        String finalActionDescription = (stopDetails.reason() == TaskResult.StopReason.SUCCESS)
                                        ? userInput
                                        : userInput + " [" + stopDetails.reason().name() + "]";
        // architect auto-compresses the task entry so let's give it the full history to work with, quickModel is cheap
        // Prepare messages for TaskEntry log: filter raw messages and keep S/R blocks verbatim
        var finalMessages = forArchitect ? List.copyOf(io.getLlmRawMessages()) : prepareMessagesForTaskEntryLog();
        return new TaskResult("Code: " + finalActionDescription,
                              new ContextFragment.TaskFragment(contextManager, finalMessages, userInput),
                              originalContents,
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
     * @return StopDetails if the fallback fails or is interrupted, null otherwise.
     */
    private TaskResult.StopDetails attemptFullFileReplacements(List<EditBlock.FailedBlock> failedBlocks,
                                                               Map<ProjectFile, String> originalContents,
                                                               String originalUserInput,
                                                               List<ChatMessage> taskMessages)
    {
        var failuresByFile = failedBlocks.stream()
                .map(fb -> fb.block().filename())
                .filter(Objects::nonNull)
                .distinct()
                .map(contextManager::toFile)
                .toList();

        if (failuresByFile.isEmpty()) {
            logger.debug("Fatal: no filenames present in failed blocks");
            return new TaskResult.StopDetails(TaskResult.StopReason.APPLY_ERROR, "No filenames present in failed blocks");
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
            // Return error indicating failure, as the initial failures couldn't be addressed by fallback
            return new TaskResult.StopDetails(TaskResult.StopReason.APPLY_ERROR, "Could not read content for any files needing full replacement.");
        }

        var succeededCount = new AtomicInteger(0);

        // Process files in parallel using streams
        var futures = filesToProcess.stream().parallel().map(file -> CompletableFuture.supplyAsync(() -> {
             try {
                 assert originalContents.containsKey(file); // Should now always be true for files in filesToProcess

                 // Prepare request
                 var goal = "The previous attempt to modify this file using SEARCH/REPLACE failed repeatedly. Original goal: " + originalUserInput;
                 var messages = CodePrompts.instance.collectFullFileReplacementMessages(contextManager, file, goal, taskMessages);
                 var model = contextManager.getService().getModel(Service.GROK_3_MINI, Service.ReasoningLevel.DEFAULT);
                 var coder = contextManager.getLlm(model, "Full File Replacement: " + file.getFileName());

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
                 succeededCount.incrementAndGet();
                 return Optional.<String>empty(); // Success
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
            return new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
        }

        // Not cancelled -- collect results
        var actualFailureMessages = futures.stream()
                .map(f -> {
                    assert f.isDone();
                    try {
                        return f.getNow(Optional.of("Should never happen"));
                    } catch (CancellationException ce) {
                        // we already caught cancellations above
                        throw new AssertionError();
                    } catch (CompletionException ce) {
                        logger.error("Unexpected error applying change", ce);
                        return Optional.of("Unexpected error : " + ce.getCause().getMessage());
                    }
                })
                .flatMap(Optional::stream)
                .toList();

        if (succeededCount.get() == failuresByFile.size()) {
            assert actualFailureMessages.isEmpty() : actualFailureMessages;
            return null;
        } else {
            // Report combined errors
            var combinedError = String.join("\n", actualFailureMessages);
            if (succeededCount.get() < failuresByFile.size()) {
                combinedError = "%d/%d files succeeded.\n".formatted(succeededCount.get(), failuresByFile.size()) + combinedError;
            }
            logger.debug("Full file replacement fallback finished with issues for {} file(s): {}", actualFailureMessages.size(), combinedError);
            return new TaskResult.StopDetails(TaskResult.StopReason.APPLY_ERROR, "Full replacement failed or was cancelled for %d file(s).".formatted(failuresByFile.size() - succeededCount.get()));
        }
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
     * Runs the build verification command (once available) and appends any build error text to buildErrors list.
     * Returns empty string if build is successful, error message otherwise.
     */
    private static String attemptBuildVerification(CompletableFuture<String> verificationCommandFuture,
                                                   IContextManager contextManager,
                                                   IConsoleIO io) throws InterruptedException
    {
        String verificationCommand;
        try {
            verificationCommand = verificationCommandFuture.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.warn("Failed to get verification command", e);
            var bd = contextManager.getProject().loadBuildDetails();
            verificationCommand = (bd.equals(BuildDetails.EMPTY) ? null : bd.buildLintCommand());
        }

        return checkBuild(verificationCommand, contextManager, io);
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
            stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, result.error().getMessage());
            io.toolError("Quick edit failed: " + result.error().getMessage());
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
}
