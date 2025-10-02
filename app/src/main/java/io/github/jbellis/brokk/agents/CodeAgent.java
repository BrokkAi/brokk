package io.github.jbellis.brokk.agents;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.difflib.patch.AbstractDelta;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.Llm.StreamingResult;
import io.github.jbellis.brokk.analyzer.Languages;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.prompts.QuickEditPrompts;
import io.github.jbellis.brokk.util.LogDescription;
import io.github.jbellis.brokk.util.Messages;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * Manages interactions with a Language Model (LLM) to generate and apply code modifications based on user instructions.
 * It handles parsing LLM responses, applying edits to files, verifying changes through build/test commands, and
 * managing the conversation history. It supports both iterative coding tasks (potentially involving multiple LLM
 * interactions and build attempts) and quick, single-shot edits.
 */
public class CodeAgent {
    private static final Logger logger = LogManager.getLogger(CodeAgent.class);
    private static final int MAX_PARSE_ATTEMPTS = 3;

    @VisibleForTesting
    static final int MAX_APPLY_FAILURES = 3;
    /** maximum consecutive build failures before giving up */
    @VisibleForTesting
    static final int MAX_BUILD_FAILURES = 5;

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

    public enum Option {
        DEFER_BUILD
    }

    /**
     * @param userInput The user's goal/instructions.
     * @return A TaskResult containing the conversation history and original file contents
     */
    public TaskResult runTask(String userInput, Set<Option> options) {
        // pause watching for external changes (so they don't get added to activity history while we're still making
        // changes);
        // this means that we're responsible for refreshing the analyzer when we make changes
        contextManager.getAnalyzerWrapper().pause();
        try {
            return runTaskInternal(userInput, options);
        } finally {
            contextManager.getAnalyzerWrapper().resume();
        }
    }

    private TaskResult runTaskInternal(String userInput, Set<Option> options) {
        var collectMetrics = "true".equalsIgnoreCase(System.getenv("BRK_CODEAGENT_METRICS"));
        @Nullable Metrics metrics = collectMetrics ? new Metrics() : null;

        // Create Coder instance with the user's input as the task description
        var io = contextManager.getIo();
        var coder = contextManager.getLlm(model, "Code: " + userInput, true);
        coder.setOutput(io);

        // Track changed files
        var changedFiles = new HashSet<ProjectFile>();

        // Retry-loop state tracking
        int applyFailures = 0;
        int blocksAppliedWithoutBuild = 0;

        String buildError = "";
        var blocks = new ArrayList<EditBlock.SearchReplaceBlock>(); // This will be part of WorkspaceState
        Map<ProjectFile, String> originalFileContents = new HashMap<>();

        var msg = "Code Agent engaged: `%s...`".formatted(LogDescription.getShortDescription(userInput));
        io.systemOutput(msg);
        TaskResult.StopDetails stopDetails;

        var parser = EditBlockParser.instance;
        // We'll collect the conversation as ChatMessages to store in context history.
        var taskMessages = new ArrayList<ChatMessage>();
        UserMessage nextRequest = CodePrompts.instance.codeRequest(
                contextManager,
                userInput.trim(),
                CodePrompts.instance.codeReminder(contextManager.getService(), model));

        // FSM state
        var cs = new ConversationState(taskMessages, nextRequest, 0);
        var es = new EditState(
                blocks, 0, applyFailures, 0, blocksAppliedWithoutBuild, buildError, changedFiles, originalFileContents);

        while (true) {
            if (Thread.interrupted()) {
                logger.debug("CodeAgent interrupted");
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
                break;
            }

            // "Update everything in the workspace" wouldn't be necessary if we were 100% sure that the analyzer were up
            // to date
            // before we paused it, but empirically that is not the case as of this writing.
            var filesToRefresh = es.changedFiles().isEmpty() ? contextManager.getFilesInContext() : es.changedFiles();
            var analyzerFuture = contextManager.getAnalyzerWrapper().updateFiles(filesToRefresh);

            // Make the LLM request
            StreamingResult streamingResult;
            try {
                var allMessagesForLlm = CodePrompts.instance.collectCodeMessages(
                        contextManager,
                        model,
                        cs.taskMessages(),
                        requireNonNull(cs.nextRequest(), "nextRequest must be set before sending to LLM"),
                        es.changedFiles());
                var llmStartNanos = System.nanoTime();
                streamingResult = coder.sendRequest(allMessagesForLlm, true);
                if (metrics != null) {
                    metrics.llmWaitNanos += System.nanoTime() - llmStartNanos;
                    Optional.ofNullable(streamingResult.tokenUsage()).ifPresent(metrics::addTokens);
                    metrics.addApiRetries(streamingResult.retries());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                continue; // let main loop interruption check handle
            }

            // REQUEST PHASE handles the result of sendLlmRequest
            var requestOutcome = requestPhase(cs, es, streamingResult, metrics);
            if (requestOutcome instanceof Step.Fatal fatalReq) {
                stopDetails = fatalReq.stopDetails();
                break;
            }
            cs = requestOutcome.cs();
            es = requestOutcome.es();

            // PARSE PHASE parses edit blocks
            var parseOutcome = parsePhase(
                    cs,
                    es,
                    streamingResult.text(),
                    streamingResult.isPartial(),
                    parser,
                    metrics); // Ensure parser is available
            if (parseOutcome instanceof Step.Fatal fatalParse) {
                stopDetails = fatalParse.stopDetails();
                break;
            }
            if (parseOutcome instanceof Step.Retry retryParse) {
                if (metrics != null) {
                    metrics.parseRetries++;
                }
                cs = retryParse.cs();
                es = retryParse.es();
                continue; // Restart main loop
            }
            cs = parseOutcome.cs();
            es = parseOutcome.es();

            // Wait for analyzer update before applying blocks
            try {
                analyzerFuture.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                continue; // let main loop interruption check handle
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }

            // APPLY PHASE applies blocks
            var applyOutcome = applyPhase(cs, es, metrics);
            if (applyOutcome instanceof Step.Fatal fatalApply) {
                stopDetails = fatalApply.stopDetails();
                break;
            }
            if (applyOutcome instanceof Step.Retry retryApply) {
                cs = retryApply.cs();
                es = retryApply.es();
                continue; // Restart main loop
            }
            cs = applyOutcome.cs();
            es = applyOutcome.es();

            // After a successful apply, consider compacting the turn into a clean, synthetic summary.
            // Only do this if the turn had more than a single user/AI pair; for simple one-shot turns,
            // keep the original messages for clarity.
            if (es.blocksAppliedWithoutBuild() > 0) {
                int msgsThisTurn = cs.taskMessages().size() - cs.turnStartIndex();
                if (msgsThisTurn > 2) {
                    var srb = es.toSearchReplaceBlocks();
                    var summaryText = "Here are the SEARCH/REPLACE blocks:\n\n"
                            + srb.stream()
                                    .map(EditBlock.SearchReplaceBlock::repr)
                                    .collect(Collectors.joining("\n"));
                    cs = cs.replaceCurrentTurnMessages(summaryText);
                }
            }

            // PARSE-JAVA PHASE: If Java files were edited, run a parse-only check before full build
            var parseJavaOutcome = parseJavaPhase(cs, es, metrics);
            if (parseJavaOutcome instanceof Step.Retry retryJava) {
                cs = retryJava.cs();
                es = retryJava.es();
                continue;
            }
            if (parseJavaOutcome instanceof Step.Fatal fatalJava) {
                stopDetails = fatalJava.stopDetails();
                break;
            }

            // VERIFY or finish if build is deferred
            assert es.pendingBlocks().isEmpty() : es;

            if (options.contains(Option.DEFER_BUILD)) {
                reportComplete(
                        es.blocksAppliedWithoutBuild() > 0
                                ? "Edits applied. Build/check deferred."
                                : "No edits to apply. Build/check deferred.");
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
                break;
            }

            var verifyOutcome = verifyPhase(cs, es, metrics);
            if (verifyOutcome instanceof Step.Retry retryVerify) {
                cs = retryVerify.cs();
                es = retryVerify.es();
                continue;
            }
            if (verifyOutcome instanceof Step.Fatal fatalVerify) {
                stopDetails = fatalVerify.stopDetails();
                break;
            }
            throw new IllegalStateException("verifyPhase returned unexpected Step type " + verifyOutcome);
        }

        // everyone reports their own reasons for stopping, except for interruptions
        if (stopDetails.reason() == TaskResult.StopReason.INTERRUPTED) {
            reportComplete("Cancelled by user.");
        }

        if (metrics != null) {
            metrics.print(es.changedFiles(), stopDetails);
        }

        // create the Result for history
        String finalActionDescription = (stopDetails.reason() == TaskResult.StopReason.SUCCESS)
                ? userInput
                : userInput + " [" + stopDetails.reason().name() + "]";
        // architect auto-compresses the task entry so let's give it the full history to work with, quickModel is cheap
        // Prepare messages for TaskEntry log: filter raw messages and keep S/R blocks verbatim
        var finalMessages = prepareMessagesForTaskEntryLog(io.getLlmRawMessages());
        return new TaskResult(
                "Code: " + finalActionDescription,
                new ContextFragment.TaskFragment(contextManager, finalMessages, userInput),
                es.changedFiles(),
                stopDetails);
    }

    /**
     * Runs a “single-file edit” session in which the LLM is asked to modify exactly {@code file}. The method drives the
     * same request / parse / apply FSM that {@link #runTask(String, Set)} uses, but it stops after all SEARCH/REPLACE
     * blocks have been applied (no build verification is performed).
     *
     * @param file the file to edit
     * @param instructions user instructions describing the desired change
     * @param readOnlyMessages conversation context that should be provided to the LLM as read-only (e.g., other related
     *     files, build output, etc.)
     * @param flags
     * @return a {@link TaskResult} recording the conversation and the original contents of all files that were changed
     */
    public TaskResult runSingleFileEdit(
            ProjectFile file,
            String instructions,
            List<ChatMessage> readOnlyMessages,
            Set<CodePrompts.InstructionsFlags> flags) {
        // 0.  Setup: coder, parser, initial messages, and initial state
        var coder = contextManager.getLlm(model, "Code (single-file): " + instructions, true);
        coder.setOutput(io);

        EditBlockParser parser = EditBlockParser.instance;

        UserMessage initialRequest = CodePrompts.instance.codeRequest(
                contextManager, instructions, CodePrompts.instance.codeReminder(contextManager.getService(), model));

        var conversationState = new ConversationState(new ArrayList<>(), initialRequest, 0);
        var editState = new EditState(new ArrayList<>(), 0, 0, 0, 0, "", new HashSet<>(), new HashMap<>());

        logger.debug("Code Agent engaged in single-file mode for %s: `%s…`"
                .formatted(file.getFileName(), LogDescription.getShortDescription(instructions)));

        TaskResult.StopDetails stopDetails;

        // 1.  Main FSM loop (request → parse → apply)
        while (true) {
            // ----- 1-a.  Construct messages for this turn --------------------
            List<ChatMessage> llmMessages = CodePrompts.instance.getSingleFileCodeMessages(
                    contextManager.getProject(),
                    readOnlyMessages,
                    conversationState.taskMessages(),
                    requireNonNull(conversationState.nextRequest(), "nextRequest must be set before sending to LLM"),
                    file);

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
            var step = requestPhase(conversationState, editState, streamingResult, null);
            if (step instanceof Step.Fatal(TaskResult.StopDetails details)) {
                stopDetails = details;
                break;
            }
            conversationState = step.cs();
            editState = step.es();

            // ----- 1-d.  PARSE PHASE -----------------------------------------
            step = parsePhase(
                    conversationState, editState, streamingResult.text(), streamingResult.isPartial(), parser, null);
            if (step instanceof Step.Retry retry) {
                conversationState = retry.cs();
                editState = retry.es();
                continue; // back to while-loop top
            }
            if (step instanceof Step.Fatal(TaskResult.StopDetails details)) {
                stopDetails = details;
                break;
            }
            conversationState = step.cs();
            editState = step.es();

            // ----- 1-e.  APPLY PHASE -----------------------------------------
            step = applyPhase(conversationState, editState, null);
            if (step instanceof Step.Retry retry2) {
                conversationState = retry2.cs();
                editState = retry2.es();
                continue;
            }
            if (step instanceof Step.Fatal fatal3) {
                stopDetails = fatal3.stopDetails();
                break;
            }
            conversationState = step.cs();
            editState = step.es();

            // ----- 1-e.5.  PARSE-JAVA PHASE ----------------------------------
            step = parseJavaPhase(conversationState, editState, null);
            if (step instanceof Step.Retry retryJava) {
                conversationState = retryJava.cs();
                editState = retryJava.es();
                continue;
            }
            if (step instanceof Step.Fatal fatalJava) {
                stopDetails = fatalJava.stopDetails();
                break;
            }
            conversationState = step.cs();
            editState = step.es();

            // ----- 1-f.  Termination checks ----------------------------------
            if (editState.pendingBlocks().isEmpty()) {
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

        return new TaskResult(
                "Code: " + finalAction,
                new ContextFragment.TaskFragment(contextManager, finalMessages, instructions),
                editState.changedFiles(),
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

    Step parsePhase(
            ConversationState cs,
            EditState es,
            String llmText,
            boolean isPartialResponse,
            EditBlockParser parser,
            @Nullable Metrics metrics) {
        logger.debug("Got response (potentially partial if LLM connection was cut off)");
        var parseResult =
                parser.parseEditBlocks(llmText, contextManager.getRepo().getTrackedFiles());
        var newlyParsedBlocks = parseResult.blocks();
        if (metrics != null) {
            metrics.totalEditBlocks += newlyParsedBlocks.size();
        }

        // Handle explicit parse errors from the parser
        if (parseResult.parseError() != null) {
            int updatedConsecutiveParseFailures = es.consecutiveParseFailures();
            UserMessage messageForRetry;
            String consoleLogForRetry;
            if (newlyParsedBlocks.isEmpty()) {
                // Pure parse failure
                updatedConsecutiveParseFailures++;

                // The bad response is the last message; the user request that caused it is the one before that.
                // We will remove both, and create a new request that is the original + a reminder.
                cs.taskMessages().removeLast(); // bad AI response
                var lastRequest = (UserMessage) cs.taskMessages().removeLast(); // original user request

                var reminder =
                        "Remember to pay close attention to the SEARCH/REPLACE block format instructions and examples!";
                var newRequestText = Messages.getText(lastRequest) + "\n\n" + reminder;
                messageForRetry = new UserMessage(newRequestText);
                consoleLogForRetry = "Failed to parse LLM response; retrying with format reminder";
            } else {
                // Partial parse, then an error
                updatedConsecutiveParseFailures = 0; // Reset, as we got some good blocks.
                messageForRetry = new UserMessage(getContinueFromLastBlockPrompt(newlyParsedBlocks.getLast()));
                consoleLogForRetry =
                        "Malformed or incomplete response after %d blocks parsed; asking LLM to continue/fix"
                                .formatted(newlyParsedBlocks.size());
            }

            if (updatedConsecutiveParseFailures > MAX_PARSE_ATTEMPTS) {
                reportComplete("Parse error limit reached; ending task.");
                return new Step.Fatal(new TaskResult.StopDetails(
                        TaskResult.StopReason.PARSE_ERROR, "Parse error limit reached; ending task."));
            }

            var nextCs = new ConversationState(cs.taskMessages(), messageForRetry, cs.turnStartIndex());
            // Add any newly parsed blocks before the error to the pending list for the next apply phase
            var nextPending = new ArrayList<>(es.pendingBlocks());
            nextPending.addAll(newlyParsedBlocks);
            var nextEs = es.withPendingBlocks(nextPending, updatedConsecutiveParseFailures);
            report(consoleLogForRetry);
            return new Step.Retry(nextCs, nextEs);
        }

        // No explicit parse error. Reset counter. Add newly parsed blocks to the pending list.
        int updatedConsecutiveParseFailures = 0;
        var mutablePendingBlocks = new ArrayList<>(es.pendingBlocks());
        mutablePendingBlocks.addAll(newlyParsedBlocks);

        // Handle case where LLM response was cut short, even if syntactically valid so far.
        if (isPartialResponse) {
            UserMessage messageForRetry;
            String consoleLogForRetry;
            if (newlyParsedBlocks.isEmpty()) {
                // Treat "partial with no blocks" as a parse failure subject to MAX_PARSE_ATTEMPTS
                updatedConsecutiveParseFailures = es.consecutiveParseFailures() + 1;
                if (updatedConsecutiveParseFailures > MAX_PARSE_ATTEMPTS) {
                    reportComplete("Parse error limit reached; ending task.");
                    return new Step.Fatal(new TaskResult.StopDetails(
                            TaskResult.StopReason.PARSE_ERROR, "Parse error limit reached; ending task."));
                }
                messageForRetry = new UserMessage(
                        "It looks like the response was cut off before you provided any code blocks. Please continue with your response.");
                consoleLogForRetry =
                        "LLM indicated response was partial before any blocks; counting as parse failure and asking to continue";
            } else {
                messageForRetry = new UserMessage(getContinueFromLastBlockPrompt(newlyParsedBlocks.getLast()));
                consoleLogForRetry = "LLM indicated response was partial after %d clean blocks; asking to continue"
                        .formatted(newlyParsedBlocks.size());
            }
            var nextCs = new ConversationState(cs.taskMessages(), messageForRetry, cs.turnStartIndex());
            var nextEs = es.withPendingBlocks(mutablePendingBlocks, updatedConsecutiveParseFailures);
            report(consoleLogForRetry);
            return new Step.Retry(nextCs, nextEs);
        }

        // No parse error, not a partial response. This is a successful, complete segment.
        var nextEs = es.withPendingBlocks(mutablePendingBlocks, updatedConsecutiveParseFailures);
        return new Step.Continue(cs, nextEs);
    }

    /**
     * Prepares messages for storage in a TaskEntry. This involves filtering raw LLM I/O to keep USER, CUSTOM, and AI
     * messages. AI messages containing SEARCH/REPLACE blocks will have their raw text preserved, rather than converting
     * blocks to HTML placeholders or summarizing block-only messages.
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
     * Runs a quick-edit task where we: 1) Gather the entire file content plus related context (buildAutoContext) 2) Use
     * QuickEditPrompts to ask for a single fenced code snippet 3) Replace the old text with the new snippet in the file
     *
     * @return A TaskResult containing the conversation and original content.
     */
    public TaskResult runQuickTask(ProjectFile file, String oldText, String instructions) throws InterruptedException {
        var coder = contextManager.getLlm(model, "QuickEdit: " + instructions);
        coder.setOutput(io);

        // Use up to 5 related classes as context
        // buildAutoContext is an instance method on Context, or a static helper on ContextFragment for SkeletonFragment
        // directly
        var relatedCode = contextManager.liveContext().buildAutoContext(5);

        String fileContents = file.read().orElse("");

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
        } else {
            // Success from LLM perspective
            pendingHistory.add(result.aiMessage());
            stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
        }

        // Return TaskResult containing conversation and original content
        return new TaskResult(
                "Quick Edit: " + file.getFileName(),
                new ContextFragment.TaskFragment(contextManager, pendingHistory, "Quick Edit: " + file.getFileName()),
                Set.of(file),
                stopDetails);
    }

    /**
     * Generates a user message to ask the LLM to continue when a response appears to be cut off.
     *
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
                """
                .stripIndent()
                .formatted(lastBlock);
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

    /**
     * Appends request + AI message to the conversation or ends on error. After successfully recording the exchange,
     * this method returns a ConversationState where {@code nextRequest} has been nulled out to enforce single-use
     * semantics (see TODO resolution).
     */
    Step requestPhase(
            ConversationState cs, EditState es, StreamingResult streamingResultFromLlm, @Nullable Metrics metrics) {
        var llmError = streamingResultFromLlm.error();
        if (llmError != null) {
            String message;
            TaskResult.StopDetails fatalDetails;
            message = "LLM returned an error even after retries: " + llmError.getMessage() + ". Ending task";
            fatalDetails =
                    new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, requireNonNull(llmError.getMessage()));
            io.toolError(message);
            return new Step.Fatal(fatalDetails);
        }

        // Append request and AI message to taskMessages
        cs.taskMessages().add(requireNonNull(cs.nextRequest(), "nextRequest must be non-null when recording request"));
        cs.taskMessages().add(streamingResultFromLlm.aiMessage());

        // Null out nextRequest after use (Task 3)
        var nextCs = new ConversationState(cs.taskMessages(), null, cs.turnStartIndex());
        return new Step.Continue(nextCs, es);
    }

    private EditBlock.EditResult applyBlocksAndHandleErrors(
            List<EditBlock.SearchReplaceBlock> blocksToApply, Set<ProjectFile> changedFilesCollector)
            throws EditStopException, InterruptedException {

        EditBlock.EditResult editResult;
        try {
            editResult = EditBlock.apply(contextManager, io, blocksToApply);
        } catch (IOException e) {
            var eMessage = requireNonNull(e.getMessage());
            // io.toolError is handled by caller if this exception propagates
            throw new EditStopException(new TaskResult.StopDetails(TaskResult.StopReason.IO_ERROR, eMessage));
        }

        changedFilesCollector.addAll(editResult.originalContents().keySet());
        return editResult;
    }

    Step verifyPhase(ConversationState cs, EditState es, @Nullable Metrics metrics) {
        // Plan Invariant 3: Verify only runs when editsSinceLastBuild > 0.
        if (es.blocksAppliedWithoutBuild() == 0) {
            reportComplete("No edits found or applied in response, and no changes since last build; ending task.");
            TaskResult.StopDetails stopDetails;
            if (es.lastBuildError().isEmpty()) {
                var text = Messages.getText(cs.taskMessages().getLast());
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS, text);
            } else {
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.BUILD_ERROR, es.lastBuildError());
            }
            return new Step.Fatal(stopDetails);
        }

        String buildError;
        try {
            buildError = BuildAgent.runVerification(contextManager);
        } catch (InterruptedException e) {
            logger.debug("CodeAgent interrupted during build verification.");
            Thread.currentThread().interrupt();
            return new Step.Fatal(new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED));
        }

        // Base success/failure decision on raw build result, not processed output
        if (buildError.isEmpty()) {
            // Build succeeded or was skipped by performBuildVerification
            logger.debug("Build verification succeeded");
            reportComplete("Success!");
            return new Step.Fatal(TaskResult.StopReason.SUCCESS);
        } else {
            // Build failed - use raw error for decisions, sanitized for storage, processed for LLM context
            if (metrics != null) {
                metrics.buildFailures++;
            }

            int newBuildFailures = es.consecutiveBuildFailures() + 1;
            if (newBuildFailures >= MAX_BUILD_FAILURES) {
                reportComplete("Build failed %d consecutive times; aborting.".formatted(newBuildFailures));
                return new Step.Fatal(new TaskResult.StopDetails(
                        TaskResult.StopReason.BUILD_ERROR,
                        "Build failed %d consecutive times:\n%s".formatted(newBuildFailures, buildError)));
            }
            // Use processed output for LLM context, but fallback to sanitized if pipeline processing returned empty
            UserMessage nextRequestForBuildFailure = new UserMessage(CodePrompts.buildFeedbackPrompt());
            var newCs = new ConversationState(
                    cs.taskMessages(),
                    nextRequestForBuildFailure,
                    cs.taskMessages().size());
            var newEs = es.afterBuildFailure(buildError);
            report("Asking LLM to fix build/lint failures");
            return new Step.Retry(newCs, newEs);
        }
    }

    Step applyPhase(ConversationState cs, EditState es, @Nullable Metrics metrics) {
        if (es.pendingBlocks().isEmpty()) {
            logger.debug("nothing to apply, continuing to next phase");
            return new Step.Continue(cs, es);
        }

        EditBlock.EditResult editResult;
        int updatedConsecutiveApplyFailures = es.consecutiveApplyFailures();
        EditState esForStep = es; // Will be updated
        ConversationState csForStep = cs; // Will be updated

        try {
            editResult =
                    applyBlocksAndHandleErrors(es.pendingBlocks(), es.changedFiles() /* Helper mutates this set */);

            int attemptedBlockCount = es.pendingBlocks().size();
            var failedBlocks = editResult.failedBlocks();
            if (metrics != null) {
                metrics.failedEditBlocks += failedBlocks.size();
            }
            int succeededCount = attemptedBlockCount - failedBlocks.size();
            int newBlocksAppliedWithoutBuild = es.blocksAppliedWithoutBuild() + succeededCount;

            // Update originalFileContents in the workspace state being built for the next step
            Map<ProjectFile, String> nextOriginalFileContents = new HashMap<>(es.originalFileContents());
            editResult.originalContents().forEach(nextOriginalFileContents::putIfAbsent);

            List<EditBlock.SearchReplaceBlock> nextPendingBlocks =
                    new ArrayList<>(); // Blocks are processed, so clear for next step's ws

            if (!failedBlocks.isEmpty()) { // Some blocks failed the direct apply
                if (succeededCount == 0) { // Total failure for this batch of pendingBlocks
                    updatedConsecutiveApplyFailures++;
                } else { // Partial success
                    updatedConsecutiveApplyFailures = 0;
                }

                if (updatedConsecutiveApplyFailures >= MAX_APPLY_FAILURES) {
                    var files = failedBlocks.stream()
                            .map(b -> b.block().rawFileName())
                            .collect(Collectors.joining(","));
                    var detailMsg = "Apply failed %d consecutive times; unable to apply %d blocks to %s"
                            .formatted(updatedConsecutiveApplyFailures, failedBlocks.size(), files);
                    reportComplete(
                            "Apply failed %d consecutive times; aborting.".formatted(updatedConsecutiveApplyFailures));
                    var details = new TaskResult.StopDetails(TaskResult.StopReason.APPLY_ERROR, detailMsg);
                    return new Step.Fatal(details);
                } else { // Apply failed, but not yet time for full fallback -> ask LLM to retry
                    if (metrics != null) {
                        metrics.applyRetries++;
                    }
                    String retryPromptText = CodePrompts.getApplyFailureMessage(failedBlocks, succeededCount);
                    UserMessage retryRequest = new UserMessage(retryPromptText);
                    csForStep = new ConversationState(cs.taskMessages(), retryRequest, cs.turnStartIndex());
                    esForStep = es.afterApply(
                            nextPendingBlocks,
                            updatedConsecutiveApplyFailures,
                            newBlocksAppliedWithoutBuild,
                            nextOriginalFileContents);
                    report("Failed to apply %s block(s), asking LLM to retry".formatted(failedBlocks.size()));
                    return new Step.Retry(csForStep, esForStep);
                }
            } else { // All blocks from es.pendingBlocks() applied successfully
                if (succeededCount > 0) {
                    report(succeededCount + " SEARCH/REPLACE blocks applied.");
                }
                updatedConsecutiveApplyFailures = 0; // Reset on success
                esForStep = es.afterApply(
                        nextPendingBlocks,
                        updatedConsecutiveApplyFailures,
                        newBlocksAppliedWithoutBuild,
                        nextOriginalFileContents);
                return new Step.Continue(csForStep, esForStep);
            }
        } catch (EditStopException e) {
            // Handle exceptions from findConflicts, preCreateNewFiles (if it threw that), or applyEditBlocks (IO)
            // Log appropriate messages based on e.stopDetails.reason()
            if (e.stopDetails.reason() == TaskResult.StopReason.READ_ONLY_EDIT) {
                // already reported by applyBlocksAndHandleErrors
            } else if (e.stopDetails.reason() == TaskResult.StopReason.IO_ERROR) {
                io.toolError(e.stopDetails.explanation());
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

    /**
     * If any edited files in this turn include Java sources, run a parse-only check before attempting a full build. On
     * syntax errors, we construct a diagnostic summary and ask the LLM to fix those first.
     */
    Step parseJavaPhase(ConversationState cs, EditState es, @Nullable Metrics metrics) {
        // Only run if there were edits since the last build attempt
        if (es.blocksAppliedWithoutBuild() == 0) {
            return new Step.Continue(cs, es);
        }

        // Collect changed .java files
        var javaFiles = es.changedFiles().stream()
                .filter(f -> Languages.JAVA.getExtensions().contains(f.extension()))
                .toList();

        if (javaFiles.isEmpty()) {
            return new Step.Continue(cs, es);
        }

        // Use Eclipse JDT ASTParser without classpath/bindings
        var projectRoot = contextManager.getProject().getRoot();
        var allProblems = new ArrayList<String>();

        javaFiles.parallelStream().forEach(file -> {
            var absPath = projectRoot.resolve(file.toString());
            String src = file.read().orElse("");
            if (src.isBlank()) {
                return;
            }
            char[] sourceChars = src.toCharArray();

            ASTParser parser = ASTParser.newParser(AST.JLS24);
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setSource(sourceChars);
            // Enable binding resolution with recovery and use the running JVM's boot classpath.
            // This allows JDT to surface undefined identifier errors without requiring a project classpath.
            parser.setResolveBindings(true);
            parser.setStatementsRecovery(true);
            parser.setBindingsRecovery(true);
            parser.setUnitName(absPath.getFileName().toString());
            // Provide minimal environment: no explicit classpath/sourcepath, but include current JVM bootclasspath
            parser.setEnvironment(new String[0], new String[0], null, true);
            var options = JavaCore.getOptions();
            JavaCore.setComplianceOptions(JavaCore.VERSION_25, options);
            parser.setCompilerOptions(options);

            CompilationUnit cu = (CompilationUnit) parser.createAST(null);

            // - Exclude import problems
            // - Exclude type errors
            // - Keep syntax errors and local identifier/undefined-name errors
            for (IProblem prob : cu.getProblems()) {
                if (!prob.isError()) {
                    continue;
                }
                int id = prob.getID();
                boolean isUndefinedIdentifier = (id == IProblem.UndefinedName);
                var msg = Objects.toString(prob.getMessage(), "");
                boolean isCannotResolveVariable = msg.contains("cannot be resolved to a variable");

                if (prob instanceof CategorizedProblem cp) {
                    int cat = cp.getCategoryID();
                    if (cat == CategorizedProblem.CAT_IMPORT) {
                        continue; // ignore import issues outright
                    }
                    if (cat == CategorizedProblem.CAT_TYPE && !(isUndefinedIdentifier || isCannotResolveVariable)) {
                        // general type problems can be noisy without a classpath; skip unless it's clearly an undefined
                        // identifier
                        continue;
                    }
                }

                var summary = formatJdtProblem(absPath, cu, prob);
                allProblems.add(summary);
            }
        });

        if (allProblems.isEmpty()) {
            // clean parse; proceed
            return new Step.Continue(cs, es);
        }

        // Build a concise diagnostic summary for the LLM
        var summary = String.join("\n", allProblems);

        var prompt =
                """
                Java syntax or identifier errors were detected in the edited files. Please fix these before we proceed to the full build.

                %s
                """
                        .stripIndent()
                        .formatted(summary);

        var nextRequest = new UserMessage(prompt);
        var nextCs = new ConversationState(
                cs.taskMessages(), nextRequest, cs.taskMessages().size());
        var nextEs = es.afterBuildFailure(summary); // reuse to reset state and per-turn baseline

        report("Java parse errors detected; asking LLM to fix syntax/identifier issues before building.");
        return new Step.Retry(nextCs, nextEs);
    }

    private static String formatJdtProblem(Path absPath, CompilationUnit cu, IProblem prob) {
        int start = Math.max(0, prob.getSourceStart());
        long line = Math.max(1, cu.getLineNumber(start));
        long col = Math.max(1, cu.getColumnNumber(start));
        var message = Objects.toString(prob.getMessage(), "Problem");
        return "%s:%d:%d: %s".formatted(absPath.toString(), line, col, message);
    }

    /** next FSM state */
    sealed interface Step permits Step.Continue, Step.Retry, Step.Fatal {
        ConversationState cs();

        EditState es();

        /** continue to the next phase */
        record Continue(ConversationState cs, EditState es) implements Step {}

        /** this phase found a problem that it wants to send back to the llm */
        record Retry(ConversationState cs, EditState es) implements Step {}

        /** fatal error, stop the task */
        record Fatal(TaskResult.StopDetails stopDetails) implements Step {
            public Fatal(TaskResult.StopReason stopReason) {
                this(new TaskResult.StopDetails(stopReason));
            }

            @Override
            public ConversationState cs() {
                throw new UnsupportedOperationException("Fatal step does not have a conversation state.");
            }

            @Override
            public EditState es() {
                throw new UnsupportedOperationException("Fatal step does not have an edit state.");
            }
        }
    }

    /**
     * Conversation state for the current coding task.
     *
     * <p>Task 3: {@code nextRequest} is now {@code @Nullable}. We deliberately null it out in
     * {@link #requestPhase(ConversationState, EditState, StreamingResult, Metrics)} after sending, to prevent stale
     * reuse. Callers that need to send a request must {@link java.util.Objects#requireNonNull(Object) requireNonNull}
     * it first.
     */
    record ConversationState(List<ChatMessage> taskMessages, @Nullable UserMessage nextRequest, int turnStartIndex) {
        /**
         * Replace all messages in the current turn (starting from turnStartIndex) with: - the original starting
         * UserMessage of the turn - a single synthetic AiMessage summarizing the edits.
         *
         * <p>Exposed for testing.
         */
        ConversationState replaceCurrentTurnMessages(String summaryText) {
            var msgs = new ArrayList<>(taskMessages);
            if (turnStartIndex < 0 || turnStartIndex >= msgs.size()) {
                logger.warn("Invalid turnStartIndex {}; cannot replace current turn messages safely.", turnStartIndex);
                // Fall back: just append a synthetic message (should never happen in practice)
                msgs.add(new AiMessage(summaryText));
                return new ConversationState(msgs, nextRequest, msgs.size());
            }

            var startMsg = msgs.get(turnStartIndex);
            UserMessage startUser;
            if (startMsg instanceof UserMessage su) {
                startUser = su;
            } else {
                logger.warn(
                        "Expected UserMessage at turnStartIndex {}, found {}. Using nextRequest as fallback.",
                        turnStartIndex,
                        startMsg.type());
                // Fail fast if this unexpected branch occurs without a pending nextRequest.
                startUser = requireNonNull(nextRequest, "nextRequest should be non-null at turn boundary");
            }

            // Drop everything from the start of this turn onward
            while (msgs.size() > turnStartIndex) {
                msgs.removeLast();
            }

            // Re-add the starting user message and the synthetic summary
            msgs.add(startUser);
            msgs.add(new AiMessage(summaryText));

            logger.debug("Replaced current turn messages (from index {}) with synthetic summary.", turnStartIndex);

            // After replacement, the next turn should start at the end of the current msgs
            return new ConversationState(msgs, nextRequest, msgs.size());
        }
    }

    record EditState(
            // parsed but not yet applied
            List<EditBlock.SearchReplaceBlock> pendingBlocks,
            int consecutiveParseFailures,
            int consecutiveApplyFailures,
            int consecutiveBuildFailures,
            int blocksAppliedWithoutBuild,
            String lastBuildError,
            Set<ProjectFile> changedFiles,
            Map<ProjectFile, String> originalFileContents) {
        /** Returns a new WorkspaceState with updated pending blocks and parse failures. */
        EditState withPendingBlocks(List<EditBlock.SearchReplaceBlock> newPendingBlocks, int newParseFailures) {
            return new EditState(
                    newPendingBlocks,
                    newParseFailures,
                    consecutiveApplyFailures,
                    consecutiveBuildFailures,
                    blocksAppliedWithoutBuild,
                    lastBuildError,
                    changedFiles,
                    originalFileContents);
        }

        /**
         * Returns a new WorkspaceState after a build failure, updating the error message. Also resets the per-turn
         * baseline (originalFileContents) for the next turn.
         */
        EditState afterBuildFailure(String newBuildError) {
            return new EditState(
                    pendingBlocks,
                    consecutiveParseFailures,
                    consecutiveApplyFailures,
                    consecutiveBuildFailures + 1,
                    0,
                    newBuildError,
                    changedFiles,
                    new HashMap<>()); // Clear per-turn baseline
        }

        /** Returns a new WorkspaceState after applying blocks, updating relevant fields. */
        EditState afterApply(
                List<EditBlock.SearchReplaceBlock> newPendingBlocks,
                int newApplyFailures,
                int newBlocksApplied,
                Map<ProjectFile, String> newOriginalContents) {
            return new EditState(
                    newPendingBlocks,
                    consecutiveParseFailures,
                    newApplyFailures,
                    consecutiveBuildFailures,
                    newBlocksApplied,
                    lastBuildError,
                    changedFiles,
                    newOriginalContents);
        }

        /**
         * Generate SEARCH/REPLACE blocks by diffing each changed file's current contents against the per-turn original
         * contents captured at the start of the turn. For files that were created in this turn (no original content),
         * generate a "new file" block (empty before / full after).
         *
         * <p>Note: We use full-file replacements for simplicity and robustness. This ensures correctness for the
         * history compaction without depending on the diff library package structure at compile time.
         */
        @VisibleForTesting
        List<EditBlock.SearchReplaceBlock> toSearchReplaceBlocks() {
            var results = new ArrayList<EditBlock.SearchReplaceBlock>();
            var originals = originalFileContents();

            // Include both files we have originals for and new files created in this turn
            var candidates = new HashSet<>(changedFiles());
            candidates.addAll(originals.keySet());

            // Sort for determinism
            var sorted = candidates.stream()
                    .sorted(Comparator.comparing(ProjectFile::toString))
                    .toList();

            for (var file : sorted) {
                String original = originals.getOrDefault(file, "");
                String revised;
                revised = file.read().orElse("");

                if (Objects.equals(original, revised)) {
                    continue; // No effective change
                }

                // New file created this turn
                if (!originals.containsKey(file)) {
                    results.add(new EditBlock.SearchReplaceBlock(file.toString(), "", revised));
                    continue;
                }

                var originalLines = original.isEmpty() ? List.<String>of() : Arrays.asList(original.split("\n", -1));
                var revisedLines = revised.isEmpty() ? List.<String>of() : Arrays.asList(revised.split("\n", -1));

                try {
                    com.github.difflib.patch.Patch<String> patch =
                            com.github.difflib.DiffUtils.diff(originalLines, revisedLines);

                    // 1) Build minimal windows per delta in original line space
                    record Window(int start, int end) {
                        Window expandLeft() {
                            return new Window(Math.max(0, start - 1), end);
                        }

                        Window expandRight(int max) {
                            return new Window(start, Math.min(max, end + 1));
                        }
                    }
                    var windows = new ArrayList<Window>();
                    for (AbstractDelta<String> delta : patch.getDeltas()) {
                        var src = delta.getSource();
                        int sPos = src.getPosition();
                        int sSize = src.size();

                        int wStart, wEnd;
                        if (sSize > 0) {
                            wStart = sPos;
                            wEnd = sPos + sSize - 1;
                        } else {
                            // Pure insertion: anchor on previous line when possible, else next
                            wStart = (sPos == 0) ? 0 : sPos - 1;
                            wEnd = wStart;
                        }
                        if (!originalLines.isEmpty()) {
                            wStart = Math.max(0, Math.min(wStart, originalLines.size() - 1));
                            wEnd = Math.max(0, Math.min(wEnd, originalLines.size() - 1));
                        }
                        windows.add(new Window(wStart, wEnd));
                    }

                    // 2) Expand each window until its before-text is unique in the original
                    int lastIdx = Math.max(0, originalLines.size() - 1);
                    windows = windows.stream()
                            .map(w -> {
                                Window cur = w;
                                String before = joinLines(originalLines, cur.start, cur.end);
                                while (!before.isEmpty()
                                        && countOccurrences(original, before) > 1
                                        && (cur.start > 0 || cur.end < lastIdx)) {
                                    if (cur.start > 0) cur = cur.expandLeft();
                                    if (cur.end < lastIdx) cur = cur.expandRight(lastIdx);
                                    before = joinLines(originalLines, cur.start, cur.end);
                                }
                                return cur;
                            })
                            .collect(Collectors.toCollection(ArrayList::new));

                    // 3) Merge overlapping/adjacent windows after expansion
                    windows.sort(Comparator.comparingInt(w -> w.start));
                    var merged = new ArrayList<Window>();
                    for (var w : windows) {
                        if (merged.isEmpty()) {
                            merged.add(w);
                        } else {
                            var last = merged.getLast();
                            if (w.start <= last.end + 1) { // overlap or adjacent
                                merged.set(merged.size() - 1, new Window(last.start, Math.max(last.end, w.end)));
                            } else {
                                merged.add(w);
                            }
                        }
                    }

                    // Precompute net line deltas for mapping original -> revised
                    record DeltaShape(int pos, int size, int net) {}
                    var shapes = patch.getDeltas().stream()
                            .map(d -> new DeltaShape(
                                    d.getSource().getPosition(),
                                    d.getSource().size(),
                                    d.getTarget().size() - d.getSource().size()))
                            .sorted(Comparator.comparingInt(s -> s.pos))
                            .toList();

                    for (var w : merged) {
                        // Map original start to revised start
                        int netBeforeStart = shapes.stream()
                                .filter(s -> s.pos + s.size <= w.start) // ends before start
                                .mapToInt(s -> s.net)
                                .sum();
                        int revisedStart = w.start + netBeforeStart;

                        int windowLen = w.end - w.start + 1;
                        // Net deltas that intersect the window
                        int netInWindow = 0;
                        for (AbstractDelta<String> d : patch.getDeltas()) {
                            int p = d.getSource().getPosition();
                            int sz = d.getSource().size();
                            int net = d.getTarget().size() - sz;
                            boolean overlaps;
                            if (sz > 0) {
                                overlaps = p < (w.end + 1) && (p + sz) > w.start;
                            } else {
                                overlaps = p >= w.start && p <= (w.end + 1);
                            }
                            if (overlaps) {
                                netInWindow += net;
                            }
                        }
                        int revisedEnd = revisedStart + windowLen + netInWindow - 1;

                        String before = joinLines(originalLines, w.start, w.end);
                        String after = joinLines(
                                revisedLines,
                                clamp(revisedStart, 0, Math.max(0, revisedLines.size() - 1)),
                                clamp(revisedEnd, 0, Math.max(0, revisedLines.size() - 1)));

                        // If uniqueness still fails (pathological), fall back to whole-file
                        if (!before.isEmpty() && countOccurrences(original, before) > 1) {
                            results.add(new EditBlock.SearchReplaceBlock(file.toString(), original, revised));
                            continue;
                        }

                        results.add(new EditBlock.SearchReplaceBlock(file.toString(), before, after));
                    }
                } catch (Exception e) {
                    // If diffing fails for any reason, fall back to a conservative whole-file replacement
                    logger.warn("Diff generation failed for {}; falling back to whole-file SRB", file, e);
                    results.add(new EditBlock.SearchReplaceBlock(file.toString(), original, revised));
                }
            }
            return results;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String joinLines(List<String> lines, int start, int end) {
        if (lines.isEmpty() || start > end) return "";
        var slice = String.join("\n", lines.subList(start, end + 1));
        return slice.isEmpty() ? "" : ensureTerminated(slice);
    }

    private static int countOccurrences(String text, String needle) {
        if (needle.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) != -1) {
            count++;
            idx = idx + Math.max(1, needle.length());
        }
        return count;
    }

    private static String ensureTerminated(String s) {
        if (s.isEmpty()) return s;
        return s.endsWith("\n") ? s : s + "\n";
    }

    private static class Metrics {
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

        final long startNanos = System.nanoTime();
        long llmWaitNanos = 0;
        int totalInputTokens = 0;
        int totalCachedTokens = 0;
        int totalThinkingTokens = 0;
        int totalOutputTokens = 0;
        int totalEditBlocks = 0;
        int failedEditBlocks = 0;
        int parseRetries = 0;
        int buildFailures = 0;
        int applyRetries = 0;
        int apiRetries = 0;

        void addTokens(@Nullable Llm.RichTokenUsage usage) {
            if (usage == null) {
                return;
            }
            totalInputTokens += usage.inputTokens();
            totalCachedTokens += usage.cachedInputTokens();
            totalThinkingTokens += usage.thinkingTokens();
            totalOutputTokens += usage.outputTokens();
        }

        void addApiRetries(int retryCount) {
            apiRetries += retryCount;
        }

        void print(Set<ProjectFile> changedFiles, TaskResult.StopDetails stopDetails) {
            var changedFilesList =
                    changedFiles.stream().map(ProjectFile::toString).toList();

            var jsonMap = new LinkedHashMap<String, Object>();
            jsonMap.put(
                    "totalMillis",
                    Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
            jsonMap.put("llmMillis", Duration.ofNanos(llmWaitNanos).toMillis());
            jsonMap.put("inputTokens", totalInputTokens);
            jsonMap.put("cachedInputTokens", totalCachedTokens);
            jsonMap.put("reasoningTokens", totalThinkingTokens);
            jsonMap.put("outputTokens", totalOutputTokens);
            jsonMap.put("editBlocksTotal", totalEditBlocks);
            jsonMap.put("editBlocksFailed", failedEditBlocks);
            jsonMap.put("buildFailures", buildFailures);
            jsonMap.put("parseRetries", parseRetries);
            jsonMap.put("applyRetries", applyRetries);
            jsonMap.put("apiRetries", apiRetries);
            jsonMap.put("changedFiles", changedFilesList);
            jsonMap.put("stopReason", stopDetails.reason().name());
            jsonMap.put("stopExplanation", stopDetails.explanation());

            try {
                var jsonString = OBJECT_MAPPER.writeValueAsString(jsonMap);
                System.err.println("\nBRK_CODEAGENT_METRICS=" + jsonString);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
