package io.github.jbellis.brokk.agents;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.Llm.StreamingResult;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.prompts.EditBlockConflictsParser;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.prompts.QuickEditPrompts;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.LogDescription;
import io.github.jbellis.brokk.util.Messages;
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
    @VisibleForTesting
    static final int MAX_APPLY_FAILURES_BEFORE_FALLBACK = 3;

    final IContextManager contextManager;
    private final StreamingChatModel model;
    private final IConsoleIO io;

    public CodeAgent(IContextManager contextManager, StreamingChatModel model) {
        this(contextManager, model, contextManager.getIo());
    }

    public CodeAgent(IContextManager contextManager, StreamingChatModel model, IConsoleIO io) {
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

        // Track changed files
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
                                                                   parser,
                                                                   null);

        var conversationState = new ConversationState(taskMessages, nextRequest, originalWorkspaceEditableMessages);
        var workspaceState = new EditState(blocks, 0, applyFailures, blocksAppliedWithoutBuild, buildError, changedFiles, originalFileContents);
        var loopContext = new LoopContext(conversationState, workspaceState, userInput);

        while (true) {
            if (Thread.interrupted()) {
                logger.debug("CodeAgent interrupted");
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
                break;
            }

            // Variables needed across phase calls if not passed via Step results
            StreamingResult streamingResult; // Will be set before requestPhase

            // Make the LLM request
            try {
                var allMessagesForLlm = CodePrompts.instance.collectCodeMessages(contextManager,
                                                                                 model,
                                                                                 parser,
                                                                                 loopContext.conversationState().taskMessages(),
                                                                                 loopContext.conversationState().nextRequest(),
                                                                                 loopContext.editState().changedFiles(),
                                                                                 loopContext.conversationState().originalWorkspaceEditableMessages());
                streamingResult = coder.sendRequest(allMessagesForLlm, true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                continue; // let main loop interruption check handle
            }

            // REQUEST PHASE handles the result of sendLlmRequest
            var requestOutcome = requestPhase(loopContext, streamingResult);
            switch (requestOutcome) {
                case Step.Continue(var newLoopContext) -> loopContext = newLoopContext;
                case Step.Fatal(var details) -> stopDetails = details;
                default -> throw new IllegalStateException("requestPhase returned unexpected Step type: " + requestOutcome.getClass());
            }
            if (stopDetails != null) break; // If requestPhase was Fatal

            // PARSE PHASE parses edit blocks
            var parseOutcome = parsePhase(loopContext, streamingResult.text(), streamingResult.isPartial(), parser); // Ensure parser is available
            switch (parseOutcome) {
                case Step.Continue(var newLoopContext) -> loopContext = newLoopContext;
                case Step.Retry(var newLoopContext) -> {
                    loopContext = newLoopContext;
                    continue; // Restart main loop
                }
                case Step.Fatal(var details) -> stopDetails = details;
            }
            if (stopDetails != null) break;

            // APPLY PHASE applies blocks
            var applyOutcome = applyPhase(loopContext, parser);
            switch (applyOutcome) {
                case Step.Continue(var newLoopContext) -> loopContext = newLoopContext;
                case Step.Retry(var newLoopContext) -> {
                    loopContext = newLoopContext;
                    continue; // Restart main loop
                }
                case Step.Fatal(var details) -> stopDetails = details;
            }
            if (stopDetails != null) break;

            // VERIFY PHASE runs the build
            // since this is the last phase, it does not use Continue
            assert loopContext.editState().pendingBlocks().isEmpty() : loopContext;
            var verifyOutcome = verifyPhase(loopContext);
            switch (verifyOutcome) {
                case Step.Retry(var newLoopContext) -> {
                    loopContext = newLoopContext;
                    continue;
                }
                case Step.Fatal(var details) -> stopDetails = details;
                default -> throw new IllegalStateException("verifyPhase returned unexpected Step type " + verifyOutcome);
            }
            // awkward construction but maintains symmetry
            if (stopDetails != null) break;
        }

        // everyone reports their own reasons for stopping, except for interruptions
        if (stopDetails.reason() == TaskResult.StopReason.INTERRUPTED) {
            reportComplete("Cancelled by user.");
        }
        // create the Result for history
        String finalActionDescription = (stopDetails.reason() == TaskResult.StopReason.SUCCESS)
                                        ? loopContext.userGoal()
                                        : loopContext.userGoal() + " [" + stopDetails.reason().name() + "]";
        // architect auto-compresses the task entry so let's give it the full history to work with, quickModel is cheap
        // Prepare messages for TaskEntry log: filter raw messages and keep S/R blocks verbatim
        var finalMessages = forArchitect ? List.copyOf(io.getLlmRawMessages()) : prepareMessagesForTaskEntryLog(io.getLlmRawMessages());
        return new TaskResult("Code: " + finalActionDescription,
                              new ContextFragment.TaskFragment(contextManager, finalMessages, loopContext.userGoal()),
                              loopContext.editState().changedFiles(),
                              stopDetails);
    }

    /**
     * Runs a “single-file edit” session in which the LLM is asked to modify exactly
     * {@code file}.  The method drives the same request / parse / apply FSM that
     * {@link #runTask(String, boolean)} uses, but it stops after all SEARCH/REPLACE
     * blocks have been applied (no build verification is performed).
     *
     * @param file            the file to edit
     * @param instructions    user instructions describing the desired change
     * @param readOnlyMessages conversation context that should be provided
     *                         to the LLM as read-only (e.g., other related
     *                         files, build output, etc.)
     *
     * @return a {@link TaskResult} recording the conversation and the original
     *         contents of all files that were changed
     */
    public TaskResult runSingleFileEdit(ProjectFile file,
                                        String instructions,
                                        List<ChatMessage> readOnlyMessages)
    {
        // 0.  Setup: coder, parser, initial messages, and initial LoopContext
        var coder = contextManager.getLlm(model, "Code (single-file): " + instructions, true);
        coder.setOutput(io);

        // TODO smart parser selection -- tricky because we need the redaction in UAPD to work
        var parser = EditBlockConflictsParser.instance;
        var editableMsg = CodePrompts.instance.getSingleFileEditableMessage(file);

        UserMessage initialRequest = CodePrompts.instance.codeRequest(instructions,
                                                                      CodePrompts.reminderForModel(contextManager.getService(), model),
                                                                      parser,
                                                                      file);

        var conversationState = new ConversationState(new ArrayList<>(), initialRequest, editableMsg);
        var editState = new EditState(new ArrayList<>(), 0, 0, 0, "", new HashSet<>(), new HashMap<>());
        var loopContext = new LoopContext(conversationState, editState, instructions);

        logger.debug("Code Agent engaged in single-file mode for %s: `%s…`"
                                .formatted(file.getFileName(), LogDescription.getShortDescription(instructions)));

        TaskResult.StopDetails stopDetails;

        // 1.  Main FSM loop (request → parse → apply)
        while (true) {
            // ----- 1-a.  Construct messages for this turn --------------------
            List<ChatMessage> llmMessages;
            llmMessages = CodePrompts.instance.getSingleFileCodeMessages(contextManager.getProject().getStyleGuide(),
                                                                         parser,
                                                                         readOnlyMessages,
                                                                         loopContext.conversationState().taskMessages(),
                                                                         loopContext.conversationState().nextRequest(),
                                                                         loopContext.editState().originalFileContents().keySet(),
                                                                         editableMsg);

            // ----- 1-b.  Send to LLM -----------------------------------------
            StreamingResult streamingResult;
            try {
                streamingResult = coder.sendRequest(llmMessages, true);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
                break;
            }

            // ----- 1-c.  REQUEST PHASE ---------------------------------------
            var step = requestPhase(loopContext, streamingResult);
            if (step instanceof Step.Fatal(TaskResult.StopDetails details)) {
                stopDetails = details;
                break;
            }
            loopContext = step.loopContext();           // Step.Continue

            // ----- 1-d.  PARSE PHASE -----------------------------------------
            step = parsePhase(loopContext,
                              streamingResult.text(),
                              streamingResult.isPartial(),
                              parser);
            if (step instanceof Step.Retry retry) {
                loopContext = retry.loopContext();
                continue;                               // back to while-loop top
            }
            if (step instanceof Step.Fatal(TaskResult.StopDetails details)) {
                stopDetails = details;
                break;
            }
            loopContext = step.loopContext();

            // ----- 1-e.  APPLY PHASE -----------------------------------------
            step = applyPhase(loopContext, parser);
            if (step instanceof Step.Retry retry2) {
                loopContext = retry2.loopContext();
                continue;
            }
            if (step instanceof Step.Fatal fatal3) {
                stopDetails = fatal3.stopDetails();
                break;
            }
            loopContext = step.loopContext();

            // ----- 1-f.  Termination checks ----------------------------------
            if (loopContext.editState().pendingBlocks().isEmpty()) {
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
                break;
            }

            if (Thread.currentThread().isInterrupted()) {
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
                break;
            }
        }

        // 2.  Produce TaskResult
        assert stopDetails != null;
        var finalMessages = prepareMessagesForTaskEntryLog(io.getLlmRawMessages());

        String finalAction = (stopDetails.reason() == TaskResult.StopReason.SUCCESS)
                             ? instructions
                             : instructions + " [" + stopDetails.reason().name() + "]";

        return new TaskResult("Code: " + finalAction,
                              new ContextFragment.TaskFragment(contextManager, finalMessages, instructions),
                              loopContext.editState().changedFiles(),
                              stopDetails);
    }
    
    void report(String message) {
        logger.debug(message);
        io.llmOutput("\n" + message, ChatMessageType.CUSTOM);
    }

    void reportComplete(String message) {
        logger.debug(message);
        io.llmOutput("\n# Code Agent Finished\n" + message, ChatMessageType.CUSTOM);
    }

    Step parsePhase(LoopContext currentLoopContext, String llmText, boolean isPartialResponse, EditBlockParser parser) {
        var cs = currentLoopContext.conversationState();
        var ws = currentLoopContext.editState();

        logger.debug("Got response (potentially partial if LLM connection was cut off)");
        var parseResult = parser.parseEditBlocks(llmText, contextManager.getRepo().getTrackedFiles());
        var newlyParsedBlocks = parseResult.blocks();

        // Handle explicit parse errors from the parser
        if (parseResult.parseError() != null) {
            int updatedConsecutiveParseFailures = ws.consecutiveParseFailures();
            UserMessage messageForRetry;
            String consoleLogForRetry;
            if (newlyParsedBlocks.isEmpty()) { 
                // Pure parse failure
                updatedConsecutiveParseFailures++;
                messageForRetry = new UserMessage(parseResult.parseError());
                consoleLogForRetry = "Failed to parse LLM response; retrying";
            } else { 
                // Partial parse, then an error
                updatedConsecutiveParseFailures = 0; // Reset, as we got some good blocks.
                messageForRetry = new UserMessage(getContinueFromLastBlockPrompt(newlyParsedBlocks.getLast()));
                consoleLogForRetry = "Malformed or incomplete response after %d blocks parsed; asking LLM to continue/fix".formatted(newlyParsedBlocks.size());
            }

            if (updatedConsecutiveParseFailures > MAX_PARSE_ATTEMPTS) {
                reportComplete("Parse error limit reached; ending task.");
                return new Step.Fatal(new TaskResult.StopDetails(TaskResult.StopReason.PARSE_ERROR));
            }

            var nextCs = new ConversationState(cs.taskMessages(), messageForRetry, cs.originalWorkspaceEditableMessages());
            // Add any newly parsed blocks before the error to the pending list for the next apply phase
            var nextPending = new ArrayList<>(ws.pendingBlocks());
            nextPending.addAll(newlyParsedBlocks);
            var nextWs = ws.withPendingBlocks(nextPending, updatedConsecutiveParseFailures);
            report(consoleLogForRetry);
            return new Step.Retry(new LoopContext(nextCs, nextWs, currentLoopContext.userGoal()));
        }

        // No explicit parse error. Reset counter. Add newly parsed blocks to the pending list.
        var updatedConsecutiveParseFailures = 0;
        var mutablePendingBlocks = new ArrayList<>(ws.pendingBlocks());
        mutablePendingBlocks.addAll(newlyParsedBlocks);

        // Handle case where LLM response was cut short, even if syntactically valid so far.
        if (isPartialResponse) {
            UserMessage messageForRetry;
            String consoleLogForRetry;
            if (newlyParsedBlocks.isEmpty()) {
                messageForRetry = new UserMessage("It looks like the response was cut off before you provided any code blocks. Please continue with your response.");
                consoleLogForRetry = "LLM indicated response was partial before any blocks (no parse error); asking to continue";
            } else {
                messageForRetry = new UserMessage(getContinueFromLastBlockPrompt(newlyParsedBlocks.getLast()));
                consoleLogForRetry = "LLM indicated response was partial after %d clean blocks; asking to continue".formatted(newlyParsedBlocks.size());
            }
            var nextCs = new ConversationState(cs.taskMessages(), messageForRetry, cs.originalWorkspaceEditableMessages());
            var nextWs = ws.withPendingBlocks(mutablePendingBlocks, updatedConsecutiveParseFailures);
            report(consoleLogForRetry);
            return new Step.Retry(new LoopContext(nextCs, nextWs, currentLoopContext.userGoal()));
        }

        // No parse error, not a partial response. This is a successful, complete segment.
        var nextWs = ws.withPendingBlocks(mutablePendingBlocks, updatedConsecutiveParseFailures);
        return new Step.Continue(new LoopContext(cs, nextWs, currentLoopContext.userGoal()));
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

        report("Attempting full file replacement for: " + failuresByFile.keySet().stream().map(ProjectFile::toString).collect(Collectors.joining(", ")));
        
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
    private static List<ChatMessage> prepareMessagesForTaskEntryLog(List<ChatMessage> rawMessages) {
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

    static class EditStopException extends RuntimeException {
        final TaskResult.StopDetails stopDetails;

        public EditStopException(TaskResult.StopDetails stopDetails) {
            super(stopDetails.reason().name() + ": " + stopDetails.explanation());
            this.stopDetails = stopDetails;
        }

        public EditStopException(TaskResult.StopReason stopReason) {
            this(new TaskResult.StopDetails(stopReason));
        }
    }

    Step requestPhase(LoopContext currentLoopContext, StreamingResult streamingResultFromLlm) {
        var cs = currentLoopContext.conversationState();

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

        // Append request and AI message to taskMessages
        cs.taskMessages().add(cs.nextRequest());
        cs.taskMessages().add(streamingResultFromLlm.aiMessage());

        return new Step.Continue(currentLoopContext);
    }

    private EditBlock.EditResult applyBlocksAndHandleErrors(List<EditBlock.SearchReplaceBlock> blocksToApply,
                                                            Set<ProjectFile> changedFilesCollector)
            throws EditStopException, InterruptedException
    {
        // Identify files referenced by blocks that are not already editable
        var filesToAdd = blocksToApply.stream()
                .map(EditBlock.SearchReplaceBlock::filename)
                .filter((@Nullable var f) -> f != null)
                .distinct()
                .map(contextManager::toFile) // Convert filename string to ProjectFile
                .filter(file -> !contextManager.getEditableFiles().contains(file))
                .toList();
        // Check for conflicts with read-only files
        var readOnlyFiles = filesToAdd.stream()
                .filter(file -> contextManager.getReadonlyProjectFiles().contains(file))
                .toList();
        if (!readOnlyFiles.isEmpty()) {
            var msg = "LLM attempted to edit read-only file(s): %s.\nNo edits applied. Mark the file(s) editable or clarify the approach."
                    .formatted(readOnlyFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(",")));
            reportComplete(msg);
            var filenames = readOnlyFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(","));
            throw new EditStopException(new TaskResult.StopDetails(TaskResult.StopReason.READ_ONLY_EDIT, filenames));
        }

        // Pre-create empty files for any new files from the current LLM response segment
        // (and add to git + workspace). This prevents UI race conditions.
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
        return editResult;
    }

    Step verifyPhase(LoopContext loopContext) {
        var cs = loopContext.conversationState();
        var ws = loopContext.editState();

        // Plan Invariant 3: Verify only runs when editsSinceLastBuild > 0.
        if (ws.blocksAppliedWithoutBuild() == 0) {
            reportComplete("No edits found or applied in response, and no changes since last build; ending task.");
            TaskResult.StopDetails stopDetails;
            if (loopContext.editState().lastBuildError().isEmpty()) {
                var text = Messages.getText(loopContext.conversationState().taskMessages.getLast());
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS, text);
            } else {
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.BUILD_ERROR, loopContext.editState().lastBuildError());
            }
            return new Step.Fatal(stopDetails);
        }

        String latestBuildError;
        try {
            latestBuildError = performBuildVerification();
        } catch (InterruptedException e) {
            logger.debug("CodeAgent interrupted during build verification.");
            Thread.currentThread().interrupt();
            return new Step.Fatal(new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED));
        }

        if (latestBuildError.isEmpty()) {
            // Build succeeded or was skipped by performBuildVerification
            reportComplete("Success!");
            return new Step.Fatal(TaskResult.StopReason.SUCCESS);
        } else { 
            // Build failed
            UserMessage nextRequestForBuildFailure = new UserMessage(formatBuildErrorsForLLM(latestBuildError));
            var newCs = new ConversationState(
                cs.taskMessages(),
                nextRequestForBuildFailure,
                cs.originalWorkspaceEditableMessages()
            );
            var newWs = ws.afterBuildFailure(latestBuildError);
            report("Asking LLM to fix build/lint failures");
            return new Step.Retry(new LoopContext(newCs, newWs, loopContext.userGoal()));
        }
    }

    Step applyPhase(LoopContext currentLoopContext, EditBlockParser parser) {
        var cs = currentLoopContext.conversationState();
        var ws = currentLoopContext.editState();

        if (ws.pendingBlocks().isEmpty()) {
            logger.debug("nothing to apply, continuing to next phase");
            return new Step.Continue(currentLoopContext);
        }

        EditBlock.EditResult editResult;
        int updatedConsecutiveApplyFailures = ws.consecutiveApplyFailures();
        EditState wsForStep = ws; // Will be updated
        ConversationState csForStep = cs; // Will be updated

        try {
            editResult = applyBlocksAndHandleErrors(ws.pendingBlocks(),
                                                    ws.changedFiles() /* Helper mutates this set */);

            int attemptedBlockCount = ws.pendingBlocks().size();
            int succeededCount = attemptedBlockCount - editResult.failedBlocks().size();
            int newBlocksAppliedWithoutBuild = ws.blocksAppliedWithoutBuild() + succeededCount;

            // Update originalFileContents in the workspace state being built for the next step
            Map<ProjectFile, String> nextOriginalFileContents = new HashMap<>(ws.originalFileContents());
            editResult.originalContents().forEach(nextOriginalFileContents::putIfAbsent);

            List<EditBlock.SearchReplaceBlock> nextPendingBlocks = new ArrayList<>(); // Blocks are processed, so clear for next step's ws

            if (!editResult.failedBlocks().isEmpty()) { // Some blocks failed the direct apply
                if (succeededCount == 0) { // Total failure for this batch of pendingBlocks
                    updatedConsecutiveApplyFailures++;
                } else { // Partial success
                    updatedConsecutiveApplyFailures = 0;
                }

                if (updatedConsecutiveApplyFailures >= MAX_APPLY_FAILURES_BEFORE_FALLBACK) {
                    report("Apply failure limit reached (%d), attempting full file replacement fallback.".formatted(updatedConsecutiveApplyFailures));
                    List<EditBlock.FailedBlock> blocksForFallback = List.copyOf(editResult.failedBlocks());
                    attemptFullFileReplacements(blocksForFallback, currentLoopContext.userGoal(), cs.taskMessages());
                    // If attemptFullFileReplacements succeeds, it doesn't throw. If it fails, it throws EditStopException caught below.
                    report("Full file replacement fallback successful.");

                    // Update changedFiles with files modified by fallback
                    Set<ProjectFile> updatedChangedFiles = new HashSet<>(ws.changedFiles());
                    blocksForFallback.stream()
                        .filter(fb -> fb.block().filename() != null) // Ensure filename is not null
                        .map(fb -> contextManager.toFile(requireNonNull(fb.block().filename()))) // Now safe to call requireNonNull
                        .forEach(updatedChangedFiles::add);
                    
                    UserMessage placeholderPrompt = new UserMessage("[Placeholder: Build errors will be inserted here by verifyPhase if build fails after this full file replacement]");
                    csForStep = new ConversationState(cs.taskMessages(), placeholderPrompt, cs.originalWorkspaceEditableMessages());
                    
                    wsForStep = ws.afterFallbackSuccess(nextPendingBlocks, updatedChangedFiles, nextOriginalFileContents);
                    return new Step.Continue(new LoopContext(csForStep, wsForStep, currentLoopContext.userGoal()));
                } else { // Apply failed, but not yet time for full fallback -> ask LLM to retry
                    String retryPromptText = CodePrompts.getApplyFailureMessage(editResult.failedBlocks(), parser, succeededCount, contextManager);
                    UserMessage retryRequest = new UserMessage(retryPromptText);
                    csForStep = new ConversationState(cs.taskMessages(), retryRequest, cs.originalWorkspaceEditableMessages());
                    wsForStep = ws.afterApply(nextPendingBlocks, updatedConsecutiveApplyFailures, newBlocksAppliedWithoutBuild, nextOriginalFileContents);
                    report("Failed to apply %s block(s), asking LLM to retry".formatted(editResult.failedBlocks().size()));
                    return new Step.Retry(new LoopContext(csForStep, wsForStep, currentLoopContext.userGoal()));
                }
            } else { // All blocks from ws.pendingBlocks() applied successfully
                if (succeededCount > 0) {
                    report(succeededCount + " SEARCH/REPLACE blocks applied.");
                }
                updatedConsecutiveApplyFailures = 0; // Reset on success
                wsForStep = ws.afterApply(nextPendingBlocks, updatedConsecutiveApplyFailures, newBlocksAppliedWithoutBuild, nextOriginalFileContents);
                return new Step.Continue(new LoopContext(csForStep, wsForStep, currentLoopContext.userGoal()));
            }

        } catch (EditStopException e) {
            // Handle exceptions from findConflicts, preCreateNewFiles (if it threw that), applyEditBlocks (IO), or attemptFullFileReplacements failure
            // Log appropriate messages based on e.stopDetails.reason()
            if (e.stopDetails.reason() == TaskResult.StopReason.READ_ONLY_EDIT) {
                // already reported by applyBlocksAndHandleErrors
            } else if (e.stopDetails.reason() == TaskResult.StopReason.IO_ERROR) {
                io.toolError(requireNonNull(e.stopDetails.explanation()));
            } else if (e.stopDetails.reason() == TaskResult.StopReason.APPLY_ERROR) {
                reportComplete(e.stopDetails.explanation());
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
        if (verificationCommand == null || verificationCommand.isBlank()) {
            report("No verification command specified, skipping build/check.");
            return "";
        }

        io.llmOutput("\nRunning verification command: " + verificationCommand, ChatMessageType.CUSTOM);
        io.llmOutput("\n```bash\n", ChatMessageType.CUSTOM);
        try {
            var output = Environment.instance.runShellCommand(verificationCommand,
                                                              contextManager.getProject().getRoot(),
                                                              line -> io.llmOutput(line + "\n", ChatMessageType.CUSTOM));
            logger.debug("Verification command successful. Output: {}", output);
            io.llmOutput("\n```", ChatMessageType.CUSTOM);
            return "";
        } catch (Environment.SubprocessException e) {
            io.llmOutput("\n```", ChatMessageType.CUSTOM); // Close the markdown block
            // Add the combined error and output to the history for the next request
            return e.getMessage() + "\n\n" + e.getOutput();
        }
    }

    record LoopContext(
            ConversationState conversationState,
            EditState editState,
            String userGoal
    ) {}

    sealed interface Step permits Step.Continue, Step.Retry, Step.Fatal {
        LoopContext loopContext();

        /** continue to the next phase */
        record Continue(LoopContext loopContext) implements Step {}
        /** this phase found a problem that it wants to send back to the llm */
        record Retry(LoopContext loopContext) implements Step {}
        /** fatal error, stop the task */
        record Fatal(TaskResult.StopDetails stopDetails) implements Step {
            public Fatal(TaskResult.StopReason stopReason) {
                this(new TaskResult.StopDetails(stopReason));
            }

            @Override
            public LoopContext loopContext() {
                throw new UnsupportedOperationException("Fatal step does not have a loop context.");
            }
        }
    }

    record ConversationState(
            List<ChatMessage> taskMessages,
            UserMessage nextRequest,
            List<ChatMessage> originalWorkspaceEditableMessages
    ) {}

    record EditState(
            // parsed but not yet applied
            List<EditBlock.SearchReplaceBlock> pendingBlocks,
            int consecutiveParseFailures,
            int consecutiveApplyFailures,
            int blocksAppliedWithoutBuild,
            String lastBuildError,
            Set<ProjectFile> changedFiles,
            Map<ProjectFile, String> originalFileContents
    ) {
        /**
         * Returns a new WorkspaceState with updated pending blocks and parse failures.
         */
        EditState withPendingBlocks(List<EditBlock.SearchReplaceBlock> newPendingBlocks, int newParseFailures)
        {
            return new EditState(newPendingBlocks, newParseFailures, consecutiveApplyFailures,
                                 blocksAppliedWithoutBuild, lastBuildError, changedFiles, originalFileContents);
        }
        
        /**
         * Returns a new WorkspaceState after a build failure, updating the error message.
         */
        EditState afterBuildFailure(String newBuildError)
        {
            return new EditState(pendingBlocks, consecutiveParseFailures, consecutiveApplyFailures,
                                 0, newBuildError, changedFiles, originalFileContents);
        }

        /**
         * Returns a new WorkspaceState after applying blocks, updating relevant fields.
         */
        EditState afterApply(List<EditBlock.SearchReplaceBlock> newPendingBlocks, int newApplyFailures,
                             int newBlocksApplied, Map<ProjectFile, String> newOriginalContents)
        {
            return new EditState(newPendingBlocks, consecutiveParseFailures, newApplyFailures,
                                 newBlocksApplied, lastBuildError, changedFiles, newOriginalContents);
        }

        /**
         * Returns a new WorkspaceState after a successful full-file replacement fallback.
         */
        EditState afterFallbackSuccess(List<EditBlock.SearchReplaceBlock> newPendingBlocks,
                                       Set<ProjectFile> updatedChangedFiles,
                                       Map<ProjectFile, String> newOriginalContents)
        {
            return new EditState(newPendingBlocks, consecutiveParseFailures, 0,
                                 1, lastBuildError, updatedChangedFiles, newOriginalContents);
        }
    }
}
