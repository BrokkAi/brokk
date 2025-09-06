package io.github.jbellis.brokk.agents;

import static java.util.Objects.requireNonNull;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.github.jbellis.brokk.prompts.LineEditorParser;
import io.github.jbellis.brokk.prompts.QuickEditPrompts;
import io.github.jbellis.brokk.prompts.SummarizerPrompts;
import io.github.jbellis.brokk.LineEdit;
import io.github.jbellis.brokk.LineEditor;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.LogDescription;
import io.github.jbellis.brokk.util.Messages;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
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

    /** maximum combined parse+apply retries [while making progress] before giving up */
    @VisibleForTesting
    static final int MAX_EDIT_RETRIES = 12;

    /**
     * stricter cap when a turn results in **no successful applications** (succeeded == 0);
     * counts consecutively and resets on any success
     */
    @VisibleForTesting
    static final int MAX_EDIT_RETRIES_NO_RESULTS = 3;

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

    /**
     * Implementation of the LLM task that runs in a separate thread. Uses the provided model for the initial request
     * and potentially switches for fixes.
     *
     * @param userInput The user's goal/instructions.
     * @return A TaskResult containing the conversation history and original file contents
     */
    public TaskResult runTask(String userInput, boolean forArchitect) {
        var collectMetrics = "true".equalsIgnoreCase(System.getenv("BRK_CODEAGENT_METRICS"));
        @Nullable Metrics metrics = collectMetrics ? new Metrics() : null;

        var io = contextManager.getIo();
        var coder = contextManager.getLlm(model, "Code: " + userInput, true);
        coder.setOutput(io);
        var summarizer = contextManager.getLlm(contextManager.getService().quickModel(), "Summarize for history");

        var changedFiles = new HashSet<ProjectFile>();

        int blocksAppliedWithoutBuild = 0;
        String buildError = "";
        Map<ProjectFile, String> originalFileContents = new HashMap<>();

        var msg = "Code Agent engaged: `%s...`".formatted(LogDescription.getShortDescription(userInput));
        io.systemOutput(msg);
        TaskResult.StopDetails stopDetails = null;

        // We'll collect the conversation as ChatMessages to store in context history.
        var taskMessages = new ArrayList<ChatMessage>();
        UserMessage nextRequest = CodePrompts.instance.codeRequest(
                userInput.trim(), CodePrompts.instance.codeReminder(contextManager.getService(), model), null);

        var conversationState = new ConversationState(taskMessages, nextRequest);
        var workspaceState = new EditState(
                0,         // consecutiveEditRetries
                0,         // consecutiveNoResultRetries
                0,         // consecutiveBuildFailures
                blocksAppliedWithoutBuild,
                buildError,
                changedFiles,
                originalFileContents);
        var loopContext = new LoopContext(conversationState, workspaceState, new TurnState(new ArrayList<>(), new ArrayList<>()), userInput);

        while (true) {
            if (Thread.interrupted()) {
                logger.debug("CodeAgent interrupted");
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
                break;
            }

            // Make the LLM request
            StreamingResult streamingResult;
            try {
                var allMessagesForLlm = CodePrompts.instance.collectCodeMessages(
                        contextManager,
                        model,
                        loopContext.conversationState().taskMessages(),
                        loopContext.conversationState().nextRequest(),
                        loopContext.editState().changedFiles());
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

            // REQUEST PHASE
            var requestOutcome = requestPhase(loopContext, streamingResult, metrics, summarizer);
            switch (requestOutcome) {
                case Step.Continue(var newLoopContext) -> loopContext = newLoopContext;
                case Step.Fatal(var details) -> stopDetails = details;
                default -> throw new IllegalStateException(
                        "requestPhase returned unexpected Step type: " + requestOutcome.getClass());
            }
            if (stopDetails != null) break;

            // EDIT PHASE (combined parse+apply)
            var editOutcome = editPhase(loopContext, streamingResult.text(), streamingResult.isPartial(), metrics);
            switch (editOutcome) {
                case Step.Continue(var newLoopContext) -> loopContext = newLoopContext;
                case Step.Retry(var newLoopContext) -> {
                    loopContext = newLoopContext;
                    continue; // Retry the LLM turn
                }
                case Step.Fatal(var details) -> stopDetails = details;
            }
            if (stopDetails != null) break;

            // VERIFY PHASE runs the build (only when something was applied since last build)
            var verifyOutcome = verifyPhase(loopContext, metrics);
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

        if (metrics != null) {
            metrics.print(loopContext.editState().changedFiles(), stopDetails);
        }

        // create the Result for history
        String finalActionDescription = (stopDetails.reason() == TaskResult.StopReason.SUCCESS)
                ? loopContext.userGoal()
                : loopContext.userGoal() + " [" + stopDetails.reason().name() + "]";
        // architect auto-compresses the task entry so let's give it the full history to work with, quickModel is cheap
        // Prepare messages for TaskEntry log: filter raw messages and keep Line Edit tags verbatim
        var finalMessages = forArchitect
                ? List.copyOf(io.getLlmRawMessages(false))
                : prepareMessagesForTaskEntryLog(io.getLlmRawMessages(false));
        return new TaskResult(
                "Code: " + finalActionDescription,
                new ContextFragment.TaskFragment(contextManager, finalMessages, loopContext.userGoal()),
                loopContext.editState().changedFiles(),
                stopDetails);
    }

    /**
     * Runs a “single-file edit” session. Uses the same request → edit (combined) flow as runTask, but stops
     * after all Line Edit tags for this turn have been applied (no verify/build).
     */
    public TaskResult runSingleFileEdit(ProjectFile file, String instructions, List<ChatMessage> readOnlyMessages) {
        var coder = contextManager.getLlm(model, "Code (single-file): " + instructions, true);
        coder.setOutput(io);

        UserMessage initialRequest = CodePrompts.instance.codeRequest(
                instructions, CodePrompts.instance.codeReminder(contextManager.getService(), model), file);

        var conversationState = new ConversationState(new ArrayList<>(), initialRequest);
        var editState = new EditState(0, 0, 0, 0, "", new HashSet<>(), new HashMap<>());
        var loopContext = new LoopContext(conversationState, editState, new TurnState(new ArrayList<>(), new ArrayList<>()), instructions);

        logger.debug("Code Agent engaged in single-file mode for %s: `%s…`"
                             .formatted(file.getFileName(), LogDescription.getShortDescription(instructions)));

        TaskResult.StopDetails stopDetails;

        while (true) {
            // Build messages for this turn
            List<ChatMessage> llmMessages = CodePrompts.instance.getSingleFileCodeMessages(
                    contextManager.getProject().getStyleGuide(),
                    readOnlyMessages,
                    loopContext.conversationState().taskMessages(),
                    loopContext.conversationState().nextRequest(),
                    file);

            // Send to LLM
            StreamingResult streamingResult;
            try {
                streamingResult = coder.sendRequest(llmMessages, true);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
                break;
            }

            // REQUEST
            var step = requestPhase(loopContext, streamingResult, null, null);
            if (step instanceof Step.Fatal(TaskResult.StopDetails details)) {
                stopDetails = details;
                break;
            }
            loopContext = step.loopContext();

            // EDIT (combined)
            step = editPhase(loopContext, streamingResult.text(), streamingResult.isPartial(), null);
            if (step instanceof Step.Retry retry) {
                loopContext = retry.loopContext();
                continue; // ask LLM again
            }
            if (step instanceof Step.Fatal(TaskResult.StopDetails details)) {
                stopDetails = details;
                break;
            }
            loopContext = step.loopContext();

            // Stop after this turn (no build in single-file mode)
            stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
            break;
        }

        assert stopDetails != null;
        var finalMessages = prepareMessagesForTaskEntryLog(io.getLlmRawMessages(false));

        String finalAction = (stopDetails.reason() == TaskResult.StopReason.SUCCESS)
                ? instructions
                : instructions + " [" + stopDetails.reason().name() + "]";

        return new TaskResult(
                "Code: " + finalAction,
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

    /**
     * Combined phase: parse LineEdits from the LLM response and immediately attempt to apply them.
     * If any parse or apply problems occur, return a Retry with a unified failure message.
     */
    Step editPhase(
            LoopContext currentLoopContext,
            String llmText,
            boolean isPartialResponse,
            @Nullable Metrics metrics) {
        var cs = currentLoopContext.conversationState();
        var ws = currentLoopContext.editState();

        logger.debug("Got response (potentially partial if LLM connection was cut off)");

        var lepResult = LineEditorParser.instance.parse(llmText);
        var parsedEdits = lepResult.edits();
        if (metrics != null) {
            metrics.totalLineEdits += parsedEdits.size();
        }

        // If there are parsed edits, ensure we’re not touching read-only files
        if (!parsedEdits.isEmpty()) {
            var filesToTouch = parsedEdits.stream()
                    .map(LineEdit::file)
                    .map(contextManager::toFile)
                    .distinct()
                    .toList();
            var readOnlyConflicts = filesToTouch.stream()
                    .filter(f -> contextManager.getReadonlyProjectFiles().contains(f))
                    .toList();
            if (!readOnlyConflicts.isEmpty()) {
                var msg =
                        "LLM attempted to edit read-only file(s): %s.\nNo edits applied. Mark the file(s) editable or clarify the approach."
                                .formatted(readOnlyConflicts.stream()
                                                   .map(ProjectFile::toString)
                                                   .collect(Collectors.joining(",")));
                reportComplete(msg);
                var filenames = readOnlyConflicts.stream().map(ProjectFile::toString).collect(Collectors.joining(","));
                return new Step.Fatal(new TaskResult.StopDetails(TaskResult.StopReason.READ_ONLY_EDIT, filenames));
            }
        }

        // Pre-create empty files for insertion edits
        preCreateNewFilesForInsertEdits(parsedEdits);

        // Attempt to apply edits (if any)
        List<LineEditor.ApplyFailure> applyFailures = List.of();
        int succeeded = 0;
        Map<ProjectFile, String> nextOriginals = new HashMap<>(ws.originalFileContents());

        if (!parsedEdits.isEmpty()) {
            LineEditor.ApplyResult applyResult;
            try {
                applyResult = LineEditor.applyEdits(contextManager, io, parsedEdits);
            } catch (Exception e) {
                var msg = Objects.toString(e.getMessage(), e.toString());
                io.toolError(msg);
                return new Step.Fatal(new TaskResult.StopDetails(TaskResult.StopReason.IO_ERROR, msg));
            }

            ws.changedFiles().addAll(applyResult.originalContents().keySet());
            applyResult.originalContents().forEach(nextOriginals::putIfAbsent);

            applyFailures = applyResult.failures();
            if (metrics != null) {
                metrics.failedLineEdits += applyFailures.size();
            }

            int attempted = parsedEdits.size();
            succeeded = attempted - applyFailures.size();
        }

        boolean hasParseProblems = lepResult.error() != null || !lepResult.failures().isEmpty();
        boolean hasApplyProblems = !applyFailures.isEmpty();
        boolean hasErrors = isPartialResponse || hasParseProblems || hasApplyProblems;

        if (!hasErrors) {
            // Clean, complete turn; may be a prose-only response or actual edits applied successfully
            int newBlocksAppliedWithoutBuild = ws.blocksAppliedWithoutBuild() + Math.max(0, succeeded);
            if (succeeded > 0) {
                report(succeeded + " line edit(s) applied.");
            }
            // Reset both edit retry counters on a clean turn
            var newWs = ws.afterEditAttempt(
                    0,         // consecutiveEditRetries reset
                    0,         // consecutiveNoResultRetries reset
                    newBlocksAppliedWithoutBuild,
                    nextOriginals);

            // Do not finalize the turn yet; wait until after build results.
            return new Step.Continue(new LoopContext(
                    currentLoopContext.conversationState(),
                    newWs,
                    currentLoopContext.turnState(),
                    currentLoopContext.userGoal()));
        }

        // Construct unified retry prompt
        // Determine the last successfully applied edit (if any) to provide a correct continuation hint
        LineEdit lastGoodApplied = null;
        if (succeeded > 0 && !parsedEdits.isEmpty()) {
            var failed = applyFailures.stream().map(LineEditor.ApplyFailure::edit).collect(Collectors.toSet());
            for (int i = parsedEdits.size() - 1; i >= 0; i--) {
                var e = parsedEdits.get(i);
                if (!failed.contains(e)) {
                    lastGoodApplied = e;
                    break;
                }
            }
        }
        var retryPrompt = CodePrompts.getCombinedEditFailureMessage(
                lepResult.failures(), lepResult.error(), lastGoodApplied, applyFailures, Math.max(0, succeeded));

        int newConsecutiveEditRetries = ws.consecutiveEditRetries() + 1;
        int newConsecutiveNoResultRetries = (succeeded == 0) ? (ws.consecutiveNoResultRetries() + 1) : 0;

        // Metrics bookkeeping (keep separate counters even though the phase is combined)
        if (metrics != null) {
            if (isPartialResponse || hasParseProblems) {
                metrics.parseRetries++;
            }
            if (hasApplyProblems) {
                metrics.applyRetries++;
            }
        }

        // Decide if we exceeded caps
        boolean hitCombinedCap = newConsecutiveEditRetries >= MAX_EDIT_RETRIES;
        boolean hitNoResultCap = newConsecutiveNoResultRetries >= MAX_EDIT_RETRIES_NO_RESULTS;

        if (hitCombinedCap || hitNoResultCap) {
            reportComplete("Edit retry limit reached; ending task.");
            var stopReason = (isPartialResponse || hasParseProblems)
                    ? TaskResult.StopReason.PARSE_ERROR
                    : TaskResult.StopReason.APPLY_ERROR;
            return new Step.Fatal(new TaskResult.StopDetails(stopReason));
        }

        // Retry: increment blocksAppliedWithoutBuild by any success we did get
        int newBlocksAppliedWithoutBuild = ws.blocksAppliedWithoutBuild() + Math.max(0, succeeded);
        var nextCs = new ConversationState(cs.taskMessages(), new UserMessage(retryPrompt));
        var nextWs = ws.afterEditAttempt(
                newConsecutiveEditRetries,
                newConsecutiveNoResultRetries,
                newBlocksAppliedWithoutBuild,
                nextOriginals);

        // Helpful console logging
        if (isPartialResponse && parsedEdits.isEmpty()) {
            report("LLM indicated response was partial before any Line Edit tags; asking to continue");
        } else if (isPartialResponse) {
            report("Malformed or partial response after %d Line Edits parsed; asking LLM to continue/fix"
                           .formatted(parsedEdits.size()));
        } else if (hasParseProblems) {
            report("Failed to parse some Line Edits; asking LLM to fix parse issues");
        } else {
            report("Failed to apply %s edit(s), asking LLM to retry".formatted(applyFailures.size()));
            report(applyFailures.toString());
        }

        return new Step.Retry(new LoopContext(nextCs, nextWs, currentLoopContext.turnState(), currentLoopContext.userGoal()));
    }

    /**
     * Pre-creates empty files for SearchReplaceBlocks representing new files (those with empty beforeText). This
     * ensures files exist on disk before they are added to the context, preventing race conditions with UI updates.
     *
     * @param blocks Collection of SearchReplaceBlocks potentially containing new file creations
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
                    file.write("");
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
                // the file watcher that normally does this automatically is paused during task execution.
                // clear the cache manually so BuildAgent's call to CM::getTestFiles sees the new files as part of the
                // project.
                contextManager.getRepo().invalidateCaches();
            } catch (GitAPIException e) {
                io.toolError("Failed to add %s to git".formatted(newFiles), "Error");
            }
            contextManager.editFiles(newFiles);
        }
        return newFiles;
    }

    @VisibleForTesting
    public List<ProjectFile> preCreateNewFilesForInsertEdits(Collection<? extends LineEdit> edits) {
        List<ProjectFile> newFiles = new ArrayList<>();
        for (var edit : edits) {
            if (edit instanceof LineEdit.EditFile ef) {
                boolean isInsertion = ef.endLine() < ef.beginLine();
                if (isInsertion) {
                    var pf = contextManager.toFile(ef.file());
                    if (!pf.exists()) {
                        newFiles.add(pf);
                        try {
                            pf.write("");
                            logger.debug("Pre-created empty file: {}", pf);
                        } catch (IOException e) {
                            io.toolError("Failed to create empty file " + pf + ": " + e.getMessage(), "Error");
                        }
                    }
                }
            }
        }

        if (!newFiles.isEmpty()) {
            try {
                contextManager.getRepo().add(newFiles);
                contextManager.getRepo().invalidateCaches();
            } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
                io.toolError("Failed to add %s to git".formatted(newFiles), "Error");
            }
            contextManager.editFiles(newFiles);
        }
        return newFiles;
    }

    /**
     * Prepares messages for storage in a TaskEntry. This involves filtering raw LLM I/O to keep USER, CUSTOM, and AI
     * messages. AI messages containing Line Edit tags will have their raw text preserved, rather than converting
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
                            // Raw Line Edit tags are preserved.
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
        var relatedCode = contextManager.liveContext().buildAutoContext(5);

        String fileContents;
        try {
            fileContents = file.read();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }

        // Build the prompt messages
        var styleGuide = contextManager.getProject().getStyleGuide();
        var messages = QuickEditPrompts.instance.collectMessages(fileContents, relatedCode, styleGuide);
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
        return new TaskResult(
                "Quick Edit: " + file.getFileName(),
                new ContextFragment.TaskFragment(contextManager, pendingHistory, "Quick Edit: " + file.getFileName()),
                Set.of(file),
                stopDetails);
    }

    /** Formats the most recent build error for the LLM retry prompt. */
    private static String formatBuildErrorsForLLM(String latestBuildError) {
        return """
                The build failed with the following error:

                %s

                Please analyze the error message, review the conversation history for previous attempts, and provide Line Edit tags to fix the error.

                IMPORTANT: If you determine that the build errors are not improving or are going in circles after reviewing the history,
                do your best to explain the problem but DO NOT provide any edits.
                Otherwise, provide the edits as usual.
                """
                .stripIndent()
                .formatted(latestBuildError);
    }

    Step requestPhase(
            LoopContext currentLoopContext, StreamingResult streamingResultFromLlm, @Nullable Metrics metrics, @Nullable Llm summarizer) {
        var cs = currentLoopContext.conversationState();

        var llmError = streamingResultFromLlm.error();
        if (streamingResultFromLlm.isEmpty()) {
            String message;
            TaskResult.StopDetails fatalDetails;
            if (llmError != null) {
                message = "LLM returned an error even after retries: " + llmError.getMessage() + ". Ending task";
                fatalDetails = new TaskResult.StopDetails(
                        TaskResult.StopReason.LLM_ERROR, requireNonNull(llmError.getMessage()));
            } else {
                message = "Empty LLM response even after retries. Ending task";
                fatalDetails = new TaskResult.StopDetails(TaskResult.StopReason.EMPTY_RESPONSE, message);
            }
            io.toolError(message);
            return new Step.Fatal(fatalDetails);
        }

        // DO NOT append straight to taskMessages here. Instead sandbox this turn's messages.
        var aiMessage = streamingResultFromLlm.aiMessage();

        var newTurnMessages = new ArrayList<ChatMessage>(currentLoopContext.turnState().messages());
        newTurnMessages.add(cs.nextRequest());
        newTurnMessages.add(aiMessage);

        // Propagate any previously queued summary futures for this turn
        var newSummaries = new ArrayList<java.util.concurrent.CompletableFuture<String>>(currentLoopContext.turnState().summaries());

        // If a summarizer is provided and the AI response contains edits, start async summarization.
        try {
            if (summarizer != null && aiMessage.text().contains("BRK_EDIT_EX")) {
                var toSummarize = aiMessage.text();
                var future = java.util.concurrent.CompletableFuture.<String>supplyAsync(() -> {
                    try {
                        var messagesForSummarizer = SummarizerPrompts.instance.compressHistory(toSummarize);
                        var sr = summarizer.sendRequest(messagesForSummarizer, true);
                        if (sr.error() != null) {
                            throw new RuntimeException("Summarizer LLM error: " + sr.error().getMessage());
                        }
                        if (sr.text().isBlank()) {
                            throw new RuntimeException("Summarizer returned empty result");
                        }
                        return sr.text();
                    } catch (RuntimeException re) {
                        throw re;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                newSummaries.add(future);
            }
        } catch (RuntimeException e) {
            // If launching the summarization itself fails, surface as fatal so caller can decide.
            return new Step.Fatal(new TaskResult.StopDetails(
                    TaskResult.StopReason.LLM_ERROR,
                    Objects.toString(e.getMessage(), e.toString())));
        }

        var nextTurnState = new TurnState(newTurnMessages, newSummaries);
        return new Step.Continue(new LoopContext(cs, currentLoopContext.editState(), nextTurnState, currentLoopContext.userGoal()));
    }

    Step verifyPhase(LoopContext loopContext, @Nullable Metrics metrics) {
        var ws = loopContext.editState();

        // Verify only runs when editsSinceLastBuild > 0.
        if (ws.blocksAppliedWithoutBuild() == 0) {
            reportComplete("No edits found or applied in response, and no changes since last build; ending task.");
            TaskResult.StopDetails stopDetails;
            if (loopContext.editState().lastBuildError().isEmpty()) {
                stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
            } else {
                stopDetails = new TaskResult.StopDetails(
                        TaskResult.StopReason.BUILD_ERROR,
                        loopContext.editState().lastBuildError());
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
            if (metrics != null) {
                metrics.buildFailures++;
            }

            int newBuildFailures = ws.consecutiveBuildFailures() + 1;
            if (newBuildFailures >= MAX_BUILD_FAILURES) {
                reportComplete("Build failed %d consecutive times; aborting.".formatted(newBuildFailures));
                return new Step.Fatal(new TaskResult.StopDetails(
                        TaskResult.StopReason.BUILD_ERROR,
                        "Build failed %d consecutive times:\n%s".formatted(newBuildFailures, latestBuildError)));
            }
            UserMessage nextRequestForBuildFailure = new UserMessage(formatBuildErrorsForLLM(latestBuildError));
            var newWs = ws.afterBuildFailure(latestBuildError);
            report("Asking LLM to fix build/lint failures");

            LoopContext ended;
            try {
                ended = endTurn(loopContext, nextRequestForBuildFailure);
            } catch (SummarizationException se) {
                return new Step.Fatal(new TaskResult.StopDetails(
                        TaskResult.StopReason.LLM_ERROR,
                        Objects.toString(se.getMessage(), se.toString())));
            }

            return new Step.Retry(new LoopContext(
                    ended.conversationState(),
                    newWs,
                    ended.turnState(),
                    loopContext.userGoal()));
        }
    }

    private String performBuildVerification() throws InterruptedException {
        var verificationCommand = BuildAgent.determineVerificationCommand(contextManager);
        if (verificationCommand == null || verificationCommand.isBlank()) {
            report("No verification command specified, skipping build/check.");
            return "";
        }

        // Enforce single-build execution when requested
        boolean noConcurrentBuilds = "true".equalsIgnoreCase(System.getenv("BRK_NO_CONCURRENT_BUILDS"));
        if (!noConcurrentBuilds) {
            return runVerificationCommand(verificationCommand);
        }

        Path lockDir = Paths.get(System.getProperty("java.io.tmpdir"), "brokk");
        try {
            Files.createDirectories(lockDir);
        } catch (IOException e) {
            logger.warn("Unable to create lock directory {}; proceeding without build lock", lockDir, e);
            return runVerificationCommand(verificationCommand);
        }

        var repoNameForLock = getOriginRepositoryName();
        Path lockFile = lockDir.resolve(repoNameForLock + ".lock");

        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            logger.debug("Acquired build lock {}", lockFile);
            return runVerificationCommand(verificationCommand);
        } catch (IOException ioe) {
            logger.warn("Failed to acquire file lock {}; proceeding without it", lockFile, ioe);
            return runVerificationCommand(verificationCommand);
        }
    }

    public String getOriginRepositoryName() {
        var url = contextManager.getRepo().getRemoteUrl();
        if (url == null || url.isBlank()) {
            // Fallback: use directory name of repo root
            return contextManager.getRepo().getGitTopLevel().getFileName().toString();
        }

        // Strip trailing ".git", if any
        if (url.endsWith(".git")) {
            url = url.substring(0, url.length() - 4);
        }

        // SSH URLs use ':', HTTPS uses '/'
        int idx = Math.max(url.lastIndexOf('/'), url.lastIndexOf(':'));
        if (idx >= 0 && idx < url.length() - 1) {
            return url.substring(idx + 1);
        }

        throw new IllegalArgumentException("Unable to parse git repo url " + url);
    }

    /**
     * Executes the given verification command, streaming output back to the console. Returns an empty string on
     * success, or the combined error/output when the command exits non-zero.
     */
    private String runVerificationCommand(String verificationCommand) throws InterruptedException {
        io.llmOutput("\nRunning verification command: " + verificationCommand, ChatMessageType.CUSTOM);
        io.llmOutput("\n```bash\n", ChatMessageType.CUSTOM);
        try {
            var output = Environment.instance.runShellCommand(
                    verificationCommand,
                    contextManager.getProject().getRoot(),
                    line -> io.llmOutput(line + "\n", ChatMessageType.CUSTOM),
                    Environment.UNLIMITED_TIMEOUT);
            logger.debug("Verification command successful. Output: {}", output);
            io.llmOutput("\n```", ChatMessageType.CUSTOM);
            return "";
        } catch (Environment.SubprocessException e) {
            io.llmOutput("\n```", ChatMessageType.CUSTOM); // Close the markdown block
            // Add the combined error and output to the history for the next request
            return e.getMessage() + "\n\n" + e.getOutput();
        }
    }

    public static record TurnState(List<ChatMessage> messages, List<java.util.concurrent.CompletableFuture<String>> summaries) {}

    private static class SummarizationException extends RuntimeException {
        public SummarizationException(Throwable cause) {
            super(cause);
        }
    }

    record LoopContext(ConversationState conversationState, EditState editState, TurnState turnState, String userGoal) {}

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

    record ConversationState(List<ChatMessage> taskMessages, UserMessage nextRequest) {}

    record EditState(
            int consecutiveEditRetries,
            int consecutiveNoResultRetries,
            int consecutiveBuildFailures,
            int blocksAppliedWithoutBuild,
            String lastBuildError,
            Set<ProjectFile> changedFiles,
            Map<ProjectFile, String> originalFileContents) {

        /**
         * Returns a new EditState after an edit attempt (success or retry).
         */
        EditState afterEditAttempt(
                int newConsecutiveEditRetries,
                int newConsecutiveNoResultRetries,
                int newBlocksApplied,
                Map<ProjectFile, String> newOriginalContents) {
            return new EditState(
                    newConsecutiveEditRetries,
                    newConsecutiveNoResultRetries,
                    consecutiveBuildFailures,
                    newBlocksApplied,
                    lastBuildError,
                    changedFiles,
                    newOriginalContents);
        }

        /** Returns a new EditState after a build failure, updating the error message. */
        EditState afterBuildFailure(String newBuildError) {
            return new EditState(
                    consecutiveEditRetries,
                    consecutiveNoResultRetries,
                    consecutiveBuildFailures + 1,
                    0,
                    newBuildError,
                    changedFiles,
                    originalFileContents);
        }
    }

    private LoopContext endTurn(LoopContext currentLoopContext, @Nullable UserMessage nextRequest) {
        // Wait for any pending summary futures and then fold either summaries or raw messages into task history.
        List<String> summaries = new ArrayList<>();
        try {
            summaries = currentLoopContext.turnState().summaries().stream()
                    .map(f -> {
                        try {
                            return f.join();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();
        } catch (RuntimeException e) {
            throw new SummarizationException(e.getCause() == null ? e : e.getCause());
        }

        // base task messages on the prior taskMessages
        var newTaskMessages = new ArrayList<ChatMessage>(currentLoopContext.conversationState().taskMessages());

        if (!summaries.isEmpty()) {
            // find the first UserMessage of the turn to pair with the combined summary AI message
            var maybeUser = currentLoopContext.turnState().messages().stream()
                    .filter(m -> m instanceof UserMessage)
                    .findFirst();
            if (maybeUser.isPresent()) {
                var firstUser = (UserMessage) maybeUser.get();
                newTaskMessages.add(firstUser);
            }
            var combined = new StringBuilder();
            combined.append("I've made the following edits (not shown) following the BRK_EDIT_EX format:\n\n");
            for (int i = 0; i < summaries.size(); i++) {
                if (i > 0) combined.append("\n\n");
                combined.append(summaries.get(i));
            }
            newTaskMessages.add(new AiMessage(combined.toString()));
        } else {
            // no summaries: append all sandboxed messages for the turn
            newTaskMessages.addAll(currentLoopContext.turnState().messages());
        }

        var resultingNextRequest = (nextRequest != null) ? nextRequest : currentLoopContext.conversationState().nextRequest();
        var newConversation = new ConversationState(newTaskMessages, resultingNextRequest);
        var newTurnState = new TurnState(new ArrayList<>(), new ArrayList<>());

        return new LoopContext(newConversation, currentLoopContext.editState(), newTurnState, currentLoopContext.userGoal());
    }

    private static class Metrics {
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

        final long startNanos = System.nanoTime();
        long llmWaitNanos = 0;
        int totalInputTokens = 0;
        int totalCachedTokens = 0;
        int totalThinkingTokens = 0;
        int totalOutputTokens = 0;
        int totalLineEdits = 0;
        int failedLineEdits = 0;
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
            jsonMap.put("editBlocksTotal", totalLineEdits);
            jsonMap.put("editBlocksFailed", failedLineEdits);
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
