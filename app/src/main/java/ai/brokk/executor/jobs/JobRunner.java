package ai.brokk.executor.jobs;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.Llm;
import ai.brokk.Service;
import ai.brokk.TaskResult;
import ai.brokk.agents.CodeAgent;
import ai.brokk.agents.LutzAgent;
import ai.brokk.agents.SearchAgent;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragments;
import ai.brokk.executor.io.HeadlessHttpConsole;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.tasks.TaskList;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.io.IOException;
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
        ASK,
        SEARCH,
        REVIEW,
        LUTZ
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
                // Determine execution mode (default ARCHITECT)
                Mode mode = parseMode(spec);

                // Mutable holder for completion result (modified in lambda, read after join)
                Object[] completionResultHolder = {null};

                var completed = new java.util.concurrent.atomic.AtomicInteger(0);

                var rawCodeModelName = spec.codeModel();
                var trimmedCodeModelName = rawCodeModelName == null ? null : rawCodeModelName.trim();
                var hasCodeModelOverride = trimmedCodeModelName != null && !trimmedCodeModelName.isEmpty();

                final StreamingChatModel architectPlannerModel =
                        mode == Mode.ARCHITECT || mode == Mode.LUTZ ? resolveModelOrThrow(spec.plannerModel()) : null;
                final StreamingChatModel architectCodeModel = (mode == Mode.ARCHITECT || mode == Mode.LUTZ)
                        ? (hasCodeModelOverride
                                ? resolveModelOrThrow(Objects.requireNonNull(trimmedCodeModelName))
                                : defaultCodeModel())
                        : null;
                final StreamingChatModel reviewPlannerModel =
                        mode == Mode.REVIEW ? resolveModelOrThrow(spec.plannerModel()) : null;
                // Resolve scan model for REVIEW mode (prefer explicit spec.scanModel() if provided; otherwise project
                // default)
                final StreamingChatModel reviewScanModel = mode == Mode.REVIEW
                        ? (spec.scanModel() != null && !spec.scanModel().trim().isEmpty()
                                ? resolveModelOrThrow(spec.scanModel().trim())
                                : cm.getService().getScanModel())
                        : null;
                final StreamingChatModel askPlannerModel =
                        mode == Mode.ASK ? resolveModelOrThrow(spec.plannerModel()) : null;
                final StreamingChatModel codeModeModel = mode == Mode.CODE
                        ? (hasCodeModelOverride
                                ? resolveModelOrThrow(Objects.requireNonNull(trimmedCodeModelName))
                                : defaultCodeModel())
                        : null;

                var service = cm.getService();

                // Resolve a scan model for SEARCH mode if needed (prefer explicit spec.scanModel() if provided;
                // otherwise project default)
                final StreamingChatModel searchPlannerModel = mode == Mode.SEARCH
                        ? (spec.scanModel() != null && !spec.scanModel().trim().isEmpty()
                                ? resolveModelOrThrow(spec.scanModel().trim())
                                : cm.getService().getScanModel())
                        : null;

                String plannerModelNameForLog =
                        switch (mode) {
                            case ARCHITECT, LUTZ -> service.nameOf(Objects.requireNonNull(architectPlannerModel));
                            case ASK -> service.nameOf(Objects.requireNonNull(askPlannerModel));
                            case SEARCH -> service.nameOf(Objects.requireNonNull(searchPlannerModel));
                            case CODE -> {
                                var plannerName = spec.plannerModel();
                                yield plannerName.isBlank() ? "(unused)" : plannerName.trim();
                            }
                            case REVIEW -> service.nameOf(Objects.requireNonNull(reviewPlannerModel));
                        };
                String codeModelNameForLog =
                        switch (mode) {
                            case ARCHITECT, LUTZ -> service.nameOf(Objects.requireNonNull(architectCodeModel));
                            case ASK -> "(default, ignored for ASK)";
                            case SEARCH -> "(default, ignored for SEARCH)";
                            case CODE -> service.nameOf(Objects.requireNonNull(codeModeModel));
                            case REVIEW -> "(default, ignored for REVIEW)";
                        };
                boolean usesDefaultCodeModel =
                        switch (mode) {
                            case ARCHITECT, LUTZ -> !hasCodeModelOverride;
                            case ASK, SEARCH, REVIEW -> true;
                            case CODE -> !hasCodeModelOverride;
                        };
                if (plannerModelNameForLog == null || plannerModelNameForLog.isBlank()) {
                    plannerModelNameForLog = (mode == Mode.CODE) ? "(unused)" : "(unknown)";
                }
                if (codeModelNameForLog == null || codeModelNameForLog.isBlank()) {
                    codeModelNameForLog = usesDefaultCodeModel ? "(default)" : "(unknown)";
                } else if (usesDefaultCodeModel && mode != Mode.ASK && mode != Mode.SEARCH && mode != Mode.REVIEW) {
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
                            if (cancelled.get()) {
                                logger.info("Job {} execution cancelled by user", jobId);
                                return;
                            }
                            try {
                                switch (mode) {
                                    case ARCHITECT -> {
                                        cm.executeTask(
                                                new TaskList.TaskItem("", spec.taskInput(), false),
                                                Objects.requireNonNull(
                                                        architectPlannerModel,
                                                        "plannerModel required for ARCHITECT jobs"),
                                                Objects.requireNonNull(
                                                        architectCodeModel,
                                                        "code model unavailable for ARCHITECT jobs"));
                                    }
                                    case LUTZ -> {
                                        // Phase 1: Use SearchAgent to generate a task list from the initial task
                                        try (var scope = cm.beginTaskUngrouped(spec.taskInput())) {
                                            var context = cm.liveContext();
                                            var searchAgent = new LutzAgent(
                                                    context,
                                                    spec.taskInput(),
                                                    Objects.requireNonNull(
                                                            architectPlannerModel,
                                                            "plannerModel required for LUTZ jobs"),
                                                    SearchPrompts.Objective.TASKS_ONLY,
                                                    scope);
                                            var taskListResult = searchAgent.execute();
                                            scope.append(taskListResult);
                                        }
                                        // Task list is now in the live context and persisted by the scope
                                        logger.debug("LUTZ Phase 1 complete: task list generated");

                                        // Phase 2: Check if task list was generated; if empty, mark job complete
                                        var generatedTasks = cm.getTaskList().tasks();
                                        if (generatedTasks.isEmpty()) {
                                            var msg = "SearchAgent generated no tasks for: " + spec.taskInput();
                                            logger.info("LUTZ job {}: {}", jobId, msg);
                                            if (console != null) {
                                                try {
                                                    console.showNotification(IConsoleIO.NotificationRole.INFO, msg);
                                                } catch (Throwable ignore) {
                                                    // Non-critical: event writing failed
                                                }
                                            }
                                            // No tasks generated; outer loop will handle completion/progress
                                        } else {
                                            // Phase 3: Execute each generated incomplete task sequentially
                                            logger.debug(
                                                    "LUTZ Phase 2 complete: {} task(s) to execute",
                                                    generatedTasks.size());
                                            var incompleteTasks = generatedTasks.stream()
                                                    .filter(t -> !t.done())
                                                    .toList();
                                            logger.debug(
                                                    "LUTZ will execute {} incomplete task(s)", incompleteTasks.size());

                                            for (TaskList.TaskItem generatedTask : incompleteTasks) {
                                                if (cancelled.get()) {
                                                    logger.info(
                                                            "LUTZ job {} execution cancelled during task iteration",
                                                            jobId);
                                                    return; // Cancelled: exit submitLlmAction early to prevent
                                                    // further job completion handling in the outer loop
                                                }

                                                logger.info(
                                                        "LUTZ job {} executing generated task: {}",
                                                        jobId,
                                                        generatedTask.text());
                                                try {
                                                    cm.executeTask(
                                                            generatedTask,
                                                            architectPlannerModel,
                                                            Objects.requireNonNull(architectCodeModel));
                                                } catch (Exception e) {
                                                    logger.warn(
                                                            "Generated task execution failed for job {}: {}",
                                                            jobId,
                                                            e.getMessage());
                                                    throw e;
                                                }
                                            }

                                            logger.debug("LUTZ Phase 3 complete: all generated tasks executed");
                                        }
                                    }
                                    case CODE -> {
                                        var agent = new CodeAgent(
                                                cm,
                                                Objects.requireNonNull(
                                                        codeModeModel, "code model unavailable for CODE jobs"));
                                        try (var scope = cm.beginTaskUngrouped(spec.taskInput())) {
                                            var result = agent.execute(spec.taskInput(), Set.of());
                                            scope.append(result);
                                        }
                                    }
                                    case ASK -> {
                                        // Read-only ASK execution: perform optional pre-scan (Context Agent)
                                        // then generate a single, final written answer using the plannerModel
                                        // and the current Workspace. Do NOT invoke SearchAgent.execute() or
                                        // any tools that could modify the workspace. Append the answer to the
                                        // task scope and continue.
                                        try (var scope = cm.beginTaskUngrouped(spec.taskInput())) {
                                            var context = cm.liveContext();

                                            // Optional pre-scan: resolve scan model similarly to SEARCH mode.
                                            if (spec.preScan()) {
                                                // Construct agent only for potential pre-scan usage (no execute).
                                                var searchAgent = new LutzAgent(
                                                        context,
                                                        spec.taskInput(),
                                                        Objects.requireNonNull(
                                                                askPlannerModel, "plannerModel required for ASK jobs"),
                                                        SearchPrompts.Objective.ANSWER_ONLY,
                                                        scope);

                                                String rawScanModel = spec.scanModel();
                                                String trimmedScanModel =
                                                        rawScanModel == null ? "" : rawScanModel.trim();

                                                // Emit deterministic start NOTIFICATION so headless clients/tests
                                                // can observe the pre-scan start.
                                                try {
                                                    store.appendEvent(
                                                            jobId,
                                                            JobEvent.of(
                                                                    "NOTIFICATION",
                                                                    "Brokk Context Engine: analyzing repository context..."));
                                                } catch (IOException ioe) {
                                                    logger.warn(
                                                            "Failed to append pre-scan start notification event for job {}: {}",
                                                            jobId,
                                                            ioe.getMessage(),
                                                            ioe);
                                                }

                                                StreamingChatModel scanModelToUse = null;
                                                try {
                                                    scanModelToUse = !trimmedScanModel.isEmpty()
                                                            ? resolveModelOrThrow(trimmedScanModel)
                                                            : cm.getService().getScanModel();
                                                } catch (IllegalArgumentException iae) {
                                                    // resolveModelOrThrow may throw; log and continue without
                                                    // failing job.
                                                    logger.warn(
                                                            "Pre-scan model unavailable for job {}: {}",
                                                            jobId,
                                                            iae.getMessage());
                                                } catch (Exception e) {
                                                    logger.warn(
                                                            "Unexpected error during pre-scan model resolution for job {}: {}",
                                                            jobId,
                                                            e.getMessage(),
                                                            e);
                                                }

                                                if (scanModelToUse == null) {
                                                    // No scan model available; log and skip pre-scan but still emit
                                                    // completion below.
                                                    logger.warn(
                                                            "ASK pre-scan requested but no scan model is available (spec.scanModel='{}'). Skipping pre-scan for job {}.",
                                                            trimmedScanModel,
                                                            jobId);
                                                } else {
                                                    // Attempt the pre-scan, but do not allow failures to abort the
                                                    // job.
                                                    try {
                                                        context = searchAgent.scanContext();
                                                    } catch (InterruptedException ie) {
                                                        // Preserve interruption status but continue with the job.
                                                        Thread.currentThread().interrupt();
                                                        logger.warn(
                                                                "Pre-scan interrupted for job {}: {}",
                                                                jobId,
                                                                ie.getMessage(),
                                                                ie);
                                                    } catch (IllegalArgumentException iae) {
                                                        // Model resolution or argument problems: log and continue.
                                                        logger.warn(
                                                                "Pre-scan skipped due to model error for job {}: {}",
                                                                jobId,
                                                                iae.getMessage());
                                                    } catch (Exception ex) {
                                                        // Any other exception during pre-scan should not fail the
                                                        // job.
                                                        logger.warn(
                                                                "Pre-scan failed for job {}: {}",
                                                                jobId,
                                                                ex.getMessage(),
                                                                ex);
                                                    }
                                                }

                                                // Emit deterministic completion NOTIFICATION so headless
                                                // clients/tests can reliably observe that the Context Engine
                                                // pre-scan phase finished.
                                                try {
                                                    store.appendEvent(
                                                            jobId,
                                                            JobEvent.of(
                                                                    "NOTIFICATION",
                                                                    "Brokk Context Engine: complete — contextual insights added to Workspace."));
                                                } catch (IOException ioe) {
                                                    logger.warn(
                                                            "Failed to append pre-scan completion event for job {}: {}",
                                                            jobId,
                                                            ioe.getMessage(),
                                                            ioe);
                                                }
                                            }

                                            try {
                                                // Use helper that builds a workspace-only prompt and calls the
                                                // planner model.
                                                TaskResult askResult = askUsingPlannerModel(
                                                        context,
                                                        Objects.requireNonNull(askPlannerModel),
                                                        spec.taskInput());
                                                scope.append(askResult);
                                            } catch (Throwable t) {
                                                // Do not allow a planner-model failure to abort the entire job.
                                                logger.error(
                                                        "ASK direct-answer failed for job {}: {}",
                                                        jobId,
                                                        t.getMessage(),
                                                        t);
                                                // Report to headless console if available (best-effort).
                                                if (console != null) {
                                                    try {
                                                        console.toolError(
                                                                "ASK direct-answer failed: "
                                                                        + (t.getMessage() == null
                                                                                ? t.getClass()
                                                                                        .getSimpleName()
                                                                                : t.getMessage()),
                                                                "ASK error");
                                                    } catch (Throwable ignore) {
                                                        // best-effort only
                                                    }
                                                }
                                                // Append a non-fatal TaskResult indicating the failure so the task
                                                // has a record,
                                                // but do not rethrow (so job status handling can complete
                                                // normally).
                                                var stopDetails = new TaskResult.StopDetails(
                                                        TaskResult.StopReason.LLM_ERROR,
                                                        t.getMessage() == null
                                                                ? t.getClass().getSimpleName()
                                                                : t.getMessage());
                                                List<ChatMessage> ui = List.of(
                                                        new UserMessage(spec.taskInput()),
                                                        new SystemMessage("ASK direct-answer failed: "
                                                                + stopDetails.explanation()));
                                                var failureResult = new TaskResult(
                                                        cm,
                                                        "ASK: " + spec.taskInput() + " [LLM_ERROR]",
                                                        ui,
                                                        context,
                                                        stopDetails,
                                                        null);
                                                try {
                                                    scope.append(failureResult);
                                                } catch (Throwable e2) {
                                                    // If appending also fails, log it but keep proceeding so we can
                                                    // update job status normally.
                                                    logger.warn(
                                                            "Failed to append ASK failure result for job {}: {}",
                                                            jobId,
                                                            e2.getMessage(),
                                                            e2);
                                                }
                                                // Do not rethrow; allow outer flow to mark job completed
                                                // (read-only) where appropriate.
                                            }
                                        }
                                    }
                                    case SEARCH -> {
                                        // Read-only repository search using a scan model (spec.scanModel preferred,
                                        // otherwise project default)
                                        try (var scope = cm.beginTaskUngrouped(spec.taskInput())) {
                                            var context = cm.liveContext();

                                            // Determine scan model: prefer explicit spec.scanModel() if provided,
                                            // otherwise use project default
                                            String rawScanModel = spec.scanModel();
                                            String trimmedScanModel = rawScanModel == null ? null : rawScanModel.trim();
                                            final StreamingChatModel scanModelToUse =
                                                    (trimmedScanModel != null && !trimmedScanModel.isEmpty())
                                                            ? resolveModelOrThrow(trimmedScanModel)
                                                            : cm.getService().getScanModel();

                                            // SearchAgent now handles scanning internally via execute()
                                            var scanConfig = SearchAgent.ScanConfig.withModel(scanModelToUse);
                                            var searchAgent = new LutzAgent(
                                                    context,
                                                    spec.taskInput(),
                                                    Objects.requireNonNull(
                                                            scanModelToUse, "scan model unavailable for SEARCH jobs"),
                                                    SearchPrompts.Objective.ANSWER_ONLY,
                                                    scope,
                                                    cm.getIo(),
                                                    scanConfig);
                                            var result = searchAgent.execute();
                                            scope.append(result);
                                        }
                                    }
                                    case REVIEW -> {
                                        // taskInput IS the diff directly
                                        String diff = spec.taskInput();

                                        try (var scope = cm.beginTaskUngrouped("Diff Review")) {
                                            // 1. Add diff to context
                                            var context = cm.liveContext();
                                            var diffFragment = new ContextFragments.StringFragment(
                                                    cm, diff, "Diff to Review", "text/x-diff", Set.of());
                                            context = context.addFragments(diffFragment);

                                            // 2. Pre-scan to load relevant context (LutzAgent.scanContext() uses ContextAgent internally)
                                            var scanModel = Objects.requireNonNull(
                                                    reviewScanModel, "scan model unavailable for REVIEW jobs");
                                            var searchAgent = new LutzAgent(
                                                    context,
                                                    "Review this diff",
                                                    scanModel,
                                                    SearchPrompts.Objective.ANSWER_ONLY,
                                                    scope);
                                            context = searchAgent.scanContext();

                                            // 3. Generate review using planner model
                                            var plannerModel = Objects.requireNonNull(
                                                    reviewPlannerModel, "planner model unavailable for REVIEW jobs");
                                            TaskResult result = reviewDiff(context, plannerModel, diff);
                                            scope.append(result);
                                        }
                                    }
                                    default -> throw new IllegalStateException("Unhandled job mode: " + mode);
                                }

                                completed.incrementAndGet();

                                try {
                                    JobStatus s = store.loadStatus(jobId);
                                    if (s != null) {
                                        store.updateStatus(jobId, s);
                                    }
                                } catch (Exception e) {
                                    logger.debug("Unable to update job {} for {}", jobId, e);
                                }
                            } catch (Exception e) {
                                logger.warn("Task execution failed for job {}: {}", jobId, e.getMessage());
                                // Continue to next task or exit depending on requirements
                                throw e;
                            }
                        })
                        .join();

                // Optional compress after execution:
                // - For ARCHITECT/LUTZ: per-task compression already honored via spec.autoCompress().
                if (mode != Mode.ARCHITECT && mode != Mode.LUTZ && spec.autoCompress()) {
                    logger.info("Job {} auto-compressing history", jobId);
                    cm.compressGlobalHistory();
                }

                // Determine final status: completed or cancelled
                JobStatus current = store.loadStatus(jobId);
                if (current != null) {
                    if (cancelled.get()) {
                        current = current.cancelled();
                        logger.info("Job {} marked as CANCELLED", jobId);
                    } else {
                        current = current.completed(completionResultHolder[0]);
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
                        // No-op: events are written synchronously, so nothing to await.
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

    /**
     * Build a single-shot workspace-only prompt using the provided planner model and return
     * a TaskResult representing the answer (or an error TaskResult on failure). This helper
     * is read-only and does not mutate workspace state.
     *
     * @param model the model to use for the single-shot answer
     * @param question the user's question / task text
     * @return a TaskResult suitable for appending to a TaskScope
     */
    private TaskResult askUsingPlannerModel(Context ctx, StreamingChatModel model, String question) {
        var svc = cm.getService();
        var meta = new TaskResult.TaskMeta(TaskResult.Type.ASK, Service.ModelConfig.from(model, svc));

        List<ChatMessage> messages;
        messages = SearchPrompts.instance.buildAskPrompt(ctx, question);
        // Create an LLM instance for the planner model and route output to the ContextManager IO
        var llm = cm.getLlm(new Llm.Options(model, "Answer: " + question).withEcho());
        llm.setOutput(cm.getIo());
        // Build and send the request to the LLM
        TaskResult.StopDetails stop = null;
        Llm.StreamingResult response = null;
        try {
            response = llm.sendRequest(messages);
        } catch (InterruptedException e) {
            stop = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
        }

        // Determine stop details based on the response
        if (response != null) {
            stop = TaskResult.StopDetails.fromResponse(response);
        }

        // construct TaskResult
        Objects.requireNonNull(stop);
        return new TaskResult(
                cm,
                "Ask: " + question,
                List.copyOf(cm.getIo().getLlmRawMessages()),
                ctx, // Ask never changes files; use current live context
                stop,
                meta);
    }

    /**
     * Generates a diff review using the planner model and returns the result as Markdown.
     *
     * @param ctx the current Context with workspace and diff
     * @param model the model to use for the review
     * @param diff the diff content to review
     * @return a TaskResult containing the review
     */
    private TaskResult reviewDiff(Context ctx, StreamingChatModel model, String diff) {
        var svc = cm.getService();
        var meta = new TaskResult.TaskMeta(TaskResult.Type.ASK, Service.ModelConfig.from(model, svc));

        String fencedDiff = "```diff\nDIFF_START\n" + diff + "\nDIFF_END\n```";

        String prompt =
                """
                You are performing a Pull Request diff review. The diff to review is provided
                *between the fenced code block marked DIFF_START and DIFF_END*.
                Everything inside that block is code - do not ignore any part of it.

                %s

                IMPORTANT: Line Number Format
                -----------------------------
                Each diff line is annotated with explicit OLD/NEW line numbers for your reference:

                - Added lines:   "[OLD:- NEW:N] +<content>" where N is the exact line number in the new file
                - Removed lines: "[OLD:N NEW:-] -<content>" where N is the exact line number in the old file
                - Context lines: "[OLD:N NEW:N]  <content>" where N/N are the exact line numbers in the old/new files

                When writing your review, cite line numbers using just the number (e.g., #L42), choosing the appropriate number:
                - For additions ("+"): use the NEW line number from the annotation
                - For deletions ("-"): use the OLD line number from the annotation
                - For context/unchanged lines (" "): use the NEW line number from the annotation

                Your task:
                Analyze ONLY the diff content above and any direct implications to the surrounding code visible in the diff.

                Return a concise Markdown response with EXACTLY the following sections (use the headings exactly as written):

                Summary
                --------
                Start this section with the exact line:
                ## Brokk PR Review

                Then provide: 1-3 sentences describing what changed and the key issues.

                Comments
                --------
                Use this exact format for detailed findings (one issue per bullet):
                - file://<path>#L<line> | Description of issue and why it matters. Provide a minimal actionable suggestion if relevant.
                - file://<path>#L<line> | Another issue.

                Rules:
                - Only analyze the diff content provided in DIFF_START/DIFF_END.
                - Do NOT say you cannot see the diff. It is inside the code block.
                - SKIP any line that has no issue. Do not comment on correct code.
                - Only output lines that describe actual problems, bugs, security issues, or code smells.
                - Never write comments like "this is correct", "looks good", "well done", "properly implemented", or any form of approval/praise.
                - If you cannot identify a concrete problem with a line, do not mention that line at all.
                - Every issue MUST be a single bullet line with EXACTLY this structure:
                  - file://<path>#L<line> | <description>
                - The file://<path>#L<line> part MUST be plain text:
                  - No Markdown formatting (no **bold**, no backticks, no links).
                  - No surrounding punctuation or extra characters.
                - The <line> MUST be the exact line number from the diff annotation (just the number):
                  - "+" lines: use the number from NEW
                  - "-" lines: use the number from OLD
                  - " " lines: use the number from NEW
                - The line MUST be a single integer (e.g. #L12), NOT a range (do not output #L12-L34).
                - Use exactly one | separator with spaces around it:  ... #L<line> | ...
                - Be precise and concise.
                - Include code snippets only when they clarify the fix.
                - If no issues are found, still describe what the PR changes in the Summary section, then state "No issues were found." Omit the Comments section entirely when there are no issues.

                Begin analysis now.
                """
                        .formatted(fencedDiff);

        List<ChatMessage> messages = List.of(new UserMessage(prompt));

        var llm = cm.getLlm(new Llm.Options(model, "Diff Review").withEcho());
        llm.setOutput(cm.getIo());

        TaskResult.StopDetails stop = null;
        Llm.StreamingResult response = null;
        try {
            response = llm.sendRequest(messages);
        } catch (InterruptedException e) {
            stop = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
        }

        if (response != null) {
            stop = TaskResult.StopDetails.fromResponse(response);
        }

        Objects.requireNonNull(stop);
        return new TaskResult(cm, "Diff Review", List.copyOf(cm.getIo().getLlmRawMessages()), ctx, stop, meta);
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
}
