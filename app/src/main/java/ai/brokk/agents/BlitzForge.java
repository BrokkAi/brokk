package ai.brokk.agents;

import static java.util.Objects.requireNonNull;

import ai.brokk.AbstractService;
import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.Service;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.prompts.CodePrompts;
import ai.brokk.prompts.EditBlockParser;
import ai.brokk.util.AdaptiveExecutor;
import ai.brokk.util.Messages;
import ai.brokk.util.TokenAware;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/** Core engine for parallel "BlitzForge" style processing of files. GUI-agnostic and reusable. */
public final class BlitzForge {

    private static final Logger logger = LogManager.getLogger(BlitzForge.class);

    /** How much of the per-file output to include in the aggregated result. */
    public enum ParallelOutputMode {
        NONE,
        ALL,
        CHANGED
    }

    /** Listener for lifecycle and per-file console access. Callbacks may be invoked from worker threads. */
    public interface Listener {
        default void onStart(int total) {}
        /** Called with the initial, sorted queue of files before any processing begins. */
        default void onQueued(List<ProjectFile> queued) {}

        default void onFileStart(ProjectFile file) {}

        IConsoleIO getConsoleIO(ProjectFile file);

        default void onFileResult(ProjectFile file, boolean edited, @Nullable String errorMessage, String llmOutput) {}

        /** Called exactly once when the entire run is complete (success, empty, or interrupted). */
        default void onComplete(TaskResult result) {}
    }

    /** Configuration for a BlitzForge run. */
    public record RunConfig(
            String instructions,
            StreamingChatModel model,
            Supplier<String> perFileContext,
            Supplier<String> sharedContext,
            String contextFilter,
            ParallelOutputMode outputMode) {}

    /** Result of processing a single file. */
    public record FileResult(ProjectFile file, boolean edited, @Nullable String errorMessage, String llmOutput) {}

    private final IContextManager cm;
    private final AbstractService service;
    private final RunConfig config;
    private final Listener listener;

    public BlitzForge(IContextManager cm, AbstractService service, RunConfig config, Listener listener) {
        this.cm = cm;
        this.service = service;
        this.config = config;
        this.listener = listener;
    }

    /**
     * Execute a set of per-file tasks in parallel, using AdaptiveExecutor token-aware scheduling when possible. The
     * provided processor should be thread-safe.
     *
     * <p><strong>Invariant:</strong> Any returned {@link TaskResult} carries a live (unfrozen) {@link Context}.
     * This is enforced by an assertion in the {@code TaskResult} constructor. All context creation for the result
     * must use {@link #createLiveResultingContext()} to ensure compliance with this invariant.
     *
     * @param files the collection of files to process in parallel
     * @param processor a thread-safe function that processes each file and returns a {@link FileResult}
     * @return a {@link TaskResult} containing aggregated results, with a live context
     */
    public TaskResult executeParallel(Collection<ProjectFile> files, Function<ProjectFile, FileResult> processor) {
        logger.debug(
                "BlitzForge.executeParallel start: totalFiles={}, thread={}",
                files.size(),
                Thread.currentThread().getName());
        listener.onStart(files.size());

        if (files.isEmpty()) {
            // No files -> produce an empty successful TaskResult whose resultingContext is the current top context
            var meta = new TaskResult.TaskMeta(
                    TaskResult.Type.BLITZFORGE, Service.ModelConfig.from(config.model(), service));
            var emptyResult = new TaskResult(
                    cm,
                    config.instructions(),
                    List.of(),
                    createLiveResultingContext(),
                    new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS),
                    meta);
            listener.onComplete(emptyResult);
            return emptyResult;
        }

        // Sort by on-disk size ascending (smallest first)
        var sortedFiles = files.stream()
                .sorted(Comparator.comparingLong(BlitzForge::fileSize))
                .toList();
        // Notify listener of the initial queue ordering
        listener.onQueued(sortedFiles);

        // Prepare executor
        final ExecutorService executor;
        if (config.model() instanceof Service.UnavailableStreamingModel) {
            // Fallback simple fixed pool for tests
            int pool = Math.min(Math.max(1, files.size()), Runtime.getRuntime().availableProcessors());
            executor = Executors.newFixedThreadPool(pool);
        } else {
            executor = AdaptiveExecutor.create(service, config.model(), files.size());
        }

        int processedCount = 0;
        var results = new ArrayList<FileResult>(files.size());

        try {
            // Warm-up: if sharedContext is non-empty, process the first (smallest) file synchronously to "prime"
            // any
            // server caches
            int startIdx = 0;
            if (!config.sharedContext().get().isBlank()) {
                var first = sortedFiles.getFirst();
                listener.onFileStart(first);
                if (Thread.currentThread().isInterrupted()) {
                    return interruptedResult(processedCount, files);
                }
                var fr = processor.apply(first);
                results.add(fr);
                ++processedCount;
                listener.onFileResult(fr.file(), fr.edited(), fr.errorMessage(), fr.llmOutput());
                startIdx = 1;
            }

            // Submit the rest using a completion service
            CompletionService<FileResult> completionService = new ExecutorCompletionService<>(executor);
            IdentityHashMap<Future<FileResult>, ProjectFile> futureFiles = new IdentityHashMap<>();
            for (var file : sortedFiles.subList(startIdx, sortedFiles.size())) {
                listener.onFileStart(file);

                interface TokenAwareCallable extends Callable<FileResult>, TokenAware {}

                var future = completionService.submit(new TokenAwareCallable() {
                    @Override
                    public int tokens() {
                        int fileTokens =
                                Messages.getApproximateTokens(file.read().orElse(""));
                        int sharedTokens = 0;
                        int perFileCtxTokens = 0;
                        try {
                            var shared = config.sharedContext().get();
                            sharedTokens = Messages.getApproximateTokens(shared);
                        } catch (Exception ignore) {
                            // ignore
                        }
                        try {
                            var pfc = config.perFileContext().get();
                            perFileCtxTokens = Messages.getApproximateTokens(pfc);
                        } catch (Exception ignore) {
                            // ignore
                        }
                        return Math.max(1, fileTokens + sharedTokens + perFileCtxTokens);
                    }

                    @Override
                    public FileResult call() {
                        return processor.apply(file);
                    }
                });
                futureFiles.put(future, file);
            }

            // Collect completions
            int pending = sortedFiles.size() - startIdx;
            for (int i = 0; i < pending; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    logger.debug("BlitzForge.executeParallel main thread interrupted during collection");
                    return interruptedResult(processedCount, files);
                }

                Future<FileResult> fut;
                try {
                    fut = completionService.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.debug("BlitzForge.executeParallel interrupted while taking from completion service");
                    return interruptedResult(processedCount, files);
                }

                try {
                    var res = fut.get();
                    results.add(res);
                    ++processedCount;
                    listener.onFileResult(res.file(), res.edited(), res.errorMessage(), res.llmOutput());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.debug("BlitzForge.executeParallel interrupted while getting result");
                    return interruptedResult(processedCount, files);
                } catch (ExecutionException e) {
                    var cause = e.getCause() == null ? e : e.getCause();
                    logger.error("Error during file processing", cause);
                    var source = requireNonNull(futureFiles.get(fut));
                    var failure = new FileResult(source, false, "Execution error: " + cause.getMessage(), "");
                    results.add(failure);
                    ++processedCount;
                    listener.onFileResult(source, false, failure.errorMessage(), "");
                }
            }
        } finally {
            executor.shutdownNow();
        }

        // Aggregate results
        var changedFiles = results.stream()
                .filter(FileResult::edited)
                .map(FileResult::file)
                .collect(Collectors.toSet());

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

        List<ChatMessage> uiMessages;
        if (outputText.isBlank()) {
            uiMessages = List.of();
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

        // Build a resulting Context that represents the current topContext with any changed files added as editable
        var resultingCtx = createLiveResultingContext().addPathFragments(cm.toPathFragments(changedFiles));

        var meta =
                new TaskResult.TaskMeta(TaskResult.Type.BLITZFORGE, Service.ModelConfig.from(config.model(), service));
        var finalResult = new TaskResult(cm, config.instructions(), uiMessages, resultingCtx, sd, meta);

        logger.debug(
                "BlitzForge.executeParallel delivering onComplete: reason={}, processed={}/{}, thread={}",
                finalResult.stopDetails().reason(),
                processedCount,
                files.size(),
                Thread.currentThread().getName());
        listener.onComplete(finalResult);
        return finalResult;
    }

    /**
     * Creates a live (unfrozen) Context from the current top context.
     *
     * <p>TaskResult requires a live (unfrozen) context as enforced by an assertion in its
     * constructor. This helper centralizes the invariant and eliminates duplication across
     * multiple result-building code paths.
     *
     * @return an unfrozen Context representing the current top context state
     */
    private Context createLiveResultingContext() {
        return Context.unfreeze(cm.topContext());
    }

    private static long fileSize(ProjectFile file) {
        try {
            return Files.size(file.absPath());
        } catch (IOException | SecurityException e) {
            return Long.MAX_VALUE;
        }
    }

    private TaskResult interruptedResult(int processed, Collection<ProjectFile> files) {
        logger.debug("BlitzForge interrupted: processed {} of {}", processed, files.size());
        var sd = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED, "User cancelled operation.");
        var meta =
                new TaskResult.TaskMeta(TaskResult.Type.BLITZFORGE, Service.ModelConfig.from(config.model(), service));
        var tr = new TaskResult(cm, config.instructions(), List.of(), createLiveResultingContext(), sd, meta);
        listener.onComplete(tr);
        return tr;
    }
}
