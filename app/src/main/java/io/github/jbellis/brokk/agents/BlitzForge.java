package io.github.jbellis.brokk.agents;

import static java.util.Objects.requireNonNull;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.util.AdaptiveExecutor;
import io.github.jbellis.brokk.util.Messages;
import io.github.jbellis.brokk.util.TokenAware;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Core engine for parallel "BlitzForge" style processing of files. GUI-agnostic and reusable.
 */
public final class BlitzForge {

    private static final Logger logger = LogManager.getLogger(BlitzForge.class);

    /** High-level action the engine is asked to perform. */
    public enum Action {
        CODE,
        ASK,
        MERGE
    }

    /** How much of the per-file output to include in the aggregated result. */
    public enum ParallelOutputMode {
        NONE,
        ALL,
        CHANGED
    }

    /** Listener for lifecycle and progress events. All callbacks are best-effort and may be called from worker threads. */
    public interface Listener {
        default void onStart(int total) {}
        default void onFileStart(ProjectFile file) {}
        default void onLlmOutput(ProjectFile file, String token, boolean isNewMessage, boolean isReasoning) {}
        default void onFileResult(ProjectFile file, boolean edited, @Nullable String errorMessage, String llmOutput) {}
        default void onProgress(int processed, int total) {}
        default void onDone(TaskResult result) {}
    }

    /** Configuration for a BlitzForge run. */
    public static record RunConfig(
            String instructions,
            @Nullable StreamingChatModel model,
            boolean includeWorkspace,
            @Nullable Integer relatedK,
            @Nullable String perFileCommandTemplate,
            String contextFilter,
            ParallelOutputMode outputMode,
            boolean buildFirst,
            String postProcessingInstructions,
            Action action) {

        public RunConfig {}
    }

    /** Result of processing a single file. */
    public static record FileResult(
            ProjectFile file, boolean edited, @Nullable String errorMessage, String llmOutput) {}

    private final @Nullable IContextManager cm;
    private final @Nullable io.github.jbellis.brokk.Service service;
    private final RunConfig config;
    private final Listener listener;

    public BlitzForge(
            @Nullable IContextManager cm,
            @Nullable io.github.jbellis.brokk.Service service,
            RunConfig config,
            Listener listener) {
        this.cm = cm;
        this.service = service;
        this.config = config;
        this.listener = listener;
    }

    /**
     * Execute a set of per-file tasks in parallel, using AdaptiveExecutor token-aware scheduling when possible.
     * The provided processor should be thread-safe.
     */
    public TaskResult executeParallel(List<ProjectFile> files, Function<ProjectFile, FileResult> processor) {

        listener.onStart(files.size());

        if (files.isEmpty()) {
            var ctx = (cm != null) ? cm : new IContextManager() {};
            var emptyResult = new TaskResult(
                    ctx,
                    config.instructions(),
                    List.of(),
                    Set.of(),
                    new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS));
            listener.onDone(emptyResult);
            return emptyResult;
        }

        // Sort by on-disk size ascending (smallest first)
        var sortedFiles = files.stream().sorted(Comparator.comparingLong(BlitzForge::fileSize)).toList();

        // Prepare executor
        final ExecutorService executor;
        if (service != null && config.model() != null) {
            executor = AdaptiveExecutor.create(service, requireNonNull(config.model()), files.size());
        } else {
            // Fallback simple fixed pool
            int pool = Math.min(Math.max(1, files.size()), Runtime.getRuntime().availableProcessors());
            executor = java.util.concurrent.Executors.newFixedThreadPool(pool);
        }

        int processedCount = 0;
        var results = new ArrayList<FileResult>(files.size());

        try {
            // Warm-up: if includeWorkspace, process the first (smallest) file synchronously to "prime" any server caches
            int startIdx = 0;
            if (config.includeWorkspace()) {
                var first = sortedFiles.getFirst();
                listener.onFileStart(first);
                if (Thread.currentThread().isInterrupted()) {
                    return interruptedResult(processedCount, files);
                }
                var fr = processor.apply(first);
                results.add(fr);
                var c = ++processedCount;
                listener.onFileResult(fr.file(), fr.edited(), fr.errorMessage(), fr.llmOutput());
                listener.onProgress(c, files.size());
                startIdx = 1;
            }

            // Submit the rest using a completion service
            CompletionService<FileResult> completionService = new ExecutorCompletionService<>(executor);
            for (var file : sortedFiles.subList(startIdx, sortedFiles.size())) {
                listener.onFileStart(file);

                interface TokenAwareCallable extends Callable<FileResult>, TokenAware {}

                completionService.submit(new TokenAwareCallable() {
                    @Override
                    public int tokens() {
                        // best-effort token budget estimate
                        try {
                            int fileTokens = Messages.getApproximateTokens(file.read().orElse(""));
                            int workspaceTokens = 0;
                            int historyTokens = 0;
                            if (cm != null && config.includeWorkspace()) {
                                var ctx = cm.topContext();
                                workspaceTokens = Messages.getApproximateTokens(
                                        CodePrompts.instance.getWorkspaceContentsMessages(ctx));
                                var hist = cm.getHistoryMessages().stream().map(m -> (ChatMessage) m).toList();
                                historyTokens = Messages.getApproximateTokens(hist);
                            }
                            int relatedAdd = 0;
                            if (config.relatedK() != null && config.relatedK() > 0) {
                                relatedAdd = Math.round((float) (fileTokens * (config.relatedK() * 0.1)));
                            }
                            return Math.max(1, fileTokens + workspaceTokens + historyTokens + relatedAdd);
                        } catch (Exception e) {
                            return 1;
                        }
                    }

                    @Override
                    public FileResult call() {
                        return processor.apply(file);
                    }
                });
            }

            // Collect completions
            int pending = sortedFiles.size() - startIdx;
            for (int i = 0; i < pending; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    return interruptedResult(processedCount, files);
                }
                try {
                    var fut = completionService.take();
                    var res = fut.get();
                    results.add(res);
                    var c = ++processedCount;
                    listener.onFileResult(res.file(), res.edited(), res.errorMessage(), res.llmOutput());
                    listener.onProgress(c, files.size());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return interruptedResult(processedCount, files);
                } catch (ExecutionException e) {
                    var cause = e.getCause() == null ? e : e.getCause();
                    logger.error("Error during file processing", cause);
                    // fabricate a failure for accounting using a best-effort association
                    var fallbackFile = i < sortedFiles.size() ? sortedFiles.get(i) : sortedFiles.getLast();
                    var failure = new FileResult(fallbackFile, false, "Execution error: " + cause.getMessage(), "");
                    results.add(failure);
                    var c = ++processedCount;
                    listener.onFileResult(fallbackFile, false, failure.errorMessage(), "");
                    listener.onProgress(c, files.size());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        // Aggregate results
        var changedFiles =
                results.stream().filter(FileResult::edited).map(FileResult::file).collect(Collectors.toSet());

        // Build output according to the configured ParallelOutputMode
        var outputStream = results.stream()
                .filter(r -> !r.llmOutput().isBlank())
                .filter(r -> switch (config.outputMode()) {
                    case NONE -> false;
                    case CHANGED -> r.edited();
                    case ALL -> true;
                });

        var outputText = outputStream
                .map(r -> "## " + r.file() + "\n" + r.llmOutput() + "\n\n")
                .collect(Collectors.joining());

        // For MERGE action, avoid injecting an extra UserMessage with instructions; only include AI output if any.
        List<ChatMessage> uiMessages;
        if (outputText.isBlank()) {
            uiMessages = List.of();
        } else if (config.action() == Action.MERGE) {
            uiMessages = List.of(
                    CodePrompts.redactAiMessage(new AiMessage(outputText), EditBlockParser.instance)
                            .orElse(new AiMessage("")));
        } else {
            uiMessages = List.of(
                    new UserMessage(config.instructions()),
                    CodePrompts.redactAiMessage(new AiMessage(outputText), EditBlockParser.instance)
                            .orElse(new AiMessage("")));
        }

        List<String> failures = results.stream()
                .filter(r -> r.errorMessage() != null)
                .map(r -> r.file() + ": " + r.errorMessage())
                .toList();

        var sd = failures.isEmpty()
                ? new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS)
                : new TaskResult.StopDetails(TaskResult.StopReason.TOOL_ERROR, String.join("\n", failures));

        var ctx = (cm != null) ? cm : new IContextManager() {};
        var finalResult = new TaskResult(ctx, config.instructions(), uiMessages, changedFiles, sd);

        listener.onDone(finalResult);
        return finalResult;
    }

    private static long fileSize(ProjectFile file) {
        try {
            return Files.size(file.absPath());
        } catch (IOException | SecurityException e) {
            return Long.MAX_VALUE;
        }
    }

    private TaskResult interruptedResult(int processed, List<ProjectFile> files) {
        var sd = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED, "User cancelled operation.");
        var ctx = (cm != null) ? cm : new IContextManager() {};
        var tr = new TaskResult(ctx, config.instructions(), List.of(), Set.of(), sd);
        listener.onDone(tr);
        logger.debug("Interrupted; processed {} of {}", processed, files.size());
        return tr;
    }
}
