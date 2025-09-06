package io.github.jbellis.brokk.agents;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import com.github.difflib.patch.DeltaType;
import com.github.difflib.patch.Patch;
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
        var loopContext = new LoopContext(conversationState, workspaceState, userInput);

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
        var loopContext = new LoopContext(conversationState, editState, instructions);

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

        return new Step.Retry(new LoopContext(nextCs, nextWs, currentLoopContext.userGoal()));
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

        var aiMessage = streamingResultFromLlm.aiMessage();

        var newTaskMessages = new ArrayList<ChatMessage>(cs.taskMessages());
        newTaskMessages.add(cs.nextRequest());
        newTaskMessages.add(aiMessage);

        var newCs = new ConversationState(newTaskMessages, cs.nextRequest());
        return new Step.Continue(new LoopContext(newCs, currentLoopContext.editState(), currentLoopContext.userGoal()));
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

        // Before running verification, emit reverse edits (derived from diffs) to get back to original.
        var reverseMsg = generateReverseEditsMessage(ws);
        if (!reverseMsg.isBlank()) {
            // Show to user in the console before verification output
            io.llmOutput("\n" + reverseMsg, ChatMessageType.CUSTOM);
            // Also add to conversation history
            var cs = loopContext.conversationState();
            var newTaskMessages = new ArrayList<ChatMessage>(cs.taskMessages());
            newTaskMessages.add(new AiMessage(reverseMsg));
            loopContext = new LoopContext(
                    new ConversationState(newTaskMessages, cs.nextRequest()),
                    loopContext.editState(),
                    loopContext.userGoal());
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
            } catch (RuntimeException se) {
                return new Step.Fatal(new TaskResult.StopDetails(
                        TaskResult.StopReason.LLM_ERROR,
                        Objects.toString(se.getMessage(), se.toString())));
            }

            return new Step.Retry(new LoopContext(
                    ended.conversationState(),
                    newWs,
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


    // Compute reverse edits (current -> original) using fine-grained diff, one or more LineEdit per file.
    @VisibleForTesting
    List<LineEdit> generateReverseEdits(EditState ws) {
        if (ws.changedFiles().isEmpty()) {
            return List.of();
        }

        var results = new ArrayList<LineEdit>();
        var sortedFiles = ws.changedFiles().stream()
                .sorted(Comparator.comparing(ProjectFile::toString))
                .toList();

        for (var pf : sortedFiles) {
            // If the file wasn't present in the original map, treat as a newly created file -> delete on reverse.
            if (!ws.originalFileContents().containsKey(pf)) {
                results.add(new LineEdit.DeleteFile(pf.toString()));
                continue;
            }

            String original = requireNonNull(ws.originalFileContents().get(pf));
            String current;
            try {
                current = pf.read();
            } catch (IOException e) {
                logger.warn("Failed reading current contents of {} to generate reverse edits", pf, e);
                continue;
            }

            if (Objects.equals(original, current)) {
                continue; // nothing to reverse for this file
            }

            var currentLines = current.isEmpty() ? List.<String>of() : Arrays.asList(current.split("\n", -1));
            var originalLines = original.isEmpty() ? List.<String>of() : Arrays.asList(original.split("\n", -1));

            Patch<String> patch = DiffUtils.diff(currentLines, originalLines);

            for (AbstractDelta<String> delta : patch.getDeltas()) {
                var type = delta.getType();
                Chunk<String> src = delta.getSource();
                Chunk<String> tgt = delta.getTarget();

                switch (type) {
                    case CHANGE -> {
                        int begin = Math.max(1, src.getPosition() + 1);
                        int end = src.getPosition() + src.size();

                        String beginAddr = Integer.toString(begin);
                        String endAddr = Integer.toString(end);

                        String beginText = (begin - 1) < currentLines.size() && begin >= 1
                                ? currentLines.get(begin - 1)
                                : "";
                        String endText = (end - 1) < currentLines.size() && end >= 1
                                ? currentLines.get(end - 1)
                                : "";

                        var beginAnchor = new LineEdit.Anchor(beginAddr, beginText);
                        var endAnchor = new LineEdit.Anchor(endAddr, endText);

                        results.add(new LineEdit.EditFile(
                                pf.toString(),
                                begin,
                                end,
                                String.join("\n", tgt.getLines()),
                                beginAnchor,
                                endAnchor));
                    }
                    case DELETE -> {
                        int begin = Math.max(1, src.getPosition() + 1);
                        int end = src.getPosition() + src.size();

                        String beginAddr = Integer.toString(begin);
                        String endAddr = Integer.toString(end);

                        String beginText = (begin - 1) < currentLines.size() && begin >= 1
                                ? currentLines.get(begin - 1)
                                : "";
                        String endText = (end - 1) < currentLines.size() && end >= 1
                                ? currentLines.get(end - 1)
                                : "";

                        var beginAnchor = new LineEdit.Anchor(beginAddr, beginText);
                        var endAnchor = new LineEdit.Anchor(endAddr, endText);

                        results.add(new LineEdit.EditFile(
                                pf.toString(),
                                begin,
                                end,
                                "",
                                beginAnchor,
                                endAnchor));
                    }
                    case INSERT -> {
                        int pos = src.getPosition(); // insertion position relative to current content
                        int curSize = currentLines.size();

                        String addr;
                        String anchorText;
                        int begin;
                        if (pos == 0) {
                            addr = "0";
                            anchorText = "";
                            begin = 1; // will render as 0 a with anchor @0|
                        } else if (pos >= curSize) {
                            addr = "$";
                            anchorText = "";
                            begin = Integer.MAX_VALUE; // render as $ a
                        } else {
                            // insert before line (pos+1), i.e., after line pos
                            addr = Integer.toString(pos);
                            anchorText = currentLines.get(pos - 1);
                            begin = pos + 1;
                        }

                        var anchor = new LineEdit.Anchor(addr, anchorText);
                        results.add(new LineEdit.EditFile(
                                pf.toString(),
                                begin,
                                begin - 1, // insertion sentinel
                                String.join("\n", tgt.getLines()),
                                anchor,
                                null));
                    }
                    default -> {
                        // ignore EQUAL or unknown
                    }
                }
            }
        }

        // Order edits last-edits-first per file and maintain DeleteFile in original order after edits
        var editFiles = new ArrayList<LineEdit.EditFile>();
        var deletes = new ArrayList<LineEdit.DeleteFile>();
        for (var e : results) {
            if (e instanceof LineEdit.EditFile ef) editFiles.add(ef);
            else if (e instanceof LineEdit.DeleteFile df) deletes.add(df);
        }

        editFiles.sort((a, b) -> {
            int c = a.file().compareTo(b.file());
            if (c != 0) return c;
            // Treat Integer.MAX_VALUE (for $ a) as larger than any real line numbers
            int ab = (a.beginLine() == Integer.MAX_VALUE) ? Integer.MAX_VALUE : a.beginLine();
            int bb = (b.beginLine() == Integer.MAX_VALUE) ? Integer.MAX_VALUE : b.beginLine();
            c = Integer.compare(bb, ab);
            if (c != 0) return c;
            return Integer.compare(b.endLine(), a.endLine());
        });

        var combined = new ArrayList<LineEdit>(editFiles.size() + deletes.size());
        combined.addAll(editFiles);
        combined.addAll(deletes);
        return List.copyOf(combined);
    }

    // Compose a reverse BRK_EDIT_EX/BRK_EDIT_RM message from generated reverse edits.
    @VisibleForTesting
    String generateReverseEditsMessage(EditState ws) {
        var edits = generateReverseEdits(ws);
        if (edits.isEmpty()) {
            return "";
        }

        var byFile = new LinkedHashMap<String, List<LineEdit>>();
        for (var e : edits) {
            String key = e.file();
            byFile.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }

        var sb = new StringBuilder();
        for (var entry : byFile.entrySet()) {
            var list = entry.getValue();

            // Split out DeleteFile entries; render them separately
            var deletes = list.stream()
                    .filter(e -> e instanceof LineEdit.DeleteFile)
                    .map(e -> (LineEdit.DeleteFile) e)
                    .toList();
            var editsOnly = list.stream()
                    .filter(e -> e instanceof LineEdit.EditFile)
                    .map(e -> (LineEdit.EditFile) e)
                    .toList();

            for (var df : deletes) {
                sb.append("BRK_EDIT_RM ").append(df.file()).append("\n\n");
            }

            if (!editsOnly.isEmpty()) {
                sb.append(LineEdit.repr(editsOnly)).append("\n\n");
            }
        }

        return """
I have made edits using the BRK_EDIT_EX format. I am showing the REVERSE edits, to go back to the pre-edit state,
because I can see the post-edit state in the current Workspace. This allows me to reference any original code
and replace or repair it if it becomes necessary to do so to fix build or lint errors.
I will continue to make NEW edits with normal, forwards BRK_EDIT_EX commands.

%s""".stripIndent().formatted(sb.toString().stripTrailing());
    }


    record LoopContext(ConversationState conversationState, EditState editState, String userGoal) {}

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
        var cs = currentLoopContext.conversationState();
        var newTaskMessages = new ArrayList<ChatMessage>(cs.taskMessages());
        var resultingNextRequest = (nextRequest != null) ? nextRequest : cs.nextRequest();
        var newConversation = new ConversationState(newTaskMessages, resultingNextRequest);
        return new LoopContext(newConversation, currentLoopContext.editState(), currentLoopContext.userGoal());
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
