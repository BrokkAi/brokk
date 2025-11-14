package ai.brokk.executor.jobs;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.Service;
import ai.brokk.agents.CodeAgent;
import ai.brokk.executor.io.HeadlessHttpConsole;
import ai.brokk.tasks.TaskList;
import com.google.common.base.Splitter;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Executes long-running Brokk jobs using ContextManager, producing durable events
 * via HeadlessHttpConsole and updating JobStore status.
 *
 * <p>Each JobRunner instance processes one job at a time on a single-threaded executor.
 * Callers should instantiate one JobRunner per session or share a single instance across
 * sessions, ensuring only one job executes at a time (enforced by activeJobId guard).
 */
public final class JobRunner {
    private static final Logger logger = LogManager.getLogger(JobRunner.class);

    private final ContextManager cm;
    private final JobStore store;
    private final ExecutorService runner;
    private volatile @Nullable HeadlessHttpConsole console;
    private volatile @Nullable String activeJobId;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private enum Mode {
        ARCHITECT,
        CODE,
        ASK
    }

    private static Mode parseMode(JobSpec spec) {
        try {
            var tags = spec.tags();
            var raw = tags.getOrDefault("mode", "").trim();
            if (raw.isEmpty()) return Mode.ARCHITECT;
            return Mode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (Exception ignore) {
            return Mode.ARCHITECT;
        }
    }

    /**
     * Create a new JobRunner.
     *
     * @param cm The ContextManager for task execution
     * @param store The JobStore for persistence
     */
    public JobRunner(ContextManager cm, JobStore store) {
        this.cm = cm;
        this.store = store;
        this.runner = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "JobRunner");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Execute a job asynchronously.
     *
     * @param jobId The unique job identifier
     * @param spec The job specification
     * @return A CompletableFuture that completes when the job finishes or fails
     * @throws IllegalStateException if another job is already running and has a different jobId
     */
    public synchronized CompletableFuture<Void> runAsync(String jobId, JobSpec spec) {
        // Guard: prevent concurrent execution of different jobs
        if (activeJobId != null && !activeJobId.equals(jobId)) {
            var fut = new CompletableFuture<Void>();
            fut.completeExceptionally(new IllegalStateException("Another job is already running: " + activeJobId));
            return fut;
        }

        activeJobId = jobId;
        cancelled.set(false);
        console = new HeadlessHttpConsole(store, jobId);
        final var previousIo = cm.getIo();
        cm.setIo(console);
        logger.info("Job {} attaching streaming console", jobId);

        // Transition status to RUNNING
        try {
            JobStatus status = store.loadStatus(jobId);
            if (status == null) {
                status = JobStatus.queued(jobId);
            }
            status = status.withState("RUNNING");
            store.updateStatus(jobId, status);
            logger.info("Job {} transitioned to RUNNING", jobId);
        } catch (Exception e) {
            logger.warn("Failed to update job status to RUNNING for {}", jobId, e);
        }

        // Emit start event
        try {
            console.showNotification(IConsoleIO.NotificationRole.INFO, "Job started: " + jobId);
        } catch (Throwable ignore) {
            // Non-critical: event writing failed, continue
        }

        var future = new CompletableFuture<Void>();

        runner.execute(() -> {
            try {
                // Parse tasks from spec.taskInput (split by newlines, trim, ignore blanks)
                List<TaskList.TaskItem> tasks = parseTasks(spec.taskInput());
                int total = tasks.size();
                var completed = new java.util.concurrent.atomic.AtomicInteger(0);

                logger.info("Job {} parsed {} task(s)", jobId, total);

                // Determine execution mode (default ARCHITECT)
                Mode mode = parseMode(spec);
                var rawCodeModelName = spec.codeModel();
                var trimmedCodeModelName = rawCodeModelName == null ? null : rawCodeModelName.trim();
                var hasCodeModelOverride = trimmedCodeModelName != null && !trimmedCodeModelName.isEmpty();

                final StreamingChatModel architectPlannerModel =
                        mode == Mode.ARCHITECT ? resolveModelOrThrow(spec.plannerModel()) : null;
                final StreamingChatModel architectCodeModel = mode == Mode.ARCHITECT
                        ? (hasCodeModelOverride
                                ? resolveModelOrThrow(Objects.requireNonNull(trimmedCodeModelName))
                                : defaultCodeModel())
                        : null;
                final StreamingChatModel askPlannerModel =
                        mode == Mode.ASK ? resolveModelOrThrow(spec.plannerModel()) : null;
                final StreamingChatModel askCodeModel = mode == Mode.ASK ? defaultCodeModel() : null;
                final StreamingChatModel codeModeModel = mode == Mode.CODE
                        ? (hasCodeModelOverride
                                ? resolveModelOrThrow(Objects.requireNonNull(trimmedCodeModelName))
                                : defaultCodeModel())
                        : null;

                var service = cm.getService();
                String plannerModelNameForLog =
                        switch (mode) {
                            case ARCHITECT -> service.nameOf(Objects.requireNonNull(architectPlannerModel));
                            case ASK -> service.nameOf(Objects.requireNonNull(askPlannerModel));
                            case CODE -> {
                                var plannerName = spec.plannerModel();
                                yield plannerName.isBlank() ? "(unused)" : plannerName.trim();
                            }
                        };
                String codeModelNameForLog =
                        switch (mode) {
                            case ARCHITECT -> service.nameOf(Objects.requireNonNull(architectCodeModel));
                            case ASK -> service.nameOf(Objects.requireNonNull(askCodeModel));
                            case CODE -> service.nameOf(Objects.requireNonNull(codeModeModel));
                        };
                boolean usesDefaultCodeModel =
                        switch (mode) {
                            case ARCHITECT -> !hasCodeModelOverride;
                            case ASK -> true;
                            case CODE -> !hasCodeModelOverride;
                        };
                if (plannerModelNameForLog == null || plannerModelNameForLog.isBlank()) {
                    plannerModelNameForLog = mode == Mode.CODE ? "(unused)" : "(unknown)";
                }
                if (codeModelNameForLog == null || codeModelNameForLog.isBlank()) {
                    codeModelNameForLog = usesDefaultCodeModel ? "(default)" : "(unknown)";
                } else if (usesDefaultCodeModel) {
                    codeModelNameForLog = codeModelNameForLog + " (default)";
                }

                logger.info(
                        "Job {} mode={} plannerModel={} codeModel={}",
                        jobId,
                        mode,
                        plannerModelNameForLog,
                        codeModelNameForLog);

                // Execute within submitLlmAction to honor cancellation semantics
                cm.submitLlmAction(() -> {
                            for (TaskList.TaskItem task : tasks) {
                                if (cancelled.get()) {
                                    logger.info("Job {} execution cancelled by user", jobId);
                                    break;
                                }

                                logger.info("Job {} executing task: {}", jobId, task.text());
                                try {
                                    switch (mode) {
                                        case ARCHITECT -> {
                                            cm.executeTask(
                                                    task,
                                                    Objects.requireNonNull(
                                                            architectPlannerModel,
                                                            "plannerModel required for ARCHITECT jobs"),
                                                    Objects.requireNonNull(
                                                            architectCodeModel,
                                                            "code model unavailable for ARCHITECT jobs"),
                                                    spec.autoCommit(),
                                                    spec.autoCompress());
                                        }
                                        case CODE -> {
                                            var agent = new CodeAgent(
                                                    cm,
                                                    Objects.requireNonNull(
                                                            codeModeModel, "code model unavailable for CODE jobs"));
                                            try (var scope = cm.beginTask(task.text(), false)) {
                                                var result = agent.runTask(task.text(), Set.of());
                                                scope.append(result);
                                            }
                                        }
                                        case ASK -> {
                                            // Read-only execution: never auto-commit; allow compression if requested
                                            cm.executeTask(
                                                    new TaskList.TaskItem(task.title(), task.text(), false),
                                                    Objects.requireNonNull(
                                                            askPlannerModel, "plannerModel required for ASK jobs"),
                                                    Objects.requireNonNull(
                                                            askCodeModel, "code model unavailable for ASK jobs"),
                                                    false,
                                                    spec.autoCompress());
                                        }
                                    }

                                    completed.incrementAndGet();

                                    // Update progress
                                    int progress = total == 0 ? 100 : (int) ((completed.get() * 100.0) / total);
                                    try {
                                        JobStatus s = store.loadStatus(jobId);
                                        if (s != null) {
                                            s = s.withProgress(progress);
                                            store.updateStatus(jobId, s);
                                            logger.debug("Job {} progress updated: {}%", jobId, progress);
                                        }
                                    } catch (Exception e) {
                                        logger.debug("Unable to update job progress {}% for {}", progress, jobId, e);
                                    }
                                } catch (Exception e) {
                                    logger.warn("Task execution failed for job {}: {}", jobId, e.getMessage());
                                    // Continue to next task or exit depending on requirements
                                    throw e;
                                }
                            }
                        })
                        .join();

                // Optional compress after execution:
                // - For ARCHITECT: per-task compression already honored via spec.autoCompress().
                // - For CODE/ASK: run a single compression pass if requested.
                if (mode != Mode.ARCHITECT && spec.autoCompress()) {
                    logger.info("Job {} auto-compressing history", jobId);
                    try {
                        cm.compressHistoryAsync().join();
                    } catch (Exception e) {
                        logger.warn("Auto-compress failed for job {}", jobId, e);
                    }
                }

                // Determine final status: completed or cancelled
                JobStatus current = store.loadStatus(jobId);
                if (current != null) {
                    if (cancelled.get()) {
                        current = current.cancelled();
                        logger.info("Job {} marked as CANCELLED", jobId);
                    } else {
                        current = current.completed(null);
                        logger.info("Job {} completed successfully", jobId);
                    }
                    if (console != null) {
                        long lastSeq = console.getLastSeq();
                        current = current.withMetadata("lastSeq", Long.toString(lastSeq));
                    }
                    store.updateStatus(jobId, current);
                }

                future.complete(null);
            } catch (Throwable t) {
                logger.error("Job {} execution failed", jobId, t);

                var failure = unwrapFailure(t);
                var errorMessage = formatThrowableMessage(failure);

                // Emit error event
                if (console != null) {
                    try {
                        console.toolError(errorMessage, "Job error");
                    } catch (Throwable ignore) {
                        // Non-critical: event writing failed
                    }
                }

                // Update status to FAILED
                try {
                    JobStatus s = store.loadStatus(jobId);
                    if (s == null) {
                        s = JobStatus.queued(jobId);
                    }
                    s = s.failed(errorMessage);
                    if (console != null) {
                        long lastSeq = console.getLastSeq();
                        s = s.withMetadata("lastSeq", Long.toString(lastSeq));
                    }
                    store.updateStatus(jobId, s);
                } catch (Exception e2) {
                    logger.warn("Failed to persist FAILED status for job {}", jobId, e2);
                }

                future.completeExceptionally(failure);
            } finally {
                // Clean up
                if (console != null) {
                    try {
                        console.shutdown(5);
                    } catch (Throwable ignore) {
                        // Non-critical: shutdown failed
                    }
                }
                // Restore original console. HeadlessHttpConsole is installed only for the job duration so that
                // all ContextManager/agents IConsoleIO callbacks flow to the JobStore; then the previous console is
                // restored.
                cm.setIo(previousIo);
                activeJobId = null;
                logger.info("Job {} execution ended", jobId);
            }
        });

        return future;
    }

    /**
     * Request cancellation of an active job.
     *
     * <p>This is a cooperative cancel: it interrupts the LLM action and attempts to transition
     * the job status to CANCELLED if not already in a terminal state. The actual cancellation
     * is enforced by the cancelled flag checked during task loop execution.
     *
     * @param jobId The job ID to cancel
     */
    public void cancel(String jobId) {
        if (activeJobId == null || !Objects.equals(activeJobId, jobId)) {
            logger.info("Cancel requested for job {}, but no active job or different jobId", jobId);
            return;
        }

        logger.info("Cancelling job {}", jobId);
        cancelled.set(true);

        // Interrupt LLM action to stop streaming/task execution
        try {
            cm.interruptLlmAction();
        } catch (Throwable ignore) {
            logger.debug("Failed to interrupt LLM action", ignore);
        }

        // Update status to CANCELLED if not already terminal
        try {
            JobStatus s = store.loadStatus(jobId);
            if (s != null) {
                String state = s.state();
                // Only transition if not already in a terminal state
                if (!"COMPLETED".equals(state) && !"FAILED".equals(state) && !"CANCELLED".equals(state)) {
                    s = s.cancelled();
                    long lastSeq = console != null ? console.getLastSeq() : -1;
                    s = s.withMetadata("lastSeq", Long.toString(lastSeq));
                    store.updateStatus(jobId, s);
                    logger.info("Job {} status updated to CANCELLED", jobId);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to mark job {} as CANCELLED", jobId, e);
        }
    }

    private StreamingChatModel resolveModelOrThrow(String name) {
        var model = cm.getService().getModel(new Service.ModelConfig(name));
        if (model == null) {
            throw new IllegalArgumentException("MODEL_UNAVAILABLE: " + name);
        }
        return model;
    }

    private StreamingChatModel defaultCodeModel() {
        return cm.getCodeModel();
    }

    private static Throwable unwrapFailure(Throwable throwable) {
        var current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)) {
            var cause = current.getCause();
            if (cause == null) {
                break;
            }
            current = cause;
        }
        return current;
    }

    private static String formatThrowableMessage(Throwable throwable) {
        var message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": " + message;
    }

    /**
     * Parse task input string into a list of TaskItems.
     *
     * <p>Tasks are separated by newlines. Each line is trimmed and blank lines are ignored.
     * If the input is empty or blank, returns an empty list.
     *
     * @param taskInput The task input string
     * @return A list of TaskItems
     */
    private static List<TaskList.TaskItem> parseTasks(String taskInput) {
        if (taskInput.isBlank()) {
            return List.of();
        }

        var list = new ArrayList<TaskList.TaskItem>();
        for (String line : Splitter.on('\n').split(taskInput)) {
            var trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                list.add(new TaskList.TaskItem("", trimmed, false));
            }
        }

        // If no tasks were parsed, try to use the entire input as a single task
        if (list.isEmpty()) {
            String trimmed = taskInput.trim();
            if (!trimmed.isEmpty()) {
                list.add(new TaskList.TaskItem("", trimmed, false));
            }
        }

        return list;
    }
}
